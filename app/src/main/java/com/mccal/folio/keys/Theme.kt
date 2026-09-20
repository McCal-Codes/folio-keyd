package com.mccal.folio.keys

import android.content.Context
import android.content.res.Configuration

/**
 * Folio's own keyboard colours, light and dark. Every pair here clears WCAG AA for the label on its key, which is
 * checked in the mockup lab before it reaches this file.
 */
data class Theme(
    val board: Int,
    val key: Int,
    val altKey: Int,
    val label: Int,
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
    val pressTint: Int,
    /** The bubble that pops above the key under a finger. */
    val preview: Int,
) {
    companion object {
        private val DARK = Theme(
            board = 0xFF1B1B1D.toInt(),
            key = 0xFF54545A.toInt(),
            altKey = 0xFF3A3A40.toInt(),
            label = 0xFFFFFFFF.toInt(),
            hint = 0xFFD6D6DA.toInt(),
            accent = 0xFF0071E3.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pressTint = 0x33FFFFFF.toInt(),
            preview = 0xFF5A5A60.toInt(),
        )

        private val LIGHT = Theme(
            board = 0xFFD1D4DA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            altKey = 0xFFA7ACB6.toInt(),
            label = 0xFF000000.toInt(),
            hint = 0xFF4A4A4F.toInt(),
            accent = 0xFF0066CC.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pressTint = 0x33000000.toInt(),
            preview = 0xFFFFFFFF.toInt(),
        )

        /** Both themes, for the contrast test: the colours that ship are the colours that get checked. */
        fun bothForTests(): List<Pair<String, Theme>> = listOf("dark" to DARK, "light" to LIGHT)

        /** [Appearance.SYSTEM] follows the phone; the other two are for someone who has decided otherwise. */
        fun of(context: Context, appearance: Appearance = Appearance.SYSTEM): Theme = when (appearance) {
            Appearance.DARK -> DARK
            Appearance.LIGHT -> LIGHT
            Appearance.SYSTEM -> {
                val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                if (night) DARK else LIGHT
            }
        }
    }
}
