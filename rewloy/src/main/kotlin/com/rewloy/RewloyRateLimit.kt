package com.rewloy

/**
 * The request budget the API reports on every answer to an authenticated call: the `RateLimit-Limit`,
 * `RateLimit-Remaining` and `RateLimit-Reset` headers. [RewloyResponse.rateLimit] and [RewloyException.rateLimit]
 * return it.
 */
public class RewloyRateLimit internal constructor(
    /** `RateLimit-Limit`: requests allowed per minute. */
    public val limit: Int,
    /** `RateLimit-Remaining`: requests left in this minute. */
    public val remaining: Int,
    /** `RateLimit-Reset`: seconds until the limit renews. */
    public val resetSeconds: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is RewloyRateLimit && other.limit == limit && other.remaining == remaining && other.resetSeconds == resetSeconds

    override fun hashCode(): Int = (limit * 31 + remaining) * 31 + resetSeconds

    override fun toString(): String = "RewloyRateLimit(limit=$limit, remaining=$remaining, resetSeconds=$resetSeconds)"

    internal companion object {
        /** Reads the three headers; `null` unless all of them are whole numbers. */
        fun from(headers: Headers?): RewloyRateLimit? {
            if (headers == null) return null
            val limit = number(headers["ratelimit-limit"]) ?: return null
            val remaining = number(headers["ratelimit-remaining"]) ?: return null
            val reset = number(headers["ratelimit-reset"]) ?: return null
            return RewloyRateLimit(limit, remaining, reset)
        }

        private fun number(value: String?): Int? {
            val text = value?.trim() ?: return null
            return if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toIntOrNull() else null
        }
    }
}
