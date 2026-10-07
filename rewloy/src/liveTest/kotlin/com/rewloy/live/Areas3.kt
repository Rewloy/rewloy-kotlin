package com.rewloy.live

import com.rewloy.RequestOptions
import com.rewloy.Rewloy
import com.rewloy.RewloyException
import com.rewloy.models.ArchiveProgramBody
import com.rewloy.models.ClaimCodeBody
import com.rewloy.models.CreateProgramBody
import com.rewloy.models.CreateWebhookBody
import com.rewloy.models.IssuePassBody
import com.rewloy.models.ListAllBatchesQuery
import com.rewloy.models.ListCustomersQuery
import com.rewloy.models.RecordSaleBody
import com.rewloy.models.SendBatchLinkBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(6)
@DisplayName("customers")
class CustomersLiveTest {
    @Test
    @Order(1)
    fun `search finds a customer by e-mail address`() {
        Live.stampCard
        val hits = Live.rewloy.listCustomers(ListCustomersQuery().apply { q = Live.stampEmail })
        assertEquals(1L, hits.meta.total)
        val c = hits.data.single()
        assertTrue(c.identifiers.any { it.kind == "email" && it.value == Live.stampEmail }, "identifiers ${c.identifiers.size}")
        assertTrue(c.passCount >= 1)
        assertFalse(c.blocked)
    }

