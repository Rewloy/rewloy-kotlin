package com.rewloy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL

/**
 * The default [Transport]: `java.net.HttpURLConnection`, which every JVM and every Android version has, so that
 * the library needs no HTTP dependency and no Android SDK. (`java.net.http` is not on Android.)
 *
 * - **Redirects** are not followed and the connection cache is off.
 * - **`PATCH`** is accepted by Android's `HttpURLConnection` but refused by the JDK's. On a JVM up to Java 11 this
 *   class works around it; from Java 12 on that workaround is closed off, and a `PATCH` throws
 *   [UnsupportedOperationException] pointing to `rewloy-okhttp` (11 of the API's 237 operations are `PATCH`).
 * - **Cancelling** a request disconnects it (on a helper thread). That ends a request that is waiting for its
 *   answer; a read of an answer's body that is blocked on a keep-alive connection ends only when a byte or the
 *   read timeout comes, because the JDK does not close such a socket on `disconnect()` (a stream gets around it with
 *   a reader thread of its own, see [EventStream]).
 *
 * It is stateless and safe to share between threads.
 */
public class HttpUrlConnectionTransport : Transport {
    @Throws(IOException::class)
    override fun execute(request: TransportRequest): TransportResponse {
        // A request that must not be sent twice is written in streaming mode: the JDK re-sends a buffered one by
        // itself when the connection drops before the answer (sun.net.http.retryPost, on by default), and
        // streaming mode is the one thing that stops it.
        val streaming = !request.repeatable && (request.body != null || hasBody(request.method))
        val first = send(request, streaming)
        // In streaming mode the JDK does not hand over the body of a 401 (the answer to a request nothing was done
        // for, so asking again is safe). Ask again, buffered, to read what the API said.
        if (streaming && first.status == 401 && first.noBody) {
            first.close()
            return send(request, false)
        }
        return first
    }

    private fun hasBody(method: String) = method == "POST" || method == "PUT" || method == "PATCH"

    @Throws(IOException::class)
    private fun send(request: TransportRequest, streaming: Boolean): Response {
        if (request.cancel.isCancelled) throw IOException("the request was cancelled")
        val connection = java.net.URI(request.url).toURL().openConnection() as HttpURLConnection
        // On a helper thread: disconnect() can wait for a read that is blocked on the same connection, and the
        // thread that cancels must not.
        val registration = request.cancel.onCancel { abort(connection) }
        try {
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            setMethod(connection, request.method)
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)

            val body = request.body
            if (body != null || hasBody(request.method)) {
                connection.doOutput = true
                if (streaming) connection.setFixedLengthStreamingMode(body?.size ?: 0)
                connection.outputStream.use { out -> if (body != null) out.write(body) }
            }

            val status = connection.responseCode
            val error = if (status >= 400) connection.errorStream else null
            val stream: InputStream = if (status < 400) connection.inputStream else error ?: ByteArrayInputStream(ByteArray(0))
            return Response(registration, status, connection.responseMessage, Headers.of(connection.headerFields), stream, status >= 400 && error == null)
        } catch (e: IOException) {
            registration.close()
            connection.disconnect()
            throw e
        } catch (e: RuntimeException) {
            registration.close()
            connection.disconnect()
            throw e
        }
    }

    private class Response(
        private val registration: CancelToken.Registration,
        override val status: Int,
        override val reasonPhrase: String?,
        override val headers: Headers,
        override val body: InputStream,
        val noBody: Boolean,
    ) : TransportResponse {
        override fun close() {
            registration.close()
            try {
                body.close()
            } catch (_: IOException) {
                // Nothing to do about a body that will not close.
            }
        }
    }

    private companion object {
        fun abort(connection: HttpURLConnection) {
            val thread = Thread({ connection.disconnect() }, "rewloy-abort")
            thread.isDaemon = true
            thread.start()
        }

        fun setMethod(connection: HttpURLConnection, method: String) {
            try {
                connection.requestMethod = method
                return
            } catch (e: ProtocolException) {
                if (method != "PATCH") throw e
            }
            try {
                // The JDK's HttpURLConnection knows a fixed list of methods and PATCH is not on it; its `method`
                // field can be set up to Java 11. (https connections wrap the plain one in a `delegate`.)
                val field = HttpURLConnection::class.java.getDeclaredField("method")
                field.isAccessible = true
                field.set(connection, "PATCH")
                try {
                    val delegateField = connection.javaClass.getDeclaredField("delegate")
                    delegateField.isAccessible = true
                    val delegate = delegateField.get(connection)
                    if (delegate != null) field.set(delegate, "PATCH")
                } catch (_: NoSuchFieldException) {
                    // A plain http connection has no delegate.
                }
            } catch (e: ReflectiveOperationException) {
                throw patchUnsupported(e)
            } catch (e: RuntimeException) {
                // InaccessibleObjectException (Java 16+), SecurityException.
                throw patchUnsupported(e)
            }
        }

        fun patchUnsupported(cause: Exception): UnsupportedOperationException = UnsupportedOperationException(
            "This JVM's HttpURLConnection cannot send PATCH. Use the OkHttp transport (artifact com.rewloy:rewloy-okhttp) " +
                "or your own Transport. Android is not affected.",
            cause,
        )
    }
}
