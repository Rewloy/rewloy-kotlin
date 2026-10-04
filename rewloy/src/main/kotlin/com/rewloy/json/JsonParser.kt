package com.rewloy.json

/**
 * A strict JSON reader (RFC 8259): one value and nothing after it. Objects keep their members' order; a repeated
 * name keeps the last value. Nesting deeper than [MAX_DEPTH] is refused rather than left to overflow the stack.
 */
internal class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): JsonValue {
        skipWhitespace()
        if (pos == text.length) fail("empty document")
        val value = readValue(0)
        skipWhitespace()
        if (pos != text.length) fail("unexpected '${text[pos]}' after the value")
        return value
    }

    private fun fail(message: String): Nothing = throw JsonParseException("$message (at offset $pos)")

    private fun skipWhitespace() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
        }
    }

    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH levels deep")
        if (pos >= text.length) fail("unexpected end of input")
        return when (val c = text[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonString(readString())
            't' -> literal("true", JsonBoolean.TRUE)
            'f' -> literal("false", JsonBoolean.FALSE)
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) fail("unexpected '${text[pos]}'")
        pos += word.length
        return value
    }

    private fun readObject(depth: Int): JsonValue {
        pos++ // {
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != '"') fail("expected a member name")
            val name = readString()
            skipWhitespace()
            if (pos >= text.length || text[pos] != ':') fail("expected ':' after \"$name\"")
            pos++
            skipWhitespace()
            members[name] = readValue(depth + 1)
            skipWhitespace()
            if (pos >= text.length) fail("unterminated object")
            when (text[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObject(members)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue {
        pos++ // [
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items.add(readValue(depth + 1))
            skipWhitespace()
            if (pos >= text.length) fail("unterminated array")
            when (text[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val start = pos
        // Fast path: no escapes.
        while (pos < text.length) {
            val c = text[pos]
            if (c == '"') {
                val s = text.substring(start, pos)
                pos++
                return s
            }
            if (c == '\\') break
            if (c < ' ') fail("a control character in a string")
            pos++
        }
        val sb = StringBuilder()
        sb.append(text, start, pos)
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    return sb.toString()
                }
                c == '\\' -> {
                    pos++
                    if (pos >= text.length) fail("unterminated escape")
                    when (val e = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= text.length) fail("a short \\u escape")
                            var code = 0
                            for (i in 1..4) {
                                val d = Character.digit(text[pos + i], 16)
                                if (d < 0) fail("a bad \\u escape")
                                code = code * 16 + d
                            }
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("a bad escape '\\$e'")
                    }
                    pos++
                }
                c < ' ' -> fail("a control character in a string")
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        fail("unterminated string")
    }

    private fun readNumber(): JsonValue {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= text.length) fail("a number without digits")
        if (text[pos] == '0') {
            pos++
        } else if (text[pos] in '1'..'9') {
            while (pos < text.length && text[pos] in '0'..'9') pos++
        } else {
            fail("a number without digits")
        }
        if (pos < text.length && text[pos] == '.') {
            pos++
            val digits = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digits) fail("a fraction without digits")
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            val digits = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digits) fail("an exponent without digits")
        }
        return JsonNumber(text.substring(start, pos))
    }

    companion object {
        const val MAX_DEPTH = 512

        /** Whether [s] is a JSON number as RFC 8259 writes it. */
        fun isNumber(s: String): Boolean {
            var i = 0
            val n = s.length
            if (i < n && s[i] == '-') i++
            if (i >= n) return false
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') while (i < n && s[i] in '0'..'9') i++
            else return false
            if (i < n && s[i] == '.') {
                i++
                val d = i
                while (i < n && s[i] in '0'..'9') i++
                if (i == d) return false
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < n && s[i] in '0'..'9') i++
                if (i == d) return false
            }
            return i == n
        }
    }
}
