package com.mccal.folio.keys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

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
            board = Color.parseColor("#1B1B1D"),
            key = Color.parseColor("#4B4B50"),
            altKey = Color.parseColor("#2E2E32"),
            label = Color.WHITE,
            hint = Color.parseColor("#D6D6DA"),
            accent = Color.parseColor("#0071E3"),
            onAccent = Color.WHITE,
            pressTint = Color.parseColor("#33FFFFFF"),
        )

        private val LIGHT = Theme(
            board = Color.parseColor("#D1D4DA"),
            key = Color.WHITE,
            altKey = Color.parseColor("#A7ACB6"),
            label = Color.BLACK,
            hint = Color.parseColor("#4A4A4F"),
            accent = Color.parseColor("#0066CC"),
            onAccent = Color.WHITE,
            pressTint = Color.parseColor("#33000000"),
        )

        fun of(context: Context): Theme {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) DARK else LIGHT
        }
    }
}
