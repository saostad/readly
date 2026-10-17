package app.readly.article

import app.readly.input.sites.BrowserRead
import java.util.UUID

/**
 * Native-issued capture request binding: each armed read gets a fresh random
 * token, posted to the owned helper over its message port. Snapshot messages
 * must echo the current read's token; a late message from a prior document
 * carries a retired token and is rejected even when its URL still matches
 * (same-URL reload), where URL checks alone cannot tell documents apart.
 *
 * Pure JVM (no Android/Gecko): the article activity drives this on main, and
 * host tests drive the same methods. Token generation is injectable so tests
 * use fixed values.
 */
class ArticleCaptureAuth(
    private val nextToken: () -> String = { UUID.randomUUID().toString() },
) {
    private var read: BrowserRead? = null
    private var token: String? = null

    /**
     * Arms [read] with a fresh token, retiring any previous binding.
     * Returns the token to post to the helper.
     */
    fun arm(read: BrowserRead): String {
        val issued = nextToken()
        this.read = read
        this.token = issued
        return issued
    }

    /** Retires the current binding (navigation, SPA change, pause, destroy). */
    fun retire() {
        read = null
        token = null
    }

    /** True when [candidate] echoes the token issued for [forRead]. */
    fun accepts(forRead: BrowserRead, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return read == forRead && token == candidate
    }

    /** The live token for [forRead], or null when nothing is armed. */
    fun currentToken(forRead: BrowserRead): String? =
        if (read == forRead) token else null
}
