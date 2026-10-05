package com.rewloy

import com.rewloy.json.JsonArray
import com.rewloy.json.JsonBoolean
import com.rewloy.json.JsonNull
import com.rewloy.json.JsonNumber
import com.rewloy.json.JsonObject
import com.rewloy.json.JsonString
import com.rewloy.json.JsonValue

/** An answer that does not fit the type the OpenAPI document gives it; the client turns it into `INVALID_RESPONSE`. */
internal class ResponseShapeException(message: String) : RuntimeException(message)

private fun kind(v: JsonValue): String = when (v) {
    is JsonNull -> "null"
    is JsonBoolean -> "a boolean"
    is JsonNumber -> "a number"
    is JsonString -> "a string"
    is JsonArray -> "an array"
    is JsonObject -> "an object"
}

/**
 * What the generated models read an answer's JSON with: strict about the types the document gives (a required
 * field that is missing, or of another type, is an error naming its path), lenient about everything else (a field
 * nobody declared is kept in `additionalProperties`; a `null` for an optional field is the same as leaving it out).
 */
internal object Wire {
    /** One shape of a union of objects (a `oneOf`): the fields it requires, the fields it knows, a `const` string field. */
    class Shape(val required: List<String>, val known: List<String>, val tagName: String?, val tagValue: String?)

    /**
     * Which [shapes] an answer has: of those whose required fields are all present (a field that is `null` counts) and
     * whose `const` field matches, the one with the most known fields in the answer; the first on a tie.
     */
    fun pickShape(v: JsonValue, path: String, shapes: List<Shape>): Int {
        val members = (v as? JsonObject)?.members ?: throw shape(path, "an object", v)
        var best = -1
        var bestScore = -1
        for ((i, s) in shapes.withIndex()) {
            if (!s.required.all { it in members }) continue
            if (s.tagName != null && (members[s.tagName] as? JsonString)?.value != s.tagValue) continue
            val score = s.known.count { it in members }
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        if (best < 0) throw ResponseShapeException("$path: the answer has none of the ${shapes.size} shapes the API documents")
        return best
    }

    fun shape(path: String, expected: String, v: JsonValue?): ResponseShapeException =
        ResponseShapeException("$path: expected $expected" + (if (v == null) ", the field is missing" else ", got ${kind(v)}"))

    fun string(v: JsonValue, path: String): String = (v as? JsonString)?.value ?: throw shape(path, "a string", v)

    fun int(v: JsonValue, path: String): Int = v.asInt() ?: throw shape(path, "an integer", v)

    fun long(v: JsonValue, path: String): Long = v.asLong() ?: throw shape(path, "an integer", v)

    fun double(v: JsonValue, path: String): Double = v.asDouble() ?: throw shape(path, "a number", v)

    fun bool(v: JsonValue, path: String): Boolean = v.asBoolean() ?: throw shape(path, "a boolean", v)

    fun json(v: JsonValue, @Suppress("UNUSED_PARAMETER") path: String): JsonValue = v

    fun <T> list(v: JsonValue, path: String, read: (JsonValue, String) -> T): List<T> {
        val items = (v as? JsonArray)?.items ?: throw shape(path, "an array", v)
        val out = ArrayList<T>(items.size)
        for ((i, item) in items.withIndex()) out.add(read(item, "$path[$i]"))
        return out
    }

    fun <T> map(v: JsonValue, path: String, read: (JsonValue, String) -> T): Map<String, T> {
        val members = (v as? JsonObject)?.members ?: throw shape(path, "an object", v)
        val out = LinkedHashMap<String, T>(members.size * 2)
        for ((name, member) in members) out[name] = read(member, "$path.$name")
        return out
    }

    /** For the elements of a list or map that may be `null`. */
    fun <T : Any> nullable(v: JsonValue, path: String, read: (JsonValue, String) -> T): T? =
        if (v === JsonNull) null else read(v, path)
}

/** Reads the members of one JSON object for a generated model; see [Wire]. */
internal class ObjectReader(value: JsonValue, private val path: String) {
    private val members: Map<String, JsonValue> =
        (value as? JsonObject)?.members ?: throw Wire.shape(path, "an object", value)
    private val seen = HashSet<String>()

    private fun at(name: String) = "$path.$name"

    private fun raw(name: String): JsonValue? {
        seen.add(name)
        val v = members[name]
        return if (v == null || v === JsonNull) null else v
    }

    private fun need(name: String): JsonValue = raw(name) ?: throw Wire.shape(at(name), "a value", null)

