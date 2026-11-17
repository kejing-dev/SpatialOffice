# 空间办公 Spatial Office

PICO 上的键鼠办公三件套：文档 / 表格 / 演示三个原生窗口，直接打开、编辑、保存标准的 .docx / .xlsx / .pptx（另有 .md / .txt / .csv；PDF、图片、网页只读），可以把一份文件在空间里铺开。v0.2.0。

构建、目录结构、调试广播见 [AGENTS.md](AGENTS.md)。功能清单（桌面版常用功能 × 本版有没有）见工厂仓库 `apps/spatial-office/build/feature-matrix.md`。

## 连接账号（飞书、Google Drive、OneDrive、Dropbox）

账号是可选的：一个都不连，本机文件全部能用。连接走各家自己的登录页（系统浏览器里打开，授权码 + PKCE），应用里没有密码输入框；登录凭证用 EncryptedSharedPreferences 加密存在头显上，退出登录就删除。

每一家都要**你自己先在它的开发者后台登记一个应用**，把拿到的 ID 填进工程根目录的 `local.properties`（这个文件不进 git），重新打包。没填的那一家，应用里会写「需要开发者先登记应用」，点 [连接] 只会提示要填哪个键，不会发出任何请求。

| 服务 | 去哪登记 | 应用类型 / 要开的权限 | 回调地址（登记时填，和下面的键一致） | 填进 local.properties |
|---|---|---|---|---|
| 飞书 | [飞书开放平台](https://open.feishu.cn/app) → 创建企业自建应用 → 安全设置 → 重定向 URL | 网页应用；权限：云空间 `drive:drive`、云文档 `docx:document`、电子表格 `sheets:spreadsheet`、`offline_access`（用户身份）；发布版本后企业内可用 | `http://127.0.0.1:53682/callback` | `cloud.feishu.appId=cli_xxx`<br>`cloud.feishu.appSecret=xxx` |
| Google Drive | [Google Cloud Console](https://console.cloud.google.com/apis/credentials) → 新建项目 → 启用 Google Drive API → OAuth 同意屏幕（测试用户里加上自己）→ 凭据 → OAuth 客户端 ID | 类型选「桌面应用」（Desktop app）；范围 `https://www.googleapis.com/auth/drive` | 桌面应用不用填，Google 允许 `http://127.0.0.1:<端口>` 回环地址；应用用 `http://127.0.0.1:53682/callback` | `cloud.google.clientId=xxx.apps.googleusercontent.com`<br>`cloud.google.clientSecret=xxx` |
| OneDrive | [Azure 门户 · 应用注册](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade) → 新注册 → 受支持的帐户类型选「任何组织目录中的帐户和个人 Microsoft 帐户」 | 身份验证 → 添加平台「移动和桌面应用程序」；API 权限：Microsoft Graph 委托 `Files.ReadWrite`、`User.Read`、`offline_access` | `spatialoffice://oauth/onedrive` | `cloud.onedrive.clientId=<应用程序(客户端) ID>` |
| Dropbox | [Dropbox App Console](https://www.dropbox.com/developers/apps) → Create app → Scoped access → Full Dropbox | Permissions 里勾 `files.metadata.read`、`files.content.read`、`files.content.write`、`account_info.read` | `http://localhost:53682/callback`（Settings → OAuth 2 → Redirect URIs） | `cloud.dropbox.appKey=<App key>` |

回调地址也可以改：在 `local.properties` 里写 `cloud.<feishu|google|onedrive|dropbox>.redirect=…`，登记时填同一个。`http://127.0.0.1` / `http://localhost` 开头的，应用在头显本机临时监听那个端口收回调；`spatialoffice://oauth/...` 开头的，由系统把浏览器跳回应用。

登记之后各家的限制（按各家公开说明，正式发布前要再核一次）：Google 的「整个云盘读写」属于受限范围，没过审核前只有同意屏幕里列的测试用户能连；OneDrive 的工作账号可能要对方企业管理员同意；Dropbox 开发阶段连接用户数有上限；飞书企业自建应用只有本企业的账号能连。

连接之后能做什么：

- 「浏览」上面切到那个云盘，一层层翻文件夹；点文件就在对应的编辑窗口里打开，[保存] 传回同一个文件（Google Drive / OneDrive / Dropbox 是同一个文件 id，云盘里多一个历史版本）。
- 飞书云文档、飞书电子表格直接改，保存时只写回改过的段落 / 单元格；飞书幻灯片、Google 自己格式的文档只读（Google 文档会导出成 .docx / .xlsx / .pptx 打开，可以 [另存为] 一份副本）。
- 「最近」里本机和云端的文件分两组列出；[另存为] 可以选「存一份到 <云盘>」，放在云盘最上一层。
- 断网时已经打开的文件可以继续改，联网后再保存；登录过期时改动留在头显上，重新登录后再保存。

这一版没做的（PRD §9 里写了、但 v0.2.0 没做）：扫码登录（③a）、保存前比对云端版本的三选一、飞书「收件夹」和 `lark-cli` 推送、飞书普通文件的历史版本（飞书云盘没有「上传新版本」接口，保存是在同一文件夹传一个同名文件再删掉旧的）。
