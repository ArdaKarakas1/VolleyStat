package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.BuildConfig
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val HOME_TEAM = "E2E Yükleme Ev"
private const val AWAY_TEAM = "E2E Yükleme Misafir"

/**
 * The upload as the app itself performs it: the view model turns a draft with a recorded file
 * into a match, uploads it to a running backend, and the status screen, open during the upload,
 * moves on to the analysis by itself. Runs under the same conditions as [AppFlowE2eTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppUploadFlowE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val clip = System.getenv("VOLLEYSTAT_E2E_VIDEO")?.let(::File)
    private val storeFile get() = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "volleystat.json")

    /** A draft as the recording screen leaves it: two team names and a file in private storage. */
    @get:Rule(order = 0) val draftWithRecording = object : ExternalResource() {
        override fun before() {
            assumeTrue("needs a backend on ${BuildConfig.API_URL_USB}", url == BuildConfig.API_URL_USB && clip != null && clip.isFile)
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
            LocalStore(storeFile).save(
                StoredState(
                    draft = Draft(
                        homeTeam = HOME_TEAM, awayTeam = AWAY_TEAM,
                        video = LocalVideo(clip!!.path, VideoSource.CAMERA, clip.name, clip.length(), "video/mp4", null, null, null, null),
                    ),
                    settings = Settings(serverTarget = ServerTarget.USB_DEVICE, welcomeSeen = true),
                )
            )
        }

        override fun after() {
            runCatching { LocalStore(storeFile).load().matches.singleOrNull()?.check?.videoId?.let { ApiClient(url!!).deleteVideo(it) } }
        }
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = app.activity.getString(id)

    @Test fun theAppUploadsADraftVideoAndTheOpenStatusScreenReachesReview() {
        val viewModel = ViewModelProvider(app.activity)[AppViewModel::class.java]
        var matchId: String? = null
        app.runOnUiThread { matchId = viewModel.startUpload() }
        assertNotNull(matchId)

        // The status screen is opened while the upload still runs, as after "Yüklemeyi Başlat".
        app.onAllNodesWithText(text(R.string.nav_matches)).onFirst().performClick()
        app.onNodeWithText("$HOME_TEAM - $AWAY_TEAM").performClick()
        app.waitUntil(60_000) { app.onAllNodesWithText(text(R.string.action_start_review)).fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()

        val match = LocalStore(storeFile).load().matches.single()
        assertEquals(VideoCheck.READY, match.check!!.status)
        assertEquals(clip!!.length(), match.session.sentBytes)
        assertNotNull(match.session.serverMatchId)
    }
}
