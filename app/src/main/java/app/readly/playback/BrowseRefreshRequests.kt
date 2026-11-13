package app.readly.playback

/** Subscription options survive live updates: each monotonic request must force the network only once. */
class BrowseRefreshRequests {
    private val newestByParent = mutableMapOf<String, Long>()

    @Synchronized fun consume(parentId: String, requestedAt: Long?): Boolean {
        if (requestedAt == null) return false
        require(parentId.isNotBlank() && requestedAt > 0) { "Invalid feed refresh request" }
        if (requestedAt <= (newestByParent[parentId] ?: 0L)) return false
        newestByParent[parentId] = requestedAt
        return true
    }

    companion object {
        const val EXTRA_REQUEST_AT = "app.readly.REFRESH_REQUEST_AT"

        /** Retry rows and pull-to-refresh must subscribe to the same canonical list. */
        fun parent(id: String): String {
            if (!id.startsWith("retry:")) return id
            val target = id.removePrefix("retry:")
            require(target.isNotBlank()) { "Invalid feed list" }
            return if (target == CarBrowser.LATEST || target == CarBrowser.WEBSITES) target else "site:$target"
        }
    }
}
