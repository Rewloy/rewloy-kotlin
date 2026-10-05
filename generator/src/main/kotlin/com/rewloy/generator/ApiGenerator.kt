package com.rewloy.generator

import com.rewloy.json.JsonArray
import com.rewloy.json.JsonObject
import com.rewloy.json.JsonValue

/**
 * Rewloy's OpenAPI 3.1 document in, the Kotlin of `rewloy/src/main/kotlin/com/rewloy/generated/` out.
 * Pure (no I/O), so that a test can run it on the committed snapshot and compare.
 *
 * It reads only what the document says, the way the platform writes it: inline JSON Schemas with `PageMeta` and
 * `Error` as the only components, `x-credentials` for the credential kinds, the `Rewloy-Merchant` and
 * `Idempotency-Key` header parameters, the `{ data[, meta] }` envelope and one example per error code.
 *
 * Output (deterministic: the document's order, no dates):
 * - `models/<Tag>.kt`   a class per object schema: request bodies, query objects, answers
 * - `RewloyOperations.kt`  the metadata table
 * - `ErrorCode.kt`      every error code, with its title
 * - `RewloyApi.kt`      one method per operationId (plus the whole-answer and paging variants)
 */
object ApiGenerator {
    private val HTTP_METHODS = listOf("get", "post", "put", "patch", "delete")
    private const val MERCHANT_HEADER = "rewloy-merchant"
    private const val IDEMPOTENCY_HEADER = "idempotency-key"
    private const val REFERENCE = "https://rewloy.com/gelistiriciler/api"
    private const val CHANGELOG = "https://rewloy.com/gelistiriciler/degisiklikler"
    private const val OUT = "rewloy/src/main/kotlin/com/rewloy/generated"
    private val TR_MONTHS = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

    /**
     * Names of the hand-written library (packages `com.rewloy` and `com.rewloy.json`): a generated model may not
     * take one, because the generated code imports both packages by star.
     */
    internal val RESERVED_TYPES = setOf(
        "Rewloy", "RewloyApi", "RewloyOptions", "RequestOptions", "RewloyResponse", "Page", "RewloyFile", "RewloyException",
        "RateLimitException", "RewloyConnectionException", "RewloyTimeoutException", "WebhookSignatureException", "WebhookFailure",
        "Webhook", "WebhookEvent", "PassEventData", "EventStream", "ServerSentEvent", "SseParser", "OperationInfo", "Deprecation",
        "DeprecationNotice", "DeprecationListener", "CredentialKind", "ResponseKind", "IdempotencyMode", "RewloyOperations",
        "ErrorCode", "RewloyObject", "RewloyQuery", "QueryWriter", "OptionalField", "Headers", "CancelToken", "Transport",
        "TransportRequest", "TransportResponse", "HttpUrlConnectionTransport", "Sleeper", "Wire", "ObjectReader", "ObjectWriter",
        "Out", "Core", "Reply", "Exchange", "StreamLink", "StreamConnection", "UrlEncoding", "Retry", "Timeouts", "HttpDate",
        "UserAgent", "RewloyVersion", "PagedIterable", "ResponseShapeException",
        "JsonValue", "JsonNull", "JsonBoolean", "JsonNumber", "JsonString", "JsonArray", "JsonObject", "JsonParseException",
        "JsonParser", "JsonWriter",
    )

    /** Method names the client defines itself: an operationId may not take them. */
    private val RESERVED_METHODS = setOf(
        "withCancel", "toString", "hashCode", "equals", "getClass", "getBaseUrl", "getTimeoutMs", "getMaxRetries", "getCredential",
        "getMerchant", "callJson", "callRaw", "callNone", "callFile", "openStream", "baseUrl", "timeoutMs", "maxRetries",
        "credential", "merchant",
    )

    fun countOperations(document: JsonValue): Int =
        document.prop("paths").members().values.sumOf { item -> item.members().keys.count { it in HTTP_METHODS } }

    // ------------------------------------------------------------------ reading

    private class Param(val name: String, val schema: JsonValue?, val required: Boolean, val description: String?)

    private class Dep(val sunset: String?, val use: String?)

    private class Op(
        val id: String,
        val method: String,
        val path: String,
        val tag: String,
        val summary: String,
        val description: String,
        val auth: List<String>,
        val deprecated: Dep?,
        val pathParams: List<Param>,
        val queryParams: List<Param>,
        val merchant: Param?,
        val idempotency: Param?,
        val body: Pair<JsonValue?, Boolean>?,
        val response: String,
        val paged: Boolean,
        val dataSchema: JsonValue?,
    ) {
        val type: String get() = Naming.pascal(id)
    }

