package com.rewloy.live

import com.rewloy.OptionalField
import com.rewloy.RequestOptions
import com.rewloy.Rewloy
import com.rewloy.json.JsonValue
import com.rewloy.models.CopyProgramBody
import com.rewloy.models.CreateEarnGroupBody
import com.rewloy.models.CreateEarnGroupBodyMembersItem
import com.rewloy.models.CreateEarnRuleBody
import com.rewloy.models.CreateLocationBody
import com.rewloy.models.CreateProgramBody
import com.rewloy.models.CreateWebhookBody
import com.rewloy.models.FreezeLocationBody
import com.rewloy.models.IgnoreSeenLineBody
import com.rewloy.models.ListPassOperationsQuery
import com.rewloy.models.PreviewEarnBody
import com.rewloy.models.PreviewEarnBodyLinesItem
import com.rewloy.models.PreviewEarnBodyRuleSet
import com.rewloy.models.PreviewSaleBody
import com.rewloy.models.PreviewSaleBodyLinesItem
import com.rewloy.models.PutEarnRulesBody
import com.rewloy.models.PutEarnRulesBodyRulesItem
import com.rewloy.models.RecordSaleBody
import com.rewloy.models.RecordSaleBodyLinesItem
import com.rewloy.models.ReverseSaleBody
import com.rewloy.models.ReverseSaleBodyLinesItem
import com.rewloy.models.UnignoreSeenLineQuery
import com.rewloy.models.UpdateBatchBody
import com.rewloy.models.UpdateEarnGroupBody
import com.rewloy.models.UpdateEarnRuleBody
import com.rewloy.models.UpdateLocationFreezeBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The areas of Rewloy API 1.3.0 (library 0.3.0): earn rules and receipt lines, the branch QR and branch freeze, the
 * copy of a gift card, the new webhook events. They run after the older areas and before the clean-up.
 */

/** What the earn area builds once: a stamp program with one group and one rule, and a card of it. */
private object EarnFixture {
    val programId: String by lazy {
        val p = Live.rewloy.createProgram(CreateProgramBody("stamp", "Kotlin Live Cafe").apply {
            programName = Live.name("earn"); maxStamps = 10; rewardName = "Free coffee"
        })
        Live.onCleanup("archive earn program ${p.id}") { Live.archive(p.id) }
        p.id
    }

    /** The group "drinks": two category paths. Deleted by the clean-up when the area did not do it. */
    val groupId: String by lazy {
        val g = Live.rewloy.createEarnGroup(CreateEarnGroupBody(Live.name("drinks")).apply {
            members = listOf(
                CreateEarnGroupBodyMembersItem("include", "category", "Drinks > Hot"),
                CreateEarnGroupBodyMembersItem("include", "category", "Drinks > Cold"),
            )
        })
        Live.onCleanup("remove the rules of ${programId} and the earn group ${g.id}") {
            runCatching { Live.rewloy.deleteEarnRules(programId) }
            Live.rewloy.deleteEarnGroup(g.id)
        }
        g.id
    }

    val card: String by lazy { Live.newCard(programId, Live.email("earn")) }

    /** Two cups of hot drinks (9.50 each) and a cake outside every group (110.00): 300.00 in all. */
    fun saleLines() = listOf(
        RecordSaleBodyLinesItem("Latte", 9500).apply { lineId = "1"; category = JsonValue.of("Drinks > Hot"); quantity = JsonValue.of(2L) },
        RecordSaleBodyLinesItem("Cheesecake", 11000).apply { lineId = "2"; category = JsonValue.of("Food > Sweet") },
    )
    const val SALE_TOTAL = 30000
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(13)
@DisplayName("earn rules and receipt lines")
class EarnRulesLiveTest {
    @Test
    @Order(1)
    fun `a group is created, read, listed and renamed`() {
        val id = EarnFixture.groupId
        val g = Live.rewloy.getEarnGroup(id)
        assertEquals(2, g.members.size)
        assertTrue(g.members.all { it.effect == "include" && it.match == "category" })
        assertTrue(Live.rewloy.listEarnGroups().any { it.id == id })
        // PATCH: the OkHttp transport (the default one cannot send it on Java 12+).
        val renamed = Live.okhttp.updateEarnGroup(id, UpdateEarnGroupBody().apply { name = Live.name("drinks renamed") })
        assertEquals(Live.name("drinks renamed"), renamed.name)
        assertEquals(Live.name("drinks renamed"), Live.rewloy.getEarnGroup(id).name)
    }

