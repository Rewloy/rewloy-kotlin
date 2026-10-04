package com.rewloy

import com.rewloy.json.JsonObject
import com.rewloy.json.JsonValue
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated client against the document it was generated from, and the pieces that must agree. */
class MetadataTest {
    private val document = JsonValue.parse(File("../openapi/openapi.json").readText(Charsets.UTF_8)) as JsonObject
    private val operations: List<Triple<String, String, String>> = (document["paths"] as JsonObject).members.flatMap { (path, item) ->
        (item as JsonObject).members.filter { it.key in listOf("get", "post", "put", "patch", "delete") }.map { (method, op) ->
            Triple((op as JsonObject)["operationId"]!!.asString()!!, method.uppercase(), path)
        }
    }

    @Test
    fun `every operationId of the document is a method and a row of the table`() {
        val methods = Rewloy::class.java.methods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet()
        assertTrue(operations.size > 200)
        assertEquals(operations.size, RewloyOperations.all.size)
        for ((id, method, path) in operations) {
            assertTrue(id in methods, "no method $id")
            val info = RewloyOperations.all[id]!!
            assertEquals(method, info.method, id)
            assertEquals(path, info.path, id)
            assertTrue(info.isStream || "${id}WithResponse" in methods, "no ${id}WithResponse")
            if (info.isPaged) assertTrue("${id}All" in methods, "no ${id}All")
        }
        assertEquals((document["info"] as JsonObject)["version"]!!.asString(), RewloyOperations.API_VERSION)
    }

    @Test
    fun `the version is the same in the build, the library and the changelog`() {
        val build = File("../build.gradle.kts").readText()
        val version = Regex("version = \"([^\"]+)\"").find(build)!!.groupValues[1]
        assertEquals(version, RewloyVersion.CURRENT)
        assertTrue(File("../CHANGELOG.md").readText().contains("## $version"))
    }
}
