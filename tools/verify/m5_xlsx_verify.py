#!/usr/bin/env python3
"""Independent check of the spreadsheet files the M5 comment tests (XlsxM5Test) save to core/build/m5-xlsx/.

Run from the project root, after the tests:  tools/.venv/bin/python tools/verify/m5_xlsx_verify.py

Nothing here uses the app's own code. Every file is checked four ways:
  1. package: the zip is intact, every XML / VML part is well-formed, every part has a content type, every
     relationship target exists, the sheet's child elements are in schema order (<legacyDrawing> after <drawing>,
     before <tableParts>);
  2. the two forms of a comment agree: a thread (threaded-comments part + person list) has an entry in the sheet's
     `comments` part on the SAME cell with author `tc={thread id}`, every entry of the `comments` part has exactly
     one note shape in the legacy drawing with the same row / column, replies sit on their thread's cell, every
     author id is in the person list, ids are GUIDs and unique in the workbook, shape ids are unique;
  3. openpyxl opens the file and reports a comment on every commented cell;
  4. what is read here (cell, kind, author, date, text, resolved, replies) equals the `.json` list the tests wrote
     from the app's own reading of the saved file.
Files whose name has `robust` in it and whose list says `relaxed` are broken on purpose; for them the "two forms
agree" checks that the breakage defeats are left out.
"""
import json
import os
import re
import sys
import warnings
import zipfile
import xml.etree.ElementTree as ET

from openpyxl import load_workbook

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.normpath(os.path.join(HERE, "..", "..", "core", "build", "m5-xlsx"))

MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
TC = "http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments"
RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
VML_X = "urn:schemas-microsoft-com:office:excel"
VML_V = "urn:schemas-microsoft-com:vml"

EXPECTED_FILES = [
    "m5-acceptance.xlsx", "m5-untouched.xlsx", "m5-blank-first-comment.xlsx", "m5-blank-second-comment.xlsx",
    "m5-sales-with-drawing.xlsx", "m5-table-sheet.xlsx", "m5-chart-and-comment.xlsx", "m5-two-sheets.xlsx",
    "m5-resolved.xlsx", "m5-reopened.xlsx", "m5-edited.xlsx", "m5-all-deleted.xlsx", "m5-deleted-then-added.xlsx",
    "m5-more-replies.xlsx", "m5-rows-columns-inserted.xlsx", "m5-rows-columns-deleted.xlsx", "m5-sorted.xlsx",
    "m5-ids.xlsx", "m5-robust-missing-person.xlsx", "m5-robust-no-shape.xlsx", "m5-robust-thread-without-copy.xlsx",
    "m5-robust-empty-comments-0.xlsx", "m5-robust-empty-comments-1.xlsx", "m5-robust-empty-comments-2.xlsx",
    "m5-robust-dangling-legacy-drawing.xlsx", "m5-note-headings.xlsx",
]
# written only when tools/fixtures/m5_xlsx_make.py ran before the tests
OPTIONAL_FILES = ["m5-other-notes-edited.xlsx"]

SHEET_ORDER = [
    "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData", "sheetCalcPr", "sheetProtection",
    "protectedRanges", "scenarios", "autoFilter", "sortState", "dataConsolidate", "customSheetViews", "mergeCells",
    "phoneticPr", "conditionalFormatting", "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup",
    "headerFooter", "rowBreaks", "colBreaks", "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing",
    "legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts",
    "extLst",
]

GUID = re.compile(r"^\{[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\}$")
DT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?$")
CELL = re.compile(r"^([A-Z]{1,3})([0-9]+)$")

CHECKS = 0
FAILURES = []


def check(cond, what):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILURES.append(what)
        print("   FAIL", what)
    return cond


def eq(actual, expected, what):
    return check(actual == expected, f"{what}: expected {expected!r}, got {actual!r}")


def local(tag):
    return tag.rsplit("}", 1)[-1]


def resolve(owner, target):
    if target.startswith("/"):
        return target.lstrip("/")
    return os.path.normpath(os.path.join(os.path.dirname(owner), target)).replace(os.sep, "/")


def rels_of(z, names, part):
    folder, base = os.path.split(part)
    rp = (folder + "/" if folder else "") + "_rels/" + base + ".rels"
    if rp not in names:
        return []
    out = []
    for rel in ET.fromstring(z.read(rp)).findall(f"{{{RELS}}}Relationship"):
        if rel.get("TargetMode") == "External":
            continue
        out.append((rel.get("Id"), rel.get("Type"), resolve(part, rel.get("Target"))))
    return out


