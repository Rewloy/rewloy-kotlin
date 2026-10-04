package com.rewloy.coroutines

import com.rewloy.Answer
import com.rewloy.Fixtures
import com.rewloy.RewloyException
import com.rewloy.Rig
import com.rewloy.error
import com.rewloy.test
import com.rewloy.models.ListCustomersQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoroutinesTest {
    @Test
    fun `a suspending call gives the data, off the caller's thread`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS))
        val caller = Thread.currentThread()
        var ran: Thread? = null
        val card = runBlocking { rig.rewloy.suspending { ran = Thread.currentThread(); getPass("ABCD-EFGH-JKLM") } }
        assertEquals("ABCD-EFGH-JKLM", card.serial)
        assertTrue(ran !== caller)
    }

    @Test
    fun `it can make several calls and walk a list`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.script = { r, _ ->
            val page = Regex("page=(\\d+)").find(r.query)?.groupValues?.get(1)?.toInt() ?: 1
            Answer(200, Fixtures.customers((page - 1) * 2 + 1, if (page == 2) 1 else 2, page, 2, 3))
        }
        val ids = runBlocking { rig.rewloy.suspending { listCustomersAll(ListCustomersQuery(limit = 2)).map { it.personId } } }
        assertEquals(listOf("p1", "p2", "p3"), ids)
    }

    @Test
    fun `a failure comes out as the exception it is`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("NOT_FOUND", status = 404))
        val e = assertFailsWith<RewloyException> { runBlocking { rig.rewloy.suspending { getPass("X") } } }
        assertEquals("NOT_FOUND", e.code)
    }

    @Test
    fun `cancelling the coroutine aborts the request in flight`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 8000))
        val start = System.nanoTime()
        runBlocking {
            val job = launch { rig.rewloy.suspending { getPass("S") } }
            delay(200)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000)
    }

    @Test
    fun `a timeout of the coroutine cancels the call too`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 8000))
        assertFailsWith<CancellationException> { runBlocking { withTimeout(250) { rig.rewloy.suspending { getPass("S") } } } }
    }

    private fun chunk(vararg lines: String): ByteArray = (lines.joinToString("\n") + "\n\n").toByteArray()

    @Test
    fun `a stream is a flow, and cancelling the collector closes it`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("event: event", "data: 1"), chunk("event: event", "data: 2"), chunk("event: event", "data: 3")), closeAfterChunks = false))
        val got = runBlocking { rig.rewloy.liveFeed().asFlow().take(2).toList().map { it.data } }
        assertEquals(listOf("1", "2"), got)
    }

    @Test
    fun `a flow ends when the server ends the stream`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("event: event", "data: only"))))
        val got = runBlocking { rig.rewloy.liveFeed(com.rewloy.RequestOptions(reconnect = false)).asFlow().toList().map { it.data } }
        assertEquals(listOf("only"), got)
    }

    @Test
    fun `a flow throws what the stream cannot recover from`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("UNAUTHENTICATED", status = 401))
        assertFailsWith<RewloyException> { runBlocking { rig.rewloy.liveFeed().asFlow().toList() } }
    }
}
