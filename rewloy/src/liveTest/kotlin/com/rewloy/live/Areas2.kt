package com.rewloy.live

import com.rewloy.RequestOptions
import com.rewloy.models.GetPassTillQuery
import com.rewloy.models.IssuePassBody
import com.rewloy.models.ListCustomersQuery
import com.rewloy.models.ListPassOperationsQuery
import com.rewloy.models.PassActionBody
import com.rewloy.models.PassActionDataOption1
import com.rewloy.models.RecordSaleBody
import com.rewloy.models.ReverseActionBody
import com.rewloy.models.ReverseSaleBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(4)
@DisplayName("passes")
class PassesLiveTest {
    @Test
    @Order(1)
    fun `issue and get a stamp card`() {
        val serial = Live.stampCard
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(serial), serial)
        val card = Live.rewloy.getPass(serial)
        assertEquals("stamp", card.type)
        assertEquals(Live.stampProgramId, card.programId)
        assertEquals("active", card.status)
        assertEquals(0, card.stamps!!.count)
        assertEquals(4, card.stamps!!.max)
        assertEquals("Kt", card.customer?.name)
        assertTrue(card.actions.any { it.action == "earn-stamps" && it.ready })
        assertFalse(card.actions.any { it.action == "redeem-stamps" && it.ready })
        assertEquals("stamps", card.sale.writes)
    }

    @Test
    @Order(2)
    fun `issue returns the card URL with the viewing key`() {
        val r = Live.rewloy.issuePassWithResponse(IssuePassBody(Live.stampProgramId).apply {
            email = Live.email("url"); kvkkConsent = true
        })
        assertEquals(201, r.statusCode)
        assertTrue(r.data.created)
        assertTrue(r.data.cardUrl.contains(r.data.serial), r.data.cardUrl)
        assertTrue(r.data.cardUrl.contains("?k="), "a new card's link carries the viewing key")
    }

    @Test
    @Order(3)
    fun `ifExists return gives the same card back`() {
        val serial = Live.stampCard
        val again = Live.rewloy.issuePass(IssuePassBody(Live.stampProgramId).apply {
            email = Live.stampEmail; kvkkConsent = true; ifExists = "return"
        })
        assertFalse(again.created)
        assertEquals(serial, again.serial)
    }

    @Test
    @Order(4)
    fun `issue and get a gift card with its face value`() {
        val card = Live.rewloy.getPass(Live.giftCard)
        assertEquals("giftcard", card.type)
        assertEquals(5000L, card.money!!.amountMinor)
        assertEquals(Live.rewloy.getBusiness().currency, card.currency)
    }

    @Test
    @Order(5)
    fun `the till view allows the branch`() {
        val till = Live.rewloy.getPassTill(Live.stampCard, GetPassTillQuery(Live.locationId))
        assertTrue(till.allowed)
        assertNotNull(till.notices)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(5)
@DisplayName("sales and till actions")
class SalesLiveTest {
    @Test
    @Order(1)
    fun `recordSale writes stamps and answers with the card`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("sale-1")
        val sale = Live.rewloy.recordSale(
            serial, RecordSaleBody(amountMinor = 4550, locationId = Live.locationId, reference = "fis-live-1"),
            RequestOptions(idempotencyKey = key),
        )
        assertEquals("stamps", sale.applied)
        assertEquals(1L, sale.credited)
        assertFalse(sale.duplicate)
        assertFalse(sale.reversed)
        assertEquals(1, sale.card!!.stamps!!.count)
        assertEquals(serial, sale.card!!.serial)
        assertEquals(1, Live.rewloy.getPass(serial).stamps!!.count)
    }

    @Test
    @Order(2)
    fun `recordSale on a gift card writes what the program says`() {
        val serial = Live.giftCard
        val writes = Live.rewloy.getPass(serial).sale.writes
        val sale = Live.rewloy.recordSale(
            serial, RecordSaleBody(amountMinor = 1000, locationId = Live.locationId, currency = Live.rewloy.getBusiness().currency),
            RequestOptions(idempotencyKey = Live.key("sale-gift")),
        )
        if (writes == "none") assertEquals("none", sale.applied) else assertNotEquals("none", sale.applied)
    }

    @Test
    @Order(3)
    fun `reverseSale takes the sale back, once`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("sale-2")
        Live.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 2000, locationId = Live.locationId, reference = "fis-live-2"), RequestOptions(idempotencyKey = key))
        val back = Live.rewloy.reverseSale(serial, ReverseSaleBody(saleKey = key, locationId = Live.locationId))
        assertEquals(1L, back.reversed)
        assertFalse(back.duplicate)
        assertEquals(0, back.card!!.stamps!!.count)
        val again = Live.rewloy.reverseSale(serial, ReverseSaleBody(saleKey = key, locationId = Live.locationId))
        assertTrue(again.duplicate)
        assertEquals(0, Live.rewloy.getPass(serial).stamps!!.count)
    }

    @Test
    @Order(4)
    fun `reverseSale finds a sale by its reference`() {
        val serial = Live.newCard(Live.stampProgramId)
        val ref = "fis-live-" + Live.runId
        Live.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 2000, locationId = Live.locationId, reference = ref), RequestOptions(idempotencyKey = Live.key("sale-3")))
        assertEquals(1L, Live.rewloy.reverseSale(serial, ReverseSaleBody(reference = ref)).reversed)
    }

    @Test
    @Order(5)
    fun `passAction earns stamps and redeems the reward`() {
        val serial = Live.newCard(Live.stampProgramId)
        val earn = Live.rewloy.passAction(serial, PassActionBody("earn-stamps", Live.locationId).apply { count = 3 }, RequestOptions(idempotencyKey = Live.key("earn-3")))
        assertTrue(earn is PassActionDataOption1)
        assertEquals(3.0, (earn as PassActionDataOption1).balance)
        val full = Live.rewloy.passAction(serial, PassActionBody("earn-stamps", Live.locationId).apply { count = 1 }, RequestOptions(idempotencyKey = Live.key("earn-1")))
        assertEquals(4.0, (full as PassActionDataOption1).balance)
        assertTrue(Live.rewloy.getPass(serial).rewardReady)
        val redeem = Live.rewloy.passAction(serial, PassActionBody("redeem-stamps", Live.locationId), RequestOptions(idempotencyKey = Live.key("redeem-1")))
        assertEquals(0.0, (redeem as PassActionDataOption1).balance)
        assertFalse(Live.rewloy.getPass(serial).rewardReady)
    }

    @Test
    @Order(6)
    fun `reverseAction gives a redeemed reward back`() {
        val serial = Live.newCard(Live.stampProgramId)
        Live.rewloy.passAction(serial, PassActionBody("earn-stamps", Live.locationId).apply { count = 3 }, RequestOptions(idempotencyKey = Live.key("ra-earn-3")))
        Live.rewloy.passAction(serial, PassActionBody("earn-stamps", Live.locationId).apply { count = 1 }, RequestOptions(idempotencyKey = Live.key("ra-earn-1")))
        val key = Live.key("ra-redeem")
        Live.rewloy.passAction(serial, PassActionBody("redeem-stamps", Live.locationId), RequestOptions(idempotencyKey = key))
        val undone = Live.rewloy.reverseAction(serial, ReverseActionBody(actionKey = key, locationId = Live.locationId))
        assertFalse(undone.duplicate)
        assertEquals(4.0, undone.balance)
        assertTrue(Live.rewloy.reverseAction(serial, ReverseActionBody(actionKey = key)).duplicate)
    }

    @Test
    @Order(7)
    fun `a gift card is spent and the spend reversed`() {
        val serial = Live.newCard(Live.giftProgramId, faceMinor = 5000)
        val key = Live.key("spend")
        val spent = Live.rewloy.passAction(serial, PassActionBody("spend", Live.locationId).apply { amountMinor = 1500 }, RequestOptions(idempotencyKey = key))
        assertEquals(3500L, (spent as PassActionDataOption1).card!!.money!!.amountMinor)
        val undone = Live.rewloy.reverseAction(serial, ReverseActionBody(actionKey = key))
        assertEquals(1500L, undone.restored)
        assertEquals(5000L, Live.rewloy.getPass(serial).money!!.amountMinor)
    }

    @Test
    @Order(8)
    fun `the operations list shows the sale and its reversal`() {
        val serial = Live.newCard(Live.stampProgramId)
        val key = Live.key("ops-sale")
        Live.rewloy.recordSale(serial, RecordSaleBody(amountMinor = 3000, locationId = Live.locationId, reference = "fis-ops"), RequestOptions(idempotencyKey = key))
        val before = Live.rewloy.listPassOperations(serial)
        val op = before.data.single { it.saleKey == key }
        assertEquals("earn", op.kind)
        assertEquals("sale/reverse", op.undoWith)
        assertTrue(op.reversible)
        assertEquals("fis-ops", op.reference)
        assertEquals(1L, before.meta.total)
        // Walk the list the way a till's "recent operations" screen would.
        for (item in Live.rewloy.listPassOperationsAll(serial)) {
            if (item.reversible && item.undoWith == "sale/reverse") Live.rewloy.reverseSale(serial, ReverseSaleBody(saleKey = item.saleKey))
        }
        val after = Live.rewloy.listPassOperations(serial).data
        assertTrue(after.any { it.reverses != null }, "a reversal entry")
        assertTrue(after.single { it.saleKey == key }.reversedBy != null)
        assertEquals(0, Live.rewloy.getPass(serial).stamps!!.count)
    }
}
