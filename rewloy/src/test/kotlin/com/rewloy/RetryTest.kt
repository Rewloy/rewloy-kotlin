package com.rewloy

import com.rewloy.models.PassActionBody
import com.rewloy.models.CreateSegmentBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryTest {
    private val action = PassActionBody("visit", "l")

    @Test
    fun `retries a GET on 502, 503, 504 and Cloudflare's 520 to 524`() {
        for (status in listOf(502, 503, 504, 520, 521, 522, 523, 524)) {
            Rig { apiKey("rwk_abc") }.test { rig ->
                rig.server.enqueue(Answer(status, "bad gateway", mapOf("Content-Type" to "text/html")), Answer(200, Fixtures.PASS))
                assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial, "status $status")
                assertEquals(2, rig.server.received.size)
                assertEquals(1, rig.waits.size)
            }
        }
    }

    @Test
    fun `does not retry what is not a transient failure`() {
        for (status in listOf(400, 401, 403, 404, 409, 422, 500, 501)) {
            Rig { apiKey("rwk_abc") }.test { rig ->
                rig.server.enqueue(error("VALIDATION", status = status))
                val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
                assertEquals(status, e.status)
                assertEquals(1, rig.server.received.size, "status $status")
            }
        }
    }

    @Test
    fun `gives up after maxRetries and throws the last error`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(503, "x"))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals(503, e.status)
        assertEquals("HTTP_503", e.code)
        assertEquals(3, rig.server.received.size) // the first attempt and two retries
        assertEquals(2, rig.waits.size)
    }

    @Test
    fun `maxRetries is per client and per call`() = Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
        rig.server.enqueue(Answer(503, "x"))
        assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals(1, rig.server.received.size)
        assertFailsWith<RewloyException> { rig.rewloy.getPass("S", RequestOptions(maxRetries = 4)) }
        assertEquals(6, rig.server.received.size)
    }

    @Test
    fun `a POST without an idempotency key is never repeated`() = Rig { staffSession("rws_abc") }.test { rig ->
        rig.server.enqueue(Answer(503, "x"), Answer(201, "{}"))
        assertFailsWith<RewloyException> { rig.rewloy.createSegment(CreateSegmentBody("S", com.rewloy.models.CreateSegmentBodyRule())) }
        assertEquals(1, rig.server.received.size)
    }

    @Test
    fun `a POST with an idempotency key is retried with the same key`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(503, "x"), Answer(502, "x"), Answer(200, Fixtures.ACTION))
        rig.rewloy.passAction("S", action, RequestOptions(idempotencyKey = "fis-42-0001"))
        assertEquals(listOf<String?>("fis-42-0001", "fis-42-0001", "fis-42-0001"), rig.server.received.map { it.header("idempotency-key") })
    }

    @Test
    fun `waits out 409 IDEMPOTENCY_IN_PROGRESS`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("IDEMPOTENCY_IN_PROGRESS", status = 409), Answer(200, Fixtures.ACTION))
        assertEquals(5.0, rig.rewloy.passAction("S", action, RequestOptions(idempotencyKey = "kampanya-0001")).balance)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `any other 409 is an error`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("CONFLICT", status = 409))
        assertEquals("CONFLICT", assertFailsWith<RewloyException> { rig.rewloy.passAction("S", action, RequestOptions(idempotencyKey = "fis-42-0002")) }.code)
        assertEquals(1, rig.server.received.size)
    }

    @Test
    fun `honours Retry-After up to 60 seconds`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"slow"}}""", mapOf("Retry-After" to "7")), Answer(200, Fixtures.PASS))
        rig.rewloy.getPass("S")
        assertEquals(listOf(7000L), rig.waits)
    }

    @Test
    fun `does not sleep through a Retry-After longer than 60 seconds`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"locked"}}""", mapOf("Retry-After" to "900")))
        val e = assertFailsWith<RateLimitException> { rig.rewloy.getPass("S") }
        assertEquals(900, e.retryAfterSeconds)
        assertEquals(1, rig.server.received.size)
        assertTrue(rig.waits.isEmpty())
    }

    @Test
    fun `retries a dropped connection`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(drop = true), Answer(200, Fixtures.PASS))
        assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `a dropped connection on a POST without a key is a RewloyConnectionException`() = Rig { staffSession("rws_abc") }.test { rig ->
        rig.server.enqueue(Answer(drop = true))
        val e = assertFailsWith<RewloyConnectionException> { rig.rewloy.createSegment(CreateSegmentBody("S", com.rewloy.models.CreateSegmentBodyRule())) }
        assertEquals(0, e.status)
        assertEquals("CONNECTION_ERROR", e.code)
        assertEquals("createSegment", e.operation)
        assertTrue(e.cause is java.io.IOException)
        assertEquals(1, rig.server.received.size)
    }

    @Test
    fun `nothing listens, a connection error`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val rewloy = Rewloy { apiKey("rwk_abc"); baseUrl("http://127.0.0.1:$port"); maxRetries(0) }
        val e = assertFailsWith<RewloyConnectionException> { rewloy.getPass("S") }
        assertEquals("CONNECTION_ERROR", e.code)
    }

    @Test
    fun `an attempt that takes too long is a timeout, and is retried`() = Rig { apiKey("rwk_abc"); timeoutMs(150) }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 800), Answer(200, Fixtures.PASS))
        assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `a timeout that keeps happening is RewloyTimeoutException`() = Rig { apiKey("rwk_abc"); timeoutMs(100); maxRetries(1) }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 800))
        val e = assertFailsWith<RewloyTimeoutException> { rig.rewloy.getPass("S") }
        assertEquals("TIMEOUT", e.code)
        assertTrue((e as RewloyException) is RewloyConnectionException)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `the timeout covers the body too`() = Rig { apiKey("rwk_abc"); timeoutMs(300); maxRetries(0) }.test { rig ->
        // Headers at once, the body in a trickle that outlasts the timeout without any single read stalling for long.
        rig.server.script = { _, _ -> Answer(200, chunks = Fixtures.PASS.map { byteArrayOf(it.code.toByte()) }, chunkDelayMs = 20, headers = mapOf("Content-Type" to "application/json")) }
        assertFailsWith<RewloyTimeoutException> { rig.rewloy.getPass("S") }
    }

    @Test
    fun `backs off 0,5 s doubling to 8 s, with jitter between half and all`() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L), (0..6).map { Retry.backoff(it, 1.0) })
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 4000L), (0..5).map { Retry.backoff(it, 0.0) })
        assertEquals(8000L, Retry.backoff(1000, 1.0))
        repeat(200) {
            val w = Retry.backoff(2, Math.random())
            assertTrue(w in 1000L..2000L, "$w")
        }
    }

    @Test
    fun `waits the backoff when there is no Retry-After`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(503, "x"))
        assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals(2, rig.waits.size)
        assertTrue(rig.waits[0] in 250L..500L && rig.waits[1] in 500L..1000L, rig.waits.toString())
    }

    @Test
    fun `reads Retry-After as seconds, fractions or an HTTP date`() {
        assertEquals(7000L, Retry.parseRetryAfter("7"))
        assertEquals(1500L, Retry.parseRetryAfter(" 1.5 "))
        assertEquals(0L, Retry.parseRetryAfter("0"))
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("GMT") }
        val now = 1_790_000_000_000L
        assertEquals(30_000L, Retry.parseRetryAfter(fmt.format(java.util.Date(now + 30_000)), now))
        assertEquals(0L, Retry.parseRetryAfter(fmt.format(java.util.Date(now - 30_000)), now))
        assertNull(Retry.parseRetryAfter("soon"))
        assertNull(Retry.parseRetryAfter(null))
        assertNull(Retry.parseRetryAfter(""))
    }

    @Test
    fun `a caller's own Idempotency-Key header makes a POST repeatable`() = Rig { staffSession("rws_abc") }.test { rig ->
        rig.server.enqueue(Answer(503, "x"), Answer(200, Fixtures.PASS))
        // createSegment takes no key by itself; one given as a header counts.
        assertFailsWith<RewloyException> {
            rig.rewloy.createSegment(CreateSegmentBody("S", com.rewloy.models.CreateSegmentBodyRule()), RequestOptions(headers = mapOf("Idempotency-Key" to "mine")))
        }
        assertEquals(2, rig.server.received.size)
        assertEquals(listOf("mine", "mine"), rig.server.received.map { it.header("idempotency-key") })
    }
}
