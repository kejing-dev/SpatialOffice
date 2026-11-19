package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.layout.Json
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The four cloud services of PRD §9. Everything here is pure: request URLs and bodies, and the parsing of the
 * services' JSON answers. The app does the HTTP, the browser hand-off and the encrypted token storage.
 *
 * Each service needs an app registration (README, "Connecting accounts") whose client id is put in when the app is
 * built. A service without one is not offered anywhere in the app ([CloudCatalog.shown], v0.2.5); one that has it
 * shows [Connect], which opens the service's own sign-in page in the system browser ([BrowserSignIn]).
 */
enum class Provider(val id: String, val authUrl: String, val tokenUrl: String, val scopes: String) {
    FEISHU(
        "feishu", "https://accounts.feishu.cn/open-apis/authen/v1/authorize",
        "https://open.feishu.cn/open-apis/authen/v2/oauth/token",
        "drive:drive docx:document sheets:spreadsheet offline_access",
    ),
    GDRIVE(
        "gdrive", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        "https://www.googleapis.com/auth/drive openid profile",
    ),
    ONEDRIVE(
        "onedrive", "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token", "Files.ReadWrite User.Read offline_access",
    ),
    DROPBOX(
        "dropbox", "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
        "files.metadata.read files.content.read files.content.write account_info.read",
    );

    companion object {
        fun of(id: String): Provider? = values().firstOrNull { it.id == id }
    }
}

/** What the developer registered: client id (and, where the service requires it, a secret) and the callback address. */
data class AppRegistration(val clientId: String, val clientSecret: String, val redirectUri: String) {
    val registered: Boolean get() = clientId.isNotBlank() && !clientId.startsWith("REPLACE")
}

/**
 * Which services the app offers (v0.2.5, headset 2026-11-17: the Accounts page said "the developer has to register
 * the app first (set cloud.feishu.appId in local.properties…)", which means nothing to a user). A service appears in
 * Accounts, Browse and How-to only when a client id was put in at build time; the others are not mentioned at all.
 */
object CloudCatalog {
    fun shown(registrations: Map<Provider, AppRegistration>): List<Provider> = Provider.values().filter { registrations[it]?.registered == true }

    /**
     * The drive "Save as" can put a copy on: the first service this build offers that is signed in right now; null =
     * the dialog has no cloud row at all (v0.2.7, QA #7 q7-1: it always drew a grey "Save to a cloud drive · connect
     * one under Accounts & drives first" row that did nothing, in a build that has no Accounts page). A service that
     * is not offered never counts, whatever sign-in an earlier build left on the device.
     */
    fun saveAsDrive(shown: List<Provider>, connected: (Provider) -> Boolean): Provider? = shown.firstOrNull { connected(it) }

    /** A recent cloud file is listed only while its service is offered ([providerId] = [Provider.id]); otherwise its row could not open anything. */
    fun listsRecent(shown: List<Provider>, providerId: String): Boolean = shown.any { it.id == providerId }
}

/**
 * [Connect] (v0.2.5): straight to the service's own sign-in page in the system browser — authorisation code + PKCE,
 * the answer comes back through the registered callback address. [launch] hands the address to the system (the app:
 * an ACTION_VIEW intent) and says whether a browser took it. Pure, so the rule "no client id → nothing is sent,
 * client id → the browser is asked for exactly this address" is unit-tested.
 */
class BrowserSignIn(private val launch: (url: String) -> Boolean) {
    sealed class Result {
        /** The browser has the sign-in page; [request] waits for the callback. */
        data class Started(val request: AuthRequest, val url: String) : Result()
        /** No client id: nothing was built or sent. */
        object NotOffered : Result()
        /** Nothing on the device could open the page. */
        data class NoBrowser(val url: String) : Result()
    }

    fun start(provider: Provider, reg: AppRegistration): Result {
        if (!reg.registered) return Result.NotOffered
        val req = AuthRequest.create(provider, reg)
        val url = req.browserUrl()
        return if (launch(url)) Result.Started(req, url) else Result.NoBrowser(url)
    }
}

