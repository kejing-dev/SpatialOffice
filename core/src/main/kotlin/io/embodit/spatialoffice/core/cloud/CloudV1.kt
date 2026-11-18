package io.embodit.spatialoffice.core.cloud

import io.embodit.spatialoffice.core.layout.Json
import java.net.URLEncoder

/**
 * v1 step four of PRD §9 that v0.2.0 left out: sign-in by scanning a code (device authorisation, RFC 8628), the
 * "someone else changed the cloud file" check before saving (§9.3), the Feishu inbox (§9.4) and writing a Feishu
 * Drive file back in place (a new version of the same file instead of upload + delete). Pure requests and parsing;
 * the app does the HTTP.
 */
object DeviceAuth {
    /**
     * Which services offer the scan-a-code way for the permissions we need (PRD §14 "每个云盘支持哪种授权方式"):
     *  - Feishu: yes (accounts.feishu.cn/oauth/v1/device_authorization; the Feishu CLI signs in this way);
     *  - OneDrive: yes (Microsoft identity platform device code flow, public client);
     *  - Google Drive: no — the device flow only allows drive.file / drive.appdata, not browsing the whole drive;
     *  - Dropbox: no device flow.
     */
    fun supported(p: Provider): Boolean = p == Provider.FEISHU || p == Provider.ONEDRIVE

    fun startUrl(p: Provider): String = when (p) {
        Provider.FEISHU -> "https://accounts.feishu.cn/oauth/v1/device_authorization"
        Provider.ONEDRIVE -> "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
        else -> error("${p.id} has no device authorisation")
    }

    /** Form body asking for a device code. */
    fun startBody(p: Provider, reg: AppRegistration): String {
        val m = linkedMapOf("client_id" to reg.clientId, "scope" to p.scopes)
        if (p == Provider.FEISHU && reg.clientSecret.isNotBlank()) m["client_secret"] = reg.clientSecret
        return Http.form(m)
    }

    data class Code(
        val deviceCode: String, val userCode: String, val verifyUrl: String, val verifyUrlComplete: String?,
        val expiresAt: Long, val intervalMs: Long,
    ) {
        /** What the QR code holds: the page with the code already filled in when the service gives one. */
        val qrText: String get() = verifyUrlComplete ?: verifyUrl
    }

    fun parseStart(body: String, now: Long): Code {
        var m = Json.parse(body) as? Map<*, *> ?: error("device answer is not an object")
        (m["data"] as? Map<*, *>)?.let { if (it["device_code"] != null) m = it }
        val dc = m["device_code"] as? String ?: error((m["error_description"] ?: m["msg"] ?: m["error"] ?: "no device_code").toString())
        val uri = (m["verification_uri"] ?: m["verification_url"])?.toString() ?: error("no verification address")
        val exp = (m["expires_in"] as? Double)?.toLong() ?: 600L
        val iv = (m["interval"] as? Double)?.toLong() ?: 5L
        return Code(dc, (m["user_code"] ?: "").toString(), uri, m["verification_uri_complete"]?.toString(), now + exp * 1000, iv.coerceAtLeast(1) * 1000)
    }

    /** Body of one poll at the token address: (content type, body). Feishu's v2 token endpoint takes JSON. */
    fun pollBody(p: Provider, reg: AppRegistration, code: Code): Pair<String, String> {
        val m = linkedMapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to code.deviceCode, "client_id" to reg.clientId)
        if (p == Provider.FEISHU && reg.clientSecret.isNotBlank()) m["client_secret"] = reg.clientSecret
        return if (p == Provider.FEISHU) "application/json" to Json.write(m) else "application/x-www-form-urlencoded" to Http.form(m)
    }

    sealed class Poll {
        object Pending : Poll()
        /** The service asks us to wait longer between polls (RFC 8628 §3.5). */
        object SlowDown : Poll()
        data class Done(val tokens: Tokens) : Poll()
        /** Declined, or the code ran out: back to choosing a way (PRD §9.2 "拒绝或超时"). */
        data class Ended(val why: String) : Poll()
    }

    /** One poll answer (HTTP errors included: the device flow reports "pending" as a 400 with an error code). */
    fun parsePoll(body: String, now: Long): Poll {
        val m = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: return Poll.Ended("bad answer")
        val inner = (m["data"] as? Map<*, *>)
        if ((inner ?: m)["access_token"] != null) return Poll.Done(Tokens.parse(body, now))
        val err = (m["error"] ?: inner?.get("error"))?.toString() ?: ""
        return when (err) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "" -> Poll.Ended((m["msg"] ?: "no token").toString())
            else -> Poll.Ended(err)
        }
    }
}

/**
 * Cloud version stamps for the "changed since I opened it" check (PRD §9.3 / §9.5): read when the file is opened,
 * read again just before saving; a different stamp means someone else saved in between.
 */
