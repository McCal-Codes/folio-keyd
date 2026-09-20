package com.mccal.folio.keys

import android.view.inputmethod.CursorAnchorInfo

/**
 * Whether the person can still see the line they are typing on.
 *
 * A keyboard cannot scroll the app. It can only say truthfully how much room it takes and hope the app moves the
 * field out from under it — and plenty of apps do not, particularly on a short screen where there is nowhere to move
 * it to. The one thing a keyboard *can* do is stop being a strip at the bottom and take the screen instead, showing
 * the text in a field of its own. That is what Android's fullscreen mode is for, and it is how Samsung's keyboard
 * guarantees the same thing in landscape.
 *
 * Doing that always would be worse than the problem: it hides the app for no reason whenever the app was coping
 * fine. So the editor is asked where the cursor actually is, through [CursorAnchorInfo], and the screen is only
 * taken when the cursor really has gone behind the keys.
 */
object CursorWatch {

    /**
     * Where the cursor is, as far as we can tell.
     *
     * [UNKNOWN] matters as much as the other two: most editors report nothing at all, and an unanswered question
     * must never be read as "hidden" or every app that stays quiet would get a fullscreen keyboard.
     */
    enum class Where { UNKNOWN, VISIBLE, HIDDEN }

    /**
     * Reads the editor's report.
     *
     * [keyboardTopOnScreen] is the top edge of our own window. The cursor is behind us when its bottom sits below
     * that line. The editor can also say the cursor has scrolled out of view inside the field itself, which is
     * just as hidden even though nothing is on top of it.
     */
    fun read(
        markerBottomOnScreen: Float,
        keyboardTopOnScreen: Int,
        flags: Int,
    ): Where {
        val visibleRegion = flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION != 0
        val invisibleRegion = flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0
        if (invisibleRegion && !visibleRegion) return Where.HIDDEN
        if (markerBottomOnScreen.isNaN()) return Where.UNKNOWN
        // A cursor sitting exactly on our top edge is already half under it, so a little margin is given.
        return if (markerBottomOnScreen > keyboardTopOnScreen - MARGIN_PX) Where.HIDDEN else Where.VISIBLE
    }

    private const val MARGIN_PX = 2f
}