    @Test
    @Order(2)
    fun `the templates and the sources are listed`() {
        val templates = Live.rewloy.listEarnTemplates()
        assertTrue(templates.isNotEmpty())
        assertTrue(templates.any { it.type == "stamp" })
        Live.rewloy.listEarnSources() // may be empty on a business that has not sent lines yet; the call itself is the check
    }

    @Test
    @Order(3)
    fun `rules are put with a revision, and a stale revision is refused`() {
        val program = EarnFixture.programId
        val before = Live.rewloy.getEarnRules(program)
        assertFalse(before.active)
        assertEquals(0L, before.revision)
        val put = Live.rewloy.putEarnRules(program, PutEarnRulesBody(before.revision.toInt(), listOf(
            PutEarnRulesBodyRulesItem("stamp.perUnit").apply { id = "r1"; groupId = OptionalField.of(EarnFixture.groupId); stamps = 1 },
        )))
        assertTrue(put.active)
        assertEquals(1, put.revision)
        assertEquals("r1", put.rules.single().id)
        assertTrue(put.rules.single().text.isNotBlank())
        // The same revision again: someone changed the rules meanwhile.
        val e = expectError(409, "REVISION_CONFLICT") {
            Live.rewloy.putEarnRules(program, PutEarnRulesBody(before.revision.toInt(), put.rules.map { PutEarnRulesBodyRulesItem(it.kind).apply { id = it.id } }))
        }
        e.assertIsApiError()
    }

    @Test
    @Order(4)
    fun `one rule is created, changed and deleted`() {
        val program = EarnFixture.programId
        EarnFixture.groupId
        val created = Live.rewloy.createEarnRule(program, CreateEarnRuleBody("stamp.perReceipt").apply {
            id = "r2"; stamps = 1; minReceiptMinor = OptionalField.of(100000)
        })
        assertTrue(created.rules.any { it.id == "r2" })
        val changed = Live.okhttp.updateEarnRule(program, "r2", UpdateEarnRuleBody().apply { stamps = 2 })
        assertEquals(2, changed.rules.single { it.id == "r2" }.stamps)
        val deleted = Live.rewloy.deleteEarnRule(program, "r2")
        assertFalse(deleted.rules.any { it.id == "r2" })
        assertTrue(deleted.rules.any { it.id == "r1" }, "the first rule stays")
        val revisions = Live.rewloy.listEarnRuleRevisions(program)
        assertTrue(revisions.meta.total >= 4, "one revision for each change: ${revisions.meta.total}")
    }

    @Test
    @Order(5)
    fun `previewEarn says what a receipt earns, with the saved rules and with a draft`() {
        val program = EarnFixture.programId
        EarnFixture.groupId
        val saved = Live.rewloy.previewEarn(program, PreviewEarnBody(EarnFixture.SALE_TOTAL).apply {
            lines = listOf(
                PreviewEarnBodyLinesItem("Latte", 9500).apply { lineId = "1"; category = JsonValue.of("Drinks > Hot"); quantity = JsonValue.of(2L) },
                PreviewEarnBodyLinesItem("Cheesecake", 11000).apply { lineId = "2"; category = JsonValue.of("Food > Sweet") },
            )
        })
        assertEquals(2, saved.credited)
        assertEquals("stamps", saved.unit)
        assertEquals("rules", saved.earn.source)
        assertNotNull(saved.earn.revision)
        val l1 = saved.earn.lines.single { it.lineId == "1" }
        assertEquals("earned", l1.status)
        assertEquals(2L, l1.earned)
        assertTrue(EarnFixture.groupId in l1.groups!!, "the line is in the run's group: ${l1.groups}")
        assertEquals("no_rule", saved.earn.lines.single { it.lineId == "2" }.status)
        assertEquals(2L, saved.earn.total.credited)
        // A draft: nothing is saved, the explanation says so (revision null). Every item earns one stamp here.
        val draft = Live.rewloy.previewEarn(program, PreviewEarnBody(EarnFixture.SALE_TOTAL).apply {
            lines = listOf(PreviewEarnBodyLinesItem("Cheesecake", 11000).apply { lineId = "2"; category = JsonValue.of("Food > Sweet") })
            ruleSet = PreviewEarnBodyRuleSet().apply {
                rules = listOf(com.rewloy.models.PreviewEarnBodyRuleSetRulesItem("stamp.perUnit").apply { id = "d1"; stamps = 3 })
            }
        }.also { it.amountMinor = 11000 })
        assertEquals(3, draft.credited)
        assertNull(draft.earn.revision)
    }

