package com.mccal.folio.keys

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The one place a key decides how hard to tap back.
 *
 * Every panel used to check a vibrate switch and pick its own effect, which is four places to keep in step and one
 * of them always drifting. They all ask here now.
 *
 * Only Android's own haptic effects, played through the view: that needs no VIBRATE permission, and it follows the
 * phone's own touch-feedback setting, so Keyd can make it quieter but never louder than the phone allows. The price
 * is that there is no amplitude to set, so each strength is a different effect, lightest to heaviest:
 *
 * | Strength | A key                         | Holding a key                 |
 * |----------|-------------------------------|-------------------------------|
 * | Light    | CLOCK_TICK (a tick)           | KEYBOARD_TAP (a click)        |
 * | Medium   | KEYBOARD_TAP (a click)        | LONG_PRESS (a heavy click)    |
 * | Strong   | LONG_PRESS (a heavy click)    | LONG_PRESS (a heavy click)    |
 *
 * Medium is what every key did before there was a choice. What each effect feels like is up to the phone's maker;
 * on a phone that plays two of them the same, two strengths feel the same.
 */
object Haptics {

    /** A key going down, or a key held until it offers more. */
    enum class Touch { TAP, HOLD }

    /** The effect for [strength] and [touch], or null for none at all. */
    fun effect(strength: Vibration, touch: Touch): Int? = when (strength) {
        Vibration.OFF -> null
        Vibration.LIGHT -> if (touch == Touch.TAP) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP
        Vibration.MEDIUM -> if (touch == Touch.TAP) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.LONG_PRESS
        Vibration.STRONG -> HapticFeedbackConstants.LONG_PRESS
    }

    /** Taps back through [view], or does nothing when vibration is off. */
    fun feel(view: View, strength: Vibration, touch: Touch = Touch.TAP) {
        effect(strength, touch)?.let { view.performHapticFeedback(it) }
    }
}
