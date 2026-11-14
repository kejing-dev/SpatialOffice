package io.embodit.spatialoffice.core.csv

/** Comma-separated values per RFC 4180: fields quoted with `"` when they need it, `""` for a quote, CRLF records. */
object Csv {

    /**
     * Parses [text] into records. Accepts CRLF, LF or CR record ends, quoted fields with embedded commas, quotes and
     * line breaks, and a leading byte-order mark. A final line break does not produce an extra empty record.
     */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = if (text.startsWith("﻿")) 1 else 0
        val n = text.length
        var inQuotes = false
        var any = false // the current record has at least one character or separator
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    inQuotes = false
                } else field.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> { inQuotes = true; any = true }
                ',' -> { row.add(field.toString()); field.setLength(0); any = true }
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row); row = ArrayList(); any = false
                }
                else -> { field.append(c); any = true }
            }
            i++
        }
        if (any || field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    /** Writes [rows] with CRLF after every record. A field is quoted when it holds a comma, a quote, a line break, or leading / trailing white space. */
    fun write(rows: List<List<String>>): String {
        val sb = StringBuilder()
        for (r in rows) {
            for ((k, f) in r.withIndex()) {
                if (k > 0) sb.append(',')
                sb.append(quote(f))
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    private fun quote(f: String): String {
        val needs = f.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            (f.isNotEmpty() && (f[0] == ' ' || f[f.length - 1] == ' ' || f[0] == '\t' || f[f.length - 1] == '\t'))
        return if (needs) "\"" + f.replace("\"", "\"\"") + "\"" else f
    }
}
