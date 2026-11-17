package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.EditTarget
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.SetPoint
import com.voleybolveveleybol.volleystat.model.gapTarget
import com.voleybolveveleybol.volleystat.model.rallyTarget
import com.voleybolveveleybol.volleystat.ui.screens.RalliesScreen
import com.voleybolveveleybol.volleystat.ui.screens.RallyEditorScreen
import com.voleybolveveleybol.volleystat.AccountUi
import com.voleybolveveleybol.volleystat.LegacyQuestion
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.SignupClosedReason
import com.voleybolveveleybol.volleystat.ui.screens.DeleteAccountScreen
import com.voleybolveveleybol.volleystat.ui.screens.LegacyScreen
import com.voleybolveveleybol.volleystat.ui.screens.PasswordChangeScreen
import com.voleybolveveleybol.volleystat.ui.screens.LoginScreen
import com.voleybolveveleybol.volleystat.ui.screens.RegisterScreen
import com.voleybolveveleybol.volleystat.ui.screens.ServerSection
import com.voleybolveveleybol.volleystat.ui.screens.StartScreen
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.ReportLoad
import com.voleybolveveleybol.volleystat.UploadProgress
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.data.UploadPhase
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Coverage
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.MatchPart
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchKind
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.PlayerLine
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.ReviewDeck
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.TeamStats
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.ui.screens.ClipPlaceholder
import com.voleybolveveleybol.volleystat.ui.screens.GuideScreen
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchTeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchesScreen
import com.voleybolveveleybol.volleystat.ui.screens.PlayersScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.RecordOverlay
import com.voleybolveveleybol.volleystat.ui.screens.RecordPermissionScreen
import com.voleybolveveleybol.volleystat.ui.screens.RecordUi
import com.voleybolveveleybol.volleystat.ui.screens.MatchRulesScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReportScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReviewScreen
import com.voleybolveveleybol.volleystat.ui.screens.StatusScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamRosterScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoCheckScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoSourceScreen
import com.voleybolveveleybol.volleystat.ui.screens.WelcomeScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.voleybolveveleybol.volleystat.part

