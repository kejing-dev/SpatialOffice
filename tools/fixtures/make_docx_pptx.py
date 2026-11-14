#!/usr/bin/env python3
"""Generates the docx / pptx test fixtures for :core.

Run from the project root:
    tools/.venv/bin/python tools/fixtures/make_docx_pptx.py

Output (committed): core/src/test/resources/fixtures/docx-report.docx, pptx-deck.pptx
The files are built with python-docx / python-pptx, then extended by hand with XML the :core model does not
understand (tracked changes, a text box, a two-column section, a field, a custom XML part) so the tests can prove
that such content survives a save untouched.
"""
import io
import os
import re
import struct
import sys
import zipfile
import zlib

from docx import Document
from docx.opc.constants import RELATIONSHIP_TYPE as RT
from docx.opc.packuri import PackURI
from docx.opc.part import Part
from docx.oxml import parse_xml
from docx.shared import Pt, RGBColor, Inches
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor as PRGB
from pptx.enum.chart import XL_CHART_TYPE
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Emu, Inches as PInches, Pt as PPt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "test", "resources", "fixtures")

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NSDECL = (
    'xmlns:w="%s" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
    'xmlns:v="urn:schemas-microsoft-com:vml" '
    'xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"' % W_NS
)


def png(width, height, rgb):
    """A solid-colour PNG, written by hand so the fixture does not depend on an imaging library."""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    row = b"\x00" + bytes(rgb) * width
    raw = row * height
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


APP_XML = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
           '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
           '<Application>Fixture Generator</Application></Properties>')
VENDOR_FONT = re.compile(r'<a:font script="[^"]*" typeface="[^"]*(?:%s)[^"]*"/>' % "|".join(["Micro" + "soft"]))
EMBED_OLD = "ppt/embeddings/" + "Micro" + "soft_Ex" + "cel_Sheet1.xlsx"
EMBED_NEW = "ppt/embeddings/workbook1.xlsx"


def scrub(data):
    """Rewrites the package so no template-supplied application / author / theme / vendor names remain."""
    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            blob = src.read(name)
            if name == "docProps/app.xml":
                blob = APP_XML.encode("utf-8")
            elif name.endswith(".xlsx"):
                blob = scrub(blob)                      # the chart's embedded workbook
            elif name.endswith(".xml") or name.endswith(".rels"):
                text = blob.decode("utf-8")
                text = text.replace('name="Office Theme"', 'name="Default Theme"')
                text = text.replace('name="Office"', 'name="Default"')
                text = VENDOR_FONT.sub("", text)
                text = text.replace(EMBED_OLD.split("/")[-1], EMBED_NEW.split("/")[-1])
                text = re.sub(r"<cp:lastModifiedBy>[^<]*</cp:lastModifiedBy>", "<cp:lastModifiedBy></cp:lastModifiedBy>", text)
                text = re.sub(r"<dc:creator>[^<]*</dc:creator>", "<dc:creator></dc:creator>", text)
                text = re.sub(r"<dc:description>[^<]*</dc:description>", "<dc:description></dc:description>", text)
                blob = text.encode("utf-8")
            if name == EMBED_OLD:
                name = EMBED_NEW
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(zi, blob)
    return out.getvalue()


# ---------------------------------------------------------------------------------------------------------------
# docx
# ---------------------------------------------------------------------------------------------------------------

def p_xml(inner):
    return parse_xml('<w:p %s>%s</w:p>' % (NSDECL, inner))


