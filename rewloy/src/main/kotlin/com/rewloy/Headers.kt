package com.rewloy

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * The headers of an HTTP answer: names compare without regard to case, and a name can have several values.
 * Immutable.
 */
public class Headers private constructor(private val map: Map<String, List<String>>) {
    /** The first value of the header [name], or `null` when the answer has none. */
    public operator fun get(name: String): String? = map[name.lowercase(Locale.ROOT)]?.firstOrNull()

    /** Every value of the header [name], in the order received. */
    public fun getAll(name: String): List<String> = map[name.lowercase(Locale.ROOT)] ?: emptyList()

    /** Whether the answer has the header [name]. */
    public fun contains(name: String): Boolean = map.containsKey(name.lowercase(Locale.ROOT))

    /** The header names, lower-case. */
    public fun names(): Set<String> = map.keys

    /** All headers by lower-case name. */
    public fun toMap(): Map<String, List<String>> = map

    override fun toString(): String = map.toString()

    public companion object {
        /** No headers. */
        @JvmField
        public val EMPTY: Headers = Headers(emptyMap())

        /** Makes headers from names and values; a `null` name (the status line of `HttpURLConnection`) is skipped. */
        @JvmStatic
        public fun of(fields: Map<out String?, List<String>?>): Headers {
            val out = LinkedHashMap<String, MutableList<String>>()
            for ((name, values) in fields) {
                if (name == null || values == null) continue
                out.getOrPut(name.lowercase(Locale.ROOT)) { ArrayList() }.addAll(values)
            }
            val frozen = LinkedHashMap<String, List<String>>()
            for ((k, v) in out) frozen[k] = Collections.unmodifiableList(v)
            return Headers(Collections.unmodifiableMap(frozen))
        }
    }
}
