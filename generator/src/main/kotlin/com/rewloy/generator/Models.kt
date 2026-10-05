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

internal enum class ClassKind { REQUEST, RESPONSE, QUERY, UNION }

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

/** One shape of a union of objects: the class that holds it, and what tells it from the others in an answer. */
internal class UnionShape(
    val className: String,
    val required: List<String>,
    val known: List<String>,
    /** A property that is a `const` string in this shape: its name and value. */
    val tag: Pair<String, String>?,
)

internal class ClassDecl(
    val name: String,
    var doc: String?,
    val kind: ClassKind,
    /** The operation's tag: which file the class goes to. */
    val tag: String,
    val pagedOp: String? = null,
) {
    val props = ArrayList<PropDecl>()
    var pageType: KType? = null

    /** The sealed class this one is a shape of (a union of objects). */
    var parent: String? = null

    /** For [ClassKind.UNION]: the shapes, in the document's order. */
    var shapes: List<UnionShape> = emptyList()

    /** For [ClassKind.UNION]: the properties every shape has with the same type, declared abstract here. */
    var common: List<PropDecl> = emptyList()

    /** For a shape of a union: the names of its properties that override the sealed class's. */
    var overrides: Set<String> = emptySet()
}

/**
 * Turns JSON Schemas into Kotlin classes and types.
 *
 * The choices (docs/DECISIONS.md explains them):
 * - an object schema with properties becomes a class: for a request, a body or a query, a `var` property each
 *   (required ones first, without a default; the rest `null`); for an answer, immutable `val`s;
 * - a union of objects (a `oneOf` of different shapes, such as the two answers of passAction) becomes a sealed class with a
 *   class per shape, named after the property that tells them apart (`kind`: `Staff`, `Key`) or `Option1`, `Option2`; the
 *   reader picks the shape by the fields the answer has. Anything else that is a real union, a free-form object and an
 *   unknown type stay `JsonValue`;
 * - enums stay `String` (and integer enums stay numbers): an enum type would throw on the day the API adds a value;
 * - ids and timestamps stay `String`: opaque ids need no parsing, and `java.time` is missing from older Android.
 */
internal class ModelBuilder(claimable: Collection<String>, private val reserved: Set<String>) {
    val classes = ArrayList<ClassDecl>()
    private val used = HashSet<String>()
    private val claimable = HashSet(claimable)
    private val components = HashMap<String, KType>()
    private var tag = ""

    /**
     * A request body that is a union of objects (`oneOf`): the first shape is the body class of that name, the others
     * are classes of their own (`CreateApiKeyBody`, `CreateApiKeyBodyPos`) and the operation gets one overload per
     * shape. Keyed by the first shape's class name; the value is the other shapes' class names.
     */
    val requestAlternatives = LinkedHashMap<String, List<String>>()

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
        if (union != null) return mapUnion(union, hint, request, where)

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