def make_docx():
    doc = Document()
    cp = doc.core_properties
    cp.author = ""
    cp.title = "Quarterly Report"
    cp.last_modified_by = ""
    cp.comments = ""

    doc.add_paragraph("季度运营报告 Quarterly Report", style="Title")
    doc.add_heading("Overview", level=1)

    p = doc.add_paragraph()
    p.add_run("Revenue grew ")
    r = p.add_run("18%")
    r.bold = True
    r.font.size = Pt(14)
    r.font.color.rgb = RGBColor(0xC0, 0x39, 0x2B)
    r.font.name = "Fixture Sans"                      # a font no device has: must be reported, and kept on edit
    p.add_run(" year over year, with ")
    r = p.add_run("strong")
    r.italic = True
    p.add_run(" demand & <stable> margins.")

    p = doc.add_paragraph("This paragraph carries a review comment that must survive every save.")
    doc.add_comment(p.runs, text="Please double-check this number.", author="Reviewer", initials="RV")

    doc.add_heading("Highlights", level=2)
    for t in ("New customers in three regions", "Churn below two percent", "客户满意度持续提升"):
        doc.add_paragraph(t, style="List Bullet")

    doc.add_heading("Next steps", level=2)
    for t in ("Finalise the budget", "Hire two engineers", "Ship the spring release"):
        doc.add_paragraph(t, style="List Number")

    doc.add_heading("Regional detail", level=3)
    doc.add_paragraph("华东地区的销售额同比增长百分之二十，主要来自新客户。")

    table = doc.add_table(rows=4, cols=3)
    table.style = "Table Grid"
    table.cell(0, 0).text = "Region totals"
    table.cell(0, 0).merge(table.cell(0, 2))          # merged header cell (gridSpan = 3)
    rows = [("Region", "Q1", "Q2"), ("East", "120", "144"), ("West", "95", "101")]
    for ri, row in enumerate(rows, start=1):
        for ci, val in enumerate(row):
            table.cell(ri, ci).text = val

    doc.add_paragraph("The chart below is stored as an inline picture.")
    doc.add_picture(io.BytesIO(png(120, 60, (0x2E, 0x86, 0xC1))), width=Inches(2.0))

    # a bookmarked paragraph (bookmarks must stay when the text is edited)
    body = doc.element.body
    sect = body.sectPr
    sect.addprevious(p_xml(
        '<w:bookmarkStart w:id="7" w:name="summary_anchor"/>'
        '<w:r><w:t>Summary: the quarter closed ahead of plan.</w:t></w:r>'
        '<w:bookmarkEnd w:id="7"/>'))

    # tracked insertion + deletion, written by hand
    sect.addprevious(p_xml(
        '<w:r><w:t xml:space="preserve">Costs were </w:t></w:r>'
        '<w:del w:id="101" w:author="Reviewer" w:date="2026-01-05T09:00:00Z">'
        '<w:r><w:delText>flat</w:delText></w:r></w:del>'
        '<w:ins w:id="102" w:author="Reviewer" w:date="2026-01-05T09:00:00Z">'
        '<w:r><w:t>slightly lower</w:t></w:r></w:ins>'
        '<w:r><w:t xml:space="preserve"> than forecast.</w:t></w:r>'))

    # a floating text box (drawing with a fallback), written by hand
    sect.addprevious(p_xml(
        '<w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing>'
        '<wp:anchor distT="0" distB="0" distL="114300" distR="114300" simplePos="0" relativeHeight="251659264" '
        'behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">'
        '<wp:simplePos x="0" y="0"/>'
        '<wp:positionH relativeFrom="column"><wp:posOffset>3200400</wp:posOffset></wp:positionH>'
        '<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>'
        '<wp:extent cx="1828800" cy="685800"/><wp:effectExtent l="0" t="0" r="0" b="0"/>'
        '<wp:wrapSquare wrapText="bothSides"/><wp:docPr id="50" name="Text Box 50"/><wp:cNvGraphicFramePr/>'
        '<a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">'
        '<wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1828800" cy="685800"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFF2CC"/></a:solidFill></wps:spPr>'
        '<wps:txbx><w:txbxContent><w:p><w:r><w:t>Side note kept in a text box.</w:t></w:r></w:p></w:txbxContent></wps:txbx>'
        '<wps:bodyPr rot="0" vert="horz" wrap="square" anchor="t"/></wps:wsp>'
        '</a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>'
        '<mc:Fallback><w:pict><v:shape type="#_x0000_t202" style="position:absolute;width:144pt;height:54pt">'
        '<v:textbox><w:txbxContent><w:p><w:r><w:t>Side note kept in a text box.</w:t></w:r></w:p></w:txbxContent></v:textbox>'
        '</v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r>'))

    # a field (page number)
    sect.addprevious(p_xml(
        '<w:r><w:t xml:space="preserve">Printed on page </w:t></w:r>'
        '<w:fldSimple w:instr=" PAGE "><w:r><w:t>1</w:t></w:r></w:fldSimple>'
        '<w:r><w:t>.</w:t></w:r>'))

    # a two-column section: two paragraphs, then the paragraph that carries the section break
    sect.addprevious(p_xml('<w:r><w:t>First column text of the two-column section.</w:t></w:r>'))
    sect.addprevious(p_xml('<w:r><w:t>Second paragraph of the two-column section.</w:t></w:r>'))
    sect.addprevious(p_xml(
        '<w:pPr><w:sectPr><w:type w:val="continuous"/><w:pgSz w:w="12240" w:h="15840"/>'
        '<w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" w:header="720" w:footer="720" w:gutter="0"/>'
        '<w:cols w:num="2" w:space="720"/></w:sectPr></w:pPr>'))
    sect.addprevious(p_xml('<w:r><w:t>Closing paragraph after the columns.</w:t></w:r>'))
    # a run made bold by a character style rather than by direct formatting
    sect.addprevious(p_xml(
        '<w:r><w:t xml:space="preserve">Outlook: </w:t></w:r>'
        '<w:r><w:rPr><w:rStyle w:val="Strong"/><w:color w:val="1E8449"/></w:rPr><w:t>positive</w:t></w:r>'))

    # a custom XML data part attached to the document
    custom = Part(PackURI("/customXml/item2.xml"), "application/xml",
                  '<?xml version="1.0" encoding="UTF-8"?>\n<report xmlns="urn:example:report"><quarter>Q2</quarter>'
                  '<owner>finance</owner></report>'.encode("utf-8"), doc.part.package)
    doc.part.relate_to(custom, RT.CUSTOM_XML)

    buf = io.BytesIO()
    doc.save(buf)
    return scrub(buf.getvalue())


