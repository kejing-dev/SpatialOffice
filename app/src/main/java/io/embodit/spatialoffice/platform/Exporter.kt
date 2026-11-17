package io.embodit.spatialoffice.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.files.Naming
import io.embodit.spatialoffice.core.pptx.ShapeKind
import java.io.File
import java.io.FileOutputStream

/** "Save as": writes a new file next to the app's other files. The file being edited is never touched. */
object Exporter {
    fun targets(s: Session): List<SaveAsTarget> = when {
        s is DeckSession -> listOf(SaveAsTarget.COPY, SaveAsTarget.PDF, SaveAsTarget.PNG)
        s is SheetSession -> listOf(SaveAsTarget.COPY, SaveAsTarget.CSV, SaveAsTarget.PDF)
        s.readOnly -> listOf(SaveAsTarget.COPY)
        else -> listOf(SaveAsTarget.COPY, SaveAsTarget.PDF, SaveAsTarget.TXT)
    }

    /** Where [target] will be written (a free name; for PNG the folder). */
    fun destination(graph: Graph, s: Session, target: SaveAsTarget, zh: Boolean): File {
        val dir = graph.storage.newDir
        val taken = dir.list()?.toList() ?: emptyList()
        val base = s.ref.name.substringBeforeLast('.')
        return when (target) {
            SaveAsTarget.COPY -> {
                val copy = Naming.copyName(s.ref.name, zh)
                File(dir, Naming.unique(taken, copy.substringBeforeLast('.'), copy.substringAfterLast('.')))
            }
            SaveAsTarget.PDF -> File(dir, Naming.unique(taken, base, "pdf"))
            SaveAsTarget.CSV -> File(dir, Naming.unique(taken, base, "csv"))
            SaveAsTarget.TXT -> File(dir, Naming.unique(taken, base, "txt"))
            SaveAsTarget.PNG -> { var n = base; var i = 2; while (n in taken) n = "$base ${i++}"; File(dir, n) }
            SaveAsTarget.CLOUD -> File(dir, s.ref.name)
        }
    }

