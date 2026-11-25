package com.voleybolveveleybol.volleystat.data

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A team's logo on the phone (design 4.2 and 10.5; plan P7). The app crops the picked picture to its centred square,
 * draws it on an opaque background in a bitmap without alpha, and encodes two lossy WebP files: 256 px for members and
 * 128 px for link views. The server takes only a simple lossy WebP (one `VP8 ` chunk, no alpha, no metadata) of at most
 * 36 KiB and 8 KiB. Decoding always names its bounds, so a large picture never fills the memory.
 */
object LogoCodec {
    const val LARGE = 256
    const val SMALL = 128
    const val LARGE_MAX_BYTES = 36 * 1024
    const val SMALL_MAX_BYTES = 8 * 1024
    /** The qualities the app tries, best first, until a file fits its limit (design 4.2: quality 80). */
    val QUALITIES = listOf(80, 70, 60)
    /**
     * The longest side the app decodes a picked picture to before it crops ([sourceSize]), unless the shorter side would
     * then end below [LARGE]: a usual photo keeps more than 256 px for the square.
     */
    const val SOURCE_SIDE = 1024
    /**
     * The longest side a wide or tall picture may keep so that its shorter side reaches [LARGE] (P7-C4): a panorama up to
     * 16:1 keeps a sharp square, and the decoded picture stays at most 4096 × 256 px (4 MiB).
     */
    const val SOURCE_LONG_SIDE = 4096
    /** The largest picture file the app reads: a phone's photo is far smaller. A larger file reads as one the app can not open. */
    const val SOURCE_MAX_BYTES = 40 * 1024 * 1024
    /** The background a picture's transparent parts get, so the files need no alpha. */
    const val BACKGROUND = Color.WHITE
    /**
     * The background of light art on a transparent picture, such as a white crest made for dark shirts (P7-C5): the app's
     * ink colour.
     */
    const val DARK_BACKGROUND = 0xFF111827.toInt()
    /**
     * The relative luminance above which a colour counts as light: its contrast with white stays below 3:1, the least
     * that WCAG 1.4.11 asks of a graphical object.
     */
    const val LIGHT_LUMINANCE = 0.3f

    /** The picked picture could not be opened or decoded. */
    class Unreadable(cause: Throwable? = null) : Exception("the picture can not be read", cause)

    /** No quality of [QUALITIES] made the file as small as the server takes it. */
    class TooLarge : Exception("the logo stays above the server's limit")

    /** The encoder wrote something else than one lossy frame: alpha, a lossless frame, metadata or a broken file. */
    class NotSimple : Exception("the encoder's file is no simple lossy WebP")

    /** Encodes a bitmap at a quality. The platform's encoder on the phone; a recording one in the JVM tests. */
    fun interface Compressor {
        fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray
    }

    /** The platform's encoder, whose file the app rewrites as the simple lossy WebP the server takes ([simpleWebp]). */
    val platform = Compressor { bitmap, format, quality ->
        simpleWebp(ByteArrayOutputStream().also { out -> check(bitmap.compress(format, quality, out)) { "the encoder refused the bitmap" } }.toByteArray())
    }