/** One OAuth 2 authorisation-code request with PKCE (RFC 7636): what goes to the browser and what comes back. */
class AuthRequest(val provider: Provider, val reg: AppRegistration, val state: String, val verifier: String) {
    val challenge: String get() = Pkce.challenge(verifier)

    fun browserUrl(): String {
        val q = LinkedHashMap<String, String>()
        q["client_id"] = reg.clientId
        q["redirect_uri"] = reg.redirectUri
        q["response_type"] = "code"
        q["state"] = state
        q["code_challenge"] = challenge
        q["code_challenge_method"] = "S256"
        when (provider) {
            Provider.DROPBOX -> { q["token_access_type"] = "offline"; q["scope"] = provider.scopes }
            Provider.GDRIVE -> { q["scope"] = provider.scopes; q["access_type"] = "offline"; q["prompt"] = "consent" }
            else -> q["scope"] = provider.scopes
        }
        return provider.authUrl + "?" + Http.form(q)
    }

    /** Form (or, for Feishu, JSON) body that trades the returned code for tokens. */
    fun tokenBody(code: String): Pair<String, String> {
        val m = LinkedHashMap<String, String>()
        m["grant_type"] = "authorization_code"
        m["code"] = code
        m["redirect_uri"] = reg.redirectUri
        m["client_id"] = reg.clientId
        m["code_verifier"] = verifier
        if (reg.clientSecret.isNotBlank()) m["client_secret"] = reg.clientSecret
        return if (provider == Provider.FEISHU) "application/json" to Json.write(m) else "application/x-www-form-urlencoded" to Http.form(m)
    }

    /** The `code` from the callback address, or an error message; null when the callback is not for this request. */
    fun parseCallback(query: Map<String, String>): Result<String>? {
        if (query["state"] != state) return null
        query["error"]?.let { return Result.failure(IllegalStateException(query["error_description"] ?: it)) }
        val code = query["code"] ?: return Result.failure(IllegalStateException("no code"))
        return Result.success(code)
    }

    companion object {
        fun create(provider: Provider, reg: AppRegistration): AuthRequest = AuthRequest(provider, reg, Pkce.random(16), Pkce.verifier())
    }
}

object Pkce {
    private val rnd = SecureRandom()
    private fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun random(bytes: Int): String = ByteArray(bytes).also { rnd.nextBytes(it) }.let { b64(it) }
    /** 43–128 characters from the unreserved set, as RFC 7636 §4.1 asks. */
    fun verifier(): String = random(48)
    fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

data class Tokens(val access: String, val refresh: String?, val expiresAt: Long) {
    fun toJson(): String = Json.write(mapOf("a" to access, "r" to refresh, "e" to expiresAt.toDouble()))

    companion object {
        fun fromJson(s: String): Tokens? = runCatching {
            val m = Json.parse(s) as Map<*, *>
            Tokens(m["a"] as String, m["r"] as? String, (m["e"] as Double).toLong())
        }.getOrNull()

        /** Token endpoint answer (Feishu wraps nothing for v2; older answers put it under "data"). */
        fun parse(body: String, now: Long): Tokens {
            var m = Json.parse(body) as? Map<*, *> ?: error("token answer is not an object")
            (m["data"] as? Map<*, *>)?.let { if (it["access_token"] != null) m = it }
            val access = m["access_token"] as? String ?: error((m["error_description"] ?: m["msg"] ?: m["error"] ?: "no access_token").toString())
            val exp = (m["expires_in"] as? Double)?.toLong() ?: 3600L
            return Tokens(access, m["refresh_token"] as? String, now + exp * 1000)
        }
    }
}

/** A folder or file in a cloud drive. [kind] for Feishu: "folder", "docx", "sheet", "slides", "file", …; for others "folder" / "file". */
data class CloudItem(
    val id: String, val name: String, val folder: Boolean, val size: Long, val modified: String,
    val kind: String = if (folder) "folder" else "file", val mime: String = "", val path: String = "", val url: String = "",
) {
    /** Google's own documents and Feishu slides open read-only. */
    val readOnly: Boolean get() = mime.startsWith("application/vnd.google-apps.") || kind == "slides"
    /** Feishu online documents and sheets: edited as such and written back block by block / cell by cell. */
    val feishuOnline: Boolean get() = kind == "docx" || kind == "sheet"
}

/** Requests and answers of the four services' file APIs. */
object CloudApi {
    data class Req(val method: String, val url: String, val contentType: String? = null, val body: String? = null, val headers: Map<String, String> = emptyMap())

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun me(p: Provider): Req = when (p) {
        Provider.FEISHU -> Req("GET", "https://open.feishu.cn/open-apis/authen/v1/user_info")
        Provider.GDRIVE -> Req("GET", "https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress)")
        Provider.ONEDRIVE -> Req("GET", "https://graph.microsoft.com/v1.0/me?\$select=displayName,userPrincipalName")
        Provider.DROPBOX -> Req("POST", "https://api.dropboxapi.com/2/users/get_current_account", "application/json", "null")
    }

