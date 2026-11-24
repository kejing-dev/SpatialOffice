"""spatial-office: sample decks for the storyline by chapters.
chapters-titles.pptx   14 slides, chapters start at title / section-header slides (1, 5, 10)
chapters-sections.pptx 12 slides, the file's own sections (3 + 5 + 4)
chapters-plain.pptx    12 slides, nothing to go by: groups of five
Usage: python make_chapters.py <out dir>
"""
import sys, uuid
from lxml import etree
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE

out = sys.argv[1]
COLORS = [RGBColor(0x25, 0x63, 0xEB), RGBColor(0xEA, 0x58, 0x0C), RGBColor(0x16, 0xA3, 0x4A), RGBColor(0x93, 0x33, 0xEA)]

def body(p, text, n, color):
    s = p.slides.add_slide(p.slide_layouts[6])
    w, h = p.slide_width, p.slide_height
    band = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, w, int(h * 0.16)); band.fill.solid(); band.fill.fore_color.rgb = color; band.line.fill.background()
    tb = s.shapes.add_textbox(int(w * 0.06), int(h * 0.03), int(w * 0.88), int(h * 0.12))
    r = tb.text_frame.paragraphs[0].add_run(); r.text = text; r.font.size = Pt(32); r.font.bold = True; r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    num = s.shapes.add_textbox(int(w * 0.06), int(h * 0.3), int(w * 0.5), int(h * 0.4))
    r = num.text_frame.paragraphs[0].add_run(); r.text = str(n); r.font.size = Pt(160); r.font.bold = True; r.font.color.rgb = color
    s.notes_slide.notes_text_frame.text = f"第 {n} 页的备注 / Notes of slide {n}"
    return s

def head(p, layout, title, sub):
    s = p.slides.add_slide(p.slide_layouts[layout])
    s.shapes.title.text = title
    if len(s.placeholders) > 1: s.placeholders[1].text = sub
    return s

def new():
    p = Presentation(); p.slide_width = Inches(13.333); p.slide_height = Inches(7.5); return p

# 1. title slides
p = new(); n = 0
plan = [("空间办公 季度汇报 / Quarterly review", 0, 3), ("增长 / Growth", 2, 4), ("计划 / Plan", 2, 4)]
for c, (title, layout, pages) in enumerate(plan):
    n += 1; head(p, layout, title, f"第 {c + 1} 章 / Chapter {c + 1}")
    for k in range(pages):
        n += 1; body(p, f"{title} · {k + 1}", n, COLORS[c])
p.save(f"{out}/chapters-titles.pptx"); print("chapters-titles.pptx", n)

# 2. the file's sections
p = new()
names = [("开场 / Opening", 3), ("数据 / Numbers", 5), ("下一步 / Next", 4)]
n = 0
for c, (name, pages) in enumerate(names):
    for k in range(pages):
        n += 1; body(p, f"{name} · {k + 1}", n, COLORS[c])
P14 = "http://schemas.microsoft.com/office/powerpoint/2010/main"
PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
root = p.part._element
ids = [e.get("id") for e in root.find(f"{{{PML}}}sldIdLst")]
ext_lst = root.find(f"{{{PML}}}extLst")
if ext_lst is None: ext_lst = etree.SubElement(root, f"{{{PML}}}extLst")
ext = etree.SubElement(ext_lst, f"{{{PML}}}ext"); ext.set("uri", "{521415D9-36F7-43E2-AB2F-B90AF26B5E84}")
lst = etree.SubElement(ext, f"{{{P14}}}sectionLst", nsmap={"p14": P14})
at = 0
for name, pages in names:
    sec = etree.SubElement(lst, f"{{{P14}}}section"); sec.set("name", name); sec.set("id", "{" + str(uuid.uuid4()).upper() + "}")
    sl = etree.SubElement(sec, f"{{{P14}}}sldIdLst")
    for i in ids[at:at + pages]: etree.SubElement(sl, f"{{{P14}}}sldId").set("id", i)
    at += pages
p.save(f"{out}/chapters-sections.pptx"); print("chapters-sections.pptx", n)

# 3. nothing to go by
p = new()
for n in range(1, 13): body(p, f"第 {n} 页 / Slide {n}", n, COLORS[(n - 1) // 5 % 4])
p.save(f"{out}/chapters-plain.pptx"); print("chapters-plain.pptx", 12)