    private fun mapUnion(members: JsonValue, hint: String, request: Boolean, where: String): Mapped {
        if (!request) unionOfObjects(members, hint, where)?.let { return it }
        else requestUnion(members, hint, where)?.let { return it }
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

    /** The members of a union when all are plain objects with properties (no `$ref`, nested union or `nullable`), else `null`. */
    private fun objectMembers(members: JsonValue): List<JsonValue>? {
        val list = members.items()
        if (list.size < 2 || list.any { m ->
                m !is com.rewloy.json.JsonObject || m.str("type") != "object" || m.prop("properties").members().isEmpty() || m.prop("oneOf") != null ||
                    m.prop("anyOf") != null || m.prop("allOf") != null || m.prop("\$ref") != null || m.flag("nullable")
            }
        ) {
            return null
        }
        return list
    }

    /** A property's only allowed value: its `const` string, or its `enum` of one string. */
    private fun singleValue(property: JsonValue?): String? =
        (property.prop("const") as? JsonString)?.value ?: (property.prop("enum").items().singleOrNull() as? JsonString)?.value

    /**
     * A request body that is a union of objects. The first shape keeps the name `hint` (so what a caller wrote before
     * the union stays valid) and the others are named after the property that tells them apart (`kind` = "pos":
     * `…Pos`) or `…Option2`; the operation takes any of them (see [requestAlternatives]). `null` when it is not a
     * union of objects: then it stays a `JsonValue`.
     */
    private fun requestUnion(members: JsonValue, hint: String, where: String): Mapped? {
        val list = objectMembers(members) ?: return null
        val discriminator = list.first().prop("properties").members().keys.firstOrNull { name ->
            val values = list.map { singleValue(it.prop("properties").prop(name)) }
            values.all { it != null } && values.toSet().size == values.size
        }
        val suffixes = list.mapIndexed { i, m ->
            if (discriminator != null) Naming.pascal(singleValue(m.prop("properties").prop(discriminator))!!) else "Option${i + 1}"
        }
        val types = list.mapIndexed { i, member ->
            val type = mapObject(member, if (i == 0) hint else hint + suffixes[i], request = true, "$where<${suffixes[i]}>").type as KType.Obj
            val decl = classes.first { it.name == type.name }
            decl.doc = listOfNotNull(member.str("title"), member.str("description")).joinToString(": ").ifEmpty { null }
            type.name
        }
        val others = types.drop(1)
        requestAlternatives[types.first()] = others
        val first = classes.first { it.name == types.first() }
        first.doc = (first.doc?.plus("\n\n") ?: "") + "One of ${types.size} body shapes; the others: " + others.joinToString(", ") { "[$it]" } + "."
        for (name in others) {
            val decl = classes.first { it.name == name }
            decl.doc = (decl.doc?.plus("\n\n") ?: "") + "The alternative body shape of [${types.first()}]."
        }
        return Mapped(KType.Obj(types.first()), false)
    }

    /**
     * A sealed class for a union whose members are all objects with properties, or `null` when it is something else.
     * Each member becomes a class of its own (so its fields are typed, required ones included); the sealed class's
     * reader picks the shape the answer has: the members whose required fields are all present, the one with the
     * most known fields among them, and a `const` string field must match.
     */
    private fun unionOfObjects(members: JsonValue, hint: String, where: String): Mapped? {
        val list = members.items()
        if (list.size < 2 || list.any { m ->
                m !is com.rewloy.json.JsonObject || m.str("type") != "object" || m.prop("properties").members().isEmpty() || m.prop("oneOf") != null ||
                    m.prop("anyOf") != null || m.prop("allOf") != null || m.prop("\$ref") != null || m.flag("nullable")
            }
        ) {
            return null
        }
        // The property that is a distinct `const` string in every member names the shapes (`kind` = "staff" is `Staff`).
        val tagNames = list.map { m -> m.prop("properties").members().filter { (_, v) -> v.prop("const") is JsonString }.keys.toList() }
        val discriminator = tagNames.first().firstOrNull { name ->
            tagNames.all { name in it } &&
                list.map { (it.prop("properties").prop(name)!!.prop("const") as JsonString).value }.let { values -> values.toSet().size == values.size }
        }
        val suffixes = list.mapIndexed { i, m ->
            if (discriminator != null) Naming.pascal((m.prop("properties").prop(discriminator)!!.prop("const") as JsonString).value) else "Option${i + 1}"
        }
        if (suffixes.toSet().size != suffixes.size) return null

        val base = reserve(hint)
        val union = ClassDecl(base, null, ClassKind.UNION, tag)
        classes.add(union)
        val shapes = ArrayList<UnionShape>()
        val variants = ArrayList<String>()
        for ((i, member) in list.withIndex()) {
            val type = mapObject(member, base + suffixes[i], request = false, "$where<${suffixes[i]}>").type as KType.Obj
            val decl = classes.first { it.name == type.name }
            decl.parent = base
            val title = member.str("title")
            val description = member.str("description")
            decl.doc = listOfNotNull(title, description).joinToString(": ").ifEmpty { null }
            variants.add("- [${type.name}]" + (decl.doc?.let { ": $it" } ?: ""))
            val tag = discriminator?.let { it to (member.prop("properties").prop(it)!!.prop("const") as JsonString).value }
            shapes.add(UnionShape(type.name, member.prop("required").items().mapNotNull { it.str() }, member.prop("properties").members().keys.toList(), tag))
        }
        union.shapes = shapes
        // What every shape has, required and of one type (`duplicate`), is a property of the sealed class: no `when` needed to read it.
        val decls = shapes.map { sh -> classes.first { it.name == sh.className } }
        union.common = decls.first().props.filter { p ->
            p.deprecated == null && decls.all { d -> d.props.any { q -> q.jsonName == p.jsonName && q.name == p.name && q.type == p.type && q.nullable == p.nullable && q.required && p.required && q.deprecated == null } }
        }
        for (d in decls) d.overrides = union.common.map { it.name }.toSet()
        union.doc = "An answer of one of ${list.size} shapes: `when (answer) { is X -> … }` tells them apart.\n\n" + variants.joinToString("\n")
        return Mapped(KType.Obj(base), false)
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
