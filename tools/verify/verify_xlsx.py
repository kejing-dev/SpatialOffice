#!/usr/bin/env python3
"""Independent check of the spreadsheet files the :core xlsx tests save to core/build/roundtrip/.

Run from the project root, after the tests:  tools/.venv/bin/python tools/verify/verify_xlsx.py

Every file is checked three ways: the zip and every XML part are well-formed and the package is consistent
(content types, relationship targets); it opens in openpyxl; and the edits the tests made, the formulas, the
number of charts and the untouched extras (merged cells, data validation, conditional format) read back.
"""
import datetime
import os
import sys
import warnings
import zipfile
import xml.dom.minidom

from openpyxl import load_workbook
from openpyxl.utils.escape import unescape

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.normpath(os.path.join(HERE, "..", "..", "core", "build", "roundtrip"))
FAILURES = []
CHECKS = 0


def check(cond, what):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILURES.append(what)
        print("   FAIL", what)


def eq(actual, expected, what):
    check(actual == expected, f"{what}: expected {expected!r}, got {actual!r}")


def package_ok(path):
    """Zip integrity, XML well-formedness, content types for every part, relationship targets exist."""
    name = os.path.basename(path)
    with zipfile.ZipFile(path) as z:
        check(z.testzip() is None, f"{name}: zip entries are intact")
        names = z.namelist()
        eq(len(names), len(set(names)), f"{name}: no duplicate entries")
        docs = {}
        for n in names:
            if n.endswith(".xml") or n.endswith(".rels"):
                try:
                    docs[n] = xml.dom.minidom.parseString(z.read(n))
                except Exception as e:  # noqa: BLE001
                    check(False, f"{name}: {n} is not well-formed XML ({e})")
        ct = docs.get("[Content_Types].xml")
        check(ct is not None, f"{name}: has [Content_Types].xml")
        if ct is None:
            return
        defaults = {d.getAttribute("Extension").lower() for d in ct.getElementsByTagName("Default")}
        overrides = {o.getAttribute("PartName") for o in ct.getElementsByTagName("Override")}
        for n in names:
            if n == "[Content_Types].xml":
                continue
            ext = n.rsplit(".", 1)[-1].lower()
            check("/" + n in overrides or ext in defaults, f"{name}: {n} has a content type")
        for o in overrides:
            check(o.lstrip("/") in names, f"{name}: content-type override {o} points at an existing part")
        for n, doc in docs.items():
            if not n.endswith(".rels"):
                continue
            folder = os.path.dirname(os.path.dirname(n))
            for rel in doc.getElementsByTagName("Relationship"):
                if rel.getAttribute("TargetMode") == "External":
                    continue
                target = rel.getAttribute("Target")
                full = target.lstrip("/") if target.startswith("/") else os.path.normpath(os.path.join(folder, target)).replace(os.sep, "/")
                check(full in names, f"{name}: relationship {rel.getAttribute('Id')} in {n} → {full} exists")


def books(name):
    path = os.path.join(DIR, name)
    if not os.path.exists(path):
        check(False, f"{name}: file was not written by the tests")
        return None, None
    package_ok(path)
    return load_workbook(path), load_workbook(path, data_only=True)


def chart_title(chart):
    t = chart.title
    if t is None or t.tx is None or t.tx.rich is None:
        return None
    return "".join(r.t for p in t.tx.rich.p for r in (p.r or []))


def sales_extras(name, wb, merged="A8:M8", block="B2:M5", charts=(1, 1)):
    ws = wb["Sales"]
    eq([str(m) for m in ws.merged_cells.ranges], [merged], f"{name}: merged title")
    eq([str(d.sqref) for d in ws.data_validations.dataValidation], [block], f"{name}: data validation range")
    eq(ws.data_validations.dataValidation[0].type, "whole", f"{name}: data validation type")
    eq([str(c.sqref) for c in ws.conditional_formatting], [block], f"{name}: conditional format range")
    eq((len(ws._charts), len(wb["Notes"]._charts)), charts, f"{name}: charts per sheet")
    eq(wb.sheetnames, ["Sales", "Notes"], f"{name}: sheets")


