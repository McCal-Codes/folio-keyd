package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicOnSpaceRowTest {
    private fun bottom(micKey: Boolean, layer: Layer = Layer.LETTERS) =
        Layouts.rows(layer, false, FieldRules(), micKey = micKey).last()

    @Test fun `the mic is off unless asked for`() {
        assertFalse(bottom(false).any { it.kind == KeyKind.VOICE })
    }

    @Test fun `the mic sits right of the space bar and left of the action key`() {
        val row = bottom(true)
        val space = row.indexOfFirst { it.kind == KeyKind.SPACE }
        val mic = row.indexOfFirst { it.kind == KeyKind.VOICE }
        assertTrue(mic > space)
        assertEquals(KeyKind.ACTION, row.last().kind)
        assertEquals(row.lastIndex - 1, mic)
    }

    @Test fun `the symbol layers keep their row without a mic`() {
        assertFalse(bottom(true, Layer.NUMBERS).any { it.kind == KeyKind.VOICE })
        assertFalse(bottom(true, Layer.SYMBOLS).any { it.kind == KeyKind.VOICE })
    }

    @Test fun `the space bar gives up width for the mic`() {
        val with = bottom(true).first { it.kind == KeyKind.SPACE }.weight
        val without = bottom(false).first { it.kind == KeyKind.SPACE }.weight
        assertTrue(with < without)
    }

    @Test fun `the setting round trips`() {
        assertFalse(Settings().micOnSpaceRow)
        assertTrue(Settings().copy(micOnSpaceRow = true).micOnSpaceRow)
    }
}
