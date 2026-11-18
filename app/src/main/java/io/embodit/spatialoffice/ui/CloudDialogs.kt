package io.embodit.spatialoffice.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.AlertDialog
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.cloud.DeviceAuth
import io.embodit.spatialoffice.core.cloud.Provider
import io.embodit.spatialoffice.platform.Accounts
import io.embodit.spatialoffice.platform.Graph
import io.embodit.spatialoffice.platform.Overlay
import io.embodit.spatialoffice.platform.Win

/** Cloud dialogs of the home window (PRD §9.2 ②, ③a, ⑤) and of the editors (§9.3 conflict). */
@Composable
fun CloudOverlays(graph: Graph, win: Win) {
    val o = graph.overlay ?: return
    if (o.win != win) return
    when (o) {
        is Overlay.Connect -> ConnectDialog(graph, o.provider)
        is Overlay.Scan -> ScanDialog(graph, o.provider)
        is Overlay.Connected -> ConnectedDialog(graph, o.provider)
        is Overlay.Conflict -> ConflictDialog(graph, win)
        is Overlay.CopyBeside -> CopyBesideDialog(graph, o.original, o.copy)
        else -> {}
    }
}

/** One way of signing in: a whole-row button (≥ 72 dp) with an icon, a name and a line saying what happens. */
@Composable
private fun WayRow(icon: Int, title: String, sub: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillSecondary)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.alpha(0.55f))
            .semantics { contentDescription = tag }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleMedium, weight = FontWeight.Bold, maxLines = 2)
            Txt(sub, "$tag/sub", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        }
    }
}

/** ② Choose a way. Unregistered services show the same choice greyed out, with what the developer must register. */
@Composable
private fun ConnectDialog(graph: Graph, p: Provider) {
    val a = graph.accounts
    val name = tr(Accounts.nameRes(p))
    val registered = a.registration(p).registered
    val scan = DeviceAuth.supported(p)
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.connect_title, name), "connect/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!registered) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_info_circle), contentDescription = null, modifier = Modifier.size(22.dp))
                        Txt(tr(R.string.connect_need_reg, a.configKey(p)), "connect/need-reg", Modifier.padding(start = 12.dp), style = PicoTheme.typography.bodyLarge, maxLines = 4)
                    }
                }
                WayRow(
                    R.drawable.ic_qr, tr(R.string.way_scan), if (scan) tr(R.string.way_scan_sub) else tr(R.string.way_scan_none, name),
                    "connect/scan", enabled = registered && scan,
                ) { a.connectByScan(p) }
                WayRow(R.drawable.ic_globe, tr(R.string.way_browser), tr(R.string.way_browser_sub), "connect/browser", enabled = registered) { a.connectInBrowser(p) }
                Txt(tr(R.string.connect_no_password), "connect/no-password", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            }
        },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton(tr(if (registered) R.string.cancel else R.string.got_it), "connect/close", onClick = { graph.overlay = null })
            }
        },
    )
}