    fun saveAs(graph: Graph, s: Session, target: SaveAsTarget, zh: Boolean): File {
        val out = destination(graph, s, target, zh)
        out.parentFile?.mkdirs()
        when (target) {
            SaveAsTarget.COPY -> out.writeBytes(if (s.readOnly) graph.storage.read(s.ref) else s.serialize())
            SaveAsTarget.CSV -> out.writeText((s as SheetSession).book.toCsv(s.sheet))
            SaveAsTarget.TXT -> out.writeText((s as DocSession).plainText())
            SaveAsTarget.CLOUD -> error("cloud copies go through Accounts.saveCopy")
            SaveAsTarget.PDF -> when (s) {
                is DeckSession -> deckPdf(s, out)
                is SheetSession -> sheetPdf(s, out)
                is DocSession -> docPdf(s, out)
                else -> error("unsupported")
            }
            SaveAsTarget.PNG -> {
                val deck = s as DeckSession
                out.mkdirs()
                for (i in deck.deck.slides.indices) {
                    val bmp = Bitmap.createBitmap(1920, (1920f * deck.deck.heightEmu / deck.deck.widthEmu).toInt(), Bitmap.Config.ARGB_8888)
                    drawSlide(Canvas(bmp), deck, i, bmp.width.toFloat(), bmp.height.toFloat())
                    FileOutputStream(File(out, String.format(java.util.Locale.ROOT, "%02d.png", i + 1))).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
            }
        }
        return out
    }

    // ---- documents: A4, text flow ----

    private fun docPdf(s: DocSession, out: File) {
        val pw = 595; val ph = 842; val margin = 56f
        val pdf = PdfDocument()
        var pageNo = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
        var y = margin
        fun room(h: Float) {
            if (y + h > ph - margin && y > margin) {
                pdf.finishPage(page); pageNo++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create()); y = margin
            }
        }
        val width = (pw - 2 * margin).toInt()
        for (b in s.blocks()) {
            when (b) {
                is UiBlock.Para -> {
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = when (b.style) { ParaStyle.TITLE -> 22f; ParaStyle.H1 -> 18f; ParaStyle.H2 -> 15f; ParaStyle.H3 -> 13f; else -> 11f }
                        typeface = if (b.style == ParaStyle.BODY || b.style == ParaStyle.BULLET || b.style == ParaStyle.NUMBERED) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                    }
                    val text = (if (b.style == ParaStyle.BULLET) "• " else "") + b.spans.joinToString("") { it.text }
                    val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.15f).build()
                    room(layout.height + 8f)
                    page.canvas.save(); page.canvas.translate(margin, y); layout.draw(page.canvas); page.canvas.restore()
                    y += layout.height + if (b.style == ParaStyle.BODY) 8f else 12f
                }
                is UiBlock.Table -> {
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f }
                    val line = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
                    val cols = b.rows.maxOfOrNull { it.size } ?: 0
                    if (cols > 0) {
                        val cw = width / cols
                        for (row in b.rows) {
                            val cells = row.map { StaticLayout.Builder.obtain(it, 0, it.length, tp, (cw - 8).coerceAtLeast(8)).build() }
                            val h = (cells.maxOfOrNull { it.height } ?: 12) + 8f
                            room(h)
                            cells.forEachIndexed { i, l ->
                                page.canvas.drawRect(margin + i * cw, y, margin + (i + 1) * cw, y + h, line)
                                page.canvas.save(); page.canvas.translate(margin + i * cw + 4, y + 4); l.draw(page.canvas); page.canvas.restore()
                            }
                            y += h
                        }
                        y += 10f
                    }
                }
                is UiBlock.Image, is UiBlock.Page -> {
                    val bmp = (b as? UiBlock.Image)?.bitmap ?: (b as? UiBlock.Page)?.bitmap ?: continue
                    val w = minOf(width.toFloat(), bmp.width.toFloat())
                    val h = w * bmp.height / bmp.width
                    room(h + 8f)
                    page.canvas.drawBitmap(bmp, null, RectF(margin, y, margin + w, y + h), null)
                    y += h + 10f
                }
                is UiBlock.Opaque -> {}
            }
        }
        pdf.finishPage(page)
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
    }

    // ---- spreadsheets: current sheet as a grid, landscape A4 ----

    private fun sheetPdf(s: SheetSession, out: File) {
        val pw = 842; val ph = 595; val margin = 36f
        val book = s.book
        val rows = book.rows(s.sheet); val cols = book.cols(s.sheet)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f }
        val line = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val widths = FloatArray(cols) { c -> (0 until rows).maxOf { tp.measureText(book.cell(s.sheet, it, c).display) }.coerceIn(28f, 160f) + 10f }
        val rowH = 16f
        val pdf = PdfDocument()
        var pageNo = 1
        var c0 = 0
        while (c0 < cols) {
            var c1 = c0; var w = 0f
            while (c1 < cols && (w + widths[c1] <= pw - 2 * margin || c1 == c0)) { w += widths[c1]; c1++ }
            var r0 = 0
            while (r0 < rows) {
                val r1 = minOf(rows, r0 + ((ph - 2 * margin) / rowH).toInt())
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo++).create())
                var y = margin
                for (r in r0 until r1) {
                    var x = margin
                    for (c in c0 until c1) {
                        val cell = book.cell(s.sheet, r, c)
                        tp.typeface = if (cell.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        page.canvas.drawRect(x, y, x + widths[c], y + rowH, line)
                        page.canvas.save(); page.canvas.clipRect(x + 2, y, x + widths[c] - 2, y + rowH)
                        val tx = if (cell.number != null) x + widths[c] - 4 - tp.measureText(cell.display) else x + 4
                        page.canvas.drawText(cell.display, tx, y + rowH - 4.5f, tp)
                        page.canvas.restore()
                        x += widths[c]
                    }
                    y += rowH
                }
                pdf.finishPage(page)
                r0 = r1
            }
            c0 = c1
        }
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
    }

    // ---- slides ----

    private fun deckPdf(s: DeckSession, out: File) {
        val w = 960; val h = (960f * s.deck.heightEmu / s.deck.widthEmu).toInt()
        val pdf = PdfDocument()
        for (i in s.deck.slides.indices) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            drawSlide(page.canvas, s, i, w.toFloat(), h.toFloat())
            pdf.finishPage(page)
        }
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
    }

    /** Draws slide [index] onto [canvas] at [w] × [h]: background, pictures, text boxes; other objects as a light frame. */
    fun drawSlide(canvas: Canvas, s: DeckSession, index: Int, w: Float, h: Float) {
        val deck = s.deck
        val slide = deck.slides[index]
        canvas.drawColor(slide.backgroundRgb?.let { 0xFF000000.toInt() or it } ?: Color.WHITE)
        val sx = w / deck.widthEmu; val sy = h / deck.heightEmu
        val ptPx = h / (deck.heightEmu / 12700f)
        for (sh in slide.shapes) {
            val r = RectF(sh.x * sx, sh.y * sy, (sh.x + sh.w) * sx, (sh.y + sh.h) * sy)
            when (sh.kind) {
                ShapeKind.PICTURE -> sh.imagePart?.let { s.image(it) }?.let { canvas.drawBitmap(it, null, r, Paint(Paint.FILTER_BITMAP_FLAG)) }
                ShapeKind.TITLE, ShapeKind.BODY, ShapeKind.TEXT -> {
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = sh.colorRgb?.let { 0xFF000000.toInt() or it } ?: Color.rgb(0x17, 0x18, 0x1C)
                        textSize = (sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f) * ptPx
                        typeface = if (sh.bold || sh.kind == ShapeKind.TITLE) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val text = sh.paras.joinToString("\n") { (if (it.bullet) "    ".repeat(it.level) + "• " else "") + it.text }
                    if (text.isBlank() || r.width() < 4) continue
                    val align = when (sh.align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                    val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, r.width().toInt().coerceAtLeast(8)).setAlignment(align).build()
                    canvas.save()
                    canvas.translate(r.left, if (sh.kind == ShapeKind.TITLE) r.top + ((r.height() - layout.height) / 2f).coerceAtLeast(0f) else r.top)
                    layout.draw(canvas)
                    canvas.restore()
                }
                else -> canvas.drawRoundRect(r, 6f, 6f, Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1.5f })
            }
        }
    }
}
