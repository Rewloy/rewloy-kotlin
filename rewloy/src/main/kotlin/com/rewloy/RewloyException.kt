package com.rewloy

import com.rewloy.json.JsonValue

/**
 * What the client throws. Every failure to get an answer from Rewloy is a `RewloyException`: an error answer
 * from the API (with its stable [code]), an answer that is not what the API documents, or no answer at all.
 * It is unchecked, so Java code does not have to declare it.
 *
 * Act on [code]: it is stable, while [detail] is a human sentence in Turkish that may change. Besides the API's
 * codes ([ErrorCode], <https://rewloy.com/gelistiriciler/hatalar>) the client uses:
 * - `CONNECTION_ERROR` and `TIMEOUT` (status 0): no answer arrived;
 * - `INVALID_RESPONSE`: a 2xx answer that is not the documented JSON ([body] has the text);
 * - `HTTP_<status>`: an error answer without Rewloy's error body (a proxy's 502 page).
 */
public open class RewloyException @JvmOverloads constructor(
    /** The HTTP status; `0` when no answer arrived. */
    public val status: Int,
    /** The API's stable machine code, such as `INSUFFICIENT_BALANCE` (see [ErrorCode]). */
    public val code: String,
    /** What happened, in the API's words (`error.message`). */
    public val detail: String,
    /** The code's one-line title in the catalogue, such as "Bakiye yetersiz"; `null` for a code newer than this library. */
    public val title: String? = null,
    /**
     * The API's `error.details`, when it sent any: for `VALIDATION` a list of `{ field, rule, message }`, for
     * others what the catalogue says (`left`, `channels`, `request`…).
     */
    public val details: JsonValue? = null,
    /** Where the catalogue explains the code (`error.docs`). */
    public val docs: String? = null,
    /** `x-request-id`: quote it to Rewloy support. */
    public val requestId: String? = null,
    /** The answer's body as text, when there was one. */
    public val body: String? = null,
    /** The answer's headers, when there was an answer. */
    public val headers: Headers? = null,
    /** The operationId of the call. */
    public val operation: String? = null,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(status, code, detail, operation, requestId), cause) {
    /** The `RateLimit-*` headers of the answer; `null` when it carried none. */
    public val rateLimit: RewloyRateLimit? get() = RewloyRateLimit.from(headers)

    private companion object {
        fun buildMessage(status: Int, code: String, detail: String, operation: String?, requestId: String?): String {
            val where = listOfNotNull(operation, requestId?.let { "requestId $it" }).filter { it.isNotEmpty() }.joinToString(", ")
            return (if (status != 0) "$status " else "") + "$code: $detail" + (if (where.isNotEmpty()) " ($where)" else "")
        }
    }
}

/** `429 RATE_LIMITED`: too many requests for this credential or this action. */
public class RateLimitException @JvmOverloads constructor(
    status: Int,
    code: String,
    detail: String,
    /** Seconds to wait before trying again (`Retry-After`, or the body's `retryAfterSec`), when the API said. */
    public val retryAfterSeconds: Long?,
    title: String? = null,
    details: JsonValue? = null,
    docs: String? = null,
    requestId: String? = null,
    body: String? = null,
    headers: Headers? = null,
    operation: String? = null,
) : RewloyException(status, code, detail, title, details, docs, requestId, body, headers, operation, null)

/** No answer arrived: the connection failed or broke (`CONNECTION_ERROR`). The cause is the I/O exception. */
public open class RewloyConnectionException @JvmOverloads constructor(
    detail: String,
    operation: String? = null,
    requestId: String? = null,
    cause: Throwable? = null,
    code: String = "CONNECTION_ERROR",
) : RewloyException(0, code, detail, null, null, null, requestId, null, null, operation, cause)

/** No answer within the timeout (`TIMEOUT`), or a stream fell silent. */
public class RewloyTimeoutException @JvmOverloads constructor(
    detail: String,
    operation: String? = null,
    requestId: String? = null,
    cause: Throwable? = null,
) : RewloyConnectionException(detail, operation, requestId, cause, "TIMEOUT")
