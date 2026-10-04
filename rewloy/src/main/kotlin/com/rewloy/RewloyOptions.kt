package com.rewloy

/** Where deprecation notices go; see [RewloyOptions.deprecationListener]. */
public fun interface DeprecationListener {
    /** Called once per deprecated operation per client, with the first answer that carries `Deprecation`. */
    public fun onDeprecated(notice: DeprecationNotice)
}

/** A deprecated operation's notice, from the `Deprecation`, `Sunset` and `Link` headers of an answer. */
public class DeprecationNotice internal constructor(
    /** The operationId, such as `listCustomers`. */
    public val operation: String,
    /** The HTTP method. */
    public val method: String,
    /** The path. */
    public val path: String,
    /** The `Sunset` header: when the API stops answering this operation, if it said. */
    public val sunset: String?,
    /** The URL of the `Link` header with `rel="deprecation"` (else its first link), if any. */
    public val link: String?,
    /** A sentence for a log. */
    public val message: String,
) {
    override fun toString(): String = message
}

/** Waits between retries and reconnections. Replace it in tests, or to schedule differently. */
public fun interface Sleeper {
    /** Waits [millis] milliseconds, or until [cancel] is cancelled, whichever is first. */
    public fun sleep(millis: Long, cancel: CancelToken)
}

/**
 * How to build a client: with one credential, or none for the endpoints that need none (sign-in, joining a
 * programme…). Make it with [builder].
 */
public class RewloyOptions private constructor(builder: Builder) {
    /** An API key, `rwk_…`: a till, a shop, your own system. */
    internal val apiKey: String? = builder.apiKey

    /** A staff session, `rws_…` (`login`): a person's business app. */
    internal val staffSession: String? = builder.staffSession

    /** A card holder's session, `rwh_…`: a Rewloy Cüzdan app. */
    internal val holderSession: String? = builder.holderSession

    /** The business a staff session acts for. */
    public val merchant: String? = builder.merchant

    /** The API's origin, without `/v1`. */
    public val baseUrl: String = builder.baseUrl

    /** Time allowed for one attempt, in milliseconds; `0` for none. */
    public val timeoutMs: Long = builder.timeoutMs

    /** Retries after a failed attempt, when retrying is safe. */
    public val maxRetries: Int = builder.maxRetries

    /** Added to the `User-Agent` this client sends. */
    public val userAgent: String? = builder.userAgent

    /** The HTTP stack. */
    public val transport: Transport = builder.transport

    /** Waits between retries. */
    public val sleeper: Sleeper = builder.sleeper

    /** Where deprecation notices go; `null` for the log. */
    public val deprecationListener: DeprecationListener? = builder.deprecationListener

    /** Makes options. */
    public class Builder internal constructor() {
        internal var apiKey: String? = null
        internal var staffSession: String? = null
        internal var holderSession: String? = null
        internal var merchant: String? = null
        internal var baseUrl: String = DEFAULT_BASE_URL
        internal var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        internal var maxRetries: Int = DEFAULT_MAX_RETRIES
        internal var userAgent: String? = null
        internal var transport: Transport = HttpUrlConnectionTransport()
        internal var sleeper: Sleeper = Sleeper { millis, cancel -> cancel.sleep(millis) }
        internal var deprecationListener: DeprecationListener? = null

        /** An API key, `rwk_…`: a till, a shop, your own system. */
        public fun apiKey(value: String?): Builder = apply { apiKey = value }

        /** A staff session, `rws_…`: a person's business app. Give [merchant] too when the person has seats in several businesses. */
        public fun staffSession(value: String?): Builder = apply { staffSession = value }

        /** A card holder's session, `rwh_…`: a Rewloy Cüzdan-style customer app. */
        public fun holderSession(value: String?): Builder = apply { holderSession = value }

        /** The business a staff session acts for (`Rewloy-Merchant`). Only with [staffSession]; a call can override it. */
        public fun merchant(value: String?): Builder = apply { merchant = value }

        /** The API's origin, without `/v1`. Default `https://app.rewloy.com`. */
        public fun baseUrl(value: String): Builder = apply { baseUrl = value }

        /** Time allowed for one attempt, in milliseconds; `0` for none. Default 60000. */
        public fun timeoutMs(value: Long): Builder = apply { timeoutMs = value }

        /** Retries after a failed attempt, when retrying is safe. Default 2. */
        public fun maxRetries(value: Int): Builder = apply { maxRetries = value }

        /** Added to the `User-Agent` this client sends, for example `"KasaPOS/4.2"`. */
        public fun userAgent(value: String?): Builder = apply { userAgent = value }

        /** The HTTP stack. Default [HttpUrlConnectionTransport]. */
        public fun transport(value: Transport): Builder = apply { transport = value }

        /** Replaces the wait between retries and reconnections (tests, custom schedulers). */
        public fun sleeper(value: Sleeper): Builder = apply { sleeper = value }

        /**
         * Receives a [DeprecationNotice] once per deprecated operation. Without one, the notice goes to the
         * `com.rewloy` logger (`java.util.logging`, which Android routes to Logcat) once per operation per process.
         */
        public fun deprecationListener(value: DeprecationListener?): Builder = apply { deprecationListener = value }

        /** Makes the options. */
        public fun build(): RewloyOptions = RewloyOptions(this)
    }

    public companion object {
        /** The API's origin when none is given. */
        public const val DEFAULT_BASE_URL: String = "https://app.rewloy.com"

        /** The default time for one attempt: 60 seconds. */
        public const val DEFAULT_TIMEOUT_MS: Long = 60_000

        /** The default number of retries: 2. */
        public const val DEFAULT_MAX_RETRIES: Int = 2

        /** A builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
