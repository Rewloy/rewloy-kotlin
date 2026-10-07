package com.rewloy.live

import com.rewloy.Rewloy
import com.rewloy.RewloyException
import com.rewloy.models.ListCustomersQuery
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last area: takes back what the run made. Programs are archived with their cards and codes (the API has no way
 * to delete a customer or a card with an API key); the test reset then removes the customers, cards, codes and
 * records. The reset is a person's operation (a staff session, `rws_`), so it runs when REWLOY_STAFF_SESSION is set;
 * with an API key alone the API answers 403 CREDENTIAL_NOT_ALLOWED, which is checked instead and said in the summary.
 */
@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(99)
@DisplayName("clean-up and test reset")
class CleanupLiveTest {
    @Test
    @Order(1)
    fun `what the run made is taken out of use`() {
        val program = Live.stampProgramId
        val report = Live.cleanUp()
        for (line in report) println("  clean-up: $line")
        assertEquals("archived", Live.rewloy.getProgram(program).status)
        assertEquals("archived", Live.rewloy.getProgram(Live.giftProgramId).status)
    }

    @Test
    @Order(2)
    fun `the test environment is reset`() {
        val session = Live.staffSession
        if (session == null) {
            // An API key may not reset: it is a person's operation. The refusal is the documented one.
            val e = expectError(403, "CREDENTIAL_NOT_ALLOWED") { Live.rewloy.resetTestEnvironment() }
            e.assertIsApiError()
            Live.note("resetTestEnvironment needs a staff session; with an API key alone it was refused (403 CREDENTIAL_NOT_ALLOWED), so customers and cards of this run (live-kt-${Live.runId}-…@example.com) remain. Set REWLOY_STAFF_SESSION (rws_…) to reset.")
            return
        }
        val mine = "live-kt-" + Live.runId
        val serial = Live.stampCard
        val businessId = Live.rewloy.getBusiness().id
        val staff = Rewloy { baseUrl(Live.baseUrl!!); staffSession(session); merchant(businessId) }
        val before = staff.getTestEnvironment()
        assertTrue(before.inTest || before.test != null)
        assertTrue(before.customers > 0 && before.cards > 0, "something to reset: ${before.customers} customers, ${before.cards} cards")
        val reset = try {
            staff.resetTestEnvironment()
        } catch (e: RewloyException) {
            // At most five resets a day per business (documented): a local loop can reach it. Said, not hidden.
            assertEquals(429, e.status)
            assertEquals("RATE_LIMITED", e.code)
            e.assertIsApiError()
            Live.note("resetTestEnvironment is limited to 5 a day per business (429 RATE_LIMITED); this run's data remains.")
            return
        }
        assertEquals(businessId, reset.merchantId, "the test environment keeps its identity")
        assertNull(reset.closed)
        assertEquals(false, reset.created)
        assertEquals(false, reset.keysRevoked)
        assertTrue(reset.deleted.customers >= 1 && reset.deleted.cards >= 1, "deleted ${reset.deleted.customers} customers, ${reset.deleted.cards} cards")
        assertTrue(reset.kept.programs >= 2, "programs are kept")
        assertTrue(reset.kept.keys >= 1, "the key is kept")
        // The same key goes on working; the run's customers and cards are gone.
        assertEquals(businessId, Live.rewloy.getBusiness().id)
        assertEquals(0L, Live.rewloy.listCustomers(ListCustomersQuery().apply { q = mine }).meta.total)
        expectError(404, "PASS_NOT_FOUND") { Live.rewloy.getPass(serial) }
        val after = staff.getTestEnvironment()
        assertEquals(0, after.customers)
        assertEquals(0, after.cards)
    }
}
