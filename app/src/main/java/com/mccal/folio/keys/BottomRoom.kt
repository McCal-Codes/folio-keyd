package com.mccal.folio.keys

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Settings
import kotlin.math.max

/**
 * How much room to leave under the keys.
 *
 * Three things can be down there, and a keyboard that assumes one of them gets in the way of the others:
 *
 * * **Gesture navigation** puts the home bar there. The system reports a small inset, and iOS and Samsung both keep a
 *   band of their own background around it rather than letting keys touch the edge.
 * * **Three-button navigation** puts Back, Home and Recents there. The inset is already generous, so adding a band on
 *   top only wastes a row's worth of screen.
 * * **Folio's Big Buttons** float above Android's navigation, and nothing in the system tells another app they are
 *   there. Folio publishes their height, and this reads it when it is offered.
 */
object BottomRoom {

    /** Android's own setting: 0 is three buttons, 1 is two buttons, 2 is gestures. */
    const val NAV_THREE_BUTTON = 0
    const val NAV_TWO_BUTTON = 1
    const val NAV_GESTURE = 2

    /**
     * Where Folio offers the height of its Big Buttons, in dp. A debug Folio carries the .dev suffix and can be
     * installed beside a release one, so both are asked and the first that answers wins. Absent when Folio isn't
     * there, the buttons are off, or they have been dragged out of the keyboard's way.
     */
    val FOLIO_BUTTONS = listOf(
        "content://com.mccal.folio.buttons/room",
        "content://com.mccal.folio.dev.buttons/room",
    )

    const val GESTURE_BAND_DP = 26f

    fun navigationMode(context: Context): Int =
        runCatching {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode")
        }.getOrDefault(NAV_GESTURE)

    /**
     * The height of Folio's Big Buttons, or zero. Reading is best-effort by design: no permission, no dependency, and
     * a keyboard that carries on exactly as before when Folio isn't installed or says nothing.
     */
    fun folioButtonsDp(resolver: ContentResolver): Float {
        for (authority in FOLIO_BUTTONS) {
            val answer = runCatching {
                resolver.query(Uri.parse(authority), arrayOf("heightDp"), null, null, null)?.use { row ->
                    if (row.moveToFirst()) row.getFloat(0) else null
                }
            }.getOrNull()
            if (answer != null) return answer.coerceIn(0f, 120f)
        }
        return 0f
    }

    /**
     * The band under the keys, in pixels.
     *
     * With buttons the system's own inset is room enough; with gestures the keyboard keeps its own band so the home
     * bar has somewhere to be. Folio's Big Buttons sit above both, so their height is added rather than compared.
     */
    fun band(navigationMode: Int, systemInsetPx: Float, folioButtonsDp: Float, density: Float): Float {
        val ownBand = if (navigationMode == NAV_GESTURE) GESTURE_BAND_DP * density else 0f
        return max(systemInsetPx, ownBand) + folioButtonsDp * density
    }
}
