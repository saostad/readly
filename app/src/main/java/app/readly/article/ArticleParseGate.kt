package app.readly.article

import app.readly.input.sites.BrowserRead

/**
 * Parse-slot ownership for the article engine: at most one snapshot parses
 * at a time, and every parse completion is scoped to its originating read.
 * A retired parse (its read was replaced by a navigation, or the activity
 * paused) releases nothing and changes nothing: [finish] returns false for
 * any read that does not own the slot, so a stale completion can neither
 * clear a newer parse's busy flag nor overwrite its error/status.
 *
 * Pure JVM (no Android/Gecko): the activity holds this on main while parse
 * completions validate against it, and host tests drive the same methods.
 */
class ArticleParseGate {
    private var owner: BrowserRead? = null

    /** True while a parse owns the slot. */
    fun isBusy(): Boolean = owner != null

    /** True when [read] owns the slot. */
    fun isOwner(read: BrowserRead): Boolean = owner == read

    /**
     * Takes the slot for [read]. Returns false (no change) when another
     * parse already owns it, so a duplicate parse never starts.
     */
    fun tryBegin(read: BrowserRead): Boolean {
        if (owner != null) return false
        owner = read
        return true
    }

    /**
     * Releases the slot when [read] owns it. Returns true only for the
     * owning read; any other read leaves the slot untouched.
     */
    fun finish(read: BrowserRead): Boolean {
        if (owner != read) return false
        owner = null
        return true
    }

    /** Retires the slot (navigation, SPA change, pause, destroy). */
    fun retire() {
        owner = null
    }
}
