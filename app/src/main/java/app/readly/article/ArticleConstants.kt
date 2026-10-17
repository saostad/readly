package app.readly.article

/** Fixed inputs for the production article engine (GeckoView + actual BPC). */
object ArticleConstants {
    // Actual upstream Bypass Paywalls Clean (Firefox edition), bundled
    // byte-identical under assets/bpc/bpc.xpi. Never patched or reimplemented.
    const val BPC_ID = "magnolia@12.34"
    const val BPC_EXPECTED_VERSION = "4.4.7.2"
    const val BPC_XPI_ASSET = "bpc/bpc.xpi"
    const val BPC_XPI_SHA256 =
        "ca3d938a9def4f2feb0ddd26f122b7701e22da45871a183e2b2ca0219669db3e"
    const val BPC_UPDATE_URL =
        "https://gitflic.ru/project/magnolia1234/bpc_updates/blob/raw?file=updates.json"

    // Own minimal DOM-capture helper (built-in extension, native messaging).
    const val CAPTURE_ID = "readly-capture@readly.app"
    const val CAPTURE_LOCATION = "resource://android/assets/readly-capture/"
    const val NATIVE_APP = "readlyArticle"
    /** Expected helper manifest version; stale installs are reinstalled. */
    const val CAPTURE_VERSION = "1.1"

    // Pinned engine.
    const val GECKOVIEW_VERSION = "158.0.20261007201254"

    // Bounded waits. Expiry always reports an explicit timeout state; nothing
    // is ever declared ready because a timer fired.
    const val EXT_TIMEOUT_MS = 90_000L
    const val PAGE_STOP_TIMEOUT_MS = 120_000L
    const val CAPTURE_TIMEOUT_MS = 150_000L

    /** Add-on update throttle: at most one automatic check per 24h. */
    const val UPDATE_THROTTLE_MS = 24L * 60 * 60 * 1000

    /** Add-on update watchdog: a hung check fails visibly after this long. */
    const val UPDATE_TIMEOUT_MS = 60_000L

    const val PREF_BPC_LAST_CHECK_MS = "bpcLastCheckMs"
    const val PREF_BPC_INSTALLED_VERSION = "bpcInstalledVersion"
    const val PREF_BPC_LAST_ERROR = "bpcLastError"
}
