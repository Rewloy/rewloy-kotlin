package com.rewloy

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * What the client needs of an HTTP stack: send one request, hand back the answer with its body still unread.
 * The default is [HttpUrlConnectionTransport], which is built into every JVM and Android; the `rewloy-okhttp`
 * artifact has one over OkHttp. Write your own to route calls through a proxy, a test double or an
 * instrumented stack.
 *
 * The client does the rest: retries, timeouts per attempt, errors, JSON. A transport must not follow redirects
 * (the API does not redirect, and a redirect could carry the token elsewhere), must not send a request twice
 * unless [TransportRequest.repeatable] says that is harmless (the JDK's and OkHttp's stacks both re-send on a
 * dropped connection by themselves), and must be safe to call from several threads at once.
 */
public fun interface Transport {
    /**
     * Sends [request] and returns the answer once its status and headers are in. A non-2xx status is an answer,
     * not an exception: throw [IOException] only when there is no answer (connection refused, TLS failure, a
     * timeout, or the request was cancelled).
     *
     * Watch `request.cancel`: when it is cancelled the call must stop (abort the connection) and throw
     * [IOException], whether it is connecting, sending or reading. Reads of the body may block no longer than
     * `request.readTimeoutMs` without a byte.
     */
    @Throws(IOException::class)
    public fun execute(request: TransportRequest): TransportResponse
}

/** One request, as the client has built it: everything the wire needs. */
public class TransportRequest(
    /** `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. */
    public val method: String,
    /** The whole URL, query string included. */
    public val url: String,
    /** The request headers, in the order to send them. */
    public val headers: Map<String, String>,
    /** The body, or `null` for none. A `POST`, `PUT` or `PATCH` with a `null` body is sent with `Content-Length: 0`. */
    public val body: ByteArray?,
    /** Longest wait for the connection, in milliseconds; `0` for no limit. */
    public val connectTimeoutMs: Int,
    /** Longest wait for a byte of the answer, in milliseconds; `0` for no limit. */
    public val readTimeoutMs: Int,
    /** Cancelled when the call is given up (the caller cancelled, or the attempt timed out). */
    public val cancel: CancelToken,
    /**
     * Whether sending this request twice does no harm (a GET, PUT or DELETE, or a POST or PATCH with an
     * `Idempotency-Key`). When `false` the transport must send it at most once, even if the connection drops
     * before an answer: whether the server acted is not known, and the caller decides what to do.
     */
    public val repeatable: Boolean = true,
)

/** An answer whose body has not been read. Close it when done, whether or not the body was read to its end. */
public interface TransportResponse : Closeable {
    /** The HTTP status. */
    public val status: Int

    /** The reason phrase of the status line, when the stack gives it. */
    public val reasonPhrase: String?

    /** The answer's headers. */
    public val headers: Headers

    /** The body. It decodes nothing: a gzip body arrives as gzip, with `Content-Encoding` saying so. */
    public val body: InputStream
}
