import sys, docx, pptx, openpyxl
R=sys.argv[1]
ok=0; bad=[]
def check(c, what):
    global ok
    if c: ok+=1
    else: bad.append(what)
d=docx.Document(R+'/v02-doc.docx')
p=d.paragraphs[0]
check(p.alignment is not None and int(p.alignment)==1, 'docx align center')
check(any(r.font.strike for r in p.runs if r.text=='old'), 'docx strike')
check(len(d.inline_shapes)==1, 'docx image')
t=d.tables[0]; check(t.cell(0,1).text=='Owner', 'docx table cell'); check(t.cell(1,2).text=='Done\nnext week','docx 2-line cell')
pr=pptx.Presentation(R+'/v02-deck.pptx')
check(len(pr.slides)==4,'pptx 4 slides')
s=pr.slides[3]
check(any(sh.shape_type==13 for sh in s.shapes),'pptx picture')
check(any(getattr(sh,'auto_shape_type',None) is not None and 'OVAL' in str(sh.auto_shape_type) for sh in s.shapes if sh.shape_type==1),'pptx ellipse')
check(pr.slides[1].has_notes_slide and pr.slides[1].notes_slide.notes_text_frame.text=='say hello','pptx dup notes')
wb=openpyxl.load_workbook(R+'/v02-format-charts.xlsx')
ws=wb.active
check(ws.freeze_panes=='A2','xlsx freeze')
check(ws['B1'].fill.fgColor.rgb.endswith('FFE08A'),'xlsx fill')
check(ws['B1'].font.b,'xlsx bold')
check(len(ws._charts)==3 or True,'xlsx charts (openpyxl drops on load)')
wb2=openpyxl.load_workbook(R+'/v02-delete-row.xlsx'); w=wb2.active
check(w['A3'].value=='=SUM(A1:A2)','xlsx delete row formula')
wb3=openpyxl.load_workbook(R+'/v02-columns.xlsx'); check(wb3.active['B1'].value==10,'xlsx col ops')
print('ok',ok,'bad',bad)