    @Test
    @Order(2)
    fun `a customer is read by id, and an unknown search finds nobody`() {
        Live.stampCard
        val c = Live.rewloy.listCustomers(ListCustomersQuery().apply { q = Live.stampEmail }).data.single()
        val one = Live.rewloy.getCustomer(c.personId)
        assertEquals(c.personId, one.personId)
        assertEquals(c.displayName, one.displayName)
        val none = Live.rewloy.listCustomers(ListCustomersQuery().apply { q = "nobody-" + Live.runId + "@example.com" })
        assertEquals(0L, none.meta.total)
        assertTrue(none.data.isEmpty())
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(7)
@DisplayName("batches (codes)")
class BatchesLiveTest {
    @Test
    @Order(1)
    fun `create a gift card code and list it`() {
        val b = Live.newBatch()
        assertEquals("open", b.status)
        assertEquals(3, b.capacity)
        assertEquals(2500L, b.valueMinor)
        assertEquals(Live.giftProgramId, b.programId)
        assertTrue(b.claimUrl.contains(b.code), b.claimUrl)
        assertTrue(Live.rewloy.listBatches(Live.giftProgramId).any { it.id == b.id })
        assertEquals(b.id, Live.rewloy.getBatch(b.id).id)
    }

    @Test
    @Order(2)
    fun `listAllBatches filters and pages`() {
        val a = Live.newBatch()
        val b = Live.newBatch()
        val open = Live.rewloy.listAllBatches(ListAllBatchesQuery().apply { status = "open"; programId = Live.giftProgramId })
        assertTrue(open.data.any { it.id == a.id } && open.data.any { it.id == b.id })
        assertTrue(open.data.all { it.state == "open" && it.programId == Live.giftProgramId })
        val paged = Live.rewloy.listAllBatches(ListAllBatchesQuery().apply { programId = Live.giftProgramId; limit = 1 })
        assertEquals(1, paged.data.size)
        assertTrue(paged.meta.total >= 2)
        val everything = Live.rewloy.listAllBatchesAll(ListAllBatchesQuery().apply { programId = Live.giftProgramId; limit = 1 }).toList()
        assertEquals(paged.meta.total, everything.size.toLong())
        assertEquals(everything.size, everything.map { it.id }.toSet().size)
    }

    @Test
    @Order(3)
    fun `a code is claimed and counted`() {
        val b = Live.newBatch()
        val card = Live.rewloy.claimCode(b.code, ClaimCodeBody(Live.email("claim"), true))
        assertEquals(2500L, Live.rewloy.getPass(card.serial).money!!.amountMinor)
        assertEquals(1, Live.rewloy.listBatchCardsAll(b.id).count())
        assertEquals(1, Live.rewloy.listAllBatches(ListAllBatchesQuery().apply { q = b.code }).data.single().registered)
    }

    @Test
    @Order(4)
    fun `sendBatchLink answers for an open code`() {
        val b = Live.newBatch()
        val sent = Live.rewloy.sendBatchLink(b.id, SendBatchLinkBody(Live.email("send")))
        assertTrue(sent.result.isNotBlank())
    }

    @Test
    @Order(5)
    fun `sendBatchLink refuses a closed code with BATCH_CLOSED`() {
        val b = Live.newBatch()
        Live.rewloy.closeBatch(b.id)
        val e = expectError(410, "BATCH_CLOSED") { Live.rewloy.sendBatchLink(b.id, SendBatchLinkBody(Live.email("closed"))) }
        e.assertIsApiError()
        assertEquals("closed", Live.rewloy.getBatch(b.id).status)
    }

    @Test
    @Order(6)
    fun `sendBatchLink refuses a full code with BATCH_FULL`() {
        val b = Live.newBatch { capacity = 1 }
        Live.rewloy.claimCode(b.code, ClaimCodeBody(Live.email("full"), true))
        expectError(410, "BATCH_FULL") { Live.rewloy.sendBatchLink(b.id, SendBatchLinkBody(Live.email("full-send"))) }
    }

    @Test
    @Order(7)
    fun `sendBatchLink refuses an expired code with BATCH_EXPIRED`() {
        val b = try {
            Live.newBatch { validUntil = Instant.now().plusSeconds(2).toString() }
        } catch (e: RewloyException) {
            // A server that does not take a moment this close is documented to say so: VALIDATION, nothing created.
            assertEquals("VALIDATION", e.code)
            return
        }
        Thread.sleep(3500)
        expectError(410, "BATCH_EXPIRED") { Live.rewloy.sendBatchLink(b.id, SendBatchLinkBody(Live.email("expired"))) }
    }

    @Test
    @Order(8)
    fun `a code of an archived program sends nothing, and no new code is made for it`() {
        val programId = Live.rewloy.createProgram(CreateProgramBody("giftcard", "Kotlin Live Cafe").apply { programName = Live.name("gift-to-archive") }).id
        val b = Live.newBatch(programId)
        Live.rewloy.archiveProgram(programId, ArchiveProgramBody().apply { cancelCards = false })
        // 1.2.0 documents PROGRAM_ARCHIVED (409) for the send. Archiving closes the program's open codes, though, and a
        // closed code is answered first (BATCH_CLOSED, 410): through the API alone only that one can be seen. Either
        // way the e-mail does not go.
        val refused = try {
            Live.rewloy.sendBatchLink(b.id, SendBatchLinkBody(Live.email("archived")))
            null
        } catch (e: RewloyException) {
            e
        }
        assertNotNull(refused, "the link of an archived program's code must be refused")
        assertTrue(refused.status == 409 && refused.code == "PROGRAM_ARCHIVED" || refused.status == 410 && refused.code == "BATCH_CLOSED", "${refused.status} ${refused.code}")
        refused.assertIsApiError()
        // The row is no longer open (its state is "archived" where the code was left open, "closed" as archiving closes it),
        // and a new code for the program is refused outright.
        assertTrue(Live.rewloy.listAllBatches(ListAllBatchesQuery().apply { q = b.code }).data.single().state in setOf("archived", "closed"))
        expectError(409, "PROGRAM_ARCHIVED") { Live.newBatch(programId) }
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(8)
@DisplayName("webhooks")
class WebhooksLiveTest {
    // A public https address that resolves: the rule is the same in a test and a live business, so a refusal is a failure.
    private val unresolvable = "https://example.com/rewloy-live-tests/kt-${Live.runId}"

    @Test
    @Order(1)
    fun `the event catalogue is listed`() {
        val events = Live.rewloy.webhookEvents()
        assertTrue(events.events.isNotEmpty())
        assertTrue(events.events.all { it.event.isNotBlank() })
    }

    @Test
    @Order(2)
    fun `create, list, rotate the secret and delete a webhook`() {
        val event = Live.rewloy.webhookEvents().events.first().event
        val created = Live.rewloy.createWebhook(CreateWebhookBody(unresolvable, listOf(event)))
        val id = created.webhook.id
        Live.onCleanup("delete webhook $id") { Live.rewloy.deleteWebhook(id) }
        assertTrue(created.secret.startsWith("whsec_"), "secret")
        assertEquals(unresolvable, created.webhook.url)
        assertEquals(listOf(event), created.webhook.events)
        assertEquals("active", created.webhook.status)

        val listed = Live.rewloy.listWebhooks().single { it.id == id }
        assertEquals(unresolvable, listed.url)
        assertEquals(id, Live.rewloy.getWebhook(id).id)

        val rotated = Live.rewloy.rotateWebhookSecret(id)
        assertTrue(rotated.secret.startsWith("whsec_"))
        assertNotEquals(created.secret, rotated.secret)
        assertTrue(rotated.previousValidUntil.isNotBlank())
        assertEquals(id, rotated.webhook.id)

        Live.rewloy.deleteWebhook(id)
        assertFalse(Live.rewloy.listWebhooks().any { it.id == id })
        expectError(404, "WEBHOOK_NOT_FOUND") { Live.rewloy.getWebhook(id) }
    }

    @Test
    @Order(3)
    fun `an internal address is refused on a live-like server, or created with warnings on a dev one`() {
        val event = Live.rewloy.webhookEvents().events.first().event
        val created = try {
            Live.rewloy.createWebhook(CreateWebhookBody("http://127.0.0.1:9/hook", listOf(event)))
        } catch (e: RewloyException) {
            assertEquals(422, e.status)
            assertEquals("BAD_WEBHOOK_URL", e.code)
            return
        }
        Live.onCleanup("delete webhook ${created.webhook.id}") { Live.rewloy.deleteWebhook(created.webhook.id) }
        // A dev server takes it and says why the live one would not.
        assertTrue(!created.warnings.isNullOrEmpty(), "a dev server's warnings")
        Live.rewloy.deleteWebhook(created.webhook.id)
    }

    @Test
    @Order(4)
    fun `a webhook with no events is a validation error`() {
        expectError(400, "VALIDATION") { Live.rewloy.createWebhook(CreateWebhookBody(unresolvable, emptyList())) }
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(9)
@DisplayName("idempotency")
class IdempotencyLiveTest {
    @Test
    @Order(1)
    fun `the same key replays the same answer, card included`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("idem-sale")
        val body = { RecordSaleBody(amountMinor = 4000, locationId = Live.locationId, reference = "fis-idem") }
        val first = Live.rewloy.recordSaleWithResponse(serial, body(), RequestOptions(idempotencyKey = key))
        assertFalse(first.data.duplicate)
        // A till write is answered from the ledger: `duplicate`, and the card as it is now (not the Idempotent-Replayed header).
        val second = Live.rewloy.recordSaleWithResponse(serial, body(), RequestOptions(idempotencyKey = key))
        assertTrue(second.data.duplicate)
        assertEquals(first.data.credited, second.data.credited)
        assertEquals(first.data.applied, second.data.applied)
        assertNotNull(second.data.card)
        assertEquals(serial, second.data.card!!.serial)
        assertEquals(1, Live.rewloy.getPass(serial).stamps!!.count, "written once")
    }

    @Test
    @Order(2)
    fun `the same key with another body is refused with IDEMPOTENCY_KEY_REUSED`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("idem-reuse")
        Live.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 4000, locationId = Live.locationId), RequestOptions(idempotencyKey = key))
        expectError(422, "IDEMPOTENCY_KEY_REUSED") {
            Live.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 9000, locationId = Live.locationId), RequestOptions(idempotencyKey = key))
        }
    }

