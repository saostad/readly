package app.readly.input.sites

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads and writes one cached rule document and its update status. Production
 * uses [AtomicJsonFileStore] in an app-private directory; host tests inject
 * fakes, never Android storage.
 */
interface JsonRuleStorage {
    /** Cached rule JSON, or null when no update has ever been stored. */
    fun readPolicyJson(): String?

    /** Atomically replaces the cached rule JSON. */
    fun writePolicyJson(json: String)

    /** Cached status JSON, or null when never stored. */
    fun readStatusJson(): String?

    /** Atomically replaces the cached status JSON (best-effort for callers). */
    fun writeStatusJson(json: String)
}

/**
 * Update status for the settings UI: where the current rules came from, their
 * version, and when checks last ran, succeeded, or failed with what error.
 */
data class RuleUpdateStatus(
    /** [ORIGIN_BUNDLED] or [ORIGIN_CACHE]. */
    val origin: String,
    /** Upstream version of the current rules. */
    val version: String,
    /** `clock()` at the last attempt (manual or auto), 0 when never checked. */
    val lastCheckMs: Long,
    /** `clock()` at the last success, 0 when never succeeded. */
    val lastSuccessMs: Long,
    /** Error of the last failed attempt, null when the last attempt succeeded. */
    val lastError: String?,
    /**
     * Transient warning that the last status write failed. Separate from
     * [lastError] so a status-persistence failure never rewrites the update
     * outcome; never persisted to disk (it only exists when a write failed).
     * UI surfaces this alongside [lastError].
     */
    val warning: String? = null,
) {
    companion object {
        const val ORIGIN_BUNDLED = "bundled"
        const val ORIGIN_CACHE = "cache"
    }
}

/** Any failure to decode shared update-status JSON. The message names the operation. */
class RuleStatusException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Strict status codec shared by every rule store; [what] names the file (`"Cached … status"`). */
object RuleStatusJson {
    const val SCHEMA = 1

    /**
     * Encodes the persisted status fields. [RuleUpdateStatus.warning] is
     * transient and never written: it only exists when a write just failed.
     */
    fun encode(status: RuleUpdateStatus): String {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("origin", status.origin)
        o.put("version", status.version)
        o.put("lastCheckMs", status.lastCheckMs)
        o.put("lastSuccessMs", status.lastSuccessMs)
        if (status.lastError == null) {
            o.put("lastError", JSONObject.NULL)
        } else {
            o.put("lastError", status.lastError)
        }
        return o.toString()
    }

    @Throws(RuleStatusException::class)
    fun decode(json: String, what: String): RuleUpdateStatus {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw RuleStatusException(
                "$what JSON is invalid: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        fun fail(detail: String): Nothing = throw RuleStatusException("$what JSON is invalid: $detail")
        val schema = o.opt("schema")
        val ok = (schema is Int || schema is Long) && schema.toLong() == SCHEMA.toLong()
        if (!ok) fail("unsupported schema ${UpdateArchive.describeJsonValue(schema)}")
        val origin = o.opt("origin")
        if (origin !is String ||
            (origin != RuleUpdateStatus.ORIGIN_BUNDLED && origin != RuleUpdateStatus.ORIGIN_CACHE)
        ) {
            fail("unexpected \"origin\" ${UpdateArchive.describeJsonValue(origin)}")
        }
        val version = o.opt("version")
        if (version !is String || !SiteVersions.isValidVersion(version)) {
            fail("invalid \"version\" ${UpdateArchive.describeJsonValue(version)}")
        }
        val lastCheck = o.opt("lastCheckMs")
        if (lastCheck !is Number || lastCheck.toLong() < 0) {
            fail("invalid \"lastCheckMs\" ${UpdateArchive.describeJsonValue(lastCheck)}")
        }
        val lastSuccess = o.opt("lastSuccessMs")
        if (lastSuccess !is Number || lastSuccess.toLong() < 0) {
            fail("invalid \"lastSuccessMs\" ${UpdateArchive.describeJsonValue(lastSuccess)}")
        }
        val lastError = when (val v = o.opt("lastError")) {
            null, JSONObject.NULL -> null
            is String -> v
            else -> fail("\"lastError\" must be a string or null")
        }
        return RuleUpdateStatus(origin, version, lastCheck.toLong(), lastSuccess.toLong(), lastError)
    }
}

/** Outcome of one [VersionedRuleStore.checkForUpdates] call. */
sealed class RuleUpdateResult {
    abstract val status: RuleUpdateStatus

