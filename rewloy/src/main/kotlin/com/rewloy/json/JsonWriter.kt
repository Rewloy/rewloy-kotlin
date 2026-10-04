package com.rewloy.json

/**
 * Writes a [JsonValue] as JSON text, compact or indented by two spaces. The indented form is exactly what
 * JavaScript's `JSON.stringify(value, null, 2)` writes, which is how the OpenAPI snapshot in this repository is
 * kept.
 */
public object JsonWriter {
    /** The value as compact JSON. */
    @JvmStatic
    public fun stringify(value: JsonValue): String {
        val sb = StringBuilder()
        write(sb, value, null, 0)
        return sb.toString()
    }

    /** The value as JSON indented by two spaces, as `JSON.stringify(value, null, 2)` writes it. */
    @JvmStatic
    public fun stringifyPretty(value: JsonValue): String {
        val sb = StringBuilder()
        write(sb, value, "  ", 0)
        return sb.toString()
    }

    internal fun write(sb: StringBuilder, value: JsonValue, indent: String?, level: Int) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBoolean -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> sb.append(value.text)
            is JsonString -> quote(sb, value.value)
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in value.items) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    write(sb, item, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append(']')
            }
            is JsonObject -> {
                if (value.members.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((name, member) in value.members) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    quote(sb, name)
                    sb.append(if (indent == null) ":" else ": ")
                    write(sb, member, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: String?, level: Int) {
        if (indent == null) return
        sb.append('\n')
        for (i in 0 until level) sb.append(indent)
    }

    private const val HEX = "0123456789abcdef"

    internal fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> unicode(sb, c)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1])
                        i++
                    } else {
                        unicode(sb, c)
                    }
                }
                Character.isLowSurrogate(c) -> unicode(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun unicode(sb: StringBuilder, c: Char) {
        val code = c.code
        sb.append("\\u").append(HEX[(code shr 12) and 15]).append(HEX[(code shr 8) and 15]).append(HEX[(code shr 4) and 15]).append(HEX[code and 15])
    }
}
