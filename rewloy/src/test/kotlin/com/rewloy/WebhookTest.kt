package com.rewloy

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vectors are made the way the platform signs a delivery (its src/modules/webhooks/service.ts, `sign`),
 * written out again here, not imported:
 * `t=${t},v1=${createHmac('sha256', secret).update(`${t}.${body}`).digest('hex')}`
 * with `body = JSON.stringify(payload)` and the secret `whsec_…` as the key. They are the Node, PHP and .NET
 * libraries' two fixed vectors, so that all four are pinned to the same bytes.
 */
class WebhookTest {
    private val secret = "whsec_dGVzdC1zZWNyZXQtZm9yLXJld2xveS1ub2RlLXRlc3Rz"
    private val t = 1790000000L
    private val body = """{"id":"0192f7c1-8b2e-7a31-9c1d-2e4f5a6b7c8d","type":"pass.activity","created_at":"2026-10-03T12:00:00.000Z","data":{"kind":"earn","card":"ABCD-EFGH-JKLM","program_id":"0192f7c1-0000-7000-8000-000000000002","location_id":"0192f7c1-0000-7000-8000-000000000003","customer_id":"0192f7c1-0000-7000-8000-000000000004","unit":"stamp","delta":2}}"""
    private val fixed = "t=1790000000,v1=b17b337b887316b1e0e19c3f16bdc4936c03e93d16fbec3da121aacc0ec1eda7"
    private val testBody = """{"type":"webhook.test","created_at":"2026-10-03T12:00:00.000Z","data":{"message":"Rewloy webhook testi — ğüşıöç"}}"""
    private val testFixed = "t=1790000000,v1=b06a92a7fb131aed835f2564fdf06a93461e0edb9216b4464598812f47704b1e"

