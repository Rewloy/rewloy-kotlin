package com.rewloy.generator

import com.rewloy.json.JsonArray
import com.rewloy.json.JsonBoolean
import com.rewloy.json.JsonObject
import com.rewloy.json.JsonString
import com.rewloy.json.JsonValue

/** The generator refuses what it does not understand, loudly: a regeneration that needs a human fails. */
class GeneratorException(message: String) : RuntimeException("generate: $message")

/** A file the generator writes, by its path from the repository root. */
class GeneratedFile(val path: String, val content: String)

internal fun JsonValue?.prop(name: String): JsonValue? = (this as? JsonObject)?.members?.get(name)

internal fun JsonValue?.str(): String? = (this as? JsonString)?.value

internal fun JsonValue?.str(name: String): String? = prop(name).str()

internal fun JsonValue?.flag(name: String): Boolean = (prop(name) as? JsonBoolean)?.value == true

internal fun JsonValue?.members(): Map<String, JsonValue> = (this as? JsonObject)?.members ?: emptyMap()

internal fun JsonValue?.items(): List<JsonValue> = (this as? JsonArray)?.items ?: emptyList()

internal fun JsonValue?.isObject(): Boolean = this is JsonObject

internal object Naming {
    /** Kotlin's hard keywords: a property or parameter called one of these is written in backticks. */
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
        "when", "while",
    )

    /** "programId" → "ProgramId", "kvkk-consent" → "KvkkConsent": each run of letters and digits starts upper-case. */
    fun pascal(name: String): String {
        val sb = StringBuilder(name.length)
        var up = true
        for (c in name) {
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')) {
                up = true
                continue
            }
            sb.append(if (up) c.uppercaseChar() else c)
            up = false
        }
        if (sb.isEmpty()) throw GeneratorException("\"$name\" has no letters or digits to make a name from")
        if (sb[0] in '0'..'9') sb.insert(0, '_')
        return sb.toString()
    }

    /** "programId" → "programId", "program_id" → "programId", "URLValue" → "urlValue", "ID" → "id". */
    fun camel(name: String): String {
        val runs = Regex("[A-Za-z0-9]+").findAll(name).map { it.value }.toList()
        if (runs.isEmpty()) throw GeneratorException("\"$name\" has no letters or digits to make a name from")
        val sb = StringBuilder()
        for ((i, run) in runs.withIndex()) {
            sb.append(if (i == 0) lowerFirst(run) else run.replaceFirstChar { it.uppercaseChar() })
        }
        if (sb[0] in '0'..'9') sb.insert(0, '_')
        return sb.toString()
    }

    private fun lowerFirst(run: String): String {
        if (run[0] !in 'A'..'Z') return run
        // A leading run of capitals is an acronym: lower all of it, except the last when a lower-case letter follows.
        var end = 0
        while (end < run.length && run[end] in 'A'..'Z') end++
        val upto = if (end < run.length && run[end] in 'a'..'z' && end > 1) end - 1 else end
        return run.substring(0, if (upto == 0) 1 else upto).lowercase() + run.substring(if (upto == 0) 1 else upto)
    }

    /** A name as a Kotlin identifier: in backticks when it is a keyword. */
    fun ident(name: String): String = if (name in keywords) "`$name`" else name

    /** A Kotlin string literal (regular, escaped), for text that is not code. */
    fun literal(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '$' -> sb.append("\\$")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Text safe inside a KDoc block: neither `*` `/` nor `/` `*` (Kotlin comments nest) and no stray carriage returns. */
    fun kdocSafe(text: String): String = text.replace("\r", "").replace("*/", "*&#47;").replace("/*", "/&#42;")

    /**
     * A KDoc block at [indent]: the paragraphs of [text], then [extra] paragraphs (already KDoc), then `@param`
     * style [tags] lines. Nothing when there is nothing to say.
     */
    fun kdoc(indent: String, text: String?, extra: List<String> = emptyList(), tags: List<String> = emptyList(), fallback: String? = null): String {
        val paragraphs = (text ?: "").replace("\r", "").split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.map { kdocSafe(it) }
        val body = ArrayList<String>()
        if (paragraphs.isEmpty() && fallback != null) body.add(kdocSafe(fallback))
        body.addAll(paragraphs)
        body.addAll(extra.map { kdocSafe(it) })
        if (body.isEmpty() && tags.isEmpty()) return ""
        val lines = ArrayList<String>()
        for ((i, p) in body.withIndex()) {
            if (i > 0) lines.add("")
            lines.addAll(p.split("\n").map { it.trimEnd() })
        }
        if (tags.isNotEmpty()) {
            if (lines.isNotEmpty()) lines.add("")
            for (t in tags) lines.addAll(kdocSafe(t).split("\n").map { it.trimEnd() })
        }
        if (lines.size == 1) return "$indent/** ${lines[0]} */\n"
        val sb = StringBuilder("$indent/**\n")
        for (l in lines) sb.append(if (l.isEmpty()) "$indent *\n" else "$indent * $l\n")
        sb.append("$indent */\n")
        return sb.toString()
    }

    private val ascii = mapOf(
        'ı' to "i", 'İ' to "I", 'ş' to "s", 'Ş' to "S", 'ğ' to "g", 'Ğ' to "G", 'ü' to "u", 'Ü' to "U", 'ö' to "o",
        'Ö' to "O", 'ç' to "c", 'Ç' to "C",
    )

    /** A tag as a file name: "Kasa kampanyaları" → "KasaKampanyalari", "İşletme" → "Isletme". */
    fun fileStem(tag: String): String {
        val plain = StringBuilder()
        for (c in tag) plain.append(ascii[c] ?: c.toString())
        val words = Regex("[A-Za-z0-9]+").findAll(plain).map { it.value }.toList()
        if (words.isEmpty()) return "Other"
        return words.joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
}
