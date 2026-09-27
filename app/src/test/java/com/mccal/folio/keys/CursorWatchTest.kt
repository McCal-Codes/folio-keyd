package com.mccal.folio.keys

import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deciding whether the line being typed on has gone behind the keys.
 *
 * The consequence of getting this wrong in one direction is a keyboard that takes the whole screen when it did not
 * need to; in the other, someone typing blind. The third answer - not knowing - has to stay distinct from both,
 * because most editors say nothing at all and silence is not evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursorWatchTest {

    private val visible = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
    private val invisible = CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION

    @Test
    fun `a cursor above the keyboard is visible`() {
        assertEquals(CursorWatch.Where.VISIBLE, CursorWatch.read(800f, 1000, visible))
    }

    @Test
    fun `a cursor below the top of the keyboard is hidden`() {
        assertEquals(CursorWatch.Where.HIDDEN, CursorWatch.read(1200f, 1000, visible))
    }

    /** Sitting exactly on the line is already half under it. */
    @Test
    fun `a cursor level with the keyboard's edge counts as hidden`() {
        assertEquals(CursorWatch.Where.HIDDEN, CursorWatch.read(1000f, 1000, visible))
    }

    /** Scrolled out of view inside the field itself is just as blind, with nothing on top of it. */
    @Test
    fun `a cursor scrolled out of its own field is hidden`() {
        assertEquals(CursorWatch.Where.HIDDEN, CursorWatch.read(100f, 1000, invisible))
    }

    @Test
    fun `partly visible is not hidden`() {
        assertEquals(CursorWatch.Where.VISIBLE, CursorWatch.read(500f, 1000, visible or invisible))
    }

    /**
     * The answer that matters most.
     *
     * Editors are not obliged to report anything, and most do not. Reading silence as "hidden" would give a
     * fullscreen keyboard to every app that stays quiet, which is nearly all of them.
     */
    @Test
    fun `no marker at all is not an answer`() {
        assertEquals(CursorWatch.Where.UNKNOWN, CursorWatch.read(Float.NaN, 1000, 0))
        assertEquals(CursorWatch.Where.UNKNOWN, CursorWatch.read(Float.NaN, 1000, visible))
    }

    // ---- Decision: the screen is taken over time, not from one reading ----------------------------------------------

    private val hidden = CursorWatch.Where.HIDDEN
    private val shown = CursorWatch.Where.VISIBLE

    @Test
    fun `one hidden reading only asks for a second look`() {
        val decision = CursorWatch.Decision()
        assertEquals(CursorWatch.Next.CHECK_LATER, decision.report(hidden, windowShown = true))
        assertEquals(false, decision.taken)
    }

    @Test
    fun `a hidden reading that stands takes the screen`() {
        val decision = CursorWatch.Decision()
        decision.report(hidden, windowShown = true)
        assertEquals(true, decision.confirm(windowShown = true))
        assertEquals(true, decision.taken)
    }

    /** A growing multi-line field: hidden for a moment, then the app moves it back into view. */
    @Test
    fun `a hidden reading the app corrects does not take the screen`() {
        val decision = CursorWatch.Decision()
        decision.report(hidden, windowShown = true)
        decision.report(shown, windowShown = true)
        assertEquals(false, decision.confirm(windowShown = true))
        assertEquals(false, decision.taken)
    }

    /** The keyboard sliding away puts every cursor "below" it. That is not a reason to take anything. */
    @Test
    fun `readings while the keyboard is not on screen are ignored`() {
        val decision = CursorWatch.Decision()
        assertEquals(CursorWatch.Next.NOTHING, decision.report(hidden, windowShown = false))
        assertEquals(false, decision.confirm(windowShown = false))
    }

    @Test
    fun `a second look after the keyboard has gone does not take the screen`() {
        val decision = CursorWatch.Decision()
        decision.report(hidden, windowShown = true)
        assertEquals(false, decision.confirm(windowShown = false))
    }

    /**
     * Roby's WhatsApp report, 25 Sep 2026: the screen was taken, the keyboard was hidden with the toolbar's chevron,
     * and every tap on the same field brought back the fullscreen keyboard until WhatsApp was closed.
     */
    @Test
    fun `hiding the keyboard gives the screen back`() {
        val decision = CursorWatch.Decision()
        decision.report(hidden, windowShown = true)
        decision.confirm(windowShown = true)
        assertEquals(true, decision.reset())
        assertEquals(false, decision.taken)
        assertEquals(false, decision.reset())
    }

    @Test
    fun `silence changes nothing`() {
        val decision = CursorWatch.Decision()
        assertEquals(CursorWatch.Next.NOTHING, decision.report(CursorWatch.Where.UNKNOWN, windowShown = true))
        assertEquals(false, decision.confirm(windowShown = true))
    }
}