    /** The platform's `sign`, in Kotlin. */
    private fun serverSign(secret: String, body: String, t: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal("$t.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$t,v1=$hex"
    }

    private fun refused(reason: WebhookFailure, payload: String, header: String?, secret: String = this.secret, now: Long = t, tolerance: Long = 300) {
        val e = assertFailsWith<WebhookSignatureException> { Webhook.verify(payload, header, secret, tolerance, now) }
        assertEquals(reason, e.reason)
    }

    @Test
    fun `accepts what the platform signs, as text or bytes`() {
        assertEquals(fixed, serverSign(secret, body, t))
        assertEquals(testFixed, serverSign(secret, testBody, t))
        for (ev in listOf(Webhook.verify(body, fixed, secret, nowSeconds = t + 10), Webhook.verify(body.toByteArray(), fixed, secret, nowSeconds = t + 10))) {
            assertEquals("pass.activity", ev.type)
            assertEquals("0192f7c1-8b2e-7a31-9c1d-2e4f5a6b7c8d", ev.id)
            assertEquals("2026-10-03T12:00:00.000Z", ev.createdAt)
            val d = ev.passData!!
            assertEquals("earn", d.kind)
            assertEquals("ABCD-EFGH-JKLM", d.card)
            assertEquals("0192f7c1-0000-7000-8000-000000000004", d.customerId)
            assertEquals("stamp", d.unit)
            assertEquals(2.0, d.delta)
            assertEquals(2, ev.data.asObject()!!["delta"]!!.asInt())
        }
        val test = Webhook.verify(testBody.toByteArray(), testFixed, secret, nowSeconds = t)
        assertEquals("webhook.test", test.type)
        assertNull(test.id)
        assertNull(test.passData)
        assertEquals("Rewloy webhook testi — ğüşıöç", test.data.asObject()!!["message"]!!.asString())
    }

    @Test
    fun `accepts other entries around v1`() {
        assertEquals("pass.activity", Webhook.verify(body, " v0=abc, " + fixed.replace(",", " , ") + " ", secret, nowSeconds = t).type)
    }

    @Test
    fun `accepts any of several v1 signatures and any of several secrets`() {
        val other = serverSign("whsec_other", body, t).split(',')[1]
        assertEquals("pass.activity", Webhook.verify(body, "$fixed,$other", secret, nowSeconds = t).type)
        assertEquals("pass.activity", Webhook.verify(body, "t=$t,$other,${fixed.split(',')[1]}", secret, nowSeconds = t).type)
        assertEquals("pass.activity", Webhook.verify(body, fixed, listOf("whsec_new", secret), nowSeconds = t).type)
        assertEquals("pass.activity", Webhook.verify(body.toByteArray(), fixed, listOf("whsec_new", secret), nowSeconds = t).type)
    }

    @Test
    fun `refuses a changed body, the wrong secret and a v1 for another time`() {
        refused(WebhookFailure.MISMATCH, body.replace("\"delta\":2", "\"delta\":20"), fixed)
        refused(WebhookFailure.MISMATCH, body + "\n", fixed)
        refused(WebhookFailure.MISMATCH, body, fixed, "whsec_wrong")
        refused(WebhookFailure.MISMATCH, body, fixed.replace("t=1790000000", "t=1790000001"))
        // The prefix is part of the key.
        refused(WebhookFailure.MISMATCH, body, fixed, secret.removePrefix("whsec_"))
    }

    @Test
    fun `refuses a time outside the tolerance either way`() {
        assertEquals("pass.activity", Webhook.verify(body, fixed, secret, nowSeconds = t + 300).type)
        assertEquals("pass.activity", Webhook.verify(body, fixed, secret, nowSeconds = t - 300).type)
        refused(WebhookFailure.EXPIRED, body, fixed, now = t + 301)
        refused(WebhookFailure.EXPIRED, body, fixed, now = t - 301)
        assertEquals("pass.activity", Webhook.verify(body, fixed, secret, 3600, t + 3600).type)
        // Real time: a 2026 signature is long expired.
        val e = assertFailsWith<WebhookSignatureException> { Webhook.verify(body, "t=1000,v1=" + "a".repeat(64), secret) }
        assertEquals(WebhookFailure.EXPIRED, e.reason)
    }

    @Test
    fun `refuses a missing or malformed header`() {
        for (h in listOf(null, "", "  ")) refused(WebhookFailure.MISSING, body, h)
        for (h in listOf("v1=" + "a".repeat(64), "t=1790000000", "t=abc,v1=" + "a".repeat(64), "t=1790000000,v1=xyz", "t=1790000000,v1=" + "a".repeat(63),
            "t=99999999999999999999999,v1=" + "a".repeat(64), "t=-5,v1=" + "a".repeat(64), "garbage", "t=1790000000,v1=" + "a".repeat(66))) {
            refused(WebhookFailure.MALFORMED, body, h)
        }
    }

    @Test
    fun `refuses a signed body that is not a JSON object, and an empty secret`() {
        refused(WebhookFailure.PAYLOAD, "not json", serverSign(secret, "not json", t))
        refused(WebhookFailure.PAYLOAD, "[1,2]", serverSign(secret, "[1,2]", t))
        refused(WebhookFailure.PAYLOAD, "\"text\"", serverSign(secret, "\"text\"", t))
        assertFailsWith<IllegalArgumentException> { Webhook.verify(body, fixed, "", nowSeconds = t) }
        assertFailsWith<IllegalArgumentException> { Webhook.verify(body, fixed, emptyList<String>(), nowSeconds = t) }
    }

    @Test
    fun `signs as the platform does, for testing your own handler`() {
        assertEquals(fixed, Webhook.sign(body, secret, t))
        assertEquals(testFixed, Webhook.sign(testBody.toByteArray(), secret, t))
        val header = Webhook.sign(body, secret)
        assertEquals("pass.activity", Webhook.verify(body, header, secret).type)
        assertTrue(header.startsWith("t="))
    }
}