def verify_sales():
    name = "xlsx-sales-untouched.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb)
        eq(wb["Sales"]["B2"].value, 120, f"{name}: B2")
        eq(wb["Sales"]["B6"].value, "=SUM(B2:B5)", f"{name}: B6 formula")
        eq(wb["Notes"]["D2"].value, datetime.datetime(2026, 3, 1), f"{name}: date")
        eq(chart_title(wb["Sales"]._charts[0]), "Units by month", f"{name}: chart title")

    name = "xlsx-sales-edit-number.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb)
        eq(wb["Sales"]["B2"].value, 500, f"{name}: edited B2")
        eq(wb["Sales"]["B6"].value, "=SUM(B2:B5)", f"{name}: B6 formula kept")
        eq(vals["Sales"]["B6"].value, 645, f"{name}: B6 cached value")
        eq(vals["Sales"]["N2"].value, 31312.5, f"{name}: N2 cached value")
        eq(vals["Sales"]["N6"].value, 61525, f"{name}: N6 cached value")
        eq(wb["Notes"]["F2"].value, "=Sales!N6", f"{name}: cross-sheet formula kept")
        eq(vals["Notes"]["F2"].value, 61525, f"{name}: cross-sheet cached value")
        eq(vals["Notes"]["F6"].value, 810, f"{name}: Notes!F6 cached value")
        eq(wb["Sales"]["C2"].value, 135, f"{name}: neighbour untouched")
        eq(wb["Sales"]["A8"].value, "FY2026 unit sales — 销售概览", f"{name}: title text")
        eq(wb["Sales"]["A1"].font.b, True, f"{name}: header still bold")
        eq(wb["Sales"]["N2"].number_format, '"¥"#,##0.00', f"{name}: currency format kept")

    name = "xlsx-sales-formats.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb)
        ws = wb["Sales"]
        eq(ws["B2"].number_format, "0%", f"{name}: percent")
        eq(ws["B3"].number_format, '"¥"#,##0.00', f"{name}: currency")
        eq(ws["B4"].number_format, "yyyy-mm-dd", f"{name}: date")
        eq(ws["B5"].number_format, "#,##0.00", f"{name}: other")
        eq(ws["N2"].number_format, "General", f"{name}: general")
        eq(ws["B2"].value, 120, f"{name}: value unchanged by format")
        eq(ws["C2"].number_format, "General", f"{name}: neighbour format untouched")

    name = "xlsx-sales-bold.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb)
        ws = wb["Sales"]
        eq(ws["A2"].font.b, True, f"{name}: A2 bold")
        eq(ws["A2"].value, "Headset", f"{name}: A2 text")
        eq(bool(ws["A3"].font.b), False, f"{name}: A3 not bold")
        eq(ws["A2"].font.name, ws["A3"].font.name, f"{name}: bold font keeps the face")

    name = "xlsx-sales-insert-row.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb, merged="A9:M9", block="B2:M6")
        ws = wb["Sales"]
        eq([ws.cell(row=r, column=1).value for r in range(1, 8)], ["Product", "Headset", "Controller", "Dock", "Strap", "Case", "Total"], f"{name}: column A")
        eq(ws["B4"].value, 10, f"{name}: new row value")
        eq(ws["B7"].value, "=SUM(B2:B6)", f"{name}: total formula grew")
        eq(vals["Sales"]["B7"].value, 275, f"{name}: total cached value")
        eq(ws["N5"].value, "=SUM(B5:M5)*12.5", f"{name}: moved row formula")
        eq(ws["O2"].value, "=N2/$N$7", f"{name}: absolute reference shifted")
        eq(ws["A9"].value, "FY2026 unit sales — 销售概览", f"{name}: title moved down")
        eq(wb["Notes"]["F2"].value, "=Sales!N7", f"{name}: cross-sheet reference shifted")
        eq(wb["Notes"]["F3"].value, "=SUM(Sales!B2:M2)*2", f"{name}: reference above the row kept")
        eq(wb.defined_names["JanUnits"].attr_text, "Sales!$B$2:$B$6", f"{name}: defined name shifted")
        ser = ws._charts[0].series
        eq([s.val.numRef.f for s in ser], ["'Sales'!$B$2:$M$2", "'Sales'!$B$3:$M$3", "'Sales'!$B$5:$M$5", "'Sales'!$B$6:$M$6"], f"{name}: chart value references")
        eq([s.tx.strRef.f for s in ser], ["'Sales'!A2", "'Sales'!A3", "'Sales'!A5", "'Sales'!A6"], f"{name}: chart name references")

    name = "xlsx-sales-add-chart.xlsx"
    wb, vals = books(name)
    if wb:
        sales_extras(name, wb, charts=(2, 1))
        new = [c for c in wb["Sales"]._charts if chart_title(c) == "First quarter"]
        eq(len(new), 1, f"{name}: the added chart is found by title")
        if new:
            c = new[0]
            eq(type(c).__name__, "BarChart", f"{name}: chart type")
            eq(c.grouping, "clustered", f"{name}: grouping")
            eq([s.val.numRef.f for s in c.series], [f"Sales!$B${r}:$D${r}" for r in range(2, 6)], f"{name}: value references")
            eq([s.tx.strRef.f for s in c.series], [f"Sales!$A${r}" for r in range(2, 6)], f"{name}: name references")
            eq(c.series[0].cat.strRef.f, "Sales!$B$1:$D$1", f"{name}: category reference")
            eq([p.v for p in c.series[0].val.numRef.numCache.pt], [120, 135, 150], f"{name}: cached points")
        eq(chart_title([c for c in wb["Sales"]._charts if c not in new][0]), "Units by month", f"{name}: old chart still there")


