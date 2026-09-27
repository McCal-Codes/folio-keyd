package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving words and shortcuts to a new phone. Runs under Robolectric only for org.json, which the plain JVM stubs out.
 *
 * The rule that matters is that an import only ever adds: the new phone's own words and shortcuts survive it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {

    private fun learned(vararg words: Pair<String, Int>) =
        Learned.decode(words.joinToString("\n") { "${it.first}:${it.second}" })

    private fun shortcuts(vararg pairs: Pair<String, String>) = Shortcuts().apply { pairs.forEach { add(it.first, it.second) } }

    private fun added(result: Backup.Result) = result as? Backup.Result.Added ?: throw AssertionError("rejected: $result")

    @Test
    fun `a backup comes back as it went`() {
        val file = Backup.export(learned("McCal" to 7, "Keyd" to 2), shortcuts("omw" to "on my way", "addr" to "12 Main St"))
        val back = added(Backup.merge(file, Learned(), Shortcuts()))
        assertEquals(7, back.learned.count("McCal"))
        assertEquals(2, back.learned.count("Keyd"))
        assertEquals("on my way", back.shortcuts.expand("omw"))
        assertEquals("12 Main St", back.shortcuts.expand("addr"))
        assertEquals(2, back.words)
        assertEquals(2, back.shortcutsAdded)
    }

    @Test
    fun `the file says what it is`() {
        val file = org.json.JSONObject(Backup.export(Learned(), Shortcuts()))
        assertEquals("keyd-backup", file.getString("format"))
        assertEquals(1, file.getInt("version"))
    }

    @Test
    fun `importing adds and never removes or replaces`() {
        val file = Backup.export(learned("McCal" to 3, "Pittsburgh" to 9), shortcuts("omw" to "on my way!!", "brb" to "be right back"))
        val here = learned("McCal" to 5, "Folio" to 4)
        val mine = shortcuts("omw" to "on my way")
        val merged = added(Backup.merge(file, here, mine))
        // Kept: everything that was here.
        assertEquals(5, merged.learned.count("McCal"))
        assertEquals(4, merged.learned.count("Folio"))
        assertEquals("on my way", merged.shortcuts.expand("omw"))
        // Added: only what was missing.
        assertEquals(9, merged.learned.count("Pittsburgh"))
        assertEquals("be right back", merged.shortcuts.expand("brb"))
        assertEquals(1, merged.words)
        assertEquals(1, merged.shortcutsAdded)
    }

    @Test
    fun `a file cannot teach a word typing never would`() {
        val file = """{"format":"keyd-backup","version":1,"words":{"pa55word":3,"ok":2,"line\nbreak":1,"Oakland":2},
            |"shortcuts":{"x\ty":"z","tab":"a\tb","fine":"all good"}}""".trimMargin()
        val merged = added(Backup.merge(file, Learned(), Shortcuts()))
        assertEquals(listOf("Oakland"), merged.learned.all())
        assertEquals(listOf("fine" to "all good"), merged.shortcuts.all())
    }

    @Test
    fun `anything that is not a Keyd backup is turned away`() {
        for (text in listOf("", "not json", "[]", """{"format":"gboard","version":1}""", """{"version":1}""")) {
            assertEquals(text, Backup.Result.Rejected(Backup.Reason.NOT_A_BACKUP), Backup.merge(text, Learned(), Shortcuts()))
        }
        assertEquals(
            Backup.Result.Rejected(Backup.Reason.NEWER),
            Backup.merge("""{"format":"keyd-backup","version":2}""", Learned(), Shortcuts()),
        )
    }

    @Test
    fun `the suggested file name has the date`() {
        assertTrue(Backup.fileName(java.time.LocalDate.of(2026, 9, 26)) == "keyd-backup-2026-09-26.json")
    }
}
