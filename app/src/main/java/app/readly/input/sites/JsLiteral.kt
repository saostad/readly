package app.readly.input.sites

/**
 * Shared iterative lexer for the official update archive's `sites.js`.
 *
 * Extracted from the single-site NYT parser so both importers scan the
 * same bytes the same way: the declared top-level `defaultSites` object is
 * located outside strings/comments/regex literals, its direct entries are
 * enumerated with balanced-nesting tracking, and values are read as
 * bounded data-only literals. There is no `eval`, no JavaScript engine,
 * and no pattern spanning unrelated objects.
 *
 * Every function takes the caller's [label] (`"NYT rule update"`,
 * `"Site rule update"`) so error messages name the operation exactly as
 * each importer's tests expect.
 */
internal object JsLiteral {
    /** Deepest nested object/array accepted inside one literal value. */
    const val MAX_LITERAL_DEPTH = 8

    /** Most keys accepted in one literal object. */
    const val MAX_LITERAL_KEYS = 256

    /** Most items accepted in one literal array. */
    const val MAX_LITERAL_ITEMS = 2048

    /** Bounds of the declared `defaultSites` object: opening `{` to matching `}`. */
    data class RootBounds(val open: Int, val close: Int)

    /**
     * One direct `"key": <value>` property of the `defaultSites` object.
     * [valuePos] is the index just after the `:`; nested mentions inside
     * another value never match.
     */
    data class DirectEntry(val key: String, val valuePos: Int)

