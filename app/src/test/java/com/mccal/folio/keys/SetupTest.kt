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

    private val us = "com.mccal.keyd"
    private val ours = "$us/.KeysService"
    private val samsung = "com.samsung.android.honeyboard/.service.HoneyBoardService"

    @Test
    fun `not added when the system does not list us`() {
        assertEquals(SetupState.NOT_ADDED, setupState(added = false, current = samsung, packageName = us))
    }

    @Test
    fun `added when we are listed but another keyboard is the default`() {
        assertEquals(SetupState.ADDED, setupState(added = true, current = samsung, packageName = us))
    }

    @Test
    fun `in use when we are the default`() {
        assertEquals(SetupState.IN_USE, setupState(added = true, current = ours, packageName = us))
    }

    /**
     * The bug this replaced: the default-keyboard setting came back empty on a real phone, and an installed,
     * enabled keyboard was told it had never been added. Not being able to read one setting may cost us the
     * difference between "added" and "in use", and nothing more.
     */
    @Test
    fun `an unreadable setting never makes an installed keyboard look missing`() {
        assertEquals(SetupState.ADDED, setupState(added = true, current = null, packageName = us))
        assertEquals(SetupState.ADDED, setupState(added = true, current = "", packageName = us))
    }

    @Test
    fun `nothing at all is not added, not a crash`() {
        assertEquals(SetupState.NOT_ADDED, setupState(added = false, current = null, packageName = us))
    }

    /** A debug build sits beside a release one; neither may claim the other's setup as its own. */
    @Test
    fun `a package that merely starts with ours is somebody else`() {
        assertEquals(SetupState.ADDED, setupState(added = true, current = "$us.dev/.KeysService", packageName = us))
    }
}
