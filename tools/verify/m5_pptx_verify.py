#!/usr/bin/env python3
"""Independent check of the decks the :core M5 pptx tests save (comments on slides, slide number and footer).

Run the tests first (they write core/build/m5-pptx/), then from the project root:
    ./gradlew :core:test --tests 'io.embodit.spatialoffice.core.pptx.PptxM5Test'
    tools/.venv/bin/python tools/verify/m5_pptx_verify.py

Nothing of :core is used here: every file is opened with python-pptx, every XML part is parsed with lxml, and the
package is walked with zipfile: relationship targets, content types, element order, comment and author ids.
Exit code 0 = all checks passed.
"""
import os
import posixpath
import re
import sys
import zipfile

from lxml import etree
from pptx import Presentation
from pptx.enum.shapes import PP_PLACEHOLDER

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "build", "m5-pptx")
FIXTURE = os.path.join(ROOT, "core", "src", "test", "resources", "fixtures", "pptx-deck.pptx")

P = "http://schemas.openxmlformats.org/presentationml/2006/main"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
P188 = "http://schemas.microsoft.com/office/powerpoint/2018/8/main"
REL_THREADS = "http://schemas.microsoft.com/office/2018/10/relationships/comments"
REL_AUTHORS = "http://schemas.microsoft.com/office/2018/10/relationships/authors"
REL_OLD = R + "/comments"
REL_OLD_AUTHORS = R + "/commentAuthors"
CT_THREADS = "application/vnd.ms-powerpoint.comments+xml"
CT_AUTHORS = "application/vnd.ms-powerpoint.authors+xml"
CT_OLD = "application/vnd.openxmlformats-officedocument.presentationml.comments+xml"
EXT_COMMENT_REL = "{6950BFC3-D8DA-4A85-94F7-54DA5524770B}"
GUID = re.compile(r"^\{[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\}$")
DATETIME = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d\d:\d\d)?$")

checks = 0
failures = []


def check(cond, what):
    global checks
    checks += 1
    if not cond:
        failures.append(what)
        print("  FAIL:", what)
    return cond


def eq(actual, expected, what):
    check(actual == expected, "%s: expected %r, got %r" % (what, expected, actual))


