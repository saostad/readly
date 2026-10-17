package app.readly.input.sites

import app.readly.input.FetchConnection
import app.readly.input.nyt.NytPolicy
import app.readly.input.nyt.NytPolicyJson
import app.readly.input.nyt.NytPolicyStorage
import app.readly.input.nyt.NytStatusJson
import app.readly.input.nyt.NytTestFixtures.policyJson
import app.readly.input.sites.SiteTestFixtures.SHA
import app.readly.input.sites.SiteTestFixtures.URL
import app.readly.input.sites.SiteTestFixtures.catalogJson
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.nytEntry
import app.readly.input.sites.SiteTestFixtures.twoRuleSitesJs
import app.readly.input.sites.SiteTestFixtures.updateZip
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Shared-catalog store over the one proven engine: bundled/cache selection,
 * candidate gates (empty, directive-less, NYT-missing), downgrade rejection,
 * atomic-write failures, throttle persistence, and non-destructive legacy
 * NYT migration. No Android, no network.
 */
class SiteCatalogStoreTest {

    private val warnings = mutableListOf<String>()
    private var now = 1_000_000L
    private val clock = { now }

    private class FakeStorage(
        var policy: String? = null,
        var status: String? = null,
        var failPolicyWrite: Exception? = null,
    ) : JsonRuleStorage {
        var policyWrites = 0
        override fun readPolicyJson(): String? = policy
        override fun writePolicyJson(json: String) {
            failPolicyWrite?.let { throw it }
            policy = json
            policyWrites++
        }
        override fun readStatusJson(): String? = status
        override fun writeStatusJson(json: String) { status = json }
    }

    /**
     * Legacy NYT storage. Writes throw: migration is read-only, so any write
     * attempt is a loud test failure, not a silent legacy mutation.
     */
    private class FakeLegacy(
        var policy: String? = null,
        var status: String? = null,
        var failPolicyRead: Exception? = null,
        var failStatusRead: Exception? = null,
    ) : NytPolicyStorage {
        override fun readPolicyJson(): String? {
            failPolicyRead?.let { throw it }
            return policy
        }
        override fun writePolicyJson(json: String): Nothing =
            throw AssertionError("legacy policy must never be written")
        override fun readStatusJson(): String? {
            failStatusRead?.let { throw it }
            return status
        }
        override fun writeStatusJson(json: String): Nothing =
            throw AssertionError("legacy status must never be written")
    }

    private class FakeDownloader(var action: () -> ByteArray) {
        var calls = 0
        fun download(url: String): ByteArray {
            calls++
            assertEquals(URL, url)
            return action()
        }
    }

    private fun bundled(): String = catalogJson(nytEntry())

    private fun legacyPolicy(version: String, userAgent: String = "Legacy-UA/1.0"): String =
        policyJson(NytPolicy(userAgent, true, null, version, SHA, NytPolicy.OFFICIAL_UPDATE_URL))

    private fun store(
        bundledJson: String = bundled(),
        storage: FakeStorage = FakeStorage(),
        legacy: FakeLegacy = FakeLegacy(),
        downloader: FakeDownloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") },
    ): SiteCatalogStore =
        SiteCatalogStore(bundledJson, storage, legacy, downloader::download, clock, warnings::add)

    // ------------------------------------------------------------------
    // Fresh install and shared-cache priority
    // ------------------------------------------------------------------

    @Test
    fun freshInstall_usesBundledSilently() {
        val s = store()
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertNull(s.startupNotice)
        assertNull("no legacy override on a fresh install", s.legacyOverride())
        assertEquals(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_BUNDLED, "4.4.7.4", 0L, 0L, null),
            s.status(),
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun invalidBundled_throws() {
        try {
            store(bundledJson = "{oops")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message ?: "", "Bundled site catalog is invalid" in (e.message ?: ""))
            return
        }
        fail("expected IllegalArgumentException")
    }

    @Test
    fun newerSharedCacheWinsAndOlderLoses() {
        val newer = catalogJson(
            nytEntry(),
            entry("New", "    domain: \"new.example.com\",\n    useragent: \"bingbot\""),
        ).let { json ->
            // Rewrite the cached version without re-importing: same ruleset.
            json.replace("\"version\": \"4.4.7.4\"", "\"version\": \"4.4.7.9\"")
        }
        val s = store(storage = FakeStorage(policy = newer))
        assertEquals("4.4.7.9", s.policy().meta.version)
        assertEquals(RuleUpdateStatus.ORIGIN_CACHE, s.status().origin)
        assertNull(s.startupNotice)

        val older = catalogJson(nytEntry()).replace("\"version\": \"4.4.7.4\"", "\"version\": \"1.0\"")
        val upgraded = store(storage = FakeStorage(policy = older))
        assertEquals("4.4.7.4", upgraded.policy().meta.version)
        assertEquals(RuleUpdateStatus.ORIGIN_BUNDLED, upgraded.status().origin)
        assertNull(upgraded.startupNotice)
    }