/**
 * Renders every screen in its main states with fixed, invented data. The images are the visual
 * record of the design; names and numbers here are placeholders and mean nothing.
 *
 * Record with: ./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    /**
     * A screenshot is a still. With animations on, a screen that shows a spinner asks for a new frame
     * after every frame, and the test runtime, which serves every frame at once, never comes to rest.
     * Whether that happens depended on which test ran before, so the screenshots turn animations off.
     */
    @Before fun noAnimations() {
        val global = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        android.provider.Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, global, 0f)
    }

    private val video = LocalVideo("/data/kayit.mp4", VideoSource.CAMERA, "kayit-1731400000.mp4", 1_240_000_000, "video/mp4", 4_980_000, 1920, 1080, null)
    private val accepted = VideoCheck("v1", VideoCheck.READY, null, 1920, 1080, 30.0, 4_980_000, "h264", emptyList())
    private val players = listOf(Player("p1", "Birinci Oyuncu", 4), Player("p2", "İkinci Oyuncu", 7), Player("p3", "", 9), Player("p4", "Dördüncü Oyuncu", 12))
    private val teams = listOf(Team("t1", "A Takımı", players), Team("t2", "B Takımı", listOf(Player("p5", "Beşinci Oyuncu", 3))))

    private fun match(id: String, check: VideoCheck? = accepted, analysis: AnalysisSummary? = null, name: String? = null, session: UploadSession = UploadSession()) =
        MatchRecord(id, "A Takımı", "B Takımı", 1_731_400_000_000, video, name, "2026-11-12", "Örnek Salon", MatchKind.TRAINING, "t1", "t2", session, check, 1_731_403_000_000, analysis)

    private val uploading = match("m1", check = null, session = UploadSession("abc", "srv", "up", 533_000_000))
    private val reviewing = match("m2", analysis = AnalysisSummary("j1", AnalysisSummary.NEEDS_REVIEW, mock = true, reviewOpen = 11, reviewTotal = 18), name = "Hazırlık Maçı")
    private val finished = match("m3", analysis = AnalysisSummary("j2", AnalysisSummary.COMPLETE, mock = false, reviewOpen = 0, reviewTotal = 42))
    private val state = AppUiState(
        matches = listOf(uploading, reviewing, finished), teams = teams, settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true),
        account = AccountUi(user = AccountUser("user-1", "deneme", "Deneme")),
        upload = UploadProgress("m1", UploadPhase.SENDING, 533_000_000, 1_240_000_000, running = true),
    )

    private fun job(state: String, mock: Boolean = true, open: Int = 11, done: Long? = null, total: Long? = null, failure: String? = null) =
        AnalysisJob("j1", "v1", state, "DETECTING_RALLIES", done, total, if (mock) "demo" else "motion-baseline", if (mock) "MOCK" else "BASELINE", mock, failure, 18, open, 18)

    private fun card(type: String, options: List<String>, answer: String? = null, sequence: Int = 8) =
        ReviewCard("c$sequence$type", "r$sequence", sequence, type, if (type == ReviewCard.RALLY_WINNER) "SCORE" else "DETAIL", 441_000, 449_000, null, options, if (answer == null) "OPEN" else "ANSWERED", answer, 1)

    private val winnerOptions = listOf("HOME", "AWAY", "NOT_A_RALLY", "UNSURE")
    private val report = MatchReport(
        "j1", "motion-baseline", "BASELINE", mock = false, verificationStatus = "PARTIALLY_VERIFIED",
        ralliesProposed = 42, ralliesVerified = 30, ralliesRejected = 2, ralliesUnresolved = 1, ralliesOpen = 9,
        scoreStatus = "PARTIAL", matches = listOf(part(SetScore(1, 25, 21, true), SetScore(2, 3, 4, false))),
        ralliesMayBeMissing = true,
        home = TeamStats(StatValue(28, "PARTIAL"), StatValue(11, "PARTIAL"), StatValue(3, "PARTIAL"), StatValue(2, "PARTIAL"), StatValue(null, "UNKNOWN")),
        away = TeamStats(StatValue(25, "PARTIAL"), StatValue(9, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(1, "PARTIAL"), StatValue(4, "PARTIAL")),
        players = listOf(PlayerLine(true, 4, 6, 5, 1, 0), PlayerLine(true, 7, 4, 2, 1, 1), PlayerLine(true, 9, 3, 3, 0, 0), PlayerLine(false, 3, 5, 4, 0, 1)),
        unattributedHome = 15, unattributedAway = 20,
        coverage = listOf(Coverage("RALLY_WINNER", 40, 30), Coverage("TERMINAL_ACTION", 30, 22), Coverage("SCORER", 18, 13)),
        rules = MatchRules.FIVB,
    )

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        captureRoboImage("build/outputs/roborazzi/$name.png") { VolleyStatTheme(dark = dark) { content() } }
    }

    /** One screen on another device shape or text size, then back to the default phone. */
    private fun shotOn(name: String, qualifiers: String, fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        try {
            shot(name, dark = dark, content = content)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("+w411dp-h891dp-port")
        }
    }

    @Test fun s01_welcome() = shot("01_acilis") { WelcomeScreen {} }

    @Test fun s02_home() {
        shot("02_ana_ekran") { HomeScreen(state, {}, {}, {}, {}, {}) }
        shot("02_ana_ekran_bos") { HomeScreen(AppUiState(), {}, {}, {}, {}, {}) }
        shot("02_ana_ekran_koyu", dark = true) { HomeScreen(state, {}, {}, {}, {}, {}) }
        // A setup the user left with nothing but a rule: its card names the rule.
        shot("02_ana_ekran_yarim_kurulum_kural") { HomeScreen(AppUiState(draft = Draft(rules = MatchRules(2, 21, 15))), {}, {}, {}, {}, {}) }
    }

    @Test fun tabs() {
        shot("maclar") { MatchesScreen(state, {}, {}) }
        shot("takimlar") { TeamsScreen(teams, { true }, {}) }
        shot("takim_kadro") { TeamRosterScreen(teams[0], { _, _ -> true }, {}, {}, {}) }
        shot("profil") { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }) }
        // The privacy lines sit below the first screen; a tall window keeps all of them in one picture.
        shotOn("profil_gizlilik", "+w411dp-h2600dp-port") { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }) }
    }

    @Test fun s03_s04_s05_newMatch() {
        shot("03_mac_olustur") { MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon"), "2026-11-12", {}, {}, {}) }
        shot("03_mac_olustur_kural") { MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon", rules = MatchRules(2, 21, 15)), "2026-11-12", {}, {}, {}) }
        shot("03_mac_olustur_koyu", dark = true) { MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon"), "2026-11-12", {}, {}, {}) }
        shotOn("kucuk_03_mac_olustur", "+w320dp-h568dp-port") { MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon"), "2026-11-12", {}, {}, {}) }
        shotOn("buyuk_yazi_03_mac_olustur", "+w411dp-h891dp-port", fontScale = 1.5f) { MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon"), "2026-11-12", {}, {}, {}) }
        // The narrowest phone with large text: the date card and the rule card put their action under the choice.
        // A tall window keeps both cards in the picture.
        shotOn("kucuk_buyuk_yazi_03_mac_olustur_kural", "+w320dp-h1200dp-port", fontScale = 1.5f) {
            MatchCreateScreen(Draft(name = "Hazırlık Maçı", venue = "Örnek Salon", rules = MatchRules(2, 21, 15)), "2026-11-12", {}, {}, {})
        }
        shot("04_takim_kadro") { MatchTeamsScreen(Draft(homeTeam = "A Takımı", awayTeam = "B Takımı", homeTeamId = "t1", awayTeamId = "t2"), teams, { _, _ -> }, { null }, {}, {}, {}) }
        shot("04_takim_kadro_bos") { MatchTeamsScreen(Draft(), emptyList(), { _, _ -> }, { null }, {}, {}, {}) }
        shot("05_video_kaynagi") { VideoSourceScreen(cameraSelected = true, reading = false, problem = null, onSelect = {}, onGo = {}, onOpenGuide = {}, onBack = {}) }
        shot("05_video_kontrol") { VideoCheckScreen(video, rightsAccepted = true, serverConfigured = true, onRights = {}, onStart = {}, onChangeVideo = {}, onBack = {}) }
        // The rule card stands above the rights box at the end of the screen, so a tall window keeps it in the picture.
        shotOn("05_video_kontrol_kural", "+w411dp-h1500dp-port") {
            VideoCheckScreen(video, rightsAccepted = false, serverConfigured = true, onRights = {}, onStart = {}, onChangeVideo = {}, onBack = {}, rules = MatchRules(2, 21, 15))
        }
        shot("cekim_rehberi") { GuideScreen {} }
    }

    @Test fun s06_record() {
        val preview: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3F4B), Color(0xFF8A6A45))))) }
        shot("06_mac_kaydi") { RecordOverlay(RecordUi(recording = true, elapsedMs = 756_000, freeBytes = 22_400_000_000), {}, {}, {}, {}, {}, preview) }
        shot("06_mac_kaydi_hazir") { RecordOverlay(RecordUi(freeBytes = 1_100_000_000), {}, {}, {}, {}, {}, preview) }
        shot("06_kamera_izni") { RecordPermissionScreen(denied = true, {}, {}, {}, {}) }
        // A match is filmed with the phone on its side, so this is the layout that matters most.
        shotOn("06_mac_kaydi_yatay", "+w891dp-h411dp-land") { RecordOverlay(RecordUi(recording = true, elapsedMs = 756_000, freeBytes = 22_400_000_000), {}, {}, {}, {}, {}, preview) }
        shotOn("06_mac_kaydi_hazir_yatay", "+w891dp-h411dp-land") { RecordOverlay(RecordUi(freeBytes = 1_100_000_000), {}, {}, {}, {}, {}, preview) }
    }

    @Test fun smallPhoneAndLargeText() {
        val review: @Composable () -> Unit = {
            val deck = ReviewDeck(List(18) { card(ReviewCard.RALLY_WINNER, winnerOptions, null, it + 1) }, 0, 18)
            ReviewScreen(reviewing, AnalysisUi("m2", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), deck, cursor = 7), players, {}, {}, {}, {}, {}) { ClipPlaceholder() }
        }
        shotOn("kucuk_02_ana_ekran", "+w320dp-h568dp-port") { HomeScreen(state, {}, {}, {}, {}, {}) }
        shotOn("kucuk_08_dogrulama", "+w320dp-h568dp-port", content = review)
        shotOn("kucuk_09_mac_raporu", "+w320dp-h568dp-port") { ReportScreen(finished, ReportLoad.READY, report, {}, {}, {}, {}, {}) }
        shotOn("kucuk_10_oyuncu", "+w320dp-h568dp-port") { PlayersScreen(finished, report, players, teams[1].players) {} }
        shotOn("buyuk_yazi_02_ana_ekran", "+w411dp-h891dp-port", fontScale = 1.5f) { HomeScreen(state, {}, {}, {}, {}, {}) }
        shotOn("buyuk_yazi_08_dogrulama", "+w411dp-h891dp-port", fontScale = 1.5f, content = review)
        shotOn("buyuk_yazi_09_mac_raporu", "+w411dp-h891dp-port", fontScale = 1.5f) { ReportScreen(finished, ReportLoad.READY, report, {}, {}, {}, {}, {}) }
        shotOn("buyuk_yazi_10_oyuncu", "+w411dp-h891dp-port", fontScale = 1.5f) { PlayersScreen(finished, report, players, teams[1].players) {} }
    }

    @Test fun s07_status() {
        fun screen(match: MatchRecord, upload: UploadProgress?, analysis: AnalysisUi): @Composable () -> Unit = { StatusScreen(match, upload, analysis, null, {}, {}, {}, {}, {}, {}, {}) }
        shot("07_yukleme", content = screen(uploading, state.upload, AnalysisUi()))
        shot("07_analiz_modeli_yok", content = screen(match("m4"), null, AnalysisUi("m4", JobLoad.NO_ANALYZER)))
        shot("07_analiz_suruyor", content = screen(match("m4"), null, AnalysisUi("m4", JobLoad.READY, job(AnalysisSummary.PROCESSING, mock = false, done = 3100, total = 5400))))
        shot("07_dogrulama_bekliyor", content = screen(reviewing, null, AnalysisUi("m2", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW))))
        shot("07_analiz_durdu", content = screen(match("m4"), null, AnalysisUi("m4", JobLoad.READY, job(AnalysisSummary.FAILED, mock = false, failure = "NO_RALLIES_FOUND"))))
    }

    @Test fun s08_review() {
        fun screen(card: ReviewCard, detail: Boolean = false): @Composable () -> Unit = {
            val deck = ReviewDeck(List(7) { card("RALLY_WINNER", winnerOptions, "HOME", it + 1) } + card + List(10) { card("RALLY_WINNER", winnerOptions, null, it + 9) }, 7, 18)
            ReviewScreen(reviewing, AnalysisUi("m2", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), deck, cursor = 7, detail = detail), players, {}, {}, {}, {}, {}) { ClipPlaceholder() }
        }
        shot("08_dogrulama_kazanan", content = screen(card(ReviewCard.RALLY_WINNER, winnerOptions)))
        shot("08_dogrulama_aksiyon", content = screen(card(ReviewCard.TERMINAL_ACTION, listOf("ATTACK", "BLOCK", "ACE", "OPPONENT_ERROR", "UNSURE"), answer = "ATTACK"), detail = true))
        shot("08_dogrulama_oyuncu", content = screen(card(ReviewCard.SCORER, listOf("UNKNOWN_PLAYER")), detail = true))
    }

    private fun rally(sequence: Int, startMs: Long, endMs: Long, winner: String? = "HOME", status: String = Rally.VERIFIED, after: SetPoint? = null, fromAnalyzer: Boolean = true) =
        Rally("r$sequence", sequence, startMs, endMs, fromAnalyzer, status, winner, after)

    /** Six rallies: one called no rally, one added by the user, and two that still wait for their winner. */
    private val waitingList = RallyList(
        listOf(
            rally(1, 62_000, 71_000, after = SetPoint(1, 1, 0)),
            rally(2, 84_000, 97_000, winner = "AWAY", after = SetPoint(1, 1, 1)),
            rally(3, 109_000, 112_000, winner = null, status = Rally.REJECTED),
            rally(4, 131_000, 143_000, after = SetPoint(1, 2, 1), fromAnalyzer = false),
            rally(5, 190_000, 204_000, winner = null, status = Rally.PROPOSED),
            rally(6, 221_000, 229_000, winner = null, status = Rally.UNRESOLVED),
        ),
        version = 12, confirmed = false, videoDurationMs = 4_980_000, matches = listOf(part(SetScore(1, 2, 1, false))),
    )
    private val decidedList = waitingList.copy(
        rallies = waitingList.rallies.map {
            when (it.sequence) {
                5 -> it.copy(status = Rally.VERIFIED, winner = "AWAY", scoreAfter = SetPoint(1, 2, 2))
                6 -> it.copy(status = Rally.VERIFIED, winner = "HOME", scoreAfter = SetPoint(1, 3, 2))
                else -> it
            }
        },
        matches = listOf(part(SetScore(1, 3, 2, false))),
    )

    private fun listState(list: RallyList, mock: Boolean = false) =
        AnalysisUi("m3", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW, mock = mock).copy(rallyListVersion = list.version), rallies = list, ralliesLoad = RalliesLoad.READY)

    @Test fun s11_rallies() {
        fun screen(analysis: AnalysisUi): @Composable () -> Unit = { RalliesScreen(finished, analysis, {}, {}, {}, {}, {}, {}) }
        shot("11_ralli_listesi", content = screen(listState(waitingList)))
        shot("11_ralli_listesi_skor_hazir", content = screen(listState(decidedList)))
        shot("11_ralli_listesi_onayli", content = screen(listState(decidedList.copy(confirmed = true))))
        shot("11_ralli_listesi_demo", content = screen(listState(decidedList, mock = true)))
        shot("11_ralli_listesi_koyu", dark = true, content = screen(listState(waitingList)))
        // The server counts this list with a best of three to 21, and the card names that rule.
        shot("11_ralli_listesi_kural", content = screen(listState(decidedList.copy(rules = MatchRules(2, 21, 15)))))
        shotOn("kucuk_11_ralli_listesi", "+w320dp-h568dp-port", content = screen(listState(waitingList)))
        shotOn("buyuk_yazi_11_ralli_listesi", "+w411dp-h891dp-port", fontScale = 1.5f, content = screen(listState(waitingList)))
        shotOn("kucuk_buyuk_yazi_11_ralli_listesi_kural", "+w320dp-h568dp-port", fontScale = 1.5f, content = screen(listState(decidedList.copy(rules = MatchRules(2, 21, 15)))))
        // The same phone after a confirmation: RallyScreensTest keeps those two pictures, because the list moves there.
    }

    @Test fun s12_editor() {
        val picture: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3F4B), Color(0xFF8A6A45))))) }
        fun screen(target: EditTarget, positionMs: Long, analysis: AnalysisUi = listState(waitingList), failed: Boolean = false): @Composable () -> Unit = {
            RallyEditorScreen(target, analysis, FakeControl(positionMs, picture).also { it.failed = failed }.video, { _, _ -> }, {}, {}, { _, _ -> }, {})
        }
        val second = rallyTarget(waitingList, "r2")!!
        val pause = gapTarget(waitingList, 143_000)!!
        shot("12_ralli_duzelt", content = screen(second, 92_400))
        shot("12_ralli_duzelt_cakisma", content = screen(second, 92_400, listState(waitingList).copy(correctionProblem = CorrectionProblem.OVERLAP, overlapsRally = 3)))
        shot("12_ralli_duzelt_video_yok", content = screen(second, 92_400, failed = true))
        shot("12_ralli_duzelt_koyu", dark = true, content = screen(second, 92_400))
        shot("12_ralli_ekle", content = screen(pause, 151_200))
        shot("12_ralli_duzelt_gonderiliyor", content = screen(second, 92_400, listState(waitingList).copy(correcting = true)))
        shotOn("kucuk_12_ralli_duzelt", "+w320dp-h568dp-port", content = screen(second, 92_400))
        // The demo analyzer is the only one a server has today, so this is the editor every user sees first.
        shotOn("kucuk_12_ralli_duzelt_demo", "+w320dp-h568dp-port", content = screen(second, 92_400, listState(waitingList, mock = true)))
        shotOn("kucuk_12_ralli_duzelt_cakisma", "+w320dp-h568dp-port", content = screen(second, 92_400, listState(waitingList).copy(correctionProblem = CorrectionProblem.OVERLAP, overlapsRally = 3)))
        shotOn("kucuk_12_ralli_duzelt_gonderiliyor", "+w320dp-h568dp-port", content = screen(second, 92_400, listState(waitingList).copy(correcting = true)))
        shotOn("kucuk_12_ralli_ekle", "+w320dp-h568dp-port", content = screen(pause, 151_200))
        shotOn("buyuk_yazi_12_ralli_duzelt", "+w411dp-h891dp-port", fontScale = 1.5f, content = screen(second, 92_400))
    }

    @Test fun s09_s10_report() {
        shot("09_mac_raporu") { ReportScreen(finished, ReportLoad.READY, report, {}, {}, {}, {}, {}) }
        // The user confirmed the list, so no number carries a plus sign, and the user's corrections are named as a source.
        shot("09_mac_raporu_liste_onayli") {
            val exact = report.copy(
                verificationStatus = "VERIFIED", scoreStatus = "MEASURED", ralliesMayBeMissing = false, listConfirmed = true, ralliesOpen = 0, ralliesUnresolved = 0,
                ralliesVerified = 40, ralliesUserCreated = 2, corrections = 5,
                home = report.home.copy(points = StatValue(28, "MEASURED")), away = report.away.copy(points = StatValue(25, "MEASURED")),
            )
            ReportScreen(finished, ReportLoad.READY, exact, {}, {}, {}, {}, {})
        }
        shot("09_mac_raporu_yok") { ReportScreen(match("m4"), ReportLoad.NONE, null, {}, {}, {}, {}, {}) }
        shot("09_mac_raporu_kararsiz_ralli") { ReportScreen(finished, ReportLoad.READY, report.copy(scoreStoppedAtRally = 29), {}, {}, {}, {}, {}) }
        shot("09_mac_raporu_koyu", dark = true) { ReportScreen(finished, ReportLoad.READY, report, {}, {}, {}, {}, {}) }
        shot("09_mac_raporu_demo") { ReportScreen(finished, ReportLoad.READY, report.copy(mock = true, analyzerId = "demo", analyzerKind = "MOCK"), {}, {}, {}, {}, {}) }
        // The rule line closes the last card of the report, so a tall window keeps it in the picture.
        shotOn("09_mac_raporu_kural", "+w411dp-h2400dp-port") {
            // Set scores a server counting to 21 could give.
            ReportScreen(finished, ReportLoad.READY, report.copy(rules = MatchRules(2, 21, 15), matches = listOf(part(SetScore(1, 21, 18, true), SetScore(2, 3, 4, false)))), {}, {}, {}, {}, {})
        }
        shot("10_oyuncu_istatistik") { PlayersScreen(finished, report, players, teams[1].players) {} }
        shot("09_mac_raporu_eski_sunucu") { ReportScreen(finished, ReportLoad.OUTDATED, null, {}, {}, {}, {}, {}) }
        // Home wins two matches and away one; the count stops in a fourth part. Every number is invented.
        val severalMatches = report.copy(
            matches = listOf(
                part(SetScore(1, 25, 20, true), SetScore(2, 25, 18, true), SetScore(3, 25, 10, true), kind = MatchPart.MATCH, winner = "HOME"),
                part(SetScore(1, 18, 25, true), SetScore(2, 20, 25, true), SetScore(3, 23, 25, true), number = 2, kind = MatchPart.MATCH, winner = "AWAY"),
                part(SetScore(1, 25, 21, true), SetScore(2, 25, 19, true), SetScore(3, 25, 17, true), number = 3, kind = MatchPart.MATCH, winner = "HOME"),
                part(SetScore(1, 25, 22, true), SetScore(2, 24, 23, false), number = 4, kind = MatchPart.STOPPED),
            ),
            scoreStoppedAtRally = 431,
        )
        shotOn("09_mac_raporu_coklu_mac", "+w411dp-h2400dp-port") { ReportScreen(finished, ReportLoad.READY, severalMatches, {}, {}, {}, {}, {}) }
        // On a confirmed list only the part the count stopped in keeps the plus.
        shotOn("09_mac_raporu_coklu_mac_onayli_koyu", "+w411dp-h2400dp-port", dark = true) {
            ReportScreen(finished, ReportLoad.READY, severalMatches.copy(ralliesMayBeMissing = false, listConfirmed = true), {}, {}, {}, {}, {})
        }
    }

    @Test fun s13_rules() {
        val chosen = MatchRules(2, 21, 15)
        shot("13_mac_kurali") { MatchRulesScreen(MatchRules.FIVB, {}, {}) }
        shot("13_mac_kurali_secim") { MatchRulesScreen(MatchRules.FIVB, {}, {}, initial = chosen) }
        shot("13_mac_kurali_liste_onayli") { MatchRulesScreen(MatchRules.FIVB, {}, {}, onList = true, listConfirmed = true, initial = chosen) }
        shot("13_mac_kurali_gonderiliyor") { MatchRulesScreen(MatchRules.FIVB, {}, {}, onList = true, sending = true, initial = chosen) }
        shot("13_mac_kurali_ret") { MatchRulesScreen(MatchRules.FIVB, {}, {}, onList = true, problem = CorrectionProblem.REFUSED, initial = chosen) }
        shot("13_mac_kurali_eski_kural") { MatchRulesScreen(MatchRules(2, 25, 25), {}, {}, onList = true, problem = CorrectionProblem.STALE, initial = chosen) }
        shot("13_mac_kurali_koyu", dark = true) { MatchRulesScreen(MatchRules.FIVB, {}, {}) }
        shotOn("kucuk_13_mac_kurali_liste_onayli", "+w320dp-h568dp-port") {
            MatchRulesScreen(MatchRules.FIVB, {}, {}, onList = true, listConfirmed = true, initial = chosen)
        }
        shotOn("buyuk_yazi_13_mac_kurali_secim", "+w411dp-h891dp-port", fontScale = 1.5f) { MatchRulesScreen(MatchRules.FIVB, {}, {}, initial = chosen) }
        // The longest notice of the screen where it has the least room: the narrowest phone with large text.
        shotOn("kucuk_buyuk_yazi_13_mac_kurali_eski_kural", "+w320dp-h568dp-port", fontScale = 1.5f) {
            MatchRulesScreen(MatchRules(2, 25, 15), {}, {}, onList = true, problem = CorrectionProblem.STALE, initial = chosen)
        }
    }

    // ----- accounts (slice B2). The fields hold invented values; a password shows as dots. -----

    @Test fun s14_s15_accounts() {
        val signedOut = state.copy(account = AccountUi())
        val server: @Composable () -> Unit = { ServerSection(signedOut, {}, {}) }
        fun login(account: AccountUi = AccountUi()): @Composable () -> Unit = {
            LoginScreen(account, { _, _ -> }, {}, server = server, initialUsername = "deneme", initialPassword = "uzun bir şifre 42")
        }
        fun register(account: AccountUi = AccountUi(), username: String? = null): @Composable () -> Unit = {
            RegisterScreen(account, { _, _, _, _ -> }, {}, {}, server = server, initialDisplayName = "Çağrı Öztürk", initialUsername = username, initialPassword = "uzun bir şifre 42", initialCode = "ABCD-EFGH-JKMN-PQRS")
        }
        shot("14a_baslangic") { StartScreen({}, {}) }
        shot("14a_baslangic_koyu", dark = true) { StartScreen({}, {}) }
        shot("14_giris", content = login())
        shot("14_giris_koyu", dark = true, content = login())
        shot("14_giris_hata", content = login(AccountUi(problem = AuthProblem.LoginFailed)))
        shot("14_giris_oturum_kapandi", content = login(AccountUi(sessionEnded = true)))
        shot("14_giris_bekliyor", content = login(AccountUi(busy = true)))
        shotOn("kucuk_buyuk_yazi_14_giris", "+w320dp-h568dp-port", fontScale = 1.5f, content = login(AccountUi(problem = AuthProblem.TooManyAttempts(AttemptScope.DEVICE, 120))))
        shot("15_hesap_olustur", content = register())
        shot("15_hesap_olustur_koyu", dark = true, content = register())
        // The whole form in one picture: the code field and the server choice sit below the first screen.
        shotOn("15_hesap_olustur_tamami", "+w411dp-h1500dp-port", content = register())
        shot("15_hesap_olustur_kapali", content = register(AccountUi(problem = AuthProblem.SignupClosed(SignupClosedReason.NO_LINK))))
        shot("15_hesap_olustur_alinmis_ad", content = register(AccountUi(problem = AuthProblem.UsernameTaken), username = "cagri.ozturk42"))
        shotOn("kucuk_buyuk_yazi_15_hesap_olustur", "+w320dp-h568dp-port", fontScale = 1.5f, content = register())
        val old = LegacyQuestion(listOf("Hazırlık Maçı", "A Takımı - B Takımı", "Lig Maçı"), teams = 2, recordings = 3, draft = true)
        shot("15b_eski_veriler") { LegacyScreen(old, saveFailed = false) {} }
        shot("15b_eski_veriler_koyu", dark = true) { LegacyScreen(old, saveFailed = false) {} }
        val signedIn = AccountUi(user = AccountUser("user-1", "deneme", "Deneme"))
        shot("15c_sifre_degistir") { PasswordChangeScreen(signedIn.copy(problem = AuthProblem.PasswordWrong), { _, _ -> }, {}) }
        shot("15d_hesabi_sil") { DeleteAccountScreen(signedIn, serverMatches = 3, onDelete = {}, onBack = {}) }
        shot("15d_hesabi_sil_koyu", dark = true) { DeleteAccountScreen(signedIn, serverMatches = 3, onDelete = {}, onBack = {}) }
        // The account card at the top of the profile.
        shotOn("profil_hesap", "+w411dp-h1100dp-port") { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }) }
        shotOn("profil_hesap_koyu", "+w411dp-h1100dp-port", dark = true) { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }) }
    }
}
