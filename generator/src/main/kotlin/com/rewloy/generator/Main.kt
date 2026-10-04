package com.rewloy.generator

import com.rewloy.json.JsonValue
import com.rewloy.json.JsonWriter
import java.io.File
import java.net.HttpURLConnection
import kotlin.system.exitProcess

/**
 * ./gradlew generate                                  fetch the live document, keep it as openapi/openapi.json,
 *                                                     write rewloy/src/main/kotlin/com/rewloy/generated/
 * ./gradlew generate -Pfile=openapi/openapi.json      generate from a saved document, e.g. the committed
 *                                                     snapshot (reproducible builds)
 * ./gradlew generate -Purl=<url>                      fetch from another address
 *
 * The document is checked (the generator refuses what it does not understand) before anything is written; files
 * that did not change are left alone, and generated files that are no longer produced are removed.
 */
private const val LIVE = "https://app.rewloy.com/v1/openapi.json"
private const val SNAPSHOT = "openapi/openapi.json"

private fun arg(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    if (i == -1) return null
    val value = args.getOrNull(i + 1)
    if (value == null || value.startsWith("--")) throw GeneratorException("$name needs a value")
    return value
}

/** The snapshot as it is kept: what JavaScript's `JSON.stringify(document, null, 2)` writes, and a newline. */
fun snapshotText(document: JsonValue): String = JsonWriter.stringifyPretty(document) + "\n"

/** Writes when the content differs; returns whether it did. */
private fun put(file: File, content: String): Boolean {
    if (file.isFile && file.readText(Charsets.UTF_8) == content) return false
    file.parentFile.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return true
}

private fun fetch(url: String): String {
    val connection = java.net.URI(url).toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "rewloy-kotlin-generator")
    connection.connectTimeout = 60_000
    connection.readTimeout = 60_000
    val status = connection.responseCode
    if (status != 200) throw GeneratorException("GET $url: HTTP $status")
    return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

fun main(args: Array<String>) {
    try {
        val root = File(".").absoluteFile.normalize()
        val file = arg(args, "--file")
        val text = if (file != null) File(file).readText(Charsets.UTF_8) else fetch(arg(args, "--url") ?: LIVE)
        val document = JsonValue.parse(text)

        val files = ApiGenerator.generate(document)
        val changed = ArrayList<String>()
        if (file == null && put(File(root, SNAPSHOT), snapshotText(document))) changed.add(SNAPSHOT)
        for (f in files) if (put(File(root, f.path), f.content)) changed.add(f.path)

        // Generated files that are not produced any more (a tag that went away) are removed.
        val produced = files.map { File(root, it.path).canonicalPath }.toSet()
        val generatedRoot = File(root, "rewloy/src/main/kotlin/com/rewloy/generated")
        generatedRoot.walkTopDown().filter { it.isFile && it.extension == "kt" && it.canonicalPath !in produced }.forEach {
            it.delete()
            changed.add("removed ${it.relativeTo(root).path}")
        }

        println("${ApiGenerator.countOperations(document)} operations; " + if (changed.isEmpty()) "nothing changed" else "changed: ${changed.joinToString(", ")}")
    } catch (e: GeneratorException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}