    @Test
    @Order(6)
    fun `previewSale answers as recordSale would and writes nothing`() {
        val serial = EarnFixture.card
        val countBefore = Live.rewloy.getPass(serial).stamps!!.count
        val preview = Live.rewloy.previewSale(serial, PreviewSaleBody(EarnFixture.SALE_TOTAL).apply {
            locationId = Live.locationId
            lines = listOf(
                PreviewSaleBodyLinesItem("Latte", 9500).apply { lineId = "1"; category = JsonValue.of("Drinks > Hot"); quantity = JsonValue.of(2L) },
                PreviewSaleBodyLinesItem("Cheesecake", 11000).apply { lineId = "2"; category = JsonValue.of("Food > Sweet") },
            )
        })
        assertEquals("stamps", preview.applied)
        assertEquals(2L, preview.credited)
        assertFalse(preview.duplicate)
        assertEquals("rules", preview.earn!!.source)
        assertEquals(countBefore, Live.rewloy.getPass(serial).stamps!!.count, "nothing was written")
        assertEquals(0L, Live.rewloy.listPassOperations(serial).meta.total)
    }

    @Test
    @Order(7)
    fun `recordSale with lines earns by the rules and explains line by line, and a replay is the same sale`() {
        val serial = EarnFixture.card
        val key = Live.key("earn-sale")
        val body = RecordSaleBody(EarnFixture.SALE_TOTAL).apply {
            locationId = Live.locationId; reference = "fis-earn"; lines = EarnFixture.saleLines()
        }
        val sold = Live.rewloy.recordSale(serial, body, RequestOptions.withIdempotencyKey(key))
        assertEquals("stamps", sold.applied)
        assertEquals(2L, sold.credited)
        assertFalse(sold.duplicate)
        val earn = sold.earn!!
        assertEquals("rules", earn.source)
        assertEquals("stamps", earn.unit)
        assertEquals(listOf("r1"), earn.rules.map { it.ruleId })
        assertEquals(2L, earn.rules.single().units)
        assertEquals(2L, earn.lines.single { it.lineId == "1" }.earned)
        assertEquals("no_rule", earn.lines.single { it.lineId == "2" }.status)
        assertEquals(2L, earn.total.credited)
        assertEquals(2, Live.rewloy.getPass(serial).stamps!!.count)
        // The same key and body: the first sale again.
        val again = Live.rewloy.recordSale(serial, body, RequestOptions.withIdempotencyKey(key))
        assertTrue(again.duplicate)
        assertEquals(2, Live.rewloy.getPass(serial).stamps!!.count, "a replay writes nothing")
    }

    @Test
    @Order(8)
    fun `lines that do not add up to the amount are refused`() {
        val e = expectError(422, "LINES_TOTAL_MISMATCH") {
            Live.rewloy.recordSale(EarnFixture.card, RecordSaleBody(99999).apply {
                locationId = Live.locationId
                lines = listOf(RecordSaleBodyLinesItem("Latte", 100))
            }, RequestOptions.withIdempotencyKey(Live.key("earn-mismatch")))
        }
        e.assertIsApiError()
    }

