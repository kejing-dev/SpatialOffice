#!/usr/bin/env python3
"""Independent check of the files the M5 document tests save (comments, tracked changes, header / footer, format painter).

Run the tests first (DocxM5Test writes core/build/m5-docx/), then from the project root:
    tools/.venv/bin/python tools/verify/m5_docx_verify.py [directory]

Nothing here uses :core. Every file is opened with python-docx, every XML part is parsed with lxml, and for every file:
  - content types and relationships resolve, relationship ids are unique;
  - comment ids are unique, every anchor in the text names an existing comment, every commented range has its start
    before its end and exactly one of each, every reference appears once;
  - the extended / ids / extensible comment parts only name paragraphs and ids that exist;
  - tracked-change ids are unique, deleted text is `w:delText` inside `w:del` and nowhere else;
  - `w:pPr` is the first child of its paragraph, header / footer references come first in `w:sectPr` and resolve.
On top of that the acceptance criteria of the milestone are asserted on the files made for them.
Exit code 0 = all checks passed.
"""
import os
import posixpath
import re
import sys
import zipfile

from docx import Document
from docx.shared import Pt, RGBColor
from lxml import etree

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "core", "build", "m5-docx")

NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
NS_W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
NS_W15 = "http://schemas.microsoft.com/office/word/2012/wordml"
NS_CID = "http://schemas.microsoft.com/office/word/2016/wordml/cid"
NS_CEX = "http://schemas.microsoft.com/office/word/2018/wordml/cex"
NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
W = "{%s}" % NS_W
R = "{%s}" % NS_R
W14 = "{%s}" % NS_W14
W15 = "{%s}" % NS_W15
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
CT = "application/vnd.openxmlformats-officedocument.wordprocessingml."
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})?$")
REVISION = ("ins", "del", "moveFrom", "moveTo", "rPrChange", "pPrChange", "sectPrChange", "tblPrChange", "tcPrChange", "trPrChange", "numberingChange")

checks = 0
failures = []
current = ""


def check(cond, what):
    global checks
    checks += 1
    if not cond:
        failures.append("%s: %s" % (current, what))
        print("  FAIL:", what)
    return cond


def eq(actual, expected, what):
    return check(actual == expected, "%s: expected %r, got %r" % (what, expected, actual))


class Pkg:
    def __init__(self, path):
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            check(len(names) == len(set(names)), "duplicate zip entries")
            self.parts = {n: z.read(n) for n in names}
        self.trees = {}
        for name, blob in self.parts.items():
            if name.endswith(".xml") or name.endswith(".rels"):
                try:
                    self.trees[name] = etree.fromstring(blob)
                except etree.XMLSyntaxError as e:
                    check(False, "%s is not well-formed XML: %s" % (name, e))

    def tree(self, name):
        return self.trees.get(name)

    def rels(self, owner):
        d, b = posixpath.split(owner)
        t = self.tree(posixpath.join(d, "_rels", b + ".rels"))
        out = []
        if t is not None:
            for r in t:
                target = r.get("Target")
                external = r.get("TargetMode") == "External"
                part = target if external else posixpath.normpath(posixpath.join(d, target)) if not target.startswith("/") else target.lstrip("/")
                out.append((r.get("Id"), r.get("Type"), part, external))
        return out

    def rel_part(self, owner, type_suffix):
        for _, t, part, ext in self.rels(owner):
            if not ext and t.endswith(type_suffix):
                return part
        return None


