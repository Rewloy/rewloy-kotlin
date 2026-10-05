package com.rewloy

import com.rewloy.json.JsonValue
import com.rewloy.models.ListCustomersQuery
import com.rewloy.models.LoginBody
import com.rewloy.models.PassActionBody
import com.rewloy.models.UpdateLocationBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTest {
    @Test
    fun `calls an operation and reads its data`() = Rig { apiKey("rwk_test_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, mapOf("x-request-id" to "req-1")))
        val pass = rig.rewloy.getPass("ABCD-EFGH-JKLM")
        assertEquals("ABCD-EFGH-JKLM", pass.serial)
        assertEquals("stamp", pass.type)
        assertEquals(3.0, pass.balance)
        assertNull(pass.progressLabel)
        assertEquals(false, pass.rewardReady)
        assertEquals(0, pass.rewardsReady)

        val r = rig.server.received.single()
        assertEquals("GET", r.method)
        assertEquals("/v1/passes/ABCD-EFGH-JKLM", r.path)
        assertEquals("Bearer rwk_test_abc", r.header("authorization"))
        assertEquals("application/json", r.header("accept"))
        assertEquals("gzip", r.header("accept-encoding"))
        assertNull(r.header("rewloy-merchant"))
        assertNull(r.header("idempotency-key"))
        assertTrue(r.header("user-agent")!!.startsWith("rewloy-kotlin/0.2.0 java/"), r.header("user-agent"))
    }

    @Test
    fun `sends the staff session with its merchant, which a call can replace`() = Rig { staffSession("rws_abc"); merchant("m-1") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS))
        rig.rewloy.getPass("S")
        rig.rewloy.getPass("S", RequestOptions(merchant = "m-2"))
        assertEquals(listOf("m-1", "m-2"), rig.server.received.map { it.header("rewloy-merchant") })
        assertEquals("Bearer rws_abc", rig.server.received[0].header("authorization"))
    }

    @Test
    fun `leaves the credential out of an operation that takes none of its kind but works without`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(201, """{"data":{"token":"rws_t","mfaRequired":false,"user":{"id":"u","email":"a@b.c"},"businesses":[]}}"""))
        val session = rig.rewloy.login(LoginBody("a@b.c", "pw"))
        assertEquals("rws_t", session.token)
        assertEquals("a@b.c", session.user.email)
        val r = rig.server.received.single()
        assertNull(r.header("authorization"))
        assertEquals("application/json", r.header("content-type"))
        assertEquals("""{"email":"a@b.c","password":"pw"}""", r.body)
    }

    @Test
    fun `an operation that takes the credential gets it, and a business only where it takes one`() = Rig { staffSession("rws_abc"); merchant("m") }.test { rig ->
        rig.server.enqueue(Answer(201, """{"data":{"token":"x","mfaRequired":false,"user":{"id":"u","email":"e"},"businesses":[]}}"""))
        rig.rewloy.login(LoginBody("a", "b"))
        // login is public: no credential and no business.
        val r = rig.server.received.single()
        assertNull(r.header("authorization"))
        assertNull(r.header("rewloy-merchant"))
    }

    @Test
    fun `writes a body with its required fields first and leaves unset ones out`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.ACTION))
        val body = PassActionBody("earn-stamps", "loc-1")
        body.count = 2
        val result = rig.rewloy.passAction("ABCD-EFGH-JKLM", body, RequestOptions(idempotencyKey = "fis-77"))
        assertEquals(5.0, result.balance)
        assertFalse(result.duplicate)
        assertNull(result.promotion)
        val r = rig.server.received.single()
        assertEquals("POST", r.method)
        assertEquals("/v1/passes/ABCD-EFGH-JKLM/actions", r.path)
        assertEquals("""{"action":"earn-stamps","locationId":"loc-1","count":2}""", r.body)
        assertEquals("fis-77", r.header("idempotency-key"))
    }

    @Test
    fun `makes an idempotency key when the call gives none`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.ACTION))
        rig.rewloy.passAction("S", PassActionBody("visit", "l"))
        val key = rig.server.received.single().header("idempotency-key")!!
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(key), key)
    }

    @Test
    fun `escapes path values and writes the query as RFC 3986`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS), Answer(200, Fixtures.customers(1, 1, 1, 2, 1)))
        rig.rewloy.getPass("a b/ç")
        assertEquals("/v1/passes/a%20b%2F%C3%A7", rig.server.received[0].target)
        rig.rewloy.listCustomers(ListCustomersQuery(q = "ayşe kahve+&=", blocked = true, limit = 2))
        assertEquals("/v1/customers?q=ay%C5%9Fe%20kahve%2B%26%3D&blocked=true&limit=2", rig.server.received[1].target)
    }

    @Test
    fun `a path value cannot be empty`() = Rig { apiKey("rwk_abc") }.test { rig ->
        assertFailsWith<IllegalArgumentException> { rig.rewloy.getPass("") }
        assertTrue(rig.server.received.isEmpty())
    }

    @Test
    fun `gives the whole answer with its headers, the test mode and the replay flag`() = Rig { apiKey("rwk_test_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.ACTION, mapOf("X-Request-Id" to "req-9", "Rewloy-Mode" to "test", "Idempotent-Replayed" to "true")))
        val res = rig.rewloy.passActionWithResponse("S", PassActionBody("visit", "l"))
        assertEquals(200, res.statusCode)
        assertEquals("req-9", res.requestId)
        assertEquals("test", res.mode)
        assertTrue(res.isTestMode)
        assertTrue(res.replayed)
        assertEquals(5.0, res.data.balance)
        assertNull(res.meta)

        rig.server.enqueue(Answer(200, Fixtures.ACTION))
        val live = rig.rewloy.passActionWithResponse("S", PassActionBody("visit", "l"))
        assertNull(live.mode)
        assertFalse(live.isTestMode)
        assertFalse(live.replayed)
    }

    @Test
    fun `a 204 has no data`() = Rig { staffSession("rws_abc") }.test { rig ->
        rig.server.enqueue(Answer(204))
        rig.rewloy.logout()
        val res = rig.rewloy.logoutWithResponse()
        assertEquals(204, res.statusCode)
        assertEquals("POST", rig.server.received[0].method)
        assertEquals("0", rig.server.received[0].header("content-length"))
    }

    @Test
    fun `a file comes with its type and name`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, headers = mapOf("Content-Type" to "image/png", "Content-Disposition" to "attachment; filename=\"map.png\""), rawBytes = byteArrayOf(1, 2, 3)))
        val file = rig.rewloy.locationMap("loc-1")
        assertEquals("image/png", file.contentType)
        assertEquals("map.png", file.fileName)
        assertEquals(listOf<Byte>(1, 2, 3), file.content.toList())
    }

    @Test
    fun `the OpenAPI document comes as JSON without the envelope`() = Rig().test { rig ->
        rig.server.enqueue(Answer(200, """{"openapi":"3.1.0"}"""))
        assertEquals("3.1.0", rig.rewloy.openapi().asObject()!!["openapi"]!!.asString())
    }

    @Test
    fun `reads a gzip body`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, headers = mapOf("Content-Encoding" to "gzip"), rawBytes = gzip(Fixtures.PASS)))
        assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
    }

    @Test
    fun `keeps the fields the API added`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS.replace("\"status\"", "\"brandNew\":{\"x\":[1]},\"status\"")))
        val pass = rig.rewloy.getPass("S")
        assertEquals("""{"x":[1]}""", pass.additionalProperties["brandNew"].toString())
        assertEquals(1, pass.additionalProperties.size)
    }

    @Test
    fun `a request can send a field the library does not know yet`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.ACTION))
        val body = PassActionBody("visit", "l")
        body.setAdditionalProperty("channel", JsonValue.of("qr"))
        rig.rewloy.passAction("S", body)
        assertEquals("""{"action":"visit","locationId":"l","channel":"qr"}""", rig.server.received.single().body)
    }

    @Test
    fun `an answer that lacks a required field is INVALID_RESPONSE with the body kept`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS.replace("\"serial\":\"ABCD-EFGH-JKLM\",", ""), mapOf("x-request-id" to "req-3")))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals("INVALID_RESPONSE", e.code)
        assertEquals(200, e.status)
        assertEquals("req-3", e.requestId)
        assertTrue(e.detail.contains("\$.data.serial"), e.detail)
        assertTrue(e.body!!.contains("programId"))
    }

    @Test
    fun `an answer of the wrong type is INVALID_RESPONSE naming the path`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS.replace("\"rewardsReady\":0", "\"rewardsReady\":\"none\"")))
        val e = assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        assertEquals("INVALID_RESPONSE", e.code)
        assertTrue(e.detail.contains("\$.data.rewardsReady") && e.detail.contains("integer"), e.detail)
    }

    @Test
    fun `an answer that is not JSON, or has no data, is INVALID_RESPONSE`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, "<html>hi</html>", mapOf("Content-Type" to "text/html")))
        assertEquals("INVALID_RESPONSE", assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }.code)
        rig.server.enqueue(Answer(200, """{"nope":1}"""))
        assertEquals("INVALID_RESPONSE", assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }.code)
    }

    @Test
    fun `a body that is all optional is sent as an empty object when left out`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.RESTORED))
        val result = rig.rewloy.restoreProgram("prog-1")
        assertEquals("active", result.status)
        assertEquals("{}", rig.server.received.single().body)
    }

    @Test
    fun `PATCH goes out as PATCH where the JVM allows it`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, """{"data":{}}"""))
        val javaMajor = System.getProperty("java.specification.version").removePrefix("1.").toInt()
        if (javaMajor >= 12) {
            // The JDK's HttpURLConnection refuses PATCH and the workaround is closed: say so, do not send another verb.
            val e = assertFailsWith<UnsupportedOperationException> { rig.rewloy.updateLocation("loc", UpdateLocationBody()) }
            assertTrue(e.message!!.contains("OkHttp"), e.message)
            assertTrue(rig.server.received.isEmpty())
        } else {
            assertFailsWith<RewloyException> { rig.rewloy.updateLocation("loc", UpdateLocationBody()) }
            assertEquals("PATCH", rig.server.received.single().method)
        }
    }

    @Test
    fun `refuses a client that is built wrongly, without echoing the credential`() {
        val e1 = assertFailsWith<IllegalArgumentException> { Rewloy { apiKey("rws_secretvalue") } }
        assertFalse(e1.message!!.contains("secretvalue"))
        assertTrue(e1.message!!.contains("rwk_"))
        assertFailsWith<IllegalArgumentException> { Rewloy { staffSession("rwk_x") } }
        assertFailsWith<IllegalArgumentException> { Rewloy { holderSession("rws_x") } }
        assertFailsWith<IllegalArgumentException> { Rewloy { apiKey("rwk_x"); staffSession("rws_x") } }
        assertFailsWith<IllegalArgumentException> { Rewloy { apiKey("rwk_x"); merchant("m") } }
        assertFailsWith<IllegalArgumentException> { Rewloy { baseUrl("ftp://x") } }
        assertFailsWith<IllegalArgumentException> { Rewloy { maxRetries(-1) } }
        Rewloy { holderSession("rwh_x") }
        Rewloy()
    }

    @Test
    fun `describes itself without the credential`() {
        val rewloy = Rewloy { apiKey("rwk_topsecret"); baseUrl("http://localhost:1/") }
        assertEquals("Rewloy(http://localhost:1, key)", rewloy.toString())
        assertEquals("http://localhost:1", rewloy.baseUrl)
        assertEquals(CredentialKind.KEY, rewloy.credential)
        assertEquals(2, rewloy.maxRetries)
        assertEquals(60_000, rewloy.timeoutMs)
    }

    @Test
    fun `adds the caller's suffix to the user agent and extra headers to the request`() = Rig { apiKey("rwk_abc"); userAgent("KasaPOS/4.2") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS))
        rig.rewloy.getPass("S", RequestOptions(headers = mapOf("X-Till" to "3", "accept" to "application/json; v=1")))
        val r = rig.server.received.single()
        assertTrue(r.header("user-agent")!!.endsWith(" KasaPOS/4.2"), r.header("user-agent"))
        assertEquals("3", r.header("x-till"))
        assertEquals("application/json; v=1", r.header("accept"))
    }

    @Test
    fun `knows what it needs of every operation`() {
        val op = assertNotNull(RewloyOperations.all["passAction"])
        assertEquals(IdempotencyMode.REQUIRED, op.idempotency)
        assertEquals("/v1/passes/{serial}/actions", op.path)
        assertEquals(setOf(CredentialKind.KEY, CredentialKind.STAFF), op.credentials)
        assertTrue(RewloyOperations.all["listCustomers"]!!.isPaged)
        assertTrue(RewloyOperations.all["liveFeed"]!!.isStream)
        assumeTrue(true)
    }
}
