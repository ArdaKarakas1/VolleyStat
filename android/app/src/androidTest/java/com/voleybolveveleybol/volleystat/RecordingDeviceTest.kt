package com.voleybolveveleybol.volleystat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.voleybolveveleybol.volleystat.ui.formatClock
import com.voleybolveveleybol.volleystat.ui.screens.RecordRoute
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The recording screen on a device with a camera: the recorder starts, its own clock runs on
 * the screen, and stopping leaves a video file that the platform can read back.
 */
@RunWith(AndroidJUnit4::class)
class RecordingDeviceTest {
    @get:Rule(order = 0) val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun theCameraRecordsAVideoFileThePlatformCanRead() {
        val context = screen.activity
        assumeTrue("this device has no camera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val file = File(context.cacheDir, "recording-device-test.mp4").apply { delete() }
        var finished = false
        screen.setContent {
            VolleyStatTheme { RecordRoute(prepareFile = { file }, onRecorded = { finished = true }, onUseGallery = {}, onBack = {}) }
        }
        val start = context.getString(R.string.record_start)
        val stop = context.getString(R.string.record_stop)

        screen.waitUntil(30_000) { screen.onAllNodesWithContentDescription(start).fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithContentDescription(start).performClick()
        // The clock on the screen is the recorder's own count of recorded time, so reaching
        // three seconds proves that frames are being written, not only that a button was pressed.
        screen.waitUntil(60_000) { (3L..30L).any { second -> screen.onAllNodesWithText(formatClock(second * 1000)).fetchSemanticsNodes().isNotEmpty() } }
        screen.onNodeWithContentDescription(stop).performClick()
        screen.waitUntil(30_000) { finished }

        assertTrue("the recording is empty", file.length() > 0)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            // The app records no sound.
            assertEquals(null, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            assertNotNull(duration)
            assertTrue("recorded $duration ms", duration!! >= 2_000)
            assertNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        } finally {
            retriever.release()
            file.delete()
        }
    }
}
