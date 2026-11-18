package io.embodit.spatialoffice.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.embodit.spatialoffice.BuildConfig
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.cloud.AppRegistration
import io.embodit.spatialoffice.core.cloud.AuthRequest
import io.embodit.spatialoffice.core.cloud.CloudApi
import io.embodit.spatialoffice.core.cloud.CloudConflictException
import io.embodit.spatialoffice.core.cloud.CloudVersion
import io.embodit.spatialoffice.core.cloud.DeviceAuth
import io.embodit.spatialoffice.core.cloud.FeishuDrive
import io.embodit.spatialoffice.core.cloud.CloudItem
import io.embodit.spatialoffice.core.cloud.FeishuDoc
import io.embodit.spatialoffice.core.cloud.FeishuSheet
import io.embodit.spatialoffice.core.cloud.Http
import io.embodit.spatialoffice.core.cloud.Provider
import io.embodit.spatialoffice.core.cloud.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class AcctState { UNREGISTERED, OFF, WAITING, ON, EXPIRED, REVOKED }

data class AcctInfo(val state: AcctState, val name: String = "", val note: String? = null)

/** What the Browse page shows for one cloud location. */
class CloudBrowse {
    var stack by mutableStateOf<List<CloudItem>>(emptyList())
    var items by mutableStateOf<List<CloudItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedFor by mutableStateOf<String?>(null)
}

class CloudAuthException(msg: String) : IOException(msg)

/** Scan-a-code sign-in in progress (PRD §9.2 ③a): what the dialog shows. */
data class ScanState(val provider: Provider, val code: DeviceAuth.Code?, val error: String? = null)

/**
 * Optional cloud accounts (PRD §9). Sign-in happens only on the service's own page in the system browser
 * (authorisation code + PKCE); the app never sees a password. Tokens are kept in EncryptedSharedPreferences and
 * deleted on sign-out. A service whose app registration is missing (README) says so and sends nothing.
 */
class Accounts(private val app: Application, private val graph: Graph) {
    val info = mutableStateMapOf<Provider, AcctInfo>()
    val browse = Provider.values().associateWith { CloudBrowse() }

    private val pending = ConcurrentHashMap<Provider, AuthRequest>()
    /** Scan-a-code sign-in being shown (null = none). */
    var scan by mutableStateOf<ScanState?>(null)
        private set
    private var scanJob: Job? = null
    /** Service whose [Connect] was pressed last and what happened, shown in that row so the tap visibly did something. */
    var lastTap by mutableStateOf<Provider?>(null)
    /** Version stamp of each open cloud file when it was opened / last saved (for the §9.3 conflict check). */
    private val versions = ConcurrentHashMap<String, String>()
    /** Feishu "空间办公" folder and its inbox (PRD §9.4), once known. */
    var feishuInbox by mutableStateOf<String?>(null)
        private set
    private var inboxJob: Job? = null
    private val waits = HashMap<Provider, Job>()
    private var server: ServerSocket? = null

