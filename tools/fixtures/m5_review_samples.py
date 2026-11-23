#!/usr/bin/env python3
"""M5 (v0.7.0) sample files for the emulator self-test and QA screenshots: files as a colleague would send them.

  review-sample.docx   6 paragraphs; 3 comments (one with a reply) by two authors; tracked insertions / deletions by
                       three authors and one tracked format change (listed, view only)
  review-format.docx   paragraph A bold + red + 16 pt, paragraph B plain (format painter, d24)
  review-sample.xlsx   a small table with two old-style notes (written by openpyxl)
  review-sample.pptx   a title slide and three content slides (written by python-pptx), no comments yet

Usage: tools/.venv/bin/python tools/fixtures/m5_review_samples.py <out-dir>
The .docx parts are written by hand (python-docx has no tracked changes); python-docx must open the result.
"""
import io
import os
import sys
import zipfile

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
W14 = 'xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"'
W15 = 'xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'

CONTENT_TYPES = DECL + (
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
    '%s</Types>'
)
ROOT_RELS = DECL + (
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    '</Relationships>'
)
STYLES = DECL + (
    '<w:styles %s><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Noto Sans" w:hAnsi="Noto Sans" w:eastAsia="Noto Sans CJK SC"/>'
    '<w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>'
    '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
    '<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>'
    '</w:styles>'
) % W


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def run(t, rpr=''):
    return '<w:r>%s<w:t xml:space="preserve">%s</w:t></w:r>' % (rpr, esc(t))


def ins(i, author, date, t):
    return '<w:ins w:id="%d" w:author="%s" w:date="%s">%s</w:ins>' % (i, author, date, run(t))


def dele(i, author, date, t):
    return '<w:del w:id="%d" w:author="%s" w:date="%s"><w:r><w:delText xml:space="preserve">%s</w:delText></w:r></w:del>' % (i, author, date, esc(t))


def cstart(i):
    return '<w:commentRangeStart w:id="%d"/>' % i


def cend(i):
    return '<w:commentRangeEnd w:id="%d"/><w:r><w:commentReference w:id="%d"/></w:r>' % (i, i)


def write_zip(path, parts):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, text in parts:
            z.writestr(name, text.encode('utf-8'))


def review_docx(path, zh):
    a1, a2, a3 = ('李四', '王五', '赵六') if zh else ('Lee', 'Wang', 'Zhao')
    d1, d2, d3 = '2026-11-20T09:12:00Z', '2026-11-20T10:40:00Z', '2026-11-21T02:05:00Z'
    if zh:
        title = '头显采购合同（审阅稿）'
        p1 = [run('甲方向乙方采购'), dele(101, a1, d1, '十'), ins(102, a1, d1, '十二'), run('台头显，用于门店演示。')]
        p2 = [run('乙方应在合同签订后'), dele(103, a1, d1, '30'), ins(104, a1, d1, '20'), run('个工作日内交付，'), ins(105, a2, d2, '送货上门并'), run('完成安装调试。')]
        p3 = [cstart(1), run('付款方式为一次性付清'), cend(1), run('，发票随货。'), dele(106, a3, d3, '逾期按日万分之五计息。')]
        p4 = [run('保修期为两年，'), cstart(2), run('电池不在保修范围内'), cend(2), cstart(3), cend(3), run('。')]
        p5 = [run('西南区的三家门店'), cstart(4), run('由乙方直接配送'), cend(4), run('，其余门店到总仓自提。')]
        p6 = [run('本合同一式两份，双方各执一份。', '<w:rPr><w:b/><w:rPrChange w:id="107" w:author="%s" w:date="%s"><w:rPr/></w:rPrChange></w:rPr>' % (a3, d3))]
        c = [(1, a1, d1, '分两次付行不行？首付 60 %。', '0000A001', None), (2, a2, d2, '电池至少保一年吧。', '0000A002', None),
             (3, a1, d1, '同意，我去和供应商确认。', '0000A003', '0000A002'), (4, a2, d2, '西南要不要单独列一条？', '0000A004', None)]
    else:
        title = 'Headset purchase contract (review copy)'
        p1 = [run('The buyer orders '), dele(101, a1, d1, 'ten'), ins(102, a1, d1, 'twelve'), run(' headsets for in-store demos.')]
        p2 = [run('The seller delivers within '), dele(103, a1, d1, '30'), ins(104, a1, d1, '20'), run(' working days, '), ins(105, a2, d2, 'ships to the door and '), run('sets everything up.')]
        p3 = [cstart(1), run('Payment is made in one invoice'), cend(1), run(', sent with the goods.'), dele(106, a3, d3, ' Late payment bears 0.05 % daily interest.')]
        p4 = [run('The warranty is two years; '), cstart(2), run('batteries are not covered'), cend(2), cstart(3), cend(3), run('.')]
        p5 = [run('The three stores in the south-west '), cstart(4), run('get direct delivery'), cend(4), run('; the others collect at the main warehouse.')]
        p6 = [run('Two copies of this contract are signed, one for each side.', '<w:rPr><w:b/><w:rPrChange w:id="107" w:author="%s" w:date="%s"><w:rPr/></w:rPrChange></w:rPr>' % (a3, d3))]
        c = [(1, a1, d1, 'Could we split it in two? 60 % up front.', '0000A001', None), (2, a2, d2, 'Batteries should have a year at least.', '0000A002', None),
             (3, a1, d1, "Agreed. I'll check with the supplier.", '0000A003', '0000A002'), (4, a2, d2, 'Should the south-west get its own clause?', '0000A004', None)]
    body = '<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr>%s</w:p>' % run(title)
    for p in (p1, p2, p3, p4, p5, p6):
        body += '<w:p>%s</w:p>' % ''.join(p)
    body += '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
    document = DECL + '<w:document %s %s><w:body>%s</w:body></w:document>' % (W, R, body)
    comments = DECL + '<w:comments %s %s>%s</w:comments>' % (W, W14, ''.join(
        '<w:comment w:id="%d" w:author="%s" w:date="%s" w:initials="%s"><w:p w14:paraId="%s"><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p></w:comment>'
        % (i, a, d, a[:1], pid, esc(t)) for (i, a, d, t, pid, _) in c))
    extended = DECL + '<w15:commentsEx %s>%s</w15:commentsEx>' % (W15, ''.join(
        '<w15:commentEx w15:paraId="%s"%s w15:done="0"/>' % (pid, (' w15:paraIdParent="%s"' % parent) if parent else '') for (_, _, _, _, pid, parent) in c))
    rels = DECL + (
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.microsoft.com/office/2011/relationships/commentsExtended" Target="commentsExtended.xml"/>'
        '</Relationships>')
    types = CONTENT_TYPES % (
        '<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>'
        '<Override PartName="/word/commentsExtended.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml"/>')
    write_zip(path, [('[Content_Types].xml', types), ('_rels/.rels', ROOT_RELS), ('word/document.xml', document), ('word/styles.xml', STYLES),
                     ('word/comments.xml', comments), ('word/commentsExtended.xml', extended), ('word/_rels/document.xml.rels', rels)])