    /**
     * Locates every top-level `var|let|const defaultSites = { ... }`
     * declaration outside strings/comments/regex. Each result spans from its
     * opening `{` to the matching `}`. A member (`obj.defaultSites`), a
     * nested function-local, or a bare assignment without a declaration
     * keyword never counts as the declared root; the root value must also
     * end at its closing brace (no `.missing`/operator suffix). Template
     * literals are rejected as unsupported rather than mis-scanned.
     */
    fun findDefaultSitesRoots(s: String, label: String): List<RootBounds> {
        val roots = mutableListOf<RootBounds>()
        var i = 0
        var lastSig = '\u0000'
        var lastWord = ""
        var braces = 0
        var brackets = 0
        var parens = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> i = skipLineComment(s, i)
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> i = skipBlockComment(s, i, label)
                c == '"' || c == '\'' -> {
                    val (_, end) = readString(s, i, label)
                    i = end
                    lastSig = '"'
                    lastWord = ""
                }
                c == '`' -> throw JsDataUnsupportedException(
                    "$label \"sites.js\" uses unsupported template literal",
                )
                c == '/' -> {
                    if (isRegexStart(lastSig, lastWord)) {
                        i = skipRegex(s, i, label)
                        lastSig = 'x'
                    } else {
                        i++
                        lastSig = '/'
                    }
                    lastWord = ""
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    val word = s.substring(start, i)
                    if (word == "defaultSites" && braces == 0 && brackets == 0 && parens == 0 &&
                        lastSig == 'w' && (lastWord == "var" || lastWord == "let" || lastWord == "const")
                    ) {
                        val eq = skipWsAndComments(s, i, label)
                        if (eq < s.length && s[eq] == '=' &&
                            (eq + 1 >= s.length || (s[eq + 1] != '=' && s[eq + 1] != '>'))
                        ) {
                            val open = skipWsAndComments(s, eq + 1, label)
                            if (open < s.length && s[open] == '{') {
                                val close = findMatchingBrace(s, open, label)
                                checkRootSuffix(s, close, label)
                                roots.add(RootBounds(open, close))
                                // Skip past the whole object so interior text
                                // cannot produce a second root; top-level
                                // duplicates after it are still found.
                                i = close + 1
                                lastSig = '}'
                                lastWord = ""
                                continue
                            }
                        }
                    }
                    lastWord = word
                    lastSig = 'w'
                }
                c.isDigit() -> {
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) {
                        i++
                    }
                    lastSig = '0'
                    lastWord = ""
                }
                else -> {
                    when (c) {
                        '{' -> braces++
                        '}' -> if (braces > 0) braces--
                        '[' -> brackets++
                        ']' -> if (brackets > 0) brackets--
                        '(' -> parens++
                        ')' -> if (parens > 0) parens--
                    }
                    lastSig = c
                    lastWord = ""
                    i++
                }
            }
        }
        return roots
    }

    /**
     * Requires the declared root value to end at [close]: a property access,
     * call, index or operator suffix (`.missing`, `(1)`, `[0]`, `+x`, …)
     * means this is not the supported static declaration.
     */
    fun checkRootSuffix(s: String, close: Int, label: String) {
        val j = skipWsAndComments(s, close + 1, label)
        if (j >= s.length) return
        val next = s[j]
        if (next == '.' || next == '(' || next == '[' || next in "+-*/%<>&|!?^~=:") {
            throw JsDataUnsupportedException(
                "$label \"defaultSites\" declaration has unsupported suffix " +
                    "\"$next\": expected the end of the declaration after the object",
            )
        }
    }

    /** Index of the `}` matching the `{` at [open]; strings/comments/regex are skipped. */
    fun findMatchingBrace(s: String, open: Int, label: String): Int {
        var depth = 1
        var i = open + 1
        var lastSig = '{'
        var lastWord = ""
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> i = skipLineComment(s, i)
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> i = skipBlockComment(s, i, label)
                c == '"' || c == '\'' -> {
                    val (_, end) = readString(s, i, label)
                    i = end
                    lastSig = '"'
                    lastWord = ""
                }
                c == '`' -> throw JsDataUnsupportedException(
                    "$label \"sites.js\" uses unsupported template literal",
                )
                c == '/' -> {
                    if (isRegexStart(lastSig, lastWord)) {
                        i = skipRegex(s, i, label)
                        lastSig = 'x'
                    } else {
                        i++
                        lastSig = '/'
                    }
                    lastWord = ""
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    val word = s.substring(start, i)
                    lastSig = 'w'
                    lastWord = if (word in REGEX_KEYWORDS) word else ""
                }
                c.isDigit() -> {
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) {
                        i++
                    }
                    lastSig = '0'
                    lastWord = ""
                }
                c == '{' -> {
                    depth++
                    lastSig = '{'
                    lastWord = ""
                    i++
                }
                c == '}' -> {
                    depth--
                    lastSig = '}'
                    lastWord = ""
                    if (depth == 0) return i
                    i++
                }
                else -> {
                    lastSig = c
                    lastWord = ""
                    i++
                }
            }
        }
        throw JsDataException(
            "$label \"sites.js\" has unterminated \"defaultSites\" object",
        )
    }

    /**
     * Every direct `"key": <value>` (or unquoted `key: <value>`) property of
     * the `defaultSites` object spanning [rootOpen]..[rootClose], in source
     * order, with a strict enclosing grammar: entries need `key: value`
     * separated by commas, brackets must nest correctly, and junk between
     * entries rejects the catalog instead of silently narrowing it. A
     * well-delimited dynamic/unsupported value inside one proper entry is
     * still collected here and isolated per-site by the caller. Nested
     * mentions and unrelated objects outside the root never match.
     */
    fun scanDirectEntries(s: String, rootOpen: Int, rootClose: Int, label: String): List<DirectEntry> {
        val found = mutableListOf<DirectEntry>()
        var i = rootOpen + 1
        while (true) {
            i = skipWsAndComments(s, i, label)
            if (i >= rootClose) break
            val c = s[i]
            if (c == '}') {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" has unexpected '}' in \"defaultSites\"",
                )
            }
            if (c == ']') {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" has unexpected ']' in \"defaultSites\"",
                )
            }
            if (c == ')') {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" has unexpected ')' in \"defaultSites\"",
                )
            }
            if (c == ',') {
                throw JsDataException(
                    "$label \"sites.js\" \"defaultSites\" has invalid entry: " +
                        "expected a site key but found ','",
                )
            }
            if (c == '`') {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" uses unsupported template literal",
                )
            }
            val key: String
            when {
                c == '"' || c == '\'' -> {
                    val (v, e) = readString(s, i, label)
                    key = v
                    i = e
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    key = s.substring(start, i)
                }
                else -> throw JsDataException(
                    "$label \"sites.js\" \"defaultSites\" has invalid entry: " +
                        "expected a site key but found '$c'",
                )
            }
            i = skipWsAndComments(s, i, label)
            if (i >= s.length || s[i] != ':') {
                throw JsDataException(
                    "$label \"sites.js\" \"defaultSites\" entry \"${key.take(64)}\" is missing ':'",
                )
            }
            val valuePos = i + 1
            found.add(DirectEntry(key, valuePos))
            i = skipRootValue(s, valuePos, rootClose, label, found)
            if (i >= rootClose) break
            i++ // Consume the ','; a trailing comma before '}' is allowed.
        }
        return found
    }

    /**
     * Skips one root-level entry value from [from] to the top-level ',' or
     * [rootClose], with a real bracket stack (crossed delimiters reject). A
     * `key:` after a complete value without a comma rejects as a missing
     * comma; operators and suffixes inside the value are allowed so the
     * caller can still isolate one dynamic entry per-site. Returns the index
     * of the ',' or [rootClose]. [found] names the entry for errors.
     */
    private fun skipRootValue(
        s: String,
        from: Int,
        rootClose: Int,
        label: String,
        found: List<DirectEntry>,
    ): Int {
        var i = from
        val stack = ArrayDeque<Char>()
        // Last significant top-level token: ':' = start of value; a value end
        // (balanced closer, string, number, word, regex) before a `key:`
        // means the comma is missing; operators continue the same value.
        var lastTop = ':'
        var lastSig = ':'
        var lastWord = ""
        fun isValueEnd(c: Char): Boolean =
            c == '}' || c == ']' || c == ')' || c == '"' || c == '0' || c == 'w' || c == 'x'
        while (true) {
            i = skipWsAndComments(s, i, label)
            if (i >= rootClose) {
                if (stack.isNotEmpty()) {
                    throw JsDataUnsupportedException(
                        "$label \"sites.js\" has unbalanced brackets in \"defaultSites\": " +
                            "refusing to guess the entry",
                    )
                }
                return rootClose
            }
            val c = s[i]
            val top = stack.isEmpty()
            when {
                c == '"' || c == '\'' -> {
                    val (_, end) = readString(s, i, label)
                    if (top) {
                        val j = skipWsAndComments(s, end, label)
                        if (j < s.length && s[j] == ':' && isValueEnd(lastTop)) {
                            val prev = found.lastOrNull()?.key?.take(64) ?: "?"
                            throw JsDataException(
                                "$label \"sites.js\" \"defaultSites\" is missing ',' " +
                                    "between site entries (after \"$prev\")",
                            )
                        }
                        lastTop = '"'
                    }
                    lastSig = '"'
                    lastWord = ""
                    i = end
                }
                c == '`' -> throw JsDataUnsupportedException(
                    "$label \"sites.js\" uses unsupported template literal",
                )
                c == '/' -> {
                    // Comments are already skipped, so this is code.
                    if (isRegexStart(lastSig, lastWord)) {
                        i = skipRegex(s, i, label)
                        if (top) lastTop = 'x'
                        lastSig = 'x'
                    } else {
                        if (top) lastTop = '/'
                        lastSig = '/'
                        i++
                    }
                    lastWord = ""
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    val word = s.substring(start, i)
                    if (top) {
                        val j = skipWsAndComments(s, i, label)
                        if (j < s.length && s[j] == ':' && isValueEnd(lastTop)) {
                            val prev = found.lastOrNull()?.key?.take(64) ?: "?"
                            throw JsDataException(
                                "$label \"sites.js\" \"defaultSites\" is missing ',' " +
                                    "between site entries (after \"$prev\")",
                            )
                        }
                        lastTop = 'w'
                    }
                    lastSig = 'w'
                    lastWord = if (word in REGEX_KEYWORDS) word else ""
                }
                c.isDigit() -> {
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) {
                        i++
                    }
                    if (top) lastTop = '0'
                    lastSig = '0'
                    lastWord = ""
                }
                c == '{' || c == '[' || c == '(' -> {
                    stack.addLast(c)
                    lastSig = c
                    lastWord = ""
                    i++
                }
                c == '}' || c == ']' || c == ')' -> {
                    if (top) {
                        if (c == '}') {
                            throw JsDataUnsupportedException(
                                "$label \"sites.js\" has unexpected '}' in \"defaultSites\"",
                            )
                        }
                        if (c == ']') {
                            throw JsDataUnsupportedException(
                                "$label \"sites.js\" has unexpected ']' in \"defaultSites\"",
                            )
                        }
                        throw JsDataUnsupportedException(
                            "$label \"sites.js\" has unexpected ')' in \"defaultSites\"",
                        )
                    }
                    val open = stack.removeLast()
                    val ok = (open == '{' && c == '}') ||
                        (open == '[' && c == ']') ||
                        (open == '(' && c == ')')
                    if (!ok) {
                        val want = when (open) {
                            '{' -> '}'
                            '[' -> ']'
                            else -> ')'
                        }
                        throw JsDataUnsupportedException(
                            "$label \"sites.js\" has crossed brackets in \"defaultSites\": " +
                                "expected '$want' but found '$c'",
                        )
                    }
                    if (stack.isEmpty()) lastTop = c
                    lastSig = c
                    lastWord = ""
                    i++
                }
                c == ',' -> {
                    if (top) return i
                    lastSig = ','
                    lastWord = ""
                    i++
                }
                c == ';' && top -> throw JsDataException(
                    "$label \"sites.js\" \"defaultSites\" is missing ',' between site entries",
                )
                else -> {
                    if (top) lastTop = c
                    lastSig = c
                    lastWord = ""
                    i++
                }
            }
        }
    }

    /**
     * Reads one bounded literal value at [from]: string, number, boolean,
     * null, object, array, or opaque regex token. Returns the value plus the
     * index just after it. Anything dynamic (identifiers other than
     * true/false/null, calls, operators, template literals) is
     * [JsDataUnsupportedException]; truncated input is [JsDataException].
     * [what] names the value for errors (e.g. `"key" field "cs_code"`).
     */
    fun readLiteral(s: String, from: Int, label: String, what: String, depth: Int = 0): Pair<JsValue, Int> {
        var i = skipWsAndComments(s, from, label)
        if (i >= s.length) {
            throw JsDataException("$label \"sites.js\" $what is unterminated")
        }
        val c = s[i]
        when {
            c == '"' || c == '\'' -> {
                val (v, e) = readString(s, i, label)
                return JsValue.Str(v) to e
            }
            c == '-' || c.isDigit() -> {
                if (c == '-' && (i + 1 >= s.length || !s[i + 1].isDigit())) {
                    throw JsDataUnsupportedException(
                        "$label \"sites.js\" $what has unsupported dynamic expression",
                    )
                }
                val (num, e) = readNumber(s, i)
                return JsValue.Num(num) to e
            }
            c == '/' && !isCommentStart(s, i) -> {
                val (source, e) = readRegex(s, i, label)
                return JsValue.Regex(source) to e
            }
            c == '{' -> return readLiteralObject(s, i, label, what, depth)
            c == '[' -> return readLiteralArray(s, i, label, what, depth)
            c == '`' -> throw JsDataUnsupportedException(
                "$label \"sites.js\" $what uses unsupported template literal",
            )
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                    i++
                }
                return when (val word = s.substring(start, i)) {
                    "true" -> JsValue.Bool(true) to i
                    "false" -> JsValue.Bool(false) to i
                    "null" -> JsValue.Null to i
                    else -> throw JsDataUnsupportedException(
                        "$label \"sites.js\" $what has unsupported dynamic value \"$word\"",
                    )
                }
            }
            c in "+*/%<>&|!?^~=(." -> throw JsDataUnsupportedException(
                "$label \"sites.js\" $what has unsupported dynamic expression",
            )
            else -> throw JsDataException(
                "$label \"sites.js\" $what has unexpected character '$c'",
            )
        }
    }

    private fun readLiteralObject(
        s: String,
        open: Int,
        label: String,
        what: String,
        depth: Int,
    ): Pair<JsValue, Int> {
        if (depth >= MAX_LITERAL_DEPTH) {
            throw JsDataUnsupportedException(
                "$label \"sites.js\" $what nests deeper than $MAX_LITERAL_DEPTH levels",
            )
        }
        var i = open + 1
        val entries = LinkedHashMap<String, JsValue>()
        while (true) {
            i = skipWsAndComments(s, i, label)
            if (i >= s.length) {
                throw JsDataException("$label \"sites.js\" $what is unterminated")
            }
            if (s[i] == '}') return JsValue.Obj(entries) to i + 1
            val field: String
            when {
                s[i] == '"' || s[i] == '\'' -> {
                    val (v, e) = readString(s, i, label)
                    field = v
                    i = e
                }
                s[i].isLetter() || s[i] == '_' || s[i] == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    field = s.substring(start, i)
                }
                else -> throw JsDataException(
                    "$label \"sites.js\" $what has unexpected character '${s[i]}'",
                )
            }
            if (field in entries) {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" $what has duplicate field \"$field\"",
                )
            }
            if (entries.size >= MAX_LITERAL_KEYS) {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" $what has more than $MAX_LITERAL_KEYS fields",
                )
            }
            i = skipWsAndComments(s, i, label)
            if (i >= s.length || s[i] != ':') {
                throw JsDataException(
                    "$label \"sites.js\" $what: expected ':' after field \"$field\"",
                )
            }
            val (v, e) = readLiteral(s, i + 1, label, "$what field \"$field\"", depth + 1)
            entries[field] = v
            i = skipWsAndComments(s, e, label)
            if (i >= s.length) {
                throw JsDataException("$label \"sites.js\" $what is unterminated")
            }
            if (s[i] == ',') {
                i++
                continue
            }
            if (s[i] == '}') return JsValue.Obj(entries) to i + 1
            if (s[i] in "+-*/%<>&|!?^~=(.") {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" $what has unsupported dynamic expression",
                )
            }
            throw JsDataException(
                "$label \"sites.js\" $what: expected ',' or '}' after field \"$field\"",
            )
        }
    }

    private fun readLiteralArray(
        s: String,
        open: Int,
        label: String,
        what: String,
        depth: Int,
    ): Pair<JsValue, Int> {
        if (depth >= MAX_LITERAL_DEPTH) {
            throw JsDataUnsupportedException(
                "$label \"sites.js\" $what nests deeper than $MAX_LITERAL_DEPTH levels",
            )
        }
        var i = open + 1
        val items = mutableListOf<JsValue>()
        while (true) {
            i = skipWsAndComments(s, i, label)
            if (i >= s.length) {
                throw JsDataException("$label \"sites.js\" $what is unterminated")
            }
            if (s[i] == ']') return JsValue.Arr(items) to i + 1
            if (items.size >= MAX_LITERAL_ITEMS) {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" $what has more than $MAX_LITERAL_ITEMS items",
                )
            }
            val (v, e) = readLiteral(s, i, label, "$what item ${items.size}", depth + 1)
            items.add(v)
            i = skipWsAndComments(s, e, label)
            if (i >= s.length) {
                throw JsDataException("$label \"sites.js\" $what is unterminated")
            }
            if (s[i] == ',') {
                i++
                continue
            }
            if (s[i] == ']') return JsValue.Arr(items) to i + 1
            if (s[i] in "+-*/%<>&|!?^~=(.") {
                throw JsDataUnsupportedException(
                    "$label \"sites.js\" $what has unsupported dynamic expression",
                )
            }
            throw JsDataException(
                "$label \"sites.js\" $what: expected ',' or ']' after item ${items.size - 1}",
            )
        }
    }

    private val REGEX_KEYWORDS = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete",
        "void", "throw", "case", "do", "else", "yield", "await",
    )

    fun isRegexStart(lastSig: Char, lastWord: String): Boolean {
        if (lastWord in REGEX_KEYWORDS) return true
        if (lastSig == '\u0000') return true
        return lastSig in "{},;:([=!&|?+-*%<>^~"
    }

    // ------------------------------------------------------------------
    // JS micro-lexer helpers (iterative, single pass over bounded input)
    // ------------------------------------------------------------------

    fun isCommentStart(s: String, i: Int): Boolean =
        s[i] == '/' && i + 1 < s.length && (s[i + 1] == '/' || s[i + 1] == '*')

    fun skipWsAndComments(s: String, from: Int, label: String): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                i = skipLineComment(s, i)
            } else if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i = skipBlockComment(s, i, label)
            } else {
                break
            }
        }
        return i
    }

    fun skipLineComment(s: String, i: Int): Int {
        var j = i + 2
        while (j < s.length && s[j] != '\n') j++
        return j
    }

    fun skipBlockComment(s: String, i: Int, label: String): Int {
        var j = i + 2
        while (j + 1 < s.length && !(s[j] == '*' && s[j + 1] == '/')) j++
        if (j + 1 >= s.length) {
            throw JsDataException("$label \"sites.js\" has unterminated comment")
        }
        return j + 2
    }

    /** Decoded string plus the index just after the closing quote. */
    fun readString(s: String, i: Int, label: String): Pair<String, Int> {
        val quote = s[i]
        val out = StringBuilder()
        var j = i + 1
        while (true) {
            if (j >= s.length) {
                throw JsDataException(
                    "$label \"sites.js\" has unterminated string literal",
                )
            }
            val c = s[j]
            if (c == quote) return out.toString() to j + 1
            if (c == '\n' || c == '\r') {
                throw JsDataException(
                    "$label \"sites.js\" has unterminated string literal",
                )
            }
            if (c != '\\') {
                out.append(c)
                j++
                continue
            }
            if (j + 1 >= s.length) {
                throw JsDataException(
                    "$label \"sites.js\" has unterminated string literal",
                )
            }
            val e = s[j + 1]
            when (e) {
                '\n' -> j += 2 // Line continuation.
                '\r' -> j += if (j + 2 < s.length && s[j + 2] == '\n') 3 else 2
                'b' -> { out.append('\b'); j += 2 }
                'f' -> { out.append('\u000C'); j += 2 }
                'n' -> { out.append('\n'); j += 2 }
                'r' -> { out.append('\r'); j += 2 }
                't' -> { out.append('\t'); j += 2 }
                'v' -> { out.append('\u000B'); j += 2 }
                '0' -> {
                    if (j + 2 < s.length && s[j + 2].isDigit()) {
                        throw JsDataUnsupportedException(
                            "$label \"sites.js\" uses unsupported octal escape",
                        )
                    }
                    out.append('\u0000')
                    j += 2
                }
                in '1'..'7' -> {
                    throw JsDataUnsupportedException(
                        "$label \"sites.js\" uses unsupported octal escape",
                    )
                }
                'x' -> {
                    out.append(readHex(s, j + 2, 2, "string literal", label))
                    j += 4
                }
                'u' -> {
                    if (j + 2 < s.length && s[j + 2] == '{') {
                        val end = s.indexOf('}', j + 3)
                        if (end < 0 || end - (j + 3) !in 1..6) {
                            throw JsDataException(
                                "$label \"sites.js\" has invalid unicode escape",
                            )
                        }
                        val cp = s.substring(j + 3, end).toIntOrNull(16)
                            ?: throw JsDataException(
                                "$label \"sites.js\" has invalid unicode escape",
                            )
                        out.appendCodePointCompat(cp, label)
                        j = end + 1
                    } else {
                        out.append(readHex(s, j + 2, 4, "string literal", label))
                        j += 6
                    }
                }
                // Quote, backslash, slash and any other char: the char itself.
                else -> { out.append(e); j += 2 }
            }
        }
    }

    private fun readHex(s: String, at: Int, digits: Int, what: String, label: String): Char {
        if (at + digits > s.length) {
            throw JsDataException("$label \"sites.js\" has invalid escape in $what")
        }
        val v = s.substring(at, at + digits).toIntOrNull(16)
            ?: throw JsDataException("$label \"sites.js\" has invalid escape in $what")
        return v.toChar()
    }

    private fun StringBuilder.appendCodePointCompat(cp: Int, label: String) {
        if (cp < 0 || cp > 0x10FFFF) {
            throw JsDataException("$label \"sites.js\" has invalid unicode escape")
        }
        append(String(Character.toChars(cp)))
    }

    /** Regex inner source plus the index just after any flags. Flags are accepted and ignored. */
    fun readRegex(s: String, i: Int, label: String): Pair<String, Int> {
        var j = i + 1
        var inClass = false
        var classFirst = false
        while (true) {
            if (j >= s.length) {
                throw JsDataException(
                    "$label \"sites.js\" has unterminated regex literal",
                )
            }
            val c = s[j]
            if (c == '\\') {
                if (j + 1 >= s.length) {
                    throw JsDataException(
                        "$label \"sites.js\" has unterminated regex literal",
                    )
                }
                if (inClass) classFirst = false
                j += 2
                continue
            }
            if (c == '\n' || c == '\r') {
                throw JsDataException(
                    "$label \"sites.js\" has unterminated regex literal",
                )
            }
            if (!inClass && c == '/') {
                val source = s.substring(i + 1, j)
                j++
                while (j < s.length && s[j] in 'a'..'z') j++
                return source to j
            }
            if (!inClass && c == '[') {
                inClass = true
                classFirst = true
                j++
                continue
            }
            if (inClass) {
                if (c == ']' && !classFirst) inClass = false
                classFirst = false
            }
            j++
        }
    }

    fun skipRegex(s: String, i: Int, label: String): Int = readRegex(s, i, label).second

    fun readNumber(s: String, i: Int): Pair<String, Int> {
        var j = i
        if (s[j] == '-') j++
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_')) j++
        return s.substring(i, j) to j
    }
}
