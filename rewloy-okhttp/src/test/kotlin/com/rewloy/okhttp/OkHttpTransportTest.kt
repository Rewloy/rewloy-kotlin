package com.rewloy.okhttp

import com.rewloy.Answer
import com.rewloy.CancelToken
import com.rewloy.Fixtures
import com.rewloy.RequestOptions
import com.rewloy.RewloyException
import com.rewloy.RewloyTimeoutException
import com.rewloy.Rig
import com.rewloy.error
import com.rewloy.test
import com.rewloy.gzip
import com.rewloy.models.PassActionBody
import com.rewloy.models.UpdateLocationBody
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same calls as the HttpURLConnection tests make, over OkHttp. */
class OkHttpTransportTest {
    private fun rig() = Rig { apiKey("rwk_abc"); transport(OkHttpTransport()); maxRetries(0) }

    @Test
    fun `calls an operation with its headers`() = rig().test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, mapOf("X-Request-Id" to "r1")))
        val res = rig.rewloy.getPassWithResponse("ABCD-EFGH-JKLM")
        assertEquals("ABCD-EFGH-JKLM", res.data.serial)
        assertEquals("r1", res.requestId)
        val r = rig.server.received.single()
        assertEquals("Bearer rwk_abc", r.header("authorization"))
        assertEquals("gzip", r.header("accept-encoding"))
        assertTrue(r.header("user-agent")!!.startsWith("rewloy-kotlin/0.1.0"), r.header("user-agent"))
    }

    @Test
    fun `sends a JSON body with its content type`() = rig().test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.ACTION))
        rig.rewloy.passAction("S", PassActionBody("visit", "l"), RequestOptions(idempotencyKey = "k1"))
        val r = rig.server.received.single()
        assertEquals("application/json", r.header("content-type"))
        assertEquals("""{"action":"visit","locationId":"l"}""", r.body)
        assertEquals("k1", r.header("idempotency-key"))
    }

    @Test
    fun `sends PATCH on any JVM`() = rig().test { rig ->
        rig.server.enqueue(Answer(200, """{"data":{}}"""))
        assertFailsWith<RewloyException> { rig.rewloy.updateLocation("loc", UpdateLocationBody()) }
        assertEquals("PATCH", rig.server.received.single().method)
        assertEquals("{}", rig.server.received.single().body)
    }

    @Test
    fun `a POST without a body goes out with a content length of zero`() = Rig { staffSession("rws_abc"); transport(OkHttpTransport()) }.test { rig ->
        rig.server.enqueue(Answer(204))
        rig.rewloy.logout()
        assertEquals("0", rig.server.received.single().header("content-length"))
    }

    @Test
    fun `reads an error answer, a gzip body and does not follow redirects`() = rig().test { rig ->
        rig.server.enqueue(error("UNAUTHENTICATED", status = 401))
        assertEquals(401, assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }.status)
        rig.server.enqueue(Answer(200, headers = mapOf("Content-Encoding" to "gzip"), rawBytes = gzip(Fixtures.PASS)))
        assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
        rig.server.enqueue(Answer(302, "", mapOf("Location" to "http://127.0.0.1:1/elsewhere")))
        assertEquals(302, assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }.status)
        assertEquals(3, rig.server.received.size)
    }

    @Test
    fun `times out and cancels`() = Rig { apiKey("rwk_abc"); transport(OkHttpTransport()); maxRetries(0); timeoutMs(200) }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 3000))
        assertFailsWith<RewloyTimeoutException> { rig.rewloy.getPass("S") }
        val cancel = CancelToken()
        thread { Thread.sleep(100); cancel.cancel() }
        assertFailsWith<CancellationException> { rig.rewloy.getPass("S", RequestOptions(cancel = cancel, timeoutMs = 10_000)) }
        assertNull(null)
    }
}
