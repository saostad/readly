package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
import app.readly.input.sites.AtomicJsonFileStore
import app.readly.input.sites.DefaultFileDeleter
import app.readly.input.sites.DefaultFileMover
import app.readly.input.sites.FileDeleter
import app.readly.input.sites.FileMover
import app.readly.input.sites.FixedOriginDownloader
import app.readly.input.sites.JsonRuleStorage
import app.readly.input.sites.RuleStatusException
import app.readly.input.sites.RuleStatusJson
import app.readly.input.sites.RuleUpdateResult
import app.readly.input.sites.RuleUpdateStatus
import app.readly.input.sites.StoreLabels
import app.readly.input.sites.VersionedRuleStore
import java.io.File
import java.io.IOException

/**
 * Reads and writes the cached NYT rule and update status. Same storage shape
 * as the shared engine; production uses [FileNytPolicyStorage] in an
 * app-private directory, host tests inject fakes.
 */
interface NytPolicyStorage : JsonRuleStorage

/** Legacy alias for the shared atomic-replace seam. */
typealias NytFileMover = FileMover

/** Legacy alias for the shared atomic-replace implementation. */
typealias NytDefaultFileMover = DefaultFileMover

/** Legacy alias for the shared temp-cleanup seam. */
typealias NytFileDeleter = FileDeleter

/** Legacy alias for the shared temp-cleanup implementation. */
typealias NytDefaultFileDeleter = DefaultFileDeleter

/**
 * File-backed [NytPolicyStorage] over the shared atomic JSON store: sibling
 * `.tmp` writes plus atomic replace, old file kept byte-for-byte on failure,
 * bounded reads. Same file names and error wording as before.
 */
class FileNytPolicyStorage(
    dir: File,
    mover: NytFileMover = NytDefaultFileMover,
    deleter: NytFileDeleter = NytDefaultFileDeleter,
) : NytPolicyStorage {
    private val core = AtomicJsonFileStore(dir, POLICY_NAME, STATUS_NAME, "NYT rule", mover, deleter)

    override fun readPolicyJson(): String? = core.readPolicyJson()
    override fun writePolicyJson(json: String) = core.writePolicyJson(json)
    override fun readStatusJson(): String? = core.readStatusJson()
    override fun writeStatusJson(json: String) = core.writeStatusJson(json)

    companion object {
        const val POLICY_NAME = "nyt_policy.json"
        const val STATUS_NAME = "nyt_status.json"
    }
}

/**
 * Downloads one update URL. Production uses [NytHttpDownloader]; tests
 * inject fakes, so unit tests never touch the network.
 */
fun interface NytUpdateDownloader {
    @Throws(IOException::class)
    fun download(url: String): ByteArray
}

/**
 * [NytUpdateDownloader] over the shared fixed-origin downloader (defaulting
 * to production [ArticleFetcher.httpUrlOpener] timeouts). The start URL must
 * be exactly [NytPolicy.OFFICIAL_UPDATE_URL], and every redirect is
 * validated: HTTPS only (no downgrade), same host (no off-origin), no
 * credentials, no odd ports. Bodies are bounded to
 * [NytRuleParser.MAX_COMPRESSED_BYTES].
 */
class NytHttpDownloader(
    opener: (String) -> FetchConnection = ArticleFetcher::httpUrlOpener,
) : NytUpdateDownloader {
    private val core = FixedOriginDownloader(
        opener,
        NytPolicy.OFFICIAL_UPDATE_URL,
        "NYT rule update",
        NytRuleParser.MAX_COMPRESSED_BYTES,
    )

    companion object {
        const val MAX_REDIRECTS = FixedOriginDownloader.MAX_REDIRECTS
    }

    override fun download(url: String): ByteArray = core.download(url)
}

/** Legacy alias for the shared update-status model. */
typealias NytUpdateStatus = RuleUpdateStatus

/**
 * Outcome of one [NytPolicyStore.checkForUpdates] call. A real sealed class
 * (not an alias): Kotlin cannot name shared subclasses through an alias, so
 * the wrapper converts engine results into these identical cases.
 */
sealed class NytUpdateResult {
    abstract val status: NytUpdateStatus

    /** An attempt ran: [changed] tells whether the rule itself is new. */
    data class Checked(override val status: NytUpdateStatus, val changed: Boolean) : NytUpdateResult()

    /** An auto check was skipped by the 24-hour throttle; nothing ran. */
    data class Throttled(override val status: NytUpdateStatus) : NytUpdateResult()

    /** The attempt failed with [error]; the previous policy is untouched. */
    data class Failed(override val status: NytUpdateStatus, val error: String) : NytUpdateResult()
}

