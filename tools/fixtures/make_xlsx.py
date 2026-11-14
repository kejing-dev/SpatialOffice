#!/usr/bin/env python3
"""Generates the spreadsheet fixtures used by the :core xlsx tests.

Run from the project root:  tools/.venv/bin/python tools/fixtures/make_xlsx.py

  xlsx-sales.xlsx   written with openpyxl: two sheets, formulas WITHOUT cached values, bold headers, percent and
                    currency columns, a merged title, a data validation, a conditional format, a defined name,
                    a bar chart over A1:M5 and a scatter chart on the second sheet.
  xlsx-cached.xlsx  written by hand (zip + XML): cached <v> values, a rich-text shared string, a phonetic run,
                    a shared formula, a function the evaluator does not know, a stale cache, a calculation chain,
                    custom number formats, an extension list and a custom XML part.
"""
import datetime
import os
import re
import warnings
import zipfile

from openpyxl import Workbook, load_workbook
from openpyxl.chart import BarChart, Reference, ScatterChart, Series
from openpyxl.formatting.rule import CellIsRule
from openpyxl.styles import Font, PatternFill
from openpyxl.workbook.defined_name import DefinedName
from openpyxl.worksheet.datavalidation import DataValidation

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "core", "src", "test", "resources", "fixtures"))

MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
PRODUCTS = {
    "Headset": [120, 135, 150, 160, 170, 165, 180, 190, 175, 200, 220, 260],
    "Controller": [80, 82, 90, 95, 99, 104, 110, 108, 115, 121, 130, 150],
    "Strap": [40, 38, 45, 50, 48, 52, 60, 58, 61, 66, 70, 85],
    "Case": [25, 30, 28, 33, 35, 31, 40, 42, 39, 45, 50, 62],
}


def make_sales(path):
    wb = Workbook()
    ws = wb.active
    ws.title = "Sales"
    bold = Font(bold=True)

    ws.append(["Product"] + MONTHS + ["Revenue", "Share"])
    for c in ws[1]:
        c.font = bold
    for name, nums in PRODUCTS.items():
        ws.append([name] + nums)
    ws.append(["Total"])
    ws["A6"].font = bold
    for col in range(2, 14):
        letter = ws.cell(row=1, column=col).column_letter
        ws.cell(row=6, column=col, value=f"=SUM({letter}2:{letter}5)")
    for row in range(2, 6):
        ws.cell(row=row, column=14, value=f"=SUM(B{row}:M{row})*12.5").number_format = '"¥"#,##0.00'
        ws.cell(row=row, column=15, value=f"=N{row}/$N$6").number_format = "0.0%"
    ws.cell(row=6, column=14, value="=SUM(N2:N5)").number_format = '"¥"#,##0.00'
    ws.cell(row=6, column=15, value="=SUM(O2:O5)").number_format = "0%"

    ws["A8"] = "FY2026 unit sales — 销售概览"
    ws["A8"].font = Font(bold=True, size=14)
    ws.merge_cells("A8:M8")
    ws.column_dimensions["A"].width = 18
    ws.column_dimensions["N"].width = 14.5

    dv = DataValidation(type="whole", operator="greaterThanOrEqual", formula1="0", allow_blank=True)
    dv.error = "Units must be a whole number"
    dv.add("B2:M5")
    ws.add_data_validation(dv)
    ws.conditional_formatting.add(
        "B2:M5", CellIsRule(operator="greaterThan", formula=["150"], fill=PatternFill("solid", start_color="FFE2F0D9", end_color="FFE2F0D9"))
    )

    chart = BarChart()
    chart.type = "col"
    chart.title = "Units by month"
    chart.add_data(Reference(ws, min_col=1, max_col=13, min_row=2, max_row=5), from_rows=True, titles_from_data=True)
    chart.set_categories(Reference(ws, min_col=2, max_col=13, min_row=1, max_row=1))
    ws.add_chart(chart, "A10")

    notes = wb.create_sheet("Notes")
    notes.append(["Week", "Visitors", "Comment", "Date"])
    for c in notes[1]:
        c.font = bold
    comments = ["R&D <alpha> \"quoted\"", "日本語 テスト", "naïve café", "plain", "  padded  "]
    visitors = [310, 290, 355, 402, 388]
    for i in range(5):
        notes.append([i + 1, visitors[i], comments[i], datetime.date(2026, 3, 1 + 7 * i)])
        notes.cell(row=i + 2, column=4).number_format = "yyyy-mm-dd"
    notes["D6"].number_format = "mm-dd-yy"
    notes["F1"] = "Grand total"
    notes["F2"] = "=Sales!N6"
    notes["F2"].number_format = '"¥"#,##0.00'
    notes["F3"] = "=SUM(Sales!B2:M2)*2"
    notes["F4"] = '=IF(F3>4000,"big","small")'
    notes["F5"] = "=AVERAGE(B2:B6)"
    notes["F6"] = "=Sales!B2+B2"

    sc = ScatterChart()
    sc.title = "Visitors per week"
    xs = Reference(notes, min_col=1, min_row=2, max_row=6)
    ys = Reference(notes, min_col=2, min_row=1, max_row=6)
    sc.series.append(Series(ys, xs, title_from_data=True))
    notes.add_chart(sc, "H2")

    wb.defined_names["JanUnits"] = DefinedName("JanUnits", attr_text="Sales!$B$2:$B$5")
    wb.loaded_theme = THEME.encode("utf-8")
    wb.properties.creator = "Fixture Maker"
    wb.save(path)
    neutralise(path)


