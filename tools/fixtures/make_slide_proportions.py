"""spatial-office v0.2.6: sample decks of several page proportions, each with speaker notes, a text box and a shape."""
import sys
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE

out = sys.argv[1]
decks = {
    "v026-4x3.pptx": (10, 7.5, "4:3"),
    "v026-16x9.pptx": (13.333, 7.5, "16:9"),
    "v026-16x10.pptx": (10, 6.25, "16:10"),
    "v026-tall.pptx": (7.5, 10, "3:4"),
}
for name, (w, h, label) in decks.items():
    p = Presentation()
    p.slide_width = Inches(w); p.slide_height = Inches(h)
    for n in range(1, 4):
        s = p.slides.add_slide(p.slide_layouts[6])
        tb = s.shapes.add_textbox(Inches(w * 0.08), Inches(h * 0.08), Inches(w * 0.84), Inches(h * 0.18))
        r = tb.text_frame.paragraphs[0].add_run(); r.text = f"{label} 第 {n} 页 / Page {n}"; r.font.size = Pt(36); r.font.bold = True
        sh = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(w * 0.6), Inches(h * 0.4), Inches(min(w, h) * 0.3), Inches(min(w, h) * 0.3))
        sh.fill.solid(); sh.fill.fore_color.rgb = RGBColor(0x25, 0x63, 0xEB)
        # marks in the four corners: a cut-off page shows at once
        for cx, cy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            m = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(cx * (w - 0.4)), Inches(cy * (h - 0.4)), Inches(0.4), Inches(0.4))
            m.fill.solid(); m.fill.fore_color.rgb = RGBColor(0xF9, 0x73, 0x16)
        s.notes_slide.notes_text_frame.text = f"{label} 备注 {n}：先讲结论，再讲数字。Notes {n}: the point first, then the numbers."
    p.save(f"{out}/{name}")
    print(name, p.slide_width, p.slide_height)
