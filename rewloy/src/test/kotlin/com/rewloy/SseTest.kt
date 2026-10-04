package com.rewloy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.concurrent.thread

class SseTest {
    private fun events(vararg pieces: String): List<String> {
        val p = SseParser()
        return pieces.flatMap { p.push(it) }.map { "${it.event}|${it.data}|${it.id}" }
    }

    @Test
    fun `reads the API's events, comments and retry`() {
        val p = SseParser()
        val out = p.push("retry: 5000\n\n: hb\n\nevent: event\ndata: {\"kind\":\"earn\"}\n\n")
        assertEquals(1, out.size)
        assertEquals("event", out[0].event)
        assertEquals("""{"kind":"earn"}""", out[0].data)
        assertEquals("earn", out[0].json().asObject()!!["kind"]!!.asString())
        assertEquals(5000L, p.retryMilliseconds)
    }

    @Test
    fun `an event with no type is a message, and data lines are joined`() {
        assertEquals(listOf("message|a\nb|"), events("data: a\ndata: b\n\n"))
        assertEquals(listOf("message|x|"), events("data:x\n\n"))
        assertEquals(listOf("message||"), events("data\n\n"))
    }

    @Test
    fun `reads the same events however the text is cut`() {
        val text = "retry: 10\nid: 7\nevent: a\ndata: héllo\ndata: wörld\r\n\r\n: c\r\ndata: 2\r\rdata: 3\n\n"
        val whole = events(text)
        assertEquals(listOf("a|héllo\nwörld|7", "message|2|7", "message|3|7"), whole)
        for (size in 1..text.length) assertEquals(whole, events(*text.chunked(size).toTypedArray()), "pieces of $size")
    }

    @Test
    fun `a CR then an LF in the next piece is one line ending`() {
        assertEquals(listOf("message|a|", "message|b|"), events("data: a\r", "\n\r", "\ndata: b\r\n\r\n"))
    }

    @Test
    fun `ignores a byte order mark and an event that was never completed`() {
        assertEquals(listOf("message|a|"), events("﻿data: a\n\n"))
        val p = SseParser()
        p.push("data: half")
        p.end()
        assertEquals(0, p.push("\n\n").size)
    }

    @Test
    fun `the last event id carries over and an id with a NUL is ignored`() {
        val p = SseParser("start")
        assertEquals("start", p.lastEventId)
        p.push("id: 3\ndata: x\n\n")
        assertEquals("3", p.lastEventId)
        p.push("id: a\u0000b\ndata: y\n\n")
        assertEquals("3", p.lastEventId)
        assertEquals(listOf("message|z|"), SseParser().push("id\ndata: z\n\n").map { "${it.event}|${it.data}|${it.id}" })
    }

    @Test
    fun `a bad retry is ignored`() {
        val p = SseParser()
        p.push("retry: soon\nretry: -5\nretry: 1.5\n\n")
        assertEquals(null, p.retryMilliseconds)
    }

    // ---------------------------------------------------------------- over the wire