    @Test
    fun corruptSharedCache_fallsBackWithNotice() {
        val s = store(storage = FakeStorage(policy = "{oops"))
        assertEquals("4.4.7.4", s.policy().meta.version)
        val notice = s.startupNotice
        assertTrue("notice was: $notice", notice != null && "bundled catalog" in notice)
        assertEquals(listOf(notice), warnings)
    }

    private fun rejectedCacheNotice(policy: String, vararg fragments: String): String? {
        val s = store(storage = FakeStorage(policy = policy))
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertEquals(RuleUpdateStatus.ORIGIN_BUNDLED, s.status().origin)
        val notice = s.startupNotice
        assertTrue(
            "notice was: $notice",
            notice != null && fragments.all { it in notice },
        )
        assertTrue(warnings.any { it == notice })
        return notice
    }

    @Test
    fun newerEmptyCache_rejectedWithNotice() {
        // Schema-valid version 9.0 with zero rules: version alone must not
        // select it (the download gate never sees cached data).
        val cached = JSONObject(catalogJson(nytEntry()))
        cached.getJSONObject("meta").put("version", "9.0")
        cached.put("rules", JSONArray())
        rejectedCacheNotice(
            cached.toString(),
            "Cached site catalog rejected",
            "has no rules",
            "using bundled catalog 4.4.7.4",
        )
    }

    @Test
    fun newerDirectiveLessCache_rejectedWithNotice() {
        val cached = catalogJson(
            entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
        ).replace("\"version\": \"4.4.7.4\"", "\"version\": \"9.0\"")
        rejectedCacheNotice(
            cached,
            "Cached site catalog rejected",
            "no usable request or cleanup",
            "using bundled catalog 4.4.7.4",
        )
    }

    @Test
    fun newerNytWithheldCache_rejectedWithNotice() {
        val cached = catalogJson(
            entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
        ).replace("\"version\": \"4.4.7.4\"", "\"version\": \"9.0\"")
        rejectedCacheNotice(
            cached,
            "Cached site catalog rejected",
            "NYT",
            "using bundled catalog 4.4.7.4",
        )
    }

    @Test
    fun gatedBundled_rejectedFatally() {
        // A bundled catalog the usability gate would reject is a shipping
        // bug: fail fast with the real reason, never start gated.
        try {
            store(
                bundledJson = catalogJson(
                    entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
                ),
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message was: ${e.message}",
                "Bundled site catalog is invalid" in (e.message ?: "") &&
                    "NYT" in (e.message ?: ""),
            )
        }
    }

    // ------------------------------------------------------------------
    // Candidate gates
    // ------------------------------------------------------------------

    private fun failedResult(s: SiteCatalogStore): RuleUpdateResult.Failed {
        val result = s.checkForUpdates(manual = true)
        assertTrue("result was: $result", result is RuleUpdateResult.Failed)
        return result as RuleUpdateResult.Failed
    }

    @Test
    fun emptyCandidate_rejected() {
        val zip = updateZip(
            SiteTestFixtures.sitesJs(entry("Settings", "    domain: \"###\"")),
            "4.4.7.5",
        )
        val s = store(downloader = FakeDownloader { zip })
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "has no rules" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
    }

    @Test
    fun directiveLessCandidate_rejected() {
        val zip = updateZip(
            SiteTestFixtures.sitesJs(
                entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
            ),
            "4.4.7.5",
        )
        val s = store(downloader = FakeDownloader { zip })
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "no usable request or cleanup" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
    }

    @Test
    fun nytMissingCandidate_rejected() {
        val zip = updateZip(
            SiteTestFixtures.sitesJs(
                entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
            ),
            "4.4.7.5",
        )
        val s = store(downloader = FakeDownloader { zip })
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "NYT" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertEquals("4.4.7.4", s.status().version)
    }

