package com.rewloy

/**
 * Options for one call: the last argument of every operation's method. Every field is optional.
 *
 * ```kotlin
 * rewloy.passAction(serial, body, RequestOptions(idempotencyKey = "fis-$receiptNo"))
 * ```
 * ```java
 * rewloy.passAction(serial, body, RequestOptions.builder().idempotencyKey("fis-" + receiptNo).build());
 * ```
 */
public class RequestOptions @JvmOverloads constructor(
    /**
     * The `Idempotency-Key` of an operation that takes one (a till action, a campaign, issuing a card). When left
     * out the client generates a UUID and sends the same one on every retry of the call. At the till prefer your
     * own, such as the receipt number: the same receipt is then never processed twice, even after the program
     * restarts.
     */
    public val idempotencyKey: String? = null,
    /**
     * The business a staff session acts for (`Rewloy-Merchant`), when the person has seats in several. Defaults
     * to the client's `merchant`.
     */
    public val merchant: String? = null,
    /**
     * Time allowed for one attempt, until the whole answer has arrived (for a stream: until its headers have), in
     * milliseconds; `0` for none. Overrides the client's.
     */
    public val timeoutMs: Long? = null,
    /** Retries after the first attempt, when retrying is safe. Overrides the client's. */
    public val maxRetries: Int? = null,
    /** Cancels this call, from any thread. */
    public val cancel: CancelToken? = null,
    /**
     * Streams only: reconnect when the connection drops or the server ends the stream, as a browser's
     * `EventSource` does. Errors a reconnection cannot fix (401, 403, 404) end the stream with a
     * [RewloyException]. Default `true`.
     */
    public val reconnect: Boolean? = null,
    /**
     * Streams only: treat the connection as dead after this long without a byte (the API sends a heartbeat every
     * 25 seconds), in milliseconds; `0` turns the check off. Default 60000.
     */
    public val idleTimeoutMs: Long? = null,
    /** Extra request headers. They are sent as given, after the client's own, and can replace them. */
    public val headers: Map<String, String>? = null,
) {
    /** A builder, for Java and for options made step by step. */
    public class Builder internal constructor() {
        private var idempotencyKey: String? = null
        private var merchant: String? = null
        private var timeoutMs: Long? = null
        private var maxRetries: Int? = null
        private var cancel: CancelToken? = null
        private var reconnect: Boolean? = null
        private var idleTimeoutMs: Long? = null
        private var headers: Map<String, String>? = null

        /** See [RequestOptions.idempotencyKey]. */
        public fun idempotencyKey(value: String?): Builder = apply { idempotencyKey = value }

        /** See [RequestOptions.merchant]. */
        public fun merchant(value: String?): Builder = apply { merchant = value }

        /** See [RequestOptions.timeoutMs]. */
        public fun timeoutMs(value: Long?): Builder = apply { timeoutMs = value }

        /** See [RequestOptions.maxRetries]. */
        public fun maxRetries(value: Int?): Builder = apply { maxRetries = value }

        /** See [RequestOptions.cancel]. */
        public fun cancel(value: CancelToken?): Builder = apply { cancel = value }

        /** See [RequestOptions.reconnect]. */
        public fun reconnect(value: Boolean?): Builder = apply { reconnect = value }

        /** See [RequestOptions.idleTimeoutMs]. */
        public fun idleTimeoutMs(value: Long?): Builder = apply { idleTimeoutMs = value }

        /** See [RequestOptions.headers]. */
        public fun headers(value: Map<String, String>?): Builder = apply { headers = value }

        /** Makes the options. */
        public fun build(): RequestOptions =
            RequestOptions(idempotencyKey, merchant, timeoutMs, maxRetries, cancel, reconnect, idleTimeoutMs, headers)
    }

    public companion object {
        /** A builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Options with only an idempotency key. */
        @JvmStatic
        public fun withIdempotencyKey(key: String): RequestOptions = RequestOptions(idempotencyKey = key)
    }
}
