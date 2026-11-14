package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.Correction
import com.voleybolveveleybol.volleystat.model.Coverage
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.PlayerLine
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.ReviewDeck
import com.voleybolveveleybol.volleystat.model.SetPoint
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.TeamStats
import com.voleybolveveleybol.volleystat.model.VideoCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A reply the server sent with an error status. [code] is the server's error code. */
class ApiException(val status: Int, val code: String, message: String, val details: JSONObject) : IOException(message)

data class Health(val maxVideoBytes: Long, val probeAvailable: Boolean)

/** What the server is told about a match. Player names are never part of it. */
data class MatchInfo(
    val homeTeam: String,
    val awayTeam: String,
    val name: String? = null,
    val playedOn: String? = null,
    val venue: String? = null,
    val kind: String = "TRAINING",
)
data class UploadTicket(val uploadId: String, val nextOffset: Long, val chunkSizeBytes: Int)
data class UploadStatus(val nextOffset: Long, val status: String, val videoId: String?)

/** Blocking client for the local development API. Call it off the main thread. */
class ApiClient(
    private val baseUrl: String,
    /**
     * How long the app waits for the answer to a correction, and for the list and the cards it
     * reads right after one. The screen that sent the correction keeps the user until all three
     * are there, so the wait is short: the server applies a correction in one small transaction.
     */
    val correctionTimeoutMs: Int = CORRECTION_TIMEOUT_MS,
) {
    companion object {
        const val CORRECTION_TIMEOUT_MS = 15_000
    }

    fun health(): Health {
        val json = request("GET", "/health", timeoutMs = 5_000)
        return Health(
            maxVideoBytes = json.optLong("maxVideoBytes", 4L * 1024 * 1024 * 1024),
            probeAvailable = json.optString("mediaProbe") == "available",
        )
    }

    /**
     * Creates the match under [matchId], an id this phone chose, or finds it already there.
     * The call can be repeated: the server never creates a second match and never changes the first.
     */
    fun putMatch(matchId: String, match: MatchInfo) {
        val body = JSONObject().put("homeTeamName", match.homeTeam).put("awayTeamName", match.awayTeam)
            .putOpt("name", match.name).putOpt("playedOn", match.playedOn).putOpt("venue", match.venue)
            .put("kind", match.kind)
        request("PUT", "/matches/$matchId", body.toString().toByteArray())
    }

    fun createUpload(matchId: String, fileName: String, sizeBytes: Long, mimeType: String, sha256: String): UploadTicket {
        val body = JSONObject().put("fileName", fileName).put("sizeBytes", sizeBytes).put("mimeType", mimeType)
            .put("rightsAttested", true).put("allowTraining", false).put("clientSha256", sha256)
        val json = request("POST", "/matches/$matchId/uploads", body.toString().toByteArray())
        return UploadTicket(json.getString("uploadId"), json.getLong("nextOffset"), json.getInt("chunkSizeBytes"))
    }

    fun uploadStatus(uploadId: String): UploadStatus {
        val json = request("GET", "/uploads/$uploadId")
        return UploadStatus(json.getLong("nextOffset"), json.getString("status"), json.optStringOrNull("videoId"))
    }

    /** Returns the offset the server expects next. */
    fun putChunk(uploadId: String, offset: Long, buffer: ByteArray, length: Int): Long =
        request("PUT", "/uploads/$uploadId/chunks?offset=$offset", buffer, length, "application/octet-stream").getLong("nextOffset")

    /** The server hashes and probes the whole file here, so this call can take minutes. */
    fun complete(uploadId: String): VideoCheck =
        parseVideo(request("POST", "/uploads/$uploadId/complete", ByteArray(0), timeoutMs = 300_000))

    fun video(videoId: String): VideoCheck = parseVideo(request("GET", "/videos/$videoId"))

    /**
     * Removes the match and everything the server holds for it: uploads, videos, analyses and decisions.
     * Only the route's own 204 counts as deleted; any other answer throws.
     */
    fun deleteMatch(matchId: String) {
        request("DELETE", "/matches/$matchId", expectedStatus = 204)
    }

    // ----- Analysis, review and report (docs/API_V1.md) -----

    /** Address the player streams a video from. The server answers Range requests. */
    fun videoContentUrl(videoId: String): String = baseUrl.trimEnd('/') + "/videos/$videoId/content"

    /** Asks for an analysis. Throws ApiException with code MODEL_NOT_CONFIGURED when the server has no analyzer. */
    fun startAnalysis(videoId: String, idempotencyKey: String): AnalysisJob =
        parseJob(request("POST", "/videos/$videoId/analyses", ByteArray(0), headers = mapOf("Idempotency-Key" to idempotencyKey)))

    /** The latest job of a video, or null when it never had one. A video the server does not know is an error, not "no job". */
    fun latestAnalysis(videoId: String): AnalysisJob? = try {
        parseJob(request("GET", "/videos/$videoId/analyses/latest"))
    } catch (error: ApiException) {
        if (error.code != "ANALYSIS_NOT_FOUND") throw error
        null
    }

    fun reviewCards(jobId: String, scope: String, timeoutMs: Int = 60_000): ReviewDeck {
        val json = request("GET", "/analyses/$jobId/review-cards?scope=$scope", timeoutMs = timeoutMs)
        val position = json.getJSONObject("position")
        return ReviewDeck(json.getJSONArray("cards").objects().map(::parseCard), position.getInt("answered"), position.getInt("total"))
    }

    /** Records one decision. A stale [expectedVersion] throws ApiException with code REVIEW_STALE_VERSION. */
    fun decide(cardId: String, selection: String, expectedVersion: Int, idempotencyKey: String): AnalysisJob {
        val body = JSONObject().put("selection", selection).put("expectedVersion", expectedVersion).put("idempotencyKey", idempotencyKey)
        return parseJob(request("POST", "/review-cards/$cardId/decisions", body.toString().toByteArray()).getJSONObject("job"))
    }

    // ----- Corrections of the rally list (docs/API_V1.md, section 9) -----
    // Every correction names the version of the list the user was looking at. The server refuses a
    // correction of a list that has moved on with code RALLY_LIST_STALE, so none applies twice.

    fun rallies(jobId: String, timeoutMs: Int = 60_000): RallyList {
        val json = request("GET", "/analyses/$jobId/rallies", timeoutMs = timeoutMs)
        return RallyList(
            json.getJSONArray("rallies").objects().map(::parseRally), json.getInt("rallyListVersion"),
            json.getBoolean("listConfirmed"), json.getLong("videoDurationMs"), json.getJSONArray("sets").objects().map(::parseSet),
        )
    }

    fun addRally(jobId: String, startMs: Long, endMs: Long, listVersion: Int): Correction =
        correction("/analyses/$jobId/rallies", JSONObject().put("startMs", startMs).put("endMs", endMs), listVersion)

    fun moveRally(rallyId: String, startMs: Long, endMs: Long, listVersion: Int): Correction =
        correction("/rallies/$rallyId/move", JSONObject().put("startMs", startMs).put("endMs", endMs), listVersion)

    fun splitRally(rallyId: String, atMs: Long, listVersion: Int): Correction =
        correction("/rallies/$rallyId/split", JSONObject().put("atMs", atMs), listVersion)

    fun mergeWithPrevious(rallyId: String, listVersion: Int): Correction =
        correction("/rallies/$rallyId/merge-previous", JSONObject(), listVersion)

    /** Records the user's statement that the list holds every rally of the video. */
    fun confirmRallyList(jobId: String, listVersion: Int): AnalysisJob =
        parseJob(
            request("POST", "/analyses/$jobId/rally-list/confirm", JSONObject().put("expectedListVersion", listVersion).toString().toByteArray(), timeoutMs = correctionTimeoutMs)
                .getJSONObject("job")
        )

    private fun correction(path: String, body: JSONObject, listVersion: Int): Correction {
        val json = request("POST", path, body.put("expectedListVersion", listVersion).toString().toByteArray(), timeoutMs = correctionTimeoutMs)
        val removed = json.getJSONArray("removed")
        return Correction(json.getJSONArray("affected").objects().map(::parseRally), List(removed.length()) { removed.getString(it) }, parseJob(json.getJSONObject("job")))
    }

    private fun parseSet(json: JSONObject) = SetScore(json.getInt("number"), json.getInt("home"), json.getInt("away"), json.getBoolean("complete"))

    private fun parseRally(json: JSONObject) = Rally(
        id = json.getString("id"), sequence = json.getInt("sequence"), startMs = json.getLong("startMs"), endMs = json.getLong("endMs"),
        fromAnalyzer = json.getString("origin") == "ANALYZER", status = json.getString("status"), winner = json.optStringOrNull("winner"),
        scoreAfter = json.optJSONObject("scoreAfter")?.let { SetPoint(it.getInt("set"), it.getInt("home"), it.getInt("away")) },
    )

    /** The report of a match, or null when the match has no analysis job. */
    fun report(matchId: String): MatchReport? = try {
        parseReport(request("GET", "/matches/$matchId/report"))
    } catch (error: ApiException) {
        if (error.status != 404) throw error
        null
    }

    private fun parseJob(json: JSONObject): AnalysisJob {
        val progress = json.optJSONObject("progress")
        val analyzer = json.getJSONObject("analyzer")
        val counts = json.getJSONObject("counts")
        return AnalysisJob(
            id = json.getString("id"), videoId = json.getString("videoId"), state = json.getString("state"),
            stage = json.optStringOrNull("stage"),
            progressDone = progress?.getLong("done"), progressTotal = progress?.getLong("total"),
            analyzerId = analyzer.getString("id"), analyzerKind = analyzer.getString("kind"),
            mock = json.getBoolean("mock"),
            failureCode = json.optJSONObject("failure")?.getString("code"),
            rallies = counts.getInt("rallies"), reviewOpen = counts.getInt("reviewOpen"), reviewTotal = counts.getInt("reviewTotal"),
            proposed = counts.optInt("proposed", counts.getInt("rallies")),
            rallyListVersion = json.optInt("rallyListVersion", 0), listConfirmed = json.optBoolean("listConfirmed", false),
        )
    }

    private fun parseCard(json: JSONObject) = ReviewCard(
        id = json.getString("id"), rallyId = json.getString("rallyId"), rallySequence = json.getInt("rallySequence"),
        questionType = json.getString("questionType"), scope = json.getString("scope"),
        clipStartMs = json.getLong("clipStartMs"), clipEndMs = json.getLong("clipEndMs"),
        suggestedValue = json.optStringOrNull("suggestedValue"),
        options = json.getJSONArray("options").let { options -> List(options.length()) { options.getString(it) } },
        status = json.getString("status"), answer = json.optStringOrNull("answer"), version = json.getInt("version"),
    )

    private fun parseReport(json: JSONObject): MatchReport {
        val analyzer = json.getJSONObject("analyzer")
        val rallies = json.getJSONObject("rallies")
        val score = json.getJSONObject("score")
        val setsWon = score.getJSONObject("setsWon")
        val teams = json.getJSONObject("teams")
        val unattributed = json.getJSONObject("unattributed")
        // A null stays null: an unknown number never becomes 0 on the way to the screen.
        fun value(team: JSONObject, name: String) = team.getJSONObject(name).let { StatValue(if (it.isNull("value")) null else it.getInt("value"), it.getString("status")) }
        fun team(side: String) = teams.getJSONObject(side).let { TeamStats(value(it, "points"), value(it, "attackPoints"), value(it, "blockPoints"), value(it, "aces"), value(it, "opponentErrors")) }
        return MatchReport(
            jobId = json.getString("jobId"), analyzerId = analyzer.getString("id"), analyzerKind = analyzer.getString("kind"),
            mock = json.getBoolean("mock"), verificationStatus = json.getString("verificationStatus"),
            ralliesProposed = rallies.getInt("proposed"), ralliesTotal = rallies.optInt("total", rallies.getInt("proposed")),
            ralliesVerified = rallies.getInt("verified"), ralliesRejected = rallies.getInt("rejected"),
            ralliesUnresolved = rallies.getInt("unresolved"), ralliesOpen = rallies.getInt("open"),
            scoreStatus = score.getString("status"),
            setsWonHome = if (setsWon.isNull("home")) null else setsWon.getInt("home"),
            setsWonAway = if (setsWon.isNull("away")) null else setsWon.getInt("away"),
            sets = score.getJSONArray("sets").objects().map(::parseSet),
            scoreStoppedAtRally = if (score.isNull("stoppedAtRally")) null else score.getInt("stoppedAtRally"),
            ralliesMayBeMissing = json.optBoolean("ralliesMayBeMissing", false),
            listConfirmed = json.optJSONObject("rallyList")?.optBoolean("confirmed", false) ?: false,
            corrections = json.optJSONObject("rallyList")?.optInt("corrections", 0) ?: 0,
            ralliesUserCreated = rallies.optInt("userCreated", 0),
            home = team("home"), away = team("away"),
            players = json.getJSONArray("players").objects().map {
                PlayerLine(it.getString("side") == "HOME", it.getInt("jersey"), it.getInt("points"), it.getInt("attackPoints"), it.getInt("blockPoints"), it.getInt("aces"))
            },
            unattributedHome = if (unattributed.isNull("home")) null else unattributed.getInt("home"),
            unattributedAway = if (unattributed.isNull("away")) null else unattributed.getInt("away"),
            coverage = json.getJSONArray("coverage").objects().map { Coverage(it.getString("metricId"), it.getInt("eligibleN"), it.getInt("verifiedN")) },
        )
    }

    private fun parseVideo(json: JSONObject): VideoCheck {
        val quality = json.getJSONObject("quality")
        val media = quality.optJSONObject("media")
        val warnings = quality.optJSONArray("warnings")
        return VideoCheck(
            videoId = json.getString("id"),
            status = json.getString("status"),
            rejectCode = quality.optStringOrNull("rejectCode"),
            width = media?.takeUnless { it.isNull("width") }?.getInt("width"),
            height = media?.takeUnless { it.isNull("height") }?.getInt("height"),
            fps = media?.takeUnless { it.isNull("fpsAverage") }?.getDouble("fpsAverage"),
            durationMs = media?.takeUnless { it.isNull("durationMs") }?.getLong("durationMs"),
            codec = media?.optStringOrNull("codec"),
            warnings = if (warnings == null) emptyList() else List(warnings.length()) { warnings.getString(it) },
        )
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        length: Int = body?.size ?: 0,
        contentType: String = "application/json",
        timeoutMs: Int = 60_000,
        headers: Map<String, String> = emptyMap(),
        expectedStatus: Int? = null,
    ): JSONObject {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(length)
                connection.outputStream.use { it.write(body, 0, length) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw ApiException(
                    status = status,
                    code = error?.optString("code").orEmpty().ifEmpty { "HTTP_$status" },
                    message = error?.optString("message").orEmpty().ifEmpty { "HTTP $status" },
                    details = error?.optJSONObject("details") ?: JSONObject(),
                )
            }
            if (expectedStatus != null && status != expectedStatus) {
                throw ApiException(status, "UNEXPECTED_ANSWER", "HTTP $status where $expectedStatus was expected", JSONObject())
            }
            // An answer that is not JSON comes from something that is not this API. It is a failed
            // call like any other, not a crash.
            return if (text.isBlank()) JSONObject() else try {
                JSONObject(text)
            } catch (error: org.json.JSONException) {
                throw ApiException(status, "UNEXPECTED_ANSWER", "The answer is not JSON", JSONObject())
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