def verify_cached():
    warnings.simplefilter("ignore")  # openpyxl warns that it drops the unknown extension list when IT loads the file
    name = "xlsx-cached-untouched.xlsx"
    wb, vals = books(name)
    if wb:
        eq(wb.sheetnames, ["Data", "Other Sheet"], f"{name}: sheets")
        eq(vals["Data"]["C5"].value, 10, f"{name}: cached value of unknown function")
        with zipfile.ZipFile(os.path.join(DIR, name)) as z:
            check(b"unknown extension payload" in z.read("xl/worksheets/sheet1.xml"), f"{name}: extension list kept")
            check("customXml/item1.xml" in z.namelist(), f"{name}: custom XML part kept")

    name = "xlsx-cached-edit-number.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        eq(ws["C2"].value, 3, f"{name}: edited C2")
        eq(ws["D2"].value, "=B2*C2", f"{name}: D2 formula")
        eq(v["D2"].value, 30, f"{name}: D2 cached")
        eq(v["D5"].value, 155, f"{name}: D5 cached")
        eq(ws["C5"].value, "=STDEV(B2:B4)", f"{name}: unknown function formula kept")
        eq(v["C5"].value, 10, f"{name}: unknown function cached value kept")
        eq(ws["D4"].value, "=B4*C4", f"{name}: shared formula still resolves")
        eq(v["A2"].value, "Rich text & more", f"{name}: rich shared string")
        eq(ws["C2"].number_format, '"¥"#,##0.00', f"{name}: format kept")
        eq(wb.defined_names["Quantities"].attr_text, "Data!$B$2:$B$4", f"{name}: defined name")
        with zipfile.ZipFile(os.path.join(DIR, name)) as z:
            check(b"unknown extension payload" in z.read("xl/worksheets/sheet1.xml"), f"{name}: extension list kept")
            check("xl/calcChain.xml" in z.namelist(), f"{name}: calculation chain kept")

    name = "xlsx-cached-edit-text.xlsx"
    wb, vals = books(name)
    if wb:
        eq(wb["Data"]["A2"].value, 'Visor <Pro> & "co" 日本語 ñ', f"{name}: edited text")
        eq(wb["Data"]["A3"].value, "東京", f"{name}: neighbour shared string")
        eq(wb["Data"]["A1"].value, "Item", f"{name}: header")

    name = "xlsx-cached-bold.xlsx"
    wb, vals = books(name)
    if wb:
        ws = wb["Data"]
        eq(ws["B2"].font.b, True, f"{name}: B2 bold")
        eq(ws["C2"].font.b, True, f"{name}: C2 bold")
        eq(ws["C2"].number_format, '"¥"#,##0.00', f"{name}: C2 keeps its number format")
        eq(bool(ws["C3"].font.b), False, f"{name}: C3 not bold")
        eq(ws["E6"].number_format, "yyyy\\-mm\\-dd", f"{name}: other formats untouched")
        eq(ws["E6"].alignment.horizontal, "right", f"{name}: alignment of another style untouched")

    name = "xlsx-cached-formula-removed.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        eq(ws["D3"].value, 7, f"{name}: formula replaced by a number")
        eq(ws["D4"].value, "=B4*C4", f"{name}: former shared dependent has its own formula")
        eq(v["D4"].value, 45, f"{name}: D4 cached")
        eq(v["D5"].value, 77, f"{name}: D5 cached")
        with zipfile.ZipFile(os.path.join(DIR, name)) as z:
            check("xl/calcChain.xml" not in z.namelist(), f"{name}: stale calculation chain dropped")

    name = "xlsx-cached-sort.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        # the library leaves _xHHHH_ escapes in strings undecoded; decode them with its own helper before comparing
        eq([unescape(v.cell(row=r, column=1).value) for r in (2, 3, 4)], ["Line\r\nbreak", "東京", "Rich text & more"], f"{name}: column A order")
        eq([ws.cell(row=r, column=2).value for r in (2, 3, 4)], [30, 20, 10], f"{name}: sort key order")
        eq([ws.cell(row=r, column=4).value for r in (2, 3, 4)], ["=B2*C2", "=B3*C3", "=B4*C4"], f"{name}: formulas follow their rows")
        eq([v.cell(row=r, column=4).value for r in (2, 3, 4)], [45, 80, 25], f"{name}: cached line totals")
        eq(ws["B5"].value, "=SUM(B2:B4)", f"{name}: total formula")
        eq(ws.row_dimensions[2].height, 20, f"{name}: row height stayed with the row")

    name = "xlsx-cached-unknown-fn.xlsx"
    wb, vals = books(name)
    if wb:
        eq(wb["Data"]["A9"].value, "=VLOOKUP(A1,A1:B4,2,0)", f"{name}: typed unknown function is a formula")
        eq(wb["Data"]["B2"].value, 11, f"{name}: edited input")
        eq(vals["Data"]["B5"].value, 61, f"{name}: SUM cached")
        eq(vals["Data"]["C5"].value, 10, f"{name}: STDEV cached value kept")
        eq(vals["Other Sheet"]["A1"].value, 62, f"{name}: other sheet cached")


