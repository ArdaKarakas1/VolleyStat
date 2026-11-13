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

    fun open(match: MatchRecord) = nav.navigate(
        if (match.status == MatchStatus.NEEDS_REVIEW || match.status == MatchStatus.REPORT_READY) Routes.report(match.localId) else Routes.status(match.localId)
    )
    fun goTab(tab: String) = nav.navigate(tab) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun match(id: String?) = state.matches.firstOrNull { it.localId == id }
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
                    state, onNewMatch = { nav.navigate(Routes.NEW_MATCH) }, onOpenMatch = ::open,
                    onOpenMatches = { goTab(Routes.MATCHES) }, onOpenTeams = { goTab(Routes.TEAMS) }, onOpenGuide = { nav.navigate(Routes.GUIDE) },
                )
            }
            composable(Routes.MATCHES) { MatchesScreen(state, onNewMatch = { nav.navigate(Routes.NEW_MATCH) }, onOpenMatch = ::open) }
            composable(Routes.TEAMS) {
                TeamsScreen(state.teams, onAddTeam = { viewModel.addTeam(it) != null }, onOpenTeam = { nav.navigate(Routes.team(it.id)) })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(state, BuildConfig.VERSION_NAME, viewModel::setDisplayName, viewModel::setServerTarget, viewModel::testServer, viewModel::deleteRecording)
            }
            composable(Routes.TEAM) { entry ->
                val team = state.teams.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (team == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
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
                MatchCreateScreen(state.draft, LocalDate.now().toString(), viewModel::editDraft, onContinue = { nav.navigate(Routes.NEW_TEAMS) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.NEW_TEAMS) {
                MatchTeamsScreen(
                    state.draft, state.teams, onChoose = { home, team -> viewModel.chooseTeam(home, team) }, onAddTeam = viewModel::addTeam,
                    onOpenRoster = { nav.navigate(Routes.team(it.id)) }, onContinue = { nav.navigate(Routes.NEW_VIDEO) }, onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.NEW_VIDEO) { VideoRoute(state, viewModel, nav) }
            composable(Routes.NEW_RECORD) {
                val toCheck = { nav.navigate(Routes.NEW_CHECK) { popUpTo(Routes.NEW_VIDEO) } }
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
                    // Nothing to check without a video, so this screen leaves. It must only do so
                    // while it is still the current screen: starting the upload empties the draft
                    // and moves on to the status screen, and this screen, still drawn while it
                    // slides out, would otherwise close that status screen again.
                    LaunchedEffect(Unit) {
                        if (nav.currentDestination?.route == Routes.NEW_CHECK) nav.popBackStack(Routes.HOME, inclusive = false)
                    }
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
                    LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
                } else {
                    // Follow the job from the moment the server accepted the file, and stop when the screen closes.
                    val ready = match.check?.status == com.voleybolveveleybol.volleystat.model.VideoCheck.READY
                    DisposableEffect(match.localId, ready) {
                        if (ready) viewModel.watchAnalysis(match.localId)
                        onDispose { viewModel.stopWatchingAnalysis() }
                    }
                    StatusScreen(
                        match, state.upload, analysis, removalFailed = state.removalFailedFor == match.localId,
                        onPause = viewModel::pauseUpload, onResume = { viewModel.resumeUpload(match.localId) },
                        onRemove = { viewModel.removeMatch(match.localId) {} },
                        onRestartAnalysis = { viewModel.restartAnalysis(match.localId) },
                        onOpenReview = { nav.navigate(Routes.review(match.localId)) }, onOpenReport = { nav.navigate(Routes.report(match.localId)) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.REVIEW) { entry ->
                val match = match(entry.arguments?.getString("id"))
                val videoId = match?.check?.videoId
                if (match == null || videoId == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    LaunchedEffect(match.localId) {
                        if (analysis.matchId != match.localId || analysis.job == null) viewModel.watchAnalysis(match.localId, startIfMissing = false)
                    }
                    LaunchedEffect(analysis.job?.id) { if (analysis.job != null) viewModel.loadReview() }
                    // The scorer belongs to the side that won the rally, as answered on that rally's winner card.
                    val winner = analysis.deck?.cards?.firstOrNull { it.rallyId == analysis.card?.rallyId && it.questionType == ReviewCard.RALLY_WINNER }?.answer
                    val url = remember(videoId, state.settings.serverTarget) { viewModel.api().videoContentUrl(videoId) }
                    ReviewScreen(
                        match, analysis,
                        scorerRoster = roster(if (winner == ReviewCard.AWAY) match.awayTeamId else match.homeTeamId),
                        onAnswer = viewModel::answerReview, onMove = viewModel::moveReview, onDetail = viewModel::setReviewDetail,
                        onOpenReport = { nav.navigate(Routes.report(match.localId)) { popUpTo(Routes.review(match.localId)) { inclusive = true } } },
                        onBack = { nav.popBackStack() },
                        clip = { card -> ClipPlayer(url, card.clipStartMs, card.clipEndMs) },
                    )
                }
            }
            composable(Routes.REPORT) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match == null) {
                    LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
                } else {
                    // Reload whenever the screen comes back, so decisions made in review show up.
                    LaunchedEffect(match.localId, entry) { viewModel.loadReport(match.localId) }
                    ReportScreen(
                        match, if (analysis.matchId == match.localId) analysis.reportLoad else ReportLoad.LOADING, analysis.report.takeIf { analysis.matchId == match.localId },
                        onOpenReview = { nav.navigate(Routes.review(match.localId)) }, onOpenPlayers = { nav.navigate(Routes.players(match.localId)) },
                        onOpenStatus = { nav.navigate(Routes.status(match.localId)) }, onRetry = { viewModel.loadReport(match.localId) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.PLAYERS) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    PlayersScreen(match, analysis.report.takeIf { analysis.matchId == match.localId }, roster(match.homeTeamId), roster(match.awayTeamId), onBack = { nav.popBackStack() })
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
    val toCheck = { nav.navigate(Routes.NEW_CHECK) { launchSingleTop = true } }
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
        onGo = { if (camera) nav.navigate(Routes.NEW_RECORD) else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        onOpenGuide = { nav.navigate(Routes.GUIDE) }, onBack = { nav.popBackStack() },
        existing = state.draft.video, onContinueExisting = toCheck,
    )
}
