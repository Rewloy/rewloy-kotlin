package com.rewloy.okhttp

import com.rewloy.Headers
import com.rewloy.Transport
import com.rewloy.TransportRequest
import com.rewloy.TransportResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * A [Transport] over OkHttp, for apps that already have an [OkHttpClient] (its connection pool, its proxy and
 * certificate settings, its interceptors) and for desktop JVMs, whose `HttpURLConnection` cannot send `PATCH`.
 *
 * ```kotlin
 * val rewloy = Rewloy {
 *     apiKey("rwk_…")
 *     transport(OkHttpTransport(myOkHttpClient))
 * }
 * ```
 *
 * The client's own timeouts are replaced per request by the ones the Rewloy client asks for, and redirects are not
 * followed. Everything else about the [OkHttpClient] is used as given; the pool and dispatcher are shared with it.
 * Written against OkHttp 4.12, which also runs on OkHttp 5.
 */
public class OkHttpTransport @JvmOverloads constructor(
    private val client: OkHttpClient = OkHttpClient(),
) : Transport {
    @Throws(IOException::class)
    override fun execute(request: TransportRequest): TransportResponse {
        if (request.cancel.isCancelled) throw IOException("the request was cancelled")
        val builder = Request.Builder().url(request.url)
        var contentType: String? = null
        for ((name, value) in request.headers) {
            if (name.equals("content-type", ignoreCase = true)) contentType = value else builder.header(name, value)
        }
        val needsBody = request.body != null || request.method == "POST" || request.method == "PUT" || request.method == "PATCH"
        val body = if (needsBody) (request.body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull()) else null
        builder.method(request.method, body)

        val call = client.newBuilder()
            .connectTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // OkHttp re-sends a request on a connection that fails by itself; not one that must be sent once.
            .retryOnConnectionFailure(request.repeatable)
            .build()
            .newCall(builder.build())
        val registration = request.cancel.onCancel { call.cancel() }
        try {
            val response = call.execute()
            val responseBody = response.body ?: throw IOException("the answer has no body")
            return object : TransportResponse {
                override val status: Int = response.code
                override val reasonPhrase: String? = response.message
                override val headers: Headers = Headers.of(response.headers.toMultimap())
                override val body: InputStream = responseBody.byteStream()

                override fun close() {
                    registration.close()
                    response.close()
                }
            }
        } catch (e: IOException) {
            registration.close()
            throw e
        }
    }
}
