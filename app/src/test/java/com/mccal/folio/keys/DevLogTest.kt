package com.mccal.folio.keys

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevLogTest {
    private val at = LocalDateTime.of(2026, 9, 21, 16, 2, 5)

    @Test fun anEventIsItsNameAndNumbers() {
        assertEquals("2026-09-21 16:02:05 suggest ms=212 found=3",
            DevLog.line(at, "suggest", listOf("ms" to 212L, "found" to 3)))
    }

    @Test fun anErrorNeverKeepsItsMessage() {
        // A message can quote its input, and the input of a keyboard is what someone typed.
        val error = NumberFormatException("For input string: \"my secret\"")
        val kept = DevLog.describe(at, "Dictionary.load", error)
        assertTrue(kept.startsWith("2026-09-21 16:02:05 NumberFormatException in Dictionary.load"))
        assertFalse(kept.contains("secret"))
        assertFalse(kept.contains("input string"))
    }

    @Test fun onlyTheNewestEntriesAreKept() {
        var text = ""
        repeat(8) { text = DevLog.appendKeeping(text, "entry $it", limit = 5) }
        assertEquals(listOf("entry 3", "entry 4", "entry 5", "entry 6", "entry 7"), DevLog.entries(text))
    }

    @Test fun anErrorWithItsStackStaysOneEntry() {
        val entry = DevLog.describe(at, "here", IllegalStateException())
        val text = DevLog.appendKeeping(DevLog.appendKeeping("", entry, 20), "next", 20)
        assertEquals(2, DevLog.entries(text).size)
    }

    @Test fun onlyTheDevBuildsLog() {
        assertTrue(DevLog.isDevBuild("com.mccal.keyd.dev"))
        assertTrue(DevLog.isDevBuild("com.mccal.keyd.debug"))
        assertFalse(DevLog.isDevBuild("com.mccal.keyd"))
    }

    // ---- Every build, not just Keyd Dev ----------------------------------------------------------------------

    /** The Keyd people get from the Market: the test app with the plain package name. */
    private val release = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getPackageName() = "com.mccal.keyd"
        override fun getApplicationContext(): Context = this
    }

    @Before fun clean() {
        DevLog.clear(release)
        release.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aReleaseBuildRecordsACrashAndMarksIt() {
        assertFalse(DevLog.isDevBuild(release.packageName))
        DevLog.crashed(release, "main", IllegalStateException("typed: hunter2"))
        val errors = DevLog.errors(release)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("IllegalStateException in crash on main"))
        assertFalse(errors.single().contains("hunter2"))
        assertTrue(DevLog.crashedSinceLooked(release))
        DevLog.markLooked(release)
        assertFalse(DevLog.crashedSinceLooked(release))
        // Looking at it clears the mark, not the record.
        assertEquals(1, DevLog.errors(release).size)
    }

    @Test fun aReleaseBuildKeepsProblemsAndErrors() {
        DevLog.problem(release, "slow-suggestion", "ms" to 400L)
        DevLog.error(release, "Dictionary.load", RuntimeException())
        assertEquals(2, DevLog.errors(release).size)
    }

    @Test fun theDetailedLogIsOffUntilSomeoneTurnsItOn() {
        assertFalse(DevLog.loggingOn(release))
        DevLog.event(release, "field", "class" to 1)
        assertTrue(DevLog.lines(release).isEmpty())
        DevLog.setLogging(release, true)
        DevLog.event(release, "field", "class" to 1)
        assertEquals(1, DevLog.lines(release).size)
    }

    @Test fun theDetailedLogTurnsItselfOffADayLater() {
        val on = 1_000_000_000L
        val hour = 60 * 60 * 1000L
        DevLog.setLogging(release, true, now = on)
        assertTrue(DevLog.loggingOn(release, now = on + 23 * hour))
        assertFalse(DevLog.loggingOn(release, now = on + 24 * hour))
        // And it stays off: the switch itself was turned off, not just skipped once.
        assertFalse(DevLog.loggingOn(release, now = on + hour))
        assertFalse(release.getSharedPreferences("keys", Context.MODE_PRIVATE).getBoolean(DevLog.LOGGING_KEY, true))
    }

    @Test fun keydDevLogsForAsLongAsItsSwitchIsOn() {
        assertTrue(DevLog.stillLogging(dev = true, since = 0L, now = Long.MAX_VALUE))
        // A switch left on by a version with no start time counts as expired outside Keyd Dev.
        assertFalse(DevLog.stillLogging(dev = false, since = 0L, now = 5L))
    }

    // ---- The report ------------------------------------------------------------------------------------------

    private val device = DevLog.Device("16 (API 36)", "samsung SM-F966U", "411 x 891 dp")

    private fun compose(answers: DevLog.Answers = DevLog.Answers(), include: DevLog.Include = DevLog.Include()) =
        DevLog.compose(
            build = "com.mccal.keyd 0.2.0 (20) abc1234", device = device, answers = answers, include = include,
            settings = Settings(), errors = listOf("2026-09-21 16:02:05 IllegalStateException in here"),
            log = listOf("2026-09-21 16:02:05 field class=1"),
        )

    @Test fun aReportIsTheBuildThenWhatHappenedThenWhatWasIncluded() {
        val text = compose(
            DevLog.Answers(app = "Messages", did = "Pinned a clip", saw = "The keyboard closed"),
            DevLog.Include(device = true, settings = true, errors = true, log = true),
        )
        val lines = text.lines()
        assertEquals("Keyd report", lines[0])
        assertEquals("com.mccal.keyd 0.2.0 (20) abc1234", lines[1])
        assertEquals("Android 16 (API 36)", lines[2])
        assertEquals("samsung SM-F966U", lines[3])
        assertEquals("Window 411 x 891 dp", lines[4])
        assertTrue(text.contains("What happened\nApp: Messages\nDid: Pinned a clip\nSaw: The keyboard closed\n"))
        val order = listOf("What happened", "Settings", "Recent errors", "Log").map { lines.indexOf(it) }
        assertTrue("sections out of order: $order", order.all { it > 0 } && order == order.sorted())
    }

    @Test fun onlyTheIncludedSectionsAreInAReport() {
        val text = compose(include = DevLog.Include(device = false, settings = false, errors = false, log = false))
        // No answers and nothing included: the title and nothing else. There is nowhere else for text to come from.
        assertEquals("Keyd report\n", text)
        val some = compose(include = DevLog.Include(device = false, settings = true, errors = false, log = false))
        assertTrue(some.contains("\nSettings\n"))
        assertFalse(some.contains("Recent errors"))
        assertFalse(some.contains("Log"))
        assertFalse(some.contains("samsung"))
    }

    @Test fun settingsGoInAsSwitchesOnly() {
        val lines = DevLog.switches(Settings())
        // Every field of Settings but the period's symbols, which are typed, each one name=value where the value is a
        // switch or a choice's name.
        assertEquals(Settings::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) } - 1, lines.size)
        assertTrue(lines.none { it.startsWith("periodSymbols") })
        assertTrue("holdDelay=FOLLOW_PHONE" in lines)
        assertTrue("backspaceSpeed=NORMAL" in lines)
        assertTrue("twoFingerUndo=true" in lines)
        // Symbols that look like a choice's name still stay out.
        assertTrue(DevLog.switches(Settings(periodSymbols = "OK")).none { it.startsWith("periodSymbols") })
        assertTrue(lines.all { Regex("[a-zA-Z]+=(true|false|[0-9]|[A-Z_]+(\\+[A-Z_]+)*)").matches(it) })
        assertTrue("emojiSkinTone=0" in lines)
        assertTrue("keyStyle=FOLIO" in lines)
        assertTrue("toolbar=EMOJI+UNDO+CURSOR_PAD+COPY+PASTE+CLIPBOARD+VOICE" in lines)
        assertTrue("toolbar=NONE" in DevLog.switches(Settings(toolbar = emptyList())))
    }

    @Test fun eventsWaitInMemoryAndReachTheFileInBatches() {
        DevLog.setLogging(release, true)
        val file = java.io.File(release.filesDir, "dev-log.txt")
        repeat(3) { DevLog.event(release, "suggest", "ms" to it) }
        assertFalse("not written on every keystroke", file.isFile && file.readText().contains("suggest"))
        DevLog.flush(release)
        assertEquals(3, file.readText().split("\n\n").count { it.contains("suggest") })
        repeat(50) { DevLog.event(release, "suggest", "ms" to it) }
        assertEquals("a full batch goes without being asked", 53, file.readText().split("\n\n").count { it.contains("suggest") })
    }

    @Test fun whatIsHeldInMemoryIsInTheLogWhenItIsRead() {
        DevLog.setLogging(release, true)
        DevLog.event(release, "field", "class" to 1)
        assertEquals(1, DevLog.lines(release).size)
    }

    @Test fun aSuggestionErrorIsWrittenDownOncePerPlace() {
        repeat(5) { DevLog.errorOnce(release, "Suggestions.test-once", IllegalStateException()) }
        DevLog.errorOnce(release, "Suggestions.test-other", IllegalStateException())
        assertEquals(2, DevLog.errors(release).size)
    }
}