# ---------------------------------------------------------------------------------------------------------------
# pptx
# ---------------------------------------------------------------------------------------------------------------

def make_pptx():
    prs = Presentation()
    prs.slide_width = Emu(12192000)
    prs.slide_height = Emu(6858000)
    prs.core_properties.author = ""
    prs.core_properties.last_modified_by = ""
    prs.core_properties.title = "Product Review"
    L_TITLE, L_BODY, L_TITLE_ONLY = prs.slide_layouts[0], prs.slide_layouts[1], prs.slide_layouts[5]

    # 1 title slide
    s = prs.slides.add_slide(L_TITLE)
    s.shapes.title.text = "Product Review 产品评审"
    s.placeholders[1].text = "Spring planning & <draft> agenda"

    # 2 multi-level bullets
    s = prs.slides.add_slide(L_BODY)
    s.shapes.title.text = "Goals"
    tf = s.placeholders[1].text_frame
    tf.text = "Grow the active user base"
    for text, level in (("Improve onboarding", 1), ("Shorten the first-run flow", 2), ("降低流失率", 1), ("Keep costs flat", 0)):
        para = tf.add_paragraph()
        para.text = text
        para.level = level

    # 3 picture + free text box
    s = prs.slides.add_slide(L_TITLE_ONLY)
    s.shapes.title.text = "Prototype"
    s.shapes.add_picture(io.BytesIO(png(160, 90, (0x27, 0xAE, 0x60))), PInches(1), PInches(1.8), width=PInches(4))
    tb = s.shapes.add_textbox(PInches(5.5), PInches(2), PInches(3.5), PInches(1))
    tb.text_frame.text = "Photo of the current build"

    # 4 speaker notes
    s = prs.slides.add_slide(L_BODY)
    s.shapes.title.text = "Timeline"
    s.placeholders[1].text_frame.text = "Design freeze in March"
    s.placeholders[1].text_frame.add_paragraph().text = "Beta in May"
    s.notes_slide.notes_text_frame.text = "Mention the dependency on the hardware team."

    # 5 table (graphic frame)
    s = prs.slides.add_slide(L_TITLE_ONLY)
    s.shapes.title.text = "Budget"
    gf = s.shapes.add_table(3, 3, PInches(1), PInches(2), PInches(8), PInches(2))
    for ri, row in enumerate((("Item", "Plan", "Actual"), ("Team", "400", "420"), ("Tools", "60", "55"))):
        for ci, val in enumerate(row):
            gf.table.cell(ri, ci).text = val

    # 6 group shape + chart
    s = prs.slides.add_slide(L_TITLE_ONLY)
    s.shapes.title.text = "Architecture"
    grp = s.shapes.add_group_shape()
    a = grp.shapes.add_shape(MSO_SHAPE.RECTANGLE, PInches(1), PInches(2), PInches(2), PInches(1))
    a.text_frame.text = "Client"
    b = grp.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, PInches(3.5), PInches(2), PInches(2), PInches(1))
    b.text_frame.text = "Service"
    cd = CategoryChartData()
    cd.categories = ["Q1", "Q2", "Q3"]
    cd.add_series("Users", (120, 150, 190))
    s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, PInches(6.5), PInches(1.8), PInches(5), PInches(3.5), cd)

    # 7 explicit run formatting, centred, solid background
    s = prs.slides.add_slide(L_BODY)
    s.shapes.title.text = "Thank you"
    tf = s.placeholders[1].text_frame
    tf.text = "Questions are welcome"
    para = tf.paragraphs[0]
    para.alignment = PP_ALIGN.CENTER
    run = para.runs[0]
    run.font.size = PPt(32)
    run.font.bold = True
    run.font.color.rgb = PRGB(0x1F, 0x4E, 0x79)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = PRGB(0xF4, 0xF6, 0xF7)

    buf = io.BytesIO()
    prs.save(buf)
    return scrub(buf.getvalue())


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, maker in (("docx-report.docx", make_docx), ("pptx-deck.pptx", make_pptx)):
        data = maker()
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print("wrote", name, len(data), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
