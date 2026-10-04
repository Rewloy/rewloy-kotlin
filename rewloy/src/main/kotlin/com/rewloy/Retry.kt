package com.rewloy

import java.util.Locale

/**
 * The client's retry rules, apart so that tests can pin them.
 *
 * Retried, when the request is safe to repeat (GET, HEAD, PUT, DELETE, or a POST or PATCH that carries an
 * `Idempotency-Key`): network errors and timeouts, 429, 502-504, Cloudflare's 520-524, and
 * `409 IDEMPOTENCY_IN_PROGRESS`. Between attempts the client waits `Retry-After` (up to 60 s; a longer one
 * goes to the caller as the error), else 0.5 s doubling to 8 s with jitter.
 */
internal object Retry {
    const val BACKOFF_BASE_MS: Long = 500
    const val BACKOFF_MAX_MS: Long = 8_000

    /** A `Retry-After` longer than this is not waited for: the error goes to the caller. */
    const val MAX_RETRY_AFTER_MS: Long = 60_000

    /**
     * The wait before the retry that follows attempt [attempt] (0-based): 0.5 s, 1 s, 2 s... up to 8 s, each with
     * jitter between half and all of it. [random] is a number in [0, 1].
     */
    fun backoff(attempt: Int, random: Double): Long {
        val shift = minOf(maxOf(0, attempt), 16)
        val cap = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L shl shift))
        return Math.round(cap / 2.0 + random * (cap / 2.0))
    }

    /** `Retry-After` in milliseconds: delta-seconds or an HTTP date; `null` when absent or unreadable. */
    fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val v = value?.trim()
        if (v.isNullOrEmpty()) return null
        if (isSeconds(v)) return Math.round(v.toDouble() * 1000.0)
        val at = HttpDate.parse(v) ?: return null
        return maxOf(0L, at - nowMillis)
    }

    private fun isSeconds(v: String): Boolean {
        var i = 0
        while (i < v.length && v[i] in '0'..'9') i++
        if (i == 0) return false
        if (i == v.length) return true
        if (v[i] != '.') return false
        val fraction = i + 1
        var j = fraction
        while (j < v.length && v[j] in '0'..'9') j++
        return j > fraction && j == v.length
    }

    fun isGatewayStatus(status: Int): Boolean = status == 502 || status == 503 || status == 504 || (status in 520..524)

    /** An error answer that another attempt may get past. */
    fun isRetryableStatus(status: Int, errorCode: String): Boolean =
        status == 429 || isGatewayStatus(status) || (status == 409 && errorCode == "IDEMPOTENCY_IN_PROGRESS")

    fun isSafeMethod(method: String): Boolean = method == "GET" || method == "HEAD" || method == "PUT" || method == "DELETE"

    fun hasHeader(headers: Map<String, String>?, name: String): Boolean =
        headers != null && headers.keys.any { it.lowercase(Locale.ROOT) == name }
}
