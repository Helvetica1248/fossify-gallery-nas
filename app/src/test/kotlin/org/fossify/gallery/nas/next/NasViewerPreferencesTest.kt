package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.external.NasViewerKind
import org.fossify.gallery.nas.external.NasViewerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NasViewerPreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun choicesPersistSeparatelyAndCanBeResetAfterRestart() {
        val file = File(temp.root, "settings/viewers.bin")
        NasViewerPreferences(file).apply {
            set(NasViewerKind.PDF, "fixture.pdf/Viewer")
            set(NasViewerKind.VIDEO, "fixture.video/Player")
        }
        NasViewerPreferences(file).apply {
            assertEquals("fixture.pdf/Viewer", get(NasViewerKind.PDF))
            assertEquals("fixture.video/Player", get(NasViewerKind.VIDEO))
            set(NasViewerKind.VIDEO, null)
        }
        val reopened = NasViewerPreferences(file)
        assertNull(reopened.get(NasViewerKind.VIDEO))
        assertEquals("fixture.pdf/Viewer", reopened.get(NasViewerKind.PDF))
        assertEquals(NasViewerKind.VIDEO, NasViewerKind.forMime("video/x-matroska"))
        assertEquals(NasViewerKind.VIDEO, NasViewerKind.forMime("video/mp4"))
    }

    @Test fun malformedPreferencesCanBeReplacedWithoutAffectingOtherFiles() {
        val file = temp.newFile("viewers.bin").apply { writeText("invalid") }
        val unrelated = temp.newFile("sources.bin").apply { writeText("untouched") }
        val store = NasViewerPreferences(file)
        assertTrue(runCatching { store.get(NasViewerKind.PDF) }.isFailure)
        store.set(NasViewerKind.PDF, "fixture.pdf/Viewer")
        assertEquals("fixture.pdf/Viewer", store.get(NasViewerKind.PDF))
        assertEquals("untouched", unrelated.readText())
        assertTrue(temp.root.walk().none { it.extension == "tmp" })
    }

    @Test fun staleAppCleanupCannotEraseANewerChoice() {
        val store = NasViewerPreferences(File(temp.root, "viewers.bin"))
        store.set(NasViewerKind.VIDEO, "fixture.old/Player")
        store.set(NasViewerKind.VIDEO, "fixture.new/Player")
        store.clearIf(NasViewerKind.VIDEO, "fixture.old/Player")
        assertEquals("fixture.new/Player", store.get(NasViewerKind.VIDEO))
        store.clearIf(NasViewerKind.VIDEO, "fixture.new/Player")
        assertNull(store.get(NasViewerKind.VIDEO))
    }
}