    fun parseMe(p: Provider, body: String): String {
        val m = Json.parse(body) as? Map<*, *> ?: return ""
        return when (p) {
            Provider.FEISHU -> ((m["data"] as? Map<*, *>)?.let { it["name"] ?: it["en_name"] } ?: "").toString()
            Provider.GDRIVE -> ((m["user"] as? Map<*, *>)?.let { it["displayName"] ?: it["emailAddress"] } ?: "").toString()
            Provider.ONEDRIVE -> (m["displayName"] ?: m["userPrincipalName"] ?: "").toString()
            Provider.DROPBOX -> ((m["name"] as? Map<*, *>)?.get("display_name") ?: m["email"] ?: "").toString()
        }
    }

    /** Lists a folder; [folder] null = the top of the drive. Feishu's top needs [feishuRoot] first. */
    fun list(p: Provider, folder: CloudItem?): Req = when (p) {
        Provider.FEISHU -> Req("GET", "https://open.feishu.cn/open-apis/drive/v1/files?page_size=200&order_by=EditedTime&direction=DESC" + (folder?.let { "&folder_token=" + enc(it.id) } ?: ""))
        Provider.GDRIVE -> Req(
            "GET",
            "https://www.googleapis.com/drive/v3/files?pageSize=200&orderBy=folder,name&fields=" + enc("files(id,name,mimeType,size,modifiedTime,webViewLink)") +
                "&q=" + enc("'" + (folder?.id ?: "root") + "' in parents and trashed=false"),
        )
        Provider.ONEDRIVE -> Req("GET", "https://graph.microsoft.com/v1.0/me/drive/" + (folder?.let { "items/" + enc(it.id) } ?: "root") + "/children?\$top=200")
        Provider.DROPBOX -> Req("POST", "https://api.dropboxapi.com/2/files/list_folder", "application/json", Json.write(mapOf("path" to (folder?.path ?: ""), "limit" to 200)))
    }

    fun parseList(p: Provider, body: String): List<CloudItem> {
        val m = Json.parse(body) as? Map<*, *> ?: return emptyList()
        fun s(x: Any?) = x?.toString() ?: ""
        fun l(x: Any?) = when (x) { is Double -> x.toLong(); is String -> x.toLongOrNull() ?: 0L; else -> 0L }
        val out = when (p) {
            Provider.FEISHU -> ((m["data"] as? Map<*, *>)?.get("files") as? List<*> ?: emptyList<Any>()).mapNotNull { e ->
                val f = e as? Map<*, *> ?: return@mapNotNull null
                val type = s(f["type"])
                val sc = f["shortcut_info"] as? Map<*, *>
                val kind = if (type == "shortcut" && sc != null) s(sc["target_type"]) else type
                val id = if (type == "shortcut" && sc != null) s(sc["target_token"]) else s(f["token"])
                CloudItem(id, s(f["name"]), kind == "folder", 0, s(f["modified_time"]), kind, url = s(f["url"]))
            }
            Provider.GDRIVE -> ((m["files"] as? List<*>) ?: emptyList<Any>()).mapNotNull { e ->
                val f = e as? Map<*, *> ?: return@mapNotNull null
                val mime = s(f["mimeType"])
                val folder = mime == "application/vnd.google-apps.folder"
                CloudItem(s(f["id"]), s(f["name"]), folder, l(f["size"]), s(f["modifiedTime"]), if (folder) "folder" else "file", mime, url = s(f["webViewLink"]))
            }
            Provider.ONEDRIVE -> ((m["value"] as? List<*>) ?: emptyList<Any>()).mapNotNull { e ->
                val f = e as? Map<*, *> ?: return@mapNotNull null
                val folder = f["folder"] != null
                CloudItem(s(f["id"]), s(f["name"]), folder, l(f["size"]), s(f["lastModifiedDateTime"]), if (folder) "folder" else "file", url = s(f["webUrl"]))
            }
            Provider.DROPBOX -> ((m["entries"] as? List<*>) ?: emptyList<Any>()).mapNotNull { e ->
                val f = e as? Map<*, *> ?: return@mapNotNull null
                val folder = s(f[".tag"]) == "folder"
                CloudItem(s(f["id"]), s(f["name"]), folder, l(f["size"]), s(f["server_modified"]), if (folder) "folder" else "file", path = s(f["path_lower"]))
            }
        }
        return out.sortedWith(compareBy<CloudItem>({ !it.folder }, { it.name.lowercase() }))
    }

