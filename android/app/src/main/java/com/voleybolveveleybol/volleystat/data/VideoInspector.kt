package com.voleybolveveleybol.volleystat.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.VideoSource
import java.io.File
import java.io.InputStream

/** Reads what the phone can know about a chosen video without uploading it. */
class VideoInspector(private val context: Context) {

    fun describeRecording(file: File): LocalVideo? {
        if (!file.isFile || file.length() <= 0) return null
        val facts = readFacts { it.setDataSource(file.absolutePath) }
        return LocalVideo(
            location = file.absolutePath, source = VideoSource.CAMERA, fileName = file.name,
            sizeBytes = file.length(), mimeType = facts.mimeType ?: "video/mp4",
            durationMs = facts.durationMs, width = facts.width, height = facts.height,
            thumbnailPath = saveThumbnail(facts.frame),
        )
    }

    fun describePicked(uri: Uri): LocalVideo? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size <= 0) return null
        val facts = readFacts { it.setDataSource(context, uri) }
        return LocalVideo(
            location = uri.toString(), source = VideoSource.GALLERY, fileName = name ?: "video",
            sizeBytes = size, mimeType = resolver.getType(uri) ?: facts.mimeType,
            durationMs = facts.durationMs, width = facts.width, height = facts.height,
            thumbnailPath = saveThumbnail(facts.frame),
        )
    }

    fun open(video: LocalVideo): InputStream = when (video.source) {
        VideoSource.CAMERA -> File(video.location).inputStream()
        VideoSource.GALLERY -> context.contentResolver.openInputStream(Uri.parse(video.location))
            ?: throw java.io.IOException("Video is no longer available")
    }

    private class Facts(val mimeType: String?, val durationMs: Long?, val width: Int?, val height: Int?, val frame: Bitmap?)

    private fun readFacts(setSource: (MediaMetadataRetriever) -> Unit): Facts {
        val retriever = MediaMetadataRetriever()
        return try {
            setSource(retriever)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
            var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            // The stored size ignores rotation; report the size as the viewer sees it.
            if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in setOf(90L, 270L)) {
                width = height.also { height = width }
            }
            Facts(
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                durationMs = number(MediaMetadataRetriever.METADATA_KEY_DURATION)?.takeIf { it > 0 },
                width = width?.takeIf { it > 0 },
                height = height?.takeIf { it > 0 },
                frame = runCatching { retriever.getFrameAtTime(1_000_000) }.getOrNull(),
            )
        } catch (error: Exception) {
            Facts(null, null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveThumbnail(frame: Bitmap?): String? {
        frame ?: return null
        return runCatching {
            val folder = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val target = File(folder, "thumb-${System.nanoTime()}.jpg")
            val scale = 480f / maxOf(frame.width, frame.height).coerceAtLeast(1)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true) else frame
            target.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            target.absolutePath
        }.getOrNull()
    }
}
