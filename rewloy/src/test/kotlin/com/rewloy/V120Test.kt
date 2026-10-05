package com.rewloy

import com.rewloy.models.CreateApiKeyBody
import com.rewloy.models.CreateApiKeyBodyPos
import com.rewloy.models.CreateBatchBody
import com.rewloy.models.ListAllBatchesQuery
import com.rewloy.models.ListPassOperationsQuery
import com.rewloy.models.RecordSaleBody
import com.rewloy.models.ResetTestEnvironmentBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The operations and fields Rewloy API 1.2.0 added. */
class V120Test {
    // Answers with every required field, as the API 1.2.0 document lists them.
    private val operation = """{"id":"0192f7c1-0000-7000-8000-0000000000aa","kind":"earn","delta":1,"unit":"stamp","currency":null,"at":"2026-10-06T10:00:00.000Z","occurredAt":null,"locationId":"0192f7c1-0000-7000-8000-0000000000aa","location":"Şube","reference":null,"source":"api","byCaller":true,"saleKey":"kasa3-z0187-fis0042","undoWith":"sale/reverse","reversible":true,"reversedBy":null,"reversedAt":null,"reverses":null}"""
    private val batch = """{"id":"0192f7c1-0000-7000-8000-0000000000aa","code":"HEDIYE","claimUrl":"https://rewloy.com/k/x","name":"Bahar","status":"open","state":"archived","registered":1,"active":1,"redeemed":0,"createdAt":"2026-10-06T10:00:00.000Z","closedAt":null,"validUntil":null,"programId":"0192f7c1-0000-7000-8000-0000000000aa","programName":"Kahve","type":"giftcard","currency":"TRY","capacity":100,"perPerson":1,"valueMinor":5000,"offerText":null,"percent":null,"usage":"once","usageLimit":null,"outstandingMinor":5000}"""
    private val rotated = """{"data":{"webhook":{"id":"0192f7c1-0000-7000-8000-0000000000aa","url":"https://ornek.com/h","events":[],"status":"active","failures":0,"disabledReason":null,"createdAt":"2026-10-06T10:00:00.000Z","week":{"delivered":0,"failed":0,"pending":0},"lastDelivered":null,"createdByKey":null},"secret":"whsec_new","previousValidUntil":"2026-10-06T10:00:00.000Z"}}"""
    private val reset = """{"data":{"merchantId":"0192f7c1-0000-7000-8000-0000000000aa","name":"Kahve · Test","closed":null,"created":false,"keysRevoked":true,"deleted":{"customers":2,"cards":3,"codes":0,"outbox":1,"webhookDeliveries":4},"kept":{"programs":1,"keys":0,"webhooks":0},"walletCardsVoided":0}}"""
    private val created = """{"data":{"key":{"id":"0192f7c1-0000-7000-8000-0000000000aa","name":"POS","prefix":"abc","status":"active","role":"Kasa","scopeText":"Şube","tier":"standard","ipAllowlist":[],"createdAt":"2026-10-06T10:00:00.000Z","expiresAt":null,"expired":false,"lastUsedAt":null,"stale":false,"actions30":0,"refused30":0,"shopId":null,"pos":{"locationId":"0192f7c1-0000-7000-8000-0000000000aa","register":"Kasa 1"},"requestsToday":0},"token":"rwk_x_y","baseUrl":"https://app.rewloy.com"}}"""

    private val serial = "ABCD-EFGH-JKLM"
    private val webhook = "0192f7c1-0000-7000-8000-0000000000aa"
    private val location = "0192f7c1-0000-7000-8000-000000000003"

    @Test
    fun `knows the new operations`() {
        for (id in listOf("listPassOperations", "listAllBatches", "rotateWebhookSecret", "deleteWebhook")) assertTrue(id in RewloyOperations.all, id)
        assertTrue(RewloyOperations.listPassOperations.isPaged)
        assertTrue(RewloyOperations.listAllBatches.isPaged)
    }

