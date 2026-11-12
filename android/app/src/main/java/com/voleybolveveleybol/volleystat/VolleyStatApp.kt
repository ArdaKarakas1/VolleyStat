package com.voleybolveveleybol.volleystat

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.screens.ConfirmScreen
import com.voleybolveveleybol.volleystat.ui.screens.GuideScreen
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchesScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.SourceScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.UploadScreen

private object Routes {
    const val HOME = "home"
    const val MATCHES = "matches"
    const val PROFILE = "profile"
    const val TEAMS = "new/teams"
    const val VIDEO = "new/video"
    const val CONFIRM = "new/confirm"
    const val GUIDE = "guide"
    const val UPLOAD = "upload/{id}"
    const val MATCH = "match/{id}"
    fun upload(id: String) = "upload/$id"
    fun match(id: String) = "match/$id"
}

private class TopLevel(val route: String, val label: Int, val icon: Int)

private val topLevel = listOf(
    TopLevel(Routes.HOME, R.string.nav_home, R.drawable.ic_home),
    TopLevel(Routes.MATCHES, R.string.nav_matches, R.drawable.ic_sports_volleyball),
    TopLevel(Routes.PROFILE, R.string.nav_profile, R.drawable.ic_person),
)

@Composable
fun VolleyStatApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val scheme = MaterialTheme.colorScheme

    fun open(match: MatchRecord) = nav.navigate(if (match.status == MatchStatus.UPLOAD_PENDING) Routes.upload(match.localId) else Routes.match(match.localId))
    fun newAnalysis() = nav.navigate(Routes.TEAMS)

    // The accent marks the selected destination; the icons and labels stay ink-coloured.
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.onPrimaryContainer, selectedTextColor = scheme.onSurface,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.onPrimaryContainer, selectedTextColor = scheme.onSurface,
        ),
    )

    NavigationSuiteScaffold(
        // The linear task screens have no bottom bar: one step, one action, back pops one step.
        layoutType = if (topLevel.any { it.route == route }) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()) else NavigationSuiteType.None,
        containerColor = scheme.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = scheme.surfaceContainer,
            navigationRailContainerColor = scheme.surfaceContainer,
        ),
        navigationSuiteItems = {
            topLevel.forEach { destination ->
                item(
                    selected = route == destination.route,
                    onClick = {
                        nav.navigate(destination.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                    colors = itemColors,
                )
            }
        },
    ) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(state, onNewAnalysis = ::newAnalysis, onOpenMatch = ::open, onSeeAll = { nav.navigate(Routes.MATCHES) }, onOpenGuide = { nav.navigate(Routes.GUIDE) })
            }
            composable(Routes.MATCHES) { MatchesScreen(state, onNewAnalysis = ::newAnalysis, onOpenMatch = ::open) }
            composable(Routes.PROFILE) {
                ProfileScreen(state, BuildConfig.VERSION_NAME, viewModel::setServerTarget, viewModel::testServer, viewModel::deleteRecording)
            }
            composable(Routes.GUIDE) { GuideScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TEAMS) {
                TeamsScreen(state.draft, viewModel::setTeams, onContinue = { nav.navigate(Routes.VIDEO) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.VIDEO) { VideoRoute(state, viewModel, nav) }
            composable(Routes.CONFIRM) {
                val video = state.draft.video
                if (video == null) {
                    // The draft was just turned into a match, or its video is gone: nothing to confirm.
                    LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
                } else {
                    // A new video never inherits the previous video's rights statement.
                    var rights by rememberSaveable(video.location) { mutableStateOf(false) }
                    ConfirmScreen(
                        video, rights, state.serverConfigured, onRights = { rights = it },
                        onStart = {
                            viewModel.startUpload()?.let { id ->
                                nav.navigate(Routes.upload(id)) { popUpTo(Routes.HOME) }
                            }
                        },
                        onChangeVideo = { nav.popBackStack() }, onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.UPLOAD) { entry ->
                val id = entry.arguments?.getString("id")
                val match = state.matches.firstOrNull { it.localId == id }
                when {
                    match == null -> LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
                    // The server answered: move on to the result, and back must not return to the upload.
                    match.check != null -> LaunchedEffect(Unit) { nav.navigate(Routes.match(match.localId)) { popUpTo(Routes.HOME) } }
                    else -> UploadScreen(
                        match, state.upload, removalFailed = state.removalFailedFor == match.localId,
                        onPause = viewModel::pauseUpload, onResume = { viewModel.resumeUpload(match.localId) },
                        onCancel = { viewModel.removeMatch(match.localId) {} },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.MATCH) { entry ->
                val id = entry.arguments?.getString("id")
                val match = state.matches.firstOrNull { it.localId == id }
                if (match == null) {
                    LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
                } else {
                    MatchScreen(
                        match, state.upload, removalFailed = state.removalFailedFor == match.localId,
                        onBack = { nav.popBackStack() }, onOpenUpload = { nav.navigate(Routes.upload(match.localId)) },
                        onRemove = { viewModel.removeMatch(match.localId) {} },
                    )
                }
            }
        }
    }
}

/** Owns the two system launchers. Both give the app exactly one video and need no storage permission. */
@Composable
private fun VideoRoute(state: AppUiState, viewModel: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val toConfirm = { nav.navigate(Routes.CONFIRM) { launchSingleTop = true } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A dismissed picker returns null and leaves the current choice alone.
        if (uri != null) {
            // Keep read access across restarts so an interrupted upload can continue later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.onVideoPicked(uri, toConfirm)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { viewModel.onCaptureFinished(toConfirm) }
    SourceScreen(
        state.draft, state.readingVideo, state.sourceProblem,
        onPickGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        onRecord = {
            try {
                camera.launch(viewModel.prepareCapture())
            } catch (missing: ActivityNotFoundException) {
                viewModel.onCameraMissing()
            }
        },
        onOpenGuide = { nav.navigate(Routes.GUIDE) },
        onContinue = toConfirm,
        onBack = { nav.popBackStack() },
    )
}
