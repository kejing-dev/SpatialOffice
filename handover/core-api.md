# `:core` module — API contract

Pure Kotlin/JVM (`core/`, package `io.embodit.spatialoffice.core`). No Android imports, no third-party runtime
dependencies (only `java.*` and the Kotlin stdlib — no `javax.xml` DOM/Transformer either: use `pkg/Xml.kt`).
The UI is being written against the signatures below at the same time, so **names, parameter order and types are fixed**.
You may add members; do not rename or remove these.

Shared, already written and tested (do not rewrite; add helpers in your own files if you need more):
- `pkg/OoxmlPackage.kt` — `OoxmlPackage` (read / put / write, part order and untouched bytes preserved), `Rels`, `ContentTypes`.
- `pkg/Xml.kt` — `Xml` range scanner (`root`, `children`, `find`, `findAll`, `attr`, `text`, `splice`, `escape`, …).

## The rule that matters most: save only what changed

- `open(bytes)` never modifies anything. `save()` on an untouched object returns a package whose every part is
  byte-identical to the input.
- After an edit, only the parts that hold the edit are rewritten, and inside such a part only the edited element's
  character range is replaced (`Xml.splice`). Unknown elements, attributes, namespaces, extension lists, comments,
  tracked changes, custom XML, macros, embedded objects: never dropped, never reordered, never re-serialised.
- Anything the model cannot represent is surfaced as read-only (`editable = false` / opaque block) rather than guessed.
- Tests must prove it: for each fixture, edit one thing, save, reopen, and assert (a) every other part is
  byte-identical, (b) in the edited part everything outside the edited element is identical (compare prefix and
  suffix), (c) the edit reads back.

## New files

`blank()` creates a minimal, valid, standard file. Document properties: application name `Spatial Office`, no
author. Never write another vendor's product name anywhere (strings, comments, test names).

## docx — `core/docx/`

```kotlin
enum class ParaStyle { BODY, TITLE, H1, H2, H3, BULLET, NUMBERED }

/** src = index of the original run (in this paragraph as opened) whose remaining formatting (font, size, colour…)
 *  this run keeps; -1 = take it from the paragraph's first original run, or none. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false,
               val underline: Boolean = false, val src: Int = -1)

sealed class DocBlock { abstract val id: Int }
data class Paragraph(override val id: Int, val style: ParaStyle, val runs: List<Run>,
                     val editable: Boolean, val readOnlyWhy: String?) : DocBlock()   // "tracked-changes" | "field" | "complex"
data class TableBlock(override val id: Int, val rows: List<List<String>>) : DocBlock()  // read-only in v0.1
data class ImageBlock(override val id: Int, val part: String, val widthEmu: Long, val heightEmu: Long) : DocBlock()
data class OpaqueBlock(override val id: Int, val kind: String) : DocBlock()  // "smart-graphic" | "chart" | "text-box" | "section" | "other"

enum class CompatKind { APPROX, READ_ONLY }
/** code examples: "columns", "text-box", "font:<name>", "smart-graphic", "tracked-changes", "comments", "chart" */
data class CompatNote(val kind: CompatKind, val code: String, val count: Int)

class DocxDocument {
    companion object { fun open(bytes: ByteArray): DocxDocument; fun blank(): DocxDocument }
    val blocks: List<DocBlock>          // body order; ids are stable while this object lives
    fun partBytes(part: String): ByteArray?
    fun compat(): List<CompatNote>
    fun setParagraph(id: Int, runs: List<Run>, style: ParaStyle)
    fun insertParagraphAfter(id: Int, runs: List<Run>, style: ParaStyle): Int   // id = -1 → at the start; returns new id
    fun deleteBlock(id: Int)
    val dirty: Boolean
    val changedBlockCount: Int
    fun firstLine(): String             // first non-empty paragraph text, trimmed
    fun plainText(): String
    fun save(): ByteArray
}
```
Notes: heading / list styles map through `styles.xml` (`w:pStyle` → style name / `w:outlineLvl`; lists via
`w:numPr`). `setParagraph` keeps the original `w:pPr` unless the style changed, and each run's original `w:rPr`
with only b / i / u toggled. A paragraph that contains tracked changes (`w:ins` / `w:del`), fields, or drawings
mixed with text is `editable = false`. Paragraphs made only of an inline picture become `ImageBlock`.
`BULLET` / `NUMBERED` on a new paragraph may add a numbering definition to `numbering.xml` (creating the part if needed).

## pptx — `core/pptx/`

