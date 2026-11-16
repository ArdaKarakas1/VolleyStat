package com.voleybolveveleybol.volleystat.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityRecord
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * What a screen reports to a screen reader by itself. Accessibility is switched on the way a running
 * screen reader switches it on, and the events the screen sends are recorded. A line counts as heard
 * when a "content changed" event came from a polite live region that holds it: that is the event a
 * screen reader reads a live region out for. What TalkBack then says on a phone is not measured here.
 *
 * Create it before the screen is set: the screen asks whether a screen reader runs when it attaches.
 */
class Announcements(private val compose: ComposeTestRule) {
    private val shadow = shadowOf(ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java))
    private val sourceNodeId = AccessibilityRecord::class.java.getDeclaredMethod("getSourceNodeId").apply { isAccessible = true }
    private val live = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private var mark = 0

    init {
        shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo().apply { feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN }))
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
    }

    /** The screen sends its events a moment after a change, so the clocks move on until they are out. */
    private fun settle() {
        repeat(3) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300)
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        }
        compose.waitForIdle()
    }

    /** Forgets what the screen reported so far. */
    fun reset() {
        settle()
        mark = shadow.sentAccessibilityEvents.size
    }

    /** The texts of the live regions that reported a change since the last [reset]. */
    fun heard(): List<String> {
        settle()
        val regions = compose.onAllNodes(live).fetchSemanticsNodes().associate { node ->
            node.id to node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        }
        return shadow.sentAccessibilityEvents.drop(mark)
            .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
            // The id of the node an event came from sits in the upper half of the record's source id.
            .mapNotNull { regions[((sourceNodeId.invoke(it) as Long) shr 32).toInt()] }
            .distinct()
    }
}
