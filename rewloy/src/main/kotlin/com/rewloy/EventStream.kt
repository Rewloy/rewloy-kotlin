package com.rewloy

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** An open connection of a stream: the answer, and what ends it. */
internal class StreamConnection(
    val response: TransportResponse,
    private val attempt: CancelToken,
    private val links: List<CancelToken.Registration>,
) : Closeable {
    private var extra: List<CancelToken.Registration> = emptyList()

    /** Takes over registrations that must end with the connection. */
    fun adopt(more: List<CancelToken.Registration>) {
        extra = more
    }

    /**
     * Never blocks: the transport aborts the connection on its own thread, and the answer is closed on one too,
     * because closing a stream the JDK is still reading from can wait for that read.
     */
    override fun close() {
        for (link in links) link.close()
        for (link in extra) link.close()
        attempt.cancel()
        val thread = Thread({ response.close() }, "rewloy-sse-close")
        thread.isDaemon = true
        thread.start()
    }
}

/**
 * A server-sent event stream (`liveFeed`, `holderCardEvents`): iterate it to read events as they arrive.
 *
 * ```kotlin
 * rewloy.liveFeed().use { feed ->
 *     for (event in feed) {
 *         if (event.event == "event") println(event.json())
 *     }
 * }
 * ```
 * ```java
 * try (EventStream feed = rewloy.liveFeed()) {
 *     for (ServerSentEvent event : feed) { … }
 * }
 * ```
 *
 * The iteration **blocks** until the next event; run it on a worker thread, never on Android's main thread.
 *
 * - **Reconnection.** When the connection drops or the server ends the stream, it reconnects by itself (as a
 *   browser's `EventSource` does) after the server's `retry:` time, with backoff up to 30 seconds while it keeps
 *   failing, sending `Last-Event-ID` when an event had an `id`. `RequestOptions(reconnect = false)` turns that off.
 * - **Silence.** The API sends a heartbeat every 25 seconds; after `idleTimeoutMs` (default 60 seconds) without
 *   a byte the connection counts as dead and is replaced.
 * - **Ending.** [close] (from any thread), a cancelled [CancelToken] or leaving the loop ends the stream quietly.
 *   An error that reconnecting cannot fix (401, 403, 404) is thrown from the iteration as [RewloyException].
 * - **Once.** A stream can be iterated once.
 */
public class EventStream internal constructor(private val source: Source) : Iterable<ServerSentEvent>, Closeable {
    internal class Source(
        val operation: String,
        /** Opens a connection, sending `Last-Event-ID` when the first argument is not empty; [CancelToken] ends it. */
        val connect: (lastEventId: String, stop: CancelToken) -> StreamConnection,
        val reconnect: Boolean,
        val idleTimeoutMs: Long,
        val sleeper: Sleeper,
        val stop: CancelToken,
        val release: Closeable,
    )

    private val started = AtomicBoolean(false)

    @Volatile
    private var lastId: String = ""

    @Volatile
    private var retryMs: Long = DEFAULT_RETRY_MS

    @Volatile
    private var requestIdValue: String? = null

    @Volatile
    private var modeValue: String? = null

    /** The last event ID the stream gave (`""` when it has none yet). */
    public val lastEventId: String get() = lastId

    /** How long the stream waits before it reconnects, in milliseconds: the server's `retry:`, else 3000. */
    public val retryDelayMs: Long get() = retryMs

    /** `x-request-id` of the current connection. */
    public val requestId: String? get() = requestIdValue

    /** `Rewloy-Mode` of the current connection (`test` for a test key's stream). */
    public val mode: String? get() = modeValue

    /** Ends the stream: the iteration stops, and a read that is blocked is aborted. Safe from any thread. */
    override fun close() {
        source.stop.cancel()
    }

    /** @throws IllegalStateException when the stream was iterated before. */
    override fun iterator(): Iterator<ServerSentEvent> {
        check(!started.getAndSet(true)) { "An EventStream can be iterated once." }
        return Run()
    }

    private object Eof
    private object Cancelled

