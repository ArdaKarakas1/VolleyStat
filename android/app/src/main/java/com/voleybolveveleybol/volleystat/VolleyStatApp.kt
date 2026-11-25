package com.voleybolveveleybol.volleystat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.BadgedBox
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voleybolveveleybol.volleystat.model.EditTarget
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.gapTarget
import com.voleybolveveleybol.volleystat.model.rallyTarget
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.screens.ClipPlayer
import com.voleybolveveleybol.volleystat.ui.screens.DeleteAccountScreen
import com.voleybolveveleybol.volleystat.ui.screens.EditorVideo
import com.voleybolveveleybol.volleystat.ui.screens.GuideScreen
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.LegacyScreen
import com.voleybolveveleybol.volleystat.ui.screens.LoginScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchTeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchesScreen
import com.voleybolveveleybol.volleystat.ui.screens.PasswordChangeScreen
import com.voleybolveveleybol.volleystat.ui.screens.PlayersScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.RalliesScreen
import com.voleybolveveleybol.volleystat.ui.screens.RallyEditorScreen
import com.voleybolveveleybol.volleystat.ui.screens.RallyEditorWaiting
import com.voleybolveveleybol.volleystat.ui.screens.RecordRoute
import com.voleybolveveleybol.volleystat.ui.screens.RegisterScreen
import com.voleybolveveleybol.volleystat.ui.screens.ServerSection
import com.voleybolveveleybol.volleystat.ui.screens.StartScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReportScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReviewScreen
import com.voleybolveveleybol.volleystat.ui.screens.StatusScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamRosterScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamSeatsScreen
import com.voleybolveveleybol.volleystat.ui.screens.NewJoinsBadge
import com.voleybolveveleybol.volleystat.ui.screens.VideoCheckScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoSourceScreen
import com.voleybolveveleybol.volleystat.ui.screens.WelcomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.rememberEditorVideo
import com.voleybolveveleybol.volleystat.ui.screens.MatchRulesScreen
import java.time.LocalDate

private object Routes {
    const val WELCOME = "welcome"
    /** The first start without any account on this phone (design 10.1). */
    const val START = "start"
    const val LOGIN = "login"
    const val REGISTER = "register"
    /** The question of the first login about the data app 0.7 kept without an account. */
    const val LEGACY = "legacy"
    const val HOME = "home"
    /** Şifreyi Değiştir and Hesabı Sil, opened from the profile. */
    const val PASSWORD = "account/password"
    const val DELETE_ACCOUNT = "account/delete"
    const val MATCHES = "matches"
    const val TEAMS = "teams"
    const val PROFILE = "profile"
    /** A team the server holds (slice B4, design 10.1). */
    const val TEAM = "team/{id}"
    /** Takım Kur. */
    const val NEW_TEAM = "teams/new"
    /** As ve Yedek Yerleri of a team (captain). */
    const val TEAM_SEATS = "team/{id}/seats"
    /** A local roster of the personal flow, opened from New Match's team step (design 10.1). */
    const val ROSTER = "roster/{id}"
    const val NEW_MATCH = "new/match"
    /** Screen 13 on the rule of the draft. Saving only changes the draft. */
    const val NEW_RULES = "new/rules"
    const val NEW_TEAMS = "new/teams"
    const val NEW_VIDEO = "new/video"
    const val NEW_RECORD = "new/record"
    const val NEW_CHECK = "new/check"
    const val GUIDE = "guide"
    const val STATUS = "status/{id}"
    const val REVIEW = "review/{id}"
    /** The review, opened on the winner question of one rally. */
    const val REVIEW_AT = "review/{id}/at/{rally}"
    const val REPORT = "report/{id}"
    const val PLAYERS = "players/{id}"
    const val RALLIES = "rallies/{id}"
    /** Screen 13 on the rule the server counts the rally list of a match with. Saving sends it. */
    const val RULES = "rules/{id}"
    /** The range editor on one rally of the list. */
    const val RALLY = "rally/{id}/{rally}"
    /** The range editor on the pause that holds the moment {at} of the video. */
    const val GAP = "gap/{id}/{at}"
    fun team(id: String) = "team/$id"
    fun teamSeats(id: String) = "team/$id/seats"
    fun roster(id: String) = "roster/$id"
    fun status(id: String) = "status/$id"
    fun review(id: String) = "review/$id"
    fun reviewAt(id: String, rallyId: String) = "review/$id/at/$rallyId"
    fun report(id: String) = "report/$id"
    fun players(id: String) = "players/$id"
    fun rallies(id: String) = "rallies/$id"
    fun rules(id: String) = "rules/$id"
    fun rally(id: String, rallyId: String) = "rally/$id/$rallyId"
    fun gap(id: String, atMs: Long) = "gap/$id/$atMs"
}

