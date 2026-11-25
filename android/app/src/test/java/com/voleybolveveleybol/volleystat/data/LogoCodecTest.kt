package com.voleybolveveleybol.volleystat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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

    /**
     * P7-C7 (M3): the app reads at most SOURCE_MAX_BYTES of a picked file. A picture followed by bytes of nothing decodes
     * up to the limit; one byte more reads as a picture the app can not open, so a huge or endless file never fills the
     * memory.
     */
    @Test fun aPickedFileAboveTheLimitIsNotRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val png = ByteArrayOutputStream().also { bands(300, 200).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        fun padded(name: String, size: Int) = File(context.cacheDir, name).apply {
            outputStream().buffered().use { out ->
                out.write(png)
                val zeros = ByteArray(64 * 1024)
                var left = size - png.size
                while (left > 0) {
                    val count = minOf(left, zeros.size)
                    out.write(zeros, 0, count)
                    left -= count
                }
            }
        }
        val atLimit = padded("am-limit.png", LogoCodec.SOURCE_MAX_BYTES)
        assertEquals(LogoCodec.SOURCE_MAX_BYTES.toLong(), atLimit.length())
        val read = LogoCodec.decodeSource(context.contentResolver, Uri.fromFile(atLimit))
        assertEquals(300 to 200, read.width to read.height)
        atLimit.delete()
        val above = padded("above.png", LogoCodec.SOURCE_MAX_BYTES + 1)
        assertTrue(runCatching { LogoCodec.decodeSource(context.contentResolver, Uri.fromFile(above)) }.exceptionOrNull() is LogoCodec.Unreadable)
        above.delete()
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
     * A camera JPEG stored as 600 × 300 pixels with [orientation] in its EXIF: a red bar along the stored top edge and a
     * green bar along the stored left edge, blue elsewhere.
     */
    private fun cameraJpeg(orientation: Int): ByteArray {
        val stored = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        Canvas(stored).apply {
            drawRect(0f, 0f, 600f, 40f, Paint().apply { color = Color.RED })
            drawRect(0f, 40f, 40f, 300f, Paint().apply { color = Color.GREEN })
        }
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "camera-$orientation.jpg")
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        assertEquals(orientation, ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        return file.readBytes().also { file.delete() }
    }

    private enum class Edge { TOP, RIGHT, BOTTOM, LEFT }

    /** The colour in the middle of an [edge], 8 px inside the picture: R, G or B. */
    private fun Bitmap.edge(edge: Edge): Char {
        val pixel = when (edge) {
            Edge.TOP -> getPixel(width / 2, 8)
            Edge.RIGHT -> getPixel(width - 9, height / 2)
            Edge.BOTTOM -> getPixel(width / 2, height - 9)
            Edge.LEFT -> getPixel(8, height / 2)
        }
        return when {
            Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90 -> 'R'
            Color.green(pixel) > 180 && Color.red(pixel) < 90 && Color.blue(pixel) < 90 -> 'G'
            Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 90 -> 'B'
            else -> '?'
        }
    }

    /**
     * P7-C1: ImageDecoder (API 28+) turns a photo by its EXIF orientation, BitmapFactory (API 26 and 27) does not. The
     * app's API 26-27 path reads the orientation and turns or mirrors the picture the same way, for all eight values, so a
     * portrait camera photo becomes an upright logo on every API level.
     */
    @Test fun theApi26PathTurnsAPhotoByItsExifOrientationAsImageDecoderDoes() {
        // Where the red bar (stored top edge) and the green bar (stored left edge) are seen, by the EXIF definition.
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to (Edge.TOP to Edge.LEFT),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (Edge.TOP to Edge.RIGHT),
            ExifInterface.ORIENTATION_ROTATE_180 to (Edge.BOTTOM to Edge.RIGHT),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to (Edge.BOTTOM to Edge.LEFT),
            ExifInterface.ORIENTATION_TRANSPOSE to (Edge.LEFT to Edge.TOP),
            ExifInterface.ORIENTATION_ROTATE_90 to (Edge.RIGHT to Edge.TOP),
            ExifInterface.ORIENTATION_TRANSVERSE to (Edge.RIGHT to Edge.BOTTOM),
            ExifInterface.ORIENTATION_ROTATE_270 to (Edge.LEFT to Edge.BOTTOM),
        )
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        expected.forEach { (orientation, bars) ->
            val (red, green) = bars
            val jpeg = cameraJpeg(orientation)
            val turned = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
            val size = if (turned) 300 to 600 else 600 to 300
            val modern = LogoCodec.decode(jpeg, LogoCodec.SOURCE_SIDE)!!
            val old = LogoCodec.viaBitmapFactory({ jpeg.inputStream() }, LogoCodec.SOURCE_SIDE)!!
            // The picked picture through the whole API 26-27 path: the file, the bounds and the orientation.
            val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "picked-$orientation.jpg").apply { writeBytes(jpeg) }
            val picked = LogoCodec.decodeSource(resolver, Uri.fromFile(file), sdk = 26)
            file.delete()
            listOf("ImageDecoder" to modern, "BitmapFactory" to old, "decodeSource on API 26" to picked).forEach { (path, bitmap) ->
                assertEquals("$path, orientation $orientation", size, bitmap.width to bitmap.height)
                assertEquals("$path, orientation $orientation: the red bar", 'R', bitmap.edge(red))
                assertEquals("$path, orientation $orientation: the green bar", 'G', bitmap.edge(green))
                Edge.entries.filter { it != red && it != green }.forEach { assertEquals("$path, orientation $orientation: $it", 'B', bitmap.edge(it)) }
            }
        }
        // A scaled picture turns too: its longer side keeps the bound.
        val small = LogoCodec.viaBitmapFactory({ cameraJpeg(ExifInterface.ORIENTATION_ROTATE_90).inputStream() }, 200)!!
        assertEquals(100 to 200, small.width to small.height)
        assertEquals('R', small.edge(Edge.RIGHT))
    }

    /**
     * P7-C4: a panorama or a long screenshot keeps at least LARGE px on its shorter side, so the centred square stays sharp.
     * The longer side may then exceed SOURCE_SIDE, up to SOURCE_LONG_SIDE.
     */
    @Test fun aWideOrTallPictureKeepsASharpSquare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 10:1 and 1:9 pictures, and one beyond SOURCE_LONG_SIDE's reach.
        mapOf((5000 to 500) to (2560 to 256), (1000 to 9000) to (256 to 2304), (12000 to 1500) to (2048 to 256), (4000 to 1000) to (1024 to 256))
            .forEach { (source, decoded) ->
                assertEquals("$source", decoded, LogoCodec.sourceSize(source.first, source.second))
            }
        assertEquals("the longer side stays at most SOURCE_LONG_SIDE", LogoCodec.SOURCE_LONG_SIDE to 205, LogoCodec.sourceSize(20000, 1000))
        assertEquals("a usual photo as before", 1024 to 683, LogoCodec.sourceSize(3000, 2000))
        assertEquals("a small picture keeps its size", 300 to 200, LogoCodec.sourceSize(300, 200))
        // A 10:1 picture through both decoders: the square's source edge is LARGE px, not 102.
        val file = File(context.cacheDir, "panorama.png").apply { writeBytes(ByteArrayOutputStream().also { bands(5000, 500).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }
        listOf(35, 26).forEach { sdk ->
            val picked = LogoCodec.decodeSource(context.contentResolver, Uri.fromFile(file), sdk = sdk)
            assertEquals("API $sdk", 2560 to 256, picked.width to picked.height)
            assertTrue("API $sdk", minOf(picked.width, picked.height) >= LogoCodec.LARGE)
            // The green middle fills the square.
            val square = LogoCodec.square(picked, LogoCodec.LARGE)
            listOf(2 to 2, 253 to 253, 128 to 128).forEach { (x, y) -> assertTrue("API $sdk: $x,$y", square.getPixel(x, y).isGreen()) }
        }
        file.delete()
    }

    /**
     * P7-C5: a logo made for dark shirts, white art on a transparent background, gets a dark background, so the crest
     * stays visible. Dark art keeps the white background (see theBitmapHasNoAlpha), and an opaque picture shows no
     * background at all.
     */
    @Test fun lightArtOnATransparentPictureGetsADarkBackground() {
        fun art(color: Int) = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            Canvas(this).drawCircle(200f, 200f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The white crest as a PNG file the user picks.
        val file = File(context.cacheDir, "beyaz-arma.png").apply { writeBytes(ByteArrayOutputStream().also { art(Color.WHITE).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()) }
        val white = LogoCodec.square(LogoCodec.decodeSource(context.contentResolver, Uri.fromFile(file)), LogoCodec.LARGE)
        file.delete()
        assertEquals("the corner", LogoCodec.DARK_BACKGROUND, white.getPixel(2, 2))
        assertEquals("the crest", Color.WHITE, white.getPixel(128, 128))
        assertFalse(white.hasAlpha())
        // A light yellow crest too; a dark blue one and a black one keep the white background.
        assertEquals(LogoCodec.DARK_BACKGROUND, LogoCodec.square(art(Color.rgb(250, 220, 80)), LogoCodec.LARGE).getPixel(2, 2))
        assertEquals(LogoCodec.BACKGROUND, LogoCodec.square(art(Color.rgb(20, 40, 140)), LogoCodec.LARGE).getPixel(2, 2))
        assertEquals(LogoCodec.BACKGROUND, LogoCodec.square(art(Color.BLACK), LogoCodec.LARGE).getPixel(2, 2))
        // The encoder gets the dark corner, the white crest and no alpha, for both files.
        val recording = Recording()
        LogoCodec.encode(art(Color.WHITE), sdk = 35, compressor = recording)
        assertEquals(2, recording.calls.size)
        recording.calls.forEach { call ->
            assertFalse(call.hasAlpha)
            assertEquals(LogoCodec.DARK_BACKGROUND, call.corner)
            assertEquals(Color.WHITE, call.centre)
        }
        // An opaque white picture with a black dot: no background shows, its corner stays white.
        val opaque = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawCircle(200f, 200f, 20f, Paint().apply { color = Color.BLACK })
        }
        assertEquals(Color.WHITE, LogoCodec.square(opaque, LogoCodec.LARGE).getPixel(2, 2))
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
