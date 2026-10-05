package com.rewloy

import com.rewloy.json.JsonNull
import com.rewloy.json.JsonObject
import com.rewloy.json.JsonParseException
import com.rewloy.json.JsonValue
import com.rewloy.json.JsonWriter
import com.rewloy.models.PageMeta
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPInputStream

/**
 * A client of the Rewloy API (`https://app.rewloy.com/v1`).
 *
 * ```kotlin
 * val rewloy = Rewloy { apiKey(System.getenv("REWLOY_API_KEY")) }
 * val card = rewloy.getPass("ABCD-EFGH-JKLM")
 * println("${card.type} ${card.balance}")
 * ```
 * ```java
 * Rewloy rewloy = new Rewloy(RewloyOptions.builder().apiKey(System.getenv("REWLOY_API_KEY")).build());
 * GetPassData card = rewloy.getPass("ABCD-EFGH-JKLM");
 * ```
 *
 * Every operation of the API is a method named by its operationId. Its arguments are the path parameters, then
 * the body and the query the operation takes, then [RequestOptions]. A method **blocks** until the answer is in
 * and returns the answer's data; call it from a worker thread (never Android's main thread). Failures are
 * [RewloyException]s, which are unchecked. `…WithResponse` gives the whole answer (status, headers,
 * `requestId`, test mode), and a paged list `listCustomers` also has `listCustomersAll`, which walks every page.
 *
 * The client is safe to share between threads and meant to live as long as your program: make one and reuse it.
 */
public class Rewloy private constructor(
    private val core: Core,
    private val defaultCancel: CancelToken?,
) : RewloyApi() {
    /** Makes a client from options; without any, one with no credential, for the endpoints that need none. */
    @JvmOverloads
    public constructor(options: RewloyOptions = RewloyOptions.builder().build()) : this(Core(options), null)

    /** The API's origin, without `/v1` and without a trailing slash. */
    public val baseUrl: String get() = core.baseUrl

    /** Time allowed for one attempt, in milliseconds; `0` for none. */
    public val timeoutMs: Long get() = core.timeoutMs

    /** Retries after a failed attempt, when retrying is safe. */
    public val maxRetries: Int get() = core.maxRetries

    /** The kind of credential this client sends, or `null` for none. */
    public val credential: CredentialKind? get() = core.credential

    /** The default `Rewloy-Merchant` of a staff session. */
    public val merchant: String? get() = core.merchant

    /**
     * A client that shares this one's settings and connection handling, and whose every call is also cancelled
     * by [token]. Use it to tie calls to something that ends: a screen, a coroutine (see `rewloy-coroutines`).
     */
    public fun withCancel(token: CancelToken): Rewloy = Rewloy(core, token)

    override fun toString(): String = "Rewloy(${core.baseUrl}, ${core.credential?.name?.lowercase(Locale.ROOT) ?: "no credential"})"

    // ---------------------------------------------------------------- what the generated half calls

    internal override fun <T> callJson(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        body: JsonValue?,
        options: RequestOptions?,
        read: (JsonValue, String) -> T,
    ): RewloyResponse<T> {
        val reply = core.exchange(op, pathValues, query, body, options, defaultCancel, null).reply!!
        if (reply.status == 204) throw core.invalid(op, reply, "the answer has no body, the API documents JSON")
        val root = core.parse(op, reply) as? JsonObject
        try {
            val data = root?.get("data") ?: throw core.invalid(op, reply, "the answer is not the JSON the API documents (no data)")
            val metaValue = root["meta"]
            val meta = if (metaValue is JsonObject) PageMeta.read(metaValue, "$.meta") else null
            return RewloyResponse(reply.status, reply.headers, read(data, "$.data"), meta)
        } catch (e: ResponseShapeException) {
            throw core.invalid(op, reply, "the answer does not fit the documented type: ${e.message}", e)
        }
    }

    internal override fun callRaw(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        body: JsonValue?,
        options: RequestOptions?,
    ): RewloyResponse<JsonValue> {
        val reply = core.exchange(op, pathValues, query, body, options, defaultCancel, null).reply!!
        return RewloyResponse(reply.status, reply.headers, core.parse(op, reply))
    }

    internal override fun callNone(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        body: JsonValue?,
        options: RequestOptions?,
    ): RewloyResponse<Unit> {
        val reply = core.exchange(op, pathValues, query, body, options, defaultCancel, null).reply!!
        return RewloyResponse(reply.status, reply.headers, Unit)
    }

    internal override fun callFile(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        body: JsonValue?,
        options: RequestOptions?,
    ): RewloyResponse<RewloyFile> {
        val reply = core.exchange(op, pathValues, query, body, options, defaultCancel, null).reply!!
        val file = RewloyFile(reply.body, reply.headers["content-type"], Core.fileName(reply.headers["content-disposition"]))
        return RewloyResponse(reply.status, reply.headers, file)
    }

    internal override fun openStream(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        options: RequestOptions?,
    ): EventStream {
        val stop = CancelToken()
        val links = ArrayList<CancelToken.Registration>(2)
        options?.cancel?.let { links.add(it.onCancel { stop.cancel() }) }
        defaultCancel?.let { links.add(it.onCancel { stop.cancel() }) }
        val idle = options?.idleTimeoutMs ?: DEFAULT_IDLE_TIMEOUT_MS
        val release = Closeable { for (link in links) link.close() }
        return EventStream(
            EventStream.Source(
                operation = op.id,
                connect = { lastEventId, token ->
                    core.exchange(op, pathValues, query, null, options, defaultCancel, StreamLink(lastEventId, token, idle)).connection!!
                },
                reconnect = options?.reconnect ?: true,
                idleTimeoutMs = idle,
                sleeper = core.sleeper,
                stop = stop,
                release = release,
            ),
        )
    }

    internal companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
    }
}

