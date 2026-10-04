package com.rewloy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the stub received. */
class Received(val method: String, val target: String, val headers: Map<String, String>, val body: String) {
    val path: String get() = target.substringBefore('?')
    val query: String get() = if ('?' in target) target.substringAfter('?') else ""
    fun header(name: String): String? = headers[name.lowercase()]
}

/** What the stub answers. [chunks] are sent one by one (with [chunkDelayMs] between) for streams. */
class Answer(
    val status: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
    val chunks: List<ByteArray>? = null,
    val chunkDelayMs: Long = 0,
    val closeAfterChunks: Boolean = true,
    val rawBytes: ByteArray? = null,
    /** Drop the connection without answering. */
    val drop: Boolean = false,
)

/** A local HTTP server for tests: no network, programmable answers, every request recorded. */
class StubServer : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val received = CopyOnWriteArrayList<Received>()
    private val count = AtomicInteger()

    @Volatile
    var script: (Received, Int) -> Answer = { _, _ -> Answer() }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "stub").also { it.isDaemon = true } }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    /** Answers with these in turn; the last one repeats. */
    fun enqueue(vararg answers: Answer) {
        script = { _, i -> answers[minOf(i, answers.size - 1)] }
    }

    private fun handle(ex: HttpExchange) {
        val bodyBytes = ex.requestBody.use { it.readBytes() }
        val headers = HashMap<String, String>()
        for ((k, v) in ex.requestHeaders) headers[k.lowercase()] = v.joinToString(", ")
        val request = Received(ex.requestMethod, ex.requestURI.toString(), headers, String(bodyBytes, Charsets.UTF_8))
        val index = count.getAndIncrement()
        received.add(request)
        val answer = script(request, index)
        try {
            if (answer.delayMs > 0) Thread.sleep(answer.delayMs)
            if (answer.drop) {
                ex.close()
                return
            }
            for ((k, v) in answer.headers) ex.responseHeaders.add(k, v)
            val chunks = answer.chunks
            if (chunks != null) {
                if (!ex.responseHeaders.containsKey("Content-Type")) ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(answer.status, 0)
                val out = ex.responseBody
                for (c in chunks) {
                    out.write(c)
                    out.flush()
                    if (answer.chunkDelayMs > 0) Thread.sleep(answer.chunkDelayMs)
                }
                if (answer.closeAfterChunks) out.close() else Thread.sleep(60_000)
                return
            }
            val bytes = answer.rawBytes ?: answer.body.toByteArray(Charsets.UTF_8)
            if (!ex.responseHeaders.containsKey("Content-Type")) ex.responseHeaders.add("Content-Type", "application/json")
            if (answer.status == 204) {
                ex.sendResponseHeaders(204, -1)
            } else {
                ex.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            }
        } catch (_: java.io.IOException) {
            // The client went away (a cancelled or timed-out request).
        } catch (_: InterruptedException) {
        } finally {
            ex.close()
        }
    }

    override fun close() {
        server.stop(0)
    }
}

fun gzip(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return out.toByteArray()
}

/** A client against the stub, with waits recorded instead of slept. */
class Rig(configure: RewloyOptions.Builder.() -> Unit = {}) : Closeable {
    val server = StubServer()
    val waits = CopyOnWriteArrayList<Long>()
    val rewloy: Rewloy = Rewloy {
        baseUrl(server.url)
        sleeper { ms, _ -> waits.add(ms) }
        configure()
    }

    override fun close() = server.close()
}

fun error(code: String, message: String = "message", status: Int = 400, details: String? = null): Answer =
    Answer(status, """{"error":{"code":"$code","message":"$message","requestId":"body-id"${if (details != null) ""","details":$details""" else ""}}}""")

/** Runs a test body against a rig and closes it; returns `Unit`, so that JUnit runs the method (it skips ones that return a value). */
fun Rig.test(block: (Rig) -> Unit) {
    use(block)
}
