package com.rewloy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorTest {
    @Test
    fun `an API error carries status, code, title, detail, details, docs and request id`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(
                422,
                """{"error":{"code":"VALIDATION","message":"Geçersiz bilgi","docs":"https://rewloy.com/gelistiriciler/hatalar#VALIDATION","requestId":"in-body","details":[{"field":"email","rule":"format","message":"bad"}]}}""",
                mapOf("X-Request-Id" to "from-header"),
            ),
        )
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals(422, e.status)
        assertEquals("VALIDATION", e.code)
        assertEquals("Geçersiz bilgi", e.detail)
        assertEquals(ErrorCode.titles["VALIDATION"], e.title)
        assertNotNull(e.title)
        assertEquals("https://rewloy.com/gelistiriciler/hatalar#VALIDATION", e.docs)
        assertEquals("from-header", e.requestId)
        assertEquals("getPass", e.operation)
        assertEquals("email", e.details!!.asArray()!![0]!!.asObject()!!["field"]!!.asString())
        assertTrue(e.body!!.contains("Geçersiz bilgi"))
        assertEquals("application/json", e.headers!!["content-type"])
        assertEquals("422 VALIDATION: Geçersiz bilgi (getPass, requestId from-header)", e.message)
    }

    @Test
    fun `the request id comes from the body when the header is missing`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("NOT_FOUND", status = 404))
        assertEquals("body-id", assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }.requestId)
    }

    @Test
    fun `a 429 is a RateLimitException with the seconds to wait`() {
        Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
            rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"x","details":{"retryAfterSec":12.2}}}"""))
            val e = assertFailsWith<RateLimitException> { rig.rewloy.getPass("S") }
            assertEquals(13L, e.retryAfterSeconds)
            assertEquals(429, e.status)
            assertEquals("RATE_LIMITED", e.code)
        }
        Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
            rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"x"}}""", mapOf("Retry-After" to "2.1")))
            assertEquals(3L, assertFailsWith<RateLimitException> { rig.rewloy.getPass("S") }.retryAfterSeconds)
        }
        Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
            rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"x"}}"""))
            val e = assertFailsWith<RateLimitException> { rig.rewloy.getPass("S") }
            assertNull(e.retryAfterSeconds)
            assertNull(e.rateLimit)
        }
        Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
            rig.server.enqueue(Answer(429, """{"error":{"code":"RATE_LIMITED","message":"x"}}""", mapOf("Retry-After" to "9", "RateLimit-Limit" to "60", "RateLimit-Remaining" to "0", "RateLimit-Reset" to "9")))
            val e = assertFailsWith<RateLimitException> { rig.rewloy.getPass("S") }
            assertEquals(RewloyRateLimit(60, 0, 9), e.rateLimit)
            assertEquals(9L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `a proxy's error page gets an HTTP code`() = Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
        rig.server.enqueue(Answer(502, "<html>Bad gateway</html>", mapOf("Content-Type" to "text/html")))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals("HTTP_502", e.code)
        assertEquals(502, e.status)
        assertNull(e.title)
        assertEquals("<html>Bad gateway</html>", e.body)
    }

    @Test
    fun `a 401 without a challenge is still read as the API's answer`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("UNAUTHENTICATED", status = 401))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals(401, e.status)
        assertEquals("UNAUTHENTICATED", e.code)
        assertEquals(1, rig.server.received.size)
    }

    @Test
    fun `a 401 to a POST that must be sent once still gives the API's error`() = Rig { staffSession("rws_abc") }.test { rig ->
        // Such a POST is streamed (so the JDK cannot re-send it), and the JDK hides the body of a 401 then: the
        // transport asks again, buffered, which is safe because a 401 did nothing.
        rig.server.enqueue(error("UNAUTHENTICATED", status = 401))
        val e = assertFailsWith<RewloyException> { rig.rewloy.createSegment(com.rewloy.models.CreateSegmentBody("S", com.rewloy.models.CreateSegmentBodyRule())) }
        assertEquals(401, e.status)
        assertEquals("UNAUTHENTICATED", e.code)
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `an error with no body gets the reason or the status`() = Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
        rig.server.enqueue(Answer(500, ""))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals("HTTP_500", e.code)
        assertTrue(e.detail.isNotEmpty())
    }

    @Test
    fun `every error code has a constant and a title in the catalogue`() {
        assertEquals("RATE_LIMITED", ErrorCode.RATE_LIMITED)
        assertTrue("VALIDATION" in ErrorCode.all)
        assertNull(ErrorCode.title("A_CODE_FROM_THE_FUTURE"))
        assertNull(ErrorCode.title(null))
        assertTrue(ErrorCode.all.size > 20)
        assertTrue(ErrorCode.titles.keys.all { it in ErrorCode.all })
    }

    @Test
    fun `the exception hierarchy`() {
        val timeout: RewloyException = RewloyTimeoutException("t")
        assertTrue(timeout is RewloyConnectionException)
        assertTrue(timeout is RuntimeException)
        assertEquals("TIMEOUT", timeout.code)
        assertEquals(0, timeout.status)
        assertEquals("CONNECTION_ERROR", RewloyConnectionException("c").code)
        val limited: RewloyException = RateLimitException(429, "RATE_LIMITED", "d", 5)
        assertTrue(limited is RateLimitException)
    }
}