/** A client made with a configuration block: `Rewloy { apiKey("rwk_…") }`. */
public fun Rewloy(configure: RewloyOptions.Builder.() -> Unit): Rewloy =
    Rewloy(RewloyOptions.builder().apply(configure).build())

internal class StreamLink(val lastEventId: String, val stop: CancelToken, val idleTimeoutMs: Long)

internal class Reply(val status: Int, val headers: Headers, val body: ByteArray)

internal class Exchange(val reply: Reply?, val connection: StreamConnection?)

/** The settings and the machinery of a client, shared by it and the clients made from it with `withCancel`. */
internal class Core(options: RewloyOptions) {
    val baseUrl: String
    val timeoutMs: Long
    val maxRetries: Int
    val credential: CredentialKind?
    val merchant: String?
    val sleeper: Sleeper = options.sleeper
    private val token: String?
    private val transport: Transport = options.transport
    private val listener: DeprecationListener? = options.deprecationListener
    private val userAgent: String = UserAgent.build(options.userAgent)
    private val warned = ConcurrentHashMap<String, Boolean>()

    init {
        val given = listOfNotNull(
            options.apiKey?.let { "apiKey" },
            options.staffSession?.let { "staffSession" },
            options.holderSession?.let { "holderSession" },
        )
        require(given.size <= 1) { "Give one credential, not ${given.joinToString(" and ")}." }
        when {
            options.apiKey != null -> {
                requirePrefix(options.apiKey, "rwk_", "apiKey")
                token = options.apiKey
                credential = CredentialKind.KEY
            }
            options.staffSession != null -> {
                requirePrefix(options.staffSession, "rws_", "staffSession")
                token = options.staffSession
                credential = CredentialKind.STAFF
            }
            options.holderSession != null -> {
                requirePrefix(options.holderSession, "rwh_", "holderSession")
                token = options.holderSession
                credential = CredentialKind.HOLDER
            }
            else -> {
                token = null
                credential = null
            }
        }
        require(options.merchant == null || credential == CredentialKind.STAFF) { "merchant goes with a staffSession." }
        require(options.maxRetries >= 0) { "maxRetries cannot be negative." }
        require(options.timeoutMs >= 0) { "timeoutMs cannot be negative." }
        merchant = options.merchant
        val trimmed = options.baseUrl.trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) { "baseUrl must be an http or https address." }
        baseUrl = normalizeBaseUrl(trimmed)
        timeoutMs = options.timeoutMs
        maxRetries = options.maxRetries
    }

    private fun requirePrefix(value: String, prefix: String, option: String) {
        // The message never carries the value: a mistyped credential is still a credential.
        require(value.startsWith(prefix)) { "$option must start with \"$prefix\"." }
    }

    // ---------------------------------------------------------------- the request

    private fun buildUrl(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?): String {
        var i = 0
        val path = PLACEHOLDER.replace(op.path) { match ->
            val name = match.groupValues[1]
            val value = pathValues.getOrNull(i)
            i++
            require(!value.isNullOrEmpty()) { "${op.id} needs $name." }
            UrlEncoding.encode(value)
        }
        val qs = if (query != null) QueryWriter().also { query.writeTo(it) }.toString() else ""
        return baseUrl + path + (if (qs.isNotEmpty()) "?$qs" else "")
    }

    private fun buildHeaders(op: OperationInfo, idempotencyKey: String?, options: RequestOptions?, lastEventId: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Accept"] = if (op.isStream) "text/event-stream" else if (op.response == ResponseKind.JSON || op.response == ResponseKind.RAW_JSON) "application/json" else "*/*"
        h["User-Agent"] = userAgent
        if (op.isStream) {
            // As a browser's EventSource asks: no cache, and no compression, which could hold events back.
            h["Cache-Control"] = "no-cache"
            h["Accept-Encoding"] = "identity"
        } else {
            h["Accept-Encoding"] = "gzip"
        }
        // An operation that takes no credential of this kind but works without one is called without it: the API
        // refuses a credential an operation does not accept (CREDENTIAL_NOT_ALLOWED).
        if (token != null && credential != null && (credential in op.credentials || CredentialKind.PUBLIC !in op.credentials)) {
            h["Authorization"] = "Bearer $token"
        }
        val merchantId = options?.merchant ?: (if (credential == CredentialKind.STAFF) merchant else null)
        if (op.acceptsMerchant && merchantId != null) h["Rewloy-Merchant"] = merchantId
        if (idempotencyKey != null) h["Idempotency-Key"] = idempotencyKey
        if (op.hasBody) h["Content-Type"] = "application/json"
        if (!lastEventId.isNullOrEmpty()) h["Last-Event-ID"] = lastEventId
        options?.headers?.let { extra ->
            for ((name, value) in extra) {
                val existing = h.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                if (existing != null) h.remove(existing)
                h[name] = value
            }
        }
        return h
    }

    private sealed class Attempt {
        class Done(val exchange: Exchange) : Attempt()
        class Failed(val failure: RewloyException, val waitMs: Long?) : Attempt()
    }

    /**
     * One call: attempts until an answer settles it. For a stream it returns once the headers are in, the body
     * unread (a [StreamConnection]); otherwise with the body read (a [Reply]).
     */
    fun exchange(
        op: OperationInfo,
        pathValues: Array<String>,
        query: RewloyQuery?,
        body: JsonValue?,
        options: RequestOptions?,
        defaultCancel: CancelToken?,
        stream: StreamLink?,
    ): Exchange {
        val url = buildUrl(op, pathValues, query)
        val bodyBytes = if (op.hasBody) JsonWriter.stringify(body ?: JsonObject(emptyMap())).toByteArray(Charsets.UTF_8) else null
        // A key the caller gave (in the options or as a plain header) is checked before anything is sent. Where the API
        // requires one the client never makes one up (a generated key would not survive a restart of the caller's
        // program); where it is optional the client generates a UUID. Chosen once: every retry sends the same key.
        val headerKey = options?.headers?.entries?.firstOrNull { it.key.equals("Idempotency-Key", ignoreCase = true) }?.value
        if (headerKey != null) checkIdempotencyKey(headerKey)
        val givenKey = options?.idempotencyKey
        val idempotencyKey: String? = when {
            op.idempotency == IdempotencyMode.NONE -> null
            givenKey != null -> checkIdempotencyKey(givenKey)
            headerKey != null -> headerKey
            op.idempotency == IdempotencyMode.REQUIRED -> throw IllegalArgumentException(
                "${op.id} needs options.idempotencyKey: Idempotency-Key gerekli, kütüphane uydurmaz (8-64 ASCII karakter) / " +
                    "the Idempotency-Key is required and is never generated for you (8-64 printable ASCII characters).",
            )
            else -> UUID.randomUUID().toString()
        }
        val headers = buildHeaders(op, idempotencyKey, options, stream?.lastEventId)
        val retryable = Retry.isSafeMethod(op.method) || idempotencyKey != null || Retry.hasHeader(options?.headers, "idempotency-key")
        val maxRetries = maxOf(0, options?.maxRetries ?: this.maxRetries)
        val timeoutMs = options?.timeoutMs ?: this.timeoutMs

        // One token for the whole call: it is cancelled when the caller cancels (the call's token, the client's,
        // or a stream's own), and every attempt hangs off it.
        val call = CancelToken()
        val links = ArrayList<CancelToken.Registration>(3)
        for (parent in listOfNotNull(options?.cancel, defaultCancel, stream?.stop)) links.add(parent.onCancel { call.cancel() })
        var keepLinks = false
        try {
            var attempt = 0
            while (true) {
                call.throwIfCancelled()
                when (val outcome = attemptOnce(op, url, headers, bodyBytes, call, timeoutMs, retryable, retryable && attempt < maxRetries, stream)) {
                    is Attempt.Done -> {
                        if (stream != null) {
                            // The connection now owns the links that tie it to the caller's tokens.
                            keepLinks = true
                            outcome.exchange.connection!!.adopt(links)
                        }
                        return outcome.exchange
                    }
                    is Attempt.Failed -> {
                        val delay = outcome.waitMs ?: Retry.backoff(attempt, ThreadLocalRandom.current().nextDouble())
                        if (delay > Retry.MAX_RETRY_AFTER_MS) throw outcome.failure
                        sleeper.sleep(delay, call)
                        call.throwIfCancelled()
                        attempt++
                    }
                }
            }
        } finally {
            if (!keepLinks) for (link in links) link.close()
        }
    }

    /** One attempt. Returns what settled it, or the failure to retry; throws what must not be retried. */
    private fun attemptOnce(
        op: OperationInfo,
        url: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        call: CancelToken,
        timeoutMs: Long,
        repeatable: Boolean,
        mayRetry: Boolean,
        stream: StreamLink?,
    ): Attempt {
        val attemptToken = CancelToken()
        val link = call.onCancel { attemptToken.cancel() }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = if (timeoutMs > 0) Timeouts.schedule(timeoutMs) { timedOut.set(true); attemptToken.cancel() } else null
        var response: TransportResponse? = null
        var keep = false
        try {
            val readTimeout = if (stream != null) stream.idleTimeoutMs else timeoutMs
            response = transport.execute(
                TransportRequest(op.method, url, headers, bodyBytes, clampMs(timeoutMs), clampMs(readTimeout), attemptToken, repeatable),
            )
            notice(op, response.headers)
            if (response.status in 200..299) {
                if (stream != null) {
                    keep = true
                    return Attempt.Done(Exchange(null, StreamConnection(response, attemptToken, listOf(link))))
                }
                return Attempt.Done(Exchange(Reply(response.status, response.headers, readBytes(response, attemptToken)), null))
            }
            val text = try {
                String(readBytes(response, attemptToken), Charsets.UTF_8)
            } catch (e: IOException) {
                if (timedOut.get() || call.isCancelled) throw e
                ""
            }
            val failure = failure(op, response, text)
            if (!mayRetry || !Retry.isRetryableStatus(response.status, failure.code)) throw failure
            return Attempt.Failed(failure, Retry.parseRetryAfter(response.headers["retry-after"]))
        } catch (e: IOException) {
            // The caller's own cancellation is theirs: a CancellationException, not a Rewloy failure.
            call.throwIfCancelled()
            val failure: RewloyException = if (timedOut.get() || e is SocketTimeoutException) {
                RewloyTimeoutException(if (timeoutMs > 0) "no answer within $timeoutMs ms" else "the request timed out", op.id, null, e)
            } else {
                RewloyConnectionException(describe(e), op.id, null, e)
            }
            if (!mayRetry) throw failure
            return Attempt.Failed(failure, null)
        } finally {
            timer?.cancel(false)
            if (!keep) {
                link.close()
                response?.close()
            }
        }
    }

    private fun clampMs(ms: Long): Int = if (ms <= 0) 0 else if (ms > Int.MAX_VALUE) Int.MAX_VALUE else ms.toInt()

    private fun describe(e: IOException): String {
        val cause = e.cause
        val own = e.message ?: e.javaClass.simpleName
        return if (cause != null && cause.message != null && cause.message != e.message) "$own: ${cause.message}" else own
    }

    /**
     * Reads the whole body, and stops when the attempt's token is cancelled (its time ran out, or the caller gave
     * up): a transport may not be able to abort a read in progress, but this loop sees the token between reads, and
     * a read that blocks is bounded by the transport's read timeout, which is the attempt's own.
     */
    private fun readBytes(response: TransportResponse, cancel: CancelToken): ByteArray {
        var stream = response.body
        if (response.headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true) stream = GZIPInputStream(stream)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (cancel.isCancelled) throw IOException("the request was cancelled")
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- the answer

    fun parse(op: OperationInfo, reply: Reply): JsonValue = try {
        JsonValue.parse(reply.body)
    } catch (e: JsonParseException) {
        throw invalid(op, reply, "the answer is not the JSON the API documents (${reply.headers["content-type"] ?: "no content type"})", e)
    }

    fun invalid(op: OperationInfo, reply: Reply, detail: String, cause: Throwable? = null): RewloyException = RewloyException(
        reply.status, "INVALID_RESPONSE", detail, null, null, null, reply.headers["x-request-id"],
        String(reply.body, Charsets.UTF_8), reply.headers, op.id, cause,
    )

    /** The API's error body (or a proxy's page) as the exception it is. */
    private fun failure(op: OperationInfo, response: TransportResponse, text: String): RewloyException {
        val status = response.status
        var code: String? = null
        var message: String? = null
        var docs: String? = null
        var bodyRequestId: String? = null
        var details: JsonValue? = null
        try {
            val e = (JsonValue.parse(text) as? JsonObject)?.get("error") as? JsonObject
            if (e != null) {
                code = e["code"]?.asString()
                message = e["message"]?.asString()
                docs = e["docs"]?.asString()
                bodyRequestId = e["requestId"]?.asString()
                details = e["details"]?.takeUnless { it === JsonNull }
            }
        } catch (_: JsonParseException) {
            // Not JSON: a proxy's page.
        }
        val finalCode = code ?: "HTTP_$status"
        val detail = message ?: (response.reasonPhrase?.takeIf { it.isNotEmpty() } ?: "HTTP $status")
        val title = ErrorCode.title(finalCode)
        val requestId = response.headers["x-request-id"] ?: bodyRequestId
        if (status == 429) {
            val header = Retry.parseRetryAfter(response.headers["retry-after"])
            val fromBody = (details as? JsonObject)?.get("retryAfterSec")?.asDouble()
            val seconds = if (header != null) Math.ceil(header / 1000.0).toLong() else fromBody?.let { Math.ceil(it).toLong() }
            return RateLimitException(status, finalCode, detail, seconds, title, details, docs, requestId, text, response.headers, op.id)
        }
        return RewloyException(status, finalCode, detail, title, details, docs, requestId, text, response.headers, op.id)
    }

    // ---------------------------------------------------------------- deprecations

    private fun notice(op: OperationInfo, headers: Headers) {
        if (!headers.contains("deprecation")) return
        if (warned.putIfAbsent(op.id, true) != null) return
        val sunset = headers["sunset"]
        val link = deprecationLink(headers.getAll("link").joinToString(", "))
        val message = "Rewloy API operation ${op.id} (${op.method} ${op.path}) is deprecated." +
            (if (sunset != null) " Sunset: $sunset." else "") + (if (link != null) " See $link" else "")
        val deprecation = DeprecationNotice(op.id, op.method, op.path, sunset, link, message)
        val l = listener
        if (l != null) {
            try {
                l.onDeprecated(deprecation)
            } catch (_: RuntimeException) {
                // A listener's failure must not fail an answered call: the server has already acted.
            }
        } else if (loggedOnce.putIfAbsent(op.id, true) == null) {
            LOGGER.log(Level.WARNING, message)
        }
    }

    /** The URL a `Link` header gives for `rel="deprecation"` (else its first). */
    private fun deprecationLink(link: String): String? {
        if (link.isEmpty()) return null
        var first: String? = null
        for (m in LINK.findAll(link)) {
            if (first == null) first = m.groupValues[1]
            if (DEPRECATION_REL.containsMatchIn(m.groupValues[2])) return m.groupValues[1]
        }
        return first
    }

    companion object {
        /**
         * The base URL without a trailing `/v1` (the operations' paths carry `/v1` themselves; the documentation shows
         * the address both ways), and without trailing slashes. Only a path segment counts: a host called `v1` stays.
         */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trimEnd('/')
            val authorityStart = trimmed.indexOf("://") + 3
            val pathStart = trimmed.indexOf('/', authorityStart)
            if (pathStart >= 0 && trimmed.endsWith("/v1")) return trimmed.substring(0, trimmed.length - "/v1".length).trimEnd('/')
            return trimmed
        }

        /**
         * An `Idempotency-Key` is 8-64 printable ASCII characters (0x21-0x7E): an HTTP header value cannot carry
         * anything else.
         */
        fun checkIdempotencyKey(key: String): String {
            require(key.length in 8..64 && key.all { it in '\u0021'..'\u007e' }) {
                "Idempotency-Key yalnız ASCII karakterler içerebilir (görünür karakterler, 8-64) / " +
                    "the Idempotency-Key must be printable ASCII (0x21-0x7E), 8-64 characters."
            }
            return key
        }

        private val PLACEHOLDER = Regex("\\{([^}]+)}")
        private val LINK = Regex("<([^>]*)>([^,]*)")
        private val DEPRECATION_REL = Regex("\\brel\\s*=\\s*\"?[^\";]*\\bdeprecation\\b", RegexOption.IGNORE_CASE)
        private val LOGGER: Logger = Logger.getLogger("com.rewloy")
        private val loggedOnce = ConcurrentHashMap<String, Boolean>()

        /** For tests: forget which operations were logged. */
        fun resetLogged() = loggedOnce.clear()

        fun fileName(disposition: String?): String? {
            if (disposition == null) return null
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition) ?: return null
            val raw = m.groupValues[1].trim()
            return try {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                raw
            }
        }
    }
}

/** What `User-Agent` says about the program: the library, the runtime and the caller's own suffix. */
internal object UserAgent {
    fun build(suffix: String?): String {
        val parts = ArrayList<String>(3)
        parts.add("rewloy-kotlin/${RewloyVersion.CURRENT}")
        parts.add(runtime())
        suffix?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it.replace(Regex("[\\r\\n]+"), " ")) }
        return parts.joinToString(" ")
    }

    private fun runtime(): String {
        val vm = System.getProperty("java.vm.name") ?: ""
        val vendor = System.getProperty("java.vendor") ?: ""
        if (vm.contains("Dalvik", ignoreCase = true) || vendor.contains("Android", ignoreCase = true)) {
            val sdk = try {
                Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
            } catch (_: Exception) {
                null
            }
            return if (sdk != null) "android/$sdk" else "android"
        }
        return "java/${System.getProperty("java.version") ?: "unknown"}"
    }
}

/** The library's version: the same as the Gradle build's, which a test checks. */
internal object RewloyVersion {
    const val CURRENT: String = "0.2.4"
}
