#!/usr/bin/env python3
"""Independent check of the files the :core docx / pptx tests save.

Run the tests first (they write core/build/roundtrip/), then from the project root:
    tools/.venv/bin/python tools/verify/verify_docx_pptx.py

Every file is opened with python-docx / python-pptx (not with :core), every XML part is parsed with lxml, and the
edits the tests made — as well as the content they must not have touched — are asserted here a second time.
Exit code 0 = all checks passed.
"""
import io
import os
import sys
import zipfile

from docx import Document
from docx.shared import Pt, RGBColor
from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor as PRGB
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.util import Pt as PPt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FIX = os.path.join(ROOT, "core", "src", "test", "resources", "fixtures")
OUT = os.path.join(ROOT, "core", "build", "roundtrip")
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

checks = 0
failures = []


def check(cond, what):
    global checks
    checks += 1
    if not cond:
        failures.append(what)
        print("  FAIL:", what)


def eq(actual, expected, what):
    check(actual == expected, "%s: expected %r, got %r" % (what, expected, actual))


def parts(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        assert len(names) == len(set(names)), "duplicate entries in " + path
        return {n: z.read(n) for n in names}


def well_formed(path):
    """Every XML part parses, and every internal relationship target exists."""
    ps = parts(path)
    for name, blob in ps.items():
        if name.endswith(".xml") or name.endswith(".rels"):
            try:
                etree.fromstring(blob)
            except etree.XMLSyntaxError as e:
                check(False, "%s: %s is not well-formed XML: %s" % (os.path.basename(path), name, e))
    for name, blob in ps.items():
        if not name.endswith(".rels"):
            continue
        folder = name.split("_rels/")[0]
        for rel in etree.fromstring(blob):
            if rel.get("TargetMode") == "External":
                continue
            target = rel.get("Target")
            full = target.lstrip("/") if target.startswith("/") else os.path.normpath(os.path.join(folder, target)).replace(os.sep, "/")
            check(full in ps, "%s: %s points at missing part %s" % (os.path.basename(path), name, full))
    check(True, "well-formed")
    return ps


def app_name(ps):
    return etree.fromstring(ps["docProps/app.xml"]).findtext("{http://schemas.openxmlformats.org/officeDocument/2006/extended-properties}Application")


def para_list(doc):
    return [(p.text, p.style.name) for p in doc.paragraphs]


def table_rows(doc):
    return [[c.text for c in row.cells] for row in doc.tables[0].rows]


def resave(obj):
    buf = io.BytesIO()
    obj.save(buf)
    return buf.getvalue()


# ---------------------------------------------------------------------------------------------------------------

def verify_docx_untouched():
    print("docx-untouched.docx")
    src, out = os.path.join(FIX, "docx-report.docx"), os.path.join(OUT, "docx-untouched.docx")
    a, b = parts(src), well_formed(out)
    eq(sorted(b), sorted(a), "part names")
    for n in a:
        check(a[n] == b.get(n), "part %s byte-identical" % n)
    eq(para_list(Document(out)), para_list(Document(src)), "paragraphs")


def verify_docx_edited():
    print("docx-edited.docx")
    src, out = os.path.join(FIX, "docx-report.docx"), os.path.join(OUT, "docx-edited.docx")
    a, b = parts(src), well_formed(out)
    changed = sorted(n for n in set(a) | set(b) if a.get(n) != b.get(n))
    eq(changed, ["word/document.xml", "word/numbering.xml"], "changed parts")
    eq(b["customXml/item2.xml"], a["customXml/item2.xml"], "custom XML part")
    eq(b["word/comments.xml"], a["word/comments.xml"], "comments part")

    before, doc = Document(src), Document(out)
    old, new = para_list(before), para_list(doc)
    texts = [t for t, _ in new]

    # the edits
    eq(new[0], ("Draft copy", "Normal"), "paragraph inserted at the start")
    eq(new[1], old[0], "title follows the inserted paragraph")
    check(("华南地区增长 15%，价格 < 100 & \"稳定\"。", "Normal") in new, "edited body paragraph")
    check(not any("华东地区" in t for t in texts), "old text of the edited paragraph is gone")
    check(("Picture section", "Heading 2") in new, "style change to heading 2")
    i = texts.index("客户满意度持续提升")
    eq(new[i + 1], ("Fourth highlight", "List Bullet"), "bullet inserted after the last bullet")
    check("Hire two engineers" not in texts, "deleted paragraph is gone")
    eq(texts[texts.index("Finalise the budget") + 1], "Ship the spring release", "neighbours of the deleted paragraph")

    closing = doc.paragraphs[texts.index("Closing item")]
    num_pr = closing._p.pPr.numPr
    check(num_pr is not None, "numbered paragraph has list properties")
    if num_pr is not None:
        num_id = num_pr.numId.val
        numbering = doc.part.numbering_part.element
        num = [n for n in numbering.findall(W + "num") if n.get(W + "numId") == str(num_id)]
        eq(len(num), 1, "list instance %s defined once" % num_id)
        abs_id = num[0].find(W + "abstractNumId").get(W + "val")
        absn = [n for n in numbering.findall(W + "abstractNum") if n.get(W + "abstractNumId") == abs_id]
        eq(len(absn), 1, "abstract list %s defined once" % abs_id)
        eq(absn[0].find(W + "lvl").find(W + "numFmt").get(W + "val"), "decimal", "new list is numbered")
        kinds = [etree.QName(c).localname for c in numbering if etree.QName(c).localname in ("abstractNum", "num")]
        check(kinds == sorted(kinds, key=lambda k: k != "abstractNum"), "numbering part keeps abstract definitions before instances")

    mixed = doc.paragraphs[texts.index("Revenue grew 18% year over year, with strong demand & <stable> margins.")]
    runs = mixed.runs
    eq([r.text for r in runs], ["Revenue grew ", "18%", " year over year, with ", "strong", " demand & <stable> margins."], "runs of the mixed paragraph")
    check(not runs[1].bold, "bold switched off on '18%'")
    eq(runs[1].underline, True, "underline switched on on '18%'")
    eq(runs[1].font.size, Pt(14), "'18%' keeps its size")
    eq(runs[1].font.color.rgb, RGBColor(0xC0, 0x39, 0x2B), "'18%' keeps its colour")
    eq(runs[1].font.name, "Fixture Sans", "'18%' keeps its font")
    eq(runs[3].italic, True, "'strong' is still italic")

    # everything the edits did not touch
    untouched_old = [p for p in old if p[0] not in (
        "华东地区的销售额同比增长百分之二十，主要来自新客户。", "The chart below is stored as an inline picture.", "Hire two engineers", "Closing paragraph after the columns.")]
    untouched_new = [p for p in new if p[0] not in (
        "Draft copy", "华南地区增长 15%，价格 < 100 & \"稳定\"。", "Picture section", "Fourth highlight", "Closing item")]
    eq(untouched_new, untouched_old, "all other paragraphs, in order, with their styles")
    eq(table_rows(doc), table_rows(before), "table text")
    eq(doc.tables[0].cell(0, 0)._tc.tcPr.gridSpan.val, 3, "merged header cell")
    eq(len(doc.inline_shapes), 1, "inline picture")
    eq(doc.inline_shapes[0].width, before.inline_shapes[0].width, "picture width")
    eq([c.text for c in doc.comments], ["Please double-check this number."], "comment")
    xml = b["word/document.xml"].decode("utf-8")
    for marker in ('<w:del w:id="101"', "<w:delText>flat</w:delText>", '<w:ins w:id="102"', "<wps:txbx>", "<v:textbox>",
                   '<w:cols w:num="2" w:space="720"/>', '<w:fldSimple w:instr=" PAGE ">', '<w:commentRangeStart w:id="0"/>',
                   '<w:bookmarkStart w:id="7" w:name="summary_anchor"/>'):
        check(marker in xml, "document still contains %s" % marker)
    Document(io.BytesIO(resave(doc)))
    check(True, "re-saved and reopened by python-docx")


def run_list(doc):
    return [[(r.text, bool(r.bold), bool(r.italic), bool(r.underline), r.font.size, r.font.name) for r in p.runs] for p in doc.paragraphs]


def verify_docx_rewritten():
    """Every editable paragraph was written again with its own runs and style: nothing may look different."""
    print("docx-rewritten.docx")
    src, out = os.path.join(FIX, "docx-report.docx"), os.path.join(OUT, "docx-rewritten.docx")
    a, b = parts(src), well_formed(out)
    eq(sorted(n for n in set(a) | set(b) if a.get(n) != b.get(n)), [], "changed parts (the rewrite reproduces the original XML)")
    before, doc = Document(src), Document(out)
    eq(para_list(doc), para_list(before), "paragraph texts and styles")
    eq(run_list(doc), run_list(before), "runs: text, bold, italic, underline, size, font")
    eq(table_rows(doc), table_rows(before), "table text")
    eq(len(doc.inline_shapes), 1, "inline picture")
    eq([c.text for c in doc.comments], ["Please double-check this number."], "comment")


def verify_docx_blank():
    print("docx-blank.docx")
    out = os.path.join(OUT, "docx-blank.docx")
    ps = well_formed(out)
    eq(app_name(ps), "Spatial Office", "application name")
    doc = Document(out)
    eq(doc.core_properties.author, "", "no author")
    eq(doc.core_properties.last_modified_by, "", "no last-modified-by")
    eq(para_list(doc), [
        ("项目计划 Project Plan", "Title"), ("Scope", "Heading 1"),
        ("We ship three features & fix <all> known bugs.", "Normal"), ("Risks", "Heading 2"),
        ("供应链延迟", "List Paragraph"), ("Staffing", "List Paragraph"), ("Details", "Heading 3"),
        ("Plan", "List Paragraph"), ("Build", "List Paragraph"), ("Done.", "Normal")], "paragraphs and styles")
    runs = doc.paragraphs[2].runs
    eq([r.text for r in runs], ["We ship ", "three", " features & fix <all> ", "known", " bugs."], "runs")
    eq([bool(r.bold) for r in runs], [False, True, False, False, False], "bold")
    eq([bool(r.italic) for r in runs], [False, False, False, True, False], "italic")
    eq([bool(r.underline) for r in runs], [False, False, False, True, False], "underline")
    numbering = doc.part.numbering_part.element
    fmts = {}
    for n in numbering.findall(W + "num"):
        absn = [x for x in numbering.findall(W + "abstractNum") if x.get(W + "abstractNumId") == n.find(W + "abstractNumId").get(W + "val")][0]
        fmts[n.get(W + "numId")] = absn.find(W + "lvl").find(W + "numFmt").get(W + "val")
    ids = [str(doc.paragraphs[i]._p.pPr.numPr.numId.val) for i in (4, 5, 7, 8)]
    eq([fmts[i] for i in ids], ["bullet", "bullet", "decimal", "decimal"], "list formats")
    check(ids[0] == ids[1] and ids[2] == ids[3] and ids[0] != ids[2], "bullets share a list, numbers share another")
    eq(doc.sections[0].page_width.twips, 11906, "A4 page width")
    Document(io.BytesIO(resave(doc)))
    check(True, "re-saved and reopened by python-docx")


# ---------------------------------------------------------------------------------------------------------------

def title_of(slide):
    t = slide.shapes.title
    return t.text_frame.text if t is not None else ""


def body_of(slide):
    for sh in slide.placeholders:
        if sh.placeholder_format.idx == 1:
            return sh
    return None


def verify_pptx_untouched():
    print("pptx-untouched.pptx")
    src, out = os.path.join(FIX, "pptx-deck.pptx"), os.path.join(OUT, "pptx-untouched.pptx")
    a, b = parts(src), well_formed(out)
    eq(sorted(b), sorted(a), "part names")
    for n in a:
        check(a[n] == b.get(n), "part %s byte-identical" % n)
    eq([title_of(s) for s in Presentation(out).slides], [title_of(s) for s in Presentation(src).slides], "titles")


def verify_pptx_edited():
    print("pptx-edited.pptx")
    src, out = os.path.join(FIX, "pptx-deck.pptx"), os.path.join(OUT, "pptx-edited.pptx")
    a, b = parts(src), well_formed(out)
    prs = Presentation(out)
    slides = list(prs.slides)
    eq([title_of(s) for s in slides], ["产品评审 2026 & <final>", "Thank you", "Goals", "Prototype", "Timeline", "Architecture", "Appendix"], "slide order and titles")
    eq(len(set(s.slide_id for s in slides)), 7, "unique slide ids")
    by = {title_of(s): s for s in slides}

    cover = by["产品评审 2026 & <final>"]
    eq(cover.placeholders[1].text_frame.text, "Spring planning & <draft> agenda", "untouched subtitle")
    check(cover.has_notes_slide, "notes slide created for the title slide")
    eq(cover.notes_slide.notes_text_frame.text, "New note on the title slide.", "new notes text")

    goals = body_of(by["Goals"]).text_frame.paragraphs
    eq([p.text for p in goals], ["Grow the active user base", "改进新手引导", "Shorten the first-run flow", "降低流失率", "Keep costs flat", "Added point"], "bullets")
    eq([p.level for p in goals], [0, 1, 2, 1, 0, 1], "bullet levels")

    proto = by["Prototype"]
    pics = [sh for sh in proto.shapes if sh.shape_type == MSO_SHAPE_TYPE.PICTURE]
    eq(len(pics), 1, "picture still on its slide")
    eq(pics[0].image.blob, a["ppt/media/image1.png"], "picture bytes")
    boxes = {sh.text_frame.text: sh for sh in proto.shapes if sh.shape_type == MSO_SHAPE_TYPE.TEXT_BOX}
    eq(sorted(boxes), ["Added text box", "Photo of the current build"], "text boxes")
    if "Added text box" in boxes:
        sh = boxes["Added text box"]
        eq((sh.left, sh.top, sh.width, sh.height), (5029200, 3200400, 3200400, 600000), "new text box geometry")

    timeline = by["Timeline"]
    eq(timeline.notes_slide.notes_text_frame.text, "Updated note.\nSecond line.", "edited notes")
    eq([p.text for p in body_of(timeline).text_frame.paragraphs], ["Design freeze in March", "Beta in May"], "untouched body")

    arch = by["Architecture"]
    eq(sorted(str(sh.shape_type) for sh in arch.shapes if not sh.is_placeholder), sorted([str(MSO_SHAPE_TYPE.GROUP), str(MSO_SHAPE_TYPE.CHART)]), "group and chart kept")
    chart = [sh for sh in arch.shapes if sh.has_chart][0].chart
    eq(list(chart.plots[0].categories), ["Q1", "Q2", "Q3"], "chart categories")
    eq(list(chart.plots[0].series[0].values), [120.0, 150.0, 190.0], "chart values")
    grp = [sh for sh in arch.shapes if sh.shape_type == MSO_SHAPE_TYPE.GROUP][0]
    eq([s.text_frame.text for s in grp.shapes], ["Client", "Service"], "group members")

    thanks = by["Thank you"]
    run = body_of(thanks).text_frame.paragraphs[0].runs[0]
    eq((run.text, run.font.size, run.font.bold, run.font.color.rgb), ("Questions are welcome", PPt(32), True, PRGB(0x1F, 0x4E, 0x79)), "formatted run")
    eq(thanks.background.fill.fore_color.rgb, PRGB(0xF4, 0xF6, 0xF7), "slide background")

    appendix = by["Appendix"]
    eq(appendix.slide_layout.name, "Title and Content", "layout of the added slide")
    eq([(p.text, p.level) for p in body_of(appendix).text_frame.paragraphs], [("Extra data", 0), ("Sources", 1)], "body of the added slide")

    check(not any(sh.has_table for s in slides for sh in s.shapes), "deleted slide's table is gone")
    check("ppt/slides/slide5.xml" not in b, "deleted slide part removed")
    for n in ("ppt/slideMasters/slideMaster1.xml", "ppt/theme/theme1.xml", "ppt/charts/chart1.xml", "ppt/embeddings/workbook1.xlsx",
              "ppt/slides/slide4.xml", "ppt/slides/slide6.xml", "ppt/slides/slide7.xml", "ppt/notesMasters/notesMaster1.xml", "docProps/core.xml"):
        check(a[n] == b.get(n), "untouched part %s byte-identical" % n)
    eq(len(prs.slide_layouts), 11, "layouts")
    Presentation(io.BytesIO(resave(prs)))
    check(True, "re-saved and reopened by python-pptx")


def verify_pptx_rewritten():
    """Every editable shape got a full-width exclamation mark appended to each paragraph; all else is as before."""
    print("pptx-rewritten.pptx")
    src, out = os.path.join(FIX, "pptx-deck.pptx"), os.path.join(OUT, "pptx-rewritten.pptx")
    a, b = parts(src), well_formed(out)
    eq(sorted(n for n in set(a) | set(b) if a.get(n) != b.get(n)), ["ppt/slides/slide%d.xml" % i for i in range(1, 8)], "changed parts")
    before, prs = Presentation(src), Presentation(out)
    eq(len(prs.slides), len(before.slides), "slide count")
    for i, (s0, s1) in enumerate(zip(before.slides, prs.slides)):
        eq([sh.shape_type for sh in s1.shapes], [sh.shape_type for sh in s0.shapes], "slide %d shape types" % (i + 1))
        eq([(sh.left, sh.top, sh.width, sh.height) for sh in s1.shapes], [(sh.left, sh.top, sh.width, sh.height) for sh in s0.shapes], "slide %d geometry" % (i + 1))
        for sh0, sh1 in zip(s0.shapes, s1.shapes):
            if not sh0.has_text_frame:
                continue
            p0, p1 = sh0.text_frame.paragraphs, sh1.text_frame.paragraphs
            eq([p.text for p in p1], [p.text + "\uff01" for p in p0], "slide %d '%s' text" % (i + 1, sh0.name))
            eq([p.level for p in p1], [p.level for p in p0], "slide %d '%s' levels" % (i + 1, sh0.name))
            eq([p.alignment for p in p1], [p.alignment for p in p0], "slide %d '%s' alignment" % (i + 1, sh0.name))
            f0 = [(r.font.size, r.font.bold) for p in p0 for r in p.runs[:1]]
            f1 = [(r.font.size, r.font.bold) for p in p1 for r in p.runs[:1]]
            eq(f1, f0, "slide %d '%s' run formatting" % (i + 1, sh0.name))
    eq(prs.slides[3].notes_slide.notes_text_frame.text, "Mention the dependency on the hardware team.", "notes untouched")


def verify_pptx_blank():
    print("pptx-blank.pptx")
    out = os.path.join(OUT, "pptx-blank.pptx")
    ps = well_formed(out)
    eq(app_name(ps), "Spatial Office", "application name")
    prs = Presentation(out)
    eq(prs.core_properties.author, "", "no author")
    eq(prs.core_properties.last_modified_by, "", "no last-modified-by")
    eq((prs.slide_width, prs.slide_height), (12192000, 6858000), "16:9 slide size")
    eq([l.name for l in prs.slide_layouts], ["Title Slide", "Title and Content"], "layouts")
    slides = list(prs.slides)
    eq([title_of(s) for s in slides], ["空间办公 Spatial Review", "Agenda", "Thanks"], "titles")
    eq(slides[0].placeholders[1].text_frame.text, "Q2 planning & <notes>", "subtitle")
    eq([s.slide_layout.name for s in slides], ["Title Slide", "Title and Content", "Title Slide"], "slide layouts")
    paras = body_of(slides[1]).text_frame.paragraphs
    eq([(p.text, p.level) for p in paras], [("Status", 0), ("Risks", 1), ("下一步", 1), ("Summary", 0)], "body paragraphs")
    check(slides[1].has_notes_slide, "notes slide")
    eq(slides[1].notes_slide.notes_text_frame.text, "Keep this short.", "notes text")
    check(not slides[0].has_notes_slide, "no notes on the first slide")
    boxes = [sh for sh in slides[1].shapes if sh.shape_type == MSO_SHAPE_TYPE.TEXT_BOX]
    eq([(sh.text_frame.text, sh.left, sh.top) for sh in boxes], [("Confidential", 8000000, 5600000)], "text box")
    extra = prs.slides.add_slide(prs.slide_layouts[1])       # the deck can be extended by another library
    extra.shapes.title.text = "Added elsewhere"
    again = Presentation(io.BytesIO(resave(prs)))
    eq(len(again.slides), 4, "re-saved and reopened by python-pptx with one more slide")


def main():
    missing = [n for n in ("docx-untouched.docx", "docx-edited.docx", "docx-rewritten.docx", "docx-blank.docx",
                           "pptx-untouched.pptx", "pptx-edited.pptx", "pptx-rewritten.pptx", "pptx-blank.pptx")
               if not os.path.exists(os.path.join(OUT, n))]
    if missing:
        print("missing files in %s: %s — run the :core docx / pptx tests first" % (OUT, ", ".join(missing)))
        return 2
    for fn in (verify_docx_untouched, verify_docx_edited, verify_docx_rewritten, verify_docx_blank,
               verify_pptx_untouched, verify_pptx_edited, verify_pptx_rewritten, verify_pptx_blank):
        try:
            fn()
        except Exception as e:  # a crash while opening or reading is a failed check, not a crashed verifier
            check(False, "%s raised %s: %s" % (fn.__name__, type(e).__name__, e))
    print("%d checks, %d failed" % (checks, len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
