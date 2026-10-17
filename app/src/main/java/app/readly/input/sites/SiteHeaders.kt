package app.readly.input.sites

/**
 * Shared HTTP header validation for request rules.
 *
 * Values must be transmittable single-line field values; names must be
 * valid tokens. Custom rule data can never set hop-by-hop transport
 * internals, credentials, framing, or the headers owned by dedicated rule
 * fields (`User-Agent` via `useragent`/`useragent_custom`, `Referer` via
 * `referer`/`referer_custom`).
 */
object SiteHeaders {
    /** Longest accepted header name (tokens are short; upstream uses ~10 chars). */
    const val MAX_NAME_LENGTH = 128

    /** Longest accepted header value (the inspected UA is ~50 chars). */
    const val MAX_VALUE_LENGTH = 512

    /**
     * Lowercase names custom data must not set: framing/connection
     * internals, credentials, cookies, and the UA/Referer fields owned by
     * dedicated rule fields (a single source per header, no silent merge).
     */
    val FORBIDDEN_REQUEST_NAMES: Set<String> = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
        "te",
        "trailer",
        "upgrade",
        "keep-alive",
        "proxy-connection",
        "proxy-authorization",
        "proxy-authenticate",
        "authorization",
        "cookie",
        "user-agent",
        "referer",
    )

    /**
     * First character that cannot appear in an HTTP header field value:
     * any C0/C1 control or DEL (including NUL, tab, newline) or anything
     * above Latin-1 that the transport cannot send. `' '` (0x20),
     * visible ASCII (0x21-0x7E) and obs-text (0x80-0xFF) are allowed.
     * Null when the value is transmittable.
     */
    fun findInvalidChar(value: String): Char? {
        for (c in value) {
            val code = c.code
            if (code < 0x20 || code == 0x7F || code > 0xFF) return c
        }
        return null
    }

    /** `U+XXXX` rendering for header-validation errors (never the raw control). */
    fun describeChar(c: Char): String = "U+%04X".format(java.util.Locale.US, c.code)

    /**
     * True for RFC 7230 token names within the length bound. Letters and
     * digits are ASCII-only (`isLetterOrDigit` would admit Unicode).
     */
    fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        for (c in name) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') continue
            if (c in "!#\$%&'*+-.^_`|~") continue
            return false
        }
        return true
    }
}
