# Changelog · 更新记录

## v0.7.0 · 2026-11-22

- 审阅栏：三类编辑窗口的标题栏多了 [审阅 n]，点一下窗口里右边出来一条栏，看批注、回复、解决；栏在窗口里面，窗口不变宽、不挪位置。文件里有批注或修订时，文件名下面那行会提示，不自己弹开
- 文档批注：选一段字（或把光标放在一段里），在栏底写一句点 [发送]；也可以用 [插入] › 批注 或 Ctrl+Alt+M。回复、解决、重新打开、改字、删除都在卡片上。存回 .docx 后电脑上打开看得见
- 文档修订：栏里 [修订] 一页逐条 [接受] [拒绝]，底部 [全部接受] [全部拒绝]（Ctrl+Z 一下退回）。纸上加的字是下划线，删的字是红色删除线
- 记下我的修改：栏里的开关、[更多] › 记下我的修改、Ctrl+Shift+E。开着时你加的字、删的字都存成带你名字和时间的修订
- 表格批注：有批注的格子右上角有个小三角（蓝 = 批注，灰 = 老式备注）；[编辑] › 批注 或 Ctrl+Alt+M 给当前格写批注。老式备注能改字、能删
- 演示批注：页面上是一个带作者首字的小气泡，点它栏里那条就选中；栏里先列这一页的，再列别的页的。预演时不显示
- 我的名字：主页 [设置] 里多一行「我的名字」，批注和修订上署这个名字；没填时第一次写批注会在栏里问一次
- 格式刷：[文字] › 格式刷 或 Ctrl+Shift+C 拿起，点一下要刷的那段（或拖选几个字）就刷好；Esc 放下
- 页眉和页脚：文档 [插入] › 页眉和页脚…，纸面最上、最下显示成灰字，点灰字也能改
- 页码和页脚：演示 [页面] › 页码和页脚…，可以只用在这页，也可以全部页面，标题页可以不显示
- 主页窗口高了 60 dp（放得下「我的名字」那一行）
- Review pane: the title bar of every editor has Review n; it opens a pane inside the window, at its right edge, with the comments to read, reply to and resolve. The window keeps its size and place. A file that has comments or tracked changes says so under its name; the pane never opens by itself
- Document comments: select some text (or just put the cursor in a paragraph), type at the bottom of the pane and Post; also Insert › Comment and Ctrl+Alt+M. Reply, resolve, reopen, edit and delete are on the card. They are stored in the .docx the standard way
- Tracked changes: the Changes list has Accept / Reject on each one and Accept all / Reject all at the bottom (one Ctrl+Z undoes all of them). On the paper added text is underlined, deleted text is red and struck through
- Track my edits: the switch in the pane, More › Track my edits, Ctrl+Shift+E. While on, what you add or delete is stored as a tracked change with your name and the time
- Sheet comments: a commented cell has a small triangle in its corner (blue = comment, grey = old-style note); Edit › Comment or Ctrl+Alt+M comments on the current cell. Old-style notes can be edited and deleted
- Slide comments: a small bubble with the author's initial on the slide; tapping it picks its card. The pane lists this slide's comments first. Not shown while rehearsing
- My name: Settings has a new row, the name shown on your comments and changes; the pane asks once if it is empty
- Format painter: Text › Format painter or Ctrl+Shift+C picks the format up, a tap on a paragraph (or a drag over some text) puts it on; Esc drops it
- Header & footer: Insert › Header & footer… in documents; shown as grey lines at the top and bottom of the paper, which can be tapped too
- Slide numbers & footer: Slide › Slide numbers & footer…, for this slide or all slides, optionally not on the title slide
- The home window is 60 dp higher (room for the My name row)

## v0.6.0 · 2026-11-22