    private fun readParams(op: JsonValue, where: String): List<Param> =
        op.prop("parameters").items().filter { it.str("in") == where }.map { p ->
            Param(
                p.str("name") ?: throw GeneratorException("a parameter without a name"),
                p.prop("schema"),
                p.flag("required"),
                p.str("description") ?: p.prop("schema").str("description"),
            )
        }

    /** "1 Nisan 2027" (the document's deprecation sentence) → "2027-04-01"; the replacement is "yerine `x`". */
    internal fun parseDeprecation(description: String): Pair<String?, String?> {
        val date = Regex("(\\d{1,2}) (${TR_MONTHS.joinToString("|")}) (\\d{4})").find(description)
        val use = Regex("yerine `([A-Za-z0-9_]+)`").find(description)
        val sunset = date?.let { "${it.groupValues[3]}-${(TR_MONTHS.indexOf(it.groupValues[2]) + 1).toString().padStart(2, '0')}-${it.groupValues[1].padStart(2, '0')}" }
        return Pair(sunset, use?.groupValues?.get(1))
    }

    private fun readOp(path: String, method: String, op: JsonValue): Op {
        val id = op.str("operationId") ?: throw GeneratorException("${method.uppercase()} $path has no operationId")
        if (!Regex("^[a-z][A-Za-z0-9]*$").matches(id)) throw GeneratorException("operationId \"$id\" is not camelCase")

        val headers = readParams(op, "header")
        val merchant = headers.firstOrNull { it.name.equals(MERCHANT_HEADER, ignoreCase = true) }
        val idempotency = headers.firstOrNull { it.name.equals(IDEMPOTENCY_HEADER, ignoreCase = true) }
        val other = headers.filter { it !== merchant && it !== idempotency }
        if (other.isNotEmpty()) throw GeneratorException("$id: header parameter \"${other[0].name}\" is not supported (only Rewloy-Merchant and Idempotency-Key are)")
        if (readParams(op, "cookie").isNotEmpty()) throw GeneratorException("$id: cookie parameters are not supported")

        val auth = ArrayList<String>()
        val creds = op.prop("x-credentials")
        if (creds is JsonArray) {
            for (c in creds.items) auth.add(c.str() ?: c.toString())
        } else {
            for (s in op.prop("security").items().filter { it is JsonObject }) {
                val keys = s.members().keys.toList()
                if (keys.isEmpty()) auth.add("public")
                for (k in keys) auth.add(when (k) { "apiKey" -> "key"; "staffSession" -> "staff"; "holderSession" -> "holder"; else -> k })
            }
        }
        for (a in auth) if (a !in listOf("key", "staff", "holder", "public")) throw GeneratorException("$id: unknown credential kind \"$a\"")

        var body: Pair<JsonValue?, Boolean>? = null
        val rb = op.prop("requestBody")
        if (rb is JsonObject) {
            val json = rb.prop("content").prop("application/json") ?: throw GeneratorException("$id: only application/json request bodies are supported")
            body = Pair(json.prop("schema"), rb.flag("required"))
        }

        val responses = op.prop("responses")
        if (!responses.isObject()) throw GeneratorException("$id has no responses")
        val success = responses.members().keys.filter { it.length == 3 && it[0] == '2' }.sorted()
        if (success.isEmpty()) throw GeneratorException("$id has no 2xx response")
        val first = responses.prop(success[0])
        val content = first.prop("content")
        val response: String
        var paged = false
        var dataSchema: JsonValue? = null
        if (!content.isObject() || success[0] == "204") {
            response = "none"
        } else {
            val (type, media) = content.members().entries.first().toPair()
            val schema = media.prop("schema")
            val isJson = Regex("^application/([a-z.+-]+\\+)?json\\b").containsMatchIn(type)
            val data = schema.prop("properties").prop("data")
            if (type.startsWith("text/event-stream")) {
                response = "stream"
            } else if (isJson && data != null) {
                response = "json"
                dataSchema = data
                paged = schema.prop("properties").prop("meta") != null
            } else {
                response = if (isJson) "raw-json" else "blob"
            }
        }

        val description = op.str("description") ?: ""
        var deprecated: Dep? = null
        if (op.flag("deprecated")) {
            val structured = op.prop("x-deprecation")
            deprecated = if (structured.isObject()) Dep(structured.str("sunset"), structured.str("use")) else parseDeprecation(description).let { Dep(it.first, it.second) }
        }

        return Op(
            id, method.uppercase(), path, op.prop("tags").items().firstNotNullOfOrNull { it.str() } ?: "",
            op.str("summary") ?: "", description, auth, deprecated, readParams(op, "path"), readParams(op, "query"),
            merchant, idempotency, body, response, paged, dataSchema,
        )
    }