    /** Download of a plain file; Google's own documents are exported to the matching office format (read-only). */
    fun download(p: Provider, f: CloudItem): Req = when (p) {
        Provider.FEISHU -> Req("GET", "https://open.feishu.cn/open-apis/drive/v1/files/${enc(f.id)}/download")
        Provider.GDRIVE -> if (f.mime.startsWith("application/vnd.google-apps.")) Req("GET", "https://www.googleapis.com/drive/v3/files/${enc(f.id)}/export?mimeType=" + enc(exportMime(f.mime)))
        else Req("GET", "https://www.googleapis.com/drive/v3/files/${enc(f.id)}?alt=media")
        Provider.ONEDRIVE -> Req("GET", "https://graph.microsoft.com/v1.0/me/drive/items/${enc(f.id)}/content")
        Provider.DROPBOX -> Req("POST", "https://content.dropboxapi.com/2/files/download", headers = mapOf("Dropbox-API-Arg" to Json.write(mapOf("path" to f.id))))
    }

    /** Name a Google document gets when exported (so it opens in the right editor). */
    fun exportName(f: CloudItem): String = when (f.mime) {
        "application/vnd.google-apps.document" -> f.name + ".docx"
        "application/vnd.google-apps.spreadsheet" -> f.name + ".xlsx"
        "application/vnd.google-apps.presentation" -> f.name + ".pptx"
        else -> f.name
    }