def verify_variants():
    """The hand-made file rewritten with prefixed namespaces / white space, and a workbook that had no styles part."""
    warnings.simplefilter("ignore")
    name = "xlsx-prefixed-edit.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        eq(ws["C2"].value, 3, f"{name}: edited C2")
        eq((ws["C2"].font.b, ws["C2"].number_format), (True, '"¥"#,##0.00'), f"{name}: C2 bold currency")
        eq((ws["B3"].value, ws["B3"].number_format), (20, "0%"), f"{name}: B3 percent")
        eq(ws["A10"].value, "new", f"{name}: new row")
        eq(ws["C7"].value, datetime.time(7, 15), f"{name}: typed time")
        eq(ws["D2"].value, "=B2*C2", f"{name}: formula")
        eq((v["D2"].value, v["D5"].value), (30, 155), f"{name}: cached values")
        eq(v["A2"].value, "Rich text & more", f"{name}: shared string")
        eq(ws["D4"].value, "=B4*C4", f"{name}: shared formula")

    name = "xlsx-prefixed-structural.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        eq([ws.cell(row=r, column=2).value for r in (3, 4, 5)], [30, 20, 10], f"{name}: sorted keys")
        eq([ws.cell(row=r, column=4).value for r in (3, 4, 5)], ["=B3*C3", "=B4*C4", "=B5*C5"], f"{name}: formulas")
        eq([v.cell(row=r, column=4).value for r in (3, 4, 5)], [45, 80, 30], f"{name}: cached line totals")
        eq(ws["B6"].value, "=SUM(B3:B5)", f"{name}: total formula")
        eq(ws["A1"].value, "Item", f"{name}: header stayed")
        eq(ws["A2"].value, None, f"{name}: inserted row is empty")
        eq(wb["Other Sheet"]["A1"].value, "=Data!B6+1", f"{name}: cross-sheet reference shifted")
        eq(wb.defined_names["Quantities"].attr_text, "Data!$B$3:$B$5", f"{name}: defined name shifted")

    name = "xlsx-pretty-edit.xlsx"
    wb, vals = books(name)
    if wb:
        ws, v = wb["Data"], vals["Data"]
        eq((ws["C2"].value, ws["F1"].value, ws["C10"].value), (3, "x", 42), f"{name}: edits")
        eq((v["D2"].value, v["D5"].value), (30, 155), f"{name}: cached values")
        eq(ws["B5"].value, "=SUM(B2:B4)", f"{name}: untouched formula")

    name = "xlsx-nostyles-edit.xlsx"
    wb, vals = books(name)
    if wb:
        ws = wb["Sheet1"]
        eq((ws["A1"].value, ws["A1"].font.b), ("plain", True), f"{name}: bold text")
        eq((ws["A2"].value, ws["A2"].number_format), (0.5, "0%"), f"{name}: percent")