    /** Error titles, from each error code's example (`summary` is the catalogue's title), in the document's order. */
    private fun errorTitles(paths: JsonValue?): LinkedHashMap<String, String> {
        val titles = LinkedHashMap<String, String>()
        for (item in paths.members().values) {
            for ((m, op) in item.members()) {
                if (m !in HTTP_METHODS) continue
                for (r in op.prop("responses").members().values) {
                    for ((code, ex) in r.prop("content").prop("application/json").prop("examples").members()) {
                        val title = ex.str("summary")
                        if (!title.isNullOrEmpty() && code !in titles) titles[code] = title
                    }
                }
            }
        }
        return titles
    }

    // ------------------------------------------------------------------ entry

    fun generate(document: JsonValue): List<GeneratedFile> {
        if (document !is JsonObject) throw GeneratorException("the document is not an object")
        val openapi = document.str("openapi")
        if (openapi == null || !openapi.startsWith("3.")) throw GeneratorException("not an OpenAPI 3 document")
        val apiVersion = document.prop("info").str("version") ?: "0.0.0"
        val paths = document.prop("paths")
        if (!paths.isObject()) throw GeneratorException("the document has no paths")
        val components = document.prop("components").prop("schemas")

        val ops = ArrayList<Op>()
        val seen = HashSet<String>()
        for ((path, item) in paths.members()) {
            for (m in HTTP_METHODS) {
                val op = item.prop(m)
                if (op !is JsonObject) continue
                val o = readOp(path, m, op)
                if (!seen.add(o.id)) throw GeneratorException("operationId \"${o.id}\" is used twice")
                ops.add(o)
            }
        }
        if (ops.isEmpty()) throw GeneratorException("the document has no operations")

        // Method names: every generated name must be unique (and not the client's own).
        val methodNames = HashSet<String>(RESERVED_METHODS)
        for (o in ops) {
            val names = ArrayList<String>()
            names.add(o.id)
            if (o.response != "stream") names.add("${o.id}WithResponse")
            if (o.paged) names.add("${o.id}All")
            for (n in names) {
                if (!methodNames.add(n)) throw GeneratorException("method name $n (from operationId \"${o.id}\") collides with another or with the client's own")
            }
            if (o.id == "all" || o.id == "API_VERSION") throw GeneratorException("operationId \"${o.id}\" collides with a library name")
            if (o.type in RESERVED_TYPES) throw GeneratorException("operationId \"${o.id}\" collides with a library name")
        }

        val model = ModelBuilder(ops.flatMap { listOf("${it.type}Query", "${it.type}Body", "${it.type}Data", "${it.type}Item") }, RESERVED_TYPES)
        model.addComponents(components)
        val plans = HashMap<String, Plan>()
        for (o in ops) {
            model.inTag(o.tag.ifEmpty { "Other" })
            plans[o.id] = planOp(o, model)
        }

        val titles = errorTitles(paths)
        val codes = errorCodes(components, titles.keys.toList())

        val files = ArrayList<GeneratedFile>()
        files.addAll(emitModels(apiVersion, model))
        files.add(GeneratedFile("$OUT/RewloyOperations.kt", emitOperations(apiVersion, ops)))
        files.add(GeneratedFile("$OUT/ErrorCode.kt", emitErrorCodes(apiVersion, codes, titles)))
        files.add(GeneratedFile("$OUT/RewloyApi.kt", emitApi(apiVersion, ops, plans)))
        return files
    }

    private fun errorCodes(components: JsonValue?, fromExamples: List<String>): List<String> {
        val enumValues = components.prop("Error").prop("properties").prop("error").prop("properties").prop("code").prop("enum").items().map { it.str() ?: it.toString() }
        return enumValues + fromExamples.filter { it !in enumValues }
    }

    // ------------------------------------------------------------------ planning: the types of one operation

    private class Arg(val type: String, val name: String, val required: Boolean, val doc: String)

    private class Plan(
        val args: List<Arg>,
        val pathExprs: List<String>,
        val queryExpr: String?,
        val bodyExpr: String?,
        val bodyIsJson: Boolean,
        /** What the answer's `data` is as Kotlin (without the page wrapper). */
        val dataType: String,
        val itemType: String?,
        val queryType: String?,
        val queryRequired: Boolean,
        /** The code that reads `data` from `(v, p)`; for a paged list, the list of items. */
        val reader: String?,
    )

