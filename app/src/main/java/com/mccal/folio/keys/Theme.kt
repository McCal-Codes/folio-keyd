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
) {
    companion object {
        private val DARK = Theme(
            board = 0xFF1B1B1D.toInt(),
            key = 0xFF4B4B50.toInt(),
            altKey = 0xFF2E2E32.toInt(),
            label = 0xFFFFFFFF.toInt(),
            hint = 0xFFD6D6DA.toInt(),
            accent = 0xFF0071E3.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pressTint = 0x33FFFFFF.toInt(),
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
        )

        /** Both themes, for the contrast test: the colours that ship are the colours that get checked. */
        fun bothForTests(): List<Pair<String, Theme>> = listOf("dark" to DARK, "light" to LIGHT)

        fun of(context: Context): Theme {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) DARK else LIGHT
        }
    }
}