private fun RuleUpdateResult.toNyt(): NytUpdateResult = when (this) {
    is RuleUpdateResult.Checked -> NytUpdateResult.Checked(status, changed)
    is RuleUpdateResult.Throttled -> NytUpdateResult.Throttled(status)
    is RuleUpdateResult.Failed -> NytUpdateResult.Failed(status, error)
}

/**
 * Legacy status codec over the shared implementation: same bytes, same
 * `"Cached NYT status"` wording, same [NytPolicyException] failure type.
 */
internal object NytStatusJson {
    fun encode(status: NytUpdateStatus): String = RuleStatusJson.encode(status)

    @Throws(NytPolicyException::class)
    fun decode(json: String): NytUpdateStatus {
        try {
            return RuleStatusJson.decode(json, "Cached NYT status")
        } catch (e: RuleStatusException) {
            throw NytPolicyException(e.message ?: "Cached NYT status JSON is invalid", e)
        }
    }
}

/**
 * Atomic last-working NYT rule store with a daily updater, over the one
 * proven [VersionedRuleStore] engine. Public API, persistence format, and
 * every message are unchanged; production now runs the shared catalog store
 * instead, and this class remains as the tested legacy wrapper plus the
 * read-only migration source.
 *
 * Behavior: starts with the newer of the bundled and cached policies
 * (equal prefers the verified cache); a corrupt or unreadable cache falls
 * back to bundled with [startupNotice] naming the real error. Status loads
 * independently so a failed first check still throttles after restart.
 * [checkForUpdates] downloads, validates and atomically stores the rule;
 * any download/parse/write failure keeps the prior policy and reports
 * [NytUpdateResult.Failed]. Auto checks run at most once per 24 hours
 * (failures count); manual checks bypass the throttle and still move the
 * window. Version downgrades are rejected numerically, while a
 * same-version changed rule is accepted when valid. Concurrent refreshes are
 * serialized; [policy] always returns a complete immutable policy without
 * waiting for any refresh.
 *
 * Threading: safe to call from any thread, but [checkForUpdates] does
 * network and disk I/O — run it off the main thread (or use
 * [startUpdateCheck], which uses a daemon thread).
 *
 * @param bundledJson the bundled asset text (`nyt_policy.json`); an invalid
 *   value throws [IllegalArgumentException], since that is a shipping bug.
 */
class NytPolicyStore(
    bundledJson: String,
    storage: NytPolicyStorage,
    downloader: NytUpdateDownloader,
    clock: () -> Long = System::currentTimeMillis,
    onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) {
    companion object {
        /** Minimum gap between automatic update attempts, including failures. */
        const val AUTO_CHECK_INTERVAL_MS = VersionedRuleStore.AUTO_CHECK_INTERVAL_MS

        internal val LABELS = StoreLabels(
            bundledWhat = "Bundled NYT policy",
            cachedNoun = "Cached NYT rule",
            cachedWhat = "Cached NYT policy",
            bundledNoun = "bundled rule",
            artifactNoun = "policy",
            statusNoun = "Cached NYT status",
            updateNoun = "NYT rule update",
            errorPrefix = "NYT rule",
            threadName = "readly-nyt-update",
        )
    }

    private val core = VersionedRuleStore(
        bundledJson,
        storage,
        download = { url -> downloader.download(url) },
        NytPolicy.OFFICIAL_UPDATE_URL,
        LABELS,
        decodePolicy = { json, what -> NytPolicyJson.decode(json, what) },
        encodePolicy = NytPolicyJson::encode,
        versionOf = NytPolicy::sourceVersion,
        compareVersions = NytPolicy::compareVersions,
        parseUpdate = { bytes, sha, url -> NytRuleParser.parseZip(bytes, sha, url) },
        isPolicyError = { it is NytPolicyException },
        sameContent = { candidate, current -> candidate.sameRule(current) },
        clock = clock,
        onWarn = onWarn,
    )

    /**
     * Non-null when cache/status storage degraded at startup (corrupt or
     * unreadable); names the real error. Null on a clean start, including a
     * normal app upgrade where bundled rules are newer than the cache.
     */
    val startupNotice: String?
        get() = core.startupNotice

    /** The current complete policy; never partial update state. Never blocks on updates. */
    fun policy(): NytPolicy = core.policy()

    /** The current update status for next round's UI. Never blocks on updates. */
    fun status(): NytUpdateStatus = core.status()

    /**
     * Runs one update attempt synchronously (network + disk; call off the
     * main thread). [manual] bypasses the 24-hour auto throttle.
     */
    fun checkForUpdates(manual: Boolean = false): NytUpdateResult = core.checkForUpdates(manual).toNyt()

    /**
     * Starts [checkForUpdates] on a daemon thread and delivers the result to
     * [onDone] on that same thread.
     */
    fun startUpdateCheck(manual: Boolean = false, onDone: (NytUpdateResult) -> Unit = {}) =
        core.startUpdateCheck(manual) { onDone(it.toNyt()) }
}
