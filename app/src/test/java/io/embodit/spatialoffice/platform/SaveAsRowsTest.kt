package io.embodit.spatialoffice.platform

import io.embodit.spatialoffice.core.cloud.AppRegistration
import io.embodit.spatialoffice.core.cloud.CloudCatalog
import io.embodit.spatialoffice.core.cloud.Provider
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.files.FileKind
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.xlsx.XlsxWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA #7 q7-1 (v0.2.6): the Save-as dialog of all three editors always drew a grey "Save to a cloud drive · connect one
 * under Accounts & drives first" row that did nothing, in a build that has no Accounts page. The dialog now draws
 * exactly [Exporter.rows]; these tests read that list for a document, a sheet and a deck.
 */
class SaveAsRowsTest {
    private fun doc() = DocSession(FileRef.Path(File("t.docx")), FileKind.DOC, DocxDocument.blank().save(), File("build/tmp"))
    private fun sheet() = SheetSession(FileRef.Path(File("t.xlsx")), FileKind.SHEET, XlsxWorkbook.blank().save())
    private fun deck() = DeckSession(FileRef.Path(File("t.pptx")), PptxDeck.blank().save())
    private fun editors(): List<Session> = listOf(doc(), sheet(), deck())

    /** What this release is built with: no client id for any service. */
    private val noIds = Provider.values().associateWith { AppRegistration("", "", "http://127.0.0.1:53682/callback") }
    private val feishuId = noIds + (Provider.FEISHU to AppRegistration("cli_a1b2c3", "", "http://127.0.0.1:53682/callback"))

    @Test fun withoutACloudSetupNoEditorsSaveAsHasACloudRow() {
        val shown = CloudCatalog.shown(noIds)
        assertEquals(emptyList<Provider>(), shown)
        // even with a sign-in an earlier build left on the device: a service that is not offered never counts
        for (signedIn in listOf(emptySet(), setOf(Provider.FEISHU), Provider.values().toSet())) {
            val drive = CloudCatalog.saveAsDrive(shown) { it in signedIn }
            assertNull("no drive with $signedIn", drive)
            for (s in editors()) {
                val rows = Exporter.rows(s, drive)
                assertFalse("${s.kind}: $rows", SaveAsTarget.CLOUD in rows)
                assertEquals("${s.kind}: the local targets, nothing else", Exporter.targets(s), rows)
            }
        }
        assertEquals(listOf(SaveAsTarget.COPY, SaveAsTarget.PDF, SaveAsTarget.TXT), Exporter.rows(doc(), null))
        assertEquals(listOf(SaveAsTarget.COPY, SaveAsTarget.CSV, SaveAsTarget.PDF), Exporter.rows(sheet(), null))
        assertEquals(listOf(SaveAsTarget.COPY, SaveAsTarget.PDF, SaveAsTarget.PNG), Exporter.rows(deck(), null))
    }

    @Test fun aDriveThatIsOfferedButNotSignedInGivesNoRowEither() {
        // v0.2.6 drew the row greyed out with "connect one first"; a row that cannot be picked is not drawn at all
        val shown = CloudCatalog.shown(feishuId)
        assertEquals(listOf(Provider.FEISHU), shown)
        val drive = CloudCatalog.saveAsDrive(shown) { false }
        assertNull(drive)
        for (s in editors()) assertFalse(SaveAsTarget.CLOUD in Exporter.rows(s, drive))
        // signed in to a service this build does not offer: still nothing
        assertNull(CloudCatalog.saveAsDrive(shown) { it == Provider.DROPBOX })
    }

    @Test fun aSignedInDriveGivesOneCloudRowLastAndItNamesThatDrive() {
        val shown = CloudCatalog.shown(feishuId + (Provider.DROPBOX to AppRegistration("key", "", "http://localhost:53682/callback")))
        assertEquals(Provider.DROPBOX, CloudCatalog.saveAsDrive(shown) { it == Provider.DROPBOX })
        val drive = CloudCatalog.saveAsDrive(shown) { true }
        assertEquals("the first offered one", Provider.FEISHU, drive)
        for (s in editors()) {
            val rows = Exporter.rows(s, drive)
            assertEquals(1, rows.count { it == SaveAsTarget.CLOUD })
            assertEquals(SaveAsTarget.CLOUD, rows.last())
            assertEquals(Exporter.targets(s), rows.dropLast(1))
        }
    }

    @Test fun recentCloudFilesAreListedOnlyWhileTheirServiceIsOffered() {
        assertFalse(CloudCatalog.listsRecent(CloudCatalog.shown(noIds), "feishu"))
        assertTrue(CloudCatalog.listsRecent(CloudCatalog.shown(feishuId), Provider.FEISHU.id))
        assertFalse(CloudCatalog.listsRecent(CloudCatalog.shown(feishuId), Provider.DROPBOX.id))
    }

    // ------------------------------------------------------------------ no text points at a page that is not there

    private fun strings(dir: String): Map<String, String> {
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.exists() }
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun theDeadRowsStringsAreGoneInBothLanguages() {
        for (dir in listOf("values", "values-zh", "values-en")) {
            val s = strings(dir)
            assertTrue("$dir was read", s.size > 400)
            for (k in listOf("sa_cloud", "sa_cloud_sub", "cloud_none_connected")) assertFalse("$dir still has $k", k in s)
        }
    }

    @Test fun onlyTextShownWithADriveOfferedNamesTheAccountsPage() {
        // these are drawn only under `accounts.shown.isNotEmpty()`: the navigation item and the page's own header,
        // the empty cloud group of Recent, and the "Connect a cloud drive" line of How-to
        val gated = setOf("nav_account", "cloud_none", "how_to_cloud_keys")
        for (dir in listOf("values-zh", "values-en")) {
            val s = strings(dir)
            val page = s.getValue("nav_account")
            val naming = s.filter { (_, text) -> page in text }.keys
            assertEquals("$dir: strings that send the user to \"$page\"", gated, naming)
        }
    }
}
