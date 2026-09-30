package com.mccal.folio.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Forget button counts learned words the way a person would: one word, not "1 words". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupForgetTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun forget(words: Int) = resources.getQuantityString(R.plurals.setup_forget, words, words)

    @Test
    fun `one word is the word`() {
        assertEquals("Forget the word it has learned, and the fixes it has counted", forget(1))
    }

    @Test
    fun `more than one is counted`() {
        assertEquals("Forget the 2 words it has learned, and the fixes it has counted", forget(2))
        assertEquals("Forget the 40 words it has learned, and the fixes it has counted", forget(40))
    }
}