/** QR code of [text] as a bitmap, [px] square, black on white with a quiet zone. */
fun qrBitmap(text: String, px: Int = 360): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/** ③a QR code + short code + waiting line + time left; [Use the browser] and [Cancel]. */
@Composable
private fun ScanDialog(graph: Graph, p: Provider) {
    val a = graph.accounts
    val st = a.scan
    val name = tr(Accounts.nameRes(p))
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st?.code) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    AlertDialog(
        onDismissRequest = { a.cancelScan() },
        title = { Txt(tr(R.string.scan_title, name), "scan/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = {
            // the dialog is narrow: QR code on top, the words under it at full width
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val code = st?.code
                Box(Modifier.size(212.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    if (code != null) {
                        val bmp = remember(code.qrText) { qrBitmap(code.qrText) }
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = tr(R.string.scan_qr_desc), filterQuality = FilterQuality.None, modifier = Modifier.size(196.dp))
                    } else Txt(tr(if (st?.error != null) R.string.scan_error_short else R.string.cloud_loading), "scan/loading", style = PicoTheme.typography.bodyLarge, color = Ink.Text, maxLines = 2)
                }
                if (st?.error != null) Txt(st.error, "scan/error", Modifier.fillMaxWidth(), style = PicoTheme.typography.bodyLarge, maxLines = 6)
                else {
                    Txt(tr(R.string.scan_step1, name), "scan/step1", Modifier.fillMaxWidth(), style = PicoTheme.typography.bodyLarge, maxLines = 4)
                    if (code != null) {
                        if (code.userCode.isNotEmpty()) Txt(code.userCode, "scan/code", style = PicoTheme.typography.headlineMedium, weight = FontWeight.Bold)
                        Txt(tr(R.string.scan_or_open, code.verifyUrl), "scan/url", Modifier.fillMaxWidth(), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 5)
                        val left = ((code.expiresAt - now) / 1000).coerceAtLeast(0)
                        Txt(tr(R.string.scan_waiting, left / 60, left % 60), "scan/waiting", Modifier.fillMaxWidth(), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
                    }
                }
            }
        },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.scan_other_way), "scan/other", onClick = { a.cancelScan(); graph.overlay = Overlay.Connect(p) })
                SecondaryButton(tr(R.string.cancel), "scan/cancel", onClick = { a.cancelScan() })
            }
        },
    )
}

/** ⑤ Connected: who, where the sign-in is kept, and (Feishu) the default folder and the inbox. */
@Composable
private fun ConnectedDialog(graph: Graph, p: Provider) {
    val a = graph.accounts
    val name = tr(Accounts.nameRes(p))
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.acct_on, a.info[p]?.name?.ifEmpty { name } ?: name), "connected/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(tr(R.string.connected_store), "connected/store", style = PicoTheme.typography.bodyLarge, maxLines = 3)
                if (p == Provider.FEISHU) {
                    Txt(tr(R.string.connected_folder, tr(R.string.feishu_app_folder)), "connected/folder", style = PicoTheme.typography.bodyLarge, maxLines = 3)
                    Txt(tr(R.string.connected_inbox, tr(R.string.feishu_app_folder), tr(R.string.feishu_inbox_folder)), "connected/inbox", style = PicoTheme.typography.bodyLarge, maxLines = 4)
                }
            }
        },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(tr(R.string.done), "connected/done", onClick = { graph.overlay = null })
            }
        },
    )
}

/** §9.3: the cloud copy changed since it was opened. Never overwrites quietly. */
@Composable
private fun CopyBesideDialog(graph: Graph, original: String, copy: String) {
    AlertDialog(
        onDismissRequest = { graph.overlay = null },
        title = { Txt(tr(R.string.copy_beside_title), "copybeside/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 2) },
        content = { Txt(tr(R.string.copy_beside_msg, original, copy), "copybeside/msg", style = PicoTheme.typography.bodyLarge, maxLines = 6) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.got_it), "copybeside/ok", onClick = { graph.overlay = null })
            }
        },
    )
}

@Composable
private fun ConflictDialog(graph: Graph, win: Win) {
    val s = graph.session(win) ?: return
    AlertDialog(
        onDismissRequest = { graph.resolveConflict(win, "cancel") },
        title = { Txt(tr(R.string.conflict_title, s.ref.name), "conflict/title", style = PicoTheme.typography.headlineSmall, weight = FontWeight.Bold, maxLines = 3) },
        content = { Txt(tr(R.string.conflict_msg), "conflict/msg", style = PicoTheme.typography.bodyLarge, maxLines = 5) },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "conflict/cancel", onClick = { graph.resolveConflict(win, "cancel") })
                SecondaryButton(tr(R.string.conflict_copy), "conflict/copy", onClick = { graph.resolveConflict(win, "copy") })
                SecondaryButton(tr(R.string.conflict_overwrite), "conflict/overwrite", danger = true, onClick = { graph.resolveConflict(win, "overwrite") })
            }
        },
    )
}
