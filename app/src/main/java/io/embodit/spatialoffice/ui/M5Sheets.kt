package io.embodit.spatialoffice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.Sheet
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.platform.DeckSession
import io.embodit.spatialoffice.platform.DocSession
import io.embodit.spatialoffice.platform.Graph

/**
 * M5 d31: [Insert ▸ Header & footer…] (also a tap on the grey line at the top / bottom of the paper). Two text boxes;
 * the same header and footer on every page. A header or footer the file built from pictures or fields is shown, not changed.
 */
@Composable
fun HeaderFooterSheet(graph: Graph, s: DocSession) {
    var header by remember(s.tag) { mutableStateOf(s.headerText() ?: "") }
    var footer by remember(s.tag) { mutableStateOf(s.footerText() ?: "") }
    val canHeader = s.headerEditable; val canFooter = s.footerEditable
    val close = { graph.overlay = null }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.hf_title), "hf/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.cancel), "hf/cancel", onClick = close)
                PrimaryButton(tr(R.string.ok_good), "hf/ok", enabled = !s.readOnly && (canHeader || canFooter), onClick = {
                    val done = s.setHeaderFooter(if (canHeader) header.trim() else null, if (canFooter) footer.trim() else null)
                    close()
                    if (done) graph.say(s, graph.str(R.string.hf_saved), graph.str(R.string.undo_short)) { s.undo() }
                })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canHeader) LabeledField(tr(R.string.hf_header), header, "hf/header") { header = it.replace("\n", " ").take(200) }
            if (canFooter) LabeledField(tr(R.string.hf_footer), footer, "hf/footer") { footer = it.replace("\n", " ").take(200) }
            if (!canHeader || !canFooter) Txt(tr(R.string.hf_readonly), "hf/readonly", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            Txt(tr(R.string.hf_hint), "hf/hint", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
    }
}

/**
 * M5 p32: [Slide ▸ Slide numbers & footer…]: number on / off, footer on / off with its text, not on the title slide;
 * [This slide] or [All slides]. Controls: close, three switches, one text box, two buttons = 7.
 */
@Composable
fun SlideFooterSheet(graph: Graph, s: DeckSession) {
    val now = remember(s.tag, s.current) { s.footerInfo() }
    var number by remember(s.tag, s.current) { mutableStateOf(now.number) }
    var footer by remember(s.tag, s.current) { mutableStateOf(now.footer) }
    var text by remember(s.tag, s.current) { mutableStateOf(now.text) }
    var notTitle by remember(s.tag) { mutableStateOf(true) }
    val close = { graph.overlay = null }
    fun apply(all: Boolean) {
        val n = s.setFooter(all, number, footer && text.isNotBlank(), text.trim(), notTitle)
        close()
        if (n > 0) graph.say(s, graph.str(R.string.snf_done, n), graph.str(R.string.undo_short)) { s.undo() }
    }
    Sheet(
        modifier = Modifier.width(680.dp),
        onDismissRequest = close,
        leadingAction = { SheetClose(close) },
        title = { Txt(tr(R.string.snf_title), "snf/title", style = PicoTheme.typography.titleLarge, weight = FontWeight.Bold) },
        bottom = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SecondaryButton(tr(R.string.snf_this), "snf/this", enabled = !s.readOnly, onClick = { apply(false) })
                PrimaryButton(tr(R.string.snf_all), "snf/all", enabled = !s.readOnly, onClick = { apply(true) })
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckRow(tr(R.string.snf_number), number, "snf/number") { number = it }
            CheckRow(tr(R.string.snf_footer), footer, "snf/footer") { footer = it }
            if (footer) LabeledField(tr(R.string.snf_footer), text, "snf/text") { text = it.replace("\n", " ").take(120) }
            CheckRow(tr(R.string.snf_not_title), notTitle, "snf/not-title") { notTitle = it }
        }
    }
}
