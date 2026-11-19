package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.Correction
import com.voleybolveveleybol.volleystat.model.Coverage
import com.voleybolveveleybol.volleystat.model.MatchPart
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.MatchRules
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

/**
 * A reply the server sent with an error status. [code] is the server's error code. [retryAfterSeconds]
 * is the server's Retry-After header, when it sent one (429 and 503). [contentType] is the Content-Type
 * of a 2xx answer the app could not read (UNEXPECTED_ANSWER), as it came; the registration's rule reads it.
 */
class ApiException(
    val status: Int, val code: String, message: String, val details: JSONObject, val retryAfterSeconds: Int? = null, val contentType: String? = null,
) : IOException(message) {
    /**
     * The registration's own rule: the answer may come after the server opened the account, so the app
     * marks the username, and the next try looks for the account with a login. Only a 5xx other than
     * SERVER_BUSY and 511, and a 2xx with a JSON Content-Type that the app can not read (UNEXPECTED_ANSWER),
     * mark; no answer to a request that left the phone marks too (AppViewModel.register). A 4xx comes before
     * the server changes anything, and SERVER_BUSY before it counts or writes anything (third review, B3). A 4xx
     * without the API's envelope marks none, nor does a 3xx (seventh checker, R1), nor a 4xx or a 3xx whose
     * envelope names UNEXPECTED_ANSWER (eighth checker, S1). The API answers a registration with JSON or a 5xx,
     * never with a 3xx, RFC 6585's 511 Network Authentication Required or a 2xx of another Content-Type: such
     * an answer most likely comes from a gateway or a captive portal that never passed the request on
     * (eighth checker, S3), and the mark would send a login with the typed password to the account that holds
     * the username. The changes of the user's own account read the wider [outcomeUnknownAfter], which leaves
     * the outcome open after all of these answers. HTTP has no status of 600 or above and the API never sends
     * one, so such a status marks nothing either (ninth checker).
     */
    val leavesOutcomeUnknown: Boolean
        get() = (status in 500..599 && status != 511 && code != "SERVER_BUSY") || (status in 200..299 && code == "UNEXPECTED_ANSWER" && namesJson(contentType))
}

/** Whether a Content-Type header names JSON, as the API's answers do: application/json, with any parameters. */
private fun namesJson(contentType: String?): Boolean = contentType?.substringBefore(';')?.trim().equals("application/json", ignoreCase = true)

/**
 * The request never left the phone: the app could not open a connection to the server's address (the
 * connection was refused, the name did not resolve, no route led there, or no connection came within the
 * wait). Nothing of the request reached the server, so the server can not have acted on it (fourth review, D2).
 */
class RequestNotSent(cause: IOException) : IOException("The request never left the phone", cause)

/** An account as the server describes it (docs/API_V1.md, section 10). */
data class AccountUser(val id: String, val username: String, val displayName: String, val passwordTemporary: Boolean = false)

/** The answer to a registration or a login: the account, its new session and the phone's device token. */
class SignedIn(val user: AccountUser, val token: String, val expiresAt: String, val deviceToken: String?) {
    /** Printed, the answer never shows its secrets. */
    override fun toString() = "SignedIn(user=$user, expiresAt=$expiresAt, token=<hidden>, deviceToken=${if (deviceToken == null) "none" else "<hidden>"})"
}

/**
 * The answer to `GET /health`. A 2xx carries no error envelope, so the answer's own shape is the sign that the API sent it
 * (ADR-0119): every version of the API answers with a JSON object whose `status` is "ok" and whose `mediaProbe` is
 * "available" or "unavailable". [fromApi] is false for any other 2xx: a blank body, a page, or the JSON of another
 * service at the address. Such an answer says nothing about the server, so [probeAvailable] says nothing then either.
 */
data class Health(val maxVideoBytes: Long, val probeAvailable: Boolean, val fromApi: Boolean)

/** What the server is told about a match. Player names are never part of it. */
data class MatchInfo(
    val homeTeam: String,
    val awayTeam: String,
    val name: String? = null,
    val playedOn: String? = null,
    val venue: String? = null,
    val kind: String = "TRAINING",
    /** The rule the server counts the sets with. Only the request that creates the match sets it. */
    val rules: MatchRules = MatchRules.FIVB,
)
data class UploadTicket(val uploadId: String, val nextOffset: Long, val chunkSizeBytes: Int)
data class UploadStatus(val nextOffset: Long, val status: String, val videoId: String?)

/**
 * Blocking client for the local development API. Call it off the main thread.
 *
 * With a [token], every call carries it in `Authorization: Bearer`, and only there: no token ever
 * stands in an address. A call that carried it and got `401 AUTH_REQUIRED` reports the token to
 * [onAuthRequired] before it throws, so the app can end that one session.
 */
