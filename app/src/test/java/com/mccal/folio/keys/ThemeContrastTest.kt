package com.mccal.folio.keys

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours that ship, checked the way the mockup lab checks the design: WCAG contrast, both themes. A keyboard is
 * read at a glance in the dark and in the sun, so a label that only just passes is a label that will be squinted at.
 */
class ThemeContrastTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private val themes get() = Theme.bothForTests()

    @Test
    fun `a letter on its key clears AA`() {
        for ((name, theme) in themes) {
            val ratio = contrast(theme.label, theme.key)
            assertTrue("$name: label on key is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    @Test
    fun `a label on a shift or backspace key clears AA`() {
        for ((name, theme) in themes) {
            val ratio = contrast(theme.label, theme.altKey)
            assertTrue("$name: label on alt key is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    @Test
    fun `the action key can be read`() {
        for ((name, theme) in themes) {
            val ratio = contrast(theme.onAccent, theme.accent)
            assertTrue("$name: action label is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    @Test
    fun `the corner hint clears AA as small text`() {
        for ((name, theme) in themes) {
            val ratio = contrast(theme.hint, theme.key)
            assertTrue("$name: hint on key is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    @Test
    fun `a key can be told apart from the board behind it`() {
        for ((name, theme) in themes) {
            // 3:1 is the WCAG bar for a control's own edge, which is what a key against the board is.
            val ratio = contrast(theme.key, theme.board)
            assertTrue("$name: key against board is only ${"%.2f".format(ratio)}:1", ratio >= 1.4)
        }
    }
}
