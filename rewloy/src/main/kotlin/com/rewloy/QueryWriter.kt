package com.rewloy

/**
 * Writes a query string the way the platform's own clients do: RFC 3986 percent-encoding (`%20`, not `+`),
 * booleans as `true`/`false`, numbers without a locale, a list as its key repeated, a `null` left out.
 */
internal class QueryWriter {
    private val sb = StringBuilder()

    private fun key(name: String): StringBuilder {
        if (sb.isNotEmpty()) sb.append('&')
        return sb.append(UrlEncoding.encode(name)).append('=')
    }

    fun add(name: String, value: String?) {
        if (value != null) key(name).append(UrlEncoding.encode(value))
    }

    fun add(name: String, value: Boolean?) {
        if (value != null) key(name).append(if (value) "true" else "false")
    }

    fun add(name: String, value: Int?) {
        if (value != null) key(name).append(value.toString())
    }

    fun add(name: String, value: Long?) {
        if (value != null) key(name).append(value.toString())
    }

    fun add(name: String, value: Double?) {
        if (value != null) key(name).append(com.rewloy.json.JsonValue.of(value).toString())
    }

    fun addMany(name: String, values: List<String>?) {
        if (values != null) for (v in values) add(name, v)
    }

    fun addManyInts(name: String, values: List<Int>?) {
        if (values != null) for (v in values) add(name, v)
    }

    fun addManyLongs(name: String, values: List<Long>?) {
        if (values != null) for (v in values) add(name, v)
    }

    fun addManyBooleans(name: String, values: List<Boolean>?) {
        if (values != null) for (v in values) add(name, v)
    }

    fun addManyDoubles(name: String, values: List<Double>?) {
        if (values != null) for (v in values) add(name, v)
    }

    override fun toString(): String = sb.toString()
}

/** Percent-encoding for a path segment or a query value: everything but `A-Z a-z 0-9 - . _ ~`, as UTF-8. */
internal object UrlEncoding {
    private const val HEX = "0123456789ABCDEF"

    fun encode(value: String): String {
        var plain = true
        for (c in value) {
            if (!unreserved(c)) {
                plain = false
                break
            }
        }
        if (plain) return value
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && unreserved(c)) {
                sb.append(c)
            } else {
                val v = b.toInt() and 0xFF
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 15])
            }
        }
        return sb.toString()
    }

    private fun unreserved(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
}