def _theme():
    def three(x):
        return x * 3

    colors = [("dk1", "000000"), ("lt1", "FFFFFF"), ("dk2", "1F3A5F"), ("lt2", "EEECE1"), ("accent1", "2F6DB5"),
              ("accent2", "C0504D"), ("accent3", "7FA650"), ("accent4", "8064A2"), ("accent5", "3AA6B9"),
              ("accent6", "E08A2E"), ("hlink", "2F6DB5"), ("folHlink", "8064A2")]
    clr = "".join(f'<a:{n}><a:srgbClr val="{v}"/></a:{n}>' for n, v in colors)
    font = '<a:latin typeface="Sans"/><a:ea typeface=""/><a:cs typeface=""/>'
    fill = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Plain"><a:themeElements>'
        f'<a:clrScheme name="Plain">{clr}</a:clrScheme>'
        f'<a:fontScheme name="Plain"><a:majorFont>{font}</a:majorFont><a:minorFont>{font}</a:minorFont></a:fontScheme>'
        f'<a:fmtScheme name="Plain"><a:fillStyleLst>{three(fill)}</a:fillStyleLst>'
        f'<a:lnStyleLst>{three('<a:ln w="9525">' + fill + "</a:ln>")}</a:lnStyleLst>'
        f'<a:effectStyleLst>{three("<a:effectStyle><a:effectLst/></a:effectStyle>")}</a:effectStyleLst>'
        f'<a:bgFillStyleLst>{three(fill)}</a:bgFillStyleLst></a:fmtScheme>'
        "</a:themeElements></a:theme>"
    )


THEME = _theme()
APP_XML = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
    "<Application>Fixture Maker</Application></Properties>"
)
FONT_NAME = re.compile(rb'<name val="[^"]*"/>')
TYPEFACE = re.compile(rb'typeface="([^"]*)"')


def neutralise(path):
    """Rewrites the generated package without the generating library's default application and font names."""
    with zipfile.ZipFile(path) as z:
        items = [(i.filename, z.read(i.filename)) for i in z.infolist()]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in items:
            if name == "docProps/app.xml":
                data = APP_XML.encode("utf-8")
            elif name == "xl/styles.xml":
                data = FONT_NAME.sub(b'<name val="Sans"/>', data)
            z.writestr(name, data)


def check_neutral(path):
    """Only the neutral application and font names may appear in a fixture."""
    with zipfile.ZipFile(path) as z:
        assert b"<Application>Fixture Maker</Application>" in z.read("docProps/app.xml"), path
        for i in z.infolist():
            data = z.read(i.filename)
            assert set(FONT_NAME.findall(data)) <= {b'<name val="Sans"/>'}, (path, i.filename)
            assert set(TYPEFACE.findall(data)) <= {b"Sans", b""}, (path, i.filename)


MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'