def package_checks(p):
    ct = p.tree("[Content_Types].xml")
    if not check(ct is not None, "no content types"):
        return
    defaults = {e.get("Extension").lower() for e in ct if e.tag == "{%s}Default" % NS_CT}
    overrides = {}
    for e in ct:
        if e.tag == "{%s}Override" % NS_CT:
            name = e.get("PartName").lstrip("/")
            check(name not in overrides, "content type listed twice: " + name)
            overrides[name] = e.get("ContentType")
            check(name in p.parts, "content type for a part that does not exist: " + name)
    for name in p.parts:
        if name == "[Content_Types].xml":
            continue
        check(name in overrides or name.rsplit(".", 1)[-1].lower() in defaults, "no content type for " + name)
        if name.endswith(".rels"):
            d, b = posixpath.split(name)
            owner = posixpath.join(posixpath.dirname(d), b[:-5]) if posixpath.dirname(d) or b != ".rels" else ""
            ids = [r.get("Id") for r in p.tree(name)] if p.tree(name) is not None else []
            check(len(ids) == len(set(ids)), "duplicate relationship ids in " + name)
            if owner:
                for rid, _, part, ext in p.rels(owner):
                    if not ext:
                        check(part in p.parts, "%s: %s points at missing part %s" % (name, rid, part))
    return overrides


_alive = []


def doc_order(root):
    """Position of every element in document order, by id(); the elements are kept alive so their ids stay theirs."""
    els = list(root.iter())
    _alive.append(els)
    return {id(e): i for i, e in enumerate(els)}


def comment_checks(p, overrides):
    doc = p.tree("word/document.xml")
    if doc is None:
        return
    order = doc_order(doc)
    starts = doc.findall(".//%scommentRangeStart" % W)
    ends = doc.findall(".//%scommentRangeEnd" % W)
    refs = doc.findall(".//%scommentReference" % W)
    cpart = p.rel_part("word/document.xml", "/comments")
    if cpart is None:
        check(not starts and not ends and not refs, "comment anchors in the text but no comments part")
        return
    eq(overrides.get(cpart), CT + "comments+xml", "content type of " + cpart)
    comments = p.tree(cpart)
    ids = [c.get(W + "id") for c in comments.findall(W + "comment")]
    check(len(ids) == len(set(ids)), "comment ids are not unique: %r" % ids)
    for c in comments.findall(W + "comment"):
        check(c.get(W + "author") is not None, "comment %s has no author" % c.get(W + "id"))
        check(len(c.findall(W + "p")) >= 1, "comment %s has no paragraph" % c.get(W + "id"))
    known = set(ids)
    sid = [e.get(W + "id") for e in starts]
    eid = [e.get(W + "id") for e in ends]
    rid = [e.get(W + "id") for e in refs]
    for name, lst in (("range starts", sid), ("range ends", eid), ("references", rid)):
        check(len(lst) == len(set(lst)), "comment %s appear more than once: %r" % (name, sorted(lst)))
        check(set(lst) <= known, "comment %s name comments that do not exist: %r" % (name, sorted(set(lst) - known)))
    eq(sorted(sid), sorted(eid), "commented ranges are paired")
    check(set(sid) <= set(rid), "a commented range without a reference: %r" % sorted(set(sid) - set(rid)))
    by = lambda lst: {e.get(W + "id"): order[id(e)] for e in lst}
    s, e, r = by(starts), by(ends), by(refs)
    for i in s:
        if i in e:
            check(s[i] < e[i], "comment %s: range start is not before its end" % i)
        if i in e and i in r:
            check(e[i] < r[i], "comment %s: the reference is not after the range end" % i)
    for ref in refs:
        check(ref.getparent().tag == W + "r", "a comment reference outside a run")
        anc = [a.tag for a in ref.iterancestors()]
        check(W + "del" not in anc, "a comment reference inside a tracked deletion")

    para_ids = {}
    for c in comments.findall(W + "comment"):
        for para in c.iter(W + "p"):
            if para.get(W14 + "paraId"):
                check(para.get(W14 + "paraId") not in para_ids, "paragraph id used twice in the comments part")
                para_ids[para.get(W14 + "paraId")] = c.get(W + "id")
    for pid in para_ids:
        check(re.fullmatch(r"[0-9A-F]{8}", pid) is not None and int(pid, 16) < 0x80000000, "paragraph id %s is not 8 hex digits below 80000000" % pid)
    xpart = p.rel_part("word/document.xml", "/commentsExtended")
    if xpart is not None:
        eq(overrides.get(xpart), CT + "commentsExtended+xml", "content type of " + xpart)
        seen = set()
        for ex in p.tree(xpart).findall(W15 + "commentEx"):
            pid = ex.get(W15 + "paraId")
            check(pid in para_ids, "extended part names paragraph %s, which no comment has" % pid)
            check(pid not in seen, "extended part lists paragraph %s twice" % pid)
            seen.add(pid)
            parent = ex.get(W15 + "paraIdParent")
            if parent is not None:
                check(parent in para_ids and parent != pid, "extended part: parent %s of %s does not exist" % (parent, pid))
            check(ex.get(W15 + "done") in (None, "0", "1"), "extended part: done is not 0 / 1")
    ipart = p.rel_part("word/document.xml", "/commentsIds")
    durable = set()
    if ipart is not None:
        for c in p.tree(ipart):
            check(c.get("{%s}paraId" % NS_CID) in para_ids, "ids part names a paragraph no comment has")
            d = c.get("{%s}durableId" % NS_CID)
            check(d not in durable, "durable id used twice")
            durable.add(d)
    epart = p.rel_part("word/document.xml", "/commentsExtensible")
    if epart is not None:
        for c in p.tree(epart):
            check(c.get("{%s}durableId" % NS_CEX) in durable, "extensible part names a durable id the ids part does not have")


