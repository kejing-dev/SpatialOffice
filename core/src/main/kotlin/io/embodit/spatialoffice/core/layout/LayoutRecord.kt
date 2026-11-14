package io.embodit.spatialoffice.core.layout

/**
 * The spatial layout of one file: a presentation layer kept outside the document (app data, or a sidecar
 * `<file>.spatial.json` when the user chooses "keep with the file"). Schema `spatial-layout/1.0`.
 * Anchors name content by structure (`heading:2`, `sheet:0`, `slide:5`, `chart:0`), never by byte position, so a
 * record still fits after the file was edited elsewhere.
 */
data class LayoutRecord(
    val file: String,
    val arrangement: Arrangement = Arrangement.ARC,
    val focus: String = "",
    val radius: Float = 1.5f,
    val spread: Boolean = false,
    val name: String = "",
    val pins: List<String> = emptyList(),
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        append("  \"file\": ").append(Json.str(file)).append(",\n")
        append("  \"arrangement\": \"").append(arrangement.name.lowercase()).append("\",\n")
        append("  \"focus\": ").append(Json.str(focus)).append(",\n")
        append("  \"radius\": ").append(Json.num(radius)).append(",\n")
        append("  \"spread\": ").append(spread).append(",\n")
        append("  \"name\": ").append(Json.str(name)).append(",\n")
        append("  \"pins\": [").append(pins.joinToString(", ") { Json.str(it) }).append("]\n")
        append("}\n")
    }

    companion object {
        const val SCHEMA = "spatial-layout/1.0"
        const val SIDECAR_SUFFIX = ".spatial.json"

        /** Null when [json] is not a record of this schema (unknown or newer major version, or broken). */
        fun parse(json: String): LayoutRecord? {
            val o = runCatching { Json.parse(json) }.getOrNull() as? Map<*, *> ?: return null
            val schema = o["schema"] as? String ?: return null
            if (!schema.startsWith("spatial-layout/1.")) return null
            val arr = (o["arrangement"] as? String)?.let { a -> Arrangement.values().firstOrNull { it.name.equals(a, true) } } ?: Arrangement.ARC
            return LayoutRecord(
                file = o["file"] as? String ?: "",
                arrangement = arr,
                focus = o["focus"] as? String ?: "",
                radius = (o["radius"] as? Double)?.toFloat() ?: 1.5f,
                spread = o["spread"] as? Boolean ?: false,
                name = o["name"] as? String ?: "",
                pins = (o["pins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            )
        }
    }
}

/** A minimal JSON reader / writer (objects, arrays, strings, numbers, booleans, null). No dependencies. */
object Json {
    fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }

    fun num(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    /** Writes maps, lists, strings, numbers, booleans and null. */
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Float -> num(v)
        is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { str(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> str(v.toString())
    }

    /** Objects → Map<String, Any?>, arrays → List<Any?>, numbers → Double. */
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "trailing characters" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> { require(c == '-' || c.isDigit()) { "unexpected '$c' at $i" }; number() }
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }
        fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "expected ] at $i" }; i++; return out
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); require(s[i] == '"') { "expected key at $i" }
                val k = string(); ws()
                require(s[i] == ':') { "expected : at $i" }; i++
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "expected } at $i" }; i++; return out
            }
        }
    }
}