def cell_rc(ref):
    m = CELL.match(ref)
    col = 0
    for ch in m.group(1):
        col = col * 26 + (ord(ch) - 64)
    return int(m.group(2)) - 1, col - 1


def iso(dt):
    """The file's universal time without a zone mark → ISO-8601 with Z, seconds only."""
    return dt.split(".")[0] + "Z" if DT.match(dt) else dt


def package_ok(name, z, names):
    check(z.testzip() is None, f"{name}: zip entries are intact")
    eq(len(names), len(set(names)), f"{name}: no duplicate entries")
    for n in names:
        if n.endswith((".xml", ".rels", ".vml")):
            try:
                ET.fromstring(z.read(n))
                check(True, f"{name}: {n} is well-formed")
            except ET.ParseError as e:
                # the tests break one part on purpose in no saved file; anything here is a real failure
                check(False, f"{name}: {n} is not well-formed XML ({e})")
    ct = ET.fromstring(z.read("[Content_Types].xml"))
    defaults = {d.get("Extension").lower() for d in ct.findall(f"{{{CT}}}Default")}
    overrides = {o.get("PartName"): o.get("ContentType") for o in ct.findall(f"{{{CT}}}Override")}
    eq(len(overrides), len(ct.findall(f"{{{CT}}}Override")), f"{name}: no part has two content-type overrides")
    for n in names:
        if n == "[Content_Types].xml":
            continue
        check("/" + n in overrides or n.rsplit(".", 1)[-1].lower() in defaults, f"{name}: {n} has a content type")
    for o in overrides:
        check(o.lstrip("/") in names, f"{name}: content-type override {o} points at an existing part")
    for n in names:
        if not n.endswith(".rels"):
            continue
        ids = []
        owner_dir = os.path.dirname(os.path.dirname(n))
        for rel in ET.fromstring(z.read(n)).findall(f"{{{RELS}}}Relationship"):
            ids.append(rel.get("Id"))
            if rel.get("TargetMode") == "External":
                continue
            t = rel.get("Target")
            full = t.lstrip("/") if t.startswith("/") else os.path.normpath(os.path.join(owner_dir, t)).replace(os.sep, "/")
            check(full in names, f"{name}: relationship {rel.get('Id')} in {n} -> {full} exists")
        eq(len(ids), len(set(ids)), f"{name}: relationship ids in {n} are unique")
    return overrides, defaults


ESCAPED = re.compile(r"_x([0-9A-Fa-f]{4})_")


def plain(text):
    """Text of a string element: `_xHHHH_` stands for the character with that code (`_x005F_` for a literal underscore)."""
    return ESCAPED.sub(lambda m: chr(int(m.group(1), 16)), text or "")


def note_text(text_el, author):
    """Text of a note entry without the author heading most programs put in front of it."""
    full = plain("".join(t.text or "" for t in text_el.iter(f"{{{MAIN}}}t"))) if text_el is not None else ""
    for head in (author + ":\r\n", author + ":\n"):
        if author and full.startswith(head):
            return full[len(head):]
    return full