def no_author(name):
    """The library substitutes its own name when the creator is absent, so the part itself is inspected."""
    with zipfile.ZipFile(os.path.join(DIR, name)) as z:
        core = xml.dom.minidom.parseString(z.read("docProps/core.xml"))
    people = [n.tagName for n in core.documentElement.childNodes if n.nodeType == n.ELEMENT_NODE and n.tagName in ("dc:creator", "cp:lastModifiedBy")]
    eq(people, [], f"{name}: no author in the core properties")


def verify_blank():
    name = "xlsx-blank.xlsx"
    wb, vals = books(name)
    if wb:
        eq(wb.sheetnames, ["Sheet1"], f"{name}: one sheet")
        no_author(name)
        with zipfile.ZipFile(os.path.join(DIR, name)) as z:
            check(b"<Application>Spatial Office</Application>" in z.read("docProps/app.xml"), f"{name}: application name")

    name = "xlsx-blank-edit.xlsx"
    wb, vals = books(name)
    if wb:
        ws = wb["Sheet1"]
        eq([[c.value for c in row] for row in ws.iter_rows()], [["Item", "Qty"], ["Lens", 4], ["Strap", 6], ["Total", "=SUM(B2:B3)"]], f"{name}: cells")
        eq(vals["Sheet1"]["B4"].value, 10, f"{name}: cached total")
        eq((ws["A1"].font.b, ws["B1"].font.b, bool(ws["A2"].font.b)), (True, True, False), f"{name}: bold header")
        eq(ws["B4"].number_format, '"¥"#,##0.00', f"{name}: currency format")
        no_author(name)

    name = "xlsx-blank-text.xlsx"
    wb, vals = books(name)
    if wb:
        expected = [
            "  leading and trailing  ", "line one\nline two", "tab\there", "carriage\r\nreturn", "a < b && c > d",
            "quote \" and 'apostrophe'", "emoji 😀 and 汉字 and Ωμέγα", "_x0041_ literal", "bell\x07char", "=1+", "]]>", "&amp;",
        ]
        got = [wb["Sheet1"].cell(row=i + 1, column=1).value for i in range(len(expected))]
        for i, (g, e) in enumerate(zip(got, expected)):
            # the library hands back _xHHHH_ escapes of inline strings undecoded; decode them the way it does for shared strings
            if g != e and isinstance(g, str):
                g = unescape(g)
            eq(g, e, f"{name}: A{i + 1}")
        eq(wb["Sheet1"]["A10"].data_type, "s", f"{name}: '=1+' is text, not a formula")

    name = "xlsx-blank-typed.xlsx"
    wb, vals = books(name)
    if wb:
        ws = wb["Sheet1"]
        eq((ws["A1"].value, ws["A1"].number_format), (0.12, "0%"), f"{name}: percent")
        eq((ws["A2"].value, ws["A2"].number_format), (1200, '"¥"#,##0'), f"{name}: currency")
        eq(ws["A3"].value, 1000, f"{name}: exponent")
        eq(ws["A4"].value, True, f"{name}: boolean")
        eq(ws["A5"].value, datetime.datetime(2026, 3, 1), f"{name}: date")
        eq((ws["A6"].value, ws["A6"].number_format), (-4.5, '"$"#,##0.00'), f"{name}: negative currency")
        eq(ws["A7"].value, 1234567.5, f"{name}: grouped number")
        eq(ws["A8"].value, "1,23", f"{name}: not a number")
        eq(ws["A10"].value, datetime.datetime(2026, 3, 1, 18, 30), f"{name}: date and time")

    for name, count in (("xlsx-blank-chart.xlsx", 1), ("xlsx-blank-two-charts.xlsx", 2)):
        wb, vals = books(name)
        if wb:
            ws = wb["Sheet1"]
            eq(len(ws._charts), count, f"{name}: chart count")
            c = [x for x in ws._charts if chart_title(x) == "Quarterly <units> & more"]
            eq(len(c), 1, f"{name}: titled chart")
            if c:
                eq(type(c[0]).__name__, "BarChart", f"{name}: chart type")
                eq(c[0].barDir, "col", f"{name}: direction")
                eq([s.val.numRef.f for s in c[0].series], ["Sheet1!$B$2:$D$2", "Sheet1!$B$3:$D$3"], f"{name}: value references")
                eq([s.tx.strRef.strCache.pt[0].v for s in c[0].series], ["North & <West>", "南"], f"{name}: cached names")
                eq([p.v for p in c[0].series[0].cat.strRef.strCache.pt], ["Q1", "Q2", "Q3"], f"{name}: cached categories")
            eq(ws["A2"].value, "North & <West>", f"{name}: data")
            # the library can write the file back out with the chart(s) still attached
            import io
            buf = io.BytesIO()
            wb.save(buf)
            buf.seek(0)
            eq(len(load_workbook(buf)["Sheet1"]._charts), count, f"{name}: charts survive a save by the library")


def main():
    if not os.path.isdir(DIR):
        print("no", DIR, "- run the :core tests first")
        return 2
    for step in (verify_sales, verify_cached, verify_blank, verify_variants):
        before = len(FAILURES)
        step()
        print(("ok  " if len(FAILURES) == before else "FAIL"), step.__name__)
    files = sorted(f for f in os.listdir(DIR) if f.startswith("xlsx-"))
    print(f"{len(files)} files, {CHECKS} checks, {len(FAILURES)} failed")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
