package com.rewloy.coroutines

import com.rewloy.CancelToken
import com.rewloy.EventStream
import com.rewloy.Rewloy
import com.rewloy.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Runs blocking Rewloy calls off the caller's thread, as a suspend function that cancels with its coroutine:
 * cancelling the coroutine aborts the request that is in flight (the connection is closed, a retry that is waiting
 * stops waiting).
 *
 * ```kotlin
 * val card = rewloy.suspending { getPass("ABCD-EFGH-JKLM") }
 * val receipt = rewloy.suspending {
 *     passAction(serial, PassActionBody("earn-stamps", locationId), RequestOptions(idempotencyKey = "fis-$no"))
 * }
 * ```
 *
 * [block] runs on [Dispatchers.IO] with a client that shares the receiver's settings and whose calls are all tied to
 * this coroutine. It may make several calls, and may walk a paged list.
 */
public suspend fun <T> Rewloy.suspending(block: Rewloy.() -> T): T = coroutineScope {
    val token = CancelToken()
    val scoped = withCancel(token)
    val work = async(Dispatchers.IO) { scoped.block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        token.cancel()
        throw e
    }
}

/**
 * The events of a stream as a cold [Flow]: collecting reads the stream on [Dispatchers.IO], and cancelling the
 * collector closes it. A failure the stream cannot recover from (401, 403, 404, or any error with reconnection
 * turned off) is thrown from the collector as a `RewloyException`.
 *
 * ```kotlin
 * rewloy.suspending { liveFeed() }.asFlow().collect { event -> println(event.json()) }
 * ```
 */
public fun EventStream.asFlow(): Flow<ServerSentEvent> = channelFlow {
    val stream = this@asFlow
    launch(Dispatchers.IO) {
        try {
            for (event in stream) send(event)
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            close(t)
        }
    }
    awaitClose { stream.close() }
}