    @Test
    @Order(3)
    fun `issuing a card with a key twice gives one card`() {
        val key = Live.key("idem-issue")
        val address = Live.email("idem-issue")
        val body = { IssuePassBody(Live.stampProgramId).apply { email = address; kvkkConsent = true } }
        val a = Live.rewloy.issuePassWithResponse(body(), RequestOptions(idempotencyKey = key))
        val b = Live.rewloy.issuePassWithResponse(body(), RequestOptions(idempotencyKey = key))
        assertEquals(a.data.serial, b.data.serial)
        assertEquals(a.data.cardUrl, b.data.cardUrl)
        assertFalse(a.replayed)
        assertTrue(b.replayed, "Idempotent-Replayed: the stored answer of the first call")
    }

    @Test
    @Order(4)
    fun `a till action replays too`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("idem-action")
        val action = { com.rewloy.models.PassActionBody("earn-stamps", Live.locationId).apply { count = 2 } }
        val a = Live.rewloy.passActionWithResponse(serial, action(), RequestOptions(idempotencyKey = key))
        val b = Live.rewloy.passActionWithResponse(serial, action(), RequestOptions(idempotencyKey = key))
        assertFalse(a.data.duplicate)
        assertTrue(b.data.duplicate)
        assertNotNull((b.data as com.rewloy.models.PassActionDataOption1).card)
        assertEquals(2, Live.rewloy.getPass(serial).stamps!!.count)
        assertEquals(a.requestId == b.requestId, false)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(10)
@DisplayName("rate limit headers")
class RateLimitLiveTest {
    @Test
    @Order(1)
    fun `the limit headers are read from a keyed answer`() {
        val a = Live.rewloy.getBusinessWithResponse().rateLimit
        assertNotNull(a, "RateLimit-* headers")
        assertTrue(a.limit > 0)
        assertTrue(a.remaining in 0..a.limit)
        assertTrue(a.resetSeconds in 0..120, "resetSeconds ${a.resetSeconds}")
        val b = Live.rewloy.getBusinessWithResponse().rateLimit!!
        assertEquals(a.limit, b.limit)
        assertTrue(b.remaining <= a.remaining || b.resetSeconds >= a.resetSeconds, "the budget does not grow inside a window")
    }

