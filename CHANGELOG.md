# Changelog · 更新记录

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
