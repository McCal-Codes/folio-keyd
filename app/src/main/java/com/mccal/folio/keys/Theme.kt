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
    /** How round a key's corners are: the most visible difference between the three key styles. */
    val keyRadiusDp: Float = 14f,
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
        /**
         * Every palette that ships, for the contrast checks.
         *
         * The high-contrast pair is in here deliberately: a palette whose entire purpose is being easier to read
         * would be a joke if it were the one that failed the contrast test.
         */
        fun bothForTests(): List<Pair<String, Theme>> = listOf(
            "dark" to DARK,
            "light" to LIGHT,
            "material dark" to MATERIAL_DARK,
            "material light" to MATERIAL_LIGHT,
            "samsung dark" to SAMSUNG_DARK,
            "samsung light" to SAMSUNG_LIGHT,
            "high contrast dark" to HIGH_CONTRAST_DARK,
            "high contrast light" to HIGH_CONTRAST_LIGHT,
        )

        /**
         * Material: the Material 3 baseline roles Gboard's colours come from (surface, surface-variant, primary and
         * on-surface), with rounder keys. The same values the mockup lab checked.
         */
        private val MATERIAL_DARK = DARK.copy(
            board = 0xFF1C1B1F.toInt(),
            key = 0xFF3B383E.toInt(),
            altKey = 0xFF49454F.toInt(),
            label = 0xFFE6E1E5.toInt(),
            hint = 0xFFCAC4D0.toInt(),
            accent = 0xFFD0BCFF.toInt(),
            onAccent = 0xFF381E72.toInt(),
            preview = 0xFF49454F.toInt(),
            keyRadiusDp = 18f,
        )

        private val MATERIAL_LIGHT = LIGHT.copy(
            board = 0xFFD8D0E0.toInt(),
            key = 0xFFFFFBFE.toInt(),
            altKey = 0xFFB9AFC6.toInt(),
            label = 0xFF1C1B1F.toInt(),
            hint = 0xFF49454F.toInt(),
            accent = 0xFF6750A4.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            preview = 0xFFFFFBFE.toInt(),
            keyRadiusDp = 18f,
        )

        // The light boards are a shade darker than the lab first drew them, and Material's dark key a shade lighter:
        // at the first values a key was under 1.4:1 against its board and ThemeContrastTest said so.

        /** Samsung: a darker board and squarer keys, with the blue the lab settled on at 5.36:1 under white. */
        private val SAMSUNG_DARK = DARK.copy(
            board = 0xFF131313.toInt(),
            key = 0xFF3A3A3C.toInt(),
            altKey = 0xFF242426.toInt(),
            label = 0xFFF2F2F2.toInt(),
            hint = 0xFFA8A8AD.toInt(),
            accent = 0xFF1A66D6.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            preview = 0xFF3A3A3C.toInt(),
            keyRadiusDp = 8f,
        )

        private val SAMSUNG_LIGHT = LIGHT.copy(
            board = 0xFFD4D7DC.toInt(),
            key = 0xFFFFFFFF.toInt(),
            altKey = 0xFFB4B9C1.toInt(),
            label = 0xFF141414.toInt(),
            hint = 0xFF5A5F66.toInt(),
            accent = 0xFF1A66D6.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            preview = 0xFFFFFFFF.toInt(),
            keyRadiusDp = 8f,
        )

        /**
         * Every key outlined and every label at full strength.
         *
         * Samsung has one of these under Style and layout, and it is worth having: the ordinary palettes put grey
         * keys on a grey board, which is fine for most eyes and not for all of them. This pushes both apart as far
         * as they go - black board, white keys, black labels - rather than merely nudging them.
         */
        private val HIGH_CONTRAST_DARK = DARK.copy(
            board = 0xFF000000.toInt(),
            key = 0xFFFFFFFF.toInt(),
            altKey = 0xFFB8B8BD.toInt(),
            label = 0xFF000000.toInt(),
            hint = 0xFF000000.toInt(),
            accent = 0xFFFFD400.toInt(),
            onAccent = 0xFF000000.toInt(),
            pressTint = 0x66000000,
            preview = 0xFFFFFFFF.toInt(),
        )

        private val HIGH_CONTRAST_LIGHT = LIGHT.copy(
            board = 0xFFFFFFFF.toInt(),
            key = 0xFF000000.toInt(),
            altKey = 0xFF3A3A3F.toInt(),
            label = 0xFFFFFFFF.toInt(),
            hint = 0xFFFFFFFF.toInt(),
            accent = 0xFF00348F.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pressTint = 0x66FFFFFF,
            preview = 0xFF000000.toInt(),
        )

        /** High contrast wins over the style: it exists for eyes the styles don't suit, whichever one was picked. */
        fun of(
            context: Context,
            appearance: Appearance = Appearance.SYSTEM,
            highContrast: Boolean = false,
            style: KeyStyle = KeyStyle.FOLIO,
        ): Theme {
            val dark = when (appearance) {
                Appearance.DARK -> true
                Appearance.LIGHT -> false
                Appearance.SYSTEM -> context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            if (highContrast) return if (dark) HIGH_CONTRAST_DARK else HIGH_CONTRAST_LIGHT
            return when (style) {
                KeyStyle.FOLIO -> if (dark) DARK else LIGHT
                KeyStyle.MATERIAL -> if (dark) MATERIAL_DARK else MATERIAL_LIGHT
                KeyStyle.SAMSUNG -> if (dark) SAMSUNG_DARK else SAMSUNG_LIGHT
            }
        }

    }
}