    /**
     * [file] as a simple lossy WebP: RIFF, WEBP and its one `VP8 ` chunk. The platform's encoder (Skia) writes a VP8X
     * header and the bitmap's sRGB colour profile (ICCP) before the frame, and the server refuses every VP8X file
     * (design 4.2). The app draws every logo into an sRGB bitmap, so the frame without the profile looks the same: the app
     * keeps the frame and drops these two. Any other chunk (alpha, a lossless frame, animation, metadata) or a broken file
     * throws [NotSimple], and nothing is sent.
     */
    fun simpleWebp(file: ByteArray): ByteArray {
        fun ascii(at: Int) = String(file, at, 4, Charsets.US_ASCII)
        fun le32(at: Int) = (file[at].toInt() and 0xff) or ((file[at + 1].toInt() and 0xff) shl 8) or
            ((file[at + 2].toInt() and 0xff) shl 16) or ((file[at + 3].toInt() and 0xff) shl 24)
        if (file.size < 20 || ascii(0) != "RIFF" || ascii(8) != "WEBP" || le32(4) != file.size - 8) throw NotSimple()
        var frame: ByteArray? = null
        var at = 12
        while (at < file.size) {
            if (at + 8 > file.size) throw NotSimple()
            val id = ascii(at)
            val length = le32(at + 4)
            val end = at + 8 + length
            if (length < 0 || end > file.size) throw NotSimple()
            when (id) {
                "VP8 " -> {
                    if (frame != null) throw NotSimple()
                    frame = file.copyOfRange(at + 8, end)
                }
                "VP8X", "ICCP" -> Unit
                else -> throw NotSimple()
            }
            at = end + (length and 1)
        }
        val data = frame ?: throw NotSimple()
        val padding = data.size and 1
        fun le32Bytes(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
        return ByteArrayOutputStream(20 + data.size + padding).apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(le32Bytes(4 + 8 + data.size + padding))
            write("WEBPVP8 ".toByteArray(Charsets.US_ASCII))
            write(le32Bytes(data.size))
            write(data)
            if (padding == 1) write(0)
        }.toByteArray()
    }

    /**
     * The lossy WebP format of [sdk]: `WEBP_LOSSY` from API 30, and before it `WEBP`, which encodes lossy below quality
     * 100 (design 10.5).
     */
    @SuppressLint("NewApi") // WEBP_LOSSY is read only when sdk is 30 or later.
    fun format(sdk: Int = Build.VERSION.SDK_INT): Bitmap.CompressFormat =
        if (sdk >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

    /**
     * The centred square of [source] at [side] px on an opaque background ([background]), in a bitmap without alpha.
     */
    fun square(source: Bitmap, side: Int): Bitmap {
        val edge = min(source.width, source.height)
        val left = (source.width - edge) / 2
        val top = (source.height - edge) / 2
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, Rect(left, top, left + edge, top + edge), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // The background goes behind the picture, as if it had been drawn first.
        canvas.drawColor(background(out), PorterDuff.Mode.DST_OVER)
        // Every pixel is opaque now; the encoder writes no alpha chunk for such a bitmap.
        out.setHasAlpha(false)
        return out
    }

    /**
     * The background under [picture]'s transparent parts: [BACKGROUND] (white), or [DARK_BACKGROUND] when most of what
     * the picture draws, weighed by its opacity, is light ([LIGHT_LUMINANCE]). A white crest on a transparent picture
     * would vanish on white (P7-C5). A picture without transparent parts shows no background, and gets white.
     */
    fun background(picture: Bitmap): Int {
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        var drawn = 0L
        var light = 0L
        var clear = false
        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            if (alpha < 255) clear = true
            if (alpha == 0) continue
            drawn += alpha
            if (Color.luminance(pixel or (0xff shl 24)) > LIGHT_LUMINANCE) light += alpha
        }
        return if (clear && light * 2 > drawn) DARK_BACKGROUND else BACKGROUND
    }

    /** The two files: (large, small). Throws [TooLarge] when a file stays above its limit at every quality. */
    fun encode(source: Bitmap, sdk: Int = Build.VERSION.SDK_INT, compressor: Compressor = platform): Pair<ByteArray, ByteArray> {
        val format = format(sdk)
        val large = fit(square(source, LARGE), LARGE_MAX_BYTES, format, compressor)
        val small = fit(square(source, SMALL), SMALL_MAX_BYTES, format, compressor)
        return large to small
    }

    private fun fit(bitmap: Bitmap, limit: Int, format: Bitmap.CompressFormat, compressor: Compressor): ByteArray {
        for (quality in QUALITIES) {
            val bytes = compressor.compress(bitmap, format, quality)
            if (bytes.size <= limit) return bytes
        }
        throw TooLarge()
    }

    /**
     * A logo the server sent, at most [side] px on its longer side, or null when it can not be decoded (the screen shows
     * initials). [sdk] picks the decoder: ImageDecoder from API 28, BitmapFactory before it.
     */
    fun decode(bytes: ByteArray, side: Int, sdk: Int = Build.VERSION.SDK_INT): Bitmap? = decode(bytes, sdk) { width, height -> bounded(width, height, side) }