    private fun planOp(op: Op, model: ModelBuilder): Plan {
        val args = ArrayList<Arg>()
        val taken = hashSetOf("body", "query", "options")
        val pathExprs = ArrayList<String>()

        // Path parameters, in the order the path names them (the client fills the placeholders in that order).
        val inPath = Regex("\\{([^}]+)}").findAll(op.path).map { it.groupValues[1] }.toList()
        if (inPath.toSet().size != inPath.size || inPath.sorted() != op.pathParams.map { it.name }.sorted()) {
            throw GeneratorException("${op.id}: the path's placeholders (${inPath.joinToString(", ")}) and its path parameters differ")
        }
        for (name in inPath) {
            val p = op.pathParams.first { it.name == name }
            val type = model.mapScalar(p.schema, "${op.id}.$name")
            val pname = Naming.camel(name)
            if (!taken.add(pname)) throw GeneratorException("${op.id}: path parameter \"$name\" collides with another parameter")
            args.add(Arg(type.kotlin, Naming.ident(pname), true, p.description ?: "The `$name` of the path."))
            pathExprs.add(if (type == KType.Str) Naming.ident(pname) else "${Naming.ident(pname)}.toString()")
        }

        var bodyType: String? = null
        var bodyRequired = false
        op.body?.let { (schema, required) ->
            val mapped = model.map(schema, "${op.type}Body", request = true, "${op.id} body")
            if (mapped.type !is KType.Obj && mapped.type != KType.Json) throw GeneratorException("${op.id}: a request body that is not an object is not supported")
            bodyType = mapped.type.kotlin
            val noRequired = schema is JsonObject && !(schema.prop("required") is JsonArray && (schema.prop("required") as JsonArray).items.isNotEmpty())
            bodyRequired = required && !noRequired
        }

        var queryType: String? = null
        var queryRequired = false
        if (op.queryParams.isNotEmpty()) {
            queryType = model.addQuery("${op.type}Query", op.queryParams.map { QueryParam(it.name, it.schema, it.required, it.description) }, op.paged, op.id)
            queryRequired = op.queryParams.any { it.required }
        } else if (op.paged) {
            throw GeneratorException("${op.id} is a paged list but takes no page parameter")
        }
        if (op.paged && op.body != null) throw GeneratorException("${op.id} is a paged list with a request body: not supported")

        // A parameter can be optional only when everything after it is.
        if (bodyType != null) args.add(Arg(bodyType, "body", bodyRequired || queryRequired, if (bodyRequired) "The JSON body." else "The JSON body; left out, `{}` is sent."))
        if (queryType != null) args.add(Arg(queryType, "query", queryRequired, "The query parameters."))

        var dataType: String
        var itemType: String? = null
        var reader: String? = null
        when (op.response) {
            "none" -> dataType = "Unit"
            "blob" -> dataType = "RewloyFile"
            "raw-json" -> dataType = "JsonValue"
            "stream" -> dataType = "EventStream"
            else -> {
                val data = op.dataSchema!!
                val isArray = data.str("type") == "array"
                if (op.paged && !isArray) throw GeneratorException("${op.id}: a paged list's data is not an array")
                if (isArray) {
                    val mapped = model.map(data.prop("items"), "${op.type}Item", request = false, "${op.id} items")
                    itemType = mapped.type.kotlin + (if (mapped.nullable) "?" else "")
                    dataType = "List<$itemType>"
                    reader = Codec.readValue(KType.Lst(mapped.type, mapped.nullable), false, "v", "p", 1)
                } else {
                    val mapped = model.map(data, "${op.type}Data", request = false, "${op.id} data")
                    dataType = mapped.type.kotlin + (if (mapped.nullable) "?" else "")
                    reader = Codec.readValue(mapped.type, mapped.nullable, "v", "p", 1)
                }
            }
        }

        return Plan(args, pathExprs, queryType?.let { "query" }, bodyType?.let { "body" }, bodyType == "JsonValue", dataType, itemType, queryType, queryRequired, reader)
    }

    // ------------------------------------------------------------------ files