/** The screens a phone shows while nobody is signed in. */
private val signedOutRoutes = setOf(Routes.START, Routes.LOGIN, Routes.REGISTER)

/** The screen the app opens on for [entry] (design 10.1). */
private fun routeOf(entry: Entry) = when (entry) {
    Entry.WELCOME -> Routes.WELCOME
    Entry.START -> Routes.START
    Entry.LOGIN -> Routes.LOGIN
    Entry.LEGACY -> Routes.LEGACY
    Entry.HOME -> Routes.HOME
}

/** Whether [route] belongs to what the app shows at [entry]. Within the signed-out screens and within the account's screens the user moves freely. */
private fun fits(route: String, entry: Entry) = when (entry) {
    Entry.WELCOME -> route == Routes.WELCOME
    Entry.START, Entry.LOGIN -> route in signedOutRoutes
    Entry.LEGACY -> route == Routes.LEGACY
    Entry.HOME -> route !in signedOutRoutes && route != Routes.LEGACY && route != Routes.WELCOME
}

private class Tab(val route: String, val label: Int, val icon: Int, val selectedIcon: Int)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, R.drawable.ic_home, R.drawable.ic_home_filled),
    Tab(Routes.MATCHES, R.string.nav_matches, R.drawable.ic_sports_volleyball, R.drawable.ic_sports_volleyball_filled),
    Tab(Routes.TEAMS, R.string.nav_teams, R.drawable.ic_groups, R.drawable.ic_groups_filled),
    Tab(Routes.PROFILE, R.string.nav_profile, R.drawable.ic_person, R.drawable.ic_person_filled),
)

