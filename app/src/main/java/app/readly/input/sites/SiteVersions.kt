package app.readly.input.sites

/**
 * Numeric dotted version checks shared by the single-site NYT policy and
 * the multi-site catalog. Upstream extension versions look like `4.4.7.4`.
 */
object SiteVersions {
    private val VERSION_RE = Regex("\\d{1,6}(\\.\\d{1,6}){0,7}")

    /** True for numeric dotted versions like `4.4.7.4` (1-8 parts, 1-6 digits each). */
    fun isValidVersion(version: String): Boolean =
        version.length <= 55 && VERSION_RE.matches(version)

    /**
     * Numeric dotted comparison (`4.10` > `4.9`; missing parts are 0, so
     * `4.4` == `4.4.0`). Total: all-digit parts compare by value at any
     * size, anything else compares lexically.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = comparePart(pa.getOrElse(i) { "0" }, pb.getOrElse(i) { "0" })
            if (c != 0) return c
        }
        return 0
    }

    private fun comparePart(x: String, y: String): Int {
        if (x.all { it.isDigit() } && y.all { it.isDigit() }) {
            val sx = x.trimStart('0').ifEmpty { "0" }
            val sy = y.trimStart('0').ifEmpty { "0" }
            val byLength = sx.length.compareTo(sy.length)
            if (byLength != 0) return byLength
            return sx.compareTo(sy)
        }
        return x.compareTo(y)
    }
}