    fun str(name: String): String = Wire.string(need(name), at(name))
    fun strOrNull(name: String): String? = raw(name)?.let { Wire.string(it, at(name)) }
    fun int(name: String): Int = Wire.int(need(name), at(name))
    fun intOrNull(name: String): Int? = raw(name)?.let { Wire.int(it, at(name)) }
    fun long(name: String): Long = Wire.long(need(name), at(name))
    fun longOrNull(name: String): Long? = raw(name)?.let { Wire.long(it, at(name)) }
    fun double(name: String): Double = Wire.double(need(name), at(name))
    fun doubleOrNull(name: String): Double? = raw(name)?.let { Wire.double(it, at(name)) }
    fun bool(name: String): Boolean = Wire.bool(need(name), at(name))
    fun boolOrNull(name: String): Boolean? = raw(name)?.let { Wire.bool(it, at(name)) }

    /** A field of any shape. Absent counts as JSON `null`. */
    fun json(name: String): JsonValue {
        seen.add(name)
        return members[name] ?: JsonNull
    }

    /** A field of any shape, or `null` when it is absent. */
    fun jsonOrNull(name: String): JsonValue? {
        seen.add(name)
        return members[name]
    }

    fun <T : Any> req(name: String, read: (JsonValue, String) -> T): T = read(need(name), at(name))
    fun <T : Any> opt(name: String, read: (JsonValue, String) -> T): T? = raw(name)?.let { read(it, at(name)) }

    /** The members nothing read: fields the API added. */
    fun rest(): Map<String, JsonValue> {
        var out: LinkedHashMap<String, JsonValue>? = null
        for ((name, v) in members) {
            if (name in seen) continue
            if (out == null) out = LinkedHashMap()
            out[name] = v
        }
        return out ?: emptyMap()
    }
}

/** Writes the members of one request object for a generated model; `null` properties are left out. */
internal class ObjectWriter {
    private val members = LinkedHashMap<String, JsonValue>()

    fun str(name: String, v: String?) { if (v != null) members[name] = JsonString(v) }
    fun int(name: String, v: Int?) { if (v != null) members[name] = JsonNumber(v.toString()) }
    fun long(name: String, v: Long?) { if (v != null) members[name] = JsonNumber(v.toString()) }
    fun double(name: String, v: Double?) { if (v != null) members[name] = JsonValue.of(v) }
    fun bool(name: String, v: Boolean?) { if (v != null) members[name] = JsonValue.of(v) }
    fun json(name: String, v: JsonValue?) { if (v != null) members[name] = v }
    fun obj(name: String, v: RewloyObject?) { if (v != null) members[name] = v.toJsonValue() }

    fun <T> list(name: String, v: List<T>?, f: (T) -> JsonValue) {
        if (v != null) members[name] = Out.list(v, f)
    }

    fun <T> map(name: String, v: Map<String, T>?, f: (T) -> JsonValue) {
        if (v != null) members[name] = Out.map(v, f)
    }

    fun <T : Any> optional(name: String, v: OptionalField<T>?, f: (T) -> JsonValue) {
        if (v != null) members[name] = v.value?.let(f) ?: JsonNull
    }

    fun finish(extra: Map<String, JsonValue>): JsonObject {
        for ((k, v) in extra) if (!members.containsKey(k)) members[k] = v
        return JsonObject(members)
    }
}

/** Builders for the values inside lists and maps of a request. */
internal object Out {
    fun str(v: String): JsonValue = JsonString(v)
    fun int(v: Int): JsonValue = JsonNumber(v.toString())
    fun long(v: Long): JsonValue = JsonNumber(v.toString())
    fun double(v: Double): JsonValue = JsonValue.of(v)
    fun bool(v: Boolean): JsonValue = JsonValue.of(v)
    fun obj(v: RewloyObject): JsonValue = v.toJsonValue()

    fun <T> list(v: List<T>, f: (T) -> JsonValue): JsonValue {
        val out = ArrayList<JsonValue>(v.size)
        for (x in v) out.add(f(x))
        return JsonArray(out)
    }

    fun <T> map(v: Map<String, T>, f: (T) -> JsonValue): JsonValue {
        val out = LinkedHashMap<String, JsonValue>(v.size * 2)
        for ((k, x) in v) out[k] = f(x)
        return JsonObject(out)
    }

    fun <T : Any> nullable(v: T?, f: (T) -> JsonValue): JsonValue = if (v == null) JsonNull else f(v)
}
