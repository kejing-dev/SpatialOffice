# Changelog · 更新记录

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
