package com.voleybolveveleybol.volleystat.data

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
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
    /** The longest side the app decodes a picked picture to before it crops: enough for a sharp 256 px square. */
    const val SOURCE_SIDE = 1024
    /** The largest picture file the app reads: a phone's photo is far smaller. A larger file reads as one the app can not open. */
    const val SOURCE_MAX_BYTES = 40 * 1024 * 1024
    /** The background a picture's transparent parts get, so the files need no alpha. */
    const val BACKGROUND = Color.WHITE

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

    /** The centred square of [source] at [side] px on an opaque background, in a bitmap without alpha. */
    fun square(source: Bitmap, side: Int): Bitmap {
        val edge = min(source.width, source.height)
        val left = (source.width - edge) / 2
        val top = (source.height - edge) / 2
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(BACKGROUND)
            drawBitmap(source, Rect(left, top, left + edge, top + edge), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        // Every pixel is opaque now; the encoder writes no alpha chunk for such a bitmap.
        out.setHasAlpha(false)
        return out
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

    /** A logo the server sent, at most [side] px on its longer side, or null when it can not be decoded (the screen shows initials). */
    fun decode(bytes: ByteArray, side: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) viaImageDecoder(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), side)
        else viaBitmapFactory({ ByteArrayInputStream(bytes) }, side)
    }.getOrNull()

    /**
     * The picture the user picked, at most [side] px on its longer side. The app reads the file's bytes first (at most
     * [SOURCE_MAX_BYTES]) and decodes them as it decodes a logo, with the same bounds on every API level. Throws
     * [Unreadable] when it can not be read.
     */
    fun decodeSource(resolver: ContentResolver, uri: Uri, side: Int = SOURCE_SIDE): Bitmap = try {
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
        decode(bytes, side) ?: throw Unreadable()
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

    @RequiresApi(Build.VERSION_CODES.P)
    private fun viaImageDecoder(source: ImageDecoder.Source, side: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = bounded(info.size.width, info.size.height, side)
        decoder.setTargetSize(width, height)
        // A software bitmap, so the app can draw it into the logo's square.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /**
     * API 26 and 27: the bounds first, then a power of two that keeps the decoded picture at least [side] px on its longer
     * side, then a scale to the bounds.
     */
    fun viaBitmapFactory(open: () -> InputStream, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val (width, height) = bounded(decoded.width, decoded.height, side)
        return if (width == decoded.width && height == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
    }
}
