package com.voleybolveveleybol.volleystat.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** In-process stand-in for the upload API, with switches to make it misbehave. */
class FakeServer(var maxVideoBytes: Long = 4L * 1024 * 1024 * 1024) : AutoCloseable {
    private val server = MockWebServer()
    val baseUrl get() = server.url("/v1").toString()

    /** The ids the phone created matches under, in the order they arrived. */
    val matchIds = mutableListOf<String>()
    /** The body of every request that created or repeated a match, in the order they arrived. */
    val matchBodies = mutableListOf<JSONObject>()
    val matchesCreated get() = matchIds.size
    /** Number of match requests the server stores and then answers by dropping the connection. */
    var loseMatchAnswers = 0
    var uploadsCreated = 0
    var chunksReceived = 0
    val stored = ByteArrayOutputStream()
    var declaredSha: String? = null
    var status = "NONE"

    /** Chunk number (1-based) that fails once with HTTP 500. */
    var failChunk: Int? = null
    /** Number of completes that report a checksum mismatch before behaving. */
    var checksumFailures = 0
    /** Answer every request with a web page, as another service on the same port would. */
    var answersWithAPage = false
    /** The refusal (status and error code with its details) that every request to open an upload gets, while set. */
    var refuseUpload: Triple<Int, String, JSONObject>? = null
    /**
     * Chunk numbers (1-based, counted over the whole test) that find the upload closed, as after the
     * server's sweep: the server marks the upload ABORTED, drops its bytes and answers 409 UPLOAD_CLOSED.
     */
    var closeAtChunks: Set<Int> = emptySet()
    /** Number of completes that find the upload closed by the sweep, as [closeAtChunks] does. */
    var closedCompletes = 0
    /** Chunk number (1-based) that would take the server's disk below its floor: 507 STORAGE_FULL, nothing written. */
    var storageFullAtChunk: Int? = null
    /** The server answers 403 NOT_A_MEMBER on every route of a match, as to an account that does not own it. */
    var notAMember = false

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
        if (answersWithAPage) return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>another service</html>")
        return when {
            method == "GET" && path == "/health" ->
                reply(200, JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", maxVideoBytes))
            notAMember -> reply(403, error("NOT_A_MEMBER", "This account can not see this match"))
            method == "PUT" && path.startsWith("/matches/") && path.count { it == '/' } == 2 -> {
                val id = path.removePrefix("/matches/")
                matchBodies += JSONObject(request.body.readUtf8())
                val known = id in matchIds
                if (!known) matchIds += id
                when {
                    // The match is stored; only the answer never reaches the phone.
                    loseMatchAnswers > 0 -> { loseMatchAnswers--; MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                    else -> reply(if (known) 200 else 201, JSONObject().put("id", id))
                }
            }
            method == "POST" && path.endsWith("/uploads") && path.removePrefix("/matches/").removeSuffix("/uploads") !in matchIds ->
                reply(404, error("MATCH_NOT_FOUND", "no such match"))
            method == "POST" && path.endsWith("/uploads") && refuseUpload != null ->
                refuseUpload!!.let { (status, code, details) -> reply(status, error(code, "refused", details)) }
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
                    chunksReceived in closeAtChunks -> sweptAway()
                    status != "UPLOADING" -> reply(409, error("UPLOAD_CLOSED", "Upload is not active"))
                    storageFullAtChunk == chunksReceived ->
                        reply(507, error("STORAGE_FULL", "below the floor", JSONObject().put("floorBytes", 10L * 1024 * 1024 * 1024)))
                    offset != stored.size().toLong() ->
                        reply(409, error("OFFSET_MISMATCH", "expected other offset", JSONObject().put("expectedOffset", stored.size())))
                    else -> {
                        stored.write(request.body.readByteArray())
                        reply(200, JSONObject().put("nextOffset", stored.size()))
                    }
                }
            }
            method == "POST" && path.endsWith("/complete") -> when {
                closedCompletes > 0 -> { closedCompletes--; sweptAway() }
                status != "UPLOADING" -> reply(409, error("UPLOAD_CLOSED", "Upload is not active"))
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

    /** The sweep closed the upload: its bytes are gone and the request that finds it so is refused. */
    private fun sweptAway(): MockResponse {
        status = "ABORTED"
        stored.reset()
        return reply(409, error("UPLOAD_CLOSED", "Upload is not active"))
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