    @Test
    fun nytWithheldCandidate_rejected() {
        // NYT's restrictions cannot be honored: its domains are barred, so
        // the gate sees a withheld NYT rule and rejects the candidate.
        val excluded = (0..256).joinToString(", ") { "\"s$it.example.com\"" }
        val zip = updateZip(
            SiteTestFixtures.sitesJs(
                entry(
                    "The New York Times",
                    "    domain: \"nytimes.com\",\n" +
                        "    useragent_custom: \"UA-NEW\",\n" +
                        "    excluded_domains: [$excluded]",
                ),
                entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
            ),
            "4.4.7.5",
        )
        val s = store(downloader = FakeDownloader { zip })
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "NYT" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
    }

    @Test
    fun nytWithoutUaCandidate_rejected() {
        // headers_filter withholds the NYT request headers: the gate
        // requires a usable NYT User-Agent.
        val zip = updateZip(
            SiteTestFixtures.sitesJs(
                entry(
                    "The New York Times",
                    "    domain: \"nytimes.com\",\n" +
                        "    useragent_custom: \"UA-NEW\",\n" +
                        "    headers_filter: [\"https://nytimes.com/*\"]",
                ),
                entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
            ),
            "4.4.7.5",
        )
        val s = store(downloader = FakeDownloader { zip })
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "User-Agent" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
    }

    @Test
    fun validNytUaUpdate_accepted() {
        // No hardcoded forever-UA: a new supported NYT UA applies normally.
        val zip = updateZip(twoRuleSitesJs(nytUa = "UA-NEW"), "4.4.7.5")
        val storage = FakeStorage()
        val s = store(storage = storage, downloader = FakeDownloader { zip })
        val result = s.checkForUpdates(manual = true)
        assertTrue("result was: $result", result is RuleUpdateResult.Checked)
        assertTrue((result as RuleUpdateResult.Checked).changed)
        assertEquals("4.4.7.5", s.policy().meta.version)
        assertEquals("UA-NEW", s.policy().findRule("nytimes.com")!!.request.userAgent)
        assertEquals("4.4.7.5", SiteCatalogJson.decode(storage.policy!!).meta.version)
    }

