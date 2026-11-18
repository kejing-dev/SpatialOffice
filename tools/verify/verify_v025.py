#!/usr/bin/env python3
"""Independent check of what v0.2.5 writes: moved / resized slide objects, slide tables, resized document pictures and tables.

Run `./gradlew :core:test` first (V025Test writes core/build/roundtrip/v025-*), then from the project root:
    tools/.venv/bin/python tools/verify/verify_v025.py
The files are opened with python-pptx / python-docx (not with :core). Exit code 0 = all checks passed.
"""
import os
import sys

from docx import Document
from pptx import Presentation

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "build", "roundtrip")
checks = 0
failures = []


def check(cond, what):
    global checks
    checks += 1
    if not cond:
        failures.append(what)


# 1. text box, picture, shape and table moved and resized: python-pptx reads the same boxes
want = {}
for line in open(os.path.join(OUT, "v025-objects.txt")).read().split("\n"):
    i, x, y, w, h = [int(v) for v in line.split()]
    want[i] = (x, y, w, h)
prs = Presentation(os.path.join(OUT, "v025-objects.pptx"))
shapes = {s.shape_id: s for s in prs.slides[0].shapes}
check(len(want) == 4, "four objects expected")
for i, box in want.items():
    s = shapes.get(i)
    check(s is not None, f"shape {i} missing")
    if s is not None:
        check((s.left, s.top, s.width, s.height) == box, f"shape {i}: {(s.left, s.top, s.width, s.height)} != {box}")
kinds = sorted(type(s).__name__ for i, s in shapes.items() if i in want)
check(kinds == ["Autoshape", "GraphicFrame", "Picture", "Shape"] or kinds == ["GraphicFrame", "Picture", "Shape", "Shape"], f"kinds {kinds}")
tables = [s for s in shapes.values() if s.has_table]
check(len(tables) == 1 and len(tables[0].table.rows) == 3 and len(tables[0].table.columns) == 3, "3 x 3 table")
texts = [s.text_frame.text for s in shapes.values() if s.has_text_frame]
check("moved text" in texts, f"text box text kept: {texts}")

# 2. a slide table typed into and resized: cells, frame, columns and rows
prs = Presentation(os.path.join(OUT, "v025-table.pptx"))
t = [s for s in prs.slides[0].shapes if s.has_table][0]
check((t.left, t.top, t.width, t.height) == (500000, 800000, 9000000, 2400000), "table frame")
check(t.table.cell(0, 0).text == "Q1", "cell 0,0")
check(t.table.cell(1, 2).text == "a & b\nsecond line", f"cell 1,2: {t.table.cell(1, 2).text!r}")
check([c.width for c in t.table.columns] == [3000000] * 3, "column widths follow the frame")
check([r.height for r in t.table.rows] == [1200000] * 2, "row heights follow the frame")

# 3. a document picture and table resized
doc = Document(os.path.join(OUT, "v025-resized.docx"))
check(len(doc.inline_shapes) == 1, "one picture")
pic = doc.inline_shapes[0]
check((pic.width, pic.height) == (2000000, 1000000), f"picture size {(pic.width, pic.height)}")
check(len(doc.tables) == 1 and doc.tables[0].cell(0, 0).text == "kept", "table text kept")
tblW = doc.tables[0]._tbl.tblPr.find("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}tblW")
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
check(tblW is not None and tblW.get(W + "type") == "pct" and tblW.get(W + "w") == "3000", "table width 60 % (3000 fiftieths of a percent)")

print(f"{checks} checks, {len(failures)} failed")
for f in failures:
    print("FAIL", f)
sys.exit(1 if failures else 0)
