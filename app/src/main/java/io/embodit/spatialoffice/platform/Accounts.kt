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

/**
 * Optional cloud accounts (PRD §9). Sign-in happens only on the service's own page in the system browser
 * (authorisation code + PKCE); the app never sees a password. Tokens are kept in EncryptedSharedPreferences and
 * deleted on sign-out. A service whose app registration is missing (README) says so and sends nothing.
 */
class Accounts(private val app: Application, private val graph: Graph) {
    val info = mutableStateMapOf<Provider, AcctInfo>()
    val browse = Provider.values().associateWith { CloudBrowse() }

    private val pending = ConcurrentHashMap<Provider, AuthRequest>()
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

    init { Provider.values().forEach { info[it] = initial(it) } }

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

    /** [Connect]: opens the service's own sign-in page in the system browser and waits for the callback. */
    fun connect(p: Provider) {
        val reg = registration(p)
        if (!reg.registered) {
            Log.i(TAG, "CLOUD ${p.id} not registered (${configKey(p)} empty)")
            graph.say(Win.HOME, graph.str(R.string.acct_need_reg_toast, graph.str(nameRes(p)), configKey(p)))
            return
        }
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
        waits.remove(p)?.cancel()
        if (pending.remove(p) != null) {
            info[p] = AcctInfo(AcctState.OFF, note = graph.str(R.string.acct_cancelled))
            if (pending.isEmpty()) stopLoopback()
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
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (type, body) = req.tokenBody(code)
                    val ans = String(raw(CloudApi.Req("POST", p.tokenUrl, type, body), null, null), Charsets.UTF_8)
                    val t = Tokens.parse(ans, System.currentTimeMillis())
                    saveTokens(p, t)
                    val name = runCatching { CloudApi.parseMe(p, String(call(p, CloudApi.me(p)), Charsets.UTF_8)) }.getOrDefault("")
                    prefs?.edit()?.putString("${p.id}.name", name)?.apply()
                    name
                }
            }
            r.onSuccess { name ->
                info[p] = AcctInfo(AcctState.ON, name)
                Log.i(TAG, "CLOUD ${p.id} connected")
                graph.say(Win.HOME, graph.str(R.string.acct_connected_toast, graph.str(nameRes(p)), name))
                if (graph.homePage == HomePage.BROWSE) refresh(p)
            }.onFailure { e ->
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
        val name = when {
            f.kind == "docx" -> f.name + ".docx"
            f.kind == "sheet" -> f.name + ".xlsx"
            else -> CloudApi.exportName(f)
        }
        graph.open(FileRef.Cloud(p.id, f.id, name, f.kind, f.mime, f.path, f.url, folder = browse.getValue(p).stack.lastOrNull()?.id ?: ""))
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
            Log.i(TAG, "CLOUD ${p.id} read ${ref.name} ${bytes.size} B")
            bytes
        } catch (e: IOException) {
            val c = cacheFile(ref)
            if (c.exists() && e !is CloudAuthException) { Log.w(TAG, "CLOUD offline, cached copy of ${ref.name}"); c.readBytes() } else throw e
        }
    }

    /** Writes back to the same file (Feishu online documents: only the changed blocks / cells). */
    fun write(ref: FileRef.Cloud, bytes: ByteArray): FileRef.Cloud {
        val p = Provider.of(ref.provider) ?: error("unknown service")
        if (ref.readOnlyCloud) throw IOException(graph.str(R.string.cloud_readonly_save))
        cacheFile(ref).writeBytes(bytes)
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
                // Drive files have no "new version" call: upload beside, then remove the old one (same folder, same name)
                val token = CloudApi.parseFeishuUpload(String(call(p, CloudApi.create(p, ref.name, ref.folder, bytes.size), CloudApi.feishuMultipart(ref.name, ref.folder, bytes)), Charsets.UTF_8))
                runCatching { call(p, CloudApi.feishuDelete(ref.item())) }
                if (token.isNotEmpty()) out = ref.copy(id = token)
            }
            else -> call(p, CloudApi.upload(p, ref.item()), bytes)
        }
        Log.i(TAG, "CLOUD ${p.id} wrote ${ref.name} ${bytes.size} B")
        return out
    }

    /** Save as → a new file at the top of the first connected drive. Returns where it went. */
    fun saveCopy(name: String, bytes: ByteArray): String {
        val p = Provider.values().firstOrNull { connected(it) } ?: throw IOException(graph.str(R.string.cloud_none_connected))
        when (p) {
            Provider.FEISHU -> {
                val root = prefs?.getString("feishu.root", null) ?: CloudApi.parseFeishuRoot(String(call(p, CloudApi.feishuRoot()), Charsets.UTF_8))
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
