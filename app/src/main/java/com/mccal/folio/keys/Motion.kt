package com.mccal.folio.keys

import android.content.Context
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator

/**
 * How the keyboard moves, in one place so every part of it agrees.
 *
 * Nothing here fades or pulses on its own: each motion is the answer to something the person did (pressing a key,
 * switching layer, opening a panel, resizing the window) and finishes in a fifth of a second or less. Everything
 * is off when the phone's "remove animations" or animator scale is set to zero, so a person who asked for stillness
 * gets it, and nothing here flashes or strobes: no motion repeats, and none changes brightness faster than a fade.
 */
internal object Motion {
    /** Keys sliding to their new places when the window changes size, a split opening or closing. */
    const val MOVE_MS = 200L

    /** The squeeze of a key under a finger, and its return. Quicker down than up, so it answers the touch at once. */
    const val PRESS_DOWN_MS = 60L
    const val PRESS_UP_MS = 130L
    const val PRESS_SCALE = 0.94f

    /** A key's label changing with shift, and a whole layer changing under 123. */
    const val CASE_MS = 90L
    const val LAYER_MS = 150L

    /** The strip's content changing kind: the toolbar giving way to words, a question, a tip. */
    const val STRIP_MS = 110L

    /** A panel (emoji, clipboard, cursor pad) or the letters coming back. */
    const val PANEL_MS = 170L
    const val PANEL_RISE_DP = 8f
    const val PANEL_START_ALPHA = 0.35f

    /** False when the person turned animations off in the system settings (an animator scale of zero). */
    fun enabled(context: Context): Boolean = try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
    } catch (_: Exception) {
        true
    }

    /** The clock the frame is drawn on, so a motion stays in step with what the screen is showing. */
    fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    /** Starts fast and settles, the way something thrown to a stop does: right for things that answer a touch. */
    fun ease(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    fun lerp(from: Box, to: Box, t: Float): Box = Box(
        lerp(from.left, to.left, t), lerp(from.top, to.top, t), lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t),
    )

    /** Fades a view in with a small rise, for a panel that has just taken the keyboard's place. */
    fun reveal(view: View?) {
        view ?: return
        view.animate().cancel()
        if (!enabled(view.context)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        // Not from nothing: the keys it replaces are gone at once, and a panel at zero left the board black for a frame.
        view.alpha = PANEL_START_ALPHA
        view.translationY = PANEL_RISE_DP * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(PANEL_MS).setInterpolator(DecelerateInterpolator()).start()
    }
}