    private fun header(apiVersion: String, pkg: String, imports: List<String>): String {
        val sb = StringBuilder()
        sb.append("// Generated by generator/ from the Rewloy OpenAPI document (openapi/openapi.json, API $apiVersion).\n")
        sb.append("// Do not edit: run `./gradlew generate -Pfile=openapi/openapi.json`.\n")
        sb.append("@file:Suppress(\"DEPRECATION\", \"unused\", \"UNUSED_PARAMETER\", \"RedundantVisibilityModifier\", \"UnusedImport\")\n\n")
        sb.append("package $pkg\n\n")
        for (i in imports) sb.append("import $i\n")
        sb.append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------------ models

    private fun emitModels(apiVersion: String, model: ModelBuilder): List<GeneratedFile> {
        val byTag = LinkedHashMap<String, MutableList<ClassDecl>>()
        for (c in model.classes) byTag.getOrPut(c.tag) { ArrayList() }.add(c)
        val files = ArrayList<GeneratedFile>()
        val stems = HashMap<String, String>()
        for ((tag, classes) in byTag) {
            val stem = Naming.fileStem(tag)
            if (stems.put(stem, tag) != null) throw GeneratorException("tags \"${stems[stem]}\" and \"$tag\" make the same file name $stem")
            val sb = StringBuilder(header(apiVersion, "com.rewloy.models", listOf("com.rewloy.*", "com.rewloy.json.*")))
            for (c in classes) emitClass(sb, c)
            files.add(GeneratedFile("$OUT/models/$stem.kt", sb.toString()))
        }
        return files
    }

    private fun emitUnion(sb: StringBuilder, c: ClassDecl) {
        sb.append(Naming.kdoc("", c.doc, fallback = "The `${c.name}` answer, of one of several shapes."))
        sb.append("public sealed class ${c.name} : RewloyObject() {\n")
        for (p in c.common) {
            sb.append(Naming.kdoc("    ", p.doc, fallback = "`${p.jsonName}`."))
            sb.append("    public abstract val ${Naming.ident(p.name)}: ${p.declared}\n\n")
        }
        sb.append("    internal companion object {\n")
        sb.append("        private val shapes = listOf(\n")
        for (shape in c.shapes) {
            val tag = shape.tag?.let { "${Naming.literal(it.first)}, ${Naming.literal(it.second)}" } ?: "null, null"
            sb.append("            Wire.Shape(listOf(${shape.required.joinToString(", ") { Naming.literal(it) }}), listOf(${shape.known.joinToString(", ") { Naming.literal(it) }}), $tag),\n")
        }
        sb.append("        )\n\n")
        sb.append("        fun read(v: JsonValue, path: String): ${c.name} = when (Wire.pickShape(v, path, shapes)) {\n")
        for ((i, shape) in c.shapes.withIndex()) {
            sb.append(if (i < c.shapes.size - 1) "            $i -> ${shape.className}.read(v, path)\n" else "            else -> ${shape.className}.read(v, path)\n")
        }
        sb.append("        }\n    }\n}\n\n")
    }

    private fun emitClass(sb: StringBuilder, c: ClassDecl) {
        if (c.kind == ClassKind.UNION) {
            emitUnion(sb, c)
            return
        }
        sb.append(Naming.kdoc("", c.doc, fallback = "The `${c.name}` object."))
        val ordered = if (c.kind == ClassKind.RESPONSE) c.props else c.props.filter { !it.hasDefault } + c.props.filter { it.hasDefault }
        val base = if (c.kind == ClassKind.QUERY) "RewloyQuery()" else (c.parent?.let { "$it()" } ?: "RewloyObject()")
        if (ordered.isEmpty()) {
            sb.append("public class ${c.name} public constructor() : $base {\n")
        } else {
            sb.append("public class ${c.name}(\n")
            for (p in ordered) {
                sb.append(Naming.kdoc("    ", p.doc, fallback = "`${p.jsonName}`."))
                if (p.deprecated != null) sb.append("    @Deprecated(${Naming.literal(p.deprecated)})\n")
                val mutability = if (c.kind == ClassKind.RESPONSE) "val" else "var"
                sb.append("    public ${if (p.name in c.overrides) "override " else ""}$mutability ${Naming.ident(p.name)}: ${p.declared}${if (c.kind != ClassKind.RESPONSE && p.hasDefault) " = null" else ""},\n")
            }
            sb.append(") : $base {\n")
        }

        val required = ordered.filter { !it.hasDefault }
        if (c.kind != ClassKind.RESPONSE && required.size < ordered.size && required.isNotEmpty()) {
            sb.append("    /** The required fields only; set the rest with the setters. */\n")
            sb.append("    public constructor(${required.joinToString(", ") { "${Naming.ident(it.name)}: ${it.declared}" }}) : this(\n")
            sb.append(required.joinToString(",\n") { "        ${Naming.ident(it.name)}" }).append(",\n")
            sb.append(ordered.filter { it.hasDefault }.joinToString("\n") { "        null," }).append("\n    )\n\n")
        }

        when (c.kind) {
            ClassKind.RESPONSE -> {
                sb.append("    internal companion object {\n")
                sb.append("        fun read(v: JsonValue, path: String): ${c.name} {\n")
                sb.append("            val o = ObjectReader(v, path)\n")
                sb.append("            return ${c.name}(\n")
                for (p in c.props) sb.append("                ${Naming.ident(p.name)} = ${Codec.readProp(p)},\n")
                sb.append("            ).also { it.adopt(o.rest()) }\n")
                sb.append("        }\n    }\n")
            }
            ClassKind.REQUEST -> {
                sb.append("    internal override fun toJsonValue(): JsonObject {\n")
                sb.append("        val w = ObjectWriter()\n")
                for (p in c.props) sb.append("        ${Codec.writeProp(p)}\n")
                sb.append("        return w.finish(extras())\n    }\n")
            }
            ClassKind.UNION -> error("a union is emitted by emitUnion")
            ClassKind.QUERY -> {
                sb.append("    internal override fun writeTo(writer: QueryWriter) {\n")
                for (p in c.props) sb.append("        ${Codec.writeQuery(p)}\n")
                sb.append("    }\n")
                if (c.pagedOp != null) {
                    sb.append("\n    /** A copy of these parameters asking for another page. */\n")
                    sb.append("    internal fun withPage(page: Long): ${c.name} = ${c.name}(\n")
                    for (p in ordered) {
                        val value = if (p.jsonName == "page") (if (c.pageType == KType.Int32) "page.toInt()" else "page") else "this.${Naming.ident(p.name)}"
                        sb.append("        ${Naming.ident(p.name)} = $value,\n")
                    }
                    sb.append("    )\n")
                }
            }
        }
        sb.append("}\n\n")
    }

    // ------------------------------------------------------------------ RewloyOperations.kt

    private fun emitOperations(apiVersion: String, ops: List<Op>): String {
        val sb = StringBuilder(header(apiVersion, "com.rewloy", emptyList()))
        sb.append(Naming.kdoc("", "The metadata table: per operation, its method and path, the credential kinds it accepts, whether it takes `Rewloy-Merchant` and `Idempotency-Key`, how its answer is read and whether it is paged or deprecated."))
        sb.append("public object RewloyOperations {\n")
        sb.append(Naming.kdoc("    ", "The version of the API document this was generated from (`info.version`)."))
        sb.append("    public const val API_VERSION: String = ${Naming.literal(apiVersion)}\n")
        for (op in ops) {
            val kinds = op.auth.joinToString(", ") { "CredentialKind.${it.uppercase()}" }
            val idem = if (op.idempotency == null) "IdempotencyMode.NONE" else if (op.idempotency.required) "IdempotencyMode.REQUIRED" else "IdempotencyMode.OPTIONAL"
            val dep = op.deprecated?.let { "Deprecation(${it.sunset?.let(Naming::literal) ?: "null"}, ${it.use?.let(Naming::literal) ?: "null"})" } ?: "null"
            val response = when (op.response) { "json" -> "JSON"; "none" -> "NONE"; "blob" -> "FILE"; "raw-json" -> "RAW_JSON"; else -> "STREAM" }
            sb.append('\n')
            sb.append(Naming.kdoc("    ", "`${op.method} ${op.path}`: ${op.summary}"))
            sb.append("    @JvmField\n")
            sb.append("    public val ${op.id}: OperationInfo = OperationInfo(\n")
            sb.append("        ${Naming.literal(op.id)}, ${Naming.literal(op.method)}, ${Naming.literal(op.path)}, setOf($kinds),\n")
            sb.append("        ${op.merchant != null}, $idem, ${op.body != null}, ResponseKind.$response, ${op.paged}, $dep,\n")
            sb.append("    )\n")
        }
        sb.append('\n')
        sb.append(Naming.kdoc("    ", "Every operation by its operationId."))
        sb.append("    @JvmField\n    public val all: Map<String, OperationInfo> = linkedMapOf(\n")
        for (op in ops) sb.append("        ${Naming.literal(op.id)} to ${op.id},\n")
        sb.append("    )\n}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ ErrorCode.kt

    private fun emitErrorCodes(apiVersion: String, codes: List<String>, titles: Map<String, String>): String {
        val sb = StringBuilder(header(apiVersion, "com.rewloy", emptyList()))
        sb.append(Naming.kdoc("", "Every error code the API can answer with (the catalogue: https://rewloy.com/gelistiriciler/hatalar). New codes may be added without notice: keep a default branch. The codes are constants, not an enum, so that an unknown one is still a string."))
        sb.append("public object ErrorCode {\n")
        val used = HashSet<String>()
        for (code in codes) {
            val name = code.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
            if (name.isEmpty() || name[0] in '0'..'9' || !used.add(name)) throw GeneratorException("error code $code makes a constant name that is taken or invalid ($name)")
            sb.append(Naming.kdoc("    ", titles[code] ?: "`$code`"))
            sb.append("    public const val $name: String = ${Naming.literal(code)}\n\n")
        }
        sb.append(Naming.kdoc("    ", "Every code, in the catalogue's order."))
        sb.append("    @JvmField\n    public val all: List<String> = listOf(\n")
        for (code in codes) sb.append("        ${Naming.literal(code)},\n")
        sb.append("    )\n\n")
        sb.append(Naming.kdoc("    ", "Each code's one-line title in the catalogue (https://rewloy.com/gelistiriciler/hatalar)."))
        sb.append("    @JvmField\n    public val titles: Map<String, String> = linkedMapOf(\n")
        for (code in codes.filter { it in titles }) sb.append("        ${Naming.literal(code)} to ${Naming.literal(titles[code]!!)},\n")
        sb.append("    )\n\n")
        sb.append(Naming.kdoc("    ", "The title of a code, or `null` when the catalogue has none (a code newer than this library)."))
        sb.append("    @JvmStatic\n    public fun title(code: String?): String? = if (code == null) null else titles[code]\n}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ RewloyApi.kt

    private fun emitApi(apiVersion: String, ops: List<Op>, plans: Map<String, Plan>): String {
        val sb = StringBuilder(header(apiVersion, "com.rewloy", listOf("com.rewloy.json.JsonValue", "com.rewloy.models.*")))
        sb.append(Naming.kdoc("", "One method per operation of the API, named by its operationId. [Rewloy] extends it; this half is generated."))
        sb.append("public abstract class RewloyApi internal constructor() {\n")
        sb.append("    internal abstract fun <T> callJson(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?, body: JsonValue?, options: RequestOptions?, read: (JsonValue, String) -> T): RewloyResponse<T>\n")
        sb.append("    internal abstract fun callRaw(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?, body: JsonValue?, options: RequestOptions?): RewloyResponse<JsonValue>\n")
        sb.append("    internal abstract fun callNone(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?, body: JsonValue?, options: RequestOptions?): RewloyResponse<Unit>\n")
        sb.append("    internal abstract fun callFile(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?, body: JsonValue?, options: RequestOptions?): RewloyResponse<RewloyFile>\n")
        sb.append("    internal abstract fun openStream(op: OperationInfo, pathValues: Array<String>, query: RewloyQuery?, options: RequestOptions?): EventStream\n")

        var tag: String? = null
        for (op in ops) {
            val plan = plans[op.id]!!
            if (op.tag != tag) {
                tag = op.tag
                sb.append("\n    // ${"-".repeat(60)} $tag\n")
            }
            val signature = (plan.args.map { if (it.required) "${it.name}: ${it.type}" else "${it.name}: ${it.type}? = null" } + "options: RequestOptions? = null").joinToString(", ")
            val callArgs = plan.args.joinToString("") { "${it.name}, " }
            val pathValues = if (plan.pathExprs.isEmpty()) "emptyArray()" else "arrayOf(${plan.pathExprs.joinToString(", ")})"
            val bodyRequired = plan.bodyExpr != null && plan.args.first { it.name == "body" }.required
            val bodyExpr = when {
                plan.bodyExpr == null -> "null"
                plan.bodyIsJson -> "body"
                bodyRequired -> "body.toJsonValue()"
                else -> "body?.toJsonValue()"
            }
            val queryExpr = plan.queryExpr ?: "null"
            val deprecation = op.deprecated?.let { "    @Deprecated(${Naming.literal(deprecationMessage(op, it))})\n" } ?: ""

            fun doc(summary: String, whole: Boolean): String {
                val extra = ArrayList<String>()
                extra.add("`${op.method} ${op.path}`")
                extra.add("[API referansı]($REFERENCE#op-${op.id})")
                op.deprecated?.let { extra.add(deprecationNote(op, it)) }
                if (op.idempotency != null) {
                    extra.add(
                        if (op.idempotency.required) {
                            "`idempotencyKey` is required in the options: 8–64 printable ASCII characters. The call throws an `IllegalArgumentException` before sending when it is missing, and the client never makes one up (a generated key would not survive a restart of your program). The same key is sent on every retry of this call."
                        } else {
                            "`idempotencyKey` in the options is optional: 8–64 printable ASCII characters. When it is left out, the client generates a UUID and sends the same one on every retry of this call."
                        },
                    )
                }
                if (whole) extra.add("Returns the whole answer: the status, headers, `requestId`, `mode` (the `Rewloy-Mode` header) and `replayed` besides the data.")
                val text = if (whole) "$summary (the whole answer)" else listOf(summary, op.description).filter { it.isNotEmpty() }.joinToString("\n\n")
                val tags = plan.args.map { "@param ${it.name.trim('`')} ${it.doc.replace("\n", " ")}" } +
                    "@param options Per-call options: the idempotency key, the business (`Rewloy-Merchant`), the timeout, the retries, a cancel token."
                return Naming.kdoc("    ", text, extra, tags)
            }

            sb.append('\n')
            if (op.response == "stream") {
                sb.append(doc(op.summary, false))
                sb.append(deprecation)
                sb.append("    @JvmOverloads\n    public fun ${op.id}($signature): EventStream =\n        openStream(RewloyOperations.${op.id}, $pathValues, $queryExpr, options)\n")
                continue
            }

            val call: String
            val respType: String
            val plainType: String
            when (op.response) {
                "none" -> { respType = "RewloyResponse<Unit>"; plainType = "Unit"; call = "callNone(RewloyOperations.${op.id}, $pathValues, $queryExpr, $bodyExpr, options)" }
                "blob" -> { respType = "RewloyResponse<RewloyFile>"; plainType = "RewloyFile"; call = "callFile(RewloyOperations.${op.id}, $pathValues, $queryExpr, $bodyExpr, options)" }
                "raw-json" -> { respType = "RewloyResponse<JsonValue>"; plainType = "JsonValue"; call = "callRaw(RewloyOperations.${op.id}, $pathValues, $queryExpr, $bodyExpr, options)" }
                else -> {
                    respType = "RewloyResponse<${plan.dataType}>"
                    plainType = if (op.paged) "Page<${plan.itemType}>" else plan.dataType
                    call = "callJson<${plan.dataType}>(RewloyOperations.${op.id}, $pathValues, $queryExpr, $bodyExpr, options) { v, p -> ${plan.reader} }"
                }
            }

            sb.append(doc(op.summary, false))
            sb.append(deprecation)
            sb.append("    @JvmOverloads\n")
            when {
                op.response == "none" -> sb.append("    public fun ${op.id}($signature) {\n        ${op.id}WithResponse(${callArgs}options)\n    }\n")
                op.paged -> sb.append("    public fun ${op.id}($signature): $plainType =\n        Page.from(${op.id}WithResponse(${callArgs}options))\n")
                else -> sb.append("    public fun ${op.id}($signature): $plainType =\n        ${op.id}WithResponse(${callArgs}options).data\n")
            }

            sb.append('\n')
            sb.append(doc(op.summary, true))
            sb.append(deprecation)
            sb.append("    @JvmOverloads\n    public fun ${op.id}WithResponse($signature): $respType =\n        $call\n")

            if (op.paged) {
                val q = plan.queryType!!
                val pathArgs = plan.args.filter { it.name != "query" }
                val params = (pathArgs.map { "${it.name}: ${it.type}" } + (if (plan.queryRequired) "query: $q" else "query: $q? = null") + "options: RequestOptions? = null").joinToString(", ")
                val callPath = pathArgs.joinToString("") { "${it.name}, " }
                val queryNow = if (plan.queryRequired) "query" else "(query ?: $q())"
                val startPage = if (plan.queryRequired) "query.page" else "query?.page"
                val tags = pathArgs.map { "@param ${it.name.trim('`')} ${it.doc.replace("\n", " ")}" } + "@param query The query parameters." + "@param options Per-call options, applied to every page's request."
                sb.append('\n')
                sb.append(Naming.kdoc("    ", "Every item of `${op.id}`, page after page.", listOf("Walks every page: it asks for the next one (`page`) while the answer's `meta` says there is one. `page` in the query sets where to start and `limit` the page size. Iterating blocks; each call of `iterator()` starts a new walk."), tags))
                sb.append(deprecation)
                sb.append("    @JvmOverloads\n")
                sb.append("    public fun ${op.id}All($params): Iterable<${plan.itemType}> =\n")
                sb.append("        PagedIterable(($startPage)?.toLong() ?: 1L) { page -> ${op.id}WithResponse(${callPath}$queryNow.withPage(page), options) }\n")
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun deprecationMessage(op: Op, d: Dep): String =
        "${op.id} is deprecated" + (if (d.sunset == null) "." else "; the API stops answering it after ${d.sunset}.") + (if (d.use == null) "" else " Use ${d.use} instead.")

    private fun deprecationNote(op: Op, d: Dep): String =
        listOfNotNull(
            if (d.sunset == null) "The API will stop answering this operation." else "The API stops answering this operation after ${d.sunset}.",
            d.use?.let { "Use $it instead." },
            "$CHANGELOG#${op.id}",
        ).joinToString(" ")
}
