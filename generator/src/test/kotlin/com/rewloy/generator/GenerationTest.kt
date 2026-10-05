package com.rewloy.generator

import com.rewloy.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerationTest {
    private val root = File("..").canonicalFile
    private val snapshotText = File(root, "openapi/openapi.json").readText(Charsets.UTF_8)
    private val snapshot = JsonValue.parse(snapshotText)

    @Test
    fun `is deterministic`() {
        val a = ApiGenerator.generate(snapshot)
        val b = ApiGenerator.generate(JsonValue.parse(snapshotText))
        assertEquals(a.map { it.path }, b.map { it.path })
        assertEquals(a.map { it.content }, b.map { it.content })
    }

    @Test
    fun `the committed generated code is what the snapshot generates`() {
        val files = ApiGenerator.generate(snapshot)
        for (f in files) {
            val committed = File(root, f.path)
            assertTrue(committed.isFile, "${f.path} is missing: run ./gradlew generate -Pfile=openapi/openapi.json")
            assertEquals(f.content, committed.readText(Charsets.UTF_8), "${f.path} is out of date: run ./gradlew generate -Pfile=openapi/openapi.json")
        }
        val produced = files.map { File(root, it.path).canonicalPath }.toSet()
        val extra = File(root, "rewloy/src/main/kotlin/com/rewloy/generated").walkTopDown().filter { it.isFile && it.canonicalPath !in produced }.toList()
        assertTrue(extra.isEmpty(), "files nobody generates: $extra")
    }

    @Test
    fun `the snapshot is kept the way JSON_stringify writes it`() {
        assertEquals(snapshotText, snapshotText(snapshot))
    }

    @Test
    fun `makes one method per operation of the document`() {
        val api = ApiGenerator.generate(snapshot).first { it.path.endsWith("RewloyApi.kt") }.content
        val count = ApiGenerator.countOperations(snapshot)
        assertTrue(count > 200)
        val plain = Regex("^    public fun (\\w+)\\(", RegexOption.MULTILINE).findAll(api).map { it.groupValues[1] }.toList()
        assertEquals(plain.size, plain.toSet().size)
        val primary = plain.filter { !it.endsWith("WithResponse") && !it.endsWith("All") }
        assertEquals(count, primary.size)
    }

    @Test
    fun `names are camelCase and safe`() {
        assertEquals("programId", Naming.camel("programId"))
        assertEquals("programId", Naming.camel("program_id"))
        assertEquals("urlValue", Naming.camel("URLValue"))
        assertEquals("id", Naming.camel("ID"))
        assertEquals("_2fa", Naming.camel("2fa"))
        assertEquals("kvkkConsent", Naming.camel("kvkk-consent"))
        assertEquals("KvkkConsent", Naming.pascal("kvkk-consent"))
        assertEquals("`in`", Naming.ident("in"))
        assertEquals("KasaKampanyalari", Naming.fileStem("Kasa kampanyaları"))
        assertEquals("Isletme", Naming.fileStem("İşletme"))
        assertEquals("\"a\\\$b\\n\\\"\"", Naming.literal("a\$b\n\""))
        assertTrue(!Naming.kdocSafe("a */ b /* c").contains("*/") && !Naming.kdocSafe("a */ b /* c").contains("/*"))
    }

    @Test
    fun `parses the platform's deprecation sentence`() {
        assertEquals(Pair("2027-04-01", "listCustomersV2"), ApiGenerator.parseDeprecation("**Kullanımdan kalkıyor:** 1 Nisan 2027 tarihine kadar çalışır; yerine `listCustomersV2`."))
        assertEquals(Pair(null, null), ApiGenerator.parseDeprecation("nothing"))
    }

    // ---------------------------------------------------------------- a fixture of what the live document does not have

    private fun doc(paths: String, components: String = "{}"): JsonValue = JsonValue.parse(
        """{"openapi":"3.1.0","info":{"version":"9.9.9"},"components":{"schemas":$components},"paths":$paths}""",
    )

    private fun op(id: String, method: String = "get", extra: String = "", responses: String = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}}}}}}}""") =
        """"$method":{"operationId":"$id","tags":["T"],"x-credentials":["key"],"responses":$responses${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun generated(paths: String, components: String = "{}") = ApiGenerator.generate(doc(paths, components)).joinToString("\n") { it.content }

    @Test
    fun `generates what the live document has not got`() {
        val code = generated(
            """{
              "/v1/a/{id}":{${op("getA", extra = """"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","minimum":0,"maximum":10}}]""")}},
              "/v1/b":{${op("setB", "post", """"requestBody":{"required":true,"content":{"application/json":{"schema":{"type":"object","required":["name"],"properties":{"name":{"type":"string"},"nick":{"type":["string","null"]},"tags":{"type":"array","items":{"type":"string"}},"by":{"type":"object","additionalProperties":{"type":"integer"}},"any":{"type":"object"},"class":{"type":"string"},"in":{"type":"boolean"},"isOn":{"type":"boolean"},"big":{"type":"integer"},"price":{"type":"number"}}}}}}""")}}
            }""",
        )
        assertTrue("public fun getA(id: Int, options: RequestOptions? = null)" in code, "int path parameter")
        assertTrue("arrayOf(id.toString())" in code)
        assertTrue("public var name: String," in code, "required first, without a default")
        assertTrue("public var nick: OptionalField<String>? = null" in code, "explicit null")
        assertTrue("public var tags: List<String>? = null" in code)
        assertTrue("public var by: Map<String, Long>? = null" in code)
        assertTrue("public var any: JsonValue? = null" in code)
        assertTrue("public var classValue: String? = null" in code, "getClass is taken")
        assertTrue("public var `in`: Boolean? = null" in code, "keyword")
        assertTrue("public var big: Long? = null" in code && "public var price: Double? = null" in code)
        assertTrue("w.optional(\"nick\", this.nick)" in code)
        assertTrue("public constructor(name: String) : this(" in code, "the required fields only, for Java")
    }

    @Test
    fun `a union of objects is a sealed class with a class per shape`() {
        val answer = """{"oneOf":[
            {"type":"object","title":"A","properties":{"ok":{"type":"boolean"},"balance":{"type":"number"},"kind":{"const":"a"}},"required":["ok","balance","kind"]},
            {"type":"object","properties":{"ok":{"type":"boolean"},"uses":{"type":"integer"},"kind":{"const":"b"}},"required":["ok","uses","kind"]}]}"""
        val code = generated("""{"/v1/a":{${op("getA", responses = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":$answer}}}}}}""")}}}""")
        assertTrue("public fun getA(options: RequestOptions? = null): GetADataA =" !in code)
        assertTrue("public fun getA(options: RequestOptions? = null): GetAData =" in code)
        assertTrue("public sealed class GetAData : RewloyObject() {" in code)
        assertTrue("public abstract val ok: Boolean" in code, "what every shape has is on the sealed class")
        assertTrue("public class GetADataA(" in code && "public class GetADataB(" in code, "named after the const property")
        assertTrue(") : GetAData() {" in code)
        assertTrue("public override val ok: Boolean," in code)
        assertTrue("Wire.Shape(listOf(\"ok\", \"balance\", \"kind\"), listOf(\"ok\", \"balance\", \"kind\"), \"kind\", \"a\")" in code)
        // Members that are not all objects stay a JsonValue.
        val mixed = generated("""{"/v1/a":{${op("getA", responses = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":{"oneOf":[{"type":"object","properties":{"x":{"type":"string"}}},{"type":"array","items":{"type":"string"}}]}}}}}}}""")}}}""")
        assertTrue("public fun getA(options: RequestOptions? = null): JsonValue =" in mixed)
    }

    @Test
    fun `reads the platform's structured deprecation`() {
        val code = generated(
            """{"/v1/a":{${op("oldA", extra = """"deprecated":true,"x-deprecation":{"sunset":"2027-04-05","use":"newA"}""")}},"/v1/b":{${op("newA")}}}""",
        )
        assertTrue("@Deprecated(\"oldA is deprecated; the API stops answering it after 2027-04-05. Use newA instead.\")" in code)
        assertTrue("Deprecation(\"2027-04-05\", \"newA\")" in code)
    }

    @Test
    fun `refuses what it does not understand`() {
        fun refuses(text: String, vararg parts: String) {
            val e = assertFailsWith<GeneratorException> { ApiGenerator.generate(doc(text)) }
            for (p in parts) assertTrue(p in e.message!!, "${e.message} lacks $p")
        }
        refuses("""{"/v1/a":{${op("a")}},"/v1/b":{${op("a")}}}""", "used twice")
        refuses("""{"/v1/a":{${op("Bad")}}}""", "not camelCase")
        refuses("""{"/v1/a":{${op("withCancel")}}}""", "collides")
        refuses("""{"/v1/a":{${op("a", extra = """"parameters":[{"name":"X-Other","in":"header","schema":{"type":"string"}}]""")}}}""", "X-Other")
        refuses("""{"/v1/a":{${op("a", "post", """"requestBody":{"content":{"text/plain":{"schema":{"type":"string"}}}}""")}}}""", "application/json")
        refuses("""{"/v1/a":{${op("a", "post", """"requestBody":{"content":{"application/json":{"schema":{"allOf":[{"type":"object"}]}}}}""")}}}""", "allOf")
        refuses("""{"/v1/a":{${op("a", responses = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":{"${'$'}ref":"#/components/schemas/Nope"}}}}}}}""")}}}""", "unsupported")
        refuses("""{"/v1/a":{${op("a", extra = """"parameters":[{"name":"page","in":"query","schema":{"type":"integer"}}]""", responses = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":{"type":"object"},"meta":{"type":"object"}}}}}}}""")}}}""", "not an array")
        refuses("""{"/v1/a":{${op("a", responses = """{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"data":{"type":"array","items":{"type":"object"}},"meta":{"type":"object"}}}}}}}""")}}}""", "no page parameter")
        refuses("""{"/v1/a/{x}":{${op("a")}}}""", "placeholders")
        refuses("""{"/v1/a":{${op("a", responses = """{"400":{}}""")}}}""", "no 2xx")
        refuses("""{}""", "no operations")
        assertFailsWith<GeneratorException> { ApiGenerator.generate(JsonValue.parse("""{"openapi":"2.0","paths":{}}""")) }
        assertFailsWith<GeneratorException> { ApiGenerator.generate(JsonValue.parse("[]")) }
    }
}
