package io.embodit.spatialoffice.core.xlsx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml

/** One series as the chart part describes it: reference formulas plus the values cached in the chart itself. */
internal class SeriesDef(
    val nameRef: String?, val nameLit: String?,
    val catRef: String?, val catCache: List<String>?,
    val valRef: String?, val valCache: List<Double?>?,
)

internal class ChartDef(
    val part: String, val sheet: Int, val titleRef: String?, val titleLit: String, val type: String,
    val series: List<SeriesDef>,
)

/** Reading chart parts (prefix-agnostic) and writing a standard clustered bar chart with its drawing anchor. */
internal object Charts {
    const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL_DRAWING = "$REL_NS/drawing"
    const val REL_CHART = "$REL_NS/chart"
    const val CT_CHART = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
    const val CT_DRAWING = "application/vnd.openxmlformats-officedocument.drawing+xml"
    private const val NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"

    /** Drawing part of a sheet, or null. */
    fun drawingOf(pkg: OoxmlPackage, sheetPart: String): String? =
        Rels.of(pkg, sheetPart).firstOrNull { !it.external && it.type.endsWith("/drawing") }?.let { OoxmlPackage.resolve(sheetPart, it.target) }

    /** Charts reached through sheet → drawing → chart relationships, in sheet order. */
    fun read(pkg: OoxmlPackage, sheetParts: List<String>): List<ChartDef> {
        val out = ArrayList<ChartDef>()
        for ((si, sp) in sheetParts.withIndex()) {
            for (dr in Rels.of(pkg, sp)) {
                if (dr.external || !dr.type.endsWith("/drawing")) continue
                val dPart = OoxmlPackage.resolve(sp, dr.target)
                for (cr in Rels.of(pkg, dPart)) {
                    if (cr.external || !cr.type.endsWith("/chart")) continue
                    val cPart = OoxmlPackage.resolve(dPart, cr.target)
                    val xml = pkg.text(cPart) ?: continue
                    parse(cPart, si, xml)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    private fun typeOf(local: String): String = when (local) {
        "barChart", "bar3DChart" -> "bar"
        "lineChart", "line3DChart" -> "line"
        "pieChart", "pie3DChart", "doughnutChart", "ofPieChart" -> "pie"
        "scatterChart", "bubbleChart" -> "scatter"
        "areaChart", "area3DChart" -> "area"
        else -> "other"
    }

    private fun parse(part: String, sheet: Int, xml: String): ChartDef? {
        val root = Xml.root(xml) ?: return null
        val chart = XmlX.kid(xml, root, "chart") ?: return null
        var titleRef: String? = null
        var titleLit = ""
        val tx = XmlX.path(xml, chart, "title", "tx")
        if (tx != null) {
            val ref = XmlX.kid(xml, tx, "strRef")
            if (ref != null) {
                titleRef = XmlX.kid(xml, ref, "f")?.let { Xml.text(xml, it) }
                titleLit = XmlX.kid(xml, ref, "strCache")?.let { strPts(xml, it).firstOrNull() } ?: ""
            } else {
                val rich = XmlX.kid(xml, tx, "rich")
                if (rich != null) {
                    titleLit = XmlX.kids(xml, rich, "p").joinToString(" ") { p ->
                        XmlX.findAllLocal(xml, p, "t").joinToString("") { Xml.text(xml, it) }
                    }.trim()
                }
            }
        }
        val plot = XmlX.kid(xml, chart, "plotArea") ?: return ChartDef(part, sheet, titleRef, titleLit, "other", emptyList())
        val groups = Xml.children(xml, plot).filter { Xml.local(it.name).endsWith("Chart") }
        val type = groups.firstOrNull()?.let { typeOf(Xml.local(it.name)) } ?: "other"
        val series = ArrayList<SeriesDef>()
        for (g in groups) for (ser in XmlX.kids(xml, g, "ser")) {
            var nameRef: String? = null
            var nameLit: String? = null
            val stx = XmlX.kid(xml, ser, "tx")
            if (stx != null) {
                val ref = XmlX.kid(xml, stx, "strRef")
                if (ref != null) {
                    nameRef = XmlX.kid(xml, ref, "f")?.let { Xml.text(xml, it) }
                    nameLit = XmlX.kid(xml, ref, "strCache")?.let { strPts(xml, it).firstOrNull() }
                } else nameLit = XmlX.kid(xml, stx, "v")?.let { Xml.text(xml, it) }
            }
            val cat = XmlX.kid(xml, ser, "cat") ?: XmlX.kid(xml, ser, "xVal")
            val v = XmlX.kid(xml, ser, "val") ?: XmlX.kid(xml, ser, "yVal")
            val catSrc = cat?.let { source(xml, it) }
            val valSrc = v?.let { source(xml, it) }
            series.add(SeriesDef(nameRef, nameLit, catSrc?.first, catSrc?.second, valSrc?.first, valSrc?.second?.map { it.toDoubleOrNull() }))
        }
        return ChartDef(part, sheet, titleRef, titleLit, type, series)
    }

    /** (reference formula, cached points) of a `cat` / `val` / `xVal` / `yVal` element. */
    private fun source(xml: String, el: Xml.Elem): Pair<String?, List<String>?> {
        for (k in Xml.children(xml, el)) when (Xml.local(k.name)) {
            "strRef", "numRef", "multiLvlStrRef" -> {
                val f = XmlX.kid(xml, k, "f")?.let { Xml.text(xml, it) }
                val cache = Xml.children(xml, k).firstOrNull { Xml.local(it.name).endsWith("Cache") }
                val pts = cache?.let { c -> if (Xml.local(c.name) == "multiLvlStrCache") XmlX.kid(xml, c, "lvl")?.let { strPts(xml, it) } else strPts(xml, c) }
                return Pair(f, pts)
            }
            "strLit", "numLit" -> return Pair(null, strPts(xml, k))
        }
        return Pair(null, null)
    }

    private fun strPts(xml: String, cache: Xml.Elem): List<String> {
        val pts = XmlX.kids(xml, cache, "pt")
        val declared = XmlX.kid(xml, cache, "ptCount")?.let { Xml.attr(xml, it, "val")?.toIntOrNull() } ?: 0
        var n = declared
        val vals = HashMap<Int, String>()
        for ((k, p) in pts.withIndex()) {
            val idx = Xml.attr(xml, p, "idx")?.toIntOrNull() ?: k
            vals[idx] = XmlX.kid(xml, p, "v")?.let { Xml.text(xml, it) } ?: ""
            if (idx + 1 > n) n = idx + 1
        }
        if (n > 100_000) n = 100_000
        return (0 until n).map { vals[it] ?: "" }
    }

    // ---- writing ----

    /** Sheet name as it appears in a reference: quoted unless it is a plain identifier that cannot be read as a cell. */
    fun quoteSheet(name: String): String {
        val plain = name.isNotEmpty() && (name[0] in 'A'..'Z' || name[0] in 'a'..'z' || name[0] == '_') &&
            name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' } && A1.parse(name) == null &&
            !name.equals("TRUE", true) && !name.equals("FALSE", true)
        return if (plain) name else "'" + name.replace("'", "''") + "'"
    }

    private fun absRange(sheet: String, r1: Int, c1: Int, r2: Int, c2: Int): String {
        val a = RefTok.cellText(r1, c1, true, true)
        val b = RefTok.cellText(r2, c2, true, true)
        return quoteSheet(sheet) + "!" + if (r1 == r2 && c1 == c2) a else "$a:$b"
    }

    private fun strCache(vals: List<String>): String {
        val sb = StringBuilder("<c:strCache><c:ptCount val=\"${vals.size}\"/>")
        for ((i, v) in vals.withIndex()) sb.append("<c:pt idx=\"$i\"><c:v>").append(Xml.escape(v)).append("</c:v></c:pt>")
        return sb.append("</c:strCache>").toString()
    }

    private fun numCache(vals: List<Double?>): String {
        val sb = StringBuilder("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"${vals.size}\"/>")
        for ((i, v) in vals.withIndex()) if (v != null) sb.append("<c:pt idx=\"$i\"><c:v>").append(Num.xml(v)).append("</c:v></c:pt>")
        return sb.append("</c:numCache>").toString()
    }

    /**
     * A clustered bar chart over the rectangle: first row = categories, first column = series names, one series per
     * further row. [names], [cats] and [values] are the current cell contents, stored as the chart's cached points.
     */
    fun barChartXml(
        title: String, sheet: String, r1: Int, c1: Int, r2: Int, c2: Int,
        names: List<String>, cats: List<String>, values: List<List<Double?>>,
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<c:chartSpace xmlns:c=\"$NS_C\" xmlns:a=\"$NS_A\" xmlns:r=\"$REL_NS\">")
        sb.append("<c:roundedCorners val=\"0\"/><c:chart>")
        if (title.isNotEmpty()) {
            sb.append("<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>").append(Xml.escape(title))
                .append("</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>")
        } else sb.append("<c:autoTitleDeleted val=\"1\"/>")
        sb.append("<c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>")
        val catRef = absRange(sheet, r1, c1 + 1, r1, c2)
        for (i in names.indices) {
            val row = r1 + 1 + i
            sb.append("<c:ser><c:idx val=\"$i\"/><c:order val=\"$i\"/>")
            sb.append("<c:tx><c:strRef><c:f>").append(Xml.escape(absRange(sheet, row, c1, row, c1))).append("</c:f>")
                .append(strCache(listOf(names[i]))).append("</c:strRef></c:tx>")
            sb.append("<c:invertIfNegative val=\"0\"/>")
            sb.append("<c:cat><c:strRef><c:f>").append(Xml.escape(catRef)).append("</c:f>").append(strCache(cats)).append("</c:strRef></c:cat>")
            sb.append("<c:val><c:numRef><c:f>").append(Xml.escape(absRange(sheet, row, c1 + 1, row, c2))).append("</c:f>")
                .append(numCache(values[i])).append("</c:numRef></c:val>")
            sb.append("</c:ser>")
        }
        sb.append("<c:gapWidth val=\"150\"/><c:axId val=\"$AX_CAT\"/><c:axId val=\"$AX_VAL\"/></c:barChart>")
        sb.append("<c:catAx><c:axId val=\"$AX_CAT\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>")
            .append("<c:axPos val=\"b\"/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"out\"/>")
            .append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"$AX_VAL\"/><c:crosses val=\"autoZero\"/>")
            .append("<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>")
        sb.append("<c:valAx><c:axId val=\"$AX_VAL\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>")
            .append("<c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"out\"/>")
            .append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"$AX_CAT\"/><c:crosses val=\"autoZero\"/>")
            .append("<c:crossBetween val=\"between\"/></c:valAx>")
        sb.append("</c:plotArea><c:legend><c:legendPos val=\"r\"/><c:overlay val=\"0\"/></c:legend>")
        sb.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>")
        return sb.toString()
    }

    private const val AX_CAT = 504210001
    private const val AX_VAL = 504210002

    /** A two-cell anchor holding a chart frame. Namespaces are declared on the anchor itself so it fits any drawing part. */
    fun anchorXml(col: Int, row: Int, cols: Int, rows: Int, shapeId: Int, name: String, relId: String): String =
        "<xdr:twoCellAnchor xmlns:xdr=\"$NS_XDR\" xmlns:a=\"$NS_A\">" +
            "<xdr:from><xdr:col>$col</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>" +
            "<xdr:to><xdr:col>${col + cols}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${row + rows}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>" +
            "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"$shapeId\" name=\"${Xml.escape(name)}\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>" +
            "<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>" +
            "<a:graphic><a:graphicData uri=\"$NS_C\"><c:chart xmlns:c=\"$NS_C\" xmlns:r=\"$REL_NS\" r:id=\"$relId\"/></a:graphicData></a:graphic>" +
            "</xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>"

    fun emptyDrawingXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<xdr:wsDr xmlns:xdr=\"$NS_XDR\" xmlns:a=\"$NS_A\"></xdr:wsDr>"

    /** Largest shape id and bottom-most anchored row (0-based, -1 when none) already in a drawing part. */
    fun drawingStats(xml: String): Pair<Int, Int> {
        val root = Xml.root(xml) ?: return Pair(1, -1)
        var maxId = 1
        var maxRow = -1
        for (e in XmlX.findAllLocal(xml, root, "cNvPr")) Xml.attr(xml, e, "id")?.toIntOrNull()?.let { if (it > maxId) maxId = it }
        for (to in XmlX.findAllLocal(xml, root, "to")) XmlX.kid(xml, to, "row")?.let { r -> Xml.text(xml, r).trim().toIntOrNull()?.let { if (it > maxRow) maxRow = it } }
        return Pair(maxId, maxRow)
    }
}