    @Test
    fun downgrade_rejected() {
        val s = store(downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "1.0") })
        now = 3_000_000L
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "older than current" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertEquals(3_000_000L, s.status().lastCheckMs)
        assertEquals(0L, s.status().lastSuccessMs)
    }

    @Test
    fun cacheWriteFailure_keepsOld() {
        val storage = FakeStorage(failPolicyWrite = IOException("read-only"))
        val s = store(storage = storage)
        val failed = failedResult(s)
        assertTrue("error was: ${failed.error}", "cache write failed" in failed.error)
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertEquals(0, storage.policyWrites)
    }

    // ------------------------------------------------------------------
    // Legacy migration
    // ------------------------------------------------------------------

    @Test
    fun newerLegacyPolicy_isExplicitOverride() {
        val s = store(legacy = FakeLegacy(policy = legacyPolicy("4.4.7.9")))
        // The catalog itself is never relabeled with the legacy version.
        assertEquals("4.4.7.4", s.policy().meta.version)
        assertEquals("4.4.7.4", s.status().version)
        val override = s.legacyOverride()
        assertTrue("override was: $override", override != null)
        assertEquals("4.4.7.9", override!!.sourceVersion)
        assertEquals("Legacy-UA/1.0", override.userAgent)
        val snapshot = s.snapshot()
        assertEquals("4.4.7.4", snapshot.catalog.meta.version)
        assertEquals("4.4.7.9", snapshot.legacyNyt!!.sourceVersion)
        assertNull(s.startupNotice)
    }

    @Test
    fun equalOrOlderLegacyPolicy_neverOverrides() {
        val equal = store(legacy = FakeLegacy(policy = legacyPolicy("4.4.7.4")))
        assertNull(equal.legacyOverride())
        assertNull(equal.snapshot().legacyNyt)
        val older = store(legacy = FakeLegacy(policy = legacyPolicy("1.0")))
        assertNull(older.legacyOverride())
        assertNull(older.snapshot().legacyNyt)
    }

    @Test
    fun legacyOverrideFor_decidesAgainstCapturedVersion() {
        // Pure boundary semantics, no threads: strictly newer legacy wins,
        // equal and older never override — the exact decision snapshot()
        // pairs with its captured catalog.
        val s = store(legacy = FakeLegacy(policy = legacyPolicy("4.4.7.9")))
        fun catalogAt(version: String): SiteCatalog =
            SiteCatalogJson.decode(
                catalogJson(nytEntry()).replace("\"version\": \"4.4.7.4\"", "\"version\": \"$version\""),
            )
        assertEquals("4.4.7.9", s.legacyOverrideFor(catalogAt("4.4.7.4"))!!.sourceVersion)
        assertNull(s.legacyOverrideFor(catalogAt("4.4.7.9")))
        assertNull(s.legacyOverrideFor(catalogAt("9.0")))
        // snapshot() pairs one captured catalog with its own decision.
        val snapshot = s.snapshot()
        assertEquals("4.4.7.4", snapshot.catalog.meta.version)
        assertEquals("4.4.7.9", snapshot.legacyNyt!!.sourceVersion)
    }

    @Test
    fun catalogAtLegacyVersion_replacesOverride() {
        val legacy = FakeLegacy(policy = legacyPolicy("4.4.7.5"))
        val s = store(
            legacy = legacy,
            downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") },
        )
        assertEquals("4.4.7.5", s.legacyOverride()!!.sourceVersion)
        val result = s.checkForUpdates(manual = true)
        assertTrue("result was: $result", result is RuleUpdateResult.Checked)
        assertEquals("4.4.7.5", s.policy().meta.version)
        assertNull("compatible catalog replaces the override", s.legacyOverride())
        assertNull(s.snapshot().legacyNyt)
    }

    @Test
    fun corruptLegacyPolicy_noticedAndCatalogUsable() {
        val s = store(legacy = FakeLegacy(policy = "{oops"))
        assertNull(s.legacyOverride())
        assertEquals("4.4.7.4", s.policy().meta.version)
        val notice = s.startupNotice
        assertTrue("notice was: $notice", notice != null && "Legacy NYT rule corrupt" in notice)
        assertTrue(warnings.any { "Legacy NYT rule corrupt" in it })
        // The store still updates normally.
        val result = s.checkForUpdates(manual = true)
        assertTrue("result was: $result", result is RuleUpdateResult.Checked)
    }

    @Test
    fun unreadableLegacyPolicy_noticedAndCatalogUsable() {
        val s = store(legacy = FakeLegacy(failPolicyRead = IOException("disk gone")))
        assertNull(s.legacyOverride())
        val notice = s.startupNotice
        assertTrue(
            "notice was: $notice",
            notice != null && "Legacy NYT rule unreadable" in notice && "disk gone" in notice,
        )
    }

    @Test
    fun legacyThrottle_carriesAcrossMigration() {
        val legacyStatus = NytStatusJson.encode(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_CACHE, "4.4.7.4", 100_000L, 50_000L, "offline boom"),
        )
        val s = store(legacy = FakeLegacy(policy = legacyPolicy("4.4.7.4"), status = legacyStatus))
        // Last attempt and error carry over; the legacy success is not
        // relabeled as a catalog success.
        assertEquals(100_000L, s.status().lastCheckMs)
        assertEquals(0L, s.status().lastSuccessMs)
        assertEquals("offline boom", s.status().lastError)
        assertEquals("4.4.7.4", s.status().version)
        assertNull(s.startupNotice)
        // ... and the carried attempt throttles the next auto check.
        now = 100_000L + 3_600_000L
        val downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") }
        // A restart sees the migrated status as its own persisted status.
        val restarted = FakeStorage(status = RuleStatusJson.encode(s.status()))
        val s2 = SiteCatalogStore(
            bundled(), restarted, FakeLegacy(), downloader::download, clock, warnings::add,
        )
        assertTrue(s2.checkForUpdates(manual = false) is RuleUpdateResult.Throttled)
        assertEquals(0, downloader.calls)
    }

    @Test
    fun legacyThrottle_carriesWithoutLegacyPolicy() {
        val legacyStatus = NytStatusJson.encode(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_CACHE, "4.4.7.4", 100_000L, 0L, "offline"),
        )
        val s = store(legacy = FakeLegacy(status = legacyStatus))
        assertEquals(100_000L, s.status().lastCheckMs)
        assertEquals("offline", s.status().lastError)
        assertNull(s.legacyOverride())
    }

    @Test
    fun corruptLegacyStatus_defaultsWithNotice() {
        val s = store(legacy = FakeLegacy(status = "{oops"))
        assertEquals(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_BUNDLED, "4.4.7.4", 0L, 0L, null),
            s.status(),
        )
        val notice = s.startupNotice
        assertTrue("notice was: $notice", notice != null && "Legacy NYT status corrupt" in notice)
    }

    @Test
    fun newStatusBeatsLegacyStatus() {
        val newStatus = RuleStatusJson.encode(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_CACHE, "4.4.7.4", 200_000L, 200_000L, null),
        )
        val legacyStatus = NytStatusJson.encode(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_CACHE, "4.4.7.4", 100_000L, 50_000L, "stale"),
        )
        val s = store(
            storage = FakeStorage(status = newStatus),
            legacy = FakeLegacy(status = legacyStatus),
        )
        assertEquals(200_000L, s.status().lastCheckMs)
        assertEquals(200_000L, s.status().lastSuccessMs)
        assertNull(s.status().lastError)
        assertNull(s.startupNotice)
    }

    // ------------------------------------------------------------------
    // Throttle, atomicity, and nonblocking reads
    // ------------------------------------------------------------------

    @Test
    fun failureWithoutCache_throttlesAfterRestart() {
        val storage = FakeStorage()
        val offline = FakeDownloader { throw IOException("offline") }
        now = 100_000L
        val first = store(storage = storage, downloader = offline)
        assertTrue(first.checkForUpdates(manual = false) is RuleUpdateResult.Failed)
        assertNull(storage.policy)
        val restarted = store(storage = storage, downloader = offline)
        assertEquals(100_000L, restarted.status().lastCheckMs)
        assertTrue(restarted.checkForUpdates(manual = false) is RuleUpdateResult.Throttled)
        assertEquals(1, offline.calls)
        assertTrue(restarted.checkForUpdates(manual = true) is RuleUpdateResult.Failed)
        assertEquals(2, offline.calls)
    }

    @Test(timeout = 30_000)
    fun readsDoNotBlockOnDownload() {
        val zip = updateZip(twoRuleSitesJs(), "4.4.7.5")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloader = FakeDownloader {
            entered.countDown()
            assertTrue(release.await(20, TimeUnit.SECONDS))
            zip
        }
        now = 100_000L
        val s = store(downloader = downloader)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val update = pool.submit<RuleUpdateResult> { s.checkForUpdates(manual = false) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val readPolicy = pool.submit<SiteCatalog> { s.policy() }
            val readStatus = pool.submit<RuleUpdateStatus> { s.status() }
            assertEquals("4.4.7.4", readPolicy.get(5, TimeUnit.SECONDS).meta.version)
            assertEquals("4.4.7.4", readStatus.get(5, TimeUnit.SECONDS).version)
            release.countDown()
            val result = update.get(10, TimeUnit.SECONDS)
            assertTrue("result was: $result", result is RuleUpdateResult.Checked)
            assertEquals("4.4.7.5", s.policy().meta.version)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun fileReplaceFailure_preservesOldCache() {
        val dir = Files.createTempDirectory("sites-replace-fail").toFile()
        try {
            val good = AtomicJsonFileStore(dir, SiteCatalogStore.POLICY_NAME, SiteCatalogStore.STATUS_NAME, "Site rule")
            val oldJson = bundled()
            good.writePolicyJson(oldJson)
            val oldBytes = File(dir, SiteCatalogStore.POLICY_NAME).readBytes()
            val failing = AtomicJsonFileStore(
                dir,
                SiteCatalogStore.POLICY_NAME,
                SiteCatalogStore.STATUS_NAME,
                "Site rule",
                mover = FileMover { _, _ -> throw IOException("injected move failure") },
            )
            val s = SiteCatalogStore(
                bundled(), failing, FakeLegacy(),
                { updateZip(twoRuleSitesJs(), "4.4.7.5") }, clock, warnings::add,
            )
            assertEquals("4.4.7.4", s.policy().meta.version)
            val failed = failedResult(s)
            assertTrue(
                "error was: ${failed.error}",
                "cache write failed" in failed.error && "injected move failure" in failed.error,
            )
            assertEquals("4.4.7.4", s.policy().meta.version)
            assertEquals(oldBytes.toList(), File(dir, SiteCatalogStore.POLICY_NAME).readBytes().toList())
            val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray<File>()
            assertTrue(leftovers.isEmpty())
            // The old destination was never deleted: a restart still loads it.
            val afterRestart = SiteCatalogStore(
                bundled(),
                AtomicJsonFileStore(dir, SiteCatalogStore.POLICY_NAME, SiteCatalogStore.STATUS_NAME, "Site rule"),
                FakeLegacy(),
                { updateZip(twoRuleSitesJs(), "4.4.7.5") },
                clock, warnings::add,
            )
            assertEquals("4.4.7.4", afterRestart.policy().meta.version)
            assertEquals(RuleUpdateStatus.ORIGIN_CACHE, afterRestart.status().origin)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizeCacheFile_reportsUnreadable() {
        val dir = Files.createTempDirectory("sites-oversize").toFile()
        try {
            val store = AtomicJsonFileStore(
                dir, SiteCatalogStore.POLICY_NAME, SiteCatalogStore.STATUS_NAME, "Site rule",
                maxReadBytes = 16,
            )
            File(dir, SiteCatalogStore.POLICY_NAME).writeText("{\"too\": \"large for the bound\"}", Charsets.UTF_8)
            try {
                store.readPolicyJson()
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue("message was: ${e.message}", "too large" in (e.message ?: ""))
            }
            // And the catalog store falls back to bundled with a notice.
            val s = SiteCatalogStore(bundled(), store, FakeLegacy(), { ByteArray(0) }, clock, warnings::add)
            assertEquals("4.4.7.4", s.policy().meta.version)
            assertTrue("notice was: ${s.startupNotice}", "unreadable" in (s.startupNotice ?: ""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizePolicyWrite_rejectedBeforeReplacingCache() {
        // The encoded write bound matches the read cap: an update that would
        // publish an unreadable cache fails before touching the old file.
        val dir = Files.createTempDirectory("sites-oversize-write").toFile()
        try {
            val tiny = AtomicJsonFileStore(
                dir, SiteCatalogStore.POLICY_NAME, SiteCatalogStore.STATUS_NAME, "Site rule",
                maxReadBytes = 64,
            )
            tiny.writePolicyJson("{\"v\": 1}")
            try {
                tiny.writePolicyJson("{\"v\": \"${"x".repeat(100)}\"}")
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue(
                    "message was: ${e.message}",
                    "cache write failed" in (e.message ?: "") && "too large" in (e.message ?: ""),
                )
            }
            assertEquals("{\"v\": 1}", File(dir, SiteCatalogStore.POLICY_NAME).readText(Charsets.UTF_8))
            val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray<File>()
            assertTrue(leftovers.isEmpty())

            // End to end: the encoded 4.4.7.5 catalog exceeds the tiny bound,
            // so the update fails and the working rules never swap.
            val s = SiteCatalogStore(
                bundled(), tiny, FakeLegacy(),
                { updateZip(twoRuleSitesJs(), "4.4.7.5") }, clock, warnings::add,
            )
            val failed = failedResult(s)
            assertTrue(
                "error was: ${failed.error}",
                "cache write failed" in failed.error && "too large" in failed.error,
            )
            assertEquals("4.4.7.4", s.policy().meta.version)
            assertEquals("4.4.7.4", s.status().version)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Shared downloader core with the site label
    // ------------------------------------------------------------------

    private class FakeConnection(
        override val statusCode: Int,
        override val statusMessage: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
    ) : FetchConnection {
        override fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun input(): InputStream = ByteArrayInputStream(body)
        override fun close() = Unit
    }

    @Test
    fun sharedDownloader_usesSiteLabel() {
        val downloader = FixedOriginDownloader(
            { FakeConnection(200, "OK", body = "ZIP".toByteArray()) },
            URL,
            "Site rule update",
        )
        assertEquals("ZIP", String(downloader.download(URL), Charsets.UTF_8))
        try {
            downloader.download("https://evil.example/x.zip")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(
                "message was: ${e.message}",
                "Site rule update refused unexpected URL" in (e.message ?: ""),
            )
        }
        val failing = FixedOriginDownloader(
            { FakeConnection(404, "Not Found") },
            URL,
            "Site rule update",
        )
        try {
            failing.download(URL)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Site rule update failed: HTTP 404 Not Found", e.message)
        }
    }

    @Test
    fun importerOutput_passesStoreGateShape() {
        // The production importer output has everything the candidate gate
        // requires: rules, applied directives, and the NYT UA+scope policy.
        val catalog = import(
            entry(
                "The New York Times",
                "    domain: \"nytimes.com\",\n    useragent_custom: \"UA-NEW\"",
            ),
            entry("Other", "    domain: \"other.example.com\",\n    useragent: \"googlebot\""),
        )
        assertTrue(catalog.rules.isNotEmpty())
        assertTrue(catalog.rules.any { it.hasAppliedDirectives() })
        val nyt = catalog.findRule("nytimes.com")!!
        assertEquals(HostScope.APEX_AND_WWW, nyt.scope)
        assertEquals("UA-NEW", nyt.request.userAgent)
    }
}
