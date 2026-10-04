package com.rewloy.json

/**
 * A JSON value. The library has its own small JSON reader and writer so that it needs nothing but the Kotlin
 * standard library; this is its tree.
 *
 * It appears in the API wherever the OpenAPI document gives no fixed shape: a free-form object, a field that can be
 * one of several types, the body of a webhook. Read it with [asString], [asLong] and the other `as…` functions,
 * which answer `null` when the value is of another kind, or with `get` on an object or an array.
 *
 * ```kotlin
 * val locations: JsonValue = grant.locations      // "all" or a list of ids
 * if (locations.asString() == "all") { … } else locations.asArray()?.items
 * ```
 *
 * Values are immutable and compare by content (numbers by their text: `1` and `1.0` differ).
 */
public sealed class JsonValue {
    /** The text if this is a JSON string, else `null`. */
    public open fun asString(): String? = null

    /** `true` or `false` if this is a JSON boolean, else `null`. */
    public open fun asBoolean(): Boolean? = null

    /** The number as a `Long` if this is a JSON number that is a whole number in the `Long` range, else `null`. */
    public open fun asLong(): Long? = null

    /** The number as an `Int` if this is a JSON number that is a whole number in the `Int` range, else `null`. */
    public open fun asInt(): Int? = null

    /** The number as a `Double` if this is a JSON number, else `null`. */
    public open fun asDouble(): Double? = null

    /** This as an object, or `null`. */
    public open fun asObject(): JsonObject? = null

    /** This as an array, or `null`. */
    public open fun asArray(): JsonArray? = null

    /** `true` for JSON `null`. */
    public val isNull: Boolean get() = this === JsonNull

    /** This value as compact JSON text. */
    override fun toString(): String = JsonWriter.stringify(this)

    public companion object {
        /** Reads JSON text. @throws JsonParseException when the text is not valid JSON. */
        @JvmStatic
        public fun parse(text: String): JsonValue = JsonParser(text).parse()

        /** Reads JSON from UTF-8 bytes. @throws JsonParseException when the bytes are not valid JSON. */
        @JvmStatic
        public fun parse(bytes: ByteArray): JsonValue = JsonParser(decodeUtf8(bytes)).parse()

        /** A JSON string. */
        @JvmStatic
        public fun of(value: String): JsonValue = JsonString(value)

        /** A JSON number. */
        @JvmStatic
        public fun of(value: Long): JsonValue = JsonNumber(value.toString())

        /** A JSON number. @throws IllegalArgumentException for NaN and the infinities, which JSON cannot hold. */
        @JvmStatic
        public fun of(value: Double): JsonValue {
            require(!value.isNaN() && !value.isInfinite()) { "JSON cannot hold $value" }
            return JsonNumber(formatDouble(value))
        }

        /** A JSON boolean. */
        @JvmStatic
        public fun of(value: Boolean): JsonValue = if (value) JsonBoolean.TRUE else JsonBoolean.FALSE
    }
}

/** JSON `null`. */
public object JsonNull : JsonValue() {
    override fun toString(): String = "null"
}

/** A JSON boolean. */
public class JsonBoolean private constructor(public val value: Boolean) : JsonValue() {
    override fun asBoolean(): Boolean = value

    override fun equals(other: Any?): Boolean = other is JsonBoolean && other.value == value

    override fun hashCode(): Int = if (value) 1231 else 1237

    public companion object {
        /** `true`. */
        @JvmField
        public val TRUE: JsonBoolean = JsonBoolean(true)

        /** `false`. */
        @JvmField
        public val FALSE: JsonBoolean = JsonBoolean(false)
    }
}

/**
 * A JSON number, kept as the text it was read from so that nothing is lost (the API's amounts are whole numbers
 * of kuruş, but a number can be larger than a `Double` holds exactly).
 */
public class JsonNumber(
    /** The number as JSON text. */
    public val text: String,
) : JsonValue() {
    init {
        require(JsonParser.isNumber(text)) { "not a JSON number: $text" }
    }

    override fun asLong(): Long? = text.toLongOrNull()

    override fun asInt(): Int? = text.toIntOrNull()

    override fun asDouble(): Double? = text.toDoubleOrNull()

    override fun equals(other: Any?): Boolean = other is JsonNumber && other.text == text

    override fun hashCode(): Int = text.hashCode()
}

/** A JSON string. */
public class JsonString(
    /** The text, with escapes resolved. */
    public val value: String,
) : JsonValue() {
    override fun asString(): String = value

    override fun equals(other: Any?): Boolean = other is JsonString && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/** A JSON array. */
public class JsonArray(
    /** The elements, in order. */
    public val items: List<JsonValue>,
) : JsonValue() {
    /** The number of elements. */
    public val size: Int get() = items.size

    /** The element at [index], or `null` when there is none. */
    public operator fun get(index: Int): JsonValue? = items.getOrNull(index)

    override fun asArray(): JsonArray = this

    override fun equals(other: Any?): Boolean = other is JsonArray && other.items == items

    override fun hashCode(): Int = items.hashCode()
}

/** A JSON object; its members keep the order they were read in. */
public class JsonObject(
    /** The members, in order. */
    public val members: Map<String, JsonValue>,
) : JsonValue() {
    /** The member called [name], or `null` when there is none. */
    public operator fun get(name: String): JsonValue? = members[name]

    /** Whether the object has a member called [name]. */
    public fun has(name: String): Boolean = members.containsKey(name)

    override fun asObject(): JsonObject = this

    override fun equals(other: Any?): Boolean = other is JsonObject && other.members == members

    override fun hashCode(): Int = members.hashCode()
}

/** The text is not valid JSON. */
public class JsonParseException(message: String) : IllegalArgumentException(message)

internal fun decodeUtf8(bytes: ByteArray): String {
    var start = 0
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}

/** `Double` as JSON text: whole numbers without a fraction, as JavaScript writes them. */
internal fun formatDouble(value: Double): String {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
    return value.toString()
}
