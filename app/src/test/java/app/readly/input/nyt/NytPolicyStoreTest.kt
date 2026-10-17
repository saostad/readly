package app.readly.input.nyt

import app.readly.input.nyt.NytTestFixtures.UA
import app.readly.input.nyt.NytTestFixtures.bundledJson
import app.readly.input.nyt.NytTestFixtures.manifestJson
import app.readly.input.nyt.NytTestFixtures.nytBody
import app.readly.input.nyt.NytTestFixtures.policyJson
import app.readly.input.nyt.NytTestFixtures.realStyleSitesJs
import app.readly.input.nyt.NytTestFixtures.updateZip
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NytPolicyStoreTest {

    private val warnings = mutableListOf<String>()
    private var now = 1_000_000L
    private val clock = { now }

    private class FakeStorage(
        var policy: String? = null,
        var status: String? = null,
        var failPolicyRead: Exception? = null,
        var failPolicyWrite: Exception? = null,
        var failStatusRead: Exception? = null,
        var failStatusWrite: Exception? = null,
    ) : NytPolicyStorage {
        var policyWrites = 0
        override fun readPolicyJson(): String? {
            failPolicyRead?.let { throw it }
            return policy
        }
        override fun writePolicyJson(json: String) {
            failPolicyWrite?.let { throw it }
            policy = json
            policyWrites++
        }
        override fun readStatusJson(): String? {
            failStatusRead?.let { throw it }
            return status
        }
        override fun writeStatusJson(json: String) {
            failStatusWrite?.let { throw it }
            status = json
        }
    }

    private class FakeDownloader(var action: () -> ByteArray) : NytUpdateDownloader {
        var calls = 0
        override fun download(url: String): ByteArray {
            calls++
            assertEquals(NytPolicy.OFFICIAL_UPDATE_URL, url)
            return action()
        }
    }

    private fun updateZipFor(version: String, userAgent: String = UA): ByteArray =
        updateZip(realStyleSitesJs(nytBody(userAgent = "\"$userAgent\"")), manifestJson("\"$version\""))

    private fun store(
        bundled: String = bundledJson(),
        storage: FakeStorage = FakeStorage(),
        downloader: FakeDownloader = FakeDownloader { updateZipFor("2.0") },
    ): Triple<NytPolicyStore, FakeStorage, FakeDownloader> =
        Triple(NytPolicyStore(bundled, storage, downloader, clock, warnings::add), storage, downloader)

    @Test
    fun initialState_usesBundled() {
        val (store, storage, _) = store()
        assertEquals("1.0", store.policy().sourceVersion)
        assertEquals(UA, store.policy().userAgent)
        assertNull(store.startupNotice)
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_BUNDLED, "1.0", 0L, 0L, null),
            store.status(),
        )
        assertEquals(0, storage.policyWrites)
    }

    @Test
    fun invalidBundled_throws() {
        try {
            NytPolicyStore("{oops", FakeStorage(), FakeDownloader { ByteArray(0) }, clock, warnings::add)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message ?: "", (e.message ?: "").contains("Bundled NYT policy is invalid"))
            return
        }
        fail("expected IllegalArgumentException")
    }

    @Test
    fun restart_loadsValidCache() {
        val cached = policyJson(NytPolicy("UA-CACHE", false, null, "5.0", "bb".repeat(32), NytPolicy.OFFICIAL_UPDATE_URL))
        val (store, _, _) = store(storage = FakeStorage(policy = cached))
        assertEquals("UA-CACHE", store.policy().userAgent)
        assertEquals("5.0", store.policy().sourceVersion)
        assertNull(store.startupNotice)
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, store.status().origin)
        assertEquals("5.0", store.status().version)
    }

    @Test
    fun corruptCache_fallsBackWithNotice() {
        val (store, _, _) = store(storage = FakeStorage(policy = "{oops"))
        val notice = store.startupNotice
        assertTrue("expected startupNotice", notice != null)
        assertTrue("notice was: $notice", "bundled" in notice!! && "JSONException" in notice)
        assertEquals("1.0", store.policy().sourceVersion)
        assertEquals(listOf(notice), warnings)
        assertEquals(NytUpdateStatus.ORIGIN_BUNDLED, store.status().origin)
    }

    @Test
    fun unreadableCache_fallsBackWithNotice() {
        val storage = FakeStorage(failPolicyRead = IOException("disk gone"))
        val (store, _, _) = store(storage = storage)
        val notice = store.startupNotice
        assertTrue("expected startupNotice", notice != null)
        assertTrue("notice was: $notice", "IOException" in notice!! && "disk gone" in notice)
        assertEquals(UA, store.policy().userAgent)
    }

    @Test
    fun success_replacesAtomically() {
        val (store, storage, downloader) = store()
        now = 2_000_000L
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Checked && result.changed)
        assertEquals(1, downloader.calls)
        assertEquals("2.0", store.policy().sourceVersion)
        assertEquals("2.0", NytPolicyJson.decode(storage.policy!!).sourceVersion)
        assertEquals(1, storage.policyWrites)
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_CACHE, "2.0", now, now, null),
            store.status(),
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun sameVersionChangedRule_accepted() {
        val downloader = FakeDownloader { updateZipFor("1.0", "UA-NEW") }
        val (store, _, _) = store(downloader = downloader)
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Checked && result.changed)
        assertEquals("UA-NEW", store.policy().userAgent)
        assertEquals("1.0", store.status().version)
    }

    @Test
    fun sameVersionSameRule_refreshesHashWithoutChangeFlag() {
        val zip = updateZipFor("1.0")
        val (store, storage, _) = store(downloader = FakeDownloader { zip })
        val first = store.checkForUpdates(manual = true) as NytUpdateResult.Checked
        // Same rule, new provenance hash: cache records the verified bytes.
        assertEquals(false, first.changed)
        assertEquals(1, storage.policyWrites)
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, store.status().origin)
        // Fully identical re-download: success without another write.
        val second = store.checkForUpdates(manual = true) as NytUpdateResult.Checked
        assertEquals(false, second.changed)
        assertEquals(1, storage.policyWrites)
    }

    @Test
    fun downgrade_rejected() {
        val cached = policyJson(NytPolicy(UA, true, null, "4.4.7.4", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val downloader = FakeDownloader { updateZipFor("4.4.7.3") }
        val (store, _, _) = store(storage = FakeStorage(policy = cached), downloader = downloader)
        now = 3_000_000L
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Failed)
        val error = (result as NytUpdateResult.Failed).error
        assertTrue("error was: $error", "older than current" in error && "4.4.7.3" in error)
        assertEquals("4.4.7.4", store.policy().sourceVersion)
        assertEquals("4.4.7.4", store.status().version)
        assertEquals(3_000_000L, store.status().lastCheckMs)
        assertEquals(0L, store.status().lastSuccessMs)
    }

    @Test
    fun numericVersion_410beats49() {
        val cached = policyJson(NytPolicy(UA, true, null, "4.9", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val downloader = FakeDownloader { updateZipFor("4.10") }
        val (store, _, _) = store(storage = FakeStorage(policy = cached), downloader = downloader)
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Checked && result.changed)
        assertEquals("4.10", store.policy().sourceVersion)
    }

    @Test
    fun offline_keepsOldAndLastSuccess() {
        val (store, _, downloader) = store()
        now = 10_000L
        store.checkForUpdates(manual = true)
        assertEquals("2.0", store.policy().sourceVersion)
        downloader.action = { throw IOException("boom") }
        now = 20_000L
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Failed)
        val error = (result as NytUpdateResult.Failed).error
        assertTrue("error was: $error", "IOException" in error && "boom" in error)
        assertEquals("2.0", store.policy().sourceVersion)
        assertEquals("2.0", store.status().version)
        assertEquals(20_000L, store.status().lastCheckMs)
        assertEquals(10_000L, store.status().lastSuccessMs)
        assertEquals(error, store.status().lastError)
    }

    @Test
    fun httpError_keepsOld() {
        val downloader = FakeDownloader {
            throw IOException("NYT rule update failed: HTTP 404 Not Found")
        }
        val (store, _, _) = store(downloader = downloader)
        val result = store.checkForUpdates(manual = true) as NytUpdateResult.Failed
        assertTrue("error was: ${result.error}", "HTTP 404" in result.error)
        assertEquals("1.0", store.policy().sourceVersion)
    }

    @Test
    fun invalidCandidate_keepsOld() {
        val bad = updateZip(manifestText = manifestJson("\"nope\""))
        val (store, _, _) = store(downloader = FakeDownloader { bad })
        val result = store.checkForUpdates(manual = true) as NytUpdateResult.Failed
        assertTrue("error was: ${result.error}", "invalid version" in result.error)
        assertEquals("1.0", store.policy().sourceVersion)
        assertEquals("1.0", store.status().version)
        assertEquals(0L, store.status().lastSuccessMs)
    }

    @Test
    fun cacheWriteFailure_keepsOld() {
        val storage = FakeStorage(failPolicyWrite = IOException("read-only"))
        val (store, _, _) = store(storage = storage)
        now = 5_000L
        val result = store.checkForUpdates(manual = true) as NytUpdateResult.Failed
        assertTrue("error was: ${result.error}", "cache write failed" in result.error && "read-only" in result.error)
        assertEquals("1.0", store.policy().sourceVersion)
        assertEquals("1.0", store.status().version)
        assertEquals(5_000L, store.status().lastCheckMs)
        assertEquals(0L, store.status().lastSuccessMs)
    }

    @Test
    fun statusWriteFailure_isBestEffort() {
        val storage = FakeStorage(failStatusWrite = IOException("status disk gone"))
        val (store, _, _) = store(storage = storage)
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Checked)
        assertEquals("2.0", store.policy().sourceVersion)
        assertTrue(warnings.any { "status write failed" in it && "status disk gone" in it })
        // The warning is UI-visible on both the result and the live status,
        // separate from the update outcome (which still succeeded).
        val warning = (result as NytUpdateResult.Checked).status.warning
        assertTrue("warning was: $warning", warning != null && "status disk gone" in warning)
        assertEquals(warning, store.status().warning)
        assertNull(store.status().lastError)
    }

    @Test
    fun throttle_autoSuccessFailureManualAndExpiry() {
        val (store, _, downloader) = store()
        val day = NytPolicyStore.AUTO_CHECK_INTERVAL_MS
        now = 100_000L
        assertTrue(store.checkForUpdates(manual = false) is NytUpdateResult.Checked)
        assertEquals(1, downloader.calls)
        // Auto within 24h of success: throttled.
        now += 3_600_000L
        assertTrue(store.checkForUpdates(manual = false) is NytUpdateResult.Throttled)
        assertEquals(1, downloader.calls)
        // Manual bypasses the throttle and moves the window.
        assertTrue(store.checkForUpdates(manual = true) is NytUpdateResult.Checked)
        assertEquals(2, downloader.calls)
        // A failure also throttles later auto checks.
        downloader.action = { throw IOException("boom") }
        val failedAt = now + 3_600_000L
        now = failedAt
        assertTrue(store.checkForUpdates(manual = true) is NytUpdateResult.Failed)
        now = failedAt + 3_600_000L
        assertTrue(store.checkForUpdates(manual = false) is NytUpdateResult.Throttled)
        assertEquals(3, downloader.calls)
        // Just before 24h: still throttled; at 24h: proceeds.
        downloader.action = { updateZipFor("2.0") }
        now = failedAt + day - 1
        assertTrue(store.checkForUpdates(manual = false) is NytUpdateResult.Throttled)
        now = failedAt + day
        assertTrue(store.checkForUpdates(manual = false) is NytUpdateResult.Checked)
        assertEquals(4, downloader.calls)
    }

    @Test(timeout = 60_000)
    fun concurrentRefresh_serializedAndNeverPartial() {
        val zip = updateZipFor("2.0", "UA-NEW")
        val gate = CountDownLatch(1)
        val downloader = FakeDownloader {
            assertTrue(gate.await(30, TimeUnit.SECONDS))
            zip
        }
        val (store, _, _) = store(downloader = downloader)
        val pool = Executors.newFixedThreadPool(12)
        try {
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val readersDone = AtomicBoolean(false)
            repeat(4) {
                pool.submit {
                    try {
                        while (!readersDone.get()) {
                            val p = store.policy()
                            if (p.userAgent != UA && p.userAgent != "UA-NEW") {
                                throw AssertionError("partial policy: $p")
                            }
                            if ((p.userAgent == "UA-NEW") != (p.sourceVersion == "2.0")) {
                                throw AssertionError("mixed policy: $p")
                            }
                            val s = store.status()
                            if (s.version != "1.0" && s.version != "2.0") {
                                throw AssertionError("partial status: $s")
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
            val futures = (1..8).map {
                pool.submit<NytUpdateResult> { store.checkForUpdates(manual = true) }
            }
            gate.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            readersDone.set(true)
            assertTrue("reader errors: $errors", errors.isEmpty())
            assertEquals(8, downloader.calls)
            assertTrue(results.all { it is NytUpdateResult.Checked })
            assertEquals(1, results.count { (it as NytUpdateResult.Checked).changed })
            assertEquals("UA-NEW", store.policy().userAgent)
            assertEquals("2.0", store.status().version)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun fileStorage_roundTrip() {
        val dir = Files.createTempDirectory("nyt-store").toFile()
        try {
            val storage = FileNytPolicyStorage(dir)
            assertNull(storage.readPolicyJson())
            assertNull(storage.readStatusJson())
            storage.writePolicyJson("{\"v\":1}")
            storage.writePolicyJson("{\"v\":2}")
            assertEquals("{\"v\":2}", storage.readPolicyJson())
            storage.writeStatusJson("{\"s\":1}")
            assertEquals("{\"s\":1}", storage.readStatusJson())
            val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray<File>()
            assertTrue(leftovers.isEmpty())
            assertTrue(File(dir, "nyt_policy.json").exists())
            assertTrue(File(dir, "nyt_status.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun startUpdateCheck_deliversAsync() {
        val (store, _, _) = store()
        val latch = CountDownLatch(1)
        var result: NytUpdateResult? = null
        store.startUpdateCheck(manual = true) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(result is NytUpdateResult.Checked)
        assertEquals("2.0", store.policy().sourceVersion)
    }

    @Test(timeout = 30_000)
    fun startUpdateCheck_callbackFailure_logged() {
        val (store, _, _) = store()
        store.startUpdateCheck(manual = true) { throw IOException("callback boom") }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && warnings.none { "callback failed" in it }) {
            Thread.sleep(20)
        }
        assertTrue("warnings were: $warnings", warnings.any { "callback failed" in it && "callback boom" in it })
    }

    @Test
    fun corruptStatus_defaultsWithWarn() {
        val cached = policyJson(NytPolicy(UA, true, null, "5.0", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val (store, _, _) = store(storage = FakeStorage(policy = cached, status = "{oops"))
        assertEquals("5.0", store.policy().sourceVersion)
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_CACHE, "5.0", 0L, 0L, null),
            store.status(),
        )
        assertTrue(warnings.any { "status corrupt" in it })
        val notice = store.startupNotice
        assertTrue("expected startupNotice, warnings were: $warnings", notice != null && "status corrupt" in notice)
    }

    @Test
    fun staleStatus_reconcilesThrottleWithWarn() {
        val cached = policyJson(NytPolicy(UA, true, null, "5.0", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val stale = NytStatusJson.encode(NytUpdateStatus(NytUpdateStatus.ORIGIN_CACHE, "4.0", 1L, 1L, null))
        val (store, _, _) = store(storage = FakeStorage(policy = cached, status = stale))
        // Version mismatch preserves the throttle but never invents a
        // success for the current rule.
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_CACHE, "5.0", 1L, 0L, null),
            store.status(),
        )
        assertTrue(warnings.any { "stale" in it })
    }

    @Test(timeout = 30_000)
    fun readsDoNotBlockOnDownload() {
        val zip = updateZipFor("2.0", "UA-NEW")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloader = FakeDownloader {
            entered.countDown()
            assertTrue(release.await(20, TimeUnit.SECONDS))
            zip
        }
        now = 100_000L
        val (store, _, _) = store(downloader = downloader)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val update1 = pool.submit<NytUpdateResult> { store.checkForUpdates(manual = false) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // Both accessors must return the old complete state BEFORE the
            // download finishes.
            val readPolicy = pool.submit<NytPolicy> { store.policy() }
            val readStatus = pool.submit<NytUpdateStatus> { store.status() }
            val oldPolicy = readPolicy.get(5, TimeUnit.SECONDS)
            val oldStatus = readStatus.get(5, TimeUnit.SECONDS)
            assertEquals("1.0", oldPolicy.sourceVersion)
            assertEquals(UA, oldPolicy.userAgent)
            assertEquals("1.0", oldStatus.version)
            assertEquals(NytUpdateStatus.ORIGIN_BUNDLED, oldStatus.origin)
            assertEquals(0L, oldStatus.lastCheckMs)
            // A second auto check while the first is still running must not
            // trigger a second download; it throttles after the first lands.
            val update2 = pool.submit<NytUpdateResult> { store.checkForUpdates(manual = false) }
            Thread.sleep(200)
            release.countDown()
            val first = update1.get(10, TimeUnit.SECONDS)
            assertTrue(first is NytUpdateResult.Checked && (first as NytUpdateResult.Checked).changed)
            val second = update2.get(10, TimeUnit.SECONDS)
            assertTrue("second was: $second", second is NytUpdateResult.Throttled)
            assertEquals(1, downloader.calls)
            assertEquals("UA-NEW", store.policy().userAgent)
            assertEquals("2.0", store.policy().sourceVersion)
            assertEquals("2.0", store.status().version)
            assertEquals(NytUpdateStatus.ORIGIN_CACHE, store.status().origin)
            assertEquals(100_000L, store.status().lastCheckMs)
            assertEquals(100_000L, store.status().lastSuccessMs)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun init_selectsNewerAndEqualPolicies() {
        // Cache newer than bundled: retained.
        val newerCache = policyJson(NytPolicy("UA-CACHE", true, null, "5.0", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val (newerStore, _, _) = store(
            bundled = bundledJson(version = "1.0"),
            storage = FakeStorage(policy = newerCache),
        )
        assertEquals("5.0", newerStore.policy().sourceVersion)
        assertEquals("UA-CACHE", newerStore.policy().userAgent)
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, newerStore.status().origin)
        assertEquals("5.0", newerStore.status().version)
        assertNull(newerStore.startupNotice)
        // Bundled newer than cache: bundled wins (app upgrade).
        val olderCache = policyJson(NytPolicy("UA-OLD", true, null, "1.0", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val staleStatus = NytStatusJson.encode(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_CACHE, "1.0", 7L, 7L, null),
        )
        val (upgradedStore, _, _) = store(
            bundled = bundledJson(version = "2.0"),
            storage = FakeStorage(policy = olderCache, status = staleStatus),
        )
        assertEquals("2.0", upgradedStore.policy().sourceVersion)
        assertEquals(UA, upgradedStore.policy().userAgent)
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_BUNDLED, "2.0", 7L, 0L, null),
            upgradedStore.status(),
        )
        assertNull(upgradedStore.startupNotice)
        // Equal versions: the verified cache is retained.
        val equalCache = policyJson(NytPolicy("UA-CACHE", true, null, "1.0", "", NytPolicy.OFFICIAL_UPDATE_URL))
        val (equalStore, _, _) = store(
            bundled = bundledJson(version = "1.0"),
            storage = FakeStorage(policy = equalCache),
        )
        assertEquals("UA-CACHE", equalStore.policy().userAgent)
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, equalStore.status().origin)
        assertNull(equalStore.startupNotice)
    }

    @Test
    fun fileReplaceFailure_preservesOldAndRestarts() {
        val dir = Files.createTempDirectory("nyt-replace-fail").toFile()
        try {
            val good = FileNytPolicyStorage(dir)
            val oldPolicy = NytPolicy("UA-OLD", true, null, "5.0", "", NytPolicy.OFFICIAL_UPDATE_URL)
            val oldJson = policyJson(oldPolicy)
            good.writePolicyJson(oldJson)
            val oldBytes = File(dir, FileNytPolicyStorage.POLICY_NAME).readBytes()
            val failing = FileNytPolicyStorage(
                dir,
                mover = NytFileMover { _, _ -> throw IOException("injected move failure") },
            )
            try {
                failing.writePolicyJson(bundledJson(version = "6.0"))
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue("message was: ${e.message}", "injected move failure" in (e.message ?: ""))
            }
            assertEquals(oldBytes.toList(), File(dir, FileNytPolicyStorage.POLICY_NAME).readBytes().toList())
            val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray<File>()
            assertTrue(leftovers.isEmpty())
            // Restart with the production mover still loads the old cache.
            val restarted = FileNytPolicyStorage(dir)
            assertEquals("5.0", NytPolicyJson.decode(restarted.readPolicyJson()!!).sourceVersion)
            // And a store over the same directory keeps serving it after a
            // failed update attempt.
            now = 50_000L
            val store = NytPolicyStore(
                bundledJson(version = "1.0"),
                failing,
                FakeDownloader { updateZipFor("6.0") },
                clock,
                warnings::add,
            )
            assertEquals("5.0", store.policy().sourceVersion)
            val result = store.checkForUpdates(manual = true)
            assertTrue(result is NytUpdateResult.Failed)
            assertTrue(
                "error was: ${(result as NytUpdateResult.Failed).error}",
                "cache write failed" in result.error && "injected move failure" in result.error,
            )
            assertEquals("5.0", store.policy().sourceVersion)
            val afterRestart = NytPolicyStore(
                bundledJson(version = "1.0"),
                FileNytPolicyStorage(dir),
                FakeDownloader { updateZipFor("6.0") },
                clock,
                warnings::add,
            )
            assertEquals("5.0", afterRestart.policy().sourceVersion)
            assertEquals(NytUpdateStatus.ORIGIN_CACHE, afterRestart.status().origin)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failureWithoutCache_throttlesAfterRestart() {
        val storage = FakeStorage()
        val offline = FakeDownloader { throw IOException("offline") }
        now = 100_000L
        val first = NytPolicyStore(bundledJson(version = "1.0"), storage, offline, clock, warnings::add)
        val failed = first.checkForUpdates(manual = false)
        assertTrue(failed is NytUpdateResult.Failed)
        assertNull(storage.policy)
        assertTrue(storage.status != null)
        // Restart at the same clock: throttle and error are preserved even
        // though no policy file exists.
        val restarted = NytPolicyStore(bundledJson(version = "1.0"), storage, offline, clock, warnings::add)
        assertEquals(100_000L, restarted.status().lastCheckMs)
        assertEquals(0L, restarted.status().lastSuccessMs)
        assertEquals(NytUpdateStatus.ORIGIN_BUNDLED, restarted.status().origin)
        assertEquals("1.0", restarted.status().version)
        assertTrue(
            "lastError was: ${restarted.status().lastError}",
            "offline" in (restarted.status().lastError ?: ""),
        )
        assertNull(restarted.startupNotice)
        assertTrue(restarted.checkForUpdates(manual = false) is NytUpdateResult.Throttled)
        assertEquals(1, offline.calls)
        // Manual bypass still runs and records the attempt.
        assertTrue(restarted.checkForUpdates(manual = true) is NytUpdateResult.Failed)
        assertEquals(2, offline.calls)
        assertEquals(100_000L, restarted.status().lastCheckMs)
        // After 24h the auto check runs again.
        now += NytPolicyStore.AUTO_CHECK_INTERVAL_MS
        assertTrue(restarted.checkForUpdates(manual = false) is NytUpdateResult.Failed)
        assertEquals(3, offline.calls)
        assertNull(storage.policy)
    }

    @Test
    fun unreadableStatus_defaultsWithNotice() {
        val storage = FakeStorage(failStatusRead = IOException("status gone"))
        val (store, _, _) = store(storage = storage)
        assertEquals("1.0", store.policy().sourceVersion)
        assertEquals(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_BUNDLED, "1.0", 0L, 0L, null),
            store.status(),
        )
        val notice = store.startupNotice
        assertTrue("expected startupNotice", notice != null && "status unreadable" in notice)
        assertTrue(warnings.any { "status unreadable" in it && "status gone" in it })
    }

    @Test
    fun statusWriteFailure_preservesPolicyAndTimes() {
        val storage = FakeStorage(failStatusWrite = IOException("status disk gone"))
        val (store, _, _) = store(storage = storage)
        now = 77_000L
        val result = store.checkForUpdates(manual = true)
        assertTrue(result is NytUpdateResult.Checked)
        assertEquals("2.0", store.policy().sourceVersion)
        // Policy, version and success times are unchanged; only the transient
        // warning records the persistence failure.
        val status = store.status()
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, status.origin)
        assertEquals("2.0", status.version)
        assertEquals(now, status.lastCheckMs)
        assertEquals(now, status.lastSuccessMs)
        assertNull(status.lastError)
        assertTrue(
            "warning was: ${status.warning}",
            status.warning != null && "status write failed" in status.warning &&
                "status disk gone" in status.warning,
        )
        assertEquals(status, (result as NytUpdateResult.Checked).status)
        assertTrue(warnings.any { "status write failed" in it })
        // A later successful write clears the warning.
        storage.failStatusWrite = null
        val retry = store.checkForUpdates(manual = true)
        assertTrue(retry is NytUpdateResult.Checked)
        assertNull(store.status().warning)
        assertNull(retry.status.warning)
    }

    @Test
    fun statusWriteFailureOnFailedUpdate_keepsErrorAndWarningSeparate() {
        val storage = FakeStorage(failStatusWrite = IOException("status disk gone"))
        val offline = FakeDownloader { throw IOException("offline") }
        val failed = NytPolicyStore(bundledJson(), storage, offline, clock, warnings::add)
        now = 42_000L
        val result = failed.checkForUpdates(manual = true) as NytUpdateResult.Failed
        assertTrue("error was: ${result.error}", "offline" in result.error)
        assertEquals("1.0", failed.policy().sourceVersion)
        assertEquals(42_000L, failed.status().lastCheckMs)
        assertEquals(0L, failed.status().lastSuccessMs)
        assertEquals(result.error, failed.status().lastError)
        val warning = failed.status().warning
        assertTrue("warning was: $warning", warning != null && "status disk gone" in warning)
        assertEquals(warning, result.status.warning)
        assertEquals(result.error, result.status.lastError)
    }

    @Test
    fun unsupportedAtomicMove_keepsOldCacheWithConcreteError() {
        val dir = Files.createTempDirectory("nyt-atomic-unsupported").toFile()
        try {
            FileNytPolicyStorage(dir).writePolicyJson(bundledJson(version = "5.0"))
            val oldBytes = File(dir, FileNytPolicyStorage.POLICY_NAME).readBytes()
            val unsupported = FileNytPolicyStorage(
                dir,
                mover = NytFileMover { tmp, dest ->
                    // Same failure the production mover reports when the
                    // filesystem refuses ATOMIC_MOVE: no non-atomic retry.
                    throw IOException(
                        "NYT rule cache atomic replace is not supported on this storage: " +
                            "java.nio.file.AtomicMoveNotSupportedException: " +
                            "$tmp -> $dest: filesystem does not support it",
                    )
                },
            )
            try {
                unsupported.writePolicyJson(bundledJson(version = "6.0"))
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue(
                    "message was: ${e.message}",
                    "atomic replace is not supported" in (e.message ?: ""),
                )
            }
            assertEquals(oldBytes.toList(), File(dir, FileNytPolicyStorage.POLICY_NAME).readBytes().toList())
            val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray<File>()
            assertTrue(leftovers.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultMover_atomicReplaceRoundTrip() {
        // Normal path: the production mover atomically replaces within one
        // directory. The unsupported path cannot be forced on this host's
        // filesystem; its no-fallback behavior is verified by inspection
        // (NytDefaultFileMover attempts only ATOMIC_MOVE).
        val dir = Files.createTempDirectory("nyt-atomic-ok").toFile()
        try {
            val tmp = File(dir, "v.tmp")
            val dest = File(dir, "v.json")
            dest.writeText("{\"v\":1}", Charsets.UTF_8)
            tmp.writeText("{\"v\":2}", Charsets.UTF_8)
            NytDefaultFileMover.move(tmp, dest)
            assertEquals("{\"v\":2}", dest.readText(Charsets.UTF_8))
            assertTrue(!tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun moveFailureWithFailingCleanup_reportsBoth() {
        val dir = Files.createTempDirectory("nyt-cleanup-fail").toFile()
        try {
            FileNytPolicyStorage(dir).writePolicyJson(bundledJson(version = "5.0"))
            val storage = FileNytPolicyStorage(
                dir,
                mover = NytFileMover { _, _ -> throw IOException("injected move failure") },
                deleter = NytFileDeleter { throw IOException("injected cleanup failure") },
            )
            try {
                storage.writePolicyJson(bundledJson(version = "6.0"))
                fail("expected IOException")
            } catch (e: IOException) {
                val msg = e.message ?: ""
                assertTrue("message was: $msg", "injected move failure" in msg)
                assertTrue("message was: $msg", "temp cleanup" in msg && "injected cleanup failure" in msg)
            }
            // False-returning deleter is reported too, without losing the primary.
            val falseDeleter = FileNytPolicyStorage(
                dir,
                mover = NytFileMover { _, _ -> throw IOException("injected move failure") },
                deleter = NytFileDeleter { false },
            )
            try {
                falseDeleter.writePolicyJson(bundledJson(version = "6.0"))
                fail("expected IOException")
            } catch (e: IOException) {
                val msg = e.message ?: ""
                assertTrue("message was: $msg", "injected move failure" in msg)
                assertTrue("message was: $msg", "temp cleanup" in msg && "returned false" in msg)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun partialWriteFailure_cleansTmpAndReportsCleanup() {
        // A regular file as the "directory" makes the temp write fail
        // deterministically on any host.
        val parent = Files.createTempDirectory("nyt-write-fail").toFile()
        try {
            val notDir = File(parent, "not-a-dir")
            assertTrue(notDir.createNewFile())
            val deleting = mutableListOf<String>()
            val storage = FileNytPolicyStorage(
                notDir,
                deleter = NytFileDeleter {
                    deleting.add(it.name)
                    throw IOException("injected cleanup failure")
                },
            )
            try {
                storage.writePolicyJson(bundledJson(version = "6.0"))
                fail("expected IOException")
            } catch (e: IOException) {
                val msg = e.message ?: ""
                assertTrue("message was: $msg", "cache write failed" in msg)
                assertTrue("message was: $msg", "temp cleanup" in msg && "injected cleanup failure" in msg)
            }
            assertEquals(listOf("nyt_policy.json.tmp"), deleting)
        } finally {
            parent.deleteRecursively()
        }
    }
}