def read_sheet(name, z, names, overrides, defaults, sheet_part, persons, seen_ids, seen_shape_ids, relaxed):
    """Everything about comments of one sheet, read from the parts; returns the list in the manifest's shape."""
    label = f"{name}: {sheet_part}"
    root = ET.fromstring(z.read(sheet_part))
    kids = [local(k.tag) for k in root]
    order = [SHEET_ORDER.index(k) for k in kids if k in SHEET_ORDER]
    check(order == sorted(order), f"{label}: child elements are in schema order ({kids})")
    eq(kids.count("legacyDrawing") <= 1, True, f"{label}: at most one legacyDrawing")
    rels = rels_of(z, names, sheet_part)
    by_id = {r[0]: r for r in rels}
    threads_part = next((r[2] for r in rels if r[1].endswith("/threadedComment")), None)
    legacy_part = next((r[2] for r in rels if r[1].endswith("/comments")), None)
    vml_part = None
    ld = root.find(f"{{{MAIN}}}legacyDrawing")
    if ld is not None:
        rel = by_id.get(ld.get(f"{{{R}}}id"))
        if check(rel is not None, f"{label}: legacyDrawing r:id is one of the sheet's relationships"):
            check(rel[1].endswith("/vmlDrawing"), f"{label}: legacyDrawing points at a vmlDrawing relationship")
            vml_part = rel[2]
            check("vml" in defaults, f"{label}: [Content_Types] has the vml Default")

    # threads
    roots, replies = [], {}
    if threads_part:
        eq(overrides.get("/" + threads_part), "application/vnd.ms-excel.threadedcomments+xml", f"{label}: content type of {threads_part}")
        troot = ET.fromstring(z.read(threads_part))
        eq(troot.tag, f"{{{TC}}}ThreadedComments", f"{label}: root of {threads_part}")
        items = troot.findall(f"{{{TC}}}threadedComment")
        ids = {i.get("id") for i in items}
        for i in items:
            tid = i.get("id")
            check(bool(GUID.match(tid or "")), f"{label}: thread item id {tid} is a GUID")
            check(tid not in seen_ids, f"{label}: thread item id {tid} is unique in the workbook")
            seen_ids.add(tid)
            check(bool(CELL.match(i.get("ref") or "")), f"{label}: thread item {tid} has a cell reference")
            check(bool(DT.match(i.get("dT") or "")), f"{label}: thread item {tid} dT {i.get('dT')!r} is a date-time")
            check(i.find(f"{{{TC}}}text") is not None, f"{label}: thread item {tid} has text")
            if not relaxed:
                check(i.get("personId") in persons, f"{label}: author of {tid} is in the person list")
            if i.get("parentId"):
                check(i.get("parentId") in ids, f"{label}: reply {tid} names a message of this part")
                replies.setdefault(i.get("parentId"), []).append(i)
            else:
                roots.append(i)
        for r in roots:
            for rep in replies.get(r.get("id"), []):
                eq(rep.get("ref"), r.get("ref"), f"{label}: reply {rep.get('id')} sits on its thread's cell")
                check(rep.get("parentId") in {x.get("id") for x in roots}, f"{label}: reply {rep.get('id')} answers a first message")
        eq(len({r.get("ref") for r in roots}), len(roots), f"{label}: one thread per cell")

    # entries of the comments part
    entries, authors = [], []
    if legacy_part:
        eq(overrides.get("/" + legacy_part), "application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml", f"{label}: content type of {legacy_part}")
        lroot = ET.fromstring(z.read(legacy_part))
        eq(lroot.tag, f"{{{MAIN}}}comments", f"{label}: root of {legacy_part}")
        eq([local(k.tag) for k in lroot][:2], ["authors", "commentList"], f"{label}: {legacy_part} has authors then commentList")
        authors = [a.text or "" for a in lroot.findall(f"{{{MAIN}}}authors/{{{MAIN}}}author")]
        entries = lroot.findall(f"{{{MAIN}}}commentList/{{{MAIN}}}comment")
        refs = [e.get("ref") for e in entries]
        eq(len(refs), len(set(refs)), f"{label}: one entry per cell in {legacy_part}")
        for e in entries:
            a = e.get("authorId")
            check(a is not None and a.isdigit() and int(a) < len(authors), f"{label}: entry {e.get('ref')} names a listed author")
            check(e.find(f"{{{MAIN}}}text") is not None, f"{label}: entry {e.get('ref')} has text")

    # note shapes
    shapes = []
    if vml_part:
        vroot = ET.fromstring(z.read(vml_part))
        part_ids = [sh.get("id") for sh in vroot.findall(f"{{{VML_V}}}shape")]
        eq(len(part_ids), len(set(part_ids)), f"{label}: shape ids in {vml_part} are unique")
        for sh in vroot.findall(f"{{{VML_V}}}shape"):
            sid = sh.get("id")
            # the other library's own files reuse shape ids from one drawing to the next; ours must not
            if name not in OPTIONAL_FILES:
                check(sid not in seen_shape_ids, f"{label}: shape id {sid} is unique in the workbook")
            seen_shape_ids.add(sid)
            cd = sh.find(f"{{{VML_X}}}ClientData")
            if cd is None or cd.get("ObjectType") != "Note":
                continue
            eq(sh.get("type"), "#_x0000_t202", f"{label}: note shape {sid} uses the text-box shape type")
            row, col = cd.find(f"{{{VML_X}}}Row"), cd.find(f"{{{VML_X}}}Column")
            if check(row is not None and col is not None, f"{label}: note shape {sid} names its row and column"):
                shapes.append((int(row.text), int(col.text)))
            an = cd.find(f"{{{VML_X}}}Anchor")
            if an is not None:
                nums = [p.strip() for p in (an.text or "").split(",")]
                check(len(nums) == 8 and all(p.isdigit() for p in nums), f"{label}: anchor of {sid} is eight numbers ({an.text!r})")
        if shapes:
            check(any(st.get("id") == "_x0000_t202" for st in vroot.findall(f"{{{VML_V}}}shapetype")), f"{label}: {vml_part} defines the text-box shape type")

    # the two forms agree, on the same cell
    entry_at = {e.get("ref"): e for e in entries}
    for r in roots:
        e = entry_at.get(r.get("ref"))
        if check(e is not None, f"{label}: thread {r.get('id')} on {r.get('ref')} has an entry in the comments part on the same cell"):
            eq(authors[int(e.get("authorId"))], "tc=" + r.get("id"), f"{label}: author of the entry of thread {r.get('id')}")
    if not relaxed:
        eq(sorted(shapes), sorted(cell_rc(e.get("ref")) for e in entries), f"{label}: one note shape per entry of the comments part, same cells")
    else:
        check(set(shapes) <= {cell_rc(e.get("ref")) for e in entries}, f"{label}: every note shape belongs to an entry")
    eq(len(shapes), len(set(shapes)), f"{label}: one note shape per cell")

    # the list, as the manifest gives it
    out = []
    thread_cells = set()
    for r in roots:
        thread_cells.add(r.get("ref"))
        out.append({
            "id": r.get("id"), "cell": r.get("ref"), "kind": "THREAD", "author": persons.get(r.get("personId"), ""),
            "date": iso(r.get("dT")), "text": plain(r.find(f"{{{TC}}}text").text), "resolved": r.get("done") in ("1", "true"),
            "replies": [{"id": x.get("id"), "author": persons.get(x.get("personId"), ""), "date": iso(x.get("dT")),
                         "text": plain(x.find(f"{{{TC}}}text").text)} for x in replies.get(r.get("id"), [])],
        })
    for e in entries:
        if e.get("ref") in thread_cells:
            continue
        author = authors[int(e.get("authorId"))]
        out.append({"cell": e.get("ref"), "kind": "NOTE", "author": author, "date": "", "text": note_text(e.find(f"{{{MAIN}}}text"), author),
                    "resolved": False, "replies": []})
    out.sort(key=lambda c: cell_rc(c["cell"]))
    return out


