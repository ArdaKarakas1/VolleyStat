package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.ui.screens.MatchRulesCard
import com.voleybolveveleybol.volleystat.ui.screens.MatchRulesScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screen 13, the rule of a match, one state at a time. The rules are the ones the app offers; nothing here is a real match. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MatchRulesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private var backDispatcher: OnBackPressedDispatcher? = null

    /** Shows the screen above a stand-in for the app's navigation and returns how often a back press got through to it. */
    private fun leavesBy(screen: @Composable () -> Unit): () -> Int {
        var leaves = 0
        compose.setContent {
            VolleyStatTheme(dark = false) {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                BackHandler { leaves++ }
                screen()
            }
        }
        return { leaves }
    }

    private fun pressBack() {
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent { VolleyStatTheme(dark = false) { content() } }

    // The labels the rows carry. Two rows say "21 sayı": the first answers the set, the second the deciding set.
    private val twoSets get() = text(R.string.rule_sets_option, 2)
    private val threeSets get() = text(R.string.rule_sets_option_fivb, 3)
    private val save get() = text(R.string.action_save_rule)
    private fun row(label: String, index: Int = 0) = compose.onAllNodesWithText(label)[index]
    /** The body scrolls on a small screen, so a row is brought into view before the finger reaches it. */
    private fun tap(label: String, index: Int = 0) = row(label, index).performScrollTo().performClick()
    private val announced = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun theFivbRuleIsPreselectedAndSavingNeedsAChange() {
        val saved = mutableListOf<MatchRules>()
        show { MatchRulesScreen(MatchRules.FIVB, onSave = { saved += it }, onBack = {}) }

        row(threeSets).assertIsSelected()
        row(text(R.string.rule_points_option_fivb, 25)).assertIsSelected()
        row(text(R.string.rule_points_option_fivb, 15)).assertIsSelected()
        row(twoSets).assertIsNotSelected()
        // Nothing changed, so there is nothing to save.
        compose.onNodeWithText(save).assertIsNotEnabled()

        tap(twoSets)
        row(twoSets).assertIsSelected()
        compose.onNodeWithText(save).assertIsEnabled()
        // Back at the rule it started with, the screen has nothing to save again.
        tap(threeSets)
        compose.onNodeWithText(save).assertIsNotEnabled()

        tap(twoSets)
        tap(text(R.string.rule_points_option, 21))
        compose.onNodeWithText(save).performClick()
        assertEquals(listOf(MatchRules(2, 21, 15)), saved)
    }

    @Test fun theDecidingSetQuestionAndTheSentenceFollowTheChoice() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.rule_deciding_question, 5)).performScrollTo().assertIsDisplayed()
        // The rule the match has is a fact.
        compose.onNodeWithText(text(R.string.rule_counting, 25, 5, 15)).assertIsDisplayed()

        tap(twoSets)
        compose.onNodeWithText(text(R.string.rule_deciding_question, 3)).performScrollTo().assertIsDisplayed()
        // The server counts with a choice only after Save, so the screen says it as a condition and never as a fact.
        compose.onNodeWithText(text(R.string.rule_counting_if_saved, 25, 3, 15)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rule_counting, 25, 3, 15)))
        assertFalse(shown(text(R.string.rule_deciding_question, 5)))

        // With both targets the same, the sentence names one target for every set.
        tap(text(R.string.rule_points_option, 21), 0)
        tap(text(R.string.rule_points_option, 21), 1)
        compose.onNodeWithText(text(R.string.rule_counting_same_if_saved, 21)).assertIsDisplayed()

        // Back at the rule the match has, the sentence is a fact again.
        tap(threeSets)
        tap(text(R.string.rule_points_option_fivb, 25))
        tap(text(R.string.rule_points_option_fivb, 15))
        compose.onNodeWithText(text(R.string.rule_counting, 25, 5, 15)).assertIsDisplayed()
    }

    @Test fun aRuleWithOneTargetForEverySetIsSaidInOneSentence() {
        show { MatchRulesScreen(MatchRules(2, 21, 21), onSave = {}, onBack = {}, onList = true) }
        compose.onNodeWithText(text(R.string.rule_counting_same, 21)).assertIsDisplayed()
    }

    @Test fun theRuleInForceIsSaidWithItsOwnDecidingSet() {
        // Best of three: the deciding set is the third one, and the sentence about the rule the match has says so.
        show { MatchRulesScreen(MatchRules(2, 21, 15), onSave = {}, onBack = {}, onList = true) }
        compose.onNodeWithText(text(R.string.rule_counting, 21, 3, 15)).assertIsDisplayed()
    }

    @Test fun everyChoiceIsReportedAsTheWholeRuleItMakes() {
        // The rally list route keeps what the screen reports, so that the choice outlives a list that is read again.
        val reported = mutableListOf<MatchRules>()
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, onSelectionChanged = { reported += it }) }
        tap(twoSets)
        assertEquals(MatchRules(2, 25, 15), reported.last())
        tap(text(R.string.rule_points_option, 21), 0)
        assertEquals(MatchRules(2, 21, 15), reported.last())
        tap(text(R.string.rule_points_option, 25))
        assertEquals(MatchRules(2, 21, 25), reported.last())
        // Each group reports its own answer: the sets once more, after the two targets.
        tap(threeSets)
        assertEquals(MatchRules(3, 21, 25), reported.last())
        assertEquals(4, reported.size)
    }

    @Test fun everyChoiceIsARadioRowOfAtLeast48dpThatNamesItsQuestionAndSaysWhetherItIsSelected() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        // The rows the screen draws: one for each answer of the three questions.
        compose.onAllNodes(radio).assertCountEquals(8)
        val sets = text(R.string.rule_sets_question)
        val points = text(R.string.rule_points_question)
        val deciding = text(R.string.rule_deciding_question, 5)
        val rows = listOf(
            Triple(twoSets, 0, sets), Triple(threeSets, 0, sets),
            Triple(text(R.string.rule_points_option_fivb, 25), 0, points), Triple(text(R.string.rule_points_option, 21), 0, points),
            Triple(text(R.string.rule_points_option, 15), 0, points),
            Triple(text(R.string.rule_points_option, 25), 0, deciding), Triple(text(R.string.rule_points_option, 21), 1, deciding),
            Triple(text(R.string.rule_points_option_fivb, 15), 0, deciding),
        )
        rows.forEach { (label, index, question) ->
            row(label, index).assert(radio).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                // Two questions offer "21 sayı", so a screen reader hears the question with the answer.
                .assert(hasContentDescription("$question $label"))
        }
    }

    @Test fun onTheNewMatchTheScreenSaysTheRuleCanChangeLaterAndWarnsAboutNothing() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.rule_intro)).assertIsDisplayed()
        assertTrue(shown(text(R.string.rule_later)))
        // A match ends when a team has won its sets and play after it starts a new match: the screen says so where the user picks the sets.
        assertTrue(shown(text(R.string.rule_match_end)))
        assertFalse(shown(text(R.string.rule_keeps_answers)))
        assertFalse(shown(text(R.string.rule_drops_confirmation)))
    }

    @Test fun onAConfirmedListTheScreenWarnsBeforeTheConfirmationDrops() {
        var confirmed by mutableStateOf(true)
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, listConfirmed = confirmed) }
        compose.onNodeWithText(text(R.string.rule_drops_confirmation)).performScrollTo().assertIsDisplayed()
        assertTrue(shown(text(R.string.rule_keeps_answers)))
        assertFalse(shown(text(R.string.rule_later)))
        confirmed = false
        compose.waitForIdle()
        assertFalse("an unconfirmed list has no confirmation to lose", shown(text(R.string.rule_drops_confirmation)))
    }

    @Test fun whileTheRuleIsOnItsWayTheScreenKeepsTheUser() {
        var sending by mutableStateOf(true)
        val leaves = leavesBy {
            MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, sending = sending, initial = MatchRules(2, 21, 15))
        }
        // A screen reader says the line when it appears.
        compose.onNodeWithText(text(R.string.rule_sending)).assertIsDisplayed().assert(announced)
        assertFalse("the rule on its way is the one thing the screen says", shown(text(R.string.rule_counting_if_saved, 21, 3, 15)))
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsNotEnabled()
        compose.onNodeWithText(save).assertIsNotEnabled()
        row(threeSets).assertIsNotEnabled()
        pressBack()
        assertEquals("the back press is held while the answer is on its way", 0, leaves())

        sending = false
        compose.waitForIdle()
        assertFalse(shown(text(R.string.rule_sending)))
        compose.onNodeWithText(text(R.string.rule_counting_if_saved, 21, 3, 15)).assertIsDisplayed()
        compose.onNodeWithText(save).assertIsEnabled()
        pressBack()
        assertEquals(1, leaves())
    }

    @Test fun theScreenReportsTheRuleOnItsWayAndTheRefusalToAScreenReader() {
        val announcements = Announcements(compose)
        var sending by mutableStateOf(false)
        var problem by mutableStateOf<CorrectionProblem?>(null)
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, sending = sending, problem = problem, initial = MatchRules(2, 21, 15)) }
        // The user's hand rests on Save, and the focus of a screen reader stays there. The screen itself
        // reports the line that appears: first that the rule is on its way, then how it ended.
        announcements.reset()
        sending = true
        assertEquals(listOf(text(R.string.rule_sending)), announcements.heard())

        announcements.reset()
        sending = false
        problem = CorrectionProblem.REFUSED
        assertEquals(listOf(text(R.string.rule_refused)), announcements.heard())
    }

    @Test fun aRefusalStandsAboveTheSaveButtonAndGoesWithTheNextChoice() {
        var problem by mutableStateOf<CorrectionProblem?>(CorrectionProblem.REFUSED)
        show {
            MatchRulesScreen(
                MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, problem = problem,
                onSelectionChanged = { problem = null }, initial = MatchRules(2, 21, 15),
            )
        }
        compose.onNodeWithText(text(R.string.rule_refused)).assertIsDisplayed().assert(announced)
        assertFalse(shown(text(R.string.correction_unreachable)))
        assertFalse("the refusal stands where the sentence about the choice stood", shown(text(R.string.rule_counting_if_saved, 21, 3, 15)))
        // A choice in any of the three questions is a new attempt, and the refusal goes with it.
        listOf(threeSets to 0, text(R.string.rule_points_option_fivb, 25) to 0, text(R.string.rule_points_option, 25) to 0).forEach { (label, index) ->
            problem = CorrectionProblem.REFUSED
            compose.waitForIdle()
            tap(label, index)
            assertFalse("the choice $label clears the refusal", shown(text(R.string.rule_refused)))
        }
    }

    @Test fun aRuleThatChangedOnTheServerMeanwhileSaysSoAndLeavesTheChoiceToSave() {
        // The list of now came back with the rule of now. The user's choice is still on screen.
        show { MatchRulesScreen(MatchRules(2, 25, 25), onSave = {}, onBack = {}, onList = true, problem = CorrectionProblem.STALE, initial = MatchRules(2, 21, 15)) }
        // The notice names the whole rule the server has now, the sets to win too: the next Save is made on
        // that rule, and the user checks the choice against it.
        compose.onNodeWithText(text(R.string.rule_stale, text(R.string.rule_detail_same, 2, 25))).assertIsDisplayed().assert(announced)
        assertFalse(shown(text(R.string.rule_refused)))
        compose.onNodeWithText(save).assertIsEnabled()
    }

    @Test fun onANarrowPhoneWithLargeTextTheCardPutsItsActionUnderTheRule() {
        // A 320 dp phone at 1.5x text: 288 dp between the screen margins.
        var scale by mutableStateOf(1.5f)
        var width by mutableStateOf(288.dp)
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Box(Modifier.width(width)) { MatchRulesCard(MatchRules(2, 21, 15), onClick = {}) }
            }
        }
        fun bounds(label: String) = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val detail = text(R.string.rule_detail, 2, 21, 3, 15)
        val change = text(R.string.rule_card_change)
        assertTrue("the action stands under the rule", bounds(change).top >= bounds(detail).bottom)

        // A 411 dp phone at normal text has room for both on one line.
        scale = 1f
        width = 379.dp
        compose.waitForIdle()
        assertTrue("with room, the action stands beside the rule", bounds(change).left >= bounds(detail).right)
    }

    @Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
    @Test fun onTheSmallestPhoneTheTallestNoticeLeavesSaveOnTheScreenAndARowInReach() {
        // The stale notice quotes a whole rule, so it is the tallest line above Save. 2x is the largest text Android offers.
        var scale by mutableStateOf(1.5f)
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, problem = CorrectionProblem.STALE, initial = MatchRules(2, 21, 15))
            }
        }
        val notice = text(R.string.rule_stale, text(R.string.rule_detail, 3, 25, 5, 15))
        listOf(1.5f, 2f).forEach { size ->
            scale = size
            compose.waitForIdle()
            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            val button = compose.onNodeWithText(save).assertIsEnabled().getUnclippedBoundsInRoot()
            val line = compose.onNodeWithText(notice).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("at ${size}x the whole Save button is on the screen", button.top >= line.bottom && button.bottom <= screen.bottom)
            assertTrue("at ${size}x the notice starts under the title", line.top >= compose.onNodeWithText(text(R.string.rule_title)).getUnclippedBoundsInRoot().bottom)
            println("STALE_NOTICE scale=$size notice=${line.bottom - line.top} above=${line.top - compose.onNodeWithText(text(R.string.rule_title)).getUnclippedBoundsInRoot().bottom}")
            // The questions scroll in what is left, and a row comes within reach of a finger.
            val other = if (size == 1.5f) threeSets else twoSets
            tap(other)
            row(other).assertIsSelected()
        }
    }

    @Test fun aLostConnectionIsNamedAsOne() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, problem = CorrectionProblem.UNREACHABLE) }
        compose.onNodeWithText(text(R.string.correction_unreachable)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rule_refused)))
    }
}
