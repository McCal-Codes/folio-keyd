package com.mccal.folio.keys

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who keeps 0.2's six toolbar buttons and who gets Undo back.
 *
 * Each install is rebuilt the way the betas left it: beta 1 stored no list unless a setting changed, and beta 2 on
 * settled an update with no list as 0.2's six.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolbarMigrationTest {

    private fun prefs(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("toolbar-migration-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private val beforeToolbar = Settings.TOOLBAR_ARRIVED - 2 * 24 * 60 * 60 * 1000L
    private val beta1Installed = Settings.TOOLBAR_ARRIVED + 10 * 60 * 1000L
    private val six = Settings.TOOLBAR_BEFORE_UNDO.joinToString(",") { it.name }

    private fun SharedPreferences.toolbar() = Settings.load(this).toolbar

    @Test
    fun `a beta 1 install that beta 2 settled as 0_2 gets Undo back`() {
        // What beta 2 did: an update, no list stored, so it wrote down the six.
        val p = prefs().also { it.edit().putString(Settings.TOOLBAR, six).commit() }
        Settings.settleToolbar(p, updated = true, firstInstalled = beta1Installed)
        assertEquals(Settings.DEFAULT_TOOLBAR, p.toolbar())
    }

    @Test
    fun `a beta 1 install nobody touched that updates straight to this one starts with Undo`() {
        val p = prefs()
        Settings.settleToolbar(p, updated = true, firstInstalled = beta1Installed)
        assertEquals(Settings.DEFAULT_TOOLBAR, p.toolbar())
    }

    @Test
    fun `a real 0_2 upgrade keeps its six`() {
        val p = prefs()
        Settings.settleToolbar(p, updated = true, firstInstalled = beforeToolbar)
        assertEquals(Settings.TOOLBAR_BEFORE_UNDO, p.toolbar())
        // Settled by an earlier 0.3, then this version looks.
        val q = prefs().also { it.edit().putString(Settings.TOOLBAR, six).commit() }
        Settings.settleToolbar(q, updated = true, firstInstalled = beforeToolbar)
        assertEquals(Settings.TOOLBAR_BEFORE_UNDO, q.toolbar())
    }

    @Test
    fun `when Android will not say when it was installed, the six stay`() {
        val p = prefs().also { it.edit().putString(Settings.TOOLBAR, six).commit() }
        Settings.settleToolbar(p, updated = true, firstInstalled = null)
        assertEquals(Settings.TOOLBAR_BEFORE_UNDO, p.toolbar())
    }

    @Test
    fun `a list someone arranged is never touched, even if it is the six`() {
        val arranged = prefs().also {
            it.edit().putString(Settings.TOOLBAR, six).putBoolean(Settings.TOOLBAR_ARRANGED, true).commit()
        }
        Settings.settleToolbar(arranged, updated = true, firstInstalled = beta1Installed)
        assertEquals(Settings.TOOLBAR_BEFORE_UNDO, arranged.toolbar())

        val custom = listOf(ToolKey.EMOJI, ToolKey.CURSOR_PAD, ToolKey.PASTE)
        val other = prefs().also { p -> p.edit().putString(Settings.TOOLBAR, custom.joinToString(",") { it.name }).commit() }
        Settings.settleToolbar(other, updated = true, firstInstalled = beta1Installed)
        assertEquals(custom, other.toolbar())
    }

    @Test
    fun `it looks once, so a list put back to the six later stays`() {
        val p = prefs()
        Settings.settleToolbar(p, updated = true, firstInstalled = beta1Installed)
        assertTrue(p.getBoolean(Settings.TOOLBAR_BETA_CHECKED, false))
        p.edit().putString(Settings.TOOLBAR, six).commit()
        Settings.settleToolbar(p, updated = true, firstInstalled = beta1Installed)
        Settings.settleToolbar(p, updated = true, firstInstalled = beta1Installed)
        assertEquals(Settings.TOOLBAR_BEFORE_UNDO, p.toolbar())
    }

    @Test
    fun `a fresh install gets the usual list`() {
        val p = prefs()
        Settings.settleToolbar(p, updated = false, firstInstalled = System.currentTimeMillis())
        assertEquals(Settings.DEFAULT_TOOLBAR, p.toolbar())
    }
}
