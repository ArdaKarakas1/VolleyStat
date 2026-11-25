package com.voleybolveveleybol.volleystat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The logo's files on the phone (design 4.2 and 10.5, plan P7): the centred square on an opaque background, the lossy WebP
 * format of each API level at quality 80, the steps down to the server's byte limits, and decoding with bounds. The
 * encoder itself runs on an emulator in LogoDeviceTest, which sends its files to the real server.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LogoCodecTest {
    private class Call(val width: Int, val height: Int, val format: Bitmap.CompressFormat, val quality: Int, val hasAlpha: Boolean, val corner: Int, val centre: Int)

    /** Records what the codec hands the encoder and answers with [size] bytes for each call. */
    private class Recording(val size: (Call) -> Int = { 1000 }) : LogoCodec.Compressor {
        val calls = mutableListOf<Call>()
        override fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
            val call = Call(bitmap.width, bitmap.height, format, quality, bitmap.hasAlpha(), bitmap.getPixel(0, 0), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            calls += call
            return ByteArray(size(call))
        }
    }

    /** A picture of three bands across its longer side: red, green in the middle, blue. */
    private fun bands(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val wide = width >= height
        val edge = (if (wide) width - height else height - width) / 2
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
            paint.color = color
            val from = when (index) { 0 -> 0; 1 -> edge; else -> (if (wide) width else height) - edge }
            val to = when (index) { 0 -> edge; 1 -> (if (wide) width else height) - edge; else -> if (wide) width else height }
            if (wide) canvas.drawRect(from.toFloat(), 0f, to.toFloat(), height.toFloat(), paint) else canvas.drawRect(0f, from.toFloat(), width.toFloat(), to.toFloat(), paint)
        }
        return bitmap
    }

    private fun Int.isGreen() = Color.green(this) > 200 && Color.red(this) < 60 && Color.blue(this) < 60

    @Test fun theCropIsTheCentredSquare() {
        listOf(bands(600, 400), bands(400, 600)).forEach { source ->
            val square = LogoCodec.square(source, LogoCodec.LARGE)
            assertEquals(LogoCodec.LARGE, square.width)
            assertEquals(LogoCodec.LARGE, square.height)
            // Only the green middle band lies in the centred square: its edges and its corners are green too.
            listOf(2 to 2, 253 to 2, 2 to 253, 253 to 253, 128 to 128, 2 to 128, 253 to 128).forEach { (x, y) ->
                assertTrue("pixel $x,$y of a ${source.width}x${source.height} picture", square.getPixel(x, y).isGreen())
            }
            val recording = Recording()
            LogoCodec.encode(source, sdk = 35, compressor = recording)
            assertEquals(listOf(LogoCodec.LARGE to LogoCodec.LARGE, LogoCodec.SMALL to LogoCodec.SMALL), recording.calls.map { it.width to it.height })
            assertTrue(recording.calls.all { it.centre.isGreen() && it.corner.isGreen() })
        }
    }

    @Suppress("DEPRECATION")
    @Test fun api26To29UseWebpAndApi30AndLaterWebpLossyAtQuality80() {
        (26..29).forEach { assertEquals("API $it", Bitmap.CompressFormat.WEBP, LogoCodec.format(it)) }
        (30..36).forEach { assertEquals("API $it", Bitmap.CompressFormat.WEBP_LOSSY, LogoCodec.format(it)) }
        listOf(26 to Bitmap.CompressFormat.WEBP, 30 to Bitmap.CompressFormat.WEBP_LOSSY).forEach { (sdk, format) ->
            val recording = Recording()
            val (large, small) = LogoCodec.encode(bands(500, 300), sdk = sdk, compressor = recording)
            assertEquals(1000, large.size)
            assertEquals(1000, small.size)
            assertEquals("one call per file, at quality 80", listOf(80, 80), recording.calls.map { it.quality })
            assertTrue(recording.calls.all { it.format == format })
        }
        // Quality 80 stays below 100: WEBP before API 30 is lossless only at 100.
        assertTrue(LogoCodec.QUALITIES.all { it < 100 })
        assertEquals(80, LogoCodec.QUALITIES.first())
    }

    @Test fun aTooLargeResultStepsTheQualityDownAndThenGivesUp() {
        // The large file fits at 70, the small one only at 60.
        val sizes = mapOf(
            (LogoCodec.LARGE to 80) to LogoCodec.LARGE_MAX_BYTES + 1, (LogoCodec.LARGE to 70) to LogoCodec.LARGE_MAX_BYTES,
            (LogoCodec.SMALL to 80) to LogoCodec.SMALL_MAX_BYTES + 500, (LogoCodec.SMALL to 70) to LogoCodec.SMALL_MAX_BYTES + 1, (LogoCodec.SMALL to 60) to 5000,
        )
        val recording = Recording { sizes.getValue(it.width to it.quality) }
        val (large, small) = LogoCodec.encode(bands(300, 300), sdk = 35, compressor = recording)
        assertEquals(LogoCodec.LARGE_MAX_BYTES, large.size)
        assertEquals(5000, small.size)
        assertEquals(listOf(80, 70, 80, 70, 60), recording.calls.map { it.quality })

        // Nothing fits: the app gives up after quality 60 and sends nothing.
        val never = Recording { if (it.width == LogoCodec.LARGE) LogoCodec.LARGE_MAX_BYTES + 1 else 10 }
        try {
            LogoCodec.encode(bands(300, 300), sdk = 35, compressor = never)
            fail("a logo above the limit")
        } catch (expected: LogoCodec.TooLarge) {
            assertEquals(listOf(80, 70, 60), never.calls.map { it.quality })
        }
    }

    @Test fun theBitmapHasNoAlpha() {
        // A transparent picture with an opaque red middle.
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.TRANSPARENT)
        Canvas(source).drawRect(150f, 150f, 250f, 250f, Paint().apply { color = Color.RED })
        val recording = Recording()
        LogoCodec.encode(source, sdk = 35, compressor = recording)
        assertEquals(2, recording.calls.size)
        recording.calls.forEach { call ->
            assertFalse("the encoder gets a bitmap without alpha", call.hasAlpha)
            assertEquals("a transparent part becomes the opaque background", LogoCodec.BACKGROUND, call.corner)
            assertEquals(255, Color.alpha(call.centre))
            assertTrue(Color.red(call.centre) > 200 && Color.green(call.centre) < 60)
        }
    }

    @Test fun aFailedDecodeGivesNone() {
        val garbage = "this is no picture".toByteArray()
        assertNull(LogoCodec.decode(garbage, LogoCodec.LARGE))
        assertNull(LogoCodec.decode(ByteArray(0), LogoCodec.LARGE))
        assertNull("API 26 and 27", LogoCodec.viaBitmapFactory({ garbage.inputStream() }, LogoCodec.LARGE))
        // A picked picture the app can not open or decode.
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val missing = Uri.fromFile(File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "yok.png"))
        assertTrue(runCatching { LogoCodec.decodeSource(resolver, missing) }.exceptionOrNull() is LogoCodec.Unreadable)
        val notAPicture = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "metin.png").apply { writeBytes(garbage) }
        assertTrue(runCatching { LogoCodec.decodeSource(resolver, Uri.fromFile(notAPicture)) }.exceptionOrNull() is LogoCodec.Unreadable)
    }

    @Test fun decodingKeepsItsBounds() {
        val png = ByteArrayOutputStream().also { bands(1000, 500).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        LogoCodec.decode(png, LogoCodec.LARGE)!!.let { assertEquals(LogoCodec.LARGE to LogoCodec.LARGE / 2, it.width to it.height) }
        LogoCodec.viaBitmapFactory({ png.inputStream() }, LogoCodec.SMALL)!!.let { assertEquals(LogoCodec.SMALL to LogoCodec.SMALL / 2, it.width to it.height) }
        // A picture smaller than the bound keeps its size.
        LogoCodec.decode(png, 2000)!!.let { assertEquals(1000 to 500, it.width to it.height) }
        // A picked picture: its longer side at most SOURCE_SIDE.
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "logo.png").apply { writeBytes(ByteArrayOutputStream().also { bands(3000, 2000).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }
        val picked = LogoCodec.decodeSource(ApplicationProvider.getApplicationContext<Context>().contentResolver, Uri.fromFile(file))
        assertEquals("3000 × 2000 scaled by 1024 / 3000", 1024 to 683, picked.width to picked.height)
    }

    /**
     * The platform's encoder writes what the server takes (design 4.2): RIFF with the file's length, WEBP, exactly one
     * `VP8 ` chunk (lossy, no VP8X, no alpha, no metadata) that ends the file, a key frame with the start code 9d 01 2a,
     * and the logo's sides. LogoDeviceTest sends such files to the real server from API 34 and API 26 emulators.
     */
    @Test fun thePlatformEncoderWritesASimpleLossyWebp() {
        val source = bands(640, 480)
        val (large, small) = LogoCodec.encode(source, sdk = 35)
        listOf(large to LogoCodec.LARGE, small to LogoCodec.SMALL).forEach { (file, side) ->
            fun le32(at: Int) = (file[at].toInt() and 0xff) or ((file[at + 1].toInt() and 0xff) shl 8) or ((file[at + 2].toInt() and 0xff) shl 16) or ((file[at + 3].toInt() and 0xff) shl 24)
            assertEquals("RIFF", String(file, 0, 4, Charsets.US_ASCII))
            assertEquals(file.size - 8, le32(4))
            assertEquals("WEBP", String(file, 8, 4, Charsets.US_ASCII))
            assertEquals("simple lossy: one VP8 chunk", "VP8 ", String(file, 12, 4, Charsets.US_ASCII))
            val chunk = le32(16)
            assertEquals("the chunk, with its padding byte, ends the file", file.size, 20 + chunk + (chunk and 1))
            assertEquals("a key frame", 0, file[20].toInt() and 1)
            assertEquals(listOf(0x9d, 0x01, 0x2a), listOf(file[23], file[24], file[25]).map { it.toInt() and 0xff })
            val width = ((file[26].toInt() and 0xff) or ((file[27].toInt() and 0xff) shl 8))
            val height = ((file[28].toInt() and 0xff) or ((file[29].toInt() and 0xff) shl 8))
            assertEquals("no scale bits and the logo's side", side to side, width to height)
        }
        assertTrue(large.size <= LogoCodec.LARGE_MAX_BYTES)
        assertTrue(small.size <= LogoCodec.SMALL_MAX_BYTES)
    }

    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        chunks.forEach { (id, data) ->
            body.write(id.toByteArray())
            body.write(byteArrayOf(data.size.toByte(), (data.size shr 8).toByte(), (data.size shr 16).toByte(), (data.size shr 24).toByte()))
            body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        val size = body.size()
        return "RIFF".toByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()) + body.toByteArray()
    }

    /**
     * The platform's encoder (Skia) writes a VP8X header and the bitmap's sRGB colour profile (ICCP) before the frame,
     * which the server refuses (a VP8X file). The app keeps only the lossy frame: the pixels are sRGB already, so the
     * picture looks the same without the profile. Any other chunk means another kind of file, and the app sends nothing.
     */
    @Test fun theEncodersExtendedFileBecomesASimpleLossyFile() {
        val frame = ByteArray(183) { (it * 3).toByte() }
        val extended = riff("VP8X" to ByteArray(10).also { it[0] = 0x20 }, "ICCP" to ByteArray(456) { 7 }, "VP8 " to frame)
        val simple = LogoCodec.simpleWebp(extended)
        assertTrue("the frame and its padding byte, nothing else", simple.contentEquals(riff("VP8 " to frame)))
        assertEquals(12 + 8 + 183 + 1, simple.size)
        assertEquals(0, simple.last().toInt())
        // A simple file stays as it is.
        assertTrue(LogoCodec.simpleWebp(riff("VP8 " to frame)).contentEquals(riff("VP8 " to frame)))
        // Alpha, a lossless frame, two frames, metadata, a cut file or no frame: not a logo the server takes.
        listOf(
            riff("VP8X" to ByteArray(10), "ALPH" to ByteArray(9), "VP8 " to frame),
            riff("VP8L" to ByteArray(40)),
            riff("VP8 " to frame, "VP8 " to frame),
            riff("VP8X" to ByteArray(10), "VP8 " to frame, "EXIF" to ByteArray(12)),
            riff("VP8X" to ByteArray(10), "ICCP" to ByteArray(20)),
            riff("VP8 " to frame).copyOf(100),
            "not a webp at all".toByteArray(),
        ).forEachIndexed { index, file ->
            assertTrue("case $index", runCatching { LogoCodec.simpleWebp(file) }.exceptionOrNull() is LogoCodec.NotSimple)
        }
    }
}
