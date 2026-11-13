package com.voleybolveveleybol.volleystat.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** In-process stand-in for the upload API, with switches to make it misbehave. */
class FakeServer(var maxVideoBytes: Long = 4L * 1024 * 1024 * 1024) : AutoCloseable {
    private val server = MockWebServer()
    val baseUrl get() = server.url("/v1").toString()

    var matchesCreated = 0
    var uploadsCreated = 0
    var chunksReceived = 0
    val stored = ByteArrayOutputStream()
    var declaredSha: String? = null
    var status = "NONE"

    /** Chunk number (1-based) that fails once with HTTP 500. */
    var failChunk: Int? = null
    /** Number of completes that report a checksum mismatch before behaving. */
    var checksumFailures = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeServer) {
                try {
                    route(request)
                } catch (error: Exception) {
                    reply(500, error("INTERNAL", error.toString()))
                }
            }
        }
        server.start()
    }

    override fun close() = server.shutdown()

    private fun route(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath.removePrefix("/v1")
        val method = request.method
        return when {
            method == "GET" && path == "/health" ->
                reply(200, JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", maxVideoBytes))
            method == "POST" && path == "/matches" -> {
                matchesCreated++
                reply(201, JSONObject().put("id", "match-$matchesCreated"))
            }
            method == "POST" && path.endsWith("/uploads") -> {
                uploadsCreated++
                declaredSha = JSONObject(request.body.readUtf8()).getString("clientSha256")
                stored.reset()
                status = "UPLOADING"
                reply(201, JSONObject().put("uploadId", "upload-$uploadsCreated").put("nextOffset", 0).put("chunkSizeBytes", 4 * 1024 * 1024))
            }
            method == "GET" && path == "/uploads/upload-$uploadsCreated" && status != "NONE" ->
                reply(200, JSONObject().put("uploadId", "upload-$uploadsCreated").put("nextOffset", stored.size())
                    .put("status", status).put("videoId", if (status == "COMPLETED") "video-1" else JSONObject.NULL))
            method == "PUT" && path.endsWith("/chunks") -> {
                chunksReceived++
                val offset = url.queryParameter("offset")!!.toLong()
                when {
                    failChunk == chunksReceived -> reply(500, error("BROKEN", "injected failure"))
                    offset != stored.size().toLong() ->
                        reply(409, error("OFFSET_MISMATCH", "expected other offset", JSONObject().put("expectedOffset", stored.size())))
                    else -> {
                        stored.write(request.body.readByteArray())
                        reply(200, JSONObject().put("nextOffset", stored.size()))
                    }
                }
            }
            method == "POST" && path.endsWith("/complete") -> when {
                checksumFailures > 0 || sha256(stored.toByteArray()) != declaredSha -> {
                    if (checksumFailures > 0) checksumFailures--
                    stored.reset()
                    reply(422, error("UPLOAD_CHECKSUM_MISMATCH", "send again", JSONObject().put("expectedOffset", 0)))
                }
                else -> {
                    status = "COMPLETED"
                    reply(200, video())
                }
            }
            method == "GET" && path == "/videos/video-1" -> reply(200, video())
            else -> reply(404, error("NOT_FOUND", "no route for $method $path"))
        }
    }

    private fun video() = JSONObject().put("id", "video-1").put("videoId", "video-1").put("status", "READY")
        .put("quality", JSONObject().put("rejectCode", JSONObject.NULL).put("warnings", org.json.JSONArray(listOf("LOW_RESOLUTION")))
            .put("media", JSONObject().put("width", 640).put("height", 360).put("fpsAverage", 30.0)
                .put("durationMs", 2000).put("codec", "h264")))

    private fun error(code: String, message: String, details: JSONObject = JSONObject()) =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message).put("details", details))

    private fun reply(status: Int, json: JSONObject) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json.toString())
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