- 文档：可以从一段中间拖到另一段中间，一次选中好几段；Ctrl+C / Ctrl+X 复制剪切，粘到别处时加粗、颜色、列表都还在（[更多 ▾] 里也有复制 / 剪切 / 粘贴）
- 文档：[更多 ▾] › 大纲：列出标题，点一下就翻到那里
- 新建：主页「新建」的文档、演示卡下面各有一行模板：会议纪要 / 周报 / 方案 / 简历，工作汇报 / 产品介绍 / 极简深色；新建出来就是普通的 .docx / .pptx
- 表格：输入 = 和函数名开头时列出可用的函数，Tab 补全，括号里提示参数
- 表格：[数据 ▾] › 筛选…：选值、大于、小于、包含；排序…：按好几列排，第一行是表头时表头留在最上面（中文按拼音排）；冻结到这里（如冻结到 B2：上面一行、左边一列不动）
- 表格：[行列 ▾] › 隐藏 › 隐藏行 / 隐藏列 / 取消隐藏，存进文件
- 演示：[所选对象 ▾] › 加入多选，几个对象一起左对齐 / 居中 / 右对齐、横向 / 纵向等距，组合后一起拖，也能取消组合；拖动对象时靠近别的对象或页面中线会吸附，出一条细参考线
- 预演：预演条加了 [跳到…]（点页码直接跳）和 [黑屏]；键盘输入页码按回车跳页，按 B 黑屏 / 恢复；[页面 ▾] › 从第 1 页开始预演
- 预演结束后，刚放映的那扇窗回到主页旁边最近的位置，主页上一行提示它在左边还是右边；其他窗口留在原来那一侧，不再左右对调
- Documents: drag from the middle of one paragraph to the middle of another to select across paragraphs; Ctrl+C / Ctrl+X, and pasting keeps bold, colours and lists (Copy / Cut / Paste are also under More)
- Documents: More › Outline lists the headings; a click scrolls there
- New: rows of templates under the document and presentation cards: Meeting notes / Weekly report / Proposal / Résumé, Work report / Product pitch / Minimal dark; they are ordinary .docx / .pptx files
- Sheets: typing = and the start of a name lists the functions, Tab completes, the arguments show inside the brackets
- Sheets: Data › Filter… (pick values, greater than, less than, contains); Sort… by several columns, headings kept on top (Chinese by pinyin); Freeze here (at B2: the row above and the column left stay)
- Sheets: Rows & columns › Hide › rows / columns / Unhide, saved in the file
- Slides: Object › Add to selection, then align left / centre / right, space evenly, group and drag together, ungroup; a dragged object snaps to the others and to the slide centre with a thin guide line
- Rehearsal: the bar has Go to… (pick a slide number) and Black screen; type a number and Enter to jump, B for black; Page › Rehearse from slide 1
- After a rehearsal the file just shown comes back right next to the home window, which says on which side; the other windows stay on their own side

## v0.5.2 · 2026-11-21

- 修好：从主页连开几个文件时，主页也在环绕的一排里占一个位置：第一个文件开在主页旁边，不再和主页叠在同一处；用 [窗口 n] › [主页] 回主页时主页留在原地，不会把哪个文件窗收起来，几扇窗口和主页都在、都朝向你
- Fixed: opening several files from Home, the home window now has its own place in the row around you: the first file opens beside it, not on top of it; Windows › Home leaves the home window where it is and no file window is put away, so Home and every file window stay up, all facing you

## v0.5.1 · 2026-11-21

- 修好：表格里打字按回车后，上一格的字不会再带进下一格（「abc」「de」「f」不再存成 Abc / Abcde / Abcdef）；单元格不再自动把首字母变大写
- 修好：链接地址框用网址键盘、不自动大写；「Https://…」这类大写开头的地址也能打开；打不开的链接会提示「打不开这个链接」，不再没反应
- 多开窗口直接摆在旁边：再开一个文件，新窗口沿着你周围的弧面摆到已有窗口的右边、左边，依次排开，都朝向你，不再挡在正前方把旧窗口挤到后面；不用先点 [并排摆开]
- 网盘 [连接] 和文档里的链接都用 PICO 浏览器打开，在网页里授权后回到应用，不去找网盘自己的 App
- 下拉菜单开着时按返回键 / 手柄 B 先关菜单
- Fixed: typing in a cell and pressing Enter no longer carries the last cell's text into the next one ("abc", "de", "f" no longer saved as Abc / Abcde / Abcdef); cells no longer capitalise the first letter
- Fixed: the link address field has the web-address keyboard and no automatic capital; addresses starting "Https://" open; a link that cannot open says so
- More windows stand beside each other: opening another file puts its window on the circle around you, to the right, then the left of the ones already up, all facing you; none is pushed behind; no need to press Side by side first
- Drive [Connect] and document links open in PICO Browser: approve on the web page and come back; no drive app is looked for
- With a dropdown menu open, BACK / controller B closes the menu first

## v0.5.0 · 2026-11-21

