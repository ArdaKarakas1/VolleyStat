package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.ui.screens.playbackDataSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The players of the review clip and of the range editor read the video with the session in a
 * header (design 10.5). The address stays the video's own: no token ever stands in a URL.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackHeadersTest {
    private val server = MockWebServer()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun read(headers: Map<String, String>) {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setBody(Buffer().write(ByteArray(16))))
        val source = playbackDataSource(context, headers).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(server.url("/v1/videos/video-1/content").toString())))
            source.read(ByteArray(16), 0, 16)
        } finally {
            source.close()
        }
    }

    @Test fun theVideoIsAskedForWithTheSessionHeaderAtItsOwnAddress() {
        read(mapOf("Authorization" to "Bearer session-token-1"))
        val request = server.takeRequest()
        assertEquals("Bearer session-token-1", request.getHeader("Authorization"))
        assertEquals("/v1/videos/video-1/content", request.path)
        assertFalse(request.path!!.contains("session-token-1"))
    }

    @Test fun withoutASessionNoHeaderGoesOut() {
        read(emptyMap())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
