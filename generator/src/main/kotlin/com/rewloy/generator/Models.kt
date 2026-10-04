package com.rewloy.generator

import com.rewloy.json.JsonArray
import com.rewloy.json.JsonBoolean
import com.rewloy.json.JsonNumber
import com.rewloy.json.JsonString
import com.rewloy.json.JsonValue

/**
 * A type of the generated code. The OpenAPI document's schemas are mapped to these:
 * strings (uuid and date-time too, as text), integers, numbers, booleans, lists, maps, generated classes, and
 * [Json] for what has no shape (a free-form object, a real union).
 */
internal sealed class KType {
    abstract val kotlin: String

    object Str : KType() { override val kotlin = "String" }
    object Int32 : KType() { override val kotlin = "Int" }
    object Int64 : KType() { override val kotlin = "Long" }
    object Dbl : KType() { override val kotlin = "Double" }
    object Bool : KType() { override val kotlin = "Boolean" }
    object Json : KType() { override val kotlin = "JsonValue" }
    data class Obj(val name: String) : KType() { override val kotlin get() = name }
    data class Lst(val element: KType, val elementNullable: Boolean) : KType() {
        override val kotlin get() = "List<${element.kotlin}${if (elementNullable) "?" else ""}>"
    }
    data class Mp(val element: KType, val elementNullable: Boolean) : KType() {
        override val kotlin get() = "Map<String, ${element.kotlin}${if (elementNullable) "?" else ""}>"
    }
}

internal data class Mapped(val type: KType, val nullable: Boolean)

internal enum class ClassKind { REQUEST, RESPONSE, QUERY }

internal class PropDecl(
    val jsonName: String,
    val name: String,
    val type: KType,
    /** The schema allows `null` here. */
    val nullable: Boolean,
    val required: Boolean,
    val doc: String?,
    val deprecated: String?,
    /** In a request: `OptionalField<T>`, to tell "left out" from an explicit `null`. */
    val optionalField: Boolean,
) {
    /** The Kotlin type as declared, with its question mark. */
    val declared: String
        get() {
            val inner = if (optionalField) "OptionalField<${type.kotlin}>" else type.kotlin
            val mayBeNull = when {
                optionalField -> !required
                type == KType.Json -> !required
                else -> !required || nullable
            }
            return if (mayBeNull) "$inner?" else inner
        }

    /** Whether the declaration needs `= null` (an optional request or query property). */
    val hasDefault: Boolean get() = declared.endsWith("?") && !required
}

internal class ClassDecl(
    val name: String,
    val doc: String?,
    val kind: ClassKind,
    /** The operation's tag: which file the class goes to. */
    val tag: String,
    val pagedOp: String? = null,
) {
    val props = ArrayList<PropDecl>()
    var pageType: KType? = null
}

/**
 * Turns JSON Schemas into Kotlin classes and types.
 *
 * The choices (docs/DECISIONS.md explains them):
 * - an object schema with properties becomes a class: for a request, a body or a query, a `var` property each
 *   (required ones first, without a default; the rest `null`); for an answer, immutable `val`s;
 * - a free-form object, an unknown type and a real union (a `oneOf` of different shapes) stay `JsonValue`;
 * - enums stay `String` (and integer enums stay numbers): an enum type would throw on the day the API adds a value;
 * - ids and timestamps stay `String`: opaque ids need no parsing, and `java.time` is missing from older Android.
 */
internal class ModelBuilder(claimable: Collection<String>, private val reserved: Set<String>) {
    val classes = ArrayList<ClassDecl>()
    private val used = HashSet<String>()
    private val claimable = HashSet(claimable)
    private val components = HashMap<String, KType>()
    private var tag = ""

    fun inTag(newTag: String) {
        tag = newTag
    }

