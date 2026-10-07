package com.rewloy

import com.rewloy.json.JsonValue
import com.rewloy.models.CopyProgramBody
import com.rewloy.models.CreateEarnGroupBody
import com.rewloy.models.CreateEarnGroupBodyMembersItem
import com.rewloy.models.FreezeLocationBody
import com.rewloy.models.LocationQrPngQuery
import com.rewloy.models.PreviewEarnBody
import com.rewloy.models.PreviewEarnBodyLinesItem
import com.rewloy.models.PreviewEarnBodyRuleSet
import com.rewloy.models.PreviewEarnBodyRuleSetRulesItem
import com.rewloy.models.PreviewSaleBody
import com.rewloy.models.PreviewSaleBodyLinesItem
import com.rewloy.models.PutEarnRulesBody
import com.rewloy.models.PutEarnRulesBodyRulesItem
import com.rewloy.models.RecordSaleBody
import com.rewloy.models.RecordSaleBodyLinesItem
import com.rewloy.models.ReverseSaleBody
import com.rewloy.models.ReverseSaleBodyLinesItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The operations, fields and errors Rewloy API 1.3.0 added. The answers in `src/test/resources/v130` are what a
 * 1.3.0 server sent (a development server, IDs and names of a test business), so the strict reader is checked against
 * the real shapes.
 */