def revision_checks(p):
    doc = p.tree("word/document.xml")
    if doc is None:
        return
    ids = [e.get(W + "id") for n in REVISION for e in doc.iter(W + n)]
    check(len(ids) == len(set(ids)), "tracked-change ids are not unique: %r" % sorted(ids))
    for e in list(doc.iter(W + "ins")) + list(doc.iter(W + "del")):
        if e.getparent().tag in (W + "rPr", W + "trPr", W + "numPr"):
            continue
        check(e.get(W + "id") is not None and e.get(W + "author") is not None, "a tracked change without id / author")
        if e.get(W + "date") is not None:
            check(ISO.match(e.get(W + "date")) is not None, "a tracked change with a date that is not ISO: %r" % e.get(W + "date"))
    for t in doc.iter(W + "delText"):
        check(any(a.tag == W + "del" for a in t.iterancestors()), "w:delText outside w:del")
    for d in doc.iter(W + "del"):
        if d.getparent().tag in (W + "rPr", W + "trPr", W + "numPr"):
            continue
        check(not [t for t in d.iter(W + "t")], "w:t inside w:del (must be w:delText)")
    for para in doc.iter(W + "p"):
        ppr = para.find(W + "pPr")
        if ppr is not None:
            check(para[0] is ppr, "w:pPr is not the first child of its paragraph")
    for run in doc.iter(W + "r"):
        rpr = run.find(W + "rPr")
        if rpr is not None:
            check(run[0] is rpr, "w:rPr is not the first child of its run")


def section_checks(p, overrides):
    doc = p.tree("word/document.xml")
    if doc is None:
        return
    rels = {rid: (t, part) for rid, t, part, ext in p.rels("word/document.xml") if not ext}
    body = doc.find(W + "body")
    for sp in doc.iter(W + "sectPr"):
        kids = list(sp)
        seen_other = False
        for k in kids:
            if k.tag in (W + "headerReference", W + "footerReference"):
                check(not seen_other, "a header / footer reference after other section properties")
                kind = "header" if k.tag == W + "headerReference" else "footer"
                rid = k.get(R + "id")
                if check(rid in rels, "%s reference %s has no relationship" % (kind, rid)):
                    eq(rels[rid][0], REL + "/" + kind, "relationship type of the %s" % kind)
                    check(rels[rid][1] in p.parts, "%s part %s is missing" % (kind, rels[rid][1]))
                    eq(overrides.get(rels[rid][1]), CT + kind + "+xml", "content type of " + rels[rid][1])
                    root = p.tree(rels[rid][1])
                    if root is not None:
                        eq(root.tag, W + ("hdr" if kind == "header" else "ftr"), "root element of " + rels[rid][1])
            else:
                seen_other = True
    if body is not None and len(body) and body.find(W + "sectPr") is not None:
        check(body[-1].tag == W + "sectPr", "the body-level w:sectPr is not the last child of the body")


