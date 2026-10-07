package com.rewloy.live

import org.junit.jupiter.api.ClassOrderer
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
@Order(1)
@DisplayName("guard")
class GuardLiveTest {
    @Test
    @Order(1)
    fun `the key is a test key`() {
        assertTrue(Live.apiKey!!.startsWith(Live.TEST_KEY_PREFIX))
        assertEquals(com.rewloy.CredentialKind.KEY, Live.rewloy.credential)
    }

    @Test
    @Order(2)
    fun `the server says it is a dev Rewloy`() {
        val meta = Live.anonymous().getMeta()
        assertEquals("dev", meta.environment)
    }

    @Test
    @Order(3)
    fun `the key acts in a test business`() {
        val r = Live.rewloy.getBusinessWithResponse()
        assertTrue(r.isTestMode, "Rewloy-Mode: ${r.mode}")
        assertEquals("test", r.mode)
        assertTrue(r.data.name.endsWith("· Test"), r.data.name)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(2)
@DisplayName("meta and business")
class MetaBusinessLiveTest {
    @Test
    @Order(1)
    fun `getMeta names the version and the API version`() {
        val r = Live.anonymous().getMetaWithResponse()
        assertEquals(200, r.statusCode)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(r.data.version), r.data.version)
        assertEquals("v1", r.data.apiVersion)
        assertNotNull(r.requestId)
    }

    @Test
    @Order(2)
    fun `getBusiness is the test business`() {
        val b = Live.rewloy.getBusiness()
        assertTrue(b.id.isNotBlank())
        assertTrue(b.currency.length == 3, b.currency)
        assertTrue(b.timezone.isNotBlank())
    }

    @Test
    @Order(3)
    fun `the test business has a branch`() {
        val branches = Live.rewloy.listLocations()
        assertTrue(branches.isNotEmpty())
        assertEquals(Live.locationId, branches.first { !it.archived }.id)
    }
}

@ExtendWith(LiveGuard::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(3)
@DisplayName("programs")
class ProgramsLiveTest {
    @Test
    @Order(1)
    fun `create a stamp program`() {
        val p = Live.rewloy.getProgram(Live.stampProgramId)
        assertEquals("stamp", p.type)
        assertEquals("active", p.status)
        assertEquals(Live.name("stamp"), p.programName)
    }

    @Test
    @Order(2)
    fun `create a gift card program`() {
        val p = Live.rewloy.getProgram(Live.giftProgramId)
        assertEquals("giftcard", p.type)
        assertEquals("active", p.status)
    }

    @Test
    @Order(3)
    fun `list programs finds both, and filters by type`() {
        val all = Live.rewloy.listPrograms()
        assertTrue(all.any { it.id == Live.stampProgramId })
        assertTrue(all.any { it.id == Live.giftProgramId })
        val gifts = Live.rewloy.listPrograms(com.rewloy.models.ListProgramsQuery().apply { type = "giftcard" })
        assertTrue(gifts.isNotEmpty() && gifts.all { it.type == "giftcard" })
        assertTrue(gifts.any { it.id == Live.giftProgramId })
        assertFalse(gifts.any { it.id == Live.stampProgramId })
    }
}