class V130Test {
    private fun fixture(name: String): String =
        V130Test::class.java.getResourceAsStream("/v130/$name.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private val serial = "0J56-AVXU-KXXT"

    @Test
    fun `knows the 298 operations of 1_3_0 and the new ones are generated`() {
        assertEquals(298, RewloyOperations.all.size)
        val added = listOf(
            "listEarnGroups", "createEarnGroup", "getEarnGroup", "updateEarnGroup", "deleteEarnGroup", "listSeenLines",
            "listEarnSources", "ignoreSeenLine", "unignoreSeenLine", "getEarnRules", "putEarnRules", "createEarnRule",
            "updateEarnRule", "deleteEarnRule", "deleteEarnRules", "listEarnRuleRevisions", "previewEarn", "listEarnTemplates",
            "previewSale", "copyProgram", "extendProgramCards", "updateBatch", "publicBranch", "holderBranch", "joinHolderBranch",
            "locationQrSvg", "locationQrPng", "locationQrSheetPdf", "locationQrSheetSvg", "getLocationQrItems", "putLocationQrItems",
            "addQrItems", "previewLocationQr", "freezeLocation", "updateLocationFreeze", "cancelLocationFreeze", "unfreezeLocation",
            "listLocationFreezes",
        )
        assertEquals(38, added.size)
        for (id in added) assertTrue(id in RewloyOperations.all, id)
        assertTrue(RewloyOperations.listSeenLines.isPaged)
    }

    @Test
    fun `getMeta carries the environment as a property`() = Rig().test { rig ->
        rig.server.enqueue(Answer(200, fixture("meta")))
        val meta = rig.rewloy.getMeta()
        assertEquals("dev", meta.environment)
        assertEquals("v1", meta.apiVersion)
    }

    @Test
    fun `recordSale sends receipt lines and reads the earn explanation`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, fixture("sale-lines")))
        val body = RecordSaleBody(amountMinor = 30000, locationId = "loc-1").apply {
            reference = "fis-1"
            receiptDiscountMinor = 0
            lines = listOf(
                RecordSaleBodyLinesItem(name = "Latte", unitPriceMinor = 9500).apply {
                    lineId = "1"; category = JsonValue.of("Drinks > Hot"); quantity = JsonValue.of(2L); kind = "item"; tags = listOf("odul")
                },
                RecordSaleBodyLinesItem(name = "Tartı", unitPriceMinor = 48000).apply {
                    category = JsonValue.parse("""["Paketli","Kuruyemiş"]"""); quantity = JsonValue.of("0.350"); unit = "kg"; totalMinor = 16800
                },
            )
        }
        val sale = rig.rewloy.recordSale(serial, body, RequestOptions(idempotencyKey = "kasa3-z0187-fis0042"))
        val sent = rig.server.received.single().body
        assertTrue(""""lines":[{"lineId":"1","name":"Latte","category":"Drinks > Hot","quantity":2,"unitPriceMinor":9500,"kind":"item","tags":["odul"]}""" in sent, sent)
        assertTrue(""""category":["Paketli","Kuruyemiş"],"quantity":"0.350","unit":"kg","unitPriceMinor":48000,"totalMinor":16800""" in sent, sent)
        assertEquals("stamps", sale.applied)
        val earn = assertNotNull(sale.earn)
        assertEquals("rules", earn.source)
        assertEquals("stamps", earn.unit)
        assertEquals(1, earn.revision)
        assertEquals("earned", earn.lines.single { it.lineId == "1" }.status)
        assertEquals(2L, earn.lines.single { it.lineId == "1" }.earned)
        assertEquals("no_rule", earn.lines.single { it.lineId == "2" }.status)
        assertEquals("r1", earn.rules.single().ruleId)
        assertEquals(2L, earn.total.credited)
        assertNull(earn.total.promotion)
    }

    @Test
    fun `previewSale is a POST that writes nothing and answers like recordSale`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, fixture("preview-sale")))
        val preview = rig.rewloy.previewSale(serial, PreviewSaleBody(amountMinor = 30000).apply {
            lines = listOf(PreviewSaleBodyLinesItem(name = "Latte", unitPriceMinor = 9500))
        })
        val r = rig.server.received.single()
        assertEquals("POST", r.method)
        assertEquals("/v1/passes/$serial/sale/preview", r.path)
        assertEquals(2L, preview.credited)
        assertEquals("rules", preview.earn!!.source)
        assertNotNull(preview.card)
    }

    @Test
    fun `reverseSale refunds lines and reads what is left of the receipt`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, fixture("reverse-lines")))
        val r = rig.rewloy.reverseSale(serial, ReverseSaleBody(saleKey = "fx-sale-0001").apply {
            lines = listOf(ReverseSaleBodyLinesItem(lineId = "1").apply { quantity = JsonValue.of(1L) }, ReverseSaleBodyLinesItem(lineId = "2").apply { amountMinor = 500 })
        }, RequestOptions(idempotencyKey = "fx-refund-001"))
        assertEquals("""{"saleKey":"fx-sale-0001","lines":[{"lineId":"1","quantity":1},{"lineId":"2","amountMinor":500}]}""", rig.server.received.single().body)
        assertEquals(1L, r.reversed)
        assertEquals(1L, r.earn!!.total.credited)
        val left = r.linesLeft!!
        assertEquals(listOf("1", "2"), left.map { it.lineId })
        assertEquals(9500, left[0].amountMinor.toInt())
    }

    @Test
    fun `previewEarn takes a draft rule set and answers without a card`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, fixture("preview-earn")))
        val e = rig.rewloy.previewEarn("p1", PreviewEarnBody(amountMinor = 30000).apply {
            lines = listOf(PreviewEarnBodyLinesItem(name = "Latte", unitPriceMinor = 9500))
            ruleSet = PreviewEarnBodyRuleSet().apply {
                rules = listOf(PreviewEarnBodyRuleSetRulesItem(kind = "stamp.perUnit").apply { id = "d1"; stamps = 3; groupId = OptionalField.ofNull() })
            }
        })
        val sent = rig.server.received.single()
        assertEquals("/v1/programs/p1/earn-rules/preview", sent.path)
        assertTrue(""""ruleSet":{"rules":[{"id":"d1","kind":"stamp.perUnit","groupId":null,"stamps":3}]}""" in sent.body, sent.body)
        assertEquals(2, e.credited)
        assertEquals("stamps", e.unit)
        assertEquals(1, e.earn.revision)
    }

    @Test
    fun `earn rules are put with a revision, and the refusals have codes`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(200, fixture("earn-rules")),
            error("REVISION_CONFLICT", status = 409),
            error("GROUP_IN_USE", status = 409),
            error("RULE_KIND_NOT_FOR_TYPE", status = 422),
        )
        val put = rig.rewloy.putEarnRules("p1", PutEarnRulesBody(revision = 0, rules = listOf(PutEarnRulesBodyRulesItem(kind = "stamp.perUnit").apply { id = "r1"; stamps = 1 })))
        val sent = rig.server.received[0]
        assertEquals("PUT", sent.method)
        assertEquals("/v1/programs/p1/earn-rules", sent.path)
        assertEquals("""{"revision":0,"rules":[{"id":"r1","kind":"stamp.perUnit","stamps":1}]}""", sent.body)
        assertTrue(put.active)
        assertEquals(1L, put.revision)
        assertEquals("r1", put.rules.single().id)
        assertEquals(ErrorCode.REVISION_CONFLICT, assertFailsWith<RewloyException> { rig.rewloy.putEarnRules("p1", PutEarnRulesBody(0, emptyList())) }.code)
        assertEquals(ErrorCode.GROUP_IN_USE, assertFailsWith<RewloyException> { rig.rewloy.deleteEarnGroup("g1") }.code)
        assertEquals(ErrorCode.RULE_KIND_NOT_FOR_TYPE, assertFailsWith<RewloyException> { rig.rewloy.deleteEarnRule("p1", "r9") }.code)
    }

    @Test
    fun `an earn group is created from category members`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(201, fixture("earn-group")))
        val g = rig.rewloy.createEarnGroup(CreateEarnGroupBody("Drinks").apply {
            members = listOf(CreateEarnGroupBodyMembersItem(effect = "include", match = "category", value = "Drinks > Hot"))
        })
        assertEquals("""{"name":"Drinks","members":[{"effect":"include","match":"category","value":"Drinks > Hot"}]}""", rig.server.received.single().body)
        assertTrue(g.members.isNotEmpty())
        assertTrue(g.members.all { it.effect == "include" })
    }

    @Test
    fun `a branch has its QR and a freeze, and the till refusals have codes`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(200, fixture("location")),
            Answer(200, fixture("location-frozen")),
            Answer(200, fixture("freezes")),
            error("LOCATION_FROZEN", status = 409),
            error("BUSINESS_FROZEN", status = 409),
            error("FREEZE_LIMIT", status = 409),
        )
        val open = rig.rewloy.getLocation("loc-1")
        assertEquals("live", open.qr.state)
        assertTrue(open.qr.url.endsWith("/s/${open.qr.code}"), open.qr.url)
        assertNull(open.frozen)
        val frozen = rig.rewloy.freezeLocation("loc-1", FreezeLocationBody(reason = "renovation", password = "x").apply { publicNote = OptionalField.of("Back soon") })
        assertEquals("""{"reason":"renovation","publicNote":"Back soon","password":"x"}""", rig.server.received[1].body)
        assertEquals("frozen", frozen.qr.state)
        assertEquals("renovation", frozen.frozen!!.reason)
        assertEquals("2026-11-01", frozen.frozen!!.reopensOn)
        val freezes = rig.rewloy.listLocationFreezes("loc-1")
        assertEquals("Back soon", freezes.freezes.single().publicNote)
        assertEquals(90, freezes.freeDays.left)
        assertEquals(ErrorCode.LOCATION_FROZEN, assertFailsWith<RewloyException> { rig.rewloy.recordSale(serial, RecordSaleBody(100, "loc-1"), RequestOptions(idempotencyKey = "kasa3-fis-0001")) }.code)
        assertEquals(ErrorCode.BUSINESS_FROZEN, assertFailsWith<RewloyException> { rig.rewloy.recordSale(serial, RecordSaleBody(100), RequestOptions(idempotencyKey = "kasa3-fis-0002")) }.code)
        assertEquals(ErrorCode.FREEZE_LIMIT, assertFailsWith<RewloyException> { rig.rewloy.freezeLocation("loc-1", FreezeLocationBody("renovation", "x")) }.code)
    }

    @Test
    fun `the public branch page is read without a credential`() = Rig().test { rig ->
        rig.server.enqueue(Answer(200, fixture("public-branch")))
        val page = rig.rewloy.publicBranch("7NPZQS")
        assertEquals("/v1/public/branches/7NPZQS", rig.server.received.single().path)
        assertNull(rig.server.received.single().header("authorization"))
        assertEquals("live", page.branch.state)
        assertTrue(page.test)
    }

    @Test
    fun `the branch QR image and sheets come as files`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(
            Answer(200, headers = mapOf("Content-Type" to "image/png"), rawBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            Answer(200, headers = mapOf("Content-Type" to "application/pdf"), rawBytes = "%PDF-1.7".toByteArray()),
            Answer(200, fixture("qr-items")),
        )
        val png = rig.rewloy.locationQrPng("loc-1", LocationQrPngQuery().apply { size = 600 })
        assertEquals("/v1/locations/loc-1/qr.png", rig.server.received[0].path)
        assertEquals("size=600", rig.server.received[0].query)
        assertEquals("image/png", png.contentType)
        assertEquals(4, png.content.size)
        val pdf = rig.rewloy.locationQrSheetPdf("loc-1")
        assertEquals("/v1/locations/loc-1/qr/sheet.pdf", rig.server.received[1].path)
        assertEquals("%PDF", String(pdf.content.copyOf(4)))
        val items = rig.rewloy.getLocationQrItems("loc-1")
        assertTrue(items.autoAdd)
        assertEquals("stamp", items.items.single().type)
    }

    @Test
    fun `copyProgram refuses a loyalty card`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(error("NOT_AN_INSTRUMENT", status = 422))
        val e = assertFailsWith<RewloyException> {
            rig.rewloy.copyProgram("p1", CopyProgramBody().apply { name = "Kopya"; overrides = JsonValue.parse("""{"giftValueMinor":10000}""") })
        }
        assertEquals(ErrorCode.NOT_AN_INSTRUMENT, e.code)
        assertEquals(422, e.status)
        assertEquals("""{"name":"Kopya","overrides":{"giftValueMinor":10000}}""", rig.server.received.single().body)
    }

    @Test
    fun `the webhook events of 1_3_0 are listed`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, fixture("webhook-events")))
        val names = rig.rewloy.webhookEvents().events.map { it.event }
        for (e in listOf("pass.extended", "location.frozen", "location.unfrozen", "business.paused", "business.resumed")) assertTrue(e in names, e)
    }

    @Test
    fun `a webhook delivery of a new event type verifies like any other`() {
        val secret = "whsec_test"
        val raw = """{"id":"evt_1","type":"location.frozen","created_at":"2026-10-07T10:00:00.000Z","data":{"location_id":"loc-1"}}"""
        val timestamp = System.currentTimeMillis() / 1000
        val signature = Webhook.sign(raw, secret, timestamp)
        val event = Webhook.verify(raw, signature, secret, nowSeconds = timestamp)
        assertEquals("location.frozen", event.type)
    }

    /** The README's 1.3.0 snippets: compiled, not run. */
    @Suppress("unused")
    private fun compileOnly(rewloy: Rewloy, seri: String, programId: String, subeId: String) {
        val grup = rewloy.createEarnGroup(CreateEarnGroupBody("Hazırlanan içecekler").apply {
            members = listOf(CreateEarnGroupBodyMembersItem("include", "category", "İçecek > Sıcak"))
        })
        val kurallar = rewloy.getEarnRules(programId)
        rewloy.putEarnRules(programId, PutEarnRulesBody(kurallar.revision.toInt(), listOf(
            PutEarnRulesBodyRulesItem("stamp.perUnit").apply { id = "r1"; groupId = OptionalField.of(grup.id); stamps = 1 },
        )))
        val fis = RecordSaleBody(amountMinor = 35800, locationId = subeId).apply {
            lines = listOf(RecordSaleBodyLinesItem("Latte", 9500).apply { lineId = "1"; category = JsonValue.of("İçecek > Sıcak"); quantity = JsonValue.of(2L) })
        }
        val satis = rewloy.recordSale(seri, fis, RequestOptions(idempotencyKey = "kasa3-z0187-fis0042"))
        satis.earn?.lines?.forEach { println("${it.lineId}: ${it.status} ${it.earned}") }
        rewloy.reverseSale(seri, ReverseSaleBody(saleKey = "kasa3-z0187-fis0042").apply { lines = listOf(ReverseSaleBodyLinesItem("1")) }, RequestOptions(idempotencyKey = "iade-1"))
        val onizleme = rewloy.previewSale(seri, PreviewSaleBody(amountMinor = 35800).apply { lines = listOf(PreviewSaleBodyLinesItem("Latte", 9500)) })
        println(onizleme.credited)
        val png = rewloy.locationQrPng(subeId, LocationQrPngQuery().apply { size = 800 })
        println(png.content.size)
        println(rewloy.publicBranch(rewloy.getLocation(subeId).qr.code).branch.state)
    }
}
