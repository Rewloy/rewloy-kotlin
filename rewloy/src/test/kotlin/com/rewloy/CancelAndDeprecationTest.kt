package com.rewloy

import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CancelAndDeprecationTest {
    @Test
    fun `cancelling aborts a request that is waiting for the answer`() = Rig { apiKey("rwk_abc") }.test { rig ->
        rig.server.enqueue(Answer(200, Fixtures.PASS, delayMs = 5000))
        val cancel = CancelToken()
        thread { Thread.sleep(150); cancel.cancel() }
        val start = System.nanoTime()
        assertFailsWith<CancellationException> { rig.rewloy.getPass("S", RequestOptions(cancel = cancel)) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4000)
        assertEquals(1, rig.server.received.size)
    }

    @Test
    fun `a token cancelled before the call stops it before anything is sent`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val cancel = CancelToken().also { it.cancel() }
        assertFailsWith<CancellationException> { rig.rewloy.getPass("S", RequestOptions(cancel = cancel)) }
        assertTrue(rig.server.received.isEmpty())
    }

    @Test
    fun `cancelling stops a retry that is waiting`() {
        val cancel = CancelToken()
        Rig { apiKey("rwk_abc"); sleeper { _, token -> cancel.cancel(); token.sleep(10_000) } }.test { rig ->
            rig.server.enqueue(Answer(503, "x"))
            assertFailsWith<CancellationException> { rig.rewloy.getPass("S", RequestOptions(cancel = cancel)) }
            assertEquals(1, rig.server.received.size)
        }
    }

    @Test
    fun `a client made with withCancel is cancelled by its token, and shares the settings`() = Rig { apiKey("rwk_abc") }.test { rig ->
        val token = CancelToken()
        val scoped = rig.rewloy.withCancel(token)
        rig.server.enqueue(Answer(200, Fixtures.PASS))
        assertEquals("ABCD-EFGH-JKLM", scoped.getPass("S").serial)
        token.cancel()
        assertFailsWith<CancellationException> { scoped.getPass("S") }
        // The original is not affected.
        assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
        assertEquals("Bearer rwk_abc", rig.server.received[0].header("authorization"))
    }

    @Test
    fun `an interrupted wait is a cancellation and keeps the flag`() {
        val token = CancelToken()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { token.sleep(1000) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancel callbacks run once, at once if late, and can be withdrawn`() {
        val token = CancelToken()
        val ran = CopyOnWriteArrayList<String>()
        token.onCancel { ran.add("a") }
        token.onCancel { ran.add("b") }.close()
        token.onCancel { throw IllegalStateException("one failing callback") }
        token.onCancel { ran.add("c") }
        token.cancel()
        token.cancel()
        assertEquals(listOf("a", "c"), ran)
        token.onCancel { ran.add("late") }
        assertEquals(listOf("a", "c", "late"), ran)
    }

    // ---------------------------------------------------------------- deprecations

    private val deprecated = mapOf(
        "Deprecation" to "@1790000000", "Sunset" to "Mon, 05 Apr 2027 00:00:00 GMT",
        "Link" to "<https://example.com/other>; rel=\"alternate\", <https://rewloy.com/gelistiriciler/degisiklikler#getPass>; rel=\"deprecation\"",
    )

    @Test
    fun `calls the listener once per operation, with the sunset and the link`() {
        val notices = CopyOnWriteArrayList<DeprecationNotice>()
        Rig { apiKey("rwk_abc"); deprecationListener { notices.add(it) } }.test { rig ->
            rig.server.enqueue(Answer(200, Fixtures.PASS, deprecated))
            rig.rewloy.getPass("S")
            rig.rewloy.getPass("S")
            assertEquals(1, notices.size)
            val n = notices[0]
            assertEquals("getPass", n.operation)
            assertEquals("GET", n.method)
            assertEquals("/v1/passes/{serial}", n.path)
            assertEquals("Mon, 05 Apr 2027 00:00:00 GMT", n.sunset)
            assertEquals("https://rewloy.com/gelistiriciler/degisiklikler#getPass", n.link)
            assertTrue(n.message.contains("getPass") && n.message.contains("Sunset") && n.message.contains("degisiklikler"))
        }
    }

    @Test
    fun `warns on an error answer too, and a listener that throws does not fail the call`() {
        Rig { apiKey("rwk_abc"); maxRetries(0); deprecationListener { throw IllegalStateException("boom") } }.test { rig ->
            rig.server.enqueue(Answer(200, Fixtures.PASS, deprecated))
            assertEquals("ABCD-EFGH-JKLM", rig.rewloy.getPass("S").serial)
        }
        val notices = ArrayList<DeprecationNotice>()
        Rig { apiKey("rwk_abc"); maxRetries(0); deprecationListener { notices.add(it) } }.test { rig ->
            rig.server.enqueue(Answer(404, """{"error":{"code":"NOT_FOUND","message":"x"}}""", deprecated))
            assertFailsWith<RewloyException> { rig.rewloy.getPass("S") }
        }
        assertEquals(1, notices.size)
    }

    @Test
    fun `says nothing for an operation that is not deprecated`() {
        val notices = ArrayList<DeprecationNotice>()
        Rig { apiKey("rwk_abc"); deprecationListener { notices.add(it) } }.test { rig ->
            rig.server.enqueue(Answer(200, Fixtures.PASS))
            rig.rewloy.getPass("S")
        }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `without a listener the notice goes to the com_rewloy logger once per operation`() {
        Core.resetLogged()
        val logger = Logger.getLogger("com.rewloy")
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            Rig { apiKey("rwk_abc") }.test { rig ->
                rig.server.enqueue(Answer(200, Fixtures.PASS, deprecated))
                rig.rewloy.getPass("S")
                rig.rewloy.getPass("S")
            }
            Rig { apiKey("rwk_abc") }.test { rig ->
                rig.server.enqueue(Answer(200, Fixtures.PASS, deprecated))
                rig.rewloy.getPass("S")
            }
        } finally {
            logger.removeHandler(handler)
        }
        assertEquals(1, records.size)
        assertEquals(java.util.logging.Level.WARNING, records[0].level)
        assertTrue(records[0].message.contains("getPass"))
        assertNull(null)
    }
}