    @Test
    @Order(9)
    fun `one line of the receipt is refunded and only the difference is taken back`() {
        val serial = EarnFixture.card
        val refund = Live.rewloy.reverseSale(serial, ReverseSaleBody().apply {
            saleKey = Live.key("earn-sale")
            lines = listOf(ReverseSaleBodyLinesItem("1").apply { quantity = JsonValue.of(1L) })
        }, RequestOptions.withIdempotencyKey(Live.key("earn-refund")))
        assertEquals(1L, refund.reversed)
        assertFalse(refund.duplicate)
        assertEquals(1.0, refund.balance)
        assertEquals(1L, refund.earn!!.total.credited, "the receipt as it stands now")
        val left = refund.linesLeft!!.single { it.lineId == "1" }
        assertEquals(1, left.quantity.toInt())
        assertEquals(9500, left.amountMinor.toInt())
        assertEquals(1, Live.rewloy.getPass(serial).stamps!!.count)
        val kinds = Live.rewloy.listPassOperations(serial, ListPassOperationsQuery()).data.map { it.kind }
        assertEquals(listOf("adjust", "earn"), kinds)
    }

    @Test
    @Order(10)
    fun `the categories the till sent are listed and one can be ignored`() {
        val seen = Live.rewloy.listSeenLines().data
        val hot = seen.firstOrNull { it.label.startsWith("Drinks") }
        assertNotNull(hot, "the receipt's categories are known: ${seen.map { it.label }}")
        val food = seen.first { it.label.startsWith("Food") }
        Live.rewloy.ignoreSeenLine(IgnoreSeenLineBody(food.key).apply { kind = food.kind })
        try {
            assertTrue(Live.rewloy.listSeenLines().data.first { it.key == food.key }.ignored)
        } finally {
            Live.rewloy.unignoreSeenLine(UnignoreSeenLineQuery(food.key).apply { kind = food.kind })
        }
        assertFalse(Live.rewloy.listSeenLines().data.first { it.key == food.key }.ignored)
    }

