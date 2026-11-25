package com.mccal.folio.keys

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The numbers behind the motion, and that a person who turned animations off gets none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an ease starts at nothing, ends at all of it and never overshoots`() {
        assertEquals(0f, Motion.ease(0f), 0f)
        assertEquals(1f, Motion.ease(1f), 0f)
        var last = 0f
        for (i in 0..100) {
            val v = Motion.ease(i / 100f)
            assertTrue("went backwards at $i", v >= last)
            assertTrue("overshot at $i", v <= 1f)
            last = v
        }
        assertEquals(1f, Motion.ease(7f), 0f)
        assertEquals(0f, Motion.ease(-3f), 0f)
    }

    @Test
    fun `boxes ease between two places and land exactly on the second`() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(100f, 20f, 140f, 60f)
        assertEquals(a, Motion.lerp(a, b, 0f))
        assertEquals(b, Motion.lerp(a, b, 1f))
        assertEquals(50f, Motion.lerp(a, b, 0.5f).left, 0.001f)
    }

    @Test
    fun `no motion is longer than a quarter of a second`() {
        for (ms in listOf(Motion.MOVE_MS, Motion.PRESS_DOWN_MS, Motion.PRESS_UP_MS, Motion.CASE_MS, Motion.LAYER_MS, Motion.STRIP_MS, Motion.PANEL_MS)) {
            assertTrue("$ms ms is too long", ms <= 250L)
        }
        assertTrue(Motion.PRESS_SCALE in 0.9f..1f)
    }

    @Test
    fun `animations are on by default and off when the system scale is zero`() {
        assertTrue(Motion.enabled(context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            assertFalse(Motion.enabled(context))
            val view = View(context).apply { alpha = 0.3f }
            Motion.reveal(view)
            assertEquals("a person who turned animations off sees the panel at once", 1f, view.alpha, 0f)
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun `a panel revealed with animations on starts faint and a little low, never from nothing`() {
        val view = View(context)
        Motion.reveal(view)
        assertEquals(Motion.PANEL_START_ALPHA, view.alpha, 0.001f)
        assertTrue(view.alpha in 0.2f..0.5f)
        assertTrue(view.translationY > 0f)
    }
}