    /** Original blocks / values of open Feishu online documents and sheets, by file key, for writing back only what changed. */
    private val feishuDocs = ConcurrentHashMap<String, List<FeishuDoc.Block>>()
    private val feishuSheets = ConcurrentHashMap<String, Pair<String, List<List<String>>>>()

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, "cloud_tokens", key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.onFailure { Log.e(TAG, "CLOUD token store unavailable", it) }.getOrNull()
    }

    init {
        Provider.values().forEach { info[it] = initial(it) }
        feishuInbox = prefs?.getString("feishu.inbox", null)
        if (connected(Provider.FEISHU)) watchInbox()
    }

    fun registration(p: Provider): AppRegistration = when (p) {
        Provider.FEISHU -> AppRegistration(BuildConfig.FEISHU_APP_ID, BuildConfig.FEISHU_APP_SECRET, BuildConfig.FEISHU_REDIRECT)
        Provider.GDRIVE -> AppRegistration(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET, BuildConfig.GOOGLE_REDIRECT)
        Provider.ONEDRIVE -> AppRegistration(BuildConfig.ONEDRIVE_CLIENT_ID, "", BuildConfig.ONEDRIVE_REDIRECT)
        Provider.DROPBOX -> AppRegistration(BuildConfig.DROPBOX_APP_KEY, "", BuildConfig.DROPBOX_REDIRECT)
    }

    /** The local.properties key the developer fills in for [p] (shown on screen and in README). */
    fun configKey(p: Provider): String = when (p) {
        Provider.FEISHU -> "cloud.feishu.appId"
        Provider.GDRIVE -> "cloud.google.clientId"
        Provider.ONEDRIVE -> "cloud.onedrive.clientId"
        Provider.DROPBOX -> "cloud.dropbox.appKey"
    }

    private fun initial(p: Provider): AcctInfo {
        if (!registration(p).registered) return AcctInfo(AcctState.UNREGISTERED)
        val t = tokens(p) ?: return AcctInfo(AcctState.OFF)
        return AcctInfo(AcctState.ON, prefs?.getString("${p.id}.name", "") ?: "", if (t.refresh == null && t.expiresAt < System.currentTimeMillis()) "expired" else null)
    }

    fun state(p: Provider): AcctState = info[p]?.state ?: AcctState.UNREGISTERED
    fun connected(p: Provider): Boolean = state(p) == AcctState.ON
    val anyConnected: Boolean get() = Provider.values().any { connected(it) }

    private fun tokens(p: Provider): Tokens? = prefs?.getString("${p.id}.tokens", null)?.let { Tokens.fromJson(it) }

    private fun saveTokens(p: Provider, t: Tokens?) {
        val e = prefs?.edit() ?: throw CloudAuthException(graph.str(R.string.acct_store_fail))
        if (t == null) e.remove("${p.id}.tokens").remove("${p.id}.name").remove("${p.id}.root") else e.putString("${p.id}.tokens", t.toJson())
        e.apply()
    }

    // ------------------------------------------------------------------ sign-in

    /**
     * [Connect] (PRD §9.2 ①–②): shows the choice of way — scan a code with a phone or computer (recommended, where
     * the service has it) or sign in in the headset's browser. A service that is not registered yet shows the same
     * dialog with both ways greyed out and what the developer has to register, so the tap always shows something.
     */
    fun connect(p: Provider) {
        val reg = registration(p)
        if (!reg.registered) {
            Log.i(TAG, "CLOUD ${p.id} not registered (${configKey(p)} empty)")
            lastTap = p
            graph.overlay = Overlay.Connect(p)
            graph.say(Win.HOME, graph.str(R.string.acct_need_reg_toast, graph.str(nameRes(p)), configKey(p)))
            return
        }
        lastTap = null
        // only one way: skip the choice (PRD §9.2 ②)
        if (!DeviceAuth.supported(p)) { connectInBrowser(p); return }
        graph.overlay = Overlay.Connect(p)
    }

    /** ③b: the service's own sign-in page in the system browser, then back here through the registered callback. */
    fun connectInBrowser(p: Provider) {
        val reg = registration(p)
        if (!reg.registered) return
        if (graph.overlay is Overlay.Connect) graph.overlay = null
        cancel(p)
        val req = AuthRequest.create(p, reg)
        pending[p] = req
        info[p] = AcctInfo(AcctState.WAITING, note = null)
        val loopback = reg.redirectUri.startsWith("http://127.0.0.1") || reg.redirectUri.startsWith("http://localhost")
        if (loopback) listenLoopback(Uri.parse(reg.redirectUri).port.takeIf { it > 0 } ?: 80)
        val url = req.browserUrl()
        Log.i(TAG, "CLOUD ${p.id} open sign-in page (${p.authUrl}) redirect=${reg.redirectUri}")
        val ok = runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) {
            pending.remove(p)
            info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_no_browser))
            graph.say(Win.HOME, graph.str(R.string.acct_no_browser))
            return
        }
        // the code is valid for ten minutes; after that the attempt ends
        waits[p] = graph.scope.launch {
            kotlinx.coroutines.delay(10 * 60_000L)
            if (pending.remove(p) != null) { info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled)); stopLoopback() }
        }
    }

    fun cancel(p: Provider) {
        if (scan?.provider == p) cancelScan()
        waits.remove(p)?.cancel()
        if (pending.remove(p) != null) {
            info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
            if (pending.isEmpty()) stopLoopback()
        }
    }

    /**
     * ③a: asks the service for a device code, shows it as a QR code + short code, and polls until the user agrees on
     * the other device, declines, or the code runs out (then back to the choice, PRD §9.2). No password is ever typed
     * on the headset.
     */
    fun connectByScan(p: Provider) {
        val reg = registration(p)
        if (!reg.registered || !DeviceAuth.supported(p)) return
        cancel(p)
        scan = ScanState(p, null)
        graph.overlay = Overlay.Scan(p)
        info[p] = AcctInfo(AcctState.WAITING)
        scanJob = graph.scope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    DeviceAuth.parseStart(String(raw(CloudApi.Req("POST", DeviceAuth.startUrl(p), "application/x-www-form-urlencoded", DeviceAuth.startBody(p, reg)), null, null), Charsets.UTF_8), System.currentTimeMillis())
                }
            }
            val code = started.getOrElse { e ->
                Log.e(TAG, "CLOUD ${p.id} device code failed", e)
                scan = ScanState(p, null, graph.str(R.string.scan_failed, e.message ?: ""))
                info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
                return@launch
            }
            scan = ScanState(p, code)
            Log.i(TAG, "CLOUD ${p.id} device code shown, expires in ${(code.expiresAt - System.currentTimeMillis()) / 1000}s")
            var interval = code.intervalMs
            while (System.currentTimeMillis() < code.expiresAt) {
                kotlinx.coroutines.delay(interval)
                val (type, body) = DeviceAuth.pollBody(p, reg, code)
                val ans = withContext(Dispatchers.IO) { runCatching { String(rawAny(CloudApi.Req("POST", p.tokenUrl, type, body)), Charsets.UTF_8) } }
                val poll = ans.map { DeviceAuth.parsePoll(it, System.currentTimeMillis()) }.getOrElse { DeviceAuth.Poll.Pending }
                when (poll) {
                    DeviceAuth.Poll.Pending -> {}
                    DeviceAuth.Poll.SlowDown -> interval += 5_000
                    is DeviceAuth.Poll.Done -> { connected(p, poll.tokens); return@launch }
                    is DeviceAuth.Poll.Ended -> {
                        Log.i(TAG, "CLOUD ${p.id} scan ended: ${poll.why}")
                        endScan(p); return@launch
                    }
                }
            }
            endScan(p)
        }
    }

    /** Declined or timed out: back to the choice, nothing kept (PRD §9.2 "拒绝或超时"). */
    private fun endScan(p: Provider) {
        scan = null; scanJob = null
        info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
        graph.overlay = Overlay.Connect(p)
        graph.say(Win.HOME, graph.str(R.string.acct_cancelled))
    }

    /** Debug only: the scan dialog with a sample code (no request is sent), for screenshots on machines without a registration. */
    fun previewScan(p: Provider) {
        cancelScan()
        val url = if (p == Provider.ONEDRIVE) "https://microsoft.com/devicelogin" else "https://accounts.feishu.cn/oauth/v1/device/verify"
        scan = ScanState(p, DeviceAuth.Code("sample", "SAMP-LE42", url, if (p == Provider.ONEDRIVE) null else "$url?user_code=SAMP-LE42", System.currentTimeMillis() + 600_000, 5_000))
        graph.overlay = Overlay.Scan(p)
    }

    fun cancelScan() {
        val p = scan?.provider
        scanJob?.cancel(); scanJob = null; scan = null
        if (p != null && info[p]?.state == AcctState.WAITING) info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
        if (graph.overlay is Overlay.Scan) graph.overlay = null
    }

    /** Tokens arrived (browser or scan): store, read the name, set up the Feishu folders, say so. */
    private suspend fun connected(p: Provider, t: Tokens) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                saveTokens(p, t)
                val name = runCatching { CloudApi.parseMe(p, String(call(p, CloudApi.me(p)), Charsets.UTF_8)) }.getOrDefault("")
                prefs?.edit()?.putString("${p.id}.name", name)?.apply()
                if (p == Provider.FEISHU) runCatching { ensureFeishuFolders() }.onFailure { Log.w(TAG, "CLOUD feishu folders: $it") }
                name
            }
        }
        scan = null; scanJob = null
        r.onSuccess { name ->
            info[p] = AcctInfo(AcctState.ON, name)
            Log.i(TAG, "CLOUD ${p.id} connected")
            graph.overlay = Overlay.Connected(p)
            graph.say(Win.HOME, graph.str(R.string.acct_connected_toast, graph.str(nameRes(p)), name))
            if (p == Provider.FEISHU) watchInbox()
            if (graph.homePage == HomePage.BROWSE) refresh(p)
        }.onFailure { e ->
            Log.e(TAG, "CLOUD ${p.id} sign-in failed", e)
            info[p] = AcctInfo(AcctState.OFF, note = e.message)
            if (graph.overlay is Overlay.Scan) graph.overlay = null
            graph.say(Win.HOME, graph.str(R.string.acct_failed, e.message ?: ""))
        }
    }

    fun signOut(p: Provider) {
        runCatching { saveTokens(p, null) }
        browse[p]?.let { it.stack = emptyList(); it.items = emptyList(); it.loadedFor = null; it.error = null }
        info[p] = initial(p)
        Log.i(TAG, "CLOUD ${p.id} signed out, token deleted")
        graph.say(Win.HOME, graph.str(R.string.acct_signed_out, graph.str(nameRes(p))))
    }

    /** spatialoffice://oauth/<service>?code=…&state=… (custom-scheme callbacks). Returns true when it was one. */
    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != "spatialoffice" || uri.host != "oauth") return false
        callback(uri.toString())
        return true
    }

    private fun callback(url: String) {
        val q = Http.query(url)
        for ((p, req) in pending) {
            val r = req.parseCallback(q) ?: continue
            pending.remove(p); waits.remove(p)?.cancel()
            if (pending.isEmpty()) stopLoopback()
            r.onFailure { e ->
                Log.i(TAG, "CLOUD ${p.id} declined: ${e.message}")
                info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
                graph.say(Win.HOME, graph.str(R.string.acct_cancelled))
            }.onSuccess { code -> finish(p, req, code) }
            return
        }
        Log.w(TAG, "CLOUD callback with unknown state ignored")
    }

    private fun finish(p: Provider, req: AuthRequest, code: String) {
        graph.scope.launch {
            val t = withContext(Dispatchers.IO) {
                runCatching {
                    val (type, body) = req.tokenBody(code)
                    Tokens.parse(String(raw(CloudApi.Req("POST", p.tokenUrl, type, body), null, null), Charsets.UTF_8), System.currentTimeMillis())
                }
            }
            t.onSuccess { connected(p, it) }.onFailure { e ->
                Log.e(TAG, "CLOUD ${p.id} token exchange failed", e)
                info[p] = AcctInfo(AcctState.OFF, note = e.message)
                graph.say(Win.HOME, graph.str(R.string.acct_failed, e.message ?: ""))
            }
        }
    }

    /** One-shot HTTP listener on the loopback address for services registered with an http://127.0.0.1 callback. */
    private fun listenLoopback(port: Int) {
        if (server != null) return
        val ss = runCatching { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 1000 } }
            .onFailure { Log.e(TAG, "CLOUD loopback :$port unavailable", it) }.getOrNull() ?: return
        server = ss
        Thread({
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { break }
                runCatching {
                    sock.use { s ->
                        s.soTimeout = 5000
                        val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                        val path = line.split(' ').getOrNull(1) ?: "/"
                        val ok = Http.query(path).containsKey("code")
                        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                            "<body style='font:20px sans-serif;padding:40px'>" + (if (ok) graph.str(R.string.acct_browser_done) else graph.str(R.string.acct_cancelled)) + "</body>"
                        val bytes = html.toByteArray()
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                        s.getOutputStream().flush()
                        if (path.contains("state=")) graph.scope.launch { callback("http://127.0.0.1$path") }
                    }
                }
            }
        }, "oauth-loopback").apply { isDaemon = true }.start()
    }

    private fun stopLoopback() { runCatching { server?.close() }; server = null }

    // ------------------------------------------------------------------ HTTP

    private fun raw(req: CloudApi.Req, token: String?, body: ByteArray?): ByteArray {
        val c = URL(req.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.requestMethod = req.method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        for ((k, v) in req.headers) c.setRequestProperty(k, v)
        val out = body ?: req.body?.toByteArray(Charsets.UTF_8)
        if (out != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", req.contentType ?: "application/octet-stream")
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
        }
        val code = c.responseCode
        val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code == 401) throw CloudAuthException("401")
        if (code >= 400) throw IOException("HTTP $code " + String(bytes, Charsets.UTF_8).take(300))
        if (req.url.contains("open.feishu.cn") && bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            CloudApi.feishuError(String(bytes, Charsets.UTF_8))?.let { msg ->
                if (msg.contains("(99991663)") || msg.contains("(99991668)") || msg.contains("(99991677)")) throw CloudAuthException(msg)
                throw IOException(msg)
            }
        }
        return bytes
    }

    /** POST without a token that returns the body even for HTTP 4xx (device-flow polls answer "pending" as 400). */
    private fun rawAny(req: CloudApi.Req): ByteArray {
        val c = URL(req.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.requestMethod = req.method
        val out = req.body?.toByteArray(Charsets.UTF_8)
        if (out != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", req.contentType ?: "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
        }
        val code = c.responseCode
        return (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
    }

    /** A call with the service's access token; refreshes it once when it expired. Blocking: call on IO. */
    private fun call(p: Provider, req: CloudApi.Req, body: ByteArray? = null): ByteArray {
        var t = tokens(p) ?: throw CloudAuthException("not connected")
        if (t.expiresAt < System.currentTimeMillis() + 30_000 && t.refresh != null) t = refresh(p, t)
        return try { raw(req, t.access, body) } catch (e: CloudAuthException) {
            if (t.refresh == null) { expired(p, revoked = false); throw e }
            val nt = try { refresh(p, t) } catch (e2: Exception) { expired(p, revoked = true); throw CloudAuthException("revoked") }
            raw(req, nt.access, body)
        }
    }

    private fun refresh(p: Provider, t: Tokens): Tokens {
        val reg = registration(p)
        val m = linkedMapOf("grant_type" to "refresh_token", "refresh_token" to (t.refresh ?: ""), "client_id" to reg.clientId)
        if (reg.clientSecret.isNotBlank()) m["client_secret"] = reg.clientSecret
        val (type, body) = if (p == Provider.FEISHU) "application/json" to io.embodit.spatialoffice.core.layout.Json.write(m) else "application/x-www-form-urlencoded" to Http.form(m)
        val nt = Tokens.parse(String(raw(CloudApi.Req("POST", p.tokenUrl, type, body), null, null), Charsets.UTF_8), System.currentTimeMillis())
        val keep = if (nt.refresh == null) nt.copy(refresh = t.refresh) else nt
        saveTokens(p, keep)
        return keep
    }

    private fun expired(p: Provider, revoked: Boolean) {
        graph.scope.launch {
            if (revoked) runCatching { saveTokens(p, null) }
            info[p] = AcctInfo(if (revoked) AcctState.REVOKED else AcctState.EXPIRED, info[p]?.name ?: "")
            graph.say(Win.HOME, graph.str(R.string.acct_expired_note))
        }
    }

    // ------------------------------------------------------------------ browse

    fun refresh(p: Provider) {
        val b = browse.getValue(p)
        if (!connected(p)) return
        b.loading = true; b.error = null
        val folder = b.stack.lastOrNull()
        graph.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var f = folder
                    if (p == Provider.FEISHU && f == null) {
                        val root = prefs?.getString("feishu.root", null) ?: CloudApi.parseFeishuRoot(String(call(p, CloudApi.feishuRoot()), Charsets.UTF_8)).also { prefs?.edit()?.putString("feishu.root", it)?.apply() }
                        f = CloudItem(root, "", true, 0, "")
                    }
                    CloudApi.parseList(p, String(call(p, CloudApi.list(p, f)), Charsets.UTF_8))
                }
            }
            b.loading = false
            r.onSuccess { b.items = it; b.loadedFor = folder?.id ?: "" ; Log.i(TAG, "CLOUD ${p.id} list ${it.size}") }
                .onFailure { e -> Log.e(TAG, "CLOUD ${p.id} list failed", e); b.error = if (e is CloudAuthException) graph.str(R.string.acct_expired_note) else graph.str(R.string.cloud_offline) }
        }
    }

    fun enter(p: Provider, folder: CloudItem) { browse.getValue(p).stack = browse.getValue(p).stack + folder; refresh(p) }
    fun up(p: Provider) { browse.getValue(p).let { it.stack = it.stack.dropLast(1) }; refresh(p) }

    /** Tapping a cloud file: Feishu slides go to the browser (read-only in v1); everything else opens in its editor. */
    fun open(p: Provider, f: CloudItem) {
        if (f.kind == "slides" || f.kind == "bitable" || f.kind == "mindnote" || f.kind == "doc") {
            if (f.url.isNotEmpty()) runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            graph.say(Win.HOME, graph.str(R.string.cloud_slides_ro))
            return
        }
        graph.open(refFor(p, f, browse.getValue(p).stack.lastOrNull()?.id ?: ""))
    }

    fun refFor(p: Provider, f: CloudItem, folder: String): FileRef.Cloud {
        val name = when {
            f.kind == "docx" -> f.name + ".docx"
            f.kind == "sheet" -> f.name + ".xlsx"
            else -> CloudApi.exportName(f)
        }
        return FileRef.Cloud(p.id, f.id, name, f.kind, f.mime, f.path, f.url, folder = folder)
    }

    // ------------------------------------------------------------------ Feishu folders and inbox (PRD §9.4)

    /** First connection: 「空间办公」 and 「空间办公 / 收件夹」 under My Space (reused when they already exist). Blocking. */
    private fun ensureFeishuFolders() {
        val p = Provider.FEISHU
        if (!prefs?.getString("feishu.inbox", null).isNullOrEmpty()) return
        val root = prefs?.getString("feishu.root", null) ?: CloudApi.parseFeishuRoot(String(call(p, CloudApi.feishuRoot()), Charsets.UTF_8)).also { prefs?.edit()?.putString("feishu.root", it)?.apply() }
        fun folderIn(parent: String, name: String): String {
            val items = CloudApi.parseList(p, String(call(p, CloudApi.list(p, CloudItem(parent, "", true, 0, ""))), Charsets.UTF_8))
            FeishuDrive.find(items, name)?.let { return it.id }
            return FeishuDrive.parseCreated(String(call(p, FeishuDrive.createFolder(name, parent)), Charsets.UTF_8))
        }
        val app = folderIn(root, graph.str(R.string.feishu_app_folder))
        val inbox = folderIn(app, graph.str(R.string.feishu_inbox_folder))
        prefs?.edit()?.putString("feishu.app", app)?.putString("feishu.inbox", inbox)?.apply()
        graph.scope.launch { feishuInbox = inbox }
        Log.i(TAG, "CLOUD feishu folders app=$app inbox=$inbox")
    }

    /**
     * While the app is in front, looks at the inbox every 10 s (PRD §9.4 / §14: no background sync). Something new:
     * it goes to the top of Recent and a note says 「电脑发来…」 with [Open].
     */
    private fun watchInbox() {
        if (inboxJob?.isActive == true) return
        inboxJob = graph.scope.launch {
            while (connected(Provider.FEISHU)) {
                if (graph.windows.state.values.any { it == "ON_RESUME" }) runCatching { checkInbox() }.onFailure { Log.w(TAG, "CLOUD inbox: $it") }
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    suspend fun checkInbox() {
        val p = Provider.FEISHU
        if (!connected(p)) return
        val items = withContext(Dispatchers.IO) {
            if (feishuInbox.isNullOrEmpty()) ensureFeishuFolders()
            val inbox = prefs?.getString("feishu.inbox", null) ?: return@withContext emptyList()
            CloudApi.parseList(p, String(call(p, CloudApi.list(p, CloudItem(inbox, "", true, 0, ""))), Charsets.UTF_8))
        }
        val seenRaw = prefs?.getStringSet("feishu.inboxSeen", emptySet()) ?: emptySet()
        val fresh = FeishuDrive.newArrivals(items, seenRaw)
        if (fresh.isEmpty()) return
        prefs?.edit()?.putStringSet("feishu.inboxSeen", seenRaw + fresh.map { it.id })?.apply()
        val inbox = feishuInbox ?: ""
        for (f in fresh.reversed()) {
            val ref = refFor(p, f, inbox)
            graph.recents.touch(ref, graph.str(R.string.loc_feishu) + " › " + graph.str(R.string.feishu_inbox_folder))
        }
        val first = fresh.first()
        Log.i(TAG, "CLOUD feishu inbox new=${fresh.size} first=${first.name}")
        graph.say(Win.HOME, graph.str(R.string.inbox_arrived, first.name), graph.str(R.string.open)) { open(p, first) }
    }

    // ------------------------------------------------------------------ files (blocking, on IO)

    private fun cacheFile(ref: FileRef.Cloud): File = File(app.cacheDir, "cloud/${ref.provider}/${ref.id.hashCode().toUInt()}/${ref.name}").also { it.parentFile?.mkdirs() }

    /** Bytes of a cloud file; offline, the cached copy from the last open. */
    fun read(ref: FileRef.Cloud): ByteArray {
        val p = Provider.of(ref.provider) ?: error("unknown service")
        return try {
            val bytes = when {
                p == Provider.FEISHU && ref.kind == "docx" -> {
                    val all = ArrayList<FeishuDoc.Block>()
                    var page: String? = null
                    do {
                        val (b, more) = FeishuDoc.parseBlocks(ref.id, String(call(p, FeishuDoc.blocksReq(ref.id, page)), Charsets.UTF_8))
                        all.addAll(b); page = more
                    } while (page != null)
                    feishuDocs[ref.key] = all
                    FeishuDoc.toDocx(all)
                }
                p == Provider.FEISHU && ref.kind == "sheet" -> {
                    val sid = FeishuSheet.parseFirstSheet(String(call(p, FeishuSheet.sheetsReq(ref.id)), Charsets.UTF_8)) ?: error("no sheet")
                    val values = FeishuSheet.parseValues(String(call(p, FeishuSheet.valuesReq(ref.id, sid)), Charsets.UTF_8))
                    feishuSheets[ref.key] = sid to values
                    FeishuSheet.toXlsx(values)
                }
                else -> call(p, CloudApi.download(p, ref.item()))
            }
            cacheFile(ref).writeBytes(bytes)
            versions[ref.key] = version(p, ref)
            Log.i(TAG, "CLOUD ${p.id} read ${ref.name} ${bytes.size} B version=${versions[ref.key]}")
            bytes
        } catch (e: IOException) {
            val c = cacheFile(ref)
            if (c.exists() && e !is CloudAuthException) { Log.w(TAG, "CLOUD offline, cached copy of ${ref.name}"); c.readBytes() } else throw e
        }
    }

    /** Current version stamp of a cloud file ("" when the service gave none). Blocking. */
    private fun version(p: Provider, ref: FileRef.Cloud): String =
        runCatching { CloudVersion.parse(p, ref.kind, String(call(p, CloudVersion.request(p, ref.id, ref.kind)), Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "CLOUD version of ${ref.name}: $it") }.getOrDefault("")

    /**
     * Writes back to the same file (Feishu online documents: only the changed blocks / cells). Unless [force], first
     * checks that nobody changed the cloud copy since it was opened (PRD §9.3); if someone did, throws
     * [CloudConflictException] and writes nothing, and the app asks: overwrite / save a copy / cancel.
     */
    /** Feishu copies this app uploaded this session (fallback above); the only files it may replace. */
    private val ownCopies = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Set when the last [write] saved a copy beside the original (original name to copy name); Graph shows it. */
    @Volatile var lastCopyBeside: Pair<String, String>? = null

    fun write(ref: FileRef.Cloud, bytes: ByteArray, force: Boolean = false): FileRef.Cloud {
        lastCopyBeside = null
        val p = Provider.of(ref.provider) ?: error("unknown service")
        if (ref.readOnlyCloud) throw IOException(graph.str(R.string.cloud_readonly_save))
        cacheFile(ref).writeBytes(bytes)
        if (!force) {
            val opened = versions[ref.key]
            val now = version(p, ref)
            if (CloudVersion.changed(opened, now)) { Log.i(TAG, "CLOUD ${p.id} conflict ${ref.name} $opened -> $now"); throw CloudConflictException(opened ?: "", now) }
        }
        var out = ref
        when {
            p == Provider.FEISHU && ref.kind == "docx" -> {
                val orig = feishuDocs[ref.key] ?: emptyList()
                for (op in FeishuDoc.diff(orig, bytes)) { val req = FeishuDoc.request(ref.id, op); call(p, req) }
                read(ref) // fresh block ids for the next save
            }
            p == Provider.FEISHU && ref.kind == "sheet" -> {
                val (sid, orig) = feishuSheets[ref.key] ?: error("sheet not loaded")
                FeishuSheet.diffReq(ref.id, sid, orig, bytes)?.let { call(p, it) }
                read(ref)
            }
            p == Provider.FEISHU -> {
                // upload_all with the file's own token: a new version of the same file, history kept (same as the
                // Feishu CLI's `drive +upload --file-token`). Tenants that do not take the field yet answer with an
                // error: only then fall back. v0.2.3 (user 2026-11-17 "save a copy besides"): the original stays as
                // it is, the edited file goes beside it as "<name> (空间办公 副本)" and the editor continues on the
                // copy; the app tells the user (Graph.write). v0.2.0–v0.2.2 deleted the original here.
                val inPlace = runCatching {
                    call(p, CloudApi.upload(p, ref.item()), FeishuDrive.inPlaceMultipart(ref.name, ref.folder, ref.id, bytes))
                }
                inPlace.exceptionOrNull()?.let { e ->
                    if (e is CloudAuthException || e !is IOException || !FeishuDrive.inPlaceUnsupported(e.message ?: "")) throw e
                    val fb = FeishuDrive.fallback(ref.name, ref.id, graph.settings.value.zh, ownCopies)
                    Log.w(TAG, "CLOUD feishu in-place upload not available on this tenant (${e.message}); original kept, copy ${fb.uploadName} replaceOwn=${fb.replaceOwnCopy}")
                    val token = CloudApi.parseFeishuUpload(String(call(p, CloudApi.create(p, fb.uploadName, ref.folder, bytes.size), CloudApi.feishuMultipart(fb.uploadName, ref.folder, bytes)), Charsets.UTF_8))
                    if (token.isEmpty()) throw IOException("copy upload gave no file token")
                    ownCopies.add(token)
                    // only a copy this app made itself is ever removed, never the file the user opened
                    fb.replaceOwnCopy?.let { old -> runCatching { call(p, CloudApi.feishuDelete(ref.copy(id = old).item())) }; ownCopies.remove(old) }
                    out = ref.copy(id = token, name = fb.uploadName)
                    if (fb.replaceOwnCopy == null) lastCopyBeside = ref.name to fb.uploadName
                }
            }
            else -> call(p, CloudApi.upload(p, ref.item()), bytes)
        }
        versions[out.key] = version(p, out)
        Log.i(TAG, "CLOUD ${p.id} wrote ${ref.name} ${bytes.size} B version=${versions[out.key]}")
        return out
    }

    /** Conflict → [Save a copy]: a new file beside the original, in the same folder (PRD §9.3). Returns where it went. */
    fun saveCopyBeside(ref: FileRef.Cloud, copyName: String, bytes: ByteArray): String {
        val p = Provider.of(ref.provider) ?: error("unknown service")
        when (p) {
            Provider.FEISHU -> {
                val folder = ref.folder.ifEmpty { prefs?.getString("feishu.root", null) ?: CloudApi.parseFeishuRoot(String(call(p, CloudApi.feishuRoot()), Charsets.UTF_8)) }
                call(p, CloudApi.create(p, copyName, folder, bytes.size), CloudApi.feishuMultipart(copyName, folder, bytes))
            }
            Provider.GDRIVE -> call(p, CloudApi.create(p, copyName, "", bytes.size), CloudApi.googleMultipart(copyName, bytes, ref.folder.ifEmpty { null }))
            Provider.ONEDRIVE -> call(p, CloudApi.createIn(p, copyName, ref.folder), bytes)
            Provider.DROPBOX -> call(p, CloudApi.createIn(p, copyName, ref.path.substringBeforeLast('/', "")), bytes)
        }
        Log.i(TAG, "CLOUD ${p.id} conflict copy -> $copyName")
        return graph.str(nameRes(p)) + " › " + copyName
    }

    /** Save as → a new file at the top of the first connected drive. Returns where it went. */
    fun saveCopy(name: String, bytes: ByteArray): String {
        val p = Provider.values().firstOrNull { connected(it) } ?: throw IOException(graph.str(R.string.cloud_none_connected))
        when (p) {
            Provider.FEISHU -> {
                // the app's own folder made on first connection (PRD §9.2 ⑤), else the top of My Space
                val root = prefs?.getString("feishu.app", null) ?: prefs?.getString("feishu.root", null) ?: CloudApi.parseFeishuRoot(String(call(p, CloudApi.feishuRoot()), Charsets.UTF_8))
                call(p, CloudApi.create(p, name, root, bytes.size), CloudApi.feishuMultipart(name, root, bytes))
            }
            Provider.GDRIVE -> call(p, CloudApi.create(p, name, "", bytes.size), CloudApi.googleMultipart(name, bytes))
            else -> call(p, CloudApi.create(p, name, "", bytes.size), bytes)
        }
        return graph.str(nameRes(p)) + " › " + name
    }

    companion object {
        fun nameRes(p: Provider): Int = when (p) {
            Provider.FEISHU -> R.string.loc_feishu
            Provider.GDRIVE -> R.string.loc_gdrive
            Provider.ONEDRIVE -> R.string.loc_onedrive
            Provider.DROPBOX -> R.string.loc_dropbox
        }

        fun from(context: Context): Accounts = Graph.from(context).accounts
    }
}