object CloudVersion {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** [kind]: the Feishu kind ("docx", "sheet", "file", …); ignored by the others. */
    fun request(p: Provider, id: String, kind: String): CloudApi.Req = when {
        p == Provider.FEISHU && kind == "docx" -> CloudApi.Req("GET", "https://open.feishu.cn/open-apis/docx/v1/documents/${enc(id)}")
        p == Provider.FEISHU && kind == "sheet" -> CloudApi.Req("GET", "https://open.feishu.cn/open-apis/sheets/v2/spreadsheets/${enc(id)}/metainfo")
        p == Provider.FEISHU -> CloudApi.Req(
            "POST", "https://open.feishu.cn/open-apis/drive/v1/metas/batch_query", "application/json",
            Json.write(mapOf("request_docs" to listOf(mapOf("doc_token" to id, "doc_type" to "file")))),
        )
        p == Provider.GDRIVE -> CloudApi.Req("GET", "https://www.googleapis.com/drive/v3/files/${enc(id)}?fields=version,modifiedTime")
        p == Provider.ONEDRIVE -> CloudApi.Req("GET", "https://graph.microsoft.com/v1.0/me/drive/items/${enc(id)}?\$select=cTag,eTag,lastModifiedDateTime")
        else -> CloudApi.Req("POST", "https://api.dropboxapi.com/2/files/get_metadata", "application/json", Json.write(mapOf("path" to id)))
    }

    /** The stamp in the answer ("" when the service did not give one: then there is nothing to compare). */
    fun parse(p: Provider, kind: String, body: String): String {
        val m = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: return ""
        fun num(x: Any?): String = when (x) { is Double -> x.toLong().toString(); null -> ""; else -> x.toString() }
        val data = m["data"] as? Map<*, *>
        return when {
            p == Provider.FEISHU && kind == "docx" -> num((data?.get("document") as? Map<*, *>)?.get("revision_id"))
            p == Provider.FEISHU && kind == "sheet" -> num((data?.get("properties") as? Map<*, *>)?.get("revision"))
            p == Provider.FEISHU -> num(((data?.get("metas") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("latest_modify_time"))
            p == Provider.GDRIVE -> num(m["version"]).ifEmpty { num(m["modifiedTime"]) }
            p == Provider.ONEDRIVE -> num(m["cTag"]).ifEmpty { num(m["eTag"]) }
            else -> num(m["rev"])
        }
    }

    /** True when the file was changed elsewhere since [opened]; unknown stamps never block a save. */
    fun changed(opened: String?, now: String): Boolean = !opened.isNullOrEmpty() && now.isNotEmpty() && opened != now
}

/** Thrown before writing when the cloud copy changed since it was opened; the app asks: overwrite / save a copy / cancel. */
class CloudConflictException(val opened: String, val now: String) : java.io.IOException("changed in the cloud ($opened → $now)")

/** Feishu Drive: app folders, inbox, in-place upload. */
object FeishuDrive {
    /** Creates folder [name] inside [parent] (POST drive/v1/files/create_folder). */
    fun createFolder(name: String, parent: String): CloudApi.Req = CloudApi.Req(
        "POST", "https://open.feishu.cn/open-apis/drive/v1/files/create_folder", "application/json",
        Json.write(mapOf("name" to name, "folder_token" to parent)),
    )

    fun parseCreated(body: String): String = ((Json.parse(body) as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("token")?.toString() ?: ""

    /** The folder called [name] among [items], if there is one. */
    fun find(items: List<CloudItem>, name: String): CloudItem? = items.firstOrNull { it.folder && it.name == name }

    /**
     * upload_all with `file_token` writes new bytes into the existing Drive file (a new version in its history; the
     * Feishu CLI's `drive +upload --file-token` sends exactly this). Rolling out per tenant: where the field is not
     * known yet the service answers with an error code, see [inPlaceUnsupported].
     */
    fun inPlaceMultipart(name: String, folder: String, fileToken: String, bytes: ByteArray): ByteArray =
        CloudApi.feishuMultipart(name, folder, bytes, fileToken)

    /** Feishu error codes meaning "this tenant cannot overwrite by file_token yet" (parameter not accepted). */
    fun inPlaceUnsupported(message: String): Boolean =
        listOf("(1061002)", "(1061001)", "(99992402)", "file_token").any { message.contains(it) }

    /**
     * What to do when the in-place upload is refused ([inPlaceUnsupported]); v0.2.3, user 2026-11-17 "save a copy
     * besides". The original is never deleted or changed: the edited file is uploaded as a new file
     * [uploadName] in the same folder and the editor goes on with that copy. Only when the file being saved is itself
     * a copy this app uploaded earlier in this session ([ownCopies]) is that earlier copy replaced ([replaceOwnCopy]),
     * so repeated saves do not pile up copies; nothing else is ever removed.
     */
    data class Fallback(val uploadName: String, val replaceOwnCopy: String?)

    fun fallback(name: String, fileToken: String, zh: Boolean, ownCopies: Set<String>): Fallback =
        if (fileToken.isNotEmpty() && fileToken in ownCopies) Fallback(name, fileToken)
        else Fallback(io.embodit.spatialoffice.core.files.Naming.appCopyName(name, zh), null)

    /** Items in the inbox that were not there before ([seen] = ids already announced). */
    fun newArrivals(items: List<CloudItem>, seen: Set<String>): List<CloudItem> = items.filter { !it.folder && it.id !in seen }
}
