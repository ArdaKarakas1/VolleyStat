package com.voleybolveveleybol.volleystat.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.voleybolveveleybol.volleystat.AccountStandIn
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walks the real activity, navigation graph, view model and local store on the JVM, from a fresh
 * install through a registration against a stand-in of the account routes. It proves
 * that the screens are wired together; it says nothing about the camera, the video picker or
 * the player, which need a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class JourneyTest {
    // androidx keeps the first Application in a static view model factory. All tests of this
    // class share one JVM, so without this reset a later test's view model would be built with
    // the first test's Application and would read the first test's files.
    @get:Rule(order = 0) val freshViewModelFactory = object : ExternalResource() {
        override fun before() {
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            val holder = factory.declaredFields.single { it.type == factory }
            holder.isAccessible = true
            holder.set(null, null)
        }
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int, vararg arguments: Any) = app.activity.getString(id, *arguments)
    private fun tap(id: Int) = app.onAllNodesWithText(text(id)).onFirst().performClick()
    private fun waitFor(value: String) = app.waitUntil(10_000) { app.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** The account routes. From version 0.8 the app opens Home only for a signed-in account. */
    private val standIn = AccountStandIn()

    @After fun stopTheStandIn() = standIn.close()

    /** A fresh install: the welcome, the start screen and a registration lead to Home. */
    private fun passWelcome() {
        ViewModelProvider(app.activity)[AppViewModel::class.java].baseUrlOverride = standIn.baseUrl
        app.onNodeWithText(text(R.string.action_start)).performClick()
        // The sign-up mode arrives from a health read off the main thread, which the compose rule does not wait for (slice B4).
        waitFor(text(R.string.start_body))
        app.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.action_create_account)).performClick()
        app.onNode(hasSetTextAction() and hasText(text(R.string.field_display_name))).performTextInput("Deneme Oyuncu")
        app.onNode(hasSetTextAction() and hasText(text(R.string.field_password))).performTextInput("uzun bir şifre 42")
        app.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).performClick()
        app.waitUntil(10_000) { app.onAllNodesWithText(text(R.string.home_new_match_body)).fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText(text(R.string.home_new_match_body)).assertIsDisplayed()
    }

    @Test fun firstLaunchShowsWelcomeOnceAndThenTheHomeScreenWithItsTabs() {
        app.onNodeWithText(text(R.string.welcome_today)).assertIsDisplayed()
        passWelcome()
        assertTrue(standIn.exists("deneme.oyuncu"))
        listOf(R.string.nav_home, R.string.nav_matches, R.string.nav_teams, R.string.nav_profile).forEach {
            app.onAllNodesWithText(text(it)).onFirst().assertIsDisplayed()
        }
        app.onRoot().captureRoboImage("build/outputs/roborazzi/kabuk_ana_ekran.png")

        app.activityRule.scenario.recreate()
        app.onNodeWithText(text(R.string.home_new_match_body)).assertIsDisplayed()
        app.onAllNodesWithText(text(R.string.welcome_today)).assertCountEquals(0)
    }

    @Test fun tabsOpenTheirScreens() {
        passWelcome()
        tap(R.string.nav_teams)
        // Slice B4: the tab lists the server's teams, which arrive from GET /teams off the main thread (plan C4).
        waitFor(text(R.string.teams_empty_title))
        app.onNodeWithText(text(R.string.teams_empty_title)).assertIsDisplayed()
        app.onNode(hasClickAction() and hasText(text(R.string.action_create_team))).assertIsDisplayed()
        tap(R.string.nav_profile)
        app.onNodeWithText(text(R.string.profile_today)).assertIsDisplayed()
        tap(R.string.nav_matches)
        app.onNodeWithText(text(R.string.matches_empty_title)).assertIsDisplayed()
        tap(R.string.nav_home)
        app.onNodeWithText(text(R.string.home_new_match_body)).assertIsDisplayed()
    }

    @Test fun newMatchNeedsTwoDifferentTeamsBeforeTheVideoStep() {
        passWelcome()
        app.onNodeWithText(text(R.string.home_new_match_body)).performClick()
        app.onNodeWithText(text(R.string.match_info)).assertIsDisplayed()
        tap(R.string.action_continue)

        app.onNodeWithText(text(R.string.match_teams_title)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.action_continue)).assertIsNotEnabled()
        // Home team: typed into the sheet, saved and chosen in one step.
        app.onAllNodesWithText(text(R.string.match_team_pick)).onFirst().performClick()
        app.onNodeWithText(text(R.string.teams_new_label)).performTextInput("A Takımı")
        app.onNodeWithText(text(R.string.team_sheet_add)).performClick()
        app.onNodeWithText("A Takımı").assertIsDisplayed()
        app.onNodeWithText(text(R.string.action_continue)).assertIsNotEnabled()
        // Away team: the only "pick a team" row that is left.
        app.onNodeWithText(text(R.string.match_team_pick)).performClick()
        app.onNodeWithText(text(R.string.teams_new_label)).performTextInput("B Takımı")
        app.onNodeWithText(text(R.string.team_sheet_add)).performClick()
        app.onNodeWithText(text(R.string.action_continue)).assertIsEnabled().performClick()

        app.onNodeWithText(text(R.string.source_title)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.action_record)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.source_gallery_title)).performClick()
        app.onNodeWithText(text(R.string.action_pick_video)).assertIsDisplayed()

        // Back keeps the draft: the team step still shows both names.
        app.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        app.onNodeWithText("A Takımı").assertIsDisplayed()
        app.onNodeWithText("B Takımı").assertIsDisplayed()
    }

    @Test fun cameraChoiceOpensThePermissionScreenAndOffersTheGallery() {
        passWelcome()
        app.onNodeWithText(text(R.string.home_new_match_body)).performClick()
        tap(R.string.action_continue)
        app.onAllNodesWithText(text(R.string.match_team_pick)).onFirst().performClick()
        app.onNodeWithText(text(R.string.teams_new_label)).performTextInput("A Takımı")
        app.onNodeWithText(text(R.string.team_sheet_add)).performClick()
        app.onNodeWithText(text(R.string.match_team_pick)).performClick()
        app.onNodeWithText(text(R.string.teams_new_label)).performTextInput("B Takımı")
        app.onNodeWithText(text(R.string.team_sheet_add)).performClick()
        tap(R.string.action_continue)
        app.onNodeWithText(text(R.string.action_record)).performClick()
        // No camera permission in a fresh install: the app explains before the system dialog appears.
        app.onNodeWithText(text(R.string.record_permission_title)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.record_use_gallery)).performClick()
        app.onNodeWithText(text(R.string.source_title)).assertIsDisplayed()
    }

    @Test fun theRuleOfANewMatchIsFivbUntilTheUserChangesItAndSurvivesBackAndARestart() {
        passWelcome()
        app.onNodeWithText(text(R.string.home_new_match_body)).performClick()
        app.onNodeWithText(text(R.string.rule_name_fivb)).performScrollTo().assertIsDisplayed()

        // Back from screen 13 throws the choice away.
        app.onNodeWithText(text(R.string.rule_card_change)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.rule_title)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.rule_sets_option, 2)).performScrollTo().performClick()
        app.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        app.onNodeWithText(text(R.string.rule_name_fivb)).performScrollTo().assertIsDisplayed()

        // Saved, the choice goes into the draft.
        app.onNodeWithText(text(R.string.rule_card_change)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.rule_sets_option, 2)).performScrollTo().performClick()
        app.onAllNodesWithText(text(R.string.rule_points_option, 21))[0].performScrollTo().performClick()
        app.onNodeWithText(text(R.string.action_save_rule)).performClick()
        app.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()
        app.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()
        // Opened again, the screen starts from the rule of the draft and has nothing to save yet.
        app.onNodeWithText(text(R.string.rule_card_change)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.rule_sets_option, 2)).performScrollTo().assertIsSelected()
        app.onAllNodesWithText(text(R.string.rule_points_option, 21))[0].assertIsSelected()
        app.onNodeWithText(text(R.string.action_save_rule)).assertIsNotEnabled()
        app.onNodeWithContentDescription(text(R.string.action_back)).performClick()

        // Left on Home, a setup with nothing but a chosen rule is an unfinished setup: the rule must not slip
        // unseen into the next match.
        app.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        app.onNodeWithText(text(R.string.home_draft_title)).performScrollTo().assertIsDisplayed()
        app.onNodeWithText(text(R.string.home_draft_title)).performClick()
        app.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()

        // The rule stays with the draft on the way to the team step and back, and through a restart.
        tap(R.string.action_continue)
        app.onNodeWithText(text(R.string.match_teams_title)).assertIsDisplayed()
        app.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        app.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()
        app.activityRule.scenario.recreate()
        app.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aRuleLeftOnAnUnfinishedSetupIsNamedOnHomeAndBeforeTheUploadStarts() {
        passWelcome()
        // A setup the user left some day: two teams, a video and a rule that is not the standard one.
        val viewModel = ViewModelProvider(app.activity)[AppViewModel::class.java]
        app.runOnUiThread {
            viewModel.editDraft {
                it.copy(
                    homeTeam = "A Takımı", awayTeam = "B Takımı", rules = MatchRules(2, 21, 15),
                    video = LocalVideo("/none/mac.mp4", VideoSource.GALLERY, "mac.mp4", 1_000, "video/mp4", 60_000, 1920, 1080, null),
                )
            }
        }
        app.waitForIdle()
        // Home names the rule the setup holds.
        app.onNodeWithText(text(R.string.home_draft_title)).performScrollTo().assertIsDisplayed()
        app.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()

        // The big card opens the same setup. The user walks on without looking at the rule on 03.
        app.onNodeWithText(text(R.string.home_new_match_body)).performScrollTo().performClick()
        tap(R.string.action_continue)
        app.onNodeWithText(text(R.string.match_teams_title)).assertIsDisplayed()
        tap(R.string.action_continue)
        app.onNodeWithText(text(R.string.source_existing_action)).performClick()

        // The last screen before the upload names the rule next to the box the user has to tick.
        app.onNodeWithText(text(R.string.check_title)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()
        app.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()

        // The user ticks the box and then decides to look at the rule after all.
        app.onNodeWithText(text(R.string.check_rights)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.check_rights)).assertIsOn()

        // From there the user puts the standard rule back, and the screen has no rule to name any more.
        app.onNodeWithText(text(R.string.rule_card_change)).performClick()
        app.onNodeWithText(text(R.string.rule_sets_option_fivb, 3)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.rule_points_option_fivb, 25)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.action_save_rule)).performClick()
        app.onNodeWithText(text(R.string.check_title)).assertIsDisplayed()
        assertTrue(app.onAllNodesWithText(text(R.string.rule_card_change)).fetchSemanticsNodes().isEmpty())
        assertEquals(MatchRules.FIVB, viewModel.state.value.draft.rules)
        // The statement the user gave about this video is still given: the video did not change.
        app.onNodeWithText(text(R.string.check_rights)).performScrollTo().assertIsOn()
    }
}