    /** An attempt ran: [changed] tells whether the rules themselves are new. */
    data class Checked(override val status: RuleUpdateStatus, val changed: Boolean) : RuleUpdateResult()

    /** An auto check was skipped by the 24-hour throttle; nothing ran. */
    data class Throttled(override val status: RuleUpdateStatus) : RuleUpdateResult()

    /** The attempt failed with [error]; the previous rules are untouched. */
    data class Failed(override val status: RuleUpdateStatus, val error: String) : RuleUpdateResult()
}

/**
 * Message nouns for one [VersionedRuleStore] owner. Every user-visible string
 * the engine emits is built from these, so the NYT legacy store and the
 * shared catalog store keep their own exact wording over one engine.
 */
data class StoreLabels(
    /** `"Bundled NYT policy"` / `"Bundled site catalog"`. */
    val bundledWhat: String,
    /** `"Cached NYT rule"` / `"Cached site catalog"`. */
    val cachedNoun: String,
    /** Decode context: `"Cached NYT policy"` / `"Cached site catalog"`. */
    val cachedWhat: String,
    /** `"bundled rule"` / `"bundled catalog"`. */
    val bundledNoun: String,
    /** `"policy"` / `"catalog"` (stale-status reconciliation message). */
    val artifactNoun: String,
    /** `"Cached NYT status"` / `"Cached site status"`. */
    val statusNoun: String,
    /** `"NYT rule update"` / `"Site rule update"`. */
    val updateNoun: String,
    /** `"NYT rule"` / `"Site rule"` (passthrough prefix for pipeline errors). */
    val errorPrefix: String,
    /** Background-thread name for [VersionedRuleStore.startUpdateCheck]. */
    val threadName: String,
)

/**
 * Throttle state migrated from a legacy store when no new status file
 * exists: the last attempt time and error carry over so a failed first check
 * still throttles after restart. There is deliberately no success time: a
 * legacy success downloaded a different artifact, so the engine records no
 * success for the current rules. [notice] (non-null only when legacy status
 * storage degraded) joins the startup notice.
 */
data class MigratedThrottle(
    val lastCheckMs: Long,
    val lastError: String?,
    val notice: String?,
)

/**
 * Atomic last-working rule store with a daily updater. Pure JVM core: the
 * bundled rules arrive as a JSON string, storage/download/clock/warnings are
 * injected, so host tests never call Android stubs. This is the one proven
 * engine: the NYT store keeps its exact public API and messages as a thin
 * wrapper over it, and the shared catalog store adds candidate validation
 * plus legacy migration through the hooks below.
 *
 * Behavior: starts with the newer of the bundled and cached rules (equal
 * prefers the verified cache); a corrupt, unreadable, or usability-rejected
 * cache falls back to bundled with [startupNotice] naming the real error. Status loads
 * independently so a failed first check still throttles after restart.
 * [checkForUpdates] downloads, validates and atomically stores the rules;
 * any download/parse/validation/write failure keeps the prior rules and
 * reports [RuleUpdateResult.Failed]. Auto checks run at most once per 24
 * hours (failures count); manual checks bypass the throttle and still move
 * the window. Version downgrades are rejected numerically, while a
 * same-version changed rule is accepted when valid. Concurrent refreshes are
 * serialized; [policy] always returns complete immutable rules without
 * waiting for any refresh.
 *
 * Threading: safe to call from any thread, but [checkForUpdates] does
 * network and disk I/O — run it off the main thread (or use
 * [startUpdateCheck], which uses a daemon thread).
 *
 * @param P immutable rule type ([app.readly.input.nyt.NytPolicy], [SiteCatalog]).
 * @param bundledJson the bundled asset text; an invalid value throws
 *   [IllegalArgumentException], since that is a shipping bug.
 * @param validateCandidate extra per-candidate gate (null accepts anything
 *   the parser produced): returns a rejection reason, or null when the
 *   candidate is usable. Runs after parsing, before version comparison.
 * @param migratedThrottle legacy throttle consulted only when no new status
 *   file exists (first run after migration); null starts clean.
 */
class VersionedRuleStore<P : Any>(
    bundledJson: String,
    private val storage: JsonRuleStorage,
    private val download: (String) -> ByteArray,
    private val officialUrl: String,
    private val labels: StoreLabels,
    private val decodePolicy: (json: String, what: String) -> P,
    private val encodePolicy: (P) -> String,
    private val versionOf: (P) -> String,
    private val compareVersions: (String, String) -> Int,
    private val parseUpdate: (bytes: ByteArray, sha: String, url: String) -> P,
    private val isPolicyError: (Throwable) -> Boolean,
    private val sameContent: (candidate: P, current: P) -> Boolean,
    private val validateCandidate: ((P) -> String?)? = null,
    private val migratedThrottle: (() -> MigratedThrottle?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) {
    companion object {
        /** Minimum gap between automatic update attempts, including failures. */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    private val stateLock = Any()
    private val updateLock = Any()
    private var current: P
    private var status: RuleUpdateStatus

    /**
     * Non-null when cache/status storage degraded at startup (corrupt or
     * unreadable); names the real error. Null on a clean start, including a
     * normal app upgrade where bundled rules are newer than the cache.
     */
    val startupNotice: String?

    init {
        val bundled = try {
            decodePolicy(bundledJson, labels.bundledWhat)
        } catch (e: Exception) {
            throw IllegalArgumentException("${labels.bundledWhat} is invalid: ${e.message}", e)
        }
        // A bundled artifact that decodes but fails the usability gate is a
        // shipping bug: fail fast with the real reason instead of starting
        // with rules the updater itself would reject.
        validateCandidate?.invoke(bundled)?.let { reason ->
            throw IllegalArgumentException("${labels.bundledWhat} is invalid: $reason")
        }
        var policyNotice: String? = null
        var cached: P? = null
        val cachedJson = try {
            storage.readPolicyJson()
        } catch (e: Exception) {
            policyNotice = "${labels.cachedNoun} unreadable, using ${labels.bundledNoun} " +
                "${versionOf(bundled)}: ${e.javaClass.name}: ${e.message}"
            null
        }
        if (cachedJson != null && policyNotice == null) {
            try {
                cached = decodePolicy(cachedJson, labels.cachedWhat)
            } catch (e: Exception) {
                policyNotice = "${labels.cachedNoun} corrupt, using ${labels.bundledNoun} " +
                    "${versionOf(bundled)}: ${e.message}"
            }
            // The same usability gate as downloads: a schema-valid but
            // unusable cache (empty, directive-less, NYT-withheld) must not
            // be selected by version alone.
            if (cached != null) {
                validateCandidate?.invoke(cached)?.let { reason ->
                    policyNotice = "${labels.cachedNoun} rejected: $reason, " +
                        "using ${labels.bundledNoun} ${versionOf(bundled)}"
                    cached = null
                }
            }
        }
        // Select the newer validated rules so an app upgrade with fresher
        // bundled rules wins over an older cache; a newer or equal cache is
        // retained. Equal versions prefer the cache, which was verified from
        // a downloaded update.
        val currentOrigin: String
        if (cached != null &&
            compareVersions(versionOf(cached), versionOf(bundled)) >= 0
        ) {
            current = cached
            currentOrigin = RuleUpdateStatus.ORIGIN_CACHE
        } else {
            current = bundled
            currentOrigin = RuleUpdateStatus.ORIGIN_BUNDLED
        }
        if (policyNotice != null) onWarn(policyNotice)
        val (loadedStatus, statusNotice) = loadStatus(versionOf(current), currentOrigin)
        status = loadedStatus
        startupNotice = when {
            policyNotice != null && statusNotice != null -> "$policyNotice; $statusNotice"
            else -> policyNotice ?: statusNotice
        }
    }

    /** The current complete rules; never partial update state. Never blocks on updates. */
    fun policy(): P = synchronized(stateLock) { current }

    /** The current update status for the settings UI. Never blocks on updates. */
    fun status(): RuleUpdateStatus = synchronized(stateLock) { status }

    /**
     * Runs one update attempt synchronously (network + disk; call off the
     * main thread). [manual] bypasses the 24-hour auto throttle.
     *
     * Only one refresh runs at once ([updateLock]); [policy] and [status]
     * use a short separate [stateLock] and never wait for downloads,
     * parsing, disk writes, or callbacks.
     */
    fun checkForUpdates(manual: Boolean = false): RuleUpdateResult {
        synchronized(updateLock) {
            val now = clock()
            val snapshotStatus = synchronized(stateLock) { status }
            val snapshotPolicy = synchronized(stateLock) { current }
            if (!manual && snapshotStatus.lastCheckMs != 0L &&
                now - snapshotStatus.lastCheckMs < AUTO_CHECK_INTERVAL_MS
            ) {
                return RuleUpdateResult.Throttled(snapshotStatus)
            }
            try {
                val bytes = download(officialUrl)
                val sha = sha256Hex(bytes)
                val candidate = parseUpdate(bytes, sha, officialUrl)
                validateCandidate?.invoke(candidate)?.let { reason ->
                    return fail(
                        now,
                        "${labels.updateNoun} rejected: $reason, " +
                            "keeping ${versionOf(snapshotPolicy)}",
                    )
                }
                if (compareVersions(versionOf(candidate), versionOf(snapshotPolicy)) < 0) {
                    return fail(
                        now,
                        "${labels.updateNoun} rejected: candidate version " +
                            "${versionOf(candidate)} is older than current " +
                            versionOf(snapshotPolicy),
                    )
                }
                if (candidate == snapshotPolicy) {
                    val updated = snapshotStatus.copy(
                        lastCheckMs = now,
                        lastSuccessMs = now,
                        lastError = null,
                        warning = null,
                    )
                    synchronized(stateLock) { status = updated }
                    val published = persistStatusReturning(updated)
                    return RuleUpdateResult.Checked(published, changed = false)
                }
                val encoded = encodePolicy(candidate)
                try {
                    storage.writePolicyJson(encoded)
                } catch (e: Exception) {
                    return fail(
                        now,
                        "${labels.updateNoun} downloaded version ${versionOf(candidate)} but the " +
                            "cache write failed, keeping ${versionOf(snapshotPolicy)}: " +
                            "${e.javaClass.name}: ${e.message}",
                    )
                }
                val changed = !sameContent(candidate, snapshotPolicy)
                val newStatus = RuleUpdateStatus(
                    RuleUpdateStatus.ORIGIN_CACHE,
                    versionOf(candidate),
                    now,
                    now,
                    null,
                )
                synchronized(stateLock) {
                    current = candidate
                    status = newStatus
                }
                val published = persistStatusReturning(newStatus)
                return RuleUpdateResult.Checked(published, changed = changed)
            } catch (e: IOException) {
                // Our own pipeline errors already name the operation; bare
                // transport errors gain the operation plus the real class.
                val msg = e.message
                return if (msg != null && msg.startsWith(labels.errorPrefix)) {
                    fail(now, msg)
                } else {
                    fail(now, "${labels.updateNoun} check failed: ${e.javaClass.name}: ${e.message}")
                }
            } catch (e: Exception) {
                if (isPolicyError(e)) {
                    return fail(now, e.message ?: "${labels.updateNoun} failed: ${e.javaClass.name}")
                }
                return fail(now, "${labels.updateNoun} check failed: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    /**
     * Starts [checkForUpdates] on a daemon thread and delivers the result to
     * [onDone] on that same thread.
     */
    fun startUpdateCheck(manual: Boolean = false, onDone: (RuleUpdateResult) -> Unit = {}) {
        val thread = Thread({
            val result = checkForUpdates(manual)
            try {
                onDone(result)
            } catch (e: Exception) {
                onWarn("${labels.updateNoun} callback failed: ${e.javaClass.name}: ${e.message}")
            }
        }, labels.threadName)
        thread.isDaemon = true
        thread.start()
    }

    private fun fail(now: Long, error: String): RuleUpdateResult.Failed {
        // lastSuccessMs and the policy version stay at the last success.
        // The state lock is held only for the immutable swap; disk and
        // callbacks run outside it so readers never block.
        val updated = synchronized(stateLock) {
            val next = status.copy(lastCheckMs = now, lastError = error, warning = null)
            status = next
            next
        }
        val published = persistStatusReturning(updated)
        onWarn(error)
        return RuleUpdateResult.Failed(published, error)
    }

    /**
     * Status writes are best-effort: they never turn a success into a
     * failure. On failure the policy, version and success times stay as
     * written in memory, and the concrete error is attached as a transient
     * [RuleUpdateStatus.warning] so settings can show it; the returned status
     * is the published in-memory value in both cases.
     */
    private fun persistStatusReturning(toWrite: RuleUpdateStatus): RuleUpdateStatus {
        try {
            storage.writeStatusJson(RuleStatusJson.encode(toWrite))
        } catch (e: Exception) {
            val warning = "${labels.errorPrefix} status write failed: ${e.javaClass.name}: ${e.message}"
            onWarn(warning)
            synchronized(stateLock) {
                status = status.copy(warning = warning)
                return status
            }
        }
        return toWrite
    }

    /**
     * Loads persisted status independently of any cached policy, so a failed
     * first check still throttles after restart. Returns the reconciled
     * status plus a startup notice when storage degraded (null on clean or
     * version-reconciled loads). A version mismatch preserves the throttle
     * ([RuleUpdateStatus.lastCheckMs] and `lastError`) but resets
     * [RuleUpdateStatus.lastSuccessMs] rather than inventing a success for
     * rules that were never downloaded. When no new status file exists, the
     * legacy throttle (if any) carries the last attempt across migration.
     */
    private fun loadStatus(
        currentVersion: String,
        currentOrigin: String,
    ): Pair<RuleUpdateStatus, String?> {
        val json = try {
            storage.readStatusJson()
        } catch (e: Exception) {
            val notice =
                "${labels.statusNoun} unreadable, using defaults: ${e.javaClass.name}: ${e.message}"
            onWarn(notice)
            return Pair(
                RuleUpdateStatus(currentOrigin, currentVersion, 0L, 0L, null),
                notice,
            )
        } ?: run {
            val migrated = migratedThrottle?.invoke()
            if (migrated != null) {
                if (migrated.notice != null) onWarn(migrated.notice)
                return Pair(
                    RuleUpdateStatus(
                        currentOrigin,
                        currentVersion,
                        migrated.lastCheckMs,
                        0L,
                        migrated.lastError,
                    ),
                    migrated.notice,
                )
            }
            return Pair(
                RuleUpdateStatus(currentOrigin, currentVersion, 0L, 0L, null),
                null,
            )
        }
        return try {
            val parsed = RuleStatusJson.decode(json, labels.statusNoun)
            if (parsed.version != currentVersion) {
                onWarn(
                    "${labels.statusNoun} is stale (status ${parsed.version}, " +
                        "${labels.artifactNoun} $currentVersion), reconciling throttle",
                )
                Pair(
                    RuleUpdateStatus(
                        currentOrigin,
                        currentVersion,
                        parsed.lastCheckMs,
                        0L,
                        parsed.lastError,
                    ),
                    null,
                )
            } else {
                Pair(parsed.copy(origin = currentOrigin), null)
            }
        } catch (e: RuleStatusException) {
            val notice = "${labels.statusNoun} corrupt, using defaults: ${e.message}"
            onWarn(notice)
            Pair(RuleUpdateStatus(currentOrigin, currentVersion, 0L, 0L, null), notice)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
    }
}
