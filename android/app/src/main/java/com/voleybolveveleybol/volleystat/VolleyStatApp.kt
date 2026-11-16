package com.voleybolveveleybol.volleystat

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.screens.ClipPlayer
import com.voleybolveveleybol.volleystat.ui.screens.GuideScreen
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchTeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchesScreen
import com.voleybolveveleybol.volleystat.ui.screens.PlayersScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.RecordRoute
import com.voleybolveveleybol.volleystat.ui.screens.ReportScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReviewScreen
import com.voleybolveveleybol.volleystat.ui.screens.StatusScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamRosterScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoCheckScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoSourceScreen
import com.voleybolveveleybol.volleystat.ui.screens.WelcomeScreen
import java.time.LocalDate

private object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val MATCHES = "matches"
    const val TEAMS = "teams"
    const val PROFILE = "profile"
    const val TEAM = "team/{id}"
    const val NEW_MATCH = "new/match"
    const val NEW_TEAMS = "new/teams"
    const val NEW_VIDEO = "new/video"
    const val NEW_RECORD = "new/record"
    const val NEW_CHECK = "new/check"
    const val GUIDE = "guide"
    const val STATUS = "status/{id}"
    const val REVIEW = "review/{id}"
    const val REPORT = "report/{id}"
    const val PLAYERS = "players/{id}"
    fun team(id: String) = "team/$id"
    fun status(id: String) = "status/$id"
    fun review(id: String) = "review/$id"
    fun report(id: String) = "report/$id"
    fun players(id: String) = "players/$id"
}

private class Tab(val route: String, val label: Int, val icon: Int, val selectedIcon: Int)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, R.drawable.ic_home, R.drawable.ic_home_filled),
    Tab(Routes.MATCHES, R.string.nav_matches, R.drawable.ic_sports_volleyball, R.drawable.ic_sports_volleyball_filled),
    Tab(Routes.TEAMS, R.string.nav_teams, R.drawable.ic_groups, R.drawable.ic_groups_filled),
    Tab(Routes.PROFILE, R.string.nav_profile, R.drawable.ic_person, R.drawable.ic_person_filled),
)