    @Test
    fun `lists a card's operations and pages them`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val item = operation
        rig.server.enqueue(
            Answer(200, """{"data":[$item],"meta":{"page":1,"pageSize":10,"total":1}}"""),
            Answer(200, """{"data":[$item],"meta":{"page":1,"pageSize":50,"total":1}}"""),
        )
        val page = rig.rewloy.listPassOperations(serial, ListPassOperationsQuery(limit = 10))
        val op = page.data.single()
        assertEquals("sale/reverse", op.undoWith)
        assertTrue(op.reversible)
        assertEquals("kasa3-z0187-fis0042", op.saleKey)
        assertEquals("/v1/passes/$serial/operations", rig.server.received[0].path)
        assertTrue("limit=10" in rig.server.received[0].query)
        assertEquals(listOf("0192f7c1-0000-7000-8000-0000000000aa"), rig.rewloy.listPassOperationsAll(serial).map { it.id })
    }

    @Test
    fun `lists every batch with the archived state`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, """{"data":[$batch],"meta":{"page":1,"pageSize":50,"total":1}}"""))
        val page = rig.rewloy.listAllBatches(ListAllBatchesQuery(status = "archived"))
        assertEquals("archived", page.data.single().state)
        assertEquals("/v1/batches?status=archived", rig.server.received.single().target)
    }

    @Test
    fun `rotates and deletes a webhook`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, rotated), Answer(204, ""))
        assertEquals("whsec_new", rig.rewloy.rotateWebhookSecret(webhook).secret)
        assertEquals("POST", rig.server.received[0].method)
        assertEquals("/v1/developers/webhooks/$webhook/rotate-secret", rig.server.received[0].path)
        rig.rewloy.deleteWebhook(webhook)
        assertEquals("DELETE", rig.server.received[1].method)
    }

    @Test
    fun `creates a standard key and a pos key through one operation, and resets the test environment with revokeKeys`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(201, created), Answer(201, created), Answer(200, reset))
        // The first shape keeps its class, so code written before the union compiles unchanged.
        rig.rewloy.createApiKey(CreateApiKeyBody(name = "Kasa", roleId = "r", password = "x"))
        val pos = rig.rewloy.createApiKey(CreateApiKeyBodyPos(kind = "pos", locationId = location, password = "x", register = "Kasa 1"))
        assertEquals("https://app.rewloy.com", pos.baseUrl)
        val body = rig.server.received[1].body
        assertTrue(""""kind":"pos"""" in body && """"register":"Kasa 1"""" in body && "roleId" !in body, body)
        assertTrue(""""name":"Kasa"""" in rig.server.received[0].body)

        val reset = rig.rewloy.resetTestEnvironment(ResetTestEnvironmentBody(revokeKeys = true))
        assertTrue(reset.keysRevoked)
        assertEquals("""{"revokeKeys":true}""", rig.server.received[2].body)
    }

    @Test
    fun `reads card and reversed on a replayed sale, and card may be null`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, """{"data":{"type":"stamp","applied":"stamps","credited":1,"balance":3,"duplicate":true,"reversed":true,"rewardReady":false,"rewardsReady":0,"card":null}}"""))
        val sale = rig.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 100, locationId = location), RequestOptions(idempotencyKey = "kasa3-z0187-fis0042"))
        assertTrue(sale.reversed)
        assertNull(sale.card)
    }

    @Test
    fun `surfaces PROGRAM_ARCHIVED`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(409, """{"error":{"code":"PROGRAM_ARCHIVED","message":"Program arşivde","requestId":"req-1","status":409}}"""))
        val e = assertFailsWith<RewloyException> { rig.rewloy.createBatch("p1", CreateBatchBody()) }
        assertEquals(409, e.status)
        assertEquals(ErrorCode.PROGRAM_ARCHIVED, e.code)
    }

    /** The README's 1.2.0 snippets: compiled, not run. */
    @Suppress("unused")
    private fun compileOnly(rewloy: Rewloy, seri: String, webhookId: String, hamGovde: String, imzaBasligi: String, eskiSir: String) {
        val kart = rewloy.getPass(seri)
        val odul = kart.actions.any { (it.action == "redeem-stamps" || it.action == "redeem-reward") && it.ready }
        println("${kart.type} ${kart.balance} $odul")

        val satis = rewloy.recordSale(seri, RecordSaleBody(amountMinor = 100), RequestOptions(idempotencyKey = "kasa3-z0187-fis0042"))
        if (satis.card?.actions?.any { (it.action == "redeem-stamps" || it.action == "redeem-reward") && it.ready } == true) println("Ödül hazır")

        for (islem in rewloy.listPassOperationsAll(seri)) {
            if (!islem.reversible) continue
            if (islem.undoWith == "sale/reverse") rewloy.reverseSale(seri, com.rewloy.models.ReverseSaleBody(saleKey = islem.saleKey))
            else rewloy.reverseAction(seri, com.rewloy.models.ReverseActionBody(actionKey = islem.actionKey))
        }

        val yeni = rewloy.rotateWebhookSecret(webhookId).secret
        val olay = Webhook.verify(hamGovde, imzaBasligi, listOf(yeni, eskiSir))
        println(olay.type)
        rewloy.createApiKey(CreateApiKeyBodyPos(kind = "pos", locationId = webhookId, password = "x", register = "Kasa 1"))
    }
}
