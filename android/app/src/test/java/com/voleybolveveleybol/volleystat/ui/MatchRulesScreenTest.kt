package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.model.MatchRules
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

    @Test fun theDecidingSetQuestionAndTheSentenceFollowTheNumberOfSetsToWin() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.rule_deciding_question, 5)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_counting, 25, 5, 15)).assertIsDisplayed()

        tap(twoSets)
        compose.onNodeWithText(text(R.string.rule_deciding_question, 3)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_counting, 25, 3, 15)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rule_deciding_question, 5)))

        // With both targets the same, the sentence names one target for every set.
        tap(text(R.string.rule_points_option, 21), 0)
        tap(text(R.string.rule_points_option, 21), 1)
        compose.onNodeWithText(text(R.string.rule_counting_same, 21)).assertIsDisplayed()
    }

    @Test fun everyChoiceIsARadioRowOfAtLeast48dpThatSaysWhetherItIsSelected() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val rows = listOf(
            twoSets to 0, threeSets to 0,
            text(R.string.rule_points_option_fivb, 25) to 0, text(R.string.rule_points_option, 21) to 0, text(R.string.rule_points_option, 15) to 0,
            text(R.string.rule_points_option, 25) to 0, text(R.string.rule_points_option, 21) to 1, text(R.string.rule_points_option_fivb, 15) to 0,
        )
        rows.forEach { (label, index) ->
            row(label, index).assert(radio).assertHeightIsAtLeast(48.dp)
        }
        assertEquals("one row of each answer", 8, rows.size)
    }

    @Test fun onTheNewMatchTheScreenSaysTheRuleCanChangeLaterAndWarnsAboutNothing() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.rule_intro)).assertIsDisplayed()
        assertTrue(shown(text(R.string.rule_later)))
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
        compose.onNodeWithText(text(R.string.rule_sending)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsNotEnabled()
        compose.onNodeWithText(save).assertIsNotEnabled()
        row(threeSets).assertIsNotEnabled()
        pressBack()
        assertEquals("the back press is held while the answer is on its way", 0, leaves())

        sending = false
        compose.waitForIdle()
        assertFalse(shown(text(R.string.rule_sending)))
        compose.onNodeWithText(save).assertIsEnabled()
        pressBack()
        assertEquals(1, leaves())
    }

    @Test fun aRefusalStandsAboveTheSaveButtonAndGoesWithTheNextChoice() {
        var problem by mutableStateOf<CorrectionProblem?>(CorrectionProblem.REFUSED)
        show {
            MatchRulesScreen(
                MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, problem = problem,
                onSelectionChanged = { problem = null }, initial = MatchRules(2, 21, 15),
            )
        }
        compose.onNodeWithText(text(R.string.rule_refused)).assertIsDisplayed()
        assertFalse(shown(text(R.string.correction_unreachable)))
        tap(threeSets)
        assertFalse("the next choice clears the refusal", shown(text(R.string.rule_refused)))
    }

    @Test fun aLostConnectionIsNamedAsOne() {
        show { MatchRulesScreen(MatchRules.FIVB, onSave = {}, onBack = {}, onList = true, problem = CorrectionProblem.UNREACHABLE) }
        compose.onNodeWithText(text(R.string.correction_unreachable)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rule_refused)))
    }
}