    private fun reserve(hint: String): String {
        if (hint in reserved) throw GeneratorException("type name $hint collides with a library type")
        if (claimable.remove(hint)) {
            used.add(hint)
            return hint
        }
        var name = hint
        var i = 2
        while (name in used || name in claimable || name in reserved) name = hint + i++
        used.add(name)
        return name
    }

    fun addComponents(components: JsonValue?) {
        for ((name, schema) in components.members()) {
            if (name == "Error") continue // RewloyException carries it
            inTag("Common")
            val m = map(schema, Naming.pascal(name), request = false, "component $name")
            this.components[name] = m.type
        }
    }

    // ------------------------------------------------------------------ schema → type

    /** The Kotlin type of a path parameter: `String`, `Int` or `Long`. */
    fun mapScalar(schema: JsonValue?, where: String): KType {
        val m = map(schema, "Unused", request = true, where)
        if (m.type == KType.Str || m.type == KType.Int32 || m.type == KType.Int64) return m.type
        throw GeneratorException("$where: a path parameter of type ${m.type.kotlin} is not supported")
    }

    fun map(schema: JsonValue?, hint: String, request: Boolean, where: String): Mapped {
        if (schema !is com.rewloy.json.JsonObject) return Mapped(KType.Json, false) // `true`, `{}` or absent: anything
        val nullable = schema.flag("nullable")
        val m = mapCore(schema, hint, request, where)
        return if (nullable) m.copy(nullable = true) else m
    }

    private fun mapCore(schema: JsonValue, hint: String, request: Boolean, where: String): Mapped {
        schema.str("\$ref")?.let { reference ->
            val prefix = "#/components/schemas/"
            val type = if (reference.startsWith(prefix)) components[reference.substring(prefix.length)] else null
            return Mapped(type ?: throw GeneratorException("$where: unsupported \$ref $reference"), false)
        }
        schema.prop("const")?.let { return constType(it) }
        if (schema.prop("allOf") != null) throw GeneratorException("$where: allOf is not supported")
        val union = schema.prop("oneOf") ?: schema.prop("anyOf")
        if (union != null) return mapUnion(union, where)

        var nullInTypes = false
        val types = ArrayList<String>()
        when (val t = schema.prop("type")) {
            is JsonString -> types.add(t.value)
            is JsonArray -> for (x in t.items) types.add(x.str() ?: throw GeneratorException("$where: a type that is not a string"))
            null -> when {
                schema.prop("properties") != null || schema.prop("additionalProperties") != null -> types.add("object")
                schema.prop("items") != null -> types.add("array")
                schema.prop("enum").items().any { it is JsonString } -> types.add("string")
            }
            else -> throw GeneratorException("$where: a type that is neither a string nor a list")
        }
        if (types.remove("null")) nullInTypes = true
        val distinct = types.distinct()
        val result = if (distinct.size != 1) {
            Mapped(KType.Json, false)
        } else {
            when (distinct[0]) {
                "string" -> Mapped(KType.Str, false)
                "integer" -> Mapped(integerType(schema), false)
                "number" -> Mapped(KType.Dbl, false)
                "boolean" -> Mapped(KType.Bool, false)
                "array" -> mapArray(schema, hint, request, where)
                "object" -> mapObject(schema, hint, request, where)
                else -> Mapped(KType.Json, false)
            }
        }
        return if (nullInTypes) result.copy(nullable = true) else result
    }

    private fun constType(constant: JsonValue): Mapped = when (constant) {
        is JsonString -> Mapped(KType.Str, false)
        is JsonBoolean -> Mapped(KType.Bool, false)
        is JsonNumber -> Mapped(if (constant.asLong() != null) KType.Int64 else KType.Dbl, false)
        else -> Mapped(KType.Json, false)
    }

    /** `Int` when the schema bounds the number inside Int32 on both sides, `Long` otherwise. */
    private fun integerType(schema: JsonValue): KType {
        val min = (schema.prop("minimum") as? JsonNumber)?.asLong()
        val max = (schema.prop("maximum") as? JsonNumber)?.asLong()
        return if (min != null && max != null && min >= Int.MIN_VALUE && max <= Int.MAX_VALUE) KType.Int32 else KType.Int64
    }