class ApiClient(
    /** The address every call of this client goes to. The view model compares it with the one it talks to when a login comes back. */
    val baseUrl: String,
    /**
     * How long the app waits for the answer to a correction, and for the list and the cards it
     * reads right after one. The screen that sent the correction keeps the user until all three
     * are there, so the wait is short: the server applies a correction in one small transaction.
     */
    val correctionTimeoutMs: Int = CORRECTION_TIMEOUT_MS,
    private val token: String? = null,
    private val onAuthRequired: ((String) -> Unit)? = null,
) {
    companion object {
        const val CORRECTION_TIMEOUT_MS = 15_000
    }

    // ----- Accounts (docs/API_V1.md, section 10) -----
    // A password goes only into the body of these calls. Nothing here logs a body.

    /** Opens an account. [operatorCode] is the operator's one-time sign-up code; null sends none. */
    fun register(username: String, displayName: String, password: String, operatorCode: String?): SignedIn {
        val body = JSONObject().put("username", username).put("displayName", displayName).put("password", password).putOpt("operatorCode", operatorCode)
        // The registration's rule reads the status and the Content-Type of a success the app can not read (ApiException.leavesOutcomeUnknown).
        val received = Received()
        return readable(received) { parseSignedIn(request("POST", "/auth/register", body.toString().toByteArray(), received = received)) }
    }

    /** Opens a session. [deviceToken] is the token this phone got at an earlier login of [username], if any (design 3.2). */
    fun login(username: String, password: String, deviceToken: String?): SignedIn {
        val body = JSONObject().put("username", username).put("password", password).putOpt("deviceToken", deviceToken)
        return readable { parseSignedIn(request("POST", "/auth/login", body.toString().toByteArray())) }
    }

    /** Ends this client's session on the server. Only the route's own 204 counts. */
    fun logout() {
        request("POST", "/auth/logout", "{}".toByteArray(), expectedStatus = 204)
    }

    fun me(): AccountUser = readable { parseUser(request("GET", "/me").getJSONObject("user")) }

    fun changeDisplayName(displayName: String): AccountUser =
        readable { parseUser(request("PUT", "/me/display-name", JSONObject().put("displayName", displayName).toString().toByteArray()).getJSONObject("user")) }

    /** Sets a new password. The server ends every other session of the account and forgets every device token of it. */
    fun changePassword(currentPassword: String, newPassword: String): AccountUser {
        val body = JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword)
        return readable { parseUser(request("PUT", "/me/password", body.toString().toByteArray()).getJSONObject("user")) }
    }

    /** Deletes the account on the server. Only the route's own 204 counts as deleted. */
    fun deleteAccount(password: String) {
        request("POST", "/me/delete", JSONObject().put("password", password).toString().toByteArray(), expectedStatus = 204)
    }

    private fun parseUser(json: JSONObject) = AccountUser(
        json.getString("id"), json.getString("username"), json.getString("displayName"), json.optBoolean("passwordTemporary", false),
    )

    private fun parseSignedIn(json: JSONObject): SignedIn {
        val session = json.getJSONObject("session")
        return SignedIn(parseUser(json.getJSONObject("user")), session.getString("token"), session.getString("expiresAt"), json.optStringOrNull("deviceToken"))
    }

    /**
     * An answer that came but lacks what this call reads is a failed call, not a crash. A call that passes
     * [received] gives the failure the status and the Content-Type of the answer it got.
     */
    private fun <T> readable(received: Received? = null, read: () -> T): T = try {
        read()
    } catch (error: org.json.JSONException) {
        throw ApiException(received?.status ?: 200, "UNEXPECTED_ANSWER", "The answer is not the one this call expects", JSONObject(), contentType = received?.contentType)
    }

    /** The status and the Content-Type of the 2xx answer a request got, filled by [request]. */
    private class Received {
        var status = 200
        var contentType: String? = null
    }

    fun health(): Health {
        val json = request("GET", "/health", timeoutMs = 5_000)
        val probe = json.optString("mediaProbe")
        return Health(
            maxVideoBytes = json.optLong("maxVideoBytes", 4L * 1024 * 1024 * 1024),
            probeAvailable = probe == "available",
            fromApi = json.optString("status") == "ok" && (probe == "available" || probe == "unavailable"),
        )
    }

    /**
     * Creates the match under [matchId], an id this phone chose, or finds it already there.
     * The call can be repeated: the server never creates a second match and never changes the first.
     */
    fun putMatch(matchId: String, match: MatchInfo) {
        val body = JSONObject().put("homeTeamName", match.homeTeam).put("awayTeamName", match.awayTeam)
            .putOpt("name", match.name).putOpt("playedOn", match.playedOn).putOpt("venue", match.venue)
            .put("kind", match.kind).put("rules", rulesJson(match.rules))
        request("PUT", "/matches/$matchId", body.toString().toByteArray())
    }

    /**
     * Sets the rule the server counts the sets of the match with. The request names the whole rule,
     * so repeating it after a lost answer changes nothing more. It also names the rule of the list
     * the app last read ([seen]): while the match has another rule, a request the app gave up on
     * that arrives late is refused as stale and does not undo a later change to another rule. The
     * server moves the rally list of the match on, and a confirmation given under the old rule
     * lapses. The answer is not read.
     */
    fun putMatchRules(matchId: String, rules: MatchRules, seen: MatchRules) {
        val body = rulesJson(rules).put("expectedRules", rulesJson(seen))
        request("PUT", "/matches/$matchId/rules", body.toString().toByteArray(), timeoutMs = correctionTimeoutMs)
    }

    private fun rulesJson(rules: MatchRules) = JSONObject()
        .put("setsToWin", rules.setsToWin).put("setTarget", rules.setTarget).put("decidingSetTarget", rules.decidingSetTarget)

    private fun parseRules(json: JSONObject) = MatchRules(json.getInt("setsToWin"), json.getInt("setTarget"), json.getInt("decidingSetTarget"))

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
        // A list without its rule comes from a server before 0.6, which counts every match with the FIVB rule.
        return RallyList(
            json.getJSONArray("rallies").objects().map(::parseRally), json.getInt("rallyListVersion"),
            json.getBoolean("listConfirmed"), json.getLong("videoDurationMs"), json.getJSONArray("matches").objects().map(::parsePart),
            parseRules(json.getJSONObject("rules")),
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

    // A null stays null: an unknown number never becomes 0 on the way to the screen.
    private fun parseValue(team: JSONObject, name: String) = team.getJSONObject(name).let { StatValue(if (it.isNull("value")) null else it.getInt("value"), it.getString("status")) }

    private fun parseTeam(team: JSONObject) =
        TeamStats(parseValue(team, "points"), parseValue(team, "attackPoints"), parseValue(team, "blockPoints"), parseValue(team, "aces"), parseValue(team, "opponentErrors"))

    private fun parsePlayers(players: JSONArray) = players.objects().map {
        PlayerLine(it.getString("side") == "HOME", it.getInt("jersey"), it.getInt("points"), it.getInt("attackPoints"), it.getInt("blockPoints"), it.getInt("aces"))
    }

    private fun JSONObject.intOrNull(name: String) = if (isNull(name)) null else getInt(name)

    /** One part of a video. The rally list sends no statistics, so those stay null there. */
    private fun parsePart(json: JSONObject): MatchPart {
        val setsWon = json.getJSONObject("setsWon")
        val teams = json.optJSONObject("teams")
        val unattributed = json.optJSONObject("unattributed")
        return MatchPart(
            number = json.getInt("number"), kind = json.getString("kind"), winner = json.optStringOrNull("winner"),
            setsWonHome = setsWon.getInt("home"), setsWonAway = setsWon.getInt("away"),
            sets = json.getJSONArray("sets").objects().map(::parseSet),
            firstRally = json.getInt("firstRally"), lastRally = json.getInt("lastRally"),
            home = teams?.let { parseTeam(it.getJSONObject("home")) }, away = teams?.let { parseTeam(it.getJSONObject("away")) },
            players = json.optJSONArray("players")?.let(::parsePlayers).orEmpty(),
            unattributedHome = unattributed?.intOrNull("home"), unattributedAway = unattributed?.intOrNull("away"),
        )
    }

    private fun parseRally(json: JSONObject) = Rally(
        id = json.getString("id"), sequence = json.getInt("sequence"), startMs = json.getLong("startMs"), endMs = json.getLong("endMs"),
        fromAnalyzer = json.getString("origin") == "ANALYZER", status = json.getString("status"), winner = json.optStringOrNull("winner"),
        scoreAfter = json.optJSONObject("scoreAfter")?.let { SetPoint(it.getInt("set"), it.getInt("home"), it.getInt("away"), it.getInt("match")) },
    )

    /**
     * The report of a match, or null when the API itself answers that there is none: its own 404 for a match no analysis
     * of which has produced rallies (REPORT_NOT_AVAILABLE), or for a match it does not hold (MATCH_NOT_FOUND). A 404
     * without the API's envelope may come from a gateway or a proxy, and the API's NOT_FOUND names a route it does not
     * have: neither says anything about the match, so both throw like any other failure (ADR-0119).
     */
    fun report(matchId: String): MatchReport? = try {
        parseReport(request("GET", "/matches/$matchId/report"))
    } catch (error: ApiException) {
        if (error.status != 404 || (error.code != "REPORT_NOT_AVAILABLE" && error.code != "MATCH_NOT_FOUND")) throw error
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
        val teams = json.getJSONObject("teams")
        val unattributed = json.getJSONObject("unattributed")
        return MatchReport(
            jobId = json.getString("jobId"), analyzerId = analyzer.getString("id"), analyzerKind = analyzer.getString("kind"),
            mock = json.getBoolean("mock"), verificationStatus = json.getString("verificationStatus"),
            ralliesProposed = rallies.getInt("proposed"), ralliesTotal = rallies.optInt("total", rallies.getInt("proposed")),
            ralliesVerified = rallies.getInt("verified"), ralliesRejected = rallies.getInt("rejected"),
            ralliesUnresolved = rallies.getInt("unresolved"), ralliesOpen = rallies.getInt("open"),
            scoreStatus = score.getString("status"),
            matches = score.getJSONArray("matches").objects().map(::parsePart),
            scoreStoppedAtRally = score.intOrNull("stoppedAtRally"),
            unplacedRallies = score.getInt("unplacedRallies"),
            ralliesMayBeMissing = json.optBoolean("ralliesMayBeMissing", false),
            listConfirmed = json.optJSONObject("rallyList")?.optBoolean("confirmed", false) ?: false,
            corrections = json.optJSONObject("rallyList")?.optInt("corrections", 0) ?: 0,
            ralliesUserCreated = rallies.optInt("userCreated", 0),
            home = parseTeam(teams.getJSONObject("home")), away = parseTeam(teams.getJSONObject("away")),
            players = parsePlayers(json.getJSONArray("players")),
            unattributedHome = unattributed.intOrNull("home"), unattributedAway = unattributed.intOrNull("away"),
            coverage = json.getJSONArray("coverage").objects().map { Coverage(it.getString("metricId"), it.getInt("eligibleN"), it.getInt("verifiedN")) },
            rules = json.optJSONObject("rules")?.let { rules -> runCatching { parseRules(rules) }.getOrNull() },
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
        received: Received? = null,
    ): JSONObject {
        // An address the phone can not even read (a build without a server address) sends nothing either.
        val connection = try {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw RequestNotSent(error)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                // A streamed body can not be sent a second time, so the HTTP stack never repeats a request
                // on its own after a lost answer. A register or a login that went out twice would be wrong.
                connection.setFixedLengthStreamingMode(length)
            }
            // The connection is opened on its own, before any of the body goes: a failure here (refused, no such
            // name, no route, no connection within the wait) means that the request never left the phone (D2).
            try {
                connection.connect()
            } catch (error: IOException) {
                throw RequestNotSent(error)
            }
            if (body != null) connection.outputStream.use { it.write(body, 0, length) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                val failure = ApiException(
                    status = status,
                    code = error?.optString("code").orEmpty().ifEmpty { unreadable401(status, path, token != null, connection) ?: "HTTP_$status" },
                    message = error?.optString("message").orEmpty().ifEmpty { "HTTP $status" },
                    details = error?.optJSONObject("details") ?: JSONObject(),
                    retryAfterSeconds = connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
                )
                // The session this call carried has ended. A call without one, or a wrong password at login, ends nothing.
                if (status == 401 && failure.code == "AUTH_REQUIRED" && token != null) onAuthRequired?.invoke(token)
                throw failure
            }
            if (expectedStatus != null && status != expectedStatus) {
                throw ApiException(status, "UNEXPECTED_ANSWER", "HTTP $status where $expectedStatus was expected", JSONObject())
            }
            received?.status = status
            received?.contentType = connection.contentType
            // An answer that is not JSON comes from something that is not this API. It is a failed
            // call like any other, not a crash.
            return if (text.isBlank()) JSONObject() else try {
                JSONObject(text)
            } catch (error: org.json.JSONException) {
                throw ApiException(status, "UNEXPECTED_ANSWER", "The answer is not JSON", JSONObject(), contentType = connection.contentType)
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The code of a 401 whose body did not arrive. The JVM's HttpURLConnection drops the body of a 401
 * to a streamed request, because it can not send that request again; Android's keeps it. Such an
 * answer is read by the contract of docs/API_V1.md section 10 only when it names the Bearer scheme,
 * as this API's 401 always does, and only on two kinds of request: the login route answers 401 only
 * with LOGIN_FAILED, and a request that [carriedSession] gets 401 only with AUTH_REQUIRED, which ends
 * that session. A request without a session to any other route has no session to end, and the API
 * never answers a registration with a 401: a Bearer 401 there comes from something on the way and stays
 * unread (seventh checker, R3), as anything else does.
 */
private fun unreadable401(status: Int, path: String, carriedSession: Boolean, connection: HttpURLConnection): String? {
    if (status != 401) return null
    val scheme = connection.getHeaderField("WWW-Authenticate")?.trim()?.substringBefore(' ')
    if (!scheme.equals("Bearer", ignoreCase = true)) return null
    return when {
        path == "/auth/login" -> "LOGIN_FAILED"
        carriedSession -> "AUTH_REQUIRED"
        else -> null
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