@Composable
fun VolleyStatApp(
    viewModel: AppViewModel = viewModel(),
    /**
     * The video of the range editor, read with [headers] on every request. A test puts a stand-in
     * here, because no player runs in a test, and sees what the player would be given.
     */
    editorVideo: @Composable (url: String, startMs: Long, headers: Map<String, String>) -> EditorVideo = { url, startMs, headers -> rememberEditorVideo(url, startMs, headers) },
    /** The clip of a review card, read with [headers] on every request. A test puts a stand-in here, as for [editorVideo]. */
    clipPlayer: @Composable (url: String, startMs: Long, endMs: Long, headers: Map<String, String>) -> Unit = { url, startMs, endMs, headers -> ClipPlayer(url, startMs, endMs, headers) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val scheme = MaterialTheme.colorScheme
    // The screen the app opens on, fixed for this launch (design 10.1).
    val start = remember { routeOf(state.entry) }
    // A login, a registration, the answer about the old data, a logout, a deletion or a session the server
    // ended moves the app to another part. The back stack starts afresh there, so back never leads from the
    // login into an account's screens, or from Home back to a form.
    LaunchedEffect(state.entry) {
        val current = nav.currentDestination?.route ?: return@LaunchedEffect
        if (!fits(current, state.entry)) {
            nav.navigate(routeOf(state.entry)) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

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

    /**
     * The analysis state for a screen of the rally list. The shared state may still hold another
     * match, and a list that was read before the last winner decision is not the list the server
     * holds any more: the screen then waits for the new one instead of showing the old one.
     */
    @Composable
    fun rallyState(match: MatchRecord): AnalysisUi {
        val own = if (analysis.matchId == match.localId) analysis else AnalysisUi(matchId = match.localId)
        LaunchedEffect(match.localId) {
            // A problem belongs to the screen it happened on.
            viewModel.clearCorrectionProblem()
            if (analysis.matchId != match.localId || analysis.job == null) viewModel.watchAnalysis(match.localId, startIfMissing = false)
        }
        // Without a job there is no list to wait for. The screens read the reason from the job itself (jobProblem).
        // While a correction or a rule is on its way, the screen that sent it keeps the list it was made on: a
        // winner answer that lands meanwhile moves the job on, and the list read after the answer replaces it.
        return own.copy(rallies = own.currentRallies ?: own.rallies.takeIf { own.correcting })
    }

    @Composable
    fun ReviewRoute(match: MatchRecord?, route: String, atRally: String?) {
        val videoId = match?.check?.videoId
        if (match == null || videoId == null) {
            LaunchedEffect(Unit) { leave(route) }
            return
        }
        // The shared state may still hold another match. This screen only ever shows its own.
        val shown = if (analysis.matchId == match.localId) analysis else AnalysisUi(matchId = match.localId)
        LaunchedEffect(match.localId) {
            if (analysis.matchId != match.localId || analysis.job == null) viewModel.watchAnalysis(match.localId, startIfMissing = false)
        }
        // Opening the screen goes to the card it was opened for. Coming back to it, from the range
        // editor or the rally list, stays on the card it shows, which a correction may have chosen.
        // After the system ended the app, the screen comes back as opened and without its cards: the
        // user may have answered the rally it was opened for long ago, so it goes to the first open card.
        // The screen counts as opened once it has shown its cards. Until then every load, a retry after a
        // failed one included, still asks for the card it was opened for.
        var opened by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(shown.job?.id) {
            if (shown.job != null) {
                if (opened) viewModel.loadReview(keepCursor = true) else viewModel.loadReview(atRally = atRally)
            }
        }
        LaunchedEffect(shown.deck != null) { if (shown.deck != null) opened = true }
        // The scorer belongs to the side that won the rally, as answered on that rally's winner card.
        val winner = shown.deck?.cards?.firstOrNull { it.rallyId == shown.card?.rallyId && it.questionType == ReviewCard.RALLY_WINNER }?.answer
        val url = remember(videoId, state.settings.serverTarget) { viewModel.api().videoContentUrl(videoId) }
        ReviewScreen(
            match, shown,
            scorerRoster = roster(if (winner == ReviewCard.AWAY) match.awayTeamId else match.homeTeamId),
            onAnswer = viewModel::answerReview, onMove = viewModel::moveReview, onDetail = viewModel::setReviewDetail,
            onOpenReport = {
                // The report may already be below: going back to it shows the one report there is, and it reloads.
                if (!nav.popBackStack(Routes.report(match.localId), inclusive = false)) {
                    nav.navigate(Routes.report(match.localId)) { popUpTo(route) { inclusive = true }; launchSingleTop = true }
                }
            },
            onBack = { nav.popBackStack() },
            // The session goes with every request of the player, in a header (design 10.5).
            clip = { card -> clipPlayer(url, card.clipStartMs, card.clipEndMs, viewModel.playbackHeaders()) },
            onRetry = { viewModel.retryReview(match.localId, atRally.takeIf { !opened }) },
            onFixRally = { card -> go(Routes.rally(match.localId, card.rallyId)) },
            // The list may already be below, when this review was opened from it. Going back to it keeps the
            // way back short: review and list would otherwise pile up, two screens for every round.
            onOpenRallies = { if (!nav.popBackStack(Routes.rallies(match.localId), inclusive = false)) go(Routes.rallies(match.localId)) },
        )
    }

    /** The range editor on whatever [resolve] finds on the current rally list. One applied correction closes it. */
    @Composable
    fun EditorRoute(entry: NavBackStackEntry, match: MatchRecord?, route: String, title: Int, resolve: (RallyList) -> EditTarget?) {
        val videoId = match?.check?.videoId
        if (match == null || videoId == null) {
            LaunchedEffect(Unit) { leave(route) }
            return
        }
        // Closes this editor, once. An applied correction closes it, and so does a list that no longer holds what
        // it was opened on. After an added rally both happen, and the second one must not close the screen below.
        val done: () -> Unit = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() }
        val shown = rallyState(match)
        // Opened from the review, the list in memory is older than the answers the user just gave. It can also
        // turn old while this screen is open, when the answer to a winner decision arrives late.
        val missing = shown.rallies == null
        LaunchedEffect(shown.job?.id, missing) { if (shown.job != null && missing) viewModel.loadRallies(match.localId) }
        val list = shown.rallies
        val target = list?.let(resolve)
        when {
            list == null -> RallyEditorWaiting(stringResource(title), shown, onRetry = { viewModel.retryRallies(match.localId) }, onBack = { nav.popBackStack() })
            // The rally or the pause is no longer on the list, so there is nothing left to edit here.
            target == null -> LaunchedEffect(Unit) { done() }
            else -> {
                val url = remember(videoId, state.settings.serverTarget) { viewModel.api().videoContentUrl(videoId) }
                val rally = (target as? EditTarget.OfRally)?.rally
                val startMs = remember { rally?.startMs ?: target.windowStartMs }
                // Opened from a review card, the question is the screen below. Opened from the list, it takes the editor's place.
                val below = remember { nav.previousBackStackEntry?.destination?.route }
                val fromReview = below == Routes.REVIEW || below == Routes.REVIEW_AT
                RallyEditorScreen(
                    target, shown, editorVideo(url, startMs, viewModel.playbackHeaders()),
                    onMove = { start, end -> if (rally != null) viewModel.moveRally(rally.id, start, end) { done() } },
                    onSplit = { at -> if (rally != null) viewModel.splitRally(rally.id, at) { done() } },
                    onMerge = { if (rally != null) viewModel.mergeWithPrevious(rally.id) { done() } },
                    onAdd = { start, end -> viewModel.addRally(start, end) { done() } },
                    onBack = { nav.popBackStack() },
                    onChooseWinner = if (rally == null || fromReview) null else ({
                        nav.navigate(Routes.reviewAt(match.localId, rally.id)) { popUpTo(route) { inclusive = true }; launchSingleTop = true }
                    }),
                    onProblemSeen = viewModel::clearCorrectionProblem,
                )
            }
        }
    }

    /**
     * Screen 13 on the rule of a match that is on the server. The screen shows the rule the server
     * named with the rally list, sends the new one, and closes once the server applied it.
     */
    @Composable
    fun RulesRoute(entry: NavBackStackEntry, match: MatchRecord?) {
        if (match?.check?.videoId == null) {
            LaunchedEffect(Unit) { leave(Routes.RULES) }
            return
        }
        // Closes this screen, once, and never the screen below it.
        val done: () -> Unit = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() }
        val shown = rallyState(match)
        val missing = shown.rallies == null
        LaunchedEffect(shown.job?.id, missing) { if (shown.job != null && missing) viewModel.loadRallies(match.localId) }
        // The user's choice, not saved yet. A winner answer that lands now moves the list on, and screen 13
        // gives way to the waiting screen until the list is read again. The choice lives here, so it comes back.
        var chosen by rememberSaveable { mutableStateOf<IntArray?>(null) }
        val list = shown.rallies
        if (list == null) {
            RallyEditorWaiting(stringResource(R.string.rule_title), shown, onRetry = { viewModel.retryRallies(match.localId) }, onBack = { nav.popBackStack() })
        } else {
            MatchRulesScreen(
                list.rules, onSave = { rules -> viewModel.changeMatchRules(rules) { done() } },
                // A problem belongs to this screen. Cleared on the way out, it does not flash on the list below.
                onBack = { viewModel.clearCorrectionProblem(); nav.popBackStack() },
                onList = true, listConfirmed = list.confirmed, sending = shown.correcting, problem = shown.correctionProblem,
                onSelectionChanged = { rules ->
                    chosen = intArrayOf(rules.setsToWin, rules.setTarget, rules.decidingSetTarget)
                    viewModel.clearCorrectionProblem()
                },
                initial = chosen?.let { MatchRules(it[0], it[1], it[2]) } ?: list.rules,
            )
        }
    }

    NavigationSuiteScaffold(
        // Task screens have no bottom bar: one step, one action, and back pops one step.
        layoutType = if (tabs.any { it.route == route }) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()) else NavigationSuiteType.None,
        containerColor = scheme.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(navigationBarContainerColor = scheme.surface, navigationRailContainerColor = scheme.surface),
        navigationSuiteItems = {
            tabs.forEach { tab ->
                val selected = route == tab.route
                // The Teams tab carries the captain's new members, read on Home before the tab is ever opened (plan D2).
                val badge = if (tab.route == Routes.TEAMS) state.serverTeams.badge else 0
                item(
                    selected = selected, onClick = { goTab(tab.route) },
                    icon = {
                        BadgedBox(badge = { NewJoinsBadge(badge) }) {
                            Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                    colors = itemColors,
                )
            }
        },
    ) {
        // A resume while signed in reads the teams again (design 10.3: on start and on resume). Signed out it sends nothing.
        LifecycleResumeEffect(Unit) {
            viewModel.loadTeams()
            onPauseOrDispose {}
        }
        NavHost(nav, startDestination = start) {
            // The entry moves on once the welcome was seen, and the navigation follows it.
            composable(Routes.WELCOME) { WelcomeScreen(onStart = viewModel::markWelcomeSeen) }
            composable(Routes.START) {
                // The text follows the server's sign-up mode, which the screen reads when it opens (slice B4, design 10.1).
                LaunchedEffect(Unit) { viewModel.readSignupMode() }
                StartScreen(
                    onCreateAccount = { viewModel.clearAccountProblem(); go(Routes.REGISTER) },
                    onLogin = { viewModel.clearAccountProblem(); go(Routes.LOGIN) },
                    // A release build has no server address until the hosting slice: no form of this screen can work there.
                    serverConfigured = state.serverConfigured,
                    signup = state.account.signup,
                )
            }
            composable(Routes.LOGIN) {
                // A screen the user left stops the repeats of a busy server, and its outcome stays behind.
                StopAccountCallOnLeave(viewModel)
                LoginScreen(
                    state.account, onLogin = viewModel::login,
                    onCreateAccount = {
                        viewModel.clearAccountProblem()
                        // The register screen may be below, when the login was opened from it.
                        if (!nav.popBackStack(Routes.REGISTER, inclusive = false)) go(Routes.REGISTER)
                    },
                    onBack = if (nav.previousBackStackEntry != null) ({ viewModel.clearAccountProblem(); nav.popBackStack() }) else null,
                    serverConfigured = state.serverConfigured,
                    server = { ServerSection(state, viewModel::setServerTarget, viewModel::testServer) },
                    onDeletePhoneData = viewModel::deleteUnconfirmedAccountData,
                )
            }
            composable(Routes.REGISTER) {
                StopAccountCallOnLeave(viewModel)
                // The form's code field follows the server's sign-up mode, read when the screen opens (slice B4, design 10.1).
                // Its server choice switches the server, and the view model reads the mode of the new one.
                LaunchedEffect(Unit) { viewModel.readSignupMode() }
                RegisterScreen(
                    state.account, onRegister = { name, username, password, code -> viewModel.register(name, username, password, code) },
                    onHaveAccount = {
                        viewModel.clearAccountProblem()
                        if (!nav.popBackStack(Routes.LOGIN, inclusive = false)) nav.navigate(Routes.LOGIN) { popUpTo(Routes.REGISTER) { inclusive = true } }
                    },
                    onBack = { viewModel.clearAccountProblem(); nav.popBackStack() },
                    serverConfigured = state.serverConfigured,
                    server = { ServerSection(state, viewModel::setServerTarget, viewModel::testServer) },
                    signup = state.account.signup,
                )
            }
            composable(Routes.LEGACY) {
                val question = state.account.legacy
                if (question != null) LegacyScreen(question, state.saveFailed, state.account.legacyMoving, viewModel::answerLegacy)
            }
            composable(Routes.HOME) {
                // Every login and every start with a session ends here, so the badge is there before the Teams tab opens (plan D2).
                LaunchedEffect(Unit) { viewModel.loadTeams() }
                HomeScreen(
                    state, onNewMatch = { go(Routes.NEW_MATCH) }, onOpenMatch = ::open,
                    onOpenMatches = { goTab(Routes.MATCHES) }, onOpenTeams = { goTab(Routes.TEAMS) }, onOpenGuide = { go(Routes.GUIDE) },
                )
            }
            composable(Routes.MATCHES) { MatchesScreen(state, onNewMatch = { go(Routes.NEW_MATCH) }, onOpenMatch = ::open) }
            composable(Routes.TEAMS) {
                LaunchedEffect(Unit) { viewModel.loadTeams() }
                TeamsScreen(
                    state.serverTeams, onCreate = { viewModel.clearTeamProblem(); go(Routes.NEW_TEAM) }, onOpen = { go(Routes.team(it)) },
                    onRetry = viewModel::loadTeams,
                )
            }
            composable(Routes.NEW_TEAM) {
                TeamCreateScreen(
                    state.serverTeams.busy, state.serverTeams.problem,
                    onCreate = { draft ->
                        // Takım Kur ends on the team screen (plan U36), unless the user left the form meanwhile.
                        viewModel.createTeam(draft) { id ->
                            if (nav.currentDestination?.route == Routes.NEW_TEAM) nav.navigate(Routes.team(id)) { popUpTo(Routes.NEW_TEAM) { inclusive = true } }
                        }
                    },
                    onEdit = viewModel::clearTeamProblem, onBack = { viewModel.clearTeamProblem(); nav.popBackStack() },
                )
            }
            composable(Routes.TEAM) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val team = state.serverTeams.teamOf(id)
                // The new members the screen opened with: the mark the screen sends right after does not take the line away.
                var opened by rememberSaveable(id) { mutableStateOf<Int?>(null) }
                LaunchedEffect(id) { viewModel.openTeam(id) }
                LaunchedEffect(team != null) { if (opened == null && team != null) opened = team.newJoins ?: 0 }
                TeamScreen(
                    team, loading = team == null && state.serverTeams.loadProblem == null, problem = state.serverTeams.loadProblem,
                    onSeats = { viewModel.clearTeamProblem(); go(Routes.teamSeats(id)) }, onRetry = { viewModel.openTeam(id) },
                    onBack = { nav.popBackStack() }, recentJoins = opened ?: team?.newJoins,
                )
            }
            composable(Routes.TEAM_SEATS) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val team = state.serverTeams.teamOf(id)
                if (team == null || !team.captain) {
                    LaunchedEffect(Unit) { leave(Routes.TEAM_SEATS) }
                } else {
                    TeamSeatsScreen(
                        team, state.serverTeams.busy, state.serverTeams.problem,
                        onSave = { count, moves -> viewModel.saveSeats(id, count, moves) { leave(Routes.TEAM_SEATS) } },
                        onEdit = viewModel::clearTeamProblem, onBack = { viewModel.clearTeamProblem(); nav.popBackStack() },
                    )
                }
            }
            composable(Routes.PROFILE) {
                val context = LocalContext.current
                // The profile shows the account as the server holds it now: a name changed on another phone, or a
                // session that ended there. A busy server's repeats stop when the tab is left.
                LaunchedEffect(Unit) { viewModel.refreshAccount() }
                StopAccountCallOnLeave(viewModel)
                ProfileScreen(
                    state, BuildConfig.VERSION_NAME, viewModel::setServerTarget, viewModel::testServer,
                    viewModel::deleteRecording,
                    onSendFeedback = {
                        try {
                            context.startActivity(feedbackIntent(BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT))
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    },
                    account = ProfileAccountActions(
                        onChangeDisplayName = viewModel::changeDisplayName,
                        onOpenPasswordChange = { viewModel.clearAccountProblem(); go(Routes.PASSWORD) },
                        onLogout = viewModel::logout,
                        onOpenDeletion = { viewModel.clearAccountProblem(); go(Routes.DELETE_ACCOUNT) },
                        onClearProblem = viewModel::clearAccountProblem,
                    ),
                )
            }
            composable(Routes.PASSWORD) {
                StopAccountCallOnLeave(viewModel)
                val activity = LocalActivity.current
                // A confirmed change closes the screen, and the profile says what the server did.
                LaunchedEffect(state.account.confirmed) { if (state.account.confirmed == AccountChange.PASSWORD) leave(Routes.PASSWORD) }
                PasswordChangeScreen(
                    state.account, viewModel::changePassword, onBack = { viewModel.clearAccountProblem(); nav.popBackStack() },
                    onNewPasswordNotHandedOver = viewModel::newPasswordNotHandedOver,
                    // A screen Android rebuilds comes back and speaks for itself at the confirmation (R2-D). A screen the user
                    // left does not, so the view model names the change when it is confirmed (third review, B1).
                    onGoneWithoutHandingOver = { reason -> if (activity?.isChangingConfigurations != true) viewModel.newPasswordNotHandedOver(reason) },
                )
            }
            composable(Routes.DELETE_ACCOUNT) {
                // After the deletion the app opens the login, which says what the server deleted.
                StopAccountCallOnLeave(viewModel)
                DeleteAccountScreen(
                    state.account, holdsUploadedMatches = state.matches.any { it.session.serverMatchId != null },
                    onDelete = viewModel::deleteAccount, onBack = { viewModel.clearAccountProblem(); nav.popBackStack() },
                )
            }
            composable(Routes.ROSTER) { entry ->
                val team = state.teams.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (team == null) {
                    LaunchedEffect(Unit) { leave(Routes.ROSTER) }
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
                MatchCreateScreen(
                    state.draft, LocalDate.now().toString(), viewModel::editDraft, onContinue = { go(Routes.NEW_TEAMS) }, onBack = { nav.popBackStack() },
                    onOpenRules = { go(Routes.NEW_RULES) },
                )
            }
            composable(Routes.NEW_RULES) {
                // The match is not on the server yet: the rule travels with the request that creates it.
                MatchRulesScreen(state.draft.rules, onSave = { rules -> viewModel.editDraft { it.copy(rules = rules) }; nav.popBackStack() }, onBack = { nav.popBackStack() })
            }
            composable(Routes.NEW_TEAMS) {
                MatchTeamsScreen(
                    state.draft, state.teams, onChoose = { home, team -> viewModel.chooseTeam(home, team) }, onAddTeam = viewModel::addTeam,
                    onOpenRoster = { go(Routes.roster(it.id)) }, onContinue = { go(Routes.NEW_VIDEO) }, onBack = { nav.popBackStack() },
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
                        rules = state.draft.rules, onOpenRules = { go(Routes.NEW_RULES) },
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
                        // Sends nothing to the server. Once the record is gone this route leaves for Home.
                        onRemoveFromPhone = { viewModel.removeFromPhone(match.localId) },
                    )
                }
            }
            composable(Routes.REVIEW) { entry -> ReviewRoute(match(entry.arguments?.getString("id")), Routes.REVIEW, atRally = null) }
            composable(Routes.REVIEW_AT) { entry ->
                ReviewRoute(match(entry.arguments?.getString("id")), Routes.REVIEW_AT, atRally = entry.arguments?.getString("rally"))
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
                        onBack = { nav.popBackStack() }, onOpenRallies = { go(Routes.rallies(match.localId)) },
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
            composable(Routes.RALLIES) { entry ->
                val match = match(entry.arguments?.getString("id"))
                if (match?.check?.videoId == null) {
                    LaunchedEffect(Unit) { leave(Routes.RALLIES) }
                } else {
                    val shown = rallyState(match)
                    // Read again whenever the screen comes back: a decision made in review changes the scores of the
                    // list. Read once more when the list in memory turns out to be old: the answer to a winner
                    // decision can arrive after this screen opened, and nothing else would fetch the list it moved on.
                    var read by remember { mutableStateOf(false) }
                    val missing = shown.rallies == null
                    LaunchedEffect(shown.job?.id, missing) {
                        if (shown.job != null && (!read || missing)) {
                            read = true
                            viewModel.loadRallies(match.localId)
                        }
                    }
                    RalliesScreen(
                        match, shown,
                        onOpenRally = { go(Routes.rally(match.localId, it.id)) }, onOpenGap = { go(Routes.gap(match.localId, it)) },
                        onConfirm = { viewModel.confirmRallyList {} },
                        // A new screen on top, so that back leads to this list again.
                        onOpenReview = { go(Routes.reviewAt(match.localId, it.id)) },
                        onRetry = { viewModel.retryRallies(match.localId) }, onBack = { nav.popBackStack() },
                        onChangeRules = { go(Routes.rules(match.localId)) },
                    )
                }
            }
            composable(Routes.RULES) { entry -> RulesRoute(entry, match(entry.arguments?.getString("id"))) }
            composable(Routes.RALLY) { entry ->
                val rallyId = entry.arguments?.getString("rally")
                EditorRoute(entry, match(entry.arguments?.getString("id")), Routes.RALLY, R.string.editor_title_rally) { list -> rallyId?.let { rallyTarget(list, it) } }
            }
            composable(Routes.GAP) { entry ->
                val atMs = entry.arguments?.getString("at")?.toLongOrNull()
                EditorRoute(entry, match(entry.arguments?.getString("id")), Routes.GAP, R.string.editor_title_gap) { list -> atMs?.let { gapTarget(list, it) } }
            }
        }
    }
}

/**
 * Stops the account call of a screen the user leaves: a busy server's repeats end there (design
 * 10.7, R10). A screen the system recreates for a dark theme, a language or a font size is not
 * left. Its call goes on, and the answer reaches the screen that comes back (review finding F8).
 */
@Composable
private fun StopAccountCallOnLeave(viewModel: AppViewModel) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.cancelAccountCall() } }
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
