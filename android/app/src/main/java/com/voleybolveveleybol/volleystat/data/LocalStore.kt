package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchKind
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What one account keeps on this phone. The phone's own settings live in device.json (DeviceStore). */
data class StoredState(
    val matches: List<MatchRecord> = emptyList(),
    val teams: List<Team> = emptyList(),
    val draft: Draft = Draft(),
)

/**
 * One account's record of matches, teams and the unfinished draft, kept as one JSON file in that
 * account's folder (accounts/<userId>/volleystat.json). It is the reason a rotation, a camera
 * round trip or a killed process loses neither the recording nor the upload position.
 *
 * Version 3. Version 2 was the single file of app 0.7, which also held the settings; this store
 * still reads it (for the move of the first login) and ignores its settings.
 */
class LocalStore(private val file: File) {

    fun load(): StoredState {
        if (!file.exists()) return StoredState()
        return try {
            decode(JSONObject(file.readText()))
        } catch (error: Exception) {
            // Keep the unreadable file for inspection instead of deleting it.
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"))
            StoredState()
        }
    }

    @Synchronized
    fun save(state: StoredState) {
        // An account's folder exists from its first save on.
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encode(state).toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }

    private fun encode(state: StoredState) = JSONObject()
        .put("version", 3)
        .put("draft", with(state.draft) {
            JSONObject()
                .put("name", name).put("playedOn", playedOn).put("venue", venue).put("kind", kind.name)
                .put("homeTeam", homeTeam).put("awayTeam", awayTeam)
                .putOpt("homeTeamId", homeTeamId).putOpt("awayTeamId", awayTeamId)
                .putOpt("video", video?.let(::encodeVideo))
                .putOpt("pendingCapturePath", pendingCapturePath)
                .put("rules", encodeRules(rules))
        })
        .put("teams", JSONArray(state.teams.map { team ->
            JSONObject().put("id", team.id).put("name", team.name)
                .put("players", JSONArray(team.players.map { JSONObject().put("id", it.id).put("name", it.name).putOpt("jersey", it.jersey) }))
        }))
        .put("matches", JSONArray(state.matches.map { match ->
            JSONObject()
                .put("localId", match.localId).put("homeTeam", match.homeTeam).put("awayTeam", match.awayTeam)
                .put("createdAtMs", match.createdAtMs)
                .putOpt("name", match.name).putOpt("playedOn", match.playedOn).putOpt("venue", match.venue)
                .put("kind", match.kind.name)
                .putOpt("homeTeamId", match.homeTeamId).putOpt("awayTeamId", match.awayTeamId)
                .put("video", encodeVideo(match.video))
                .put("session", JSONObject()
                    .putOpt("sha256", match.session.sha256)
                    .putOpt("serverMatchId", match.session.serverMatchId)
                    .putOpt("uploadId", match.session.uploadId)
                    .put("sentBytes", match.session.sentBytes))
                .putOpt("check", match.check?.let(::encodeCheck))
                .putOpt("checkedAtMs", match.checkedAtMs)
                .putOpt("analysis", match.analysis?.let {
                    JSONObject().put("jobId", it.jobId).put("state", it.state).put("mock", it.mock)
                        .put("reviewOpen", it.reviewOpen).put("reviewTotal", it.reviewTotal).putOpt("failureCode", it.failureCode)
                })
                .put("rules", encodeRules(match.rules))
        }))

    private fun encodeRules(rules: MatchRules) = JSONObject()
        .put("setsToWin", rules.setsToWin).put("setTarget", rules.setTarget).put("decidingSetTarget", rules.decidingSetTarget)

    /**
     * A file written before the rule existed has none, and that match was counted with the FIVB rule.
     * A rule the server would refuse also becomes FIVB: it could never be sent, so the upload would be stuck.
     */
    private fun decodeRules(json: JSONObject?): MatchRules = json
        ?.let { MatchRules(it.optInt("setsToWin", -1), it.optInt("setTarget", -1), it.optInt("decidingSetTarget", -1)) }
        ?.takeIf { it.isOffered } ?: MatchRules.FIVB

    private fun encodeVideo(video: LocalVideo) = JSONObject()
        .put("location", video.location).put("source", video.source.name)
        .put("fileName", video.fileName).put("sizeBytes", video.sizeBytes)
        .putOpt("mimeType", video.mimeType).putOpt("durationMs", video.durationMs)
        .putOpt("width", video.width).putOpt("height", video.height)
        .putOpt("thumbnailPath", video.thumbnailPath)