def text_of(el):
    return "".join(t.text or "" for t in el.iter(W + "t"))


def body_paras(p):
    return p.tree("word/document.xml").find(W + "body").findall(W + "p")


# ---- acceptance criteria, on the files made for them ----

def acc_comments(path, p):
    d = Document(path)
    cs = list(d.comments)
    eq(len(cs), 3, "python-docx reads three comments")
    eq([c.author for c in cs], ["王小明", "Li Lei", "Li Lei"], "authors")
    eq([c.initials for c in cs], ["王", "LL", "LL"], "initials")
    eq(cs[0].text, "这个数字需要核对", "text of the first comment")
    eq(cs[2].text, "Whole paragraph.\nSecond line.", "text of the two-line comment")
    eq([c.timestamp.strftime("%Y-%m-%dT%H:%M:%S") for c in cs], ["2026-11-22T10:00:00", "2026-11-22T10:05:00", "2026-11-22T10:06:00"], "dates")
    comments = p.tree("word/comments.xml")
    pid = {c.get(W + "id"): c.findall(W + "p")[-1].get(W14 + "paraId") for c in comments.findall(W + "comment")}
    ex = {e.get(W15 + "paraId"): e for e in p.tree("word/commentsExtended.xml")}
    eq(ex[pid["1"]].get(W15 + "paraIdParent"), pid["0"], "the reply names its parent")
    eq(ex[pid["2"]].get(W15 + "done"), "1", "the resolved comment is done")
    eq(ex[pid["0"]].get(W15 + "done"), "0", "the open comment is not")
    doc = p.tree("word/document.xml")
    for i in ("0", "1", "2"):
        for n in ("commentRangeStart", "commentRangeEnd", "commentReference"):
            eq(len([e for e in doc.iter(W + n) if e.get(W + "id") == i]), 1, "%s of comment %s" % (n, i))
    paras = body_paras(p)
    order = doc_order(doc)
    s0 = [e for e in doc.iter(W + "commentRangeStart") if e.get(W + "id") == "0"][0]
    e0 = [e for e in doc.iter(W + "commentRangeEnd") if e.get(W + "id") == "0"][0]
    inside = "".join(t.text for t in paras[0].iter(W + "t") if order[id(s0)] < order[id(t)] < order[id(e0)])
    eq(inside, "quarter", "the commented text")
    eq(d.paragraphs[0].text, "The quarter closed ahead of plan.", "the paragraph still reads the same")


def acc_revisions(path, p):
    paras = body_paras(p)
    plain = lambda para: "".join(t.text for t in para.iter(W + "t") if not any(a.tag in (W + "ins", W + "del") for a in t.iterancestors()))
    check("slightly lower" in plain(paras[0]), "the accepted insertion is body text")
    eq(plain(paras[1]), "Sales really grew.", "the rejected deletion's text is body text again")
    eq(len(paras[1].findall(".//" + W + "del")), 0, "no deletion left in that paragraph")
    eq([e.get(W + "id") for e in paras[0].iter(W + "ins")], ["14"], "the other author's insertion is untouched")
    eq([e.get(W + "id") for e in paras[0].iter(W + "del")], ["11"], "the other deletion is untouched")
    eq(Document(path).paragraphs[1].text, "Sales really grew.", "python-docx reads the restored text")


def acc_tracked(path, p):
    para = body_paras(p)[1]
    dels = para.findall(W + "del")
    eq(len(dels), 1, "one tracked deletion in the second paragraph")
    eq(dels[0].get(W + "author"), "王小明", "its author")
    check(ISO.match(dels[0].get(W + "date") or "") is not None, "its date is ISO")
    eq("".join(t.text for t in dels[0].iter(W + "delText")), " here", "the deleted text")
    first = body_paras(p)[0]
    eq(["".join(t.text for t in e.iter(W + "t")) for e in first.findall(W + "ins")], ["slower", "LEAPT"], "the insertions")
    eq(["".join(t.text for t in e.iter(W + "delText")) for e in first.findall(W + "del")], ["quick", "jumps"], "the deletions")


