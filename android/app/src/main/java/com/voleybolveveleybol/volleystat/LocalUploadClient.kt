package com.voleybolveveleybol.volleystat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal fun readVideoInfo(resolver: ContentResolver, uri: Uri): Pair<String, Long>? {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            if (name != null && size > 0) {
                val sanitized = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                return Pair(sanitized, size)
            }
        }
    }
    return null
}

/** What the server found in the file. Null fields were not reported; they are never shown as zero. */
internal data class UploadResult(
    val videoId: String,
    val status: String,
    val rejectCode: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val durationMs: Long?,
    val codec: String?,
    val warnings: List<String>,
)

/** Local development protocol. Network I/O must run off the main thread. */
internal class LocalUploadClient(private val resolver: ContentResolver, private val baseUrl: String) {
    private val chunkBytes = 4 * 1024 * 1024

    fun upload(home: String, away: String, uri: Uri, fileName: String, sizeBytes: Long, onProgress: (Float) -> Unit): UploadResult {
        require(sizeBytes > 0) { "Video boyutu okunamadı" }
        require(fileName.isNotEmpty())
        val mime = when (fileName.substringAfterLast('.').lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> error("Desteklenmeyen video biçimi")
        }
        // The server compares its stored bytes with this digest before it accepts the video.
        val checksum = sha256(uri, sizeBytes)
        val match = jsonRequest("POST", "/matches", JSONObject().put("homeTeamName", home).put("awayTeamName", away).toString().toByteArray())
        val matchId = match.getString("id")
        val body = JSONObject().put("fileName", fileName).put("sizeBytes", sizeBytes).put("mimeType", mime)
            .put("rightsAttested", true).put("allowTraining", false).put("clientSha256", checksum)
        val uploadInfo = jsonRequest("POST", "/matches/$matchId/uploads", body.toString().toByteArray())
        val uploadId = uploadInfo.getString("uploadId")
        var offset = uploadInfo.getLong("nextOffset")
        val buffer = ByteArray(chunkBytes)
        resolver.openInputStream(uri)?.use { input ->
            // The server supplies an offset so interrupted transfers can resume.
            var skipped = 0L
            while (skipped < offset) {
                val count = input.skip(offset - skipped)
                if (count <= 0) error("Kaynak video okunamadı")
                skipped += count
            }
            while (offset < sizeBytes) {
                val desired = minOf(buffer.size.toLong(), sizeBytes - offset).toInt()
                var n = 0
                while (n < desired) {
                    val got = input.read(buffer, n, desired - n)
                    if (got < 0) error("Dosya beklenenden kısa")
                    n += got
                }
                val next = jsonRequest("PUT", "/uploads/$uploadId/chunks?offset=$offset", buffer.copyOfRange(0, n), "application/octet-stream")
                offset = next.getLong("nextOffset")
                onProgress(offset.toFloat() / sizeBytes.toFloat())
            }
        } ?: error("Video kaynağı açılamadı")
        return parseResult(jsonRequest("POST", "/uploads/$uploadId/complete", ByteArray(0)))
    }

    private fun sha256(uri: Uri, sizeBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
            }
        } ?: error("Video kaynağı açılamadı")
        check(total == sizeBytes) { "Video boyutu okuma sırasında değişti" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseResult(json: JSONObject): UploadResult {
        val quality = json.getJSONObject("quality")
        val media = quality.optJSONObject("media")
        val warnings = quality.optJSONArray("warnings")
        return UploadResult(
            videoId = json.getString("videoId"),
            status = json.getString("status"),
            rejectCode = if (quality.isNull("rejectCode")) null else quality.getString("rejectCode"),
            width = media?.takeIf { !it.isNull("width") }?.getInt("width"),
            height = media?.takeIf { !it.isNull("height") }?.getInt("height"),
            fps = media?.takeIf { !it.isNull("fpsAverage") }?.getDouble("fpsAverage"),
            durationMs = media?.takeIf { !it.isNull("durationMs") }?.getLong("durationMs"),
            codec = media?.takeIf { !it.isNull("codec") }?.getString("codec"),
            warnings = if (warnings == null) emptyList() else List(warnings.length()) { warnings.getString(it) },
        )
    }

    private fun jsonRequest(method: String, path: String, body: ByteArray? = null, contentType: String = "application/json"): JSONObject {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            // Completing an upload hashes and probes the whole file on the server.
            conn.readTimeout = 180_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text
                error("HTTP ${conn.responseCode}: $message")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
