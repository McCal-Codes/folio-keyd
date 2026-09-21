package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewTest {
    /** The real file, found from the module or the repository root, whichever the test runs in. */
    private val changelog = listOf(File("../CHANGELOG.md"), File("CHANGELOG.md")).first { it.isFile }.readText()

    @Test fun theChangelogHasASectionForThisVersion() {
        val version = Regex("""val keysVersion = "(.+)"""").find(
            listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText(),
        )!!.groupValues[1]
        val notes = WhatsNew.parse(changelog)
        assertTrue("CHANGELOG.md needs a [$version] section", notes.any { it.version == WhatsNew.releaseVersion(version) })
    }

    @Test fun everyFeatureHasATitleShortEnoughForOneLine() {
        WhatsNew.parse(changelog).forEach { release ->
            release.sections.filter { it.first == "Added" }.flatMap { it.second }.map(WhatsNew::split).forEach { note ->
                assertTrue("${release.version}: \"$note\" has no bold title", note.title != null)
                assertTrue("${release.version}: \"${note.title}\" is over 40 characters", note.title!!.length <= 40)
            }
        }
    }

    @Test fun splitReadsTheBoldTitle() {
        assertEquals(NoteItem("Split for the fold", "On a book fold it splits."), WhatsNew.split("**Split for the fold:** on a book fold it splits."))
        assertNull(WhatsNew.split("The gear opens Settings.").title)
    }

    @Test fun anUnreleasedSectionHasNoDateToShow() {
        val notes = WhatsNew.parse("## [0.2.0] - Unreleased\n### Added\n- **A:** b\n## [0.1.0] - 2026-09-21\n### Added\n- **C:** d\n")
        assertEquals(listOf("0.2.0", "0.1.0"), notes.map { it.version })
        assertEquals("2026-09-21", notes[1].date)
    }
}
