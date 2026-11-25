package com.voleybolveveleybol.volleystat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.LogoCodec
import com.voleybolveveleybol.volleystat.model.Seat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The logo the app makes on this device's own encoder, against the backend on the development computer (design 4.2 and
 * 10.5, plan P7): the app encodes a synthetic test pattern with LogoCodec, a team the test creates takes both files with
 * 200, and the server sends the same bytes back. CI runs it on API 34 in the device job and on API 26, where the app
 * encodes with CompressFormat.WEBP instead of WEBP_LOSSY, in a job of its own. Skipped when the backend does not answer.
 * The test opens an e2e_ account (VOLLEYSTAT_SIGNUP=open) and deletes it, which deletes its team with the logo. A second
 * test, which needs no backend, turns a camera photo by its EXIF orientation on this device's decoder (P7-C1).
 */
@RunWith(AndroidJUnit4::class)
class LogoDeviceTest {
    private val url = BuildConfig.API_URL_EMULATOR

    /** A test pattern with a gradient, shapes and a transparent corner: no real picture. */
    private fun testPattern(): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 900f, 600f, Color.rgb(90, 40, 160), Color.rgb(240, 200, 40), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 700f, 600f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(450f, 300f, 180f, paint)
        paint.color = Color.rgb(20, 20, 20)
        paint.textSize = 160f
        canvas.drawText("VV", 330f, 360f, paint)
        return bitmap
    }

    @Test fun theAppsLogoIsAcceptedByTheServer() {
        assumeTrue("needs the backend on $url", runCatching { ApiClient(url).health() }.isSuccess)
        val password = "logo şifresi ${UUID.randomUUID()}"
        val account = ApiClient(url).register("e2e_" + UUID.randomUUID().toString().replace("-", "").take(20), "Logo Cihaz", password, null)
        val api = ApiClient(url, token = account.token)
        try {
            val team = api.putTeam(UUID.randomUUID().toString(), "Logo Cihaz Spor", 2, Seat.STARTER, null, null).first
            val (large, small) = LogoCodec.encode(testPattern())
            assertTrue("API ${Build.VERSION.SDK_INT}: ${large.size} bytes", large.size <= LogoCodec.LARGE_MAX_BYTES)
            assertTrue("API ${Build.VERSION.SDK_INT}: ${small.size} bytes", small.size <= LogoCodec.SMALL_MAX_BYTES)
            // The server answers 200 with the logo's version only for files its strict lossy WebP check takes.
            val version = api.putLogo(team.id, large, small)
            assertTrue(version >= 1)
            assertEquals(version, api.team(team.id).logoVersion)
            val readLarge = api.logo(team.id, "large")
            assertArrayEquals(large, readLarge)
            assertArrayEquals(small, api.logo(team.id, "small"))
            // The app decodes what it reads back, within its bounds.
            val shown = LogoCodec.decode(readLarge!!, LogoCodec.LARGE)
            assertNotNull(shown)
            assertEquals(LogoCodec.LARGE, shown!!.width)
        } finally {
            runCatching { api.deleteAccount(password) }
        }
    }

    /** The colour at [x], [y]: R, G or B, and ? for any other. */
    private fun Bitmap.colourAt(x: Int, y: Int): Char {
        val pixel = getPixel(x, y)
        return when {
            Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90 -> 'R'
            Color.green(pixel) > 180 && Color.red(pixel) < 90 && Color.blue(pixel) < 90 -> 'G'
            Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 90 -> 'B'
            else -> '?'
        }
    }

    /**
     * P7-C1: a portrait camera photo that the camera stored as landscape pixels with EXIF orientation 6. ImageDecoder
     * turns it from API 28; on API 26 and 27 the app turns it itself. On both, the picked picture and the logo the app
     * sends stand upright. The photo is synthetic: a red bar along the stored top edge, a green one along the stored left
     * edge, blue elsewhere.
     */
    @Test fun aCameraPhotoTurnedByItsExifBecomesAnUprightLogo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        Canvas(stored).apply {
            drawRect(0f, 0f, 600f, 40f, Paint().apply { color = Color.RED })
            drawRect(0f, 40f, 40f, 300f, Paint().apply { color = Color.GREEN })
        }
        val file = File(context.cacheDir, "kamera-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val api = "API ${Build.VERSION.SDK_INT}"
            val picked = LogoCodec.decodeSource(context.contentResolver, Uri.fromFile(file))
            assertEquals(api, 300 to 600, picked.width to picked.height)
            // The stored top edge is the right edge now, the stored left edge the top.
            assertEquals(api, 'R', picked.colourAt(picked.width - 9, picked.height / 2))
            assertEquals(api, 'G', picked.colourAt(picked.width / 2, 8))
            assertEquals(api, 'B', picked.colourAt(8, picked.height / 2))
            // The logo the app would send: the centred square, with the red bar on its right edge, through this device's encoder.
            val (large, _) = LogoCodec.encode(picked)
            val logo = LogoCodec.decode(large, LogoCodec.LARGE)
            assertNotNull(api, logo)
            assertEquals(api, 'R', logo!!.colourAt(logo.width - 9, logo.height / 2))
            assertEquals(api, 'B', logo.colourAt(8, logo.height / 2))
            assertEquals(api, 'B', logo.colourAt(logo.width / 2, 8))
        } finally {
            file.delete()
        }
    }
}