@Composable
fun VolleyStatApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val scheme = MaterialTheme.colorScheme
    // The welcome screen is shown once; the choice is fixed for this launch so it does not jump mid-session.
    val start = remember { if (state.settings.welcomeSeen) Routes.HOME else Routes.WELCOME }

    /** One step forward. A second tap during the transition must not open the same screen twice. */
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun open(match: MatchRecord) = go(
        if (match.status == MatchStatus.NEEDS_REVIEW || match.status == MatchStatus.REPORT_READY) Routes.report(match.localId) else Routes.status(match.localId)
    )
    fun goTab(tab: String) = nav.navigate(tab) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun match(id: String?) = state.matches.firstOrNull { it.localId == id }

    /**
     * Closes the screen at [route] because what it shows no longer exists. Only the screen on top
     * may do that: a screen stays composed while it slides out, and one that popped then would
     * close the screen that just replaced it.
     */
    fun leave(route: String, toHome: Boolean = false) {
        if (nav.currentDestination?.route != route) return
        if (toHome) nav.popBackStack(Routes.HOME, inclusive = false) else nav.popBackStack()
    }
    fun roster(teamId: String?) = state.teams.firstOrNull { it.id == teamId }?.players.orEmpty()

    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.primary, selectedTextColor = scheme.primary,
            unselectedIconColor = scheme.onSurfaceVariant, unselectedTextColor = scheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.primaryContainer, selectedIconColor = scheme.primary, selectedTextColor = scheme.primary,
            unselectedIconColor = scheme.onSurfaceVariant, unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )

    NavigationSuiteScaffold(
        // Task screens have no bottom bar: one step, one action, and back pops one step.
        layoutType = if (tabs.any { it.route == route }) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()) else NavigationSuiteType.None,
        containerColor = scheme.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(navigationBarContainerColor = scheme.surface, navigationRailContainerColor = scheme.surface),
        navigationSuiteItems = {
            tabs.forEach { tab ->
                val selected = route == tab.route
                item(
                    selected = selected, onClick = { goTab(tab.route) },
                    icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                    label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                    colors = itemColors,
                )
            }
        },
    ) {
        NavHost(nav, startDestination = start) {
            composable(Routes.WELCOME) {
                WelcomeScreen(onStart = {
                    viewModel.markWelcomeSeen()
                    nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    state, onNewMatch = { go(Routes.NEW_MATCH) }, onOpenMatch = ::open,
                    onOpenMatches = { goTab(Routes.MATCHES) }, onOpenTeams = { goTab(Routes.TEAMS) }, onOpenGuide = { go(Routes.GUIDE) },
                )
            }
            composable(Routes.MATCHES) { MatchesScreen(state, onNewMatch = { go(Routes.NEW_MATCH) }, onOpenMatch = ::open) }
            composable(Routes.TEAMS) {
                TeamsScreen(state.teams, onAddTeam = { viewModel.addTeam(it) != null }, onOpenTeam = { go(Routes.team(it.id)) })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(state, BuildConfig.VERSION_NAME, viewModel::setDisplayName, viewModel::setServerTarget, viewModel::testServer, viewModel::deleteRecording)
            }
            composable(Routes.TEAM) { entry ->
                val team = state.teams.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (team == null) {
                    LaunchedEffect(Unit) { leave(Routes.TEAM) }
                } else {
                    TeamRosterScreen(
                        team, onAddPlayer = { name, jersey -> viewModel.addPlayer(team.id, name, jersey) },
                        onRemovePlayer = { viewModel.removePlayer(team.id, it.id) },
                        onDeleteTeam = { viewModel.deleteTeam(team.id) }, onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.GUIDE) { GuideScreen(onBack = { nav.popBackStack() }) }

            composable(Routes.NEW_MATCH) {
                MatchCreateScreen(state.draft, LocalDate.now().toString(), viewModel::editDraft, onContinue = { go(Routes.NEW_TEAMS) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.NEW_TEAMS) {
                MatchTeamsScreen(
                    state.draft, state.teams, onChoose = { home, team -> viewModel.chooseTeam(home, team) }, onAddTeam = viewModel::addTeam,
                    onOpenRoster = { go(Routes.team(it.id)) }, onContinue = { go(Routes.NEW_VIDEO) }, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.NEW_VIDEO) { VideoRoute(state, viewModel, nav) }
            composable(Routes.NEW_RECORD) {
                // Reading the file takes a moment. If the user has left the source screen by then, nothing opens on its own.
                val toCheck = { if (nav.currentDestination?.route == Routes.NEW_VIDEO) nav.navigate(Routes.NEW_CHECK) { popUpTo(Routes.NEW_VIDEO); launchSingleTop = true } }
                RecordRoute(
                    prepareFile = viewModel::prepareCapture,
                    // Back to the source screen first: if nothing was recorded, that is where the message appears.
                    onRecorded = { nav.popBackStack(Routes.NEW_VIDEO, inclusive = false); viewModel.onCaptureFinished(toCheck) },
                    onUseGallery = { nav.popBackStack() }, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.NEW_CHECK) {
                val video = state.draft.video
                if (video == null) {
                    // Nothing to check without a video. Starting the upload also empties the draft
                    // while it moves on to the status screen, which is the case leave() guards.
                    LaunchedEffect(Unit) { leave(Routes.NEW_CHECK, toHome = true) }
                } else {
                    // A new video never inherits the previous video's rights statement.
                    var rights by rememberSaveable(video.location) { mutableStateOf(false) }
                    VideoCheckScreen(
                        video, rights, state.serverConfigured, onRights = { rights = it },
                        onStart = { viewModel.startUpload()?.let { id -> nav.navigate(Routes.status(id)) { popUpTo(Routes.HOME) } } },
                        onChangeVideo = { nav.popBackStack() }, onBack = { nav.popBackStack() },
                    )
                }
            }

            composable(Routes.STATUS) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match == null) {
                    LaunchedEffect(Unit) { leave(Routes.STATUS, toHome = true) }
                } else {
                    // Follow the job from the moment the server accepted the file, and stop when the screen closes.
                    val ready = match.check?.status == com.voleybolveveleybol.volleystat.model.VideoCheck.READY
                    DisposableEffect(match.localId, ready) {
                        if (ready) viewModel.watchAnalysis(match.localId)
                        onDispose { viewModel.stopWatchingAnalysis() }
                    }
                    StatusScreen(
                        match, state.upload, analysis, removalFailure = state.removalProblem?.takeIf { it.matchId == match.localId }?.failure,
                        onPause = viewModel::pauseUpload, onResume = { viewModel.resumeUpload(match.localId) },
                        onRemove = { viewModel.removeMatch(match.localId) {} },
                        onRestartAnalysis = { viewModel.restartAnalysis(match.localId) },
                        onOpenReview = { go(Routes.review(match.localId)) }, onOpenReport = { go(Routes.report(match.localId)) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.REVIEW) { entry ->
                val match = match(entry.arguments?.getString("id"))
                val videoId = match?.check?.videoId
                if (match == null || videoId == null) {
                    LaunchedEffect(Unit) { leave(Routes.REVIEW) }
                } else {
                    // The shared state may still hold another match. This screen only ever shows its own.
                    val shown = if (analysis.matchId == match.localId) analysis else AnalysisUi(matchId = match.localId)
                    LaunchedEffect(match.localId) {
                        if (analysis.matchId != match.localId || analysis.job == null) viewModel.watchAnalysis(match.localId, startIfMissing = false)
                    }
                    LaunchedEffect(shown.job?.id) { if (shown.job != null) viewModel.loadReview() }
                    // The scorer belongs to the side that won the rally, as answered on that rally's winner card.
                    val winner = shown.deck?.cards?.firstOrNull { it.rallyId == shown.card?.rallyId && it.questionType == ReviewCard.RALLY_WINNER }?.answer
                    val url = remember(videoId, state.settings.serverTarget) { viewModel.api().videoContentUrl(videoId) }
                    ReviewScreen(
                        match, shown,
                        scorerRoster = roster(if (winner == ReviewCard.AWAY) match.awayTeamId else match.homeTeamId),
                        onAnswer = viewModel::answerReview, onMove = viewModel::moveReview, onDetail = viewModel::setReviewDetail,
                        onOpenReport = {
                            // Opened from the report: going back to it shows the one report there is, and it reloads.
                            val below = nav.previousBackStackEntry
                            if (below?.destination?.route == Routes.REPORT && below.arguments?.getString("id") == match.localId) nav.popBackStack()
                            else nav.navigate(Routes.report(match.localId)) { popUpTo(Routes.review(match.localId)) { inclusive = true }; launchSingleTop = true }
                        },
                        onBack = { nav.popBackStack() },
                        clip = { card -> ClipPlayer(url, card.clipStartMs, card.clipEndMs) },
                        onRetry = { viewModel.retryReview(match.localId) },
                    )
                }
            }
            composable(Routes.REPORT) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match == null) {
                    LaunchedEffect(Unit) { leave(Routes.REPORT, toHome = true) }
                } else {
                    // Reload whenever the screen comes back, so decisions made in review show up.
                    LaunchedEffect(match.localId, entry) { viewModel.loadReport(match.localId) }
                    ReportScreen(
                        match, if (analysis.matchId == match.localId) analysis.reportLoad else ReportLoad.LOADING, analysis.report.takeIf { analysis.matchId == match.localId },
                        onOpenReview = { go(Routes.review(match.localId)) }, onOpenPlayers = { go(Routes.players(match.localId)) },
                        onOpenStatus = { go(Routes.status(match.localId)) }, onRetry = { viewModel.loadReport(match.localId) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.PLAYERS) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match == null) {
                    LaunchedEffect(Unit) { leave(Routes.PLAYERS) }
                } else {
                    // After the system ended the app this screen can be the first one to open, with no report in memory.
                    val own = analysis.matchId == match.localId
                    LaunchedEffect(match.localId) { if (!own || analysis.report == null) viewModel.loadReport(match.localId) }
                    PlayersScreen(
                        match, analysis.report.takeIf { own }, roster(match.homeTeamId), roster(match.awayTeamId), onBack = { nav.popBackStack() },
                        load = if (own) analysis.reportLoad else ReportLoad.LOADING, onRetry = { viewModel.loadReport(match.localId) },
                    )
                }
            }
        }
    }
}

/** 05 with its system launcher. The picker hands over exactly one video and needs no storage permission. */
@Composable
private fun VideoRoute(state: AppUiState, viewModel: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var camera by rememberSaveable { mutableStateOf(true) }
    // Reading a picked video takes a moment. If the user has left this screen by then, nothing opens on its own.
    val toCheck = { if (nav.currentDestination?.route == Routes.NEW_VIDEO) nav.navigate(Routes.NEW_CHECK) { launchSingleTop = true } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A dismissed picker returns null and leaves the current choice alone.
        if (uri != null) {
            // Keep read access across restarts so an interrupted upload can continue later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.onVideoPicked(uri, toCheck)
        }
    }
    VideoSourceScreen(
        cameraSelected = camera, reading = state.readingVideo, problem = state.sourceProblem,
        onSelect = { camera = it },
        onGo = { if (camera) nav.navigate(Routes.NEW_RECORD) { launchSingleTop = true } else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        onOpenGuide = { nav.navigate(Routes.GUIDE) { launchSingleTop = true } }, onBack = { nav.popBackStack() },
        existing = state.draft.video, onContinueExisting = toCheck,
    )
}
