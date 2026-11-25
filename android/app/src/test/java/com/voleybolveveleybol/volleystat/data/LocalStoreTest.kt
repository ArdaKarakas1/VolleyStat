package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchKind
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.model.teamNamesValid
import com.voleybolveveleybol.volleystat.model.uploadFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val unknownVideo = LocalVideo("content://picked/1", VideoSource.GALLERY, "maç.mp4", 1234, null, null, null, null, null)
    private val knownVideo = LocalVideo("/data/rec.mp4", VideoSource.CAMERA, "rec.mp4", 5_000_000_000, "video/mp4", 5_400_000, 1920, 1080, "/data/t.jpg")

    private fun check(status: String) = VideoCheck("v", status, null, null, null, null, null, null, emptyList())

    @Test fun everythingSurvivesARestartAndUnknownStaysUnknown() {
        val file = File(folder.root, "state.json")
        val state = StoredState(
            draft = Draft("Hazırlık", "2026-11-12", "Salon", MatchKind.OFFICIAL, "A Takımı", "B Takımı", "t1", null, unknownVideo, "/data/pending.mp4",
                rules = MatchRules(2, 21, 15)),
            teams = listOf(
                Team("t1", "A Takımı", listOf(Player("p1", "Birinci Oyuncu", 7), Player("p2", "", null))),
                Team("t2", "B Takımı"),
            ),
            matches = listOf(
                MatchRecord("m1", "Ev", "Konuk", 10, knownVideo, "Final", "2026-11-01", "Salon", MatchKind.OFFICIAL, "t1", "t2",
                    UploadSession("abc", "srv", "up", 77),
                    VideoCheck("v1", VideoCheck.QUARANTINED, "VIDEO_CORRUPT", null, null, null, null, null, emptyList()), 99,
                    rules = MatchRules(2, 25, 25)),
                MatchRecord("m2", "Bir", "İki", 20, unknownVideo, check = check(VideoCheck.READY), checkedAtMs = 5,
                    analysis = AnalysisSummary("job", AnalysisSummary.NEEDS_REVIEW, mock = true, reviewOpen = 3, reviewTotal = 9), noAccess = true),
                MatchRecord("m3", "Üç", "Dört", 30, unknownVideo),
            ),
        )
        LocalStore(file).save(state)
        val loaded = LocalStore(file).load()
        assertEquals(state, loaded)
        assertNull("a duration nobody read must not come back as 0", loaded.draft.video!!.durationMs)
        assertNull(loaded.matches[0].check!!.width)
        assertNull("a player without a number keeps no number", loaded.teams[0].players[1].jersey)
        assertNull(loaded.matches[2].session.uploadId)
        assertNull(loaded.matches[2].name)
        assertFalse("a match the server never refused carries no mark", loaded.matches[0].noAccess)
    }

    @Test fun aFileWrittenByVersion0_5LoadsWithTheFivbRule() {
        // What app 0.5.0 wrote: no rule on the draft and none on the match. Those matches were counted with the FIVB rule.
        val file = File(folder.root, "state.json").apply {
            writeText(
                """{"version": 2, "draft": {"homeTeam": "A Takımı"},
                   "matches": [{"localId": "m1", "homeTeam": "Ev", "awayTeam": "Konuk", "createdAtMs": 10,
                                "video": {"location": "/data/rec.mp4", "source": "CAMERA", "fileName": "rec.mp4", "sizeBytes": 10}}]}"""
            )
        }
        val loaded = LocalStore(file).load()
        assertEquals(MatchRules.FIVB, loaded.draft.rules)
        assertEquals(MatchRules.FIVB, loaded.matches.single().rules)
        assertEquals("A Takımı", loaded.draft.homeTeam)
        assertEquals("nothing was set aside", 0, folder.root.listFiles { f -> f.name.contains(".unreadable-") }!!.size)
    }

    @Test fun aRuleThatIsNotOfferedFallsBackToFivbAndCostsNoOtherMatch() {
        // A rule the server would refuse could never be sent, so the upload of that match would be stuck for good.
        val file = File(folder.root, "state.json").apply {
            writeText(
                """{"version": 2, "draft": {"rules": {"setsToWin": 2, "setTarget": 11, "decidingSetTarget": 15}},
                   "matches": [
                     {"localId": "m1", "homeTeam": "Ev", "awayTeam": "Konuk", "createdAtMs": 10,
                      "video": {"location": "/a.mp4", "source": "CAMERA", "fileName": "a.mp4", "sizeBytes": 10},
                      "rules": {"setsToWin": 2, "setTarget": 21}},
                     {"localId": "m2", "homeTeam": "Bir", "awayTeam": "İki", "createdAtMs": 20,
                      "video": {"location": "/b.mp4", "source": "CAMERA", "fileName": "b.mp4", "sizeBytes": 10},
                      "rules": {"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15}}]}"""
            )
        }
        val loaded = LocalStore(file).load()
        assertEquals(MatchRules.FIVB, loaded.draft.rules)
        assertEquals(listOf(MatchRules.FIVB, MatchRules(2, 21, 15)), loaded.matches.map { it.rules })
        assertEquals(0, folder.root.listFiles { f -> f.name.contains(".unreadable-") }!!.size)
    }

    @Test fun anUnreadableFileIsSetAsideNotDeleted() {
        val file = File(folder.root, "state.json").apply { writeText("{ not json") }
        assertEquals(StoredState(), LocalStore(file).load())
        assertFalse(file.exists())
        assertEquals(1, folder.root.listFiles { f -> f.name.startsWith("state.json.unreadable-") }!!.size)
    }

    @Test fun matchStatusFollowsTheFileCheckAndTheJob() {
        fun status(file: String?, job: String? = null) = MatchRecord(
            "m", "A1", "B1", 0, unknownVideo, check = file?.let(::check),
            analysis = job?.let { AnalysisSummary("j", it, false, 0, 0) },
        ).status
        assertEquals(MatchStatus.UPLOAD_PENDING, status(null))
        assertEquals(MatchStatus.FILE_REJECTED, status("QUARANTINED"))
        assertEquals(MatchStatus.FILE_UNVERIFIED, status("STORED_UNVALIDATED"))
        assertEquals(MatchStatus.FILE_ACCEPTED, status("READY"))
        assertEquals(MatchStatus.ANALYZING, status("READY", "QUEUED"))
        assertEquals(MatchStatus.ANALYZING, status("READY", "PROCESSING"))
        assertEquals(MatchStatus.NEEDS_REVIEW, status("READY", "NEEDS_REVIEW"))
        assertEquals(MatchStatus.REPORT_READY, status("READY", "COMPLETE"))
        assertEquals(MatchStatus.ANALYSIS_FAILED, status("READY", "FAILED"))
        assertEquals("a cancelled job leaves the match at the accepted file", MatchStatus.FILE_ACCEPTED, status("READY", "CANCELLED"))
        assertEquals("a job never upgrades a rejected file", MatchStatus.FILE_REJECTED, status("QUARANTINED", "COMPLETE"))
    }

    @Test fun matchTitleFallsBackToTheTeams() {
        assertEquals("A1 - B1", MatchRecord("m", "A1", "B1", 0, unknownVideo).title)
        assertEquals("A1 - B1", MatchRecord("m", "A1", "B1", 0, unknownVideo, name = "  ").title)
        assertEquals("Hazırlık", MatchRecord("m", "A1", "B1", 0, unknownVideo, name = "Hazırlık").title)
    }

    @Test fun teamNamesFollowTheServerRules() {
        assertTrue(teamNamesValid(" Takım A ", "Takım B"))
        assertFalse(teamNamesValid("A ", "Takım B"))
        assertFalse(teamNamesValid("Takım A", "takım a "))
        assertFalse(teamNamesValid("x".repeat(81), "Takım B"))
    }

    @Test fun uploadFormatPrefersTheReportedTypeOverTheName() {
        assertEquals("mov", uploadFormat("video/quicktime", "clip.mp4")!!.extension)
        assertEquals("video/webm", uploadFormat(null, "Maç Videosu.WEBM")!!.mimeType)
        assertEquals("mp4", uploadFormat("application/octet-stream", "x.mp4")!!.extension)
        assertNull(uploadFormat("video/3gpp", "eski.3gp"))
        assertNull(uploadFormat(null, "adsız"))
    }
}
