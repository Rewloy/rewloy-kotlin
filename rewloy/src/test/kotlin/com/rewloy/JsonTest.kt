package com.rewloy

import com.rewloy.json.JsonArray
import com.rewloy.json.JsonObject
import com.rewloy.json.JsonParseException
import com.rewloy.json.JsonValue
import com.rewloy.json.JsonWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun `reads and writes every kind of value`() {
        val text = """{"a":[1,2.5,-3e2,"x",true,false,null],"b":{},"c":[],"d":"é\n\"\\/"}"""
        val v = JsonValue.parse(text)
        assertEquals("""{"a":[1,2.5,-3e2,"x",true,false,null],"b":{},"c":[],"d":"é\n\"\\/"}""", v.toString())
        val o = v as JsonObject
        assertEquals(listOf("a", "b", "c", "d"), o.members.keys.toList())
        assertEquals(7, (o["a"] as JsonArray).size)
        assertEquals(1L, o["a"]!!.asArray()!![0]!!.asLong())
        assertEquals(2.5, o["a"]!!.asArray()!![1]!!.asDouble())
        assertNull(o["a"]!!.asArray()!![1]!!.asLong())
        assertEquals("é\n\"\\/", o["d"]!!.asString())
    }

    @Test
    fun `numbers keep their text`() {
        assertEquals("12345678901234567890", JsonValue.parse("12345678901234567890").toString())
        assertNull(JsonValue.parse("12345678901234567890").asLong())
        assertEquals("1.0", JsonValue.parse("1.0").toString())
    }

    @Test
    fun `resolves unicode escapes and surrogate pairs`() {
        assertEquals("A\u00e9\ud83d\ude00", JsonValue.parse("\"\\u0041\\u00e9\\ud83d\\ude00\"").asString())
        // A pair is written raw, a lone surrogate escaped (as JSON.stringify does).
        assertEquals("\"\ud83d\ude00\"", JsonValue.of("\ud83d\ude00").toString())
        assertEquals("\"\\ud83d\"", JsonValue.of("\ud83d").toString())
        assertEquals("\"\\u0001\\u001f\"", JsonValue.of("\u0001\u001f").toString())
    }

    @Test
    fun `refuses what is not JSON`() {
        for (bad in listOf("", " ", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "01", "1.", "-", "1e", "+1", "nul", "\"abc", "\"\\x\"", "\"a\nb\"", "[1] x", "{\"a\" 1}", "\"\\u12\"")) {
            assertFailsWith<JsonParseException>(bad) { JsonValue.parse(bad) }
        }
    }

    @Test
    fun `refuses nesting that would overflow the stack`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertFailsWith<JsonParseException> { JsonValue.parse(deep) }
        val ok = "[".repeat(500) + "]".repeat(500)
        JsonValue.parse(ok)
    }

    @Test
    fun `reads bytes with a byte order mark`() {
        assertEquals("1", JsonValue.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '1'.code.toByte())).toString())
        assertEquals("ğ", JsonValue.parse("\"ğ\"".toByteArray(Charsets.UTF_8)).asString())
    }

    @Test
    fun `values compare by content`() {
        assertEquals(JsonValue.parse("""{"a":[1,{"b":null}]}"""), JsonValue.parse("""{ "a" : [1, {"b":null}] }"""))
        assertTrue(JsonValue.parse("null").isNull)
    }

    @Test
    fun `the OpenAPI snapshot reads and writes back byte for byte`() {
        val file = File("../openapi/openapi.json")
        val text = file.readText(Charsets.UTF_8)
        assertEquals(text, JsonWriter.stringifyPretty(JsonValue.parse(text)) + "\n")
    }
}
