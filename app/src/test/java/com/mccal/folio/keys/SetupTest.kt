package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading Android's two settings back.
 *
 * The strings are real ones: a list of enabled input methods is colon-separated components, and more than one
 * keyboard's component can share a prefix with ours, which is why the package half is compared whole.
 */
class SetupTest {

    private val us = "com.mccal.folio.keys"
    private val ours = "$us/.KeysService"
    private val samsung = "com.samsung.android.honeyboard/.service.HoneyBoardService"
    private val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

    @Test
    fun `not added when the list doesn't mention us`() {
        assertEquals(SetupState.NOT_ADDED, setupState("$samsung:$gboard", samsung, us))
    }

    @Test
    fun `added when the list has us but another keyboard is in use`() {
        assertEquals(SetupState.ADDED, setupState("$samsung:$ours", samsung, us))
    }

    @Test
    fun `in use when we are the default`() {
        assertEquals(SetupState.IN_USE, setupState("$samsung:$ours", ours, us))
    }

    /** Reinstalling an input method drops it from the enabled list, which is the state this has to name plainly. */
    @Test
    fun `reinstalling puts us back to not added`() {
        assertEquals(SetupState.NOT_ADDED, setupState(samsung, samsung, us))
    }

    @Test
    fun `nothing at all is not added, not a crash`() {
        assertEquals(SetupState.NOT_ADDED, setupState(null, null, us))
        assertEquals(SetupState.NOT_ADDED, setupState("", "", us))
    }

    /** A debug build sits beside a release one; neither may claim the other's setup as its own. */
    @Test
    fun `a package that merely starts with ours is somebody else`() {
        assertEquals(SetupState.NOT_ADDED, setupState("$us.dev/.KeysService", "$us.dev/.KeysService", us))
    }
}