def verify(name, optional=False):
    path = os.path.join(DIR, name)
    if not os.path.exists(path):
        if not optional:
            check(False, f"{name}: file was not written by the tests")
        else:
            print(f"   (skipped: {name} is written only after tools/fixtures/m5_xlsx_make.py)")
        return
    print(name)
    with open(path + ".json", encoding="utf-8") as f:
        manifest = json.load(f)
    relaxed = bool(manifest.get("relaxed"))
    check(relaxed == ("robust-missing-person" in name or "robust-no-shape" in name), f"{name}: only the files broken on purpose are checked loosely")
    found = {}
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        overrides, defaults = package_ok(name, z, names)
        wb_part = "xl/workbook.xml"
        wb_rels = rels_of(z, names, wb_part)
        persons = {}
        person_part = next((r[2] for r in wb_rels if r[1].endswith("/person")), None)
        if person_part:
            eq(overrides.get("/" + person_part), "application/vnd.ms-excel.person+xml", f"{name}: content type of {person_part}")
            proot = ET.fromstring(z.read(person_part))
            eq(proot.tag, f"{{{TC}}}personList", f"{name}: root of {person_part}")
            plist = proot.findall(f"{{{TC}}}person")
            for p in plist:
                check(bool(GUID.match(p.get("id") or "")), f"{name}: person id {p.get('id')} is a GUID")
                check(p.get("displayName") is not None and p.get("userId") is not None and p.get("providerId") is not None, f"{name}: person {p.get('id')} has its attributes")
                persons[p.get("id")] = p.get("displayName")
            eq(len(persons), len(plist), f"{name}: person ids are unique")
            eq(len(set(persons.values())), len(persons), f"{name}: no person is listed twice")
        by_id = {r[0]: r for r in wb_rels}
        seen_ids, seen_shape_ids = set(), set()
        any_thread = False
        for s in ET.fromstring(z.read(wb_part)).find(f"{{{MAIN}}}sheets"):
            part = by_id[s.get(f"{{{R}}}id")][2]
            found[s.get("name")] = read_sheet(name, z, names, overrides, defaults, part, persons, seen_ids, seen_shape_ids, relaxed)
            any_thread = any_thread or any(c["kind"] == "THREAD" for c in found[s.get("name")])
        if any_thread:
            check(person_part is not None, f"{name}: a workbook with threads has a person list")

    # the app's own reading of the saved file says the same
    eq([s["name"] for s in manifest["sheets"]], list(found.keys()), f"{name}: sheets")
    for s in manifest["sheets"]:
        mine = found.get(s["name"], [])
        theirs = s["comments"]
        eq(len(theirs), len(mine), f"{name}: number of comments on {s['name']}")
        for a, b in zip(theirs, mine):
            where = f"{name}: {s['name']}!{b['cell']}"
            for key in ("cell", "kind", "author", "date", "text", "resolved"):
                eq(a[key], b[key], f"{where} {key}")
            if b["kind"] == "THREAD":
                eq(a["id"], b["id"], f"{where} id")
            eq([(r["id"], r["author"], r["date"], r["text"]) for r in a["replies"]],
               [(r["id"], r["author"], r["date"], r["text"]) for r in b["replies"]], f"{where} replies")
        ids = [c["id"] for c in theirs] + [r["id"] for c in theirs for r in c["replies"]]
        eq(len(ids), len(set(ids)), f"{name}: ids the app gives on {s['name']} are unique")

    # openpyxl opens it and sees a comment on every commented cell (and on no other)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            wb = load_workbook(path)
        check(True, f"{name}: openpyxl opens the file")
    except Exception as e:  # noqa: BLE001
        check(False, f"{name}: openpyxl opens the file ({e!r})")
        return
    for sheet_name, comments in found.items():
        ws = wb[sheet_name]
        cells = {c["cell"] for c in comments}
        for c in comments:
            got = ws[c["cell"]].comment
            if check(got is not None, f"{name}: openpyxl reports a comment on {sheet_name}!{c['cell']}"):
                # openpyxl hands out the text of a note with its `_xHHHH_` escapes as they are in the file
                texts = (got.text, plain(got.text))
                check(any(c["text"] in t for t in texts), f"{name}: openpyxl's text of {sheet_name}!{c['cell']} holds the comment's text")
                for r in c["replies"]:
                    check(any(r["text"] in t for t in texts), f"{name}: openpyxl's text of {sheet_name}!{c['cell']} holds the reply {r['text']!r}")
        others = [cell.coordinate for row in ws.iter_rows() for cell in row if cell.comment is not None and cell.coordinate not in cells]
        eq(others, [], f"{name}: openpyxl reports no other comment on {sheet_name}")

    # the acceptance, spelled out
    if name == "m5-acceptance.xlsx":
        ws = wb["Sheet1"]
        check(ws["C2"].comment is not None, f"{name}: ws['C2'].comment is not None")
        check(ws["A1"].comment.text.endswith("Header wording agreed"), f"{name}: the note on A1 has its new text")
        eq(ws["B2"].value, 120, f"{name}: cells are as they were")
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            for part in ("xl/threadedComments/threadedComment1.xml", "xl/persons/person.xml", "xl/comments1.xml", "xl/drawings/vmlDrawing1.vml"):
                check(part in names, f"{name}: has {part}")
        c2 = next(c for c in found["Sheet1"] if c["cell"] == "C2")
        eq((c2["kind"], c2["author"], c2["text"], len(c2["replies"])), ("THREAD", "Li Na", "Please check this price", 1), f"{name}: the thread on C2")
        eq(c2["replies"][0]["text"], "Checked: it is right", f"{name}: its reply")


def main():
    if not os.path.isdir(DIR):
        print("no", DIR, "- run ./gradlew :core:test first")
        return 2
    for n in EXPECTED_FILES:
        verify(n)
    for n in OPTIONAL_FILES:
        verify(n, optional=True)
    unknown = sorted(f for f in os.listdir(DIR) if f.endswith(".xlsx") and f not in EXPECTED_FILES + OPTIONAL_FILES)
    eq(unknown, [], "every file the tests wrote is checked here")
    print(f"\nchecks: {CHECKS}   passed: {CHECKS - len(FAILURES)}   failed: {len(FAILURES)}")
    for f in FAILURES:
        print("  FAIL", f)
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
