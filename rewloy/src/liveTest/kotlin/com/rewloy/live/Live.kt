package com.rewloy.live

import com.rewloy.Rewloy
import com.rewloy.RewloyException
import com.rewloy.models.ArchiveProgramBody
import com.rewloy.models.CreateBatchBody
import com.rewloy.models.CreateBatchData
import com.rewloy.models.CreateProgramBody
import com.rewloy.models.IssuePassBody
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Raised when the run is not allowed: not a dev server, not a test key. Every area then fails with this message. */
class LiveRefused(message: String) : RuntimeException(message)

/**
 * What the live suite needs and shares: the settings from the environment, the guard that decides whether the
 * run is allowed at all, one client, and the things the areas build on (two programs, their cards, a code).
 *
 * Everything the suite creates is recorded here and cleaned up by the last area; only the test reset (a staff
 * session's operation) removes customers and cards.
 */
object Live {
    const val TEST_KEY_PREFIX = "rwk_test_"

    @JvmField val baseUrl: String? = System.getenv("REWLOY_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    @JvmField val apiKey: String? = System.getenv("REWLOY_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    /** Optional `rws_` staff session: only a person's session may reset the test environment (the API key may not). */
    @JvmField val staffSession: String? = System.getenv("REWLOY_STAFF_SESSION")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Optional, with REWLOY_STAFF_SESSION: that person's password. Freezing a branch asks for it again
     * (`freezeLocation` is a person's operation with a step-up); without it that test only checks the refusal.
     * Read from the environment, never printed.
     */
    @JvmField val staffPassword: String? = System.getenv("REWLOY_STAFF_PASSWORD")?.takeIf { it.isNotEmpty() }

    @JvmStatic val configured: Boolean get() = baseUrl != null && apiKey != null

    /** A short id of this run, in every name, e-mail address and idempotency key the suite makes. */
    @JvmField val runId: String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString(SecureRandom().nextInt(1296).toLong(), 36)

    private val guardResult: Result<Unit> by lazy { runCatching { check() } }

    /** Throws [LiveRefused] unless this run may talk to this server with this key. Evaluated once. */
    @JvmStatic
    fun guard() {
        guardResult.getOrThrow()
    }

    private fun check() {
        val url = baseUrl ?: throw LiveRefused("REWLOY_BASE_URL is not set")
        val key = apiKey ?: throw LiveRefused("REWLOY_API_KEY is not set")
        val host = runCatching { URI(url).host }.getOrNull() ?: throw LiveRefused("REWLOY_BASE_URL is not a URL: $url")
        // 1. The key: a test key or nothing. Checked before a single request leaves.
        if (!key.startsWith(TEST_KEY_PREFIX)) {
            throw LiveRefused("REFUSED: REWLOY_API_KEY is not a test-mode key (it must start with $TEST_KEY_PREFIX). The live suite never runs with another key.")
        }
        // 2. The server: GET /v1/meta, with no credential, must say it is a dev Rewloy.
        val meta = try {
            Rewloy { baseUrl(url); maxRetries(0) }.getMeta()
        } catch (e: RewloyException) {
            throw LiveRefused("REFUSED: GET /v1/meta on $host failed (${e.status} ${e.code}); not running against a server that does not say what it is.")
        } catch (e: RuntimeException) {
            throw LiveRefused("REFUSED: $host is not reachable (${e.javaClass.simpleName}: ${e.message}).")
        }
        // `environment` is a required, typed property since 0.3.0 (API 1.3.0): a server that does not send it
        // (Rewloy before 1.2.2) fails the typed read above and is refused there.
        val environment = meta.environment
        if (environment != "dev") {
            throw LiveRefused("REFUSED: GET /v1/meta on $host says environment \"$environment\", not \"dev\". The live suite runs only against a dev server.")
        }
        // 3. The key must act in a test business: the answers say so (Rewloy-Mode) and the business is named so.
        val business = try {
            Rewloy { baseUrl(url); apiKey(key); maxRetries(0) }.getBusinessWithResponse()
        } catch (e: RewloyException) {
            throw LiveRefused("REFUSED: the key was not accepted by $host (${e.status} ${e.code}).")
        }
        if (!business.isTestMode) throw LiveRefused("REFUSED: $host answered without Rewloy-Mode: test; the key does not act in a test business.")
        if (!business.data.name.endsWith("· Test")) throw LiveRefused("REFUSED: the key's business is not named like a test business (… · Test).")
    }

    /** The one client of the suite. */
    val rewloy: Rewloy by lazy {
        guard()
        Rewloy { baseUrl(baseUrl!!); apiKey(apiKey!!) }
    }

    /**
     * A client over the OkHttp transport: the desktop JDK's default transport cannot send `PATCH` (Java 12 and
     * later), and many 1.3.0 operations are `PATCH` (`updateEarnGroup`, `updateEarnRule`, `updateLocationFreeze` …).
     */
    val okhttp: Rewloy by lazy {
        guard()
        Rewloy { baseUrl(baseUrl!!); apiKey(apiKey!!); transport(com.rewloy.okhttp.OkHttpTransport()) }
    }

    /** A client with the same server and no credential, for the endpoints that need none and for refusals. */
    fun anonymous(): Rewloy {
        guard()
        return Rewloy { baseUrl(baseUrl!!) }
    }

    /** A name for something the suite creates: unique to this run. */
    fun name(what: String): String = "live-kt $what $runId"

    private var counter = 0

    /** A fresh e-mail address of this run (example.com: nothing is ever sent; a test business sends nothing at all). */
    @Synchronized
    fun email(what: String): String = "live-kt-$runId-$what-${++counter}@example.com"

    /** An `Idempotency-Key` of this run: 8 to 64 visible ASCII characters. */
    fun key(what: String): String = "ktlive-$runId-$what".take(64)

    // ----------------------------------------------------------------- notes for the summary

    private val notes = CopyOnWriteArrayList<String>()

    /** Something the run could not do or did differently, said again at the end of the summary. */
    fun note(text: String) {
        notes.add(text)
    }

    fun notes(): List<String> = notes.toList()

    // ----------------------------------------------------------------- clean-up

    private class Cleanup(val what: String, val action: () -> Unit)

    private val cleanups = CopyOnWriteArrayList<Cleanup>()

    fun onCleanup(what: String, action: () -> Unit) {
        cleanups.add(Cleanup(what, action))
    }

    /** Runs the recorded clean-ups, last made first. Returns what was done and what was not. */
    fun cleanUp(): List<String> {
        val report = ArrayList<String>()
        for (c in cleanups.reversed()) {
            try {
                c.action()
                report.add("done: ${c.what}")
            } catch (e: RewloyException) {
                if (e.status == 404) report.add("already gone: ${c.what}")
                else report.add("not possible: ${c.what} (${e.status} ${e.code})")
            }
        }
        cleanups.clear()
        return report
    }

    // ----------------------------------------------------------------- what the areas build on

    /** The branch the till operations name. */
    val locationId: String by lazy {
        rewloy.listLocations().firstOrNull { !it.archived }?.id
            ?: error("the test business has no branch")
    }

    /** Programs the suite created, by what they are for. */
    val stampProgramId: String by lazy {
        val p = rewloy.createProgram(CreateProgramBody("stamp", "Kotlin Live Cafe").apply {
            programName = name("stamp"); maxStamps = 4; rewardName = "Free coffee"
        })
        onCleanup("archive stamp program ${p.id}") { archive(p.id) }
        p.id
    }

    val giftProgramId: String by lazy {
        val p = rewloy.createProgram(CreateProgramBody("giftcard", "Kotlin Live Cafe").apply { programName = name("gift") })
        onCleanup("archive gift card program ${p.id}") { archive(p.id) }
        p.id
    }

    /** Archives a program with its cards (the API's way to take a program out of use; nothing else deletes one). */
    fun archive(programId: String) {
        // Taking the cards out of use cannot be undone, so the API wants the program's name as the confirmation.
        val name = rewloy.getProgram(programId).programName
        rewloy.archiveProgram(programId, ArchiveProgramBody().apply { cancelCards = true; confirmName = name })
    }

    /** The address of the main stamp card's owner. */
    val stampEmail: String by lazy { email("stamp") }

    /** A card of the stamp program (its owner's address is the suite's own). */
    val stampCard: String by lazy { newCard(stampProgramId, stampEmail) }

    /** A gift card of 50.00. */
    val giftCard: String by lazy { newCard(giftProgramId, email("gift"), faceMinor = 5000) }

    /** A new card for a new (example.com) customer of this run. */
    fun newCard(programId: String, address: String = email("card"), faceMinor: Int? = null): String =
        rewloy.issuePass(IssuePassBody(programId).apply {
            email = address; firstName = "Kt"; kvkkConsent = true
            if (faceMinor != null) this.faceMinor = faceMinor
        }).serial

    /** A gift card code (batch) with room for a few cards. */
    fun newBatch(programId: String = giftProgramId, configure: CreateBatchBody.() -> Unit = {}): CreateBatchData {
        // No clean-up of its own: archiving the program at the end closes its codes.
        return rewloy.createBatch(programId, CreateBatchBody().apply { name = name("code"); valueMinor = 2500; capacity = 3; configure() })
    }
}