    @SuppressLint("NewApi") // ImageDecoder is used only when sdk is 28 or later.
    private fun decode(bytes: ByteArray, sdk: Int, size: (Int, Int) -> Pair<Int, Int>): Bitmap? = runCatching {
        if (sdk >= Build.VERSION_CODES.P) viaImageDecoder(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), size)
        else viaBitmapFactory({ ByteArrayInputStream(bytes) }, size)
    }.getOrNull()

    /**
     * The picture the user picked, upright by its EXIF orientation and at the size [sourceSize] gives. The app reads the
     * file's bytes first (at most [SOURCE_MAX_BYTES]) and decodes them as it decodes a logo, with the same bounds on every
     * API level. Throws [Unreadable] when it can not be read.
     */
    fun decodeSource(resolver: ContentResolver, uri: Uri, sdk: Int = Build.VERSION.SDK_INT): Bitmap = try {
        val bytes = (resolver.openInputStream(uri) ?: throw Unreadable()).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > SOURCE_MAX_BYTES) throw Unreadable()
            }
            out.toByteArray()
        }
        decode(bytes, sdk, ::sourceSize) ?: throw Unreadable()
    } catch (error: Unreadable) {
        throw error
    } catch (error: Exception) {
        throw Unreadable(error)
    } catch (error: OutOfMemoryError) {
        throw Unreadable(error)
    }

    /** The size a picture of [width] × [height] gets when its longer side may be at most [side]. */
    fun bounded(width: Int, height: Int, side: Int): Pair<Int, Int> {
        val scale = min(1.0, side.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * The size a picked picture of [width] × [height] gets before the app crops its square (P7-C4): at most [SOURCE_SIDE]
     * on its longer side, unless its shorter side would then end below [LARGE]. A wide or tall picture keeps [LARGE] px on
     * its shorter side instead, with its longer side at most [SOURCE_LONG_SIDE]. A picture is never enlarged.
     */
    fun sourceSize(width: Int, height: Int): Pair<Int, Int> {
        val longer = max(width, height).toDouble()
        val shorter = min(width, height).toDouble()
        val scale = minOf(1.0, SOURCE_LONG_SIDE / longer, max(SOURCE_SIDE / longer, LARGE / shorter))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * ImageDecoder reports the size after the EXIF orientation and turns the picture by it, so [size] gets the upright
     * picture's size.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun viaImageDecoder(source: ImageDecoder.Source, size: (Int, Int) -> Pair<Int, Int>): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = size(info.size.width, info.size.height)
        decoder.setTargetSize(width, height)
        // A software bitmap, so the app can draw it into the logo's square.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** API 26 and 27, at most [side] px on the longer side: see the other viaBitmapFactory. */
    fun viaBitmapFactory(open: () -> InputStream, side: Int): Bitmap? = viaBitmapFactory(open) { width, height -> bounded(width, height, side) }

    /**
     * API 26 and 27: the bounds first, then a power of two that keeps the decoded picture at least as large as [size]
     * asks, then a scale to that size. BitmapFactory ignores the EXIF orientation that ImageDecoder applies from API 28,
     * so the app reads it with ExifInterface and turns or mirrors the picture the same way (P7-C1): a portrait camera
     * photo stored as landscape pixels becomes an upright logo on every API level. [size] treats both sides alike, so the
     * app sizes the stored pixels before it turns them.
     */
    fun viaBitmapFactory(open: () -> InputStream, size: (Int, Int) -> Pair<Int, Int>): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (width, height) = size(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        // A file without EXIF, or one ExifInterface can not read, stays as it is stored.
        val orientation = runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val turn = orientationMatrix(orientation)
        if (width == decoded.width && height == decoded.height && turn == null) return decoded
        val matrix = Matrix().apply {
            setScale(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
            turn?.let(::postConcat)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /**
     * The turn or mirror that shows a picture stored with the EXIF [orientation] upright, or null for none (EXIF 2.3,
     * tag 0x0112). Bitmap.createBitmap moves the result back into view, so only the turn and the mirror matter.
     */
    fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }
}
