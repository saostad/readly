package app.readly.input.sites

/**
 * Data-only model for one literal JavaScript value inside the declared
 * `defaultSites` object: quoted/unquoted keys, strings with escapes,
 * numbers, booleans, null, bounded objects/arrays, and opaque RegExp
 * tokens. There is no representation for functions, calls, or any other
 * dynamic expression: those are rejected while reading, never modeled.
 */
internal sealed interface JsValue {
    data class Str(val value: String) : JsValue
    data class Num(val raw: String) : JsValue
    data class Bool(val value: Boolean) : JsValue
    data object Null : JsValue
    /** Inner source of a `/.../flags` literal; flags are accepted and ignored. */
    data class Regex(val source: String) : JsValue
    data class Obj(val entries: Map<String, JsValue>) : JsValue
    data class Arr(val items: List<JsValue>) : JsValue
}

/**
 * A bounded literal value is malformed (truncated input, bad escapes,
 * unbalanced brackets). The message names the operation and file.
 */
internal open class JsDataException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * A well-formed literal uses a construct the data-only reader does not
 * support (template literals, dynamic expressions, duplicate fields,
 * nesting beyond the bound). Callers map this to their own
 * "valid but unsupported" error without executing anything.
 */
internal class JsDataUnsupportedException(message: String, cause: Throwable? = null) :
    JsDataException(message, cause)