def read(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        check(len(names) == len(set(names)), "duplicate zip entries")
        return {n: z.read(n) for n in names}


def rels_of(parts, owner):
    """[(id, type, resolved target, external)] of a part ("" = the package)."""
    d, b = posixpath.split(owner)
    name = posixpath.join(d, "_rels", b + ".rels")
    if name not in parts:
        return []
    out = []
    for r in etree.fromstring(parts[name]):
        ext = r.get("TargetMode") == "External"
        t = r.get("Target")
        if not ext:
            t = t.lstrip("/") if t.startswith("/") else posixpath.normpath(posixpath.join(d, t))
        out.append((r.get("Id"), r.get("Type"), t, ext))
    return out


def local(e):
    return etree.QName(e).localname


def body_text(tb):
    return "\n".join("".join(t.text or "" for t in p.iter("{%s}t" % A)) for p in tb.findall("{%s}p" % A))


def package_checks(name, parts):
    """What must hold for any saved deck. Returns {slide part: [thread parts]}, the threads and the old comments."""
    xml = {}
    for n, data in parts.items():
        if n.endswith(".xml") or n.endswith(".rels"):
            try:
                xml[n] = etree.fromstring(data)
            except etree.XMLSyntaxError as ex:
                check(False, "%s: %s is not well-formed: %s" % (name, n, ex))
    # content types cover every part, and name no part that is not there
    types = xml["[Content_Types].xml"]
    defaults = {d.get("Extension").lower(): d.get("ContentType") for d in types.findall("{%s}Default" % CT)}
    overrides = {}
    for o in types.findall("{%s}Override" % CT):
        pn = o.get("PartName").lstrip("/")
        check(pn not in overrides, "%s: content type listed twice for %s" % (name, pn))
        overrides[pn] = o.get("ContentType")
        check(pn in parts, "%s: content type for a missing part %s" % (name, pn))
    for n in parts:
        if n != "[Content_Types].xml":
            check(n in overrides or n.rsplit(".", 1)[-1].lower() in defaults, "%s: no content type for %s" % (name, n))
    # every relationship target exists, ids unique per relationships part
    for n in parts:
        if not n.endswith(".rels"):
            continue
        d, b = posixpath.split(n)
        owner = posixpath.join(posixpath.dirname(d), b[:-5]) if b != ".rels" else ""
        if owner:
            check(owner in parts, "%s: %s has no owner part" % (name, n))
        rs = rels_of(parts, owner)
        eq(len({r[0] for r in rs}), len(rs), "%s: relationship ids of %s unique" % (name, n))
        for rid, _, target, ext in rs:
            if not ext:
                check(target in parts, "%s: %s %s points at missing %s" % (name, n, rid, target))

    pres = "ppt/presentation.xml"
    pres_rels = rels_of(parts, pres)
    by_id = {r[0]: r for r in pres_rels}
    slides = []  # (part, sldId)
    for s in xml[pres].find("{%s}sldIdLst" % P):
        slides.append((by_id[s.get("{%s}id" % R)][2], s.get("id")))
    eq(len({s[0] for s in slides}), len(slides), "%s: each slide part listed once" % name)

    authors = {}
    author_parts = [r[2] for r in pres_rels if r[1] == REL_AUTHORS]
    check(len(author_parts) <= 1, "%s: at most one authors part" % name)
    for ap in author_parts:
        eq(overrides.get(ap), CT_AUTHORS, "%s: content type of %s" % (name, ap))
        root = xml[ap]
        eq(root.tag, "{%s}authorLst" % P188, "%s: root of %s" % (name, ap))
        for a in root:
            check(local(a) in ("author", "extLst"), "%s: child %s of the author list" % (name, local(a)))
            if local(a) != "author":
                continue
            check(bool(GUID.match(a.get("id", ""))), "%s: author id %r is a GUID" % (name, a.get("id")))
            check(a.get("id") not in authors, "%s: author id %s listed once" % (name, a.get("id")))
            check(a.get("name") is not None and a.get("userId") is not None and a.get("providerId") is not None,
                  "%s: author %s has name, userId, providerId" % (name, a.get("id")))
            authors[a.get("id")] = a.get("name")
        names = list(authors.values())
        eq(len(set(names)), len(names), "%s: no author listed twice by name" % name)
    old_authors = {}
    for r in pres_rels:
        if r[1] == REL_OLD_AUTHORS:
            for a in xml[r[2]]:
                old_authors[a.get("id")] = a.get("name")

    seen_parts = {}
    ids = set()
    threads = []  # dicts
    olds = []
    by_slide = {}
    for index, (sp, sld_id) in enumerate(slides):
        root = xml[sp]
        # element order of a slide and of its shape tree
        order = ["cSld", "clrMapOvr", "transition", "timing", "extLst"]
        kids = [local(c) for c in root if isinstance(c.tag, str) and local(c) != "AlternateContent"]
        eq(kids, sorted(kids, key=order.index), "%s: child order of %s" % (name, sp))
        tree = root.find("{%s}cSld/{%s}spTree" % (P, P))
        tk = [local(c) for c in tree]
        eq(tk[:2], ["nvGrpSpPr", "grpSpPr"], "%s: %s shape tree starts with its own properties" % (name, sp))
        check("extLst" not in tk[:-1], "%s: %s shape tree extension list is last" % (name, sp))
        shape_ids = [c.get("id") for c in tree.iter("{%s}cNvPr" % P)]
        eq(len(set(shape_ids)), len(shape_ids), "%s: shape ids of %s unique" % (name, sp))
        ph_idx = [ph.get("idx", "0") for ph in tree.iter("{%s}ph" % P)]
        eq(len(set(ph_idx)), len(ph_idx), "%s: placeholder idx of %s unique" % (name, sp))

        rs = rels_of(parts, sp)
        named = {}  # r:id of each commentRel in the slide's extension list
        for ext in root.findall("{%s}extLst/{%s}ext" % (P, P)):
            for c in ext:
                if c.tag == "{%s}commentRel" % P188:
                    eq(ext.get("uri"), EXT_COMMENT_REL, "%s: uri of the commentRel extension of %s" % (name, sp))
                    named[c.get("{%s}id" % R)] = True
        thread_rels = [r for r in rs if r[1] == REL_THREADS]
        check(len(thread_rels) <= 1, "%s: %s has at most one threaded-comment part" % (name, sp))
        eq(sorted(named), sorted(r[0] for r in thread_rels), "%s: commentRel entries of %s match its relationships" % (name, sp))
        by_slide[sp] = [r[2] for r in thread_rels]
        for rid, rtype, target, _ in rs:
            if rtype not in (REL_THREADS, REL_OLD):
                continue
            check(target not in seen_parts, "%s: comment part %s belongs to one slide (%s and %s)" % (name, target, seen_parts.get(target), sp))
            seen_parts[target] = sp
            check(target.startswith("ppt/comments/"), "%s: comment part %s is under ppt/comments/" % (name, target))
            croot = xml[target]
            if rtype == REL_THREADS:
                eq(overrides.get(target), CT_THREADS, "%s: content type of %s" % (name, target))
                eq(croot.tag, "{%s}cmLst" % P188, "%s: root of %s" % (name, target))
                for cm in croot:
                    eq(cm.tag, "{%s}cm" % P188, "%s: child of the comment list in %s" % (name, target))
                    kids = [local(c) for c in cm]
                    # schema order: one anchor, then pos?, replyLst?, txBody?, extLst?
                    check(len(kids) >= 1 and kids[0] in ("sldMkLst", "deMkLst", "txMkLst", "unknownAnchor"),
                          "%s: comment %s starts with its anchor (%s)" % (name, cm.get("id"), kids))
                    rest = kids[1:]
                    want = ["pos", "replyLst", "txBody", "extLst"]
                    check(all(k in want for k in rest) and rest == sorted(rest, key=want.index) and len(set(rest)) == len(rest),
                          "%s: child order of comment %s (%s)" % (name, cm.get("id"), kids))
                    mk = cm.find(".//{http://schemas.microsoft.com/office/powerpoint/2013/main/command}sldMk")
                    check(mk is not None and mk.get("sldId") == sld_id, "%s: comment %s names its slide (%s)" % (name, cm.get("id"), sld_id))
                    pos = cm.find("{%s}pos" % P188)
                    if pos is not None:
                        check(pos.get("x", "").lstrip("-").isdigit() and pos.get("y", "").lstrip("-").isdigit(), "%s: position of %s is whole EMU" % (name, cm.get("id")))
                    items = [cm] + list(cm.findall("{%s}replyLst/{%s}reply" % (P188, P188)))
                    for it in items:
                        iid = it.get("id")
                        check(bool(GUID.match(iid or "")), "%s: comment id %r is a GUID" % (name, iid))
                        check(iid not in ids, "%s: comment id %s used once" % (name, iid))
                        ids.add(iid)
                        check(it.get("authorId") in authors, "%s: author %s of %s is in the authors part" % (name, it.get("authorId"), iid))
                        check(bool(DATETIME.match(it.get("created", ""))), "%s: created %r of %s is a date-time" % (name, it.get("created"), iid))
                        check(it.get("status") in (None, "active", "resolved", "closed"), "%s: status of %s" % (name, iid))
                        tb = it.find("{%s}txBody" % P188)
                        if check(tb is not None, "%s: %s has a text body" % (name, iid)):
                            tk2 = [local(c) for c in tb]
                            check(tk2[0] == "bodyPr" and "p" in tk2, "%s: text body of %s has bodyPr and a paragraph (%s)" % (name, iid, tk2))
                    for rp in cm.findall("{%s}replyLst/{%s}reply" % (P188, P188)):
                        eq([local(c) for c in rp if local(c) != "extLst"], ["txBody"], "%s: children of reply %s" % (name, rp.get("id")))
                    threads.append({
                        "slide": index, "part": target, "id": cm.get("id"), "author": authors.get(cm.get("authorId")),
                        "text": body_text(cm.find("{%s}txBody" % P188)) if cm.find("{%s}txBody" % P188) is not None else "",
                        "resolved": cm.get("status") == "resolved",
                        "replies": [(authors.get(r.get("authorId")), body_text(r.find("{%s}txBody" % P188))) for r in items[1:]],
                    })
            else:
                eq(overrides.get(target), CT_OLD, "%s: content type of %s" % (name, target))
                eq(croot.tag, "{%s}cmLst" % P, "%s: root of %s" % (name, target))
                for cm in croot:
                    eq(cm.tag, "{%s}cm" % P, "%s: child of the comment list in %s" % (name, target))
                    check(cm.get("authorId") in old_authors, "%s: old-style author %s is listed" % (name, cm.get("authorId")))
                    eq([local(c) for c in cm][:2], ["pos", "text"], "%s: children of an old-style comment in %s" % (name, target))
                    olds.append({"slide": index, "part": target, "author": old_authors.get(cm.get("authorId")), "idx": cm.get("idx"), "text": cm.findtext("{%s}text" % P)})
    # no comment part is left without a slide
    for n in parts:
        if n.startswith("ppt/comments/") and n.endswith(".xml"):
            check(n in seen_parts, "%s: comment part %s is referenced by a slide" % (name, n))
    return slides, by_slide, threads, olds


def placeholder_types(slide):
    return [ph.placeholder_format.type for ph in slide.placeholders]


def main():
    if not os.path.isdir(OUT):
        print("no %s: run ./gradlew :core:test --tests '*PptxM5Test*' first" % OUT)
        return 2
    files = sorted(f for f in os.listdir(OUT) if f.endswith(".pptx"))
    expected = [
        "acceptance-comments-before.pptx", "acceptance-comments.pptx", "acceptance-footer.pptx", "comments-all-deleted.pptx",
        "comments-both-kinds.pptx", "comments-fresh.pptx", "comments-new-deck.pptx", "comments-slide-deleted.pptx",
        "comments-slide-ops.pptx", "comments-threads-edited.pptx", "comments-threads-untouched.pptx", "footer-and-comments.pptx",
        "footer-fallback.pptx",
    ]
    for f in expected:
        check(f in files, "missing %s" % f)
    info = {}
    for f in files:
        print(f)
        path = os.path.join(OUT, f)
        parts = read(path)
        info[f] = (parts,) + package_checks(f, parts)
        try:
            prs = Presentation(path)
            n = 0
            for s in prs.slides:
                for sh in s.shapes:
                    n += 1
                    if sh.has_text_frame:
                        _ = sh.text_frame.text
                _ = s.slide_layout.name
            check(n >= 0 and len(prs.slides) == len(info[f][1]), "%s: python-pptx sees every slide" % f)
        except Exception as ex:  # noqa: BLE001 - any failure to open is the finding
            check(False, "%s: python-pptx cannot open it: %r" % (f, ex))

    fixture = read(FIXTURE)

    # ---- p33 acceptance: a comment and a reply on slide 2, beside the old-style comments that were there
    if "acceptance-comments.pptx" in info and "acceptance-comments-before.pptx" in info:
        parts, slides, by_slide, threads, olds = info["acceptance-comments.pptx"]
        before = info["acceptance-comments-before.pptx"][0]
        eq(len(threads), 1, "acceptance: one thread")
        if threads:
            t = threads[0]
            eq(t["slide"], 1, "acceptance: the thread is on slide 2")
            check(t["part"].startswith("ppt/comments/modernComment_"), "acceptance: comment part name %s" % t["part"])
            eq(t["text"], "请核对第二季度的数据", "acceptance: comment text")
            eq(t["author"], "王伟", "acceptance: comment author")
            eq(t["replies"], [("Li Na", "Done, see the new chart")], "acceptance: the reply")
            eq(by_slide[slides[1][0]], [t["part"]], "acceptance: slide 2's relationships name the comment part")
            eq(sum(len(v) for v in by_slide.values()), 1, "acceptance: no other slide has a thread part")
        eq([(o["slide"], o["author"], o["text"]) for o in olds], [(1, "Li Na", "Check the totals & units"), (1, "陈晨", "图表需要更新")], "acceptance: old-style comments still read")
        for n in ("ppt/comments/comment1.xml", "ppt/commentAuthors.xml"):
            check(parts.get(n) == before.get(n) and n in parts, "acceptance: %s is byte-identical" % n)
        changed = sorted(n for n in set(parts) | set(before) if parts.get(n) != before.get(n))
        eq(changed, sorted(["[Content_Types].xml", "ppt/_rels/presentation.xml.rels", "ppt/authors.xml", threads[0]["part"] if threads else "?",
                            "ppt/slides/_rels/slide2.xml.rels", "ppt/slides/slide2.xml"]), "acceptance: parts that differ from the file before")

    # ---- p32 acceptance: number + footer on every slide but the title slide
    if "acceptance-footer.pptx" in info:
        parts = info["acceptance-footer.pptx"][0]
        prs = Presentation(os.path.join(OUT, "acceptance-footer.pptx"))
        titles = 0
        for i, s in enumerate(prs.slides):
            types = placeholder_types(s)
            if s.slide_layout._element.get("type") == "title":
                titles += 1
                check(PP_PLACEHOLDER.SLIDE_NUMBER not in types and PP_PLACEHOLDER.FOOTER not in types, "footer: title slide %d has neither" % (i + 1))
                eq(parts[s.part.partname.lstrip("/")], fixture[s.part.partname.lstrip("/")], "footer: title slide %d is byte-identical" % (i + 1))
                continue
            check(PP_PLACEHOLDER.SLIDE_NUMBER in types, "footer: slide %d has a slide-number placeholder (%s)" % (i + 1, types))
            check(PP_PLACEHOLDER.FOOTER in types, "footer: slide %d has a footer placeholder (%s)" % (i + 1, types))
            ftr = [ph for ph in s.placeholders if ph.placeholder_format.type == PP_PLACEHOLDER.FOOTER]
            num = [ph for ph in s.placeholders if ph.placeholder_format.type == PP_PLACEHOLDER.SLIDE_NUMBER]
            eq([p.text_frame.text for p in ftr], ["季度复盘"], "footer: text on slide %d" % (i + 1))
            eq(len(num), 1, "footer: one number on slide %d" % (i + 1))
            if num and ftr:
                flds = num[0]._element.findall(".//{%s}fld" % A)
                eq([f.get("type") for f in flds], ["slidenum"], "footer: slide %d number is a slide-number field" % (i + 1))
                # the layout has the same placeholders, with the same idx: the slide's inherit their boxes
                lay = {ph.placeholder_format.type: ph.placeholder_format.idx for ph in s.slide_layout.placeholders}
                eq(num[0].placeholder_format.idx, lay.get(PP_PLACEHOLDER.SLIDE_NUMBER), "footer: slide %d number idx is the layout's" % (i + 1))
                eq(ftr[0].placeholder_format.idx, lay.get(PP_PLACEHOLDER.FOOTER), "footer: slide %d footer idx is the layout's" % (i + 1))
                check(num[0].width > 0 and num[0].top > prs.slide_height * 3 // 4, "footer: slide %d number sits in the bottom row" % (i + 1))
                check(ftr[0].width > 0 and ftr[0].top > prs.slide_height * 3 // 4, "footer: slide %d footer sits in the bottom row" % (i + 1))
        eq(titles, 1, "footer: one slide is on the title layout")
        other = sorted(n for n in set(parts) | set(fixture) if parts.get(n) != fixture.get(n))
        check(all(re.match(r"ppt/slides/slide\d+\.xml$", n) for n in other), "footer: only slide parts differ from the fixture (%s)" % other)
        eq(len(other), len(prs.slides) - titles, "footer: every other slide part changed")

    # ---- fallback boxes on a deck whose layouts have no footer placeholders
    if "footer-fallback.pptx" in info:
        prs = Presentation(os.path.join(OUT, "footer-fallback.pptx"))
        for i, s in enumerate(prs.slides):
            types = placeholder_types(s)
            if i == 0:
                check(PP_PLACEHOLDER.SLIDE_NUMBER not in types and PP_PLACEHOLDER.FOOTER not in types, "fallback: the title slide has neither")
                continue
            for kind in (PP_PLACEHOLDER.SLIDE_NUMBER, PP_PLACEHOLDER.FOOTER):
                ph = [p for p in s.placeholders if p.placeholder_format.type == kind]
                if check(len(ph) == 1, "fallback: slide %d has one %s" % (i + 1, kind)):
                    p = ph[0]
                    check(p.left >= 0 and p.top > prs.slide_height * 3 // 4 and p.left + p.width <= prs.slide_width and p.top + p.height <= prs.slide_height,
                          "fallback: slide %d %s box is in the bottom row inside the slide" % (i + 1, kind))
            f = [p for p in s.placeholders if p.placeholder_format.type == PP_PLACEHOLDER.FOOTER]
            n = [p for p in s.placeholders if p.placeholder_format.type == PP_PLACEHOLDER.SLIDE_NUMBER]
            if f and n:
                eq(f[0].text_frame.text, "Quarterly review\n第二行", "fallback: footer text on slide %d" % (i + 1))
                check(f[0].left + f[0].width <= n[0].left, "fallback: slide %d footer and number do not overlap" % (i + 1))
                mid = f[0].left + f[0].width // 2
                check(abs(mid - prs.slide_width // 2) < prs.slide_width // 100, "fallback: slide %d footer is centred" % (i + 1))

    # ---- the other comment decks
    if "comments-threads-untouched.pptx" in info:
        threads = info["comments-threads-untouched.pptx"][3]
        eq([(t["author"], t["resolved"], len(t["replies"])) for t in threads], [("Zhang Wei", False, 2), ("刘洋", True, 0)], "threads: two authors, replies, one resolved")
    if "comments-threads-edited.pptx" in info:
        threads = info["comments-threads-edited.pptx"][3]
        eq([t["text"] for t in threads], ["Final picture? <yes>", "已处理"], "edited: texts")
        eq(threads[0]["replies"], [("刘洋", "同意"), ("Zhang Wei", "已修复")], "edited: replies of the first thread")
        eq(threads[1]["replies"], [("刘洋", "补充说明")], "edited: reply added to the resolved thread")
        check(threads[1]["resolved"], "edited: the second thread is still resolved")
    if "comments-all-deleted.pptx" in info:
        parts, slides, by_slide, threads, olds = info["comments-all-deleted.pptx"]
        eq((len(threads), len(olds)), (0, 0), "all deleted: no comments")
        check(not any(n.startswith("ppt/comments/") for n in parts), "all deleted: no comment part left")
        check(b"commentRel" not in b"".join(parts[s[0]] for s in slides), "all deleted: no slide names a comment part")
        eq(parts["ppt/slides/slide3.xml"], fixture["ppt/slides/slide3.xml"], "all deleted: the slide is the fixture's again")
    if "comments-both-kinds.pptx" in info:
        parts, slides, by_slide, threads, olds = info["comments-both-kinds.pptx"]
        eq((len([t for t in threads if t["slide"] == 2]), len([o for o in olds if o["slide"] == 2])), (3, 1), "both kinds: slide 3 has threads and an old-style comment")
        eq(len(olds), 2, "both kinds: two old-style comments in the deck")
    if "comments-slide-ops.pptx" in info:
        parts, slides, by_slide, threads, olds = info["comments-slide-ops.pptx"]
        eq(len(slides), 8, "slide ops: eight slides after the copy")
        eq([len(by_slide[s[0]]) for s in slides[:2]], [1, 1], "slide ops: the moved slide and its copy have a comment part each")
        check(by_slide[slides[0][0]] != by_slide[slides[1][0]], "slide ops: not the same part")
        eq([t["slide"] for t in threads], [0, 0, 1], "slide ops: two threads on the original, one on the copy")
        eq([o["slide"] for o in olds], [0], "slide ops: the old-style comment stayed with the original")
    if "comments-slide-deleted.pptx" in info:
        parts, slides, by_slide, threads, olds = info["comments-slide-deleted.pptx"]
        eq((len(slides), [t["slide"] for t in threads], len(olds)), (7, [0], 0), "slide deleted: only the copy's comment is left")
        eq(sorted(n for n in parts if n.startswith("ppt/comments/")), by_slide[slides[0][0]], "slide deleted: one comment part left")
    if "footer-and-comments.pptx" in info:
        prs = Presentation(os.path.join(OUT, "footer-and-comments.pptx"))
        eq(len(info["footer-and-comments.pptx"][3]), 3, "footer + comments: three threads")
        check(all(PP_PLACEHOLDER.FOOTER in placeholder_types(s) for s in list(prs.slides)[1:]), "footer + comments: footers on every slide after the title")

    print()
    print("%d checks, %d failed" % (checks, len(failures)))
    for f in failures:
        print("  -", f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