    @Test
    @Order(11)
    fun `a group in use cannot be deleted, and without the rules it can`() {
        val program = EarnFixture.programId
        val group = EarnFixture.groupId
        val e = expectError(409, "GROUP_IN_USE") { Live.rewloy.deleteEarnGroup(group) }
        e.assertIsApiError()
        Live.rewloy.deleteEarnRules(program)
        assertFalse(Live.rewloy.getEarnRules(program).active)
        Live.rewloy.deleteEarnGroup(group)
        expectError(404, "GROUP_NOT_FOUND") { Live.rewloy.getEarnGroup(group) }
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(14)
@DisplayName("branch QR and branch freeze")
class BranchQrLiveTest {
    @Test
    @Order(1)
    fun `a branch has its QR, the public page tells what it offers, and an unknown code is a 404`() {
        Live.stampProgramId
        val branch = Live.rewloy.getLocation(Live.locationId)
        assertEquals("live", branch.qr.state)
        assertTrue(branch.qr.code.isNotBlank())
        assertTrue(branch.qr.url.contains(branch.qr.code), branch.qr.url)
        assertNull(branch.frozen)
        // The page a customer sees after scanning, with no credential at all.
        val page = Live.anonymous().publicBranch(branch.qr.code)
        assertEquals(branch.qr.code, page.code)
        assertEquals(branch.name, page.branch.name)
        assertEquals("live", page.branch.state)
        assertTrue(page.business.name.endsWith("· Test"), page.business.name)
        val e = expectError(404, "BRANCH_NOT_FOUND") { Live.anonymous().publicBranch("ZZZZZZ") }
        e.assertIsApiError()
    }

    @Test
    @Order(2)
    fun `the QR image and the sheets are downloaded`() {
        val id = Live.locationId
        val png = Live.rewloy.locationQrPng(id)
        assertTrue(png.contentType!!.startsWith("image/png"), png.contentType)
        assertEquals(listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47), png.content.take(4))
        val svg = Live.rewloy.locationQrSvg(id)
        assertTrue(svg.contentType!!.startsWith("image/svg"), svg.contentType)
        assertTrue(String(svg.content, Charsets.UTF_8).startsWith("<svg"))
        val pdf = Live.rewloy.locationQrSheetPdf(id)
        assertTrue(pdf.contentType!!.startsWith("application/pdf"), pdf.contentType)
        assertEquals("%PDF", String(pdf.content.copyOf(4), Charsets.US_ASCII))
        val sheet = Live.rewloy.locationQrSheetSvg(id)
        assertTrue(String(sheet.content, Charsets.UTF_8).startsWith("<svg"))
    }

    @Test
    @Order(3)
    fun `the list behind the QR is read, with the programs that are added by themselves`() {
        val program = Live.stampProgramId
        val items = Live.rewloy.getLocationQrItems(Live.locationId)
        assertTrue(items.autoAdd)
        assertTrue(items.items.any { it.programId == program }, "the stamp program is on the list: ${items.items.map { it.name }}")
        val preview = Live.rewloy.previewLocationQr(Live.locationId)
        assertTrue(preview.code.isNotBlank())
    }

    @Test
    @Order(4)
    fun `a key cannot freeze a branch`() {
        val e = expectError(403, "CREDENTIAL_NOT_ALLOWED") {
            Live.rewloy.freezeLocation(Live.locationId, FreezeLocationBody("renovation", "not-the-password"))
        }
        e.assertIsApiError()
        assertNull(Live.rewloy.getLocation(Live.locationId).frozen)
    }

    @Test
    @Order(5)
    fun `a frozen branch refuses the till, shows it on its page and opens again`() {
        val session = Live.staffSession
        val password = Live.staffPassword
        if (session == null || password == null) {
            Live.note("Freezing a branch is a person's operation with the password asked again; set REWLOY_STAFF_SESSION and REWLOY_STAFF_PASSWORD to run the freeze test (location.frozen, LOCATION_FROZEN, BUSINESS_FROZEN). Only the key's refusal (403 CREDENTIAL_NOT_ALLOWED) was checked.")
            return
        }
        val businessId = Live.rewloy.getBusiness().id
        val staff = Rewloy { baseUrl(Live.baseUrl!!); staffSession(session); merchant(businessId) }
        val mainId = Live.locationId
        val branch = Live.rewloy.createLocation(CreateLocationBody(Live.name("branch to freeze")))
        Live.onCleanup("archive branch ${branch.id}") { Live.rewloy.archiveLocation(branch.id) }
        val serial = Live.newCard(Live.stampProgramId, Live.email("frozen"))
        val frozenIds = ArrayList<String>()
        try {
            val frozen = staff.freezeLocation(branch.id, FreezeLocationBody("renovation", password).apply {
                publicNote = OptionalField.of("Back soon")
            })
            frozenIds.add(branch.id)
            assertEquals("frozen", frozen.qr.state)
            assertEquals("renovation", frozen.frozen!!.reason)
            // The till of that branch is closed; the answer is the same for the dry run.
            val sale = RecordSaleBody(1000).apply { locationId = branch.id }
            expectError(409, "LOCATION_FROZEN") { Live.rewloy.recordSale(serial, sale, RequestOptions.withIdempotencyKey(Live.key("frozen-sale"))) }
            expectError(409, "LOCATION_FROZEN") { Live.rewloy.previewSale(serial, PreviewSaleBody(1000).apply { locationId = branch.id }) }
            // The branch page says so; the note is changed by a key (PATCH), the list of freezes shows it.
            val page = Live.anonymous().publicBranch(frozen.qr.code)
            assertEquals("frozen", page.branch.state)
            assertEquals("Back soon", page.branch.publicNote)
            Live.okhttp.updateLocationFreeze(branch.id, UpdateLocationFreezeBody().apply { publicNote = OptionalField.of("Back on Monday") })
            val freezes = Live.rewloy.listLocationFreezes(branch.id)
            assertEquals("Back on Monday", freezes.freezes.single().publicNote)
            // Every branch frozen: the business itself is paused.
            for (other in Live.rewloy.listLocations().filter { !it.archived && it.frozen == null }) {
                staff.freezeLocation(other.id, FreezeLocationBody("renovation", password))
                frozenIds.add(other.id)
            }
            expectError(409, "BUSINESS_FROZEN") {
                Live.rewloy.recordSale(serial, RecordSaleBody(1000), RequestOptions.withIdempotencyKey(Live.key("paused-sale")))
            }.assertIsApiError()
        } finally {
            for (id in frozenIds.reversed()) Live.rewloy.unfreezeLocation(id)
        }
        assertNull(Live.rewloy.getLocation(branch.id).frozen)
        assertNull(Live.rewloy.getLocation(mainId).frozen)
        assertTrue(Live.rewloy.listLocations().none { !it.archived && it.frozen != null }, "every branch is open again")
        assertEquals("live", Live.anonymous().publicBranch(branch.code ?: Live.rewloy.getLocation(branch.id).qr.code).branch.state)
        // Open again: the sale goes through.
        val ok = Live.rewloy.recordSale(serial, RecordSaleBody(1000).apply { locationId = branch.id }, RequestOptions.withIdempotencyKey(Live.key("open-sale")))
        assertEquals("stamps", ok.applied)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(15)
@DisplayName("gift card copy, code update and new webhook events")
class ReleaseOneThreeLiveTest {
    @Test
    @Order(1)
    fun `a loyalty card is not copied`() {
        val e = expectError(422, "NOT_AN_INSTRUMENT") { Live.rewloy.copyProgram(Live.stampProgramId) }
        e.assertIsApiError()
    }

    @Test
    @Order(2)
    fun `a gift card is copied with another value, and an unknown override is refused`() {
        val copy = Live.rewloy.copyProgram(Live.giftProgramId, CopyProgramBody().apply {
            name = Live.name("gift copy")
            overrides = JsonValue.parse("""{"giftValueMinor":10000}""")
        })
        Live.onCleanup("archive the copy ${copy.id}") { Live.archive(copy.id) }
        assertEquals("giftcard", copy.type)
        assertEquals(Live.name("gift copy"), copy.name)
        assertEquals("active", copy.status)
        assertTrue(copy.id != Live.giftProgramId)
        val e = expectError(422, "INVALID_CONFIG") {
            Live.rewloy.copyProgram(Live.giftProgramId, CopyProgramBody().apply { overrides = JsonValue.parse("""{"nonsense":1}""") })
        }
        e.assertIsApiError()
    }

    @Test
    @Order(3)
    fun `a code is renamed with updateBatch`() {
        val b = Live.newBatch()
        val renamed = Live.okhttp.updateBatch(b.id, UpdateBatchBody().apply { name = Live.name("code renamed") })
        assertEquals(Live.name("code renamed"), renamed.name)
        assertEquals(b.code, renamed.code)
        assertTrue(renamed.channels.sharedCode, "a code made through the API is a shared code")
    }

    @Test
    @Order(4)
    fun `the new webhook events are listed and can be subscribed to`() {
        val names = Live.rewloy.webhookEvents().events.map { it.event }
        for (e in listOf("pass.extended", "location.frozen", "location.unfrozen", "business.paused", "business.resumed")) {
            assertTrue(e in names, "$e is in $names")
        }
        val hook = Live.rewloy.createWebhook(CreateWebhookBody("https://example.com/rewloy-live-kt-${Live.runId}", listOf("pass.extended", "location.frozen")))
        try {
            assertEquals(setOf("pass.extended", "location.frozen"), hook.webhook.events.toSet())
        } finally {
            Live.rewloy.deleteWebhook(hook.webhook.id)
        }
    }

    @Test
    @Order(5)
    fun `the holder side of the branch QR needs a holder session`() {
        // joinHolderBranch and holderBranch take a customer's (holder) session, which an API key does not have.
        Live.note("holderBranch / joinHolderBranch (the Rewloy Cüzdan side of the branch QR) need a holder session and are not exercised by the live suite (an API key is refused: 403 CREDENTIAL_NOT_ALLOWED).")
        val e = expectError(403, "CREDENTIAL_NOT_ALLOWED") { Live.rewloy.holderBranch("ZZZZZZ") }
        e.assertIsApiError()
    }
}
