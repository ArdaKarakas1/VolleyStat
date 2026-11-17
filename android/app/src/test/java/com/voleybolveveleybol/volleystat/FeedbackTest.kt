package com.voleybolveveleybol.volleystat

import android.content.Intent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedbackTest {
    @Test fun theFeedbackMailGoesToTheOwnersAddressAndCarriesOnlyTheVersionAndThePhone() {
        val intent = feedbackIntent(versionName = "0.6.0", phone = "Google Pixel 7", androidVersion = 34)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        // A mailto: address with no recipient in it lets only mail apps answer.
        assertEquals("mailto:", intent.dataString)
        assertArrayEquals(arrayOf("voleybolveveleybol@gmail.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("VolleyStat geri bildirimi", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(body, "0.6.0" in body && "Google Pixel 7" in body && "Android 34" in body)
        // Nothing else rides along: no video, no file, no player.
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(setOf(Intent.EXTRA_EMAIL, Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), intent.extras!!.keySet())
    }
}