def format_docx(path):
    rpr = '<w:rPr><w:b/><w:color w:val="C00000"/><w:sz w:val="32"/></w:rPr>'
    body = ('<w:p>%s</w:p><w:p>%s</w:p><w:p>%s</w:p>' % (
        run('A: bold, red, 16 pt. 这一段加粗、红色、16 磅。', rpr), run('B: plain text to be painted. 这一段是普通正文，等着被刷。'), run('C: stays as it is. 这一段不动。')))
    body += '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
    document = DECL + '<w:document %s %s><w:body>%s</w:body></w:document>' % (W, R, body)
    rels = DECL + ('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
    write_zip(path, [('[Content_Types].xml', CONTENT_TYPES % ''), ('_rels/.rels', ROOT_RELS), ('word/document.xml', document), ('word/styles.xml', STYLES),
                     ('word/_rels/document.xml.rels', rels)])


def sample_xlsx(path, zh):
    from openpyxl import Workbook
    from openpyxl.comments import Comment
    wb = Workbook()
    ws = wb.active
    ws.title = '销售' if zh else 'Sales'
    rows = [('区域', '负责人', '一季度', '二季度', '合计')] if zh else [('Region', 'Owner', 'Q1', 'Q2', 'Total')]
    names = [('华东', '张三'), ('华南', '李四'), ('西南', '王五'), ('华北', '赵六'), ('东北', '钱七')] if zh else [('East', 'Kai'), ('South', 'Lee'), ('South-west', 'Wang'), ('North', 'Zhao'), ('North-east', 'Chen')]
    nums = [(1200, 1350), (980, 1100), (640, 720), (1500, 1420), (410, 530)]
    for (a, b), (q1, q2) in zip(names, nums):
        rows.append((a, b, q1, q2, None))
    for r in rows:
        ws.append(r)
    for i in range(2, 7):
        ws['E%d' % i] = '=C%d+D%d' % (i, i)
    ws['B3'].comment = Comment('上个月刚接手这个区。' if zh else 'Took this region over last month.', '李四' if zh else 'Lee')
    ws['D5'].comment = Comment('含一笔退货，待核。' if zh else 'Includes a return, to be checked.', '王五' if zh else 'Wang')
    wb.save(path)


def sample_pptx(path, zh):
    from pptx import Presentation
    prs = Presentation()
    s = prs.slides.add_slide(prs.slide_layouts[0])
    s.shapes.title.text = '季度复盘' if zh else 'Quarterly review'
    s.placeholders[1].text = '门店业务 · 2026 年三季度' if zh else 'Stores · Q3 2026'
    items = [('三个数字', ['营收增长 12 %', '新开门店 3 家', '退货率降到 2.1 %']), ('做得好的', ['头显演示带来的进店量', '西南区的直接配送']), ('下一步', ['十二台头显到货后铺到门店', '付款条款和供应商再谈一次'])] if zh else \
        [('Three numbers', ['Revenue up 12 %', '3 new stores', 'Returns down to 2.1 %']), ('What went well', ['Footfall from the headset demos', 'Direct delivery in the south-west']), ('Next', ['Roll the twelve headsets out to the stores', 'Talk payment terms with the supplier again'])]
    for title, lines in items:
        s = prs.slides.add_slide(prs.slide_layouts[1])
        s.shapes.title.text = title
        tf = s.placeholders[1].text_frame
        tf.text = lines[0]
        for line in lines[1:]:
            tf.add_paragraph().text = line
    prs.save(path)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(out, exist_ok=True)
    for zh in (True, False):
        tag = 'zh' if zh else 'en'
        review_docx(os.path.join(out, 'review-sample-%s.docx' % tag), zh)
        sample_xlsx(os.path.join(out, 'review-sample-%s.xlsx' % tag), zh)
        sample_pptx(os.path.join(out, 'review-sample-%s.pptx' % tag), zh)
    format_docx(os.path.join(out, 'review-format.docx'))
    # python-docx must open what was written by hand
    from docx import Document
    for n in ('review-sample-zh.docx', 'review-sample-en.docx', 'review-format.docx'):
        d = Document(os.path.join(out, n))
        print(n, 'paragraphs', len(d.paragraphs))
    print('written to', out)


if __name__ == '__main__':
    main()