    private inner class Run : Iterator<ServerSentEvent> {
        private val pending = java.util.ArrayDeque<ServerSentEvent>()
        private var finished = false
        private var connection: StreamConnection? = null
        private var queue: java.util.concurrent.LinkedBlockingQueue<Any>? = null
        private var cancelLink: CancelToken.Registration? = null
        private var parser: SseParser? = null
        private var failures = 0
        private val bytes = ByteBuffer.allocate(READ_SIZE + 8)
        private val chars = CharBuffer.allocate(READ_SIZE + 8)
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        override fun hasNext(): Boolean {
            while (pending.isEmpty() && !finished) {
                try {
                    advance()
                } catch (e: Throwable) {
                    finish()
                    throw e
                }
            }
            return pending.isNotEmpty()
        }

        override fun next(): ServerSentEvent {
            if (!hasNext()) throw NoSuchElementException()
            return pending.removeFirst()
        }

        private fun finish() {
            finished = true
            drop()
            try {
                source.release.close()
            } catch (_: IOException) {
                // Nothing to release.
            }
            source.stop.cancel()
        }

        private fun drop() {
            cancelLink?.close()
            cancelLink = null
            queue = null
            connection?.close()
            connection = null
        }

        private fun advance() {
            if (source.stop.isCancelled) {
                finish()
                return
            }
            if (queue == null) open() else read(queue!!)
        }

        private fun open() {
            val conn = try {
                source.connect(lastId, source.stop)
            } catch (e: CancellationException) {
                finish()
                return
            } catch (e: RewloyException) {
                if (source.stop.isCancelled) {
                    finish()
                    return
                }
                if (!source.reconnect || !isTransient(e)) throw e
                failures++
                pause(reconnectDelay())
                return
            }
            connection = conn
            requestIdValue = conn.response.headers["x-request-id"]
            modeValue = conn.response.headers["rewloy-mode"]
            var body = conn.response.body
            if (conn.response.headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true) body = GZIPInputStream(body)
            parser = SseParser(lastId)
            decoder.reset()
            bytes.clear()
            // A reader thread of its own: a read blocked on the JDK's HttpURLConnection cannot be aborted from outside,
            // and this way closing the stream, cancelling it and the idle check all work the same on every transport.
            val q = java.util.concurrent.LinkedBlockingQueue<Any>(QUEUE_SIZE)
            queue = q
            cancelLink = source.stop.onCancel { q.offer(Cancelled) }
            startReader(body, q)
        }

        private fun startReader(body: InputStream, q: java.util.concurrent.LinkedBlockingQueue<Any>) {
            val thread = Thread(
                {
                    val raw = ByteArray(READ_SIZE)
                    try {
                        while (true) {
                            val n = body.read(raw)
                            if (n < 0) {
                                q.put(Eof)
                                return@Thread
                            }
                            q.put(raw.copyOf(n))
                        }
                    } catch (e: IOException) {
                        q.offer(e)
                    } catch (_: InterruptedException) {
                        // Abandoned.
                    }
                },
                "rewloy-sse",
            )
            thread.isDaemon = true
            thread.start()
        }

        private fun read(q: java.util.concurrent.LinkedBlockingQueue<Any>) {
            val message: Any? = try {
                if (source.idleTimeoutMs > 0) q.poll(source.idleTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) else q.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                finish()
                return
            }
            when (message) {
                null -> dropped(SocketTimeoutException("no data"))
                Cancelled -> finish()
                is IOException -> dropped(message)
                is ByteArray -> decode(message, false)
                else -> decode(ByteArray(0), true)
            }
        }

        private fun decode(data: ByteArray, end: Boolean) {
            val p = parser!!
            val text: String
            if (!end) {
                bytes.put(data, 0, data.size)
                bytes.flip()
                chars.clear()
                decoder.decode(bytes, chars, false)
                bytes.compact()
                chars.flip()
                text = chars.toString()
            } else {
                bytes.flip()
                chars.clear()
                decoder.decode(bytes, chars, true)
                decoder.flush(chars)
                bytes.clear()
                chars.flip()
                text = chars.toString()
            }
            for (event in p.push(text)) {
                lastId = event.id
                failures = 0
                pending.add(event)
            }
            lastId = p.lastEventId
            p.retryMilliseconds?.let { retryMs = it }
            if (end) {
                p.end()
                drop()
                if (!source.reconnect) {
                    finish()
                    return
                }
                pause(retryMs)
            }
        }

        private fun dropped(e: IOException) {
            if (source.stop.isCancelled) {
                finish()
                return
            }
            val error: RewloyException = if (e is SocketTimeoutException) {
                RewloyTimeoutException("no data for ${source.idleTimeoutMs} ms", source.operation, requestIdValue, e)
            } else {
                RewloyConnectionException(e.message ?: e.javaClass.simpleName, source.operation, requestIdValue, e)
            }
            drop()
            if (!source.reconnect) throw error
            failures++
            pause(reconnectDelay())
        }

        private fun pause(millis: Long) {
            source.sleeper.sleep(millis, source.stop)
            if (source.stop.isCancelled) finish()
        }

        private fun reconnectDelay(): Long {
            val backoff = minOf(MAX_RECONNECT_MS, 1000L * (1L shl minOf(failures, 16)))
            return maxOf(retryMs, backoff)
        }

        private fun isTransient(e: RewloyException): Boolean =
            e is RewloyConnectionException || e.status == 429 || Retry.isGatewayStatus(e.status)
    }

    private companion object {
        const val DEFAULT_RETRY_MS = 3000L
        const val MAX_RECONNECT_MS = 30_000L
        const val READ_SIZE = 8192
        const val QUEUE_SIZE = 64
    }
}