    private fun exportMime(mime: String): String = when (mime) {
        "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    /** Writes new bytes into the same file (same id, a new version in the service's history). Body is the raw file. */
    fun upload(p: Provider, f: CloudItem): Req = when (p) {
        // upload_all + file_token (body: FeishuDrive.inPlaceMultipart); same file, a new version in its history
        Provider.FEISHU -> Req("POST", "https://open.feishu.cn/open-apis/drive/v1/files/upload_all", "multipart/form-data; boundary=$BOUNDARY")
        Provider.GDRIVE -> Req("PATCH", "https://www.googleapis.com/upload/drive/v3/files/${enc(f.id)}?uploadType=media", "application/octet-stream")
        Provider.ONEDRIVE -> Req("PUT", "https://graph.microsoft.com/v1.0/me/drive/items/${enc(f.id)}/content", "application/octet-stream")
        Provider.DROPBOX -> Req(
            "POST", "https://content.dropboxapi.com/2/files/upload", "application/octet-stream",
            headers = mapOf("Dropbox-API-Arg" to Json.write(mapOf("path" to f.id, "mode" to "overwrite", "mute" to true))),
        )
    }

    /** A new file called [name] at the top of the drive (Save as → cloud). Body is the raw file; Google uses [googleMultipart]. */
    fun create(p: Provider, name: String, feishuFolder: String, size: Int): Req = when (p) {
        Provider.FEISHU -> Req("POST", "https://open.feishu.cn/open-apis/drive/v1/files/upload_all", "multipart/form-data; boundary=$BOUNDARY",
            headers = mapOf("X-Fields" to Json.write(mapOf("file_name" to name, "parent_type" to "explorer", "parent_node" to feishuFolder, "size" to size))))
        Provider.GDRIVE -> Req("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", "multipart/related; boundary=$BOUNDARY")
        Provider.ONEDRIVE -> Req("PUT", "https://graph.microsoft.com/v1.0/me/drive/root:/" + enc(name).replace("+", "%20") + ":/content?@microsoft.graph.conflictBehavior=rename", "application/octet-stream")
        Provider.DROPBOX -> Req("POST", "https://content.dropboxapi.com/2/files/upload", "application/octet-stream",
            headers = mapOf("Dropbox-API-Arg" to Json.write(mapOf("path" to "/$name", "mode" to "add", "autorename" to true))))
    }

    /** A new file [name] inside [folder] (OneDrive: parent item id, "" = top; Dropbox: parent path). Body is the raw file; never replaces. */
    fun createIn(p: Provider, name: String, folder: String): Req = when (p) {
        Provider.ONEDRIVE -> Req(
            "PUT", "https://graph.microsoft.com/v1.0/me/drive/" + (if (folder.isEmpty()) "root" else "items/" + enc(folder)) + ":/" +
                enc(name).replace("+", "%20") + ":/content?@microsoft.graph.conflictBehavior=rename", "application/octet-stream",
        )
        Provider.DROPBOX -> Req("POST", "https://content.dropboxapi.com/2/files/upload", "application/octet-stream",
            headers = mapOf("Dropbox-API-Arg" to Json.write(mapOf("path" to "$folder/$name", "mode" to "add", "autorename" to true))))
        else -> create(p, name, folder, 0)
    }

    const val BOUNDARY = "spatialofficeBoundary7c1"

    /** Google multipart/related body: metadata part + file part. */
    fun googleMultipart(name: String, bytes: ByteArray, parent: String? = null): ByteArray {
        val meta: Map<String, Any> = if (parent == null) mapOf("name" to name) else mapOf("name" to name, "parents" to listOf(parent))
        val head = "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + Json.write(meta) +
            "\r\n--$BOUNDARY\r\nContent-Type: application/octet-stream\r\n\r\n"
        return head.toByteArray() + bytes + "\r\n--$BOUNDARY--\r\n".toByteArray()
    }

    /** Feishu upload_all multipart/form-data body (the fields go in the form, not a header). */
    fun feishuMultipart(name: String, folder: String, bytes: ByteArray, fileToken: String? = null): ByteArray {
        val sb = StringBuilder()
        fun field(k: String, v: String) { sb.append("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        field("file_name", name); field("parent_type", "explorer"); field("parent_node", folder); field("size", bytes.size.toString())
        // an existing file's token: the bytes become a new version of that file (in place) instead of a new file
        if (!fileToken.isNullOrEmpty()) field("file_token", fileToken)
        sb.append("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        return sb.toString().toByteArray() + bytes + "\r\n--$BOUNDARY--\r\n".toByteArray()
    }

    fun feishuRoot(): Req = Req("GET", "https://open.feishu.cn/open-apis/drive/explorer/v2/root_folder/meta")
    fun parseFeishuRoot(body: String): String = ((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("token")?.toString() ?: ""
    fun feishuDelete(f: CloudItem): Req = Req("DELETE", "https://open.feishu.cn/open-apis/drive/v1/files/${enc(f.id)}?type=file")
    fun parseFeishuUpload(body: String): String = ((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("file_token")?.toString() ?: ""

    /** Feishu answers 200 with a non-zero "code" for many errors. */
    fun feishuError(body: String): String? {
        val m = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: return null
        val code = (m["code"] as? Double)?.toInt() ?: return null
        return if (code == 0) null else "${m["msg"] ?: "error"} ($code)"
    }
}

object Http {
    fun form(m: Map<String, String>): String = m.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8").replace("+", "%20") }

    /** Query string of a callback address → map (last value wins). */
    fun query(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }
}