- 表格：Ctrl+D 向下填充、Ctrl+R 向右填充（公式跟着挪），[编辑 ▾] › 填充 › 填充序列：1、2 接着 3、4，日期按天往后，「Q1」接「Q2」
- 表格：[行列 ▾] › 合并单元格 / 取消合并（只留左上角的内容）；[颜色 ▾] › 边框：全部框线、外框、下框线、无框线
- 表格：函数从 46 个加到 108 个，新增 SUMIFS / COUNTIFS / AVERAGEIFS / MAXIFS / MINIFS、INDEX / MATCH / XLOOKUP、TODAY / NOW / DATE / YEAR / MONTH / DAY / WEEKDAY / DATEDIF / EDATE / EOMONTH、TEXT / FIND / SEARCH / SUBSTITUTE / VALUE / TEXTJOIN、STDEV / VAR / RANK / SUMPRODUCT、IFS / SWITCH / IFNA、ISBLANK 等；新函数按 Excel 的写法存进文件，Excel / WPS 打开不会显示 #NAME?
- 文档：[插入 ▾] › 链接…（Ctrl+K），点链接用浏览器打开；分隔线、分页符；[文字 ▾] 里加了上标、下标；光标在表格里时 [插入 ▾] 多出「表格行列」：插入 / 删除行列、与右侧合并、与下方合并
- 演示：[页面 ▾] › 背景：几种底色或一张图片，这一页或所有页；选中表格时 [所选对象 ▾] 多出「表格行列」：插入 / 删除行列、和右边 / 下面合并
- 三类窗口都能缩放：标题栏的「100 %」菜单，或 Ctrl+= / Ctrl+- / Ctrl+0；只改显示，不动文件
- Sheets: Ctrl+D fill down, Ctrl+R fill right (formulas move along), Edit › Fill › Fill series (1, 2 → 3, 4; dates by day; "Q1" → "Q2")
- Sheets: Rows & columns › Merge cells / Unmerge (only the top-left value stays); Colour › Borders: all, outside, bottom, none
- Sheets: 108 functions (was 46): SUMIFS / COUNTIFS / AVERAGEIFS / MAXIFS / MINIFS, INDEX / MATCH / XLOOKUP, dates (TODAY, DATE, DATEDIF, EDATE, EOMONTH…), text (TEXT, FIND, SEARCH, SUBSTITUTE, TEXTJOIN…), STDEV / VAR / RANK / SUMPRODUCT, IFS / SWITCH / IFNA, ISBLANK and more; newer ones are saved the way Excel writes them, so Excel and WPS show them, not #NAME?
- Documents: Insert › Link… (Ctrl+K), a click opens the link in the browser; horizontal line, page break; superscript and subscript in Text; with the caret in a table, Insert › Table rows & columns: insert / delete rows and columns, merge with right / below
- Slides: Page › Background: a few colours or a picture, this slide or all; with a table picked, Object › Table rows & columns: insert / delete rows and columns, merge with right / below
- Zoom in all three windows: the title bar's "100 %" menu, or Ctrl+= / Ctrl+- / Ctrl+0; display only, the file is not touched
## v0.4.2 · 2026-11-21

- 修好：手柄 A / X / Y、回车、确认键不会再「点」到窗口里的按钮。之前在演示窗口按几下会多出空白页，在「找回没保存的改动？」上按 A 会把改动删掉
- 窗口按钮、菜单项、开关、列表行都拿不到键盘焦点；回车只在正在打字的地方（段落、单元格、文本框、查找框）起作用，Ctrl 快捷键、Tab、方向键照常
- Fixed: controller A / X / Y, Enter and the centre key no longer press a window button (before, a few presses added blank slides, and A on "Recover unsaved changes?" threw the changes away)
- Window buttons, menu entries, switches and list rows never take key focus; Enter only works where you are typing (paragraph, cell, text box, find field); Ctrl shortcuts, Tab and arrows work as before

## v0.4.1 · 2026-11-21

- 修好：打开一个文本框里有两段以上项目符号的演示文件会闪退；选中跨两段的文字加 / 去项目符号也不再闪退
- 修好：演示标题里的段内换行（PowerPoint 的 Shift+Enter）在查找替换、打字、改格式之后还是换行，不会被拆成两段
- 去掉项目符号再加回来，段落的缩进回到原样
- Fixed: a deck with bullets on two or more paragraphs of one box no longer closes the app; bullets on / off over a selection across paragraphs no longer crashes
- Fixed: a line break inside a slide paragraph (Shift+Enter in PowerPoint) stays a line break through find & replace, typing and formatting
- Turning bullets off and on again gives the paragraph its own indent back

