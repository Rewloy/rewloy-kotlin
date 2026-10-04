package com.rewloy

import com.rewloy.json.JsonObject
import com.rewloy.json.JsonValue
import java.util.Collections

/**
 * The base of every model class: a request body, a query or an answer's data.
 *
 * It keeps the fields the API sends or accepts that this version of the library has no property for. When the
 * API adds a field, an answer's [additionalProperties] hold it at once; for a request,
 * [setAdditionalProperty] sends one before the next release of the library.
 */
public abstract class RewloyObject internal constructor() {
    private var extra: MutableMap<String, JsonValue>? = null

    /** Fields the object has no property for, by name: new fields of the API, kept rather than dropped. */
    public val additionalProperties: Map<String, JsonValue>
        get() = extra?.let { Collections.unmodifiableMap(it) } ?: emptyMap()

    /** Sets a field this version of the library has no property for. For a request it is sent with the body. */
    public fun setAdditionalProperty(name: String, value: JsonValue) {
        val map = extra ?: java.util.LinkedHashMap<String, JsonValue>().also { extra = it }
        map[name] = value
    }

    /** The JSON a request writes; only request classes have it. */
    internal open fun toJsonValue(): JsonObject = throw UnsupportedOperationException("${javaClass.simpleName} is not a request")

    internal fun extras(): Map<String, JsonValue> = extra ?: emptyMap()

    /** Takes the fields an answer had that no property holds (used by the generated `read`). */
    internal fun adopt(fields: Map<String, JsonValue>) {
        if (fields.isNotEmpty()) extra = java.util.LinkedHashMap(fields)
    }
}
