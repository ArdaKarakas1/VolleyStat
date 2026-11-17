package com.voleybolveveleybol.volleystat.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.BuildConfig
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.VolleyStatApp
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.SignedIn
import com.voleybolveveleybol.volleystat.data.MatchInfo
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.data.UploadEngine
import com.voleybolveveleybol.volleystat.data.VideoBytes
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.model.gaps
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val HOME_TEAM = "Düzeltme Ev"
private const val AWAY_TEAM = "Düzeltme Misafir"

/**
 * The corrections of the rally list through the real screens, view model and API client, against
 * a running backend with the demo analyzer: a rally is moved, split and merged from its review
 * card, and a missed one is added from the list. After every step the server is asked what it
 * holds.
 *
 * No player runs in a JVM test, so a stand-in holds the place of the video and the test puts the
 * picture where a user would have stopped it. Real playback is the device test's business. The
 * intervals are the demo analyzer's inventions and the clip is a test pattern, so this proves
 * the plumbing and nothing about any match. It runs under the same conditions as AppFlowE2eTest.
 *
 * The rule of the match changes through screen 13 the same way. The demo job's rallies reach no
 * set boundary, so that test sees the rule named, not sets counted with it: the server tests count them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RallyFixE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val clip = System.getenv("VOLLEYSTAT_E2E_VIDEO")?.let(::File)
    private var videoId: String? = null
    private var matchId: String? = null
    /** The account the test opens on the backend (VOLLEYSTAT_SIGNUP=open) and deletes afterwards. */
    private var account: SignedIn? = null
    private var password: String? = null
    private val control = FakeControl()

    /** Uploads the clip and stores the match the way the app itself would have left it. */
    @get:Rule(order = 0) val acceptedMatch = object : ExternalResource() {
        override fun before() {
            assumeTrue("needs a backend on ${BuildConfig.API_URL_USB}", url == BuildConfig.API_URL_USB && clip != null && clip.isFile)
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)

            val (opened, secret) = TestAccounts.registerOnBackend(url!!)
            account = opened
            password = secret
            var session = UploadSession()
            val check = UploadEngine(ApiClient(url, token = opened.token)).run(
                session, MatchInfo(HOME_TEAM, AWAY_TEAM), "e2e.mp4", "video/mp4", VideoBytes(clip!!.length()) { clip.inputStream() },
                onSession = { session = it }, onProgress = { _, _, _ -> }, cancelled = { false },
            )
            videoId = check.videoId
            matchId = session.serverMatchId
            TestAccounts.seed(
                ApplicationProvider.getApplicationContext(),
                StoredState(
                    matches = listOf(
                        MatchRecord(
                            "m1", HOME_TEAM, AWAY_TEAM, System.currentTimeMillis(),
                            LocalVideo(clip.path, VideoSource.GALLERY, clip.name, clip.length(), "video/mp4", check.durationMs, check.width, check.height, null),
                            session = session, check = check, checkedAtMs = System.currentTimeMillis(),
                        )
                    ),
                ),
                TestAccounts.sessionOf(opened),
                TestAccounts.SIGNED_IN_PHONE.copy(serverTarget = ServerTarget.USB_DEVICE),
            )
        }

        override fun after() {
            val opened = account ?: return
            matchId?.let { runCatching { ApiClient(url!!, token = opened.token).deleteMatch(it) } }
            TestAccounts.deleteOnBackend(url!!, opened, password!!)
        }
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = app.activity.getString(id, *args)
    private fun waitFor(text: String) = app.waitUntil(30_000) { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(id: Int) = app.onNodeWithText(text(id)).performClick()

    /** Puts the picture where a user would have stopped it. */
    private fun standAt(positionMs: Long) {
        app.runOnUiThread { control.positionMs = positionMs }
        app.waitForIdle()
    }

    private fun openEditorOfTheCard(): Long {
        tap(R.string.review_fix_rally)
        // The button and the screen it opens carry the same words, so the wait is for a button only the editor has.
        waitFor(text(R.string.action_end_here))
        return control.positionMs
    }

    @Test fun ralliesAreMovedSplitMergedAndAddedThroughTheScreens() {
        app.setContent {
            // The real player seeks to the start it is given. The stand-in does the same.
            VolleyStatTheme { VolleyStatApp(editorVideo = { _, startMs, _ -> remember { control.positionMs = startMs; control.video } }) }
        }
        val api = ApiClient(url!!, token = account!!.token)
        app.onAllNodesWithText(text(R.string.nav_matches)).onFirst().performClick()
        app.onNodeWithText("$HOME_TEAM - $AWAY_TEAM").performClick()
        waitFor(text(R.string.action_start_review))
        val job = api.latestAnalysis(videoId!!)!!
        val proposed = api.rallies(job.id).rallies
        val count = proposed.size
        assumeTrue("the clip is long enough for two demo rallies", count >= 2)
        val (first, second) = proposed
        tap(R.string.action_start_review)

        // Move: the clip of the first rally ends before the ball is down.
        waitFor(text(R.string.review_position, 1, count))
        assertEquals("the editor opens at the start of the rally", first.startMs, openEditorOfTheCard())
        standAt(first.endMs + 1_500)
        tap(R.string.action_end_here)
        // One applied correction closes the editor, and the review is on the card of that rally.
        waitFor(text(R.string.review_position, 1, count))
        assertEquals(first.startMs to first.endMs + 1_500, api.rallies(job.id).rallies[0].let { it.startMs to it.endMs })
        app.onNodeWithText(HOME_TEAM).performClick()

        // Split: the second clip holds two rallies. The earlier part is new and asks its question first.
        waitFor(text(R.string.review_position, 2, count))
        openEditorOfTheCard()
        val cut = (second.startMs + second.endMs) / 2
        standAt(cut)
        tap(R.string.action_split_here)
        waitFor(text(R.string.review_position, 2, count + 1))
        api.rallies(job.id).rallies.let { now ->
            assertEquals(count + 1, now.size)
            assertEquals(listOf(false to (second.startMs to cut), true to (cut to second.endMs)), now.subList(1, 3).map { it.fromAnalyzer to (it.startMs to it.endMs) })
            assertEquals("the later part is still the analyzer's rally", second.id, now[2].id)
        }

        // Merge: they were one rally after all. The later part is merged with the one before it.
        tap(R.string.review_skip)
        waitFor(text(R.string.review_position, 3, count + 1))
        openEditorOfTheCard()
        tap(R.string.action_merge_previous)
        tap(R.string.merge_confirm)
        waitFor(text(R.string.review_position, 2, count))
        assertEquals(second, api.rallies(job.id).rallies[1])

        for (position in 2..count) {
            waitFor(text(R.string.review_position, position, count))
            app.onNodeWithText(HOME_TEAM).performClick()
        }
        waitFor(text(R.string.review_empty_title))

        // Add: the list shows the pauses between the rallies, and the first pause hides a rally.
        tap(R.string.rallies_title)
        waitFor(text(R.string.rallies_count, count))
        // Invented intervals can not be confirmed, so the list of a demo job offers no score check.
        app.onNodeWithText(text(R.string.rallies_demo)).assertIsDisplayed()
        assertTrue(app.onAllNodesWithText(text(R.string.action_check_score)).fetchSemanticsNodes().isEmpty())
        val pause = gaps(api.rallies(job.id)).first()
        app.onAllNodesWithText(text(R.string.rallies_gap_add)).onFirst().performClick()
        waitFor(text(R.string.editor_title_gap))
        assertEquals("the editor opens at the start of the pause", pause.windowStartMs, control.positionMs)
        // The rally starts where the pause starts. Once the server has added it, the moment this editor was opened
        // on lies inside a rally, so the editor has nothing left to show. It closes once, and the list is on screen:
        // a second step back would leave the list as well.
        tap(R.string.action_rally_started)
        standAt(pause.windowStartMs + 2_500)
        tap(R.string.action_rally_ended)
        waitFor(text(R.string.rallies_count, count + 1))
        api.rallies(job.id).rallies.first().let { added ->
            assertFalse(added.fromAnalyzer)
            assertEquals(Rally.PROPOSED, added.status)
            assertEquals(pause.windowStartMs to pause.windowStartMs + 2_500, added.startMs to added.endMs)
        }

        // Back in the review the added rally asks who won it.
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(text(R.string.review_position, 1, count + 1))
        app.onNodeWithText(HOME_TEAM).performClick()
        waitFor(text(R.string.review_empty_title))
        tap(R.string.action_open_report)

        // The report counts the added rally and says that the intervals are no longer the analyzer's alone.
        waitFor(text(R.string.report_verification_done))
        app.onNodeWithText("${count + 1}-0").assertIsDisplayed()
        app.onNodeWithText(text(R.string.report_source_corrected, "demo", "MOCK")).performScrollTo().assertIsDisplayed()
        val report = api.report(matchId!!)!!
        assertEquals(count + 1, report.home.points.value)
        assertEquals("move, split, merge and add", 4, report.corrections)
        assertEquals(1, report.ralliesUserCreated)
        assertTrue("a demo list stays unconfirmed, so every count stays a lower bound", report.ralliesMayBeMissing)
    }

    @Test fun theRuleChangesFromTheListAndAChangeMadeMeanwhileIsCaught() {
        app.setContent { VolleyStatTheme { VolleyStatApp(editorVideo = { _, startMs, _ -> remember { control.positionMs = startMs; control.video } }) } }
        val api = ApiClient(url!!, token = account!!.token)
        app.onAllNodesWithText(text(R.string.nav_matches)).onFirst().performClick()
        app.onNodeWithText("$HOME_TEAM - $AWAY_TEAM").performClick()
        waitFor(text(R.string.action_open_report))
        tap(R.string.action_open_report)
        // The app created the match with the FIVB rule, the one a new match starts with, and the report says so.
        waitFor(text(R.string.report_rules, text(R.string.rule_counting, 25, 5, 15)))
        val job = api.latestAnalysis(videoId!!)!!
        app.onNodeWithText(text(R.string.rallies_title)).performScrollTo().performClick()
        waitFor(text(R.string.rule_name_fivb))

        app.onNodeWithText(text(R.string.rule_card_change)).performScrollTo().performClick()
        waitFor(text(R.string.rule_sets_question))
        // While the screen is open, the rule changes elsewhere: the server ends every set at 25 now.
        api.putMatchRules(matchId!!, MatchRules(2, 25, 25), seen = MatchRules.FIVB)
        app.onNodeWithText(text(R.string.rule_sets_option, 2)).performScrollTo().performClick()
        app.onAllNodesWithText(text(R.string.rule_points_option, 21))[0].performScrollTo().performClick()
        tap(R.string.action_save_rule)

        // The request names the rule the screen showed. The server refuses it, so the late change does
        // not undo the one made meanwhile, and the screen says so and keeps the choice.
        // The notice names the rule the server has now, which this screen had not shown before.
        waitFor(text(R.string.rule_stale, text(R.string.rule_detail_same, 2, 25)))
        assertEquals(MatchRules(2, 25, 25), api.rallies(job.id).rules)
        app.onNodeWithText(text(R.string.rule_sets_option, 2)).performScrollTo().assertIsSelected()

        // Saved again on the rule of now, the change lands, the screen closes and the list names the rule.
        tap(R.string.action_save_rule)
        waitFor(text(R.string.rule_name_custom))
        app.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()
        assertEquals(MatchRules(2, 21, 15), api.rallies(job.id).rules)

        // The report names it too.
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(text(R.string.report_rules, text(R.string.rule_counting, 21, 3, 15)))
        assertEquals(MatchRules(2, 21, 15), api.report(matchId!!)!!.rules)
    }
}