    private fun mapUnion(members: JsonValue, where: String): Mapped {
        val types = LinkedHashSet<KType>()
        var nullable = false
        for (member in members.items()) {
            if (member !is com.rewloy.json.JsonObject) return Mapped(KType.Json, false)
            // A member with a shape (object, array, nested union) makes this a real union: JsonValue.
            if (member.prop("properties") != null || member.prop("items") != null || member.prop("oneOf") != null ||
                member.prop("anyOf") != null || member.prop("\$ref") != null || member.str("type") == "object" || member.str("type") == "array"
            ) {
                return Mapped(KType.Json, false)
            }
            if (member.str("type") == "null") {
                nullable = true
                continue
            }
            // Strings of different formats (a date, a date-time) are strings alike.
            val m = if (member.str("type") == "string") Mapped(KType.Str, false) else mapCore(member, "Union", request = false, where)
            if (m.type == KType.Json) return Mapped(KType.Json, false)
            types.add(m.type)
        }
        if (types.size != 1) return Mapped(KType.Json, false)
        return Mapped(types.first(), nullable)
    }

    private fun mapArray(schema: JsonValue, hint: String, request: Boolean, where: String): Mapped {
        val item = map(schema.prop("items"), hint + "Item", request, "$where[]")
        return Mapped(KType.Lst(item.type, item.nullable), false)
    }

    private fun mapObject(schema: JsonValue, hint: String, request: Boolean, where: String): Mapped {
        val props = schema.prop("properties")
        val extra = schema.prop("additionalProperties")
        val hasProps = props.members().isNotEmpty()
        if (!hasProps && !props.isObject() && !(extra is JsonBoolean && !extra.value)) {
            // No properties declared: a map of one type of value, or anything.
            if (extra is com.rewloy.json.JsonObject) {
                val value = map(extra, hint + "Value", request, "$where{}")
                return Mapped(KType.Mp(value.type, value.nullable), false)
            }
            return Mapped(KType.Json, false)
        }

        val name = reserve(hint)
        val decl = ClassDecl(name, schema.str("description"), if (request) ClassKind.REQUEST else ClassKind.RESPONSE, tag)
        classes.add(decl)
        val required = schema.prop("required").items().mapNotNull { it.str() }.toSet()
        val taken = takenNames(name)
        for ((propName, propSchema) in props.members()) {
            decl.props.add(makeProp(propName, propSchema, propName in required, name, request, taken, "$where.$propName"))
        }
        return Mapped(KType.Obj(name), false)
    }

    private fun takenNames(className: String): HashSet<String> = HashSet<String>().also {
        it.addAll(RESERVED_MEMBERS)
        it.add(className)
    }

    /** A property's Kotlin name: camelCase, unique in its class and in its JVM getter name. */
    private fun uniqueName(jsonName: String, type: KType, taken: HashSet<String>): String {
        val base = Naming.camel(jsonName)
        var name = base
        var i = 2
        while (true) {
            val getters = getterNames(name, type)
            if (name !in taken && getters.none { it in taken }) {
                taken.add(name)
                taken.addAll(getters)
                return name
            }
            name = base + (if (i == 2) "Value" else "Value$i")
            i++
        }
    }

    private fun getterNames(name: String, type: KType): List<String> {
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        val isStyle = type == KType.Bool && name.startsWith("is") && name.length > 2 && name[2] !in 'a'..'z'
        return if (isStyle) listOf(name) else listOf("get$cap")
    }