def make_cached(path):
    d1 = (datetime.date(2026, 3, 1) - datetime.date(1899, 12, 30)).days
    parts = {}
    parts["[Content_Types].xml"] = DECL + (
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
        '<Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
        "</Types>"
    )
    parts["_rels/.rels"] = DECL + (
        f'<Relationships xmlns="{PKG_REL}">'
        f'<Relationship Id="rId1" Type="{REL}/officeDocument" Target="xl/workbook.xml"/>'
        f'<Relationship Id="rId2" Type="{REL}/extended-properties" Target="docProps/app.xml"/>'
        "</Relationships>"
    )
    parts["docProps/app.xml"] = DECL + (
        '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
        "<Application>Fixture Maker</Application></Properties>"
    )
    parts["xl/workbook.xml"] = DECL + (
        f'<workbook xmlns="{MAIN}" xmlns:r="{REL}"><workbookPr/>'
        '<sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Other Sheet" sheetId="2" r:id="rId2"/></sheets>'
        "<definedNames><definedName name=\"Quantities\">Data!$B$2:$B$4</definedName></definedNames>"
        '<calcPr calcId="191029"/></workbook>'
    )
    parts["xl/_rels/workbook.xml.rels"] = DECL + (
        f'<Relationships xmlns="{PKG_REL}">'
        f'<Relationship Id="rId1" Type="{REL}/worksheet" Target="worksheets/sheet1.xml"/>'
        f'<Relationship Id="rId2" Type="{REL}/worksheet" Target="worksheets/sheet2.xml"/>'
        f'<Relationship Id="rId3" Type="{REL}/styles" Target="styles.xml"/>'
        f'<Relationship Id="rId4" Type="{REL}/sharedStrings" Target="sharedStrings.xml"/>'
        f'<Relationship Id="rId5" Type="{REL}/calcChain" Target="calcChain.xml"/>'
        "</Relationships>"
    )
    parts["xl/sharedStrings.xml"] = DECL + (
        f'<sst xmlns="{MAIN}" count="5" uniqueCount="5">'
        "<si><t>Item</t></si>"
        "<si><t>Qty</t></si>"
        '<si><r><rPr><b/><sz val="11"/></rPr><t xml:space="preserve">Rich </t></r><r><t>text &amp; more</t></r></si>'
        '<si><t>東京</t><rPh sb="0" eb="2"><t>トウキョウ</t></rPh><phoneticPr fontId="1"/></si>'
        "<si><t>Line_x000D__x000A_break</t></si>"
        "</sst>"
    )
    parts["xl/styles.xml"] = DECL + (
        f'<styleSheet xmlns="{MAIN}">'
        '<numFmts count="2"><numFmt numFmtId="164" formatCode="&quot;¥&quot;#,##0.00"/><numFmt numFmtId="165" formatCode="yyyy\\-mm\\-dd"/></numFmts>'
        '<fonts count="2"><font><sz val="11"/><color theme="1"/><name val="Sans"/><family val="2"/></font>'
        '<font><b/><sz val="11"/><color theme="1"/><name val="Sans"/><family val="2"/></font></fonts>'
        '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
        '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        '<cellXfs count="7">'
        '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
        '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>'
        '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
        '<xf numFmtId="10" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
        '<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="right"/></xf>'
        '<xf numFmtId="22" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
        '<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
        "</cellXfs>"
        '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
        "</styleSheet>"
    )
    parts["xl/worksheets/sheet1.xml"] = DECL + (
        f'<worksheet xmlns="{MAIN}" xmlns:r="{REL}">'
        '<dimension ref="A1:F7"/>'
        '<sheetViews><sheetView tabSelected="1" workbookViewId="0"/></sheetViews>'
        '<sheetFormatPr defaultRowHeight="15"/>'
        '<cols><col min="1" max="1" width="22.5" customWidth="1"/><col min="3" max="4" width="12" customWidth="1"/></cols>'
        "<sheetData>"
        '<row r="1" spans="1:4">'
        '<c r="A1" s="1" t="s"><v>0</v></c><c r="B1" s="1" t="s"><v>1</v></c>'
        '<c r="C1" s="1" t="inlineStr"><is><t>Price</t></is></c><c r="D1" s="1" t="inlineStr"><is><r><t>Line </t></r><r><t>total</t></r></is></c>'
        "</row>"
        '<row r="2" spans="1:4" ht="20" customHeight="1">'
        '<c r="A2" t="s"><v>2</v></c><c r="B2"><v>10</v></c><c r="C2" s="2"><v>2.5</v></c><c r="D2" s="2"><f>B2*C2</f><v>25</v></c>'
        "</row>"
        '<row r="3" spans="1:4">'
        '<c r="A3" t="s"><v>3</v></c><c r="B3"><v>20</v></c><c r="C3" s="2"><v>4</v></c>'
        '<c r="D3" s="2"><f t="shared" ref="D3:D4" si="0">B3*C3</f><v>80</v></c>'
        "</row>"
        '<row r="4" spans="1:4">'
        '<c r="A4" t="s"><v>4</v></c><c r="B4"><v>30</v></c><c r="C4" s="2"><v>1.5</v></c>'
        '<c r="D4" s="2"><f t="shared" si="0"/><v>45</v></c>'
        "</row>"
        '<row r="5" spans="1:4">'
        '<c r="A5" s="1" t="inlineStr"><is><t>Total</t></is></c><c r="B5"><f>SUM(B2:B4)</f><v>60</v></c>'
        '<c r="C5"><f>STDEV(B2:B4)</f><v>10</v></c><c r="D5" s="2"><f>SUM(D2:D4)</f><v>150</v></c>'
        "</row>"
        '<row r="6" spans="1:6">'
        '<c r="A6" t="b"><v>1</v></c><c r="B6" t="e"><f>1/0</f><v>#DIV/0!</v></c>'
        '<c r="C6" t="str"><f>A1&amp;"!"</f><v>Item!</v></c><c r="D6" s="3"><v>0.125</v></c>'
        f'<c r="E6" s="4"><v>{d1}</v></c><c r="F6" s="5"><v>{d1}.75</v></c>'
        "</row>"
        '<row r="7" spans="1:2">'
        '<c r="A7"><f>B5*2</f><v>999</v></c><c r="B7" s="6"><v>1234567.891</v></c>'
        "</row>"
        "</sheetData>"
        '<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>'
        '<extLst><ext uri="{00000000-FACE-4D0C-9F6B-5A1D2E3F4A5B}" xmlns:zz="urn:fixture:unknown"><zz:marker keep="yes">unknown extension payload</zz:marker></ext></extLst>'
        "</worksheet>"
    )
    parts["xl/worksheets/sheet2.xml"] = DECL + (
        f'<worksheet xmlns="{MAIN}" xmlns:r="{REL}">'
        '<dimension ref="A1:B2"/>'
        "<sheetData>"
        '<row r="1"><c r="A1"><f>Data!B5+1</f><v>61</v></c><c r="B1"><f>SUM(Quantities)</f><v>60</v></c></row>'
        '<row r="2"><c r="A2"><f>\'Other Sheet\'!A1*2</f><v>122</v></c></row>'
        "</sheetData>"
        "</worksheet>"
    )
    parts["xl/calcChain.xml"] = DECL + (
        f'<calcChain xmlns="{MAIN}">'
        '<c r="D2" i="1"/><c r="D3" i="1"/><c r="D4" i="1"/><c r="B5" i="1"/><c r="C5" i="1"/><c r="D5" i="1"/>'
        '<c r="B6" i="1"/><c r="C6" i="1"/><c r="A7" i="1"/><c r="A1" i="2"/><c r="B1" i="2"/><c r="A2" i="2"/>'
        "</calcChain>"
    )
    parts["customXml/item1.xml"] = '<?xml version="1.0" encoding="UTF-8"?><fixture xmlns="urn:fixture:custom"><note>keep me</note></fixture>'

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items():
            z.writestr(name, text.encode("utf-8"))


def main():
    os.makedirs(OUT, exist_ok=True)
    sales = os.path.join(OUT, "xlsx-sales.xlsx")
    cached = os.path.join(OUT, "xlsx-cached.xlsx")
    make_sales(sales)
    make_cached(cached)

    check_neutral(sales)
    check_neutral(cached)
    # both must open in the independent library
    wb = load_workbook(sales)
    assert wb.sheetnames == ["Sales", "Notes"], wb.sheetnames
    assert len(wb["Sales"]._charts) == 1 and len(wb["Notes"]._charts) == 1
    warnings.simplefilter("ignore")  # the library warns about the unknown extension list it cannot keep
    wb = load_workbook(cached)
    assert wb["Data"]["D4"].value == "=B4*C4", wb["Data"]["D4"].value
    wb = load_workbook(cached, data_only=True)
    assert wb["Data"]["C5"].value == 10
    print("wrote", sales)
    print("wrote", cached)


if __name__ == "__main__":
    main()
