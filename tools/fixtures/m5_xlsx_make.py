#!/usr/bin/env python3
"""Writes a workbook with notes on two sheets using openpyxl, as input for the M5 comment tests.

Run from the project root, before the tests:  tools/.venv/bin/python tools/fixtures/m5_xlsx_make.py

  core/build/m5-xlsx/in/m5-other-notes.xlsx   notes on Sheet1!A1, Sheet1!C3 (two lines) and Data!B2, written by a
                                              library other than this app's own module (XlsxM5Test skips its
                                              test for this file when it is not there).
"""
import os

from openpyxl import Workbook
from openpyxl.comments import Comment

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "core", "build", "m5-xlsx", "in"))


def main():
    os.makedirs(OUT, exist_ok=True)
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["Item", "Units", "Price"])
    ws.append(["Headset", 120, 2999])
    ws.append(["Strap", 40, 199])
    ws["A1"].comment = Comment("First note", "Wang Lei")
    ws["C3"].comment = Comment("Second note\nwith two lines", "Ana")
    data = wb.create_sheet("Data")
    data.append([1, 2, 3])
    data.append([4, 5, 6])
    data["B2"].comment = Comment("On the second sheet", "Ana")
    path = os.path.join(OUT, "m5-other-notes.xlsx")
    wb.save(path)
    print("wrote", path)


if __name__ == "__main__":
    main()