    private fun chunk(vararg lines: String): ByteArray = (lines.joinToString("\n") + "\n\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `iterates a stream's events and ends when the server ends it`() = Rig { staffSession("rws_abc"); merchant("m") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("retry: 5000"), chunk(": hb"), chunk("event: event", "data: {\"n\":1}"), chunk("event: event", "data: {\"n\":2}"))))
        rig.rewloy.liveFeed(RequestOptions(reconnect = false)).use { feed ->
            val seen = feed.map { it.json().asObject()!!["n"]!!.asInt() }
            assertEquals(listOf(1, 2), seen)
            assertEquals(5000L, feed.retryDelayMs)
        }
        val r = rig.server.received.single()
        assertEquals("/v1/live", r.path)
        assertEquals("text/event-stream", r.header("accept"))
        assertEquals("identity", r.header("accept-encoding"))
        assertEquals("no-cache", r.header("cache-control"))
        assertEquals("m", r.header("rewloy-merchant"))
    }

    @Test
    fun `decodes UTF-8 that is split between reads`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val bytes = "event: event\ndata: ğüşıöç — 😀\n\n".toByteArray(Charsets.UTF_8)
        rig.server.enqueue(Answer(chunks = bytes.map { byteArrayOf(it) }, chunkDelayMs = 1))
        val out = rig.rewloy.liveFeed(RequestOptions(reconnect = false)).use { it.map { e -> e.data } }
        assertEquals(listOf("ğüşıöç — 😀"), out)
    }

    @Test
    fun `reconnects with Last-Event-ID after the server's retry time`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(chunks = listOf(chunk("retry: 250"), chunk("id: 41", "event: event", "data: one"))),
            Answer(chunks = listOf(chunk("id: 42", "event: event", "data: two")), closeAfterChunks = true),
        )
        val feed = rig.rewloy.liveFeed()
        val got = ArrayList<String>()
        for (e in feed) {
            got.add(e.data)
            if (got.size == 2) feed.close()
        }
        assertEquals(listOf("one", "two"), got)
        assertEquals(listOf("", "41"), rig.server.received.map { it.header("last-event-id") ?: "" })
        assertTrue(rig.waits.first() >= 250, rig.waits.toString())
        assertEquals("42", feed.lastEventId)
    }

    @Test
    fun `an error that reconnecting cannot fix ends the iteration with the exception`() {
        for (status in listOf(401, 403, 404)) {
            Rig { apiKey("rwk_abc") }.test { rig ->
                rig.server.enqueue(error("UNAUTHENTICATED", status = status))
                val e = assertFailsWith<RewloyException> { rig.rewloy.liveFeed().use { f -> f.forEach { } } }
                assertEquals(status, e.status)
                assertEquals(1, rig.server.received.size)
            }
        }
    }

    @Test
    fun `keeps trying through a 503`() = Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
        rig.server.enqueue(Answer(503, "x"), Answer(chunks = listOf(chunk("event: event", "data: ok"))))
        val feed = rig.rewloy.liveFeed()
        val first = feed.iterator().next()
        feed.close()
        assertEquals("ok", first.data)
        assertEquals(2, rig.server.received.size)
        assertTrue(rig.waits.isNotEmpty())
    }

    @Test
    fun `close from another thread aborts a read that is blocked`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("event: event", "data: first")), closeAfterChunks = false))
        val feed = rig.rewloy.liveFeed()
        val it = feed.iterator()
        assertEquals("first", it.next().data)
        val closer = thread { Thread.sleep(200); feed.close() }
        val start = System.nanoTime()
        assertTrue(!it.hasNext())
        closer.join()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
    }

    @Test
    fun `a cancel token ends a stream quietly`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("event: event", "data: first")), closeAfterChunks = false))
        val cancel = CancelToken()
        val feed = rig.rewloy.liveFeed(RequestOptions(cancel = cancel))
        val it = feed.iterator()
        assertEquals("first", it.next().data)
        thread { Thread.sleep(150); cancel.cancel() }
        assertTrue(!it.hasNext())
    }

    @Test
    fun `a silent connection is dropped after the idle time and replaced`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(chunks = listOf(chunk(": hb")), closeAfterChunks = false),
            Answer(chunks = listOf(chunk("event: event", "data: after")), closeAfterChunks = true),
        )
        val feed = rig.rewloy.liveFeed(RequestOptions(idleTimeoutMs = 300))
        val e = feed.iterator().next()
        feed.close()
        assertEquals("after", e.data)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `without reconnection a silent connection is a RewloyTimeoutException`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk(": hb")), closeAfterChunks = false))
        val feed = rig.rewloy.liveFeed(RequestOptions(idleTimeoutMs = 250, reconnect = false))
        assertFailsWith<RewloyTimeoutException> { feed.forEach { } }
    }

    @Test
    fun `a stream is iterated once and carries the request id and the mode`() = Rig { apiKey("rwk_test_abc") }.test { rig ->
        rig.server.enqueue(Answer(chunks = listOf(chunk("event: event", "data: x")), headers = mapOf("X-Request-Id" to "req-s", "Rewloy-Mode" to "test")))
        val feed = rig.rewloy.liveFeed(RequestOptions(reconnect = false))
        feed.forEach { }
        assertEquals("req-s", feed.requestId)
        assertEquals("test", feed.mode)
        assertFailsWith<IllegalStateException> { feed.iterator() }
    }
}