```kotlin
enum class ShapeKind { TITLE, BODY, TEXT, PICTURE, CHART, OTHER }
data class TextPara(val text: String, val level: Int = 0, val bullet: Boolean = false)
/** x / y / w / h in EMU, resolved through layout and master placeholders when the slide omits `a:xfrm`. */
data class Shape(val id: Int, val kind: ShapeKind, val x: Long, val y: Long, val w: Long, val h: Long,
                 val paras: List<TextPara>, val fontPt: Float?, val bold: Boolean, val colorRgb: Int?,
                 val align: Int /* 0 left, 1 centre, 2 right */, val imagePart: String?, val editable: Boolean)
data class Slide(val index: Int, val shapes: List<Shape>, val notes: String, val backgroundRgb: Int?)
enum class SlideLayoutKind { TITLE, TITLE_BODY }

class PptxDeck {
    companion object { fun open(bytes: ByteArray): PptxDeck; fun blank(): PptxDeck }   // blank = one title slide
    val widthEmu: Long
    val heightEmu: Long
    val slides: List<Slide>
    fun partBytes(part: String): ByteArray?
    fun compat(): List<CompatNote>      // reuse docx CompatNote / CompatKind
    fun setShapeText(slide: Int, shapeId: Int, paras: List<TextPara>)
    fun setNotes(slide: Int, text: String)
    fun addSlide(after: Int, kind: SlideLayoutKind): Int     // after = -1 → first; returns new index
    fun deleteSlide(index: Int)
    fun moveSlide(from: Int, to: Int)
    fun addTextBox(slide: Int, x: Long, y: Long, w: Long, h: Long, text: String): Int   // returns shape id
    val dirty: Boolean
    fun firstTitle(): String
    fun save(): ByteArray
}
```
Notes: `Shape.id` = `p:cNvPr/@id`. `setShapeText` keeps `a:bodyPr`, `a:lstStyle`, each paragraph's `a:pPr` and
the first run's `a:rPr`. Group shapes, tables, SmartArt-style graphic frames, media → `OTHER`, `editable = false`
(still report their box so the UI can draw a placeholder). `fontPt` falls back through layout → master → 18.

## xlsx — `core/xlsx/`

```kotlin
enum class CellKind { EMPTY, NUMBER, TEXT, BOOL, ERROR }
enum class NumFmt { GENERAL, CURRENCY, PERCENT, DATE, OTHER }
/** input = what the formula bar shows ("=SUM(B2:B5)", "12.5", "text", "" when empty); display = formatted value. */
data class Cell(val input: String, val display: String, val kind: CellKind, val number: Double?,
                val isFormula: Boolean, val bold: Boolean, val fmt: NumFmt)
data class ChartSeries(val name: String, val categories: List<String>, val values: List<Double?>,
                       val valuesRef: String?, val xValues: List<Double?>?)
data class ChartInfo(val part: String, val sheet: Int, val title: String,
                     val type: String /* "bar" | "line" | "pie" | "scatter" | "area" | "other" */,
                     val series: List<ChartSeries>, val range: String? /* covering range like "A1:M5", when derivable */)

class XlsxWorkbook {
    companion object { fun open(bytes: ByteArray): XlsxWorkbook; fun blank(): XlsxWorkbook }   // blank = one sheet
    val sheetNames: List<String>
    fun rows(sheet: Int): Int                      // used range, ≥ 1
    fun cols(sheet: Int): Int
    fun cell(sheet: Int, row: Int, col: Int): Cell // 0-based; EMPTY outside the used range
    fun colWidthChars(sheet: Int, col: Int): Float
    fun setCell(sheet: Int, row: Int, col: Int, input: String)  // "=…" formula; number ("12%", "¥1,200", "1e3"); TRUE/FALSE; text; "" clears
    fun setBold(sheet: Int, row: Int, col: Int, bold: Boolean)
    fun setFmt(sheet: Int, row: Int, col: Int, fmt: NumFmt)
    fun insertRowBelow(sheet: Int, row: Int)
    fun sortRows(sheet: Int, col: Int, ascending: Boolean, firstRow: Int, lastRow: Int): Boolean
    fun autoSum(sheet: Int, row: Int, col: Int): String?        // sets =SUM(…) over the numbers above (else to the left); returns the formula
    fun charts(): List<ChartInfo>                  // values resolved live from cells, so they follow edits
    fun addBarChart(sheet: Int, range: String, title: String): ChartInfo
    val dirty: Boolean
    fun compat(): List<CompatNote>
    fun toCsv(sheet: Int): String
    fun save(): ByteArray
}
object A1 { fun name(row: Int, col: Int): String; fun parse(ref: String): Pair<Int, Int>?; fun colName(col: Int): String }
```
Notes: formulas are evaluated in-module (`+ - * / ^ &`, comparisons, `%`, parentheses, ranges, cross-sheet refs,
`SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS AND OR NOT INT`), cycle-safe; an unknown function keeps the cached
value from the file and stays editable as text of the formula. Edited cells are written as numbers or inline
strings so `sharedStrings.xml` is not rewritten. When any formula's value may have changed, write fresh cached
`<v>` values for the formulas this module evaluated. Cells carry their original style index unless bold / format
was changed (then append one `xf` to `styles.xml`). Merged cells, conditional formats, data validation, pivot
tables, defined names: untouched and listed in `compat()`. `csv/Csv.kt`: `Csv.parse(text): List<List<String>>`
and `Csv.write(rows): String` (RFC 4180 quoting).

## Tests

- `core/src/test/kotlin/...`, JUnit 4. Run with
  `export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home && ./gradlew :core:test --console=plain -q --tests '<your package>.*'`
  from the project root (another agent builds in the same Gradle project; run only your own tests).
- Fixtures: generate realistic files with the independent libraries in `tools/.venv` (`tools/.venv/bin/python`,
  has python-docx, openpyxl, python-pptx) via a script you keep in `tools/fixtures/`, commit the generated files to
  `core/src/test/resources/fixtures/`. Include content the model does not understand (a table with merged cells,
  a comment, a custom XML part, an image, a chart, tracked changes written by hand into the XML) to prove it survives.
- Independent check: a script in `tools/verify/` that opens the files your tests save (write them to
  `core/build/roundtrip/`) with those same libraries and asserts the edit and the untouched content. Run it and
  report the result.