    private fun makeProp(
        jsonName: String,
        schema: JsonValue?,
        isRequired: Boolean,
        className: String,
        request: Boolean,
        taken: HashSet<String>,
        where: String,
        fallbackDoc: String? = null,
        queryParam: Boolean = false,
    ): PropDecl {
        val m = map(schema, className + Naming.pascal(jsonName), request, where)
        val name = uniqueName(jsonName, m.type, taken)
        // `null` is a JSON value the API reads: keep it apart from "left out".
        val optionalField = request && !queryParam && m.nullable && m.type != KType.Json

        var deprecated: String? = null
        if (schema.flag("deprecated")) {
            val d = schema.prop("x-deprecation")
            val sunset = d.str("sunset") ?: schema.str("x-sunset")
            val use = d.str("use") ?: schema.str("x-replacement")
            deprecated = "$jsonName is deprecated" + (if (sunset == null) "." else "; the API stops sending it after $sunset.") +
                (if (use == null) "" else " Use $use instead.")
        }

        val notes = ArrayList<String>()
        schema.prop("enum")?.takeIf { it is JsonArray }?.let { e ->
            notes.add("One of: " + e.items().joinToString(", ") { v -> if (v is JsonString) "`${v.value}`" else v.toString() } + ".")
        }
        schema.prop("const")?.let { notes.add("Always `$it`.") }
        if (isRequired) notes.add(if (request) "Required." else "Always present.")
        if (optionalField) notes.add("An `OptionalField` tells a left-out field from an explicit `null`: `OptionalField.ofNull()` sends `null`.")
        val doc = listOfNotNull(schema.str("description") ?: fallbackDoc).plus(notes).filter { it.isNotEmpty() }.joinToString("\n\n")
        return PropDecl(jsonName, name, m.type, m.nullable, isRequired, doc.ifEmpty { null }, deprecated, optionalField)
    }

    /** A query class: the operation's query parameters as properties, and the code that writes them. */
    fun addQuery(hint: String, parameters: List<QueryParam>, paged: Boolean, opId: String): String {
        val name = reserve(hint)
        val decl = ClassDecl(name, "Query parameters of `$opId`.", ClassKind.QUERY, tag, if (paged) opId else null)
        classes.add(decl)
        val taken = takenNames(name)
        taken.addAll(listOf("writeTo", "withPage"))
        for (p in parameters) {
            val m = map(p.schema, name + Naming.pascal(p.name), request = true, "$opId query ${p.name}")
            val ok = m.type == KType.Str || m.type == KType.Int32 || m.type == KType.Int64 || m.type == KType.Dbl || m.type == KType.Bool ||
                (m.type is KType.Lst && !(m.type as KType.Lst).elementNullable && (m.type as KType.Lst).element.let { it == KType.Str || it == KType.Int32 || it == KType.Int64 || it == KType.Dbl || it == KType.Bool })
            if (!ok) throw GeneratorException("$opId: query parameter \"${p.name}\" of type ${m.type.kotlin} is not supported")
            decl.props.add(makeProp(p.name, p.schema, p.required, name, request = true, taken, "$opId query ${p.name}", p.description, queryParam = true))
            if (paged && p.name == "page") {
                if (m.type != KType.Int32 && m.type != KType.Int64) throw GeneratorException("$opId: the page parameter is a ${m.type.kotlin}, not an integer")
                decl.pageType = m.type
            }
        }
        if (paged && decl.pageType == null) throw GeneratorException("$opId is a paged list but takes no page parameter")
        return name
    }

    companion object {
        /** Property names a generated class cannot use: its own members and `Object`'s, as Kotlin or the JVM sees them. */
        val RESERVED_MEMBERS = setOf(
            "additionalProperties", "setAdditionalProperty", "getAdditionalProperties", "getClass", "javaClass", "hashCode", "toString",
            "equals", "notify", "notifyAll", "wait", "clone", "finalize", "toJsonValue", "extras", "adopt", "read", "Companion",
        )
    }
}

internal class QueryParam(val name: String, val schema: JsonValue?, val required: Boolean, val description: String?)