    private fun encodeCheck(check: VideoCheck) = JSONObject()
        .put("videoId", check.videoId).put("status", check.status)
        .putOpt("rejectCode", check.rejectCode)
        .putOpt("width", check.width).putOpt("height", check.height)
        .putOpt("fps", check.fps).putOpt("durationMs", check.durationMs)
        .putOpt("codec", check.codec)
        .put("warnings", JSONArray(check.warnings))

    private fun decode(json: JSONObject): StoredState {
        val draft = json.optJSONObject("draft") ?: JSONObject()
        val teams = json.optJSONArray("teams") ?: JSONArray()
        val matches = json.optJSONArray("matches") ?: JSONArray()
        return StoredState(
            draft = Draft(
                name = draft.optString("name"), playedOn = draft.optString("playedOn"), venue = draft.optString("venue"),
                kind = enumOr(draft.optString("kind"), MatchKind.TRAINING),
                homeTeam = draft.optString("homeTeam"), awayTeam = draft.optString("awayTeam"),
                homeTeamId = draft.string("homeTeamId"), awayTeamId = draft.string("awayTeamId"),
                video = draft.optJSONObject("video")?.let(::decodeVideo),
                pendingCapturePath = draft.string("pendingCapturePath"),
                rules = decodeRules(draft.optJSONObject("rules")),
            ),
            teams = List(teams.length()) { index ->
                val team = teams.getJSONObject(index)
                val players = team.optJSONArray("players") ?: JSONArray()
                Team(
                    id = team.getString("id"), name = team.getString("name"),
                    players = List(players.length()) { i ->
                        val player = players.getJSONObject(i)
                        Player(player.getString("id"), player.optString("name"), player.int("jersey"))
                    },
                )
            },
            matches = List(matches.length()) { index ->
                val match = matches.getJSONObject(index)
                val session = match.optJSONObject("session") ?: JSONObject()
                MatchRecord(
                    localId = match.getString("localId"),
                    homeTeam = match.getString("homeTeam"),
                    awayTeam = match.getString("awayTeam"),
                    createdAtMs = match.getLong("createdAtMs"),
                    video = decodeVideo(match.getJSONObject("video")),
                    name = match.string("name"), playedOn = match.string("playedOn"), venue = match.string("venue"),
                    kind = enumOr(match.optString("kind"), MatchKind.TRAINING),
                    homeTeamId = match.string("homeTeamId"), awayTeamId = match.string("awayTeamId"),
                    session = UploadSession(
                        sha256 = session.string("sha256"),
                        serverMatchId = session.string("serverMatchId"),
                        uploadId = session.string("uploadId"),
                        sentBytes = session.optLong("sentBytes", 0),
                    ),
                    check = match.optJSONObject("check")?.let(::decodeCheck),
                    checkedAtMs = match.long("checkedAtMs"),
                    analysis = match.optJSONObject("analysis")?.let {
                        AnalysisSummary(
                            jobId = it.getString("jobId"), state = it.getString("state"), mock = it.optBoolean("mock", false),
                            reviewOpen = it.optInt("reviewOpen", 0), reviewTotal = it.optInt("reviewTotal", 0), failureCode = it.string("failureCode"),
                        )
                    },
                    rules = decodeRules(match.optJSONObject("rules")),
                )
            },
        )
    }

    private fun decodeVideo(json: JSONObject) = LocalVideo(
        location = json.getString("location"),
        source = VideoSource.valueOf(json.getString("source")),
        fileName = json.getString("fileName"),
        sizeBytes = json.getLong("sizeBytes"),
        mimeType = json.string("mimeType"),
        durationMs = json.long("durationMs"),
        width = json.int("width"),
        height = json.int("height"),
        thumbnailPath = json.string("thumbnailPath"),
    )

    private fun decodeCheck(json: JSONObject): VideoCheck {
        val warnings = json.optJSONArray("warnings") ?: JSONArray()
        return VideoCheck(
            videoId = json.getString("videoId"),
            status = json.getString("status"),
            rejectCode = json.string("rejectCode"),
            width = json.int("width"),
            height = json.int("height"),
            fps = if (json.isNull("fps")) null else json.getDouble("fps"),
            durationMs = json.long("durationMs"),
            codec = json.string("codec"),
            warnings = List(warnings.length()) { warnings.getString(it) },
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    // A missing key stays null. It never turns into "", 0 or false.
    private fun JSONObject.string(name: String): String? = if (isNull(name)) null else getString(name)
    private fun JSONObject.long(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.int(name: String): Int? = if (isNull(name)) null else getInt(name)
}
