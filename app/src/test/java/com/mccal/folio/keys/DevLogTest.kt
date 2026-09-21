package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

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
}