## v0.4.0 · 2026-11-21

- 三类工具条都有 [文字 ▾] 和 [颜色 ▾]：选中几个字改字号（加大 / 减小 / 12 / 14 / 18 / 24 磅）、六种文字颜色、删除线、清除格式；文档还能加黄 / 绿 / 青高亮
- 文档 [段落 ▾]：增加 / 减少缩进，编号和项目符号列表里 Tab / Shift+Tab 换级别（1. → a. → i.），行距 1.0 / 1.15 / 1.5 / 2.0，段前段后各 6 磅
- 表格：[文字 ▾] 收进了原来的 B 键，加粗、斜体、下划线、删除线、字号都在里面（Ctrl+I / Ctrl+U 也行）；[颜色 ▾] 里是文字颜色和单元格填充色
- 演示：文本框里只改选中的那几个字（加粗、斜体、下划线、颜色、字号），别的字原来什么格式还是什么格式；段落左中右对齐、项目符号
- 演示 [所选对象 ▾]：填充色、线条颜色和粗细、向左 / 向右转 90°、转回正；选中对象后移动钮旁边多一个旋转钮，拖着转，15° 一档
- 历史版本：每次保存留一版（只存在这台设备上，最多 10 版），[更多 ▾]（表格在 [编辑 ▾]，演示在 [页面 ▾]）› 历史版本 里点 [恢复]；恢复前的内容也会留一版，撤销能退回
- Text ▾ and Colour ▾ in all three toolbars: size (larger / smaller / 12 / 14 / 18 / 24 pt), six text colours, strikethrough, clear formatting for the selected characters; documents also have yellow / green / cyan highlight
- Documents Paragraph ▾: increase / decrease indent, Tab / Shift+Tab change a list item's level (1. → a. → i.), line spacing 1.0 / 1.15 / 1.5 / 2.0, 6 pt before and after
- Sheets: Text ▾ takes the B button's place with bold, italic, underline, strikethrough and size (Ctrl+I / Ctrl+U too); Colour ▾ holds text colours and cell fills
- Slides: format only the selected characters of a text box (bold, italic, underline, colour, size); the rest keep their own format; paragraph alignment and bullets
- Slides Object ▾: fill, line colour and width, rotate left / right 90°, straighten; a rotate handle beside the move tab turns the picked object in 15° steps
- Version history: every save keeps a version on this device (up to 10); More ▾ (sheets: Edit ▾, slides: Page ▾) › Version history › Restore; what you had before a restore is kept too, and Undo takes it back

## v0.3.0 · 2026-11-21

- 表格：拖一下选一片（也可以点行号、列号，Shift+方向键加选，Ctrl+方向键跳到数据边上），选中的地方蓝框标出，下面一行显示求和 · 平均 · 计数；加粗、填充、数字格式对整片生效
- 表格 [编辑 ▾]：复制、剪切、粘贴（Ctrl+C / X / V），公式粘过去引用跟着挪；从别的应用复制来的表格文字按制表符拆成格
- 表格：列加宽 / 变窄 / 按内容自适应、行加高 / 变矮；工作表 [⋯] 新建、重命名、复制、删除；[格式 ▾] 加了左中右对齐、自动换行、增减小数位
- 查找和替换三类都有：[上一个] [下一个] 跳到那一处并标出来，[替换] 只换这一处，[全部替换] 一次撤销全回来，可以区分大小写；演示连备注一起找
- 改了没存就关掉或崩了：每 3 秒自动记一份草稿，下次打开问「找回没保存的改动？」
- 演示：选中的对象可以复制、粘到别的页、重复，可以置于顶层 / 底层 / 上移 / 下移一层
- 手柄 B / 返回键：关掉打开的弹层，主窗口关了就叫回来，不会退出应用
- Sheets: drag to select a range (or click row numbers / column letters, Shift+arrows, Ctrl+arrows to the data edge); the range has a blue box and the status line shows sum · average · count; bold, fill and formats apply to all of it
- Sheets Edit ▾: copy, cut, paste (Ctrl+C / X / V); pasted formulas move their references; tab-separated text from other apps splits into cells
- Sheets: wider / narrower / fit columns, taller / shorter rows; [⋯] new, rename, copy, delete sheet; Format ▾ has align left / centre / right, wrap text, more / fewer decimals
- Find and replace in all three: Previous / Next jump to the hit and mark it, Replace changes only that one, Replace all undoes in one step, Match case; slides include the notes
- Unsaved changes survive a crash: a draft every 3 s, and the next opening asks "Recover unsaved changes?"
- Slides: copy, paste on another slide, duplicate the picked object; bring to front / send to back / forward / backward
- Controller B / Back closes the open dialog or brings the home window back; it never leaves the app