def acc_header_footer(path, p):
    d = Document(path)
    sec = d.sections[-1]
    eq(sec.header.paragraphs[0].text, "内部 · 草稿", "python-docx reads the header")
    eq(sec.footer.paragraphs[0].text, "第三季度", "python-docx reads the footer")
    check(not sec.header.is_linked_to_previous and not sec.footer.is_linked_to_previous, "the section has its own header and footer")
    check("word/header1.xml" in p.parts and "word/footer1.xml" in p.parts, "header1.xml and footer1.xml exist")
    eq(text_of(p.tree("word/header1.xml")), "内部 · 草稿", "header1.xml text")
    eq(text_of(p.tree("word/footer1.xml")), "第三季度", "footer1.xml text")
    sp = p.tree("word/document.xml").find(W + "body").find(W + "sectPr")
    eq([k.tag.replace(W, "") for k in sp][:2], ["headerReference", "footerReference"], "the references come first in w:sectPr")
    eq([k.get(W + "type") for k in sp][:2], ["default", "default"], "both are the default ones")


def acc_painter(path, p):
    paras = body_paras(p)
    want = etree.tostring(paras[0].find(W + "r").find(W + "rPr"), method="c14n")
    runs = paras[1].findall(W + "r")
    check(len(runs) >= 1, "the painted paragraph has runs")
    for r in runs:
        rpr = r.find(W + "rPr")
        check(rpr is not None and etree.tostring(rpr, method="c14n") == want, "a run of the painted paragraph has another w:rPr than the source run")
    d = Document(path)
    for r in d.paragraphs[1].runs:
        check(r.bold is True, "python-docx: painted run is bold")
        eq(r.font.size, Pt(16), "python-docx: painted run is 16 pt")
        eq(r.font.color.rgb, RGBColor(0xFF, 0x00, 0x00), "python-docx: painted run is red")
    eq(d.paragraphs[1].text, "Target B has three runs", "the painted paragraph's text")
    eq(d.paragraphs[2].text, "Untouched", "the paragraph after it")


ACCEPTANCE = {
    "m5-comments.docx": acc_comments,
    "m5-revisions-accept-reject.docx": acc_revisions,
    "m5-tracked-typing.docx": acc_tracked,
    "m5-header-footer.docx": acc_header_footer,
    "m5-format-painter.docx": acc_painter,
}


def main():
    global current
    if not os.path.isdir(OUT):
        print("no files to check: %s does not exist (run the :core docx tests first)" % OUT)
        return 2
    files = sorted(f for f in os.listdir(OUT) if f.endswith(".docx"))
    missing = [f for f in ACCEPTANCE if f not in files]
    for f in files:
        current = f
        path = os.path.join(OUT, f)
        before = len(failures)
        p = Pkg(path)
        overrides = package_checks(p) or {}
        comment_checks(p, overrides)
        revision_checks(p)
        section_checks(p, overrides)
        try:
            d = Document(path)
            n_comments = len(list(d.comments))
            part = p.rel_part("word/document.xml", "/comments")
            eq(n_comments, len(p.tree(part).findall(W + "comment")) if part else 0, "python-docx reads as many comments as the part has")
            check(len(d.paragraphs) >= 1, "python-docx reads the paragraphs")
            for s in d.sections:
                _ = [x.text for x in s.header.paragraphs] + [x.text for x in s.footer.paragraphs]
        except Exception as e:  # noqa: BLE001 - any failure to open is a failed check
            check(False, "python-docx cannot open it: %r" % (e,))
        if f in ACCEPTANCE:
            ACCEPTANCE[f](path, p)
        print("%s %s" % ("ok  " if len(failures) == before else "FAIL", f))
    current = "(set)"
    check(not missing, "acceptance files missing: %r" % missing)
    print("\n%d files, %d checks, %d passed, %d failed" % (len(files), checks, checks - len(failures), len(failures)))
    for x in failures:
        print("  -", x)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
