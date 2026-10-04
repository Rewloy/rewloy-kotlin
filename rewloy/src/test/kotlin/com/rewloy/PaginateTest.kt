package com.rewloy

import com.rewloy.models.ListCustomersQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaginateTest {
    private fun pages(rig: Rig, total: Int, size: Int) {
        rig.server.script = { r, _ ->
            val page = Regex("page=(\\d+)").find(r.query)?.groupValues?.get(1)?.toInt() ?: 1
            val from = (page - 1) * size
            val count = maxOf(0, minOf(size, total - from))
            Answer(200, Fixtures.customers(from + 1, count, page, size, total))
        }
    }

    @Test
    fun `a page is a list with its meta`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 5, 2)
        val page = rig.rewloy.listCustomers(ListCustomersQuery(page = 2, limit = 2))
        assertEquals(listOf("Ayşe 3", "Ayşe 4"), page.data.map { it.displayName })
        assertEquals(2L, page.meta.page)
        assertEquals(5L, page.meta.total)
        assertEquals(2L, page.meta.pageSize)
        assertEquals("/v1/customers?page=2&limit=2", rig.server.received.single().target)
    }

    @Test
    fun `walks every page and stops after the last`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 5, 2)
        val all = rig.rewloy.listCustomersAll(ListCustomersQuery(limit = 2)).map { it.personId }
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), all)
        assertEquals(3, rig.server.received.size)
        assertEquals(listOf("page=1&limit=2", "page=2&limit=2", "page=3&limit=2"), rig.server.received.map { it.query })
    }

    @Test
    fun `does not ask past a full last page`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 4, 2)
        assertEquals(4, rig.rewloy.listCustomersAll(ListCustomersQuery(limit = 2)).count())
        assertEquals(2, rig.server.received.size)
    }

    @Test
    fun `an empty list is one request`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 0, 2)
        assertEquals(0, rig.rewloy.listCustomersAll().count())
        assertEquals(1, rig.server.received.size)
        assertEquals("/v1/customers?page=1", rig.server.received.single().target)
    }

    @Test
    fun `starts where the query says and leaves the query alone`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 7, 2)
        val query = ListCustomersQuery(page = 3, limit = 2, consent = "yes")
        assertEquals(listOf("p5", "p6", "p7"), rig.rewloy.listCustomersAll(query).map { it.personId })
        assertEquals(3, query.page)
        assertEquals("/v1/customers?consent=yes&page=3&limit=2", rig.server.received[0].target)
    }

    @Test
    fun `is lazy, and each iteration starts again`() = Rig { apiKey("rwk_abc") }.test { rig ->
        pages(rig, 6, 2)
        val walk = rig.rewloy.listCustomersAll(ListCustomersQuery(limit = 2))
        assertTrue(rig.server.received.isEmpty())
        assertEquals("p1", walk.first().personId)
        assertEquals(1, rig.server.received.size)
        assertEquals(6, walk.count())
        assertEquals(1 + 3, rig.server.received.size)
    }

    @Test
    fun `an error on a later page comes out of the iteration`() = Rig { apiKey("rwk_abc"); maxRetries(0) }.test { rig ->
        rig.server.script = { r, _ -> if (r.query.contains("page=2")) error("INTERNAL", status = 500) else Answer(200, Fixtures.customers(1, 2, 1, 2, 9)) }
        val seen = ArrayList<String>()
        assertFailsWith<RewloyException> { for (c in rig.rewloy.listCustomersAll(ListCustomersQuery(limit = 2))) seen.add(c.personId) }
        assertEquals(listOf("p1", "p2"), seen)
    }
}