## v0.2.7 · 2026-11-18

- [另存为]：没有登录着的云盘时，对话框里不再有那一行灰掉、点了没反应的「存到云盘」；登录了才出现「存一份到 <云盘>」，出现的每一行都能选
- 「最近」里的云端文件只在那一家云盘这一版能连时才列出
- Save as: without a signed-in cloud drive the dialog no longer has the greyed-out "Save to a cloud drive" row that did nothing; "Save a copy to <drive>" appears only once a drive is signed in, and every row drawn can be picked
- Recent lists a cloud file only while this build offers its drive

## v0.2.6 · 2026-11-18

- 演示窗口：不管页面是 4:3、16:9、16:10 还是竖版，下面的讲者备注和选中提示都留着位置，看得到也写得了（之前 4:3 的文件画布把备注挤没了）；左边缩略图、故事线里的每一页、预演大屏和「下一页」也按页面比例完整显示
- Slides window: speaker notes and the picked-object hint keep their place under the slide for any page proportion (4:3, 16:9, 16:10, tall); a 4:3 file used to push them out of the window. Thumbnails, storyline pieces, the rehearsal screen and "next slide" show the whole page too

## v0.2.5 · 2026-11-18

- 演示里插入的文本框、图片、形状、表格：点一下选中，按住拖动挪位置，拖四个角上的圆点改大小；演示里可以插入表格并逐格输入；文档里的图片和表格也能拖角改大小
- 同时开几个文件：每个文件一扇窗，开第二个文档 / 表格 / 演示不再顶掉第一个；再点已经开着的文件，它的窗口回到面前；标题栏 [窗口 n] 切换、回主页、并排摆开
- 连接账号：点 [连接] 直接弹出系统浏览器登录；打包时没放应用 ID 的服务在应用里不再出现，配置说明只留在 README
- Text boxes, pictures, shapes and tables on a slide can be dragged and resized by their corners; slides get tables; document pictures and tables resize too
- Several files at once: one window per file, nothing is replaced; opening an open file brings its window forward; Windows n in the title bar switches, goes Home or sets them side by side
- Connect opens the system browser; services without a client id at build time are no longer shown, and the setup notes live in the README only

## v0.2.4 · 2026-11-17

- 3D 图表在后台准备、分批放进场景，打开时不再卡住或弹「没有响应」；图表始终挂在当前表格窗口上方，预演、铺开回来也不压住表格
- The 3D chart is prepared off the main thread and added in small batches: no freeze or "not responding" when it opens; it always anchors to the live sheet window, also after rehearsal or spread

## v0.2.3

- 图表两侧面板按真实观看距离定尺寸（字高 ≥ 0.5°、按钮 ≥ 48 dp）；图表等表格窗口开好再开
- 飞书云盘文件不能原地更新时，原文件不动，改动另存成旁边的「<名字> (空间办公 副本)」并提示
- Chart side panels sized for the real viewing distance; the chart waits for the sheet window
- Feishu: when in-place upload is refused, the original is kept and edits go to a copy beside it

## v0.2.2

- 兼容性说明改成编辑窗口里的面板；3D 图表开在表格窗口上方；铺开 / 预演收起后所有窗口都回来
- Compatibility notes as a sheet inside the editor; 3D chart above the sheet window; collapse brings every window back

## v0.2.1

- 编辑窗口开在正前方；新建页、文本框、表格首格、插入的行列当场选中；扫码登录、保存前比对云端版本、飞书收件夹
- Editors open in front; new items are selected at once; scan-to-sign-in, cloud version check, Feishu inbox

## v0.2.0

- 云盘账号（飞书 / Google Drive / OneDrive / Dropbox，需开发者登记应用）；文档、演示、表格工具补齐（图片、表格、形状、主题、函数、筛选、折线 / 饼图）
- Cloud accounts (need a developer app registration); fuller doc / slides / sheet tools

## v0.1.0

- 标准 .docx / .xlsx / .pptx 读写核心，三种编辑窗口，铺开舞台，3D 图表
- Standard .docx / .xlsx / .pptx core, editors, spread stage, 3D chart
