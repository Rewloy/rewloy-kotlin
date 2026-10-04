package com.rewloy

import com.rewloy.models.CorrectHolderProfileBody
import com.rewloy.models.IssuePassBody
import com.rewloy.models.ListCustomersQuery
import com.rewloy.models.LoginBody
import com.rewloy.models.PassActionBody
import com.rewloy.models.ProveMfaBody
import com.rewloy.models.SendCampaignBody
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README's examples: the ones that can run do, the rest are only compiled, so that they cannot go stale. */
class ReadmeExamplesTest {
    @Test
    fun `getting started, issuing a card and a till action`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val rewloy = rig.rewloy
        rig.server.script = { r, _ ->
            when {
                r.path == "/v1/passes/ABCD-EFGH-JKLM" -> Answer(200, Fixtures.PASS)
                r.path == "/v1/passes" -> Answer(201, """{"data":{"serial":"ABCD-EFGH-JKLM","cardUrl":"https://rewloy.com/c/x"}}""")
                else -> Answer(200, Fixtures.ACTION)
            }
        }
        val kart = rewloy.getPass("ABCD-EFGH-JKLM")
        assertEquals("stamp 3.0 false", "${kart.type} ${kart.balance} ${kart.rewardReady}")

        val programId = "p"
        val locationId = "l"
        val fisNo = 42
        val issued = rewloy.issuePass(IssuePassBody(programId = programId, email = "ayse@ornek.com", firstName = "Ayşe", kvkkConsent = true))
        assertEquals("ABCD-EFGH-JKLM https://rewloy.com/c/x", issued.serial + " " + issued.cardUrl)
        val sonuc = rewloy.passAction(
            issued.serial,
            PassActionBody("earn-stamps", locationId).apply { count = 1 },
            RequestOptions(idempotencyKey = "fis-$fisNo"),
        )
        assertTrue(!sonuc.duplicate)
        assertEquals("fis-42", rig.server.received.last().header("idempotency-key"))
        assertEquals("""{"action":"earn-stamps","locationId":"l","count":1}""", rig.server.received.last().body)
    }

    @Test
    fun `paging and the stream`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val rewloy = rig.rewloy
        rig.server.script = { r, _ ->
            if (r.path == "/v1/live") Answer(chunks = listOf("event: event\ndata: {\"kind\":\"earn\"}\n\n".toByteArray()))
            else Answer(200, Fixtures.customers(1, 1, 1, 200, 1))
        }
        var names = ""
        for (musteri in rewloy.listCustomersAll(ListCustomersQuery(consent = "yes", limit = 200))) names += "${musteri.displayName} ${musteri.email}"
        assertEquals("Ayşe 1 null", names)

        val seen = ArrayList<String>()
        val t = thread {
            rewloy.liveFeed(RequestOptions(reconnect = false)).use { akis ->
                for (olay in akis) {
                    if (olay.event == "event") seen.add(olay.json().toString())
                }
            }
        }
        t.join()
        assertEquals(listOf("""{"kind":"earn"}"""), seen)
    }

    @Test
    fun `signing in without a credential and then with the session`() = Rig().test { rig ->
        rig.server.script = { r, _ ->
            when (r.path) {
                "/v1/auth/login" -> Answer(201, """{"data":{"token":"rws_t","mfaRequired":true,"user":{"id":"u","email":"e"},"businesses":[]}}""")
                else -> Answer(200, """{"data":{"via":"totp","recoveryCodesLeft":8}}""")
            }
        }
        val oturum = rig.rewloy.login(LoginBody("e", "pw"))
        val ekip = Rewloy { baseUrl(rig.server.url); staffSession(oturum.token); merchant("m-1") }
        if (oturum.mfaRequired) ekip.proveMfa(ProveMfaBody("123456"))
        assertEquals("Bearer rws_t", rig.server.received.last().header("authorization"))
        assertEquals("m-1", rig.server.received.last().header("rewloy-merchant"))
    }

    /** Compiled, not run. */
    @Suppress("unused")
    private fun compileOnly(rewloy: Rewloy, serial: String, govde: PassActionBody) {
        val yanit = rewloy.sendCampaignWithResponse(SendCampaignBody("Bu hafta kahveler 2 damga!"), RequestOptions(idempotencyKey = "kampanya-2026-10-03"))
        println("${yanit.statusCode} ${yanit.replayed} ${yanit.requestId} ${yanit.mode} ${yanit.isTestMode} ${yanit.data.id}")
        rewloy.correctHolderProfile("person", CorrectHolderProfileBody(firstName = OptionalField.of("Ayşe"), phone = OptionalField.ofNull()))
        val sayfa = rewloy.listCustomers(ListCustomersQuery(page = 2))
        println("${sayfa.data.size} ${sayfa.meta.total}")
        val iptal = CancelToken()
        thread { rewloy.passAction(serial, govde, RequestOptions(cancel = iptal)) }
        iptal.cancel()
        Rewloy { apiKey("rwk_x"); deprecationListener { n -> println(n.message) } }
        try {
            rewloy.passAction(serial, govde, RequestOptions(idempotencyKey = "fis-1"))
        } catch (e: RateLimitException) {
            println("${e.retryAfterSeconds} saniye sonra yeniden deneyin")
        } catch (e: RewloyException) {
            if (e.code == ErrorCode.INSUFFICIENT_BALANCE) println(e.detail) else throw e
        }
        val event = Webhook.verify(ByteArray(0), "h", System.getenv("REWLOY_WEBHOOK_SECRET") ?: "s")
        event.passData?.let { println("${it.card} ${it.kind} ${it.delta}") }
        println(RewloyOperations.passAction.isPaged)
    }
}
