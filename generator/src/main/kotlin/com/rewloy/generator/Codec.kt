package com.rewloy.generator

/** The code the generated models read and write JSON with, as text. */
internal object Codec {
    /** An expression that reads [type] from the JSON value named [v] at path [p]; `T?` when [nullable]. */
    fun readValue(type: KType, nullable: Boolean, v: String, p: String, depth: Int): String {
        if (nullable && type != KType.Json) {
            val vn = "v$depth"
            val pn = "p$depth"
            return "Wire.nullable($v, $p) { $vn, $pn -> ${readCore(type, vn, pn, depth + 1)} }"
        }
        return readCore(type, v, p, depth)
    }

    private fun readCore(type: KType, v: String, p: String, depth: Int): String = when (type) {
        KType.Str -> "Wire.string($v, $p)"
        KType.Int32 -> "Wire.int($v, $p)"
        KType.Int64 -> "Wire.long($v, $p)"
        KType.Dbl -> "Wire.double($v, $p)"
        KType.Bool -> "Wire.bool($v, $p)"
        KType.Json -> "Wire.json($v, $p)"
        is KType.Obj -> "${type.name}.read($v, $p)"
        is KType.Lst -> "Wire.list($v, $p) { v$depth, p$depth -> ${readValue(type.element, type.elementNullable, "v$depth", "p$depth", depth + 1)} }"
        is KType.Mp -> "Wire.map($v, $p) { v$depth, p$depth -> ${readValue(type.element, type.elementNullable, "v$depth", "p$depth", depth + 1)} }"
    }

    /** The reader's call for one property of a response class. */
    fun readProp(p: PropDecl): String {
        val name = Naming.literal(p.jsonName)
        val optional = !p.required || p.nullable
        return when (p.type) {
            KType.Str -> if (optional) "o.strOrNull($name)" else "o.str($name)"
            KType.Int32 -> if (optional) "o.intOrNull($name)" else "o.int($name)"
            KType.Int64 -> if (optional) "o.longOrNull($name)" else "o.long($name)"
            KType.Dbl -> if (optional) "o.doubleOrNull($name)" else "o.double($name)"
            KType.Bool -> if (optional) "o.boolOrNull($name)" else "o.bool($name)"
            KType.Json -> if (p.required) "o.json($name)" else "o.jsonOrNull($name)"
            else -> "o.${if (optional) "opt" else "req"}($name) { x, y -> ${readCore(p.type, "x", "y", 1)} }"
        }
    }

    /** An expression that makes the JSON value of [v], of [type]; `Out.nullable` when [nullable]. */
    fun toJson(type: KType, nullable: Boolean, v: String, depth: Int): String {
        if (nullable && type != KType.Json) {
            val e = "e$depth"
            return "Out.nullable($v) { $e -> ${toJsonCore(type, e, depth + 1)} }"
        }
        return toJsonCore(type, v, depth)
    }

    private fun toJsonCore(type: KType, v: String, depth: Int): String = when (type) {
        KType.Str -> "Out.str($v)"
        KType.Int32 -> "Out.int($v)"
        KType.Int64 -> "Out.long($v)"
        KType.Dbl -> "Out.double($v)"
        KType.Bool -> "Out.bool($v)"
        KType.Json -> v
        is KType.Obj -> "Out.obj($v)"
        is KType.Lst -> "Out.list($v) { e$depth -> ${toJson(type.element, type.elementNullable, "e$depth", depth + 1)} }"
        is KType.Mp -> "Out.map($v) { e$depth -> ${toJson(type.element, type.elementNullable, "e$depth", depth + 1)} }"
    }

    /** The writer's statement for one property of a request class. */
    fun writeProp(p: PropDecl): String {
        val name = Naming.literal(p.jsonName)
        val v = "this.${Naming.ident(p.name)}"
        if (p.optionalField) return "w.optional($name, $v) { e -> ${toJsonCore(p.type, "e", 1)} }"
        return when (p.type) {
            KType.Str -> "w.str($name, $v)"
            KType.Int32 -> "w.int($name, $v)"
            KType.Int64 -> "w.long($name, $v)"
            KType.Dbl -> "w.double($name, $v)"
            KType.Bool -> "w.bool($name, $v)"
            KType.Json -> "w.json($name, $v)"
            is KType.Obj -> "w.obj($name, $v)"
            is KType.Lst -> "w.list($name, $v) { e -> ${toJson(p.type.element, p.type.elementNullable, "e", 1)} }"
            is KType.Mp -> "w.map($name, $v) { e -> ${toJson(p.type.element, p.type.elementNullable, "e", 1)} }"
        }
    }

    /** The query writer's statement for one parameter. */
    fun writeQuery(p: PropDecl): String {
        val name = Naming.literal(p.jsonName)
        val v = "this.${Naming.ident(p.name)}"
        return when (val t = p.type) {
            KType.Str, KType.Int32, KType.Int64, KType.Dbl, KType.Bool -> "writer.add($name, $v)"
            is KType.Lst -> when (t.element) {
                KType.Str -> "writer.addMany($name, $v)"
                KType.Int32 -> "writer.addManyInts($name, $v)"
                KType.Int64 -> "writer.addManyLongs($name, $v)"
                KType.Dbl -> "writer.addManyDoubles($name, $v)"
                KType.Bool -> "writer.addManyBooleans($name, $v)"
                else -> throw GeneratorException("query parameter ${p.jsonName}: a list of ${t.element.kotlin} is not supported")
            }
            else -> throw GeneratorException("query parameter ${p.jsonName}: ${t.kotlin} is not supported")
        }
    }
}