    @Test
    @Order(2)
    fun `an error carries the limit headers as well`() {
        val e = expectError(404, "PASS_NOT_FOUND") { Live.rewloy.getPass("ZZZZ-ZZZZ-ZZZZ") }
        assertNotNull(e.rateLimit)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(11)
@DisplayName("errors")
class ErrorsLiveTest {
    @Test
    @Order(1)
    fun `a missing card is a 404 with code, status and request id`() {
        val e = expectError(404, "PASS_NOT_FOUND") { Live.rewloy.getPass("ZZZZ-ZZZZ-ZZZZ") }
        e.assertIsApiError()
        assertEquals("getPass", e.operation)
        assertTrue(e.docs!!.endsWith("#PASS_NOT_FOUND"), e.docs)
    }

    @Test
    @Order(2)
    fun `a missing program is a 404`() {
        expectError(404, "PROGRAM_NOT_FOUND") { Live.rewloy.getProgram("00000000-0000-7000-8000-000000000000") }.assertIsApiError()
    }

    @Test
    @Order(3)
    fun `a bad body is a 400 VALIDATION naming the field`() {
        val e = expectError(400, "VALIDATION") {
            Live.rewloy.createProgram(CreateProgramBody("stamp", "Kotlin Live Cafe").apply { maxStamps = 3 })
        }
        e.assertIsApiError()
        val first = e.details!!.asArray()!![0]!!.asObject()!!
        assertEquals("maxStamps", first["field"]!!.asString())
    }

    @Test
    @Order(4)
    fun `a card without consent is refused with CONSENT_REQUIRED`() {
        expectError(422, "CONSENT_REQUIRED") {
            Live.rewloy.issuePass(IssuePassBody(Live.stampProgramId).apply { email = Live.email("noconsent") })
        }.assertIsApiError()
    }

    @Test
    @Order(5)
    fun `a wrong key is a 401 and not retried into something else`() {
        val wrong = Rewloy { baseUrl(Live.baseUrl!!); apiKey(Live.TEST_KEY_PREFIX + "0".repeat(40)) }
        expectError(401, "INVALID_API_KEY") { wrong.getBusiness() }.assertIsApiError()
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(12)
@DisplayName("pagination")
class PaginationLiveTest {
    @Test
    @Order(1)
    fun `a list is read page by page and in one go`() {
        repeat(3) { Live.newCard(Live.stampProgramId, Live.email("page")) }
        val search = { ListCustomersQuery().apply { q = "live-kt-" + Live.runId + "-page"; limit = 2 } }
        val p1 = Live.rewloy.listCustomers(search())
        assertEquals(2, p1.data.size)
        assertEquals(1L, p1.meta.page)
        assertEquals(2L, p1.meta.pageSize)
        assertEquals(3L, p1.meta.total)
        val p2 = Live.rewloy.listCustomers(search().apply { page = 2 })
        assertEquals(1, p2.data.size)
        assertTrue(p1.data.map { it.personId }.intersect(p2.data.map { it.personId }.toSet()).isEmpty())
        val all = Live.rewloy.listCustomersAll(search()).toList()
        assertEquals(3, all.size)
        assertEquals(3, all.map { it.personId }.toSet().size)
    }
}
