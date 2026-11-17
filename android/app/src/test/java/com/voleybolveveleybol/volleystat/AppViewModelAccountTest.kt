package com.voleybolveveleybol.volleystat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.DeviceTokenStore
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.SessionStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.VideoSource
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The view model's accounts (slice B2): the session header, the end of a session, one folder per
 * account, the device token, the old single-account data, and every account call against a
 * scripted stand-in server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelAccountTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val files get() = context.filesDir
    private val server = MockWebServer()

    /** Every request: "METHOD path", its Authorization header and its body. */
    private class Seen(val key: String, val authorization: String?, val body: String)
    private val seen = CopyOnWriteArrayList<Seen>()
    private val script = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    /** The accounts the stand-in server knows, by username: (user id, display name). */
    private val accounts = mapOf("ali" to ("user-ali" to "Ali Yılmaz"), "veli" to ("user-veli" to "Veli Kaya"))

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                seen += Seen(key, request.getHeader("Authorization"), request.body.readUtf8())
                return script[key]?.invoke(request) ?: refuse(404, "NOT_FOUND")
            }
        }
        server.start()
        on("POST /auth/login") { request -> signedIn(JSONObject(seen.last().body).getString("username"), request) }
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }
        on("GET /health") { ok(JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", 100)) }
    }

    @After fun stop() = server.shutdown()

    private fun on(request: String, answer: (RecordedRequest) -> MockResponse) { script[request] = answer }
    private fun ok(json: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json.toString())
    /** The API's error envelope. Every 401 of the API names the Bearer scheme, as the server sends it. */
    private fun refuse(status: Int, code: String, details: JSONObject = JSONObject()) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", details)).toString())
        .apply { if (status == 401) setHeader("WWW-Authenticate", "Bearer") }

    private fun userJson(username: String) = accounts.getValue(username).let { (id, name) ->
        JSONObject().put("id", id).put("username", username).put("displayName", name).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)
    }

    /** The server's answer to a login or registration of [username]: a new session, and a device token for a phone that sent none. */
    @Suppress("UNUSED_PARAMETER")
    private fun signedIn(username: String, request: RecordedRequest? = null, status: Int = 200): MockResponse {
        val sentDevice = JSONObject(seen.last().body).optString("deviceToken").ifEmpty { null }
        return ok(
            JSONObject().put("user", userJson(username))
                .put("session", JSONObject().put("token", "token-$username-${seen.size}").put("expiresAt", "2026-12-17T10:00:00Z"))
                .put("deviceToken", sentDevice ?: "device-$username"),
            status,
        )
    }

    private fun viewModel() = AppViewModel(context).also { it.baseUrlOverride = server.url("/v1").toString() }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun AppViewModel.loginAndWait(username: String, password: String = "uzun bir şifre 42") {
        login(username, password)
        await("the login of $username") { !state.value.account.busy }
        assertEquals(username, state.value.account.user?.username)
    }

    private fun AppViewModel.logoutAndWait() {
        logout()
        await("the logout") { state.value.account.user == null && !state.value.account.busy }
    }

    private fun sessionFile() = File(files, "session.bin")
    private fun storedSession() = SessionStore(sessionFile(), PlainSessionCipher).load()
    private fun deviceTokens() = DeviceTokenStore(File(files, "device.bin"), PlainSessionCipher)
    private fun video(path: String) = LocalVideo(path, VideoSource.CAMERA, File(path).name, 10, "video/mp4", null, null, null, null)

    // ----- where the app starts -----

    @Test fun theStartFollowsTheWelcomeTheAccountsSeenAndTheSession() {
        assertEquals(Entry.WELCOME, AppUiState().entry)
        assertEquals(Entry.START, AppUiState(settings = Settings(welcomeSeen = true)).entry)
        assertEquals(Entry.LOGIN, AppUiState(settings = Settings(welcomeSeen = true, accountSeen = true)).entry)
        val user = AccountUi(user = com.voleybolveveleybol.volleystat.data.AccountUser("u", "ali", "Ali", false))
        assertEquals(Entry.HOME, AppUiState(settings = Settings(welcomeSeen = true, accountSeen = true), account = user).entry)
        val asked = user.copy(legacy = LegacyQuestion(listOf("Final"), teams = 0, recordings = 0, draft = false))
        assertEquals(Entry.LEGACY, AppUiState(settings = Settings(welcomeSeen = true, accountSeen = true), account = asked).entry)
        // The welcome comes first even for a session that survived from somewhere.
        assertEquals(Entry.WELCOME, AppUiState(account = user).entry)
    }

    @Test fun aSignedInPhoneStartsOnHomeWithItsAccountsData() {
        TestAccounts.seed(context, StoredState(teams = listOf(Team("t1", "A Takımı"))))
        val vm = viewModel()
        assertEquals(Entry.HOME, vm.state.value.entry)
        assertEquals(TestAccounts.SESSION.username, vm.state.value.account.user!!.username)
        assertEquals(listOf("A Takımı"), vm.state.value.teams.map { it.name })
    }

    @Test fun aDamagedSessionFileStartsOnTheLoginScreen() {
        TestAccounts.seed(context, StoredState(teams = listOf(Team("t1", "A Takımı"))))
        sessionFile().writeText("not a session")
        val vm = viewModel()
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertTrue(vm.state.value.teams.isEmpty())
    }

    // ----- the session header -----

    @Test fun theHeaderGoesOutOnlyAfterTheServerConfirmedTheLogin() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val release = CountDownLatch(1)
        on("POST /auth/login") { release.await(8, TimeUnit.SECONDS); signedIn("ali") }
        val vm = viewModel()
        vm.testServer()
        await("the health answer") { vm.state.value.serverTest != ServerTest.TESTING }
        assertNull(seen.single { it.key == "GET /health" }.authorization)
        assertTrue(vm.playbackHeaders().isEmpty())

        vm.login(" Ali ", "uzun bir şifre 42")
        await("the login request") { seen.any { it.key == "POST /auth/login" } }
        // The answer is not there yet: nobody is signed in, and nothing says so.
        assertTrue(vm.state.value.account.busy)
        assertNull(vm.state.value.account.user)
        assertFalse(sessionFile().exists())
        release.countDown()
        await("the login") { vm.state.value.account.user != null }

        val login = JSONObject(seen.single { it.key == "POST /auth/login" }.body)
        assertEquals("the app sends the username in lowercase", "ali", login.getString("username"))
        assertNull(seen.single { it.key == "POST /auth/login" }.authorization)
        val token = storedSession()!!.token
        vm.testServer()
        await("the second health answer") { seen.count { it.key == "GET /health" } == 2 }
        assertEquals("Bearer $token", seen.last { it.key == "GET /health" }.authorization)
        assertEquals(mapOf("Authorization" to "Bearer $token"), vm.playbackHeaders())
        assertEquals(Entry.HOME, vm.state.value.entry)
        assertTrue(vm.state.value.settings.accountSeen)
    }

    @Test fun aFailedLoginKeepsTheUserOnTheFormAndWritesNothing() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        on("POST /auth/login") { refuse(401, "LOGIN_FAILED") }
        val vm = viewModel()
        vm.login("ali", "yanlış şifre 1234")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.LoginFailed, vm.state.value.account.problem)
        assertNull(vm.state.value.account.user)
        assertFalse(vm.state.value.account.sessionEnded)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertFalse(sessionFile().exists())
        vm.clearAccountProblem()
        assertNull(vm.state.value.account.problem)
    }

    @Test fun aLockedLoginNamesItsScopeAndItsWait() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        on("POST /auth/login") {
            refuse(429, "TOO_MANY_ATTEMPTS", JSONObject().put("retryAfterSeconds", 120).put("scope", "DEVICE")).setHeader("Retry-After", "120")
        }
        val vm = viewModel()
        vm.login("ali", "yanlış şifre 1234")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.TooManyAttempts(AttemptScope.DEVICE, 120), vm.state.value.account.problem)
    }

    @Test fun anOlderServerWithoutAccountsIsNamed() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        script.remove("POST /auth/login")
        val vm = viewModel()
        vm.login("ali", "uzun bir şifre 42")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.ServerOutdated, vm.state.value.account.problem)
    }

    @Test fun aBusyServerIsAskedAgainAfterTheWaitItNamed() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var calls = 0
        val times = CopyOnWriteArrayList<Long>()
        on("POST /auth/login") {
            times += System.currentTimeMillis()
            if (++calls == 1) refuse(503, "SERVER_BUSY").setHeader("Retry-After", "1") else signedIn("ali")
        }
        val vm = viewModel()
        vm.login("ali", "uzun bir şifre 42")
        await("the login") { vm.state.value.account.user != null }
        assertEquals(2, calls)
        assertTrue("waited ${times[1] - times[0]} ms", times[1] - times[0] >= 900)
        assertNull(vm.state.value.account.problem)
    }

    /**
     * Review finding T5. Between a SERVER_BUSY answer and the next try the app has the server's answer
     * and waits out its Retry-After, so the state says so; the next try waits for an answer again.
     */
    @Test fun whileTheAppWaitsOutABusyServerTheStateSaysSoUntilTheNextTryGoesOut() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var calls = 0
        val second = CountDownLatch(1)
        on("POST /auth/login") { request ->
            if (++calls == 1) refuse(503, "SERVER_BUSY").setHeader("Retry-After", "2")
            else { second.await(10, TimeUnit.SECONDS); signedIn("ali", request) }
        }
        val vm = viewModel()
        assertFalse(vm.state.value.account.retryingAfterBusy)
        vm.login("ali", "uzun bir şifre 42")
        await("the wait after the busy answer") { vm.state.value.account.retryingAfterBusy }
        assertEquals(1, calls)
        assertTrue(vm.state.value.account.busy)
        assertNull(vm.state.value.account.problem)
        await("the second try") { calls == 2 }
        settle()
        assertFalse("the second try waits for its answer", vm.state.value.account.retryingAfterBusy)
        assertTrue(vm.state.value.account.busy)
        second.countDown()
        await("the login") { vm.state.value.account.user != null }
        assertFalse(vm.state.value.account.retryingAfterBusy)
    }

    /** T5: a session that ends while an account call waits out SERVER_BUSY does not change what the waiting form says. */
    @Test fun aSessionThatEndsWhileACallWaitsOutABusyServerLeavesTheWaitAsItIs() {
        TestAccounts.seed(context, StoredState(matches = listOf(accepted("A"))))
        var names = 0
        on("PUT /me/display-name") { if (++names == 1) refuse(503, "SERVER_BUSY").setHeader("Retry-After", "2") else refuse(401, "AUTH_REQUIRED") }
        on("GET /videos/video-A/analyses/latest") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.changeDisplayName("Yeni Ad")
        await("the wait after the busy answer") { vm.state.value.account.retryingAfterBusy }
        // Another call meets the end of the session during the wait.
        vm.watchAnalysis("A", startIfMissing = false)
        await("the end of the session") { vm.state.value.account.user == null }
        assertTrue(vm.state.value.account.busy)
        assertTrue("the call still waits out the busy answer", vm.state.value.account.retryingAfterBusy)
        await("the end of the call") { !vm.state.value.account.busy }
        assertFalse(vm.state.value.account.retryingAfterBusy)
    }

    @Test fun aBusyServerThatStaysBusyIsReportedAfterAFewTries() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var calls = 0
        on("POST /auth/login") { calls++; refuse(503, "SERVER_BUSY").setHeader("Retry-After", "0") }
        val vm = viewModel()
        vm.login("ali", "uzun bir şifre 42")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.ServerBusy(0), vm.state.value.account.problem)
        assertEquals(AppViewModel.BUSY_TRIES, calls)
        // The last answer ended the call: no next try is coming (T5).
        assertFalse(vm.state.value.account.retryingAfterBusy)
    }

    @Test fun leavingTheScreenStopsTheRetriesOfABusyServer() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var calls = 0
        on("POST /auth/login") { calls++; refuse(503, "SERVER_BUSY").setHeader("Retry-After", "1") }
        val vm = viewModel()
        vm.login("ali", "uzun bir şifre 42")
        await("the first try") { calls == 1 }
        await("the wait after the busy answer") { vm.state.value.account.retryingAfterBusy }
        vm.cancelAccountCall()
        await("the end of the call") { !vm.state.value.account.busy }
        // The wait ended with the call, and the form says nothing more about a next try (T5).
        assertFalse(vm.state.value.account.retryingAfterBusy)
        Thread.sleep(1_300)
        settle()
        assertEquals(1, calls)
        assertNull(vm.state.value.account.user)
    }

    // ----- the end of a session -----

    @Test fun anEndedSessionClearsTheSessionAndLeadsToTheLoginWithTheReason() {
        TestAccounts.seed(context, StoredState(matches = listOf(accepted("A")), teams = listOf(Team("t1", "A Takımı"))))
        on("GET /videos/video-A/analyses/latest") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.watchAnalysis("A", startIfMissing = false)
        await("the end of the session") { vm.state.value.account.user == null }
        assertEquals("Bearer ${TestAccounts.SESSION.token}", seen.first().authorization)
        assertTrue(vm.state.value.account.sessionEnded)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertFalse(sessionFile().exists())
        // Nothing of the account stays on screen, and nothing of it is lost on the phone.
        assertTrue(vm.state.value.matches.isEmpty())
        assertTrue(vm.state.value.teams.isEmpty())
        assertNull(vm.analysis.value.matchId)
        assertEquals(listOf("A Takımı"), TestAccounts.space(context).store.load().teams.map { it.name })
        assertTrue(vm.playbackHeaders().isEmpty())

        // The same account logs in again and finds its data.
        on("POST /auth/login") {
            ok(JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
                .put("displayName", TestAccounts.SESSION.displayName).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false))
                .put("session", JSONObject().put("token", "fresh-token").put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-1"))
        }
        vm.login(TestAccounts.SESSION.username, "uzun bir şifre 42")
        await("the login") { vm.state.value.account.user != null }
        assertFalse(vm.state.value.account.sessionEnded)
        assertEquals(listOf("A"), vm.state.value.matches.map { it.localId })
    }

    @Test fun anUploadThatMeetsAnEndedSessionStopsAndResumesFromTheServersOffsetAfterTheLogin() {
        val recording = File(TestAccounts.space(context).recordings.apply { mkdirs() }, "kayit-1.mp4").apply { writeBytes(ByteArray(10) { 1 }) }
        val unfinished = MatchRecord(
            "U", "Ev", "Konuk", 1L, video(recording.path),
            session = com.voleybolveveleybol.volleystat.model.UploadSession("sha", "server-U", "upload-U", 4),
        )
        TestAccounts.seed(context, StoredState(matches = listOf(unfinished)))
        var ended = true
        on("GET /uploads/upload-U") {
            if (ended) refuse(401, "AUTH_REQUIRED") else ok(JSONObject().put("uploadId", "upload-U").put("nextOffset", 4).put("status", "UPLOADING").put("videoId", JSONObject.NULL))
        }
        val vm = viewModel()
        vm.resumeUpload("U")
        await("the end of the session") { vm.state.value.account.user == null }
        // The upload's own coroutine settles first: what it does on its way out must not bring the upload back.
        Thread.sleep(300)
        settle()
        assertTrue(vm.state.value.account.sessionEnded)
        assertNull("no upload of the account that left stays on screen", vm.state.value.upload)
        // The record keeps the server's upload and what it holds, so nothing starts again from byte 0.
        assertEquals(unfinished.session, TestAccounts.space(context).store.load().matches.single().session)

        ended = false
        on("PUT /uploads/upload-U/chunks") { ok(JSONObject().put("nextOffset", 10)) }
        on("POST /uploads/upload-U/complete") {
            ok(JSONObject().put("id", "video-U").put("status", "READY").put("quality", JSONObject().put("rejectCode", JSONObject.NULL).put("warnings", org.json.JSONArray())))
        }
        on("POST /auth/login") {
            ok(JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
                .put("displayName", TestAccounts.SESSION.displayName).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false))
                .put("session", JSONObject().put("token", "fresh-token").put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-1"))
        }
        vm.login(TestAccounts.SESSION.username, "uzun bir şifre 42")
        await("the login") { vm.state.value.account.user != null }
        vm.resumeUpload("U")
        await("the finished upload") { vm.state.value.matches.single().check != null }
        val chunk = seen.single { it.key == "PUT /uploads/upload-U/chunks" }
        assertEquals("Bearer fresh-token", chunk.authorization)
        assertEquals("the upload went on from the server's offset", 6, chunk.body.length)
    }

    @Test fun anAnswerAboutAnEarlierSessionEndsNoLaterOne() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.loginAndWait("ali")
        val ofAli = vm.api()
        vm.logoutAndWait()
        vm.loginAndWait("veli")
        // A call of the earlier session comes back with 401 after the next login.
        runCatching { ofAli.me() }
        settle()
        assertEquals("veli", vm.state.value.account.user?.username)
        assertFalse(vm.state.value.account.sessionEnded)
        assertNotNull(storedSession())
    }

    @Test fun aRefreshThatFindsTheSessionEndedEndsIt() {
        TestAccounts.seed(context)
        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.refreshAccount()
        await("the end of the session") { vm.state.value.account.user == null }
        assertTrue(vm.state.value.account.sessionEnded)
    }

    @Test fun aRefreshTakesTheDisplayNameTheServerHolds() {
        TestAccounts.seed(context)
        on("GET /me") {
            ok(JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
                .put("displayName", "Yeni Ad").put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", true)).put("teams", org.json.JSONArray()))
        }
        val vm = viewModel()
        vm.refreshAccount()
        await("the new name") { vm.state.value.account.user?.displayName == "Yeni Ad" }
        assertTrue(vm.state.value.account.user!!.passwordTemporary)
        assertEquals("Yeni Ad", storedSession()!!.displayName)
    }

    // ----- one folder per account -----

    @Test fun accountBSeesNoneOfTheDataOfAccountA() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        vm.addTeam("Ali'nin Takımı")
        vm.editDraft { it.copy(name = "Ali'nin taslağı") }
        val recording = vm.prepareCapture().apply { writeBytes(ByteArray(10)) }
        vm.onCaptureFinished {}
        await("the recording") { vm.state.value.recordings.isNotEmpty() }
        assertTrue(recording.path.startsWith(File(files, "accounts/user-ali").path))
        vm.logoutAndWait()
        assertTrue(vm.state.value.teams.isEmpty())
        assertTrue(vm.state.value.recordings.isEmpty())
        assertFalse(vm.state.value.draft.started)

        vm.loginAndWait("veli")
        assertTrue(vm.state.value.teams.isEmpty())
        assertTrue(vm.state.value.matches.isEmpty())
        assertFalse(vm.state.value.draft.started)
        assertTrue(vm.state.value.recordings.isEmpty())
        // A recording of A can not be deleted from B's profile.
        vm.deleteRecording(recording.path)
        assertTrue(recording.exists())
        vm.addTeam("Veli'nin Takımı")
        vm.logoutAndWait()

        vm.loginAndWait("ali")
        assertEquals(listOf("Ali'nin Takımı"), vm.state.value.teams.map { it.name })
        assertEquals("Ali'nin taslağı", vm.state.value.draft.name)
        assertEquals(listOf(recording.path), vm.state.value.recordings.map { it.path })
        // A fresh view model, as after a restart, reads A's folder only.
        val restarted = viewModel()
        assertEquals(listOf("Ali'nin Takımı"), restarted.state.value.teams.map { it.name })
    }

    @Test fun logoutKeepsTheDeviceTokenAndTheNextLoginOfThatAccountSendsIt() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        assertEquals("device-ali", deviceTokens().tokenFor("ali"))
        vm.logoutAndWait()
        assertEquals("Bearer ${"token-ali-1"}", seen.single { it.key == "POST /auth/logout" }.authorization)
        assertFalse(sessionFile().exists())
        assertEquals("device-ali", deviceTokens().tokenFor("ali"))
        vm.loginAndWait("ali")
        assertEquals("device-ali", JSONObject(seen.last { it.key == "POST /auth/login" }.body).getString("deviceToken"))
        vm.logoutAndWait()
        // Another username has no token on this phone.
        vm.loginAndWait("veli")
        assertFalse(JSONObject(seen.last { it.key == "POST /auth/login" }.body).has("deviceToken"))
    }

    @Test fun aLogoutTheServerDidNotConfirmKeepsTheSessionUnlessTheUserLetsItGo() {
        TestAccounts.seed(context)
        on("POST /auth/logout") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val vm = viewModel()
        vm.logout()
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.Unreachable, vm.state.value.account.problem)
        assertNotNull(vm.state.value.account.user)
        assertTrue(sessionFile().exists())
        vm.logout(evenWithoutAnswer = true)
        await("the logout") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertFalse(sessionFile().exists())
        assertFalse(vm.state.value.account.sessionEnded)
    }

    @Test fun aLogoutOfASessionTheServerAlreadyEndedIsALogout() {
        TestAccounts.seed(context)
        on("POST /auth/logout") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.logoutAndWait()
        assertFalse("the user asked for this, so no session_ended text", vm.state.value.account.sessionEnded)
        assertNull(vm.state.value.account.problem)
    }

    // ----- registration -----

    @Test fun registrationOpensTheAccountWithTheTextsAsTheServerWantsThem() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        on("POST /auth/register") { signedIn("ali", status = 201) }
        val vm = viewModel()
        vm.register("  Ali Yılmaz ", " ALI ", "uzun bir şifre 42", operatorCode = "  ")
        await("the account") { vm.state.value.account.user != null }
        val body = JSONObject(seen.single().body)
        assertEquals("ali", body.getString("username"))
        assertEquals("Ali Yılmaz", body.getString("displayName"))
        assertFalse("a blank code is no code", body.has("operatorCode"))
        assertEquals(Entry.HOME, vm.state.value.entry)
        assertTrue(vm.state.value.settings.accountSeen)
        assertEquals("device-ali", deviceTokens().tokenFor("ali"))
        assertEquals("user-ali", storedSession()!!.userId)
    }

    @Test fun anOperatorCodeTravelsWithTheRegistration() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        on("POST /auth/register") { refuse(403, "SIGNUP_CODE_INVALID") }
        val vm = viewModel()
        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", operatorCode = " abcd-efgh-jkmn-pqrs ")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals("abcd-efgh-jkmn-pqrs", JSONObject(seen.single().body).getString("operatorCode"))
        assertEquals(AuthProblem.SignupCodeInvalid, vm.state.value.account.problem)
        assertNull(vm.state.value.account.user)
    }

    @Test fun aTakenUsernameIsReportedWithoutALoginAttempt() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        on("POST /auth/register") { refuse(409, "USERNAME_TAKEN") }
        val vm = viewModel()
        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", null)
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.UsernameTaken, vm.state.value.account.problem)
        assertEquals(listOf("POST /auth/register"), seen.map { it.key })
    }

    @Test fun aRegistrationWhoseAnswerWasLostIsFoundWhenTheUserTriesAgain() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var registrations = 0
        // The server opens the account and the answer never reaches the phone. The repeat meets the account it opened.
        on("POST /auth/register") { if (++registrations == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else refuse(409, "USERNAME_TAKEN") }
        val vm = viewModel()
        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", null)
        await("the first answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.Unreachable, vm.state.value.account.problem)
        assertNull(vm.state.value.account.user)

        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", null)
        await("the account") { vm.state.value.account.user != null }
        assertEquals(listOf("POST /auth/register", "POST /auth/register", "POST /auth/login"), seen.map { it.key })
        assertNull(vm.state.value.account.problem)
    }

    @Test fun aLostRegistrationWithACodeIsFoundTooBecauseTheSpentCodeAnswersFirst() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        var registrations = 0
        on("POST /auth/register") { if (++registrations == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else refuse(403, "SIGNUP_CODE_INVALID") }
        on("POST /auth/login") { refuse(401, "LOGIN_FAILED") }
        val vm = viewModel()
        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", "ABCD-EFGH-JKMN-PQRS")
        await("the first answer") { !vm.state.value.account.busy }
        vm.register("Ali Yılmaz", "ali", "uzun bir şifre 42", "ABCD-EFGH-JKMN-PQRS")
        await("the second answer") { !vm.state.value.account.busy && seen.size == 3 }
        // The login found no such account, so the code was really not valid.
        assertEquals(AuthProblem.SignupCodeInvalid, vm.state.value.account.problem)
        assertNull(vm.state.value.account.user)
    }

    // ----- the data of app 0.7 -----

    private fun writeLegacy(): File {
        val recordings = File(files, "recordings").apply { mkdirs() }
        val recording = File(recordings, "kayit-1.mp4").apply { writeBytes(ByteArray(10) { 1 }) }
        LocalStore(File(files, "volleystat.json")).save(
            StoredState(
                matches = listOf(MatchRecord("old", "Eski Ev", "Eski Konuk", 1L, video(recording.path), name = "Eski Final")),
                teams = listOf(Team("t-old", "Eski Takım")),
            )
        )
        return recording
    }

    @Test fun theFirstLoginAsksOnceAboutTheOldDataAndMovesItIntoTheAccount() {
        val recording = writeLegacy()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        val question = vm.state.value.account.legacy!!
        assertEquals(listOf("Eski Final"), question.titles)
        assertEquals(1, question.teams)
        assertEquals(1, question.recordings)
        assertEquals(Entry.LEGACY, vm.state.value.entry)
        assertTrue("nothing moves before the answer", vm.state.value.matches.isEmpty())
        // A restart before the answer asks again: the answer is what the phone remembers, not the question.
        val restarted = viewModel()
        assertEquals(question, restarted.state.value.account.legacy)
        assertEquals(Entry.LEGACY, restarted.state.value.entry)

        vm.answerLegacy(move = true)
        await("the move") { vm.state.value.account.legacy == null }
        assertEquals(listOf("old"), vm.state.value.matches.map { it.localId })
        assertEquals(listOf("Eski Takım"), vm.state.value.teams.map { it.name })
        assertTrue(vm.state.value.settings.legacyHandled)
        assertEquals(Entry.HOME, vm.state.value.entry)
        val moved = vm.state.value.matches.single().video.location
        assertTrue(moved.startsWith(File(files, "accounts/user-ali/recordings").path))
        assertFalse(recording.exists())
        assertEquals(listOf(moved), vm.state.value.recordings.map { it.path })

        // The answer is remembered: no account is asked again, and B gets none of it.
        vm.logoutAndWait()
        vm.loginAndWait("veli")
        assertNull(vm.state.value.account.legacy)
        assertTrue(vm.state.value.matches.isEmpty())
    }

    @Test fun keepingTheOldDataLeavesItWhereItIsAndAsksNoMore() {
        val recording = writeLegacy()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        assertNotNull(vm.state.value.account.legacy)
        vm.answerLegacy(move = false)
        await("the answer") { vm.state.value.account.legacy == null }
        assertTrue(vm.state.value.matches.isEmpty())
        assertTrue(vm.state.value.settings.legacyHandled)
        assertTrue(recording.exists())
        assertTrue(File(files, "volleystat.json").exists())

        vm.logoutAndWait()
        vm.loginAndWait("ali")
        assertNull(vm.state.value.account.legacy)
        // A restart remembers the answer too.
        assertTrue(viewModel().state.value.settings.legacyHandled)
    }

    @Test fun aPhoneWithoutOldDataAsksNothing() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        assertNull(vm.state.value.account.legacy)
        assertTrue(vm.state.value.settings.legacyHandled)
        assertEquals(Entry.HOME, vm.state.value.entry)
    }

    @Test fun anUpdateFromApp07KeepsTheWelcomeSeen() {
        writeLegacy()
        File(files, "volleystat.json").let { file ->
            val json = JSONObject(file.readText())
            json.put("version", 2).put("settings", JSONObject().put("welcomeSeen", true).put("serverTarget", "USB_DEVICE").put("displayName", "Eski Ad"))
            file.writeText(json.toString())
        }
        val vm = viewModel()
        assertEquals(Entry.START, vm.state.value.entry)
        assertEquals(com.voleybolveveleybol.volleystat.model.ServerTarget.USB_DEVICE, vm.state.value.settings.serverTarget)
        assertTrue("the app reads no account's data before a login", vm.state.value.matches.isEmpty())
    }

    // ----- the account itself -----

    @Test fun aNewDisplayNameIsShownOnlyAfterTheServerKeptIt() {
        TestAccounts.seed(context)
        val release = CountDownLatch(1)
        on("PUT /me/display-name") {
            release.await(8, TimeUnit.SECONDS)
            ok(JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
                .put("displayName", "Yeni Ad").put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)))
        }
        val vm = viewModel()
        vm.changeDisplayName("  Yeni Ad ")
        await("the request") { seen.isNotEmpty() }
        assertEquals("Yeni Ad", JSONObject(seen.single().body).getString("displayName"))
        assertEquals(TestAccounts.SESSION.displayName, vm.state.value.account.user!!.displayName)
        release.countDown()
        await("the new name") { vm.state.value.account.user!!.displayName == "Yeni Ad" }
        assertEquals("Yeni Ad", storedSession()!!.displayName)
        assertEquals(AccountChange.DISPLAY_NAME, vm.state.value.account.confirmed)
        vm.clearAccountProblem()
        assertNull(vm.state.value.account.confirmed)
    }

    @Test fun aRefusedDisplayNameKeepsTheOldOne() {
        TestAccounts.seed(context)
        on("PUT /me/display-name") { refuse(422, "DISPLAY_NAME_INVALID") }
        val vm = viewModel()
        vm.changeDisplayName("x")
        await("the answer") { !vm.state.value.account.busy }
        assertEquals(AuthProblem.DisplayNameInvalid, vm.state.value.account.problem)
        assertEquals(TestAccounts.SESSION.displayName, vm.state.value.account.user!!.displayName)
    }

    @Test fun aNewPasswordKeepsTheSessionAndDropsTheDeviceTokenTheServerForgot() {
        TestAccounts.seed(context)
        deviceTokens().remember(TestAccounts.SESSION.username, "device-old")
        on("PUT /me/password") {
            ok(JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
                .put("displayName", TestAccounts.SESSION.displayName).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)))
        }
        val vm = viewModel()
        vm.changePassword("eski şifre 12345", "yeni şifre 67890")
        await("the answer") { !vm.state.value.account.busy && seen.isNotEmpty() }
        assertNull(vm.state.value.account.problem)
        assertNotNull(vm.state.value.account.user)
        assertNotNull(storedSession())
        assertNull(deviceTokens().tokenFor(TestAccounts.SESSION.username))
        assertEquals(AccountChange.PASSWORD, vm.state.value.account.confirmed)
    }

    @Test fun aWrongCurrentPasswordChangesNothing() {
        TestAccounts.seed(context)
        deviceTokens().remember(TestAccounts.SESSION.username, "device-old")
        on("PUT /me/password") { refuse(403, "PASSWORD_WRONG") }
        val vm = viewModel()
        vm.changePassword("yanlış şifre 1234", "yeni şifre 67890")
        await("the answer") { !vm.state.value.account.busy && seen.isNotEmpty() }
        assertEquals(AuthProblem.PasswordWrong, vm.state.value.account.problem)
        assertEquals("device-old", deviceTokens().tokenFor(TestAccounts.SESSION.username))
        assertNull(vm.state.value.account.confirmed)
    }

    @Test fun aDeletedAccountLeavesNothingOfItOnThePhone() {
        val recording = File(TestAccounts.space(context).recordings.apply { mkdirs() }, "kayit-1.mp4").apply { writeBytes(ByteArray(10)) }
        TestAccounts.seed(context, StoredState(matches = listOf(MatchRecord("m", "Ev", "Konuk", 1L, video(recording.path))), draft = Draft(name = "Taslak")))
        deviceTokens().remember(TestAccounts.SESSION.username, "device-1")
        deviceTokens().remember("baska", "device-2")
        on("POST /me/delete") { MockResponse().setResponseCode(204) }
        val vm = viewModel()
        vm.deleteAccount("uzun bir şifre 42")
        await("the deletion") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertEquals(JSONObject().put("password", "uzun bir şifre 42").toString(), JSONObject(seen.single().body).toString())
        assertFalse(File(files, "accounts/${TestAccounts.SESSION.userId}").exists())
        assertFalse(recording.exists())
        assertFalse(sessionFile().exists())
        assertNull(deviceTokens().tokenFor(TestAccounts.SESSION.username))
        assertEquals("another account's token stays", "device-2", deviceTokens().tokenFor("baska"))
        assertTrue(vm.state.value.matches.isEmpty())
        assertFalse(vm.state.value.account.sessionEnded)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        // The login screen says so, once: the next account call clears it.
        assertEquals(AccountChange.DELETED, vm.state.value.account.confirmed)
        vm.clearAccountProblem()
        assertNull(vm.state.value.account.confirmed)
    }

    @Test fun aRefusedDeletionDeletesNothing() {
        TestAccounts.seed(context, StoredState(teams = listOf(Team("t1", "A Takımı"))))
        on("POST /me/delete") { refuse(403, "PASSWORD_WRONG") }
        val vm = viewModel()
        vm.deleteAccount("yanlış şifre 1234")
        await("the answer") { !vm.state.value.account.busy && seen.isNotEmpty() }
        assertEquals(AuthProblem.PasswordWrong, vm.state.value.account.problem)
        assertNotNull(vm.state.value.account.user)
        assertNull(vm.state.value.account.confirmed)
        assertTrue(File(files, "accounts/${TestAccounts.SESSION.userId}/volleystat.json").exists())
        assertEquals(listOf("A Takımı"), vm.state.value.teams.map { it.name })
    }

    @Test fun aDeletionWhoseAnswerWasLostDeletesNothingOnThePhone() {
        TestAccounts.seed(context, StoredState(teams = listOf(Team("t1", "A Takımı"))))
        on("POST /me/delete") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val vm = viewModel()
        vm.deleteAccount("uzun bir şifre 42")
        await("the answer") { !vm.state.value.account.busy && seen.isNotEmpty() }
        assertEquals(AuthProblem.Unreachable, vm.state.value.account.problem)
        assertNotNull(vm.state.value.account.user)
        assertNull("nothing says the account is gone", vm.state.value.account.confirmed)
        assertEquals(listOf("A Takımı"), TestAccounts.space(context).store.load().teams.map { it.name })
    }

    // ----- review findings F2, F3, F6 and F7: what one account leaves behind never reaches the next -----

    private fun processing(video: String) = JSONObject().put("id", "job-$video").put("videoId", video).put("state", "PROCESSING")
        .put("analyzer", JSONObject().put("id", "demo").put("kind", "MOCK")).put("mock", true)
        .put("counts", JSONObject().put("rallies", 0).put("reviewOpen", 0).put("reviewTotal", 0))

    private fun meOf(displayName: String) = ok(
        JSONObject().put("user", JSONObject().put("id", TestAccounts.SESSION.userId).put("username", TestAccounts.SESSION.username)
            .put("displayName", displayName).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)).put("teams", org.json.JSONArray())
    )

    /** The requests that went out after the one at [index]. */
    private fun after(index: Int) = seen.drop(index + 1)

    @Test fun anUploadOfTheAccountThatLeftSendsNothingMoreAndTheNextAccountUploadsWithItsOwnSession() {
        val recording = File(TestAccounts.space(context).recordings.apply { mkdirs() }, "kayit-1.mp4").apply { writeBytes(ByteArray(10) { 1 }) }
        val unfinished = MatchRecord("U", "Ev", "Konuk", 1L, video(recording.path), session = com.voleybolveveleybol.volleystat.model.UploadSession("sha", "server-U", "upload-U", 4))
        TestAccounts.seed(context, StoredState(matches = listOf(unfinished)))
        // Veli has an unfinished upload of his own in his folder.
        val veliRecording = File(TestAccounts.space(context, "user-veli").recordings.apply { mkdirs() }, "kayit-2.mp4").apply { writeBytes(ByteArray(10) { 2 }) }
        TestAccounts.space(context, "user-veli").store.save(
            StoredState(matches = listOf(MatchRecord("V", "Ev", "Konuk", 1L, video(veliRecording.path), session = com.voleybolveveleybol.volleystat.model.UploadSession("sha", "server-V", "upload-V", 4))))
        )
        val held = CountDownLatch(1)
        on("GET /uploads/upload-U") { held.await(8, TimeUnit.SECONDS); ok(JSONObject().put("uploadId", "upload-U").put("nextOffset", 4).put("status", "UPLOADING").put("videoId", JSONObject.NULL)) }
        on("GET /uploads/upload-V") { ok(JSONObject().put("uploadId", "upload-V").put("nextOffset", 4).put("status", "UPLOADING").put("videoId", JSONObject.NULL)) }
        listOf("U", "V").forEach { id ->
            on("PUT /uploads/upload-$id/chunks") { ok(JSONObject().put("nextOffset", 10)) }
            on("POST /uploads/upload-$id/complete") {
                ok(JSONObject().put("id", "video-$id").put("status", "READY").put("quality", JSONObject().put("rejectCode", JSONObject.NULL).put("warnings", org.json.JSONArray())))
            }
        }
        val vm = viewModel()
        vm.resumeUpload("U")
        await("the upload's first request") { seen.any { it.key == "GET /uploads/upload-U" } }
        vm.logoutAndWait()
        val logout = seen.indexOfFirst { it.key == "POST /auth/logout" }
        // The server answers the upload's question only now, after the logout.
        held.countDown()
        Thread.sleep(500)
        settle()
        assertEquals("nothing of the upload went out after the logout", emptyList<String>(), after(logout).map { it.key })
        assertNull(vm.state.value.upload)
        assertEquals("the record keeps what the server holds", 4, TestAccounts.space(context).store.load().matches.single().session.sentBytes)

        vm.loginAndWait("veli")
        vm.resumeUpload("V")
        await("Veli's finished upload") { vm.state.value.matches.single().check != null }
        assertEquals("Bearer ${storedSession()!!.token}", seen.single { it.key == "PUT /uploads/upload-V/chunks" }.authorization)
        assertTrue(seen.none { it.key.startsWith("PUT /uploads/upload-U") || it.key == "POST /uploads/upload-U/complete" })
    }

    @Test fun aFollowedAnalysisOfTheAccountThatLeftIsNotAskedForAgainWithTheNextAccountsSession() {
        TestAccounts.seed(context, StoredState(matches = listOf(accepted("A"))))
        on("GET /videos/video-A/analyses/latest") { ok(processing("video-A")) }
        val vm = viewModel()
        vm.watchAnalysis("A", startIfMissing = false)
        await("the first poll") { seen.any { it.key == "GET /videos/video-A/analyses/latest" } }
        vm.logoutAndWait()
        val logout = seen.indexOfFirst { it.key == "POST /auth/logout" }
        vm.loginAndWait("veli")
        // The watch asks again after two seconds while the server works. Its wait runs on the main looper, whose clock
        // moves only when the test moves it. None of what follows may come after the logout.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        Thread.sleep(500)
        settle()
        assertEquals(emptyList<String>(), after(logout).filter { it.key.contains("video-A") }.map { "${it.key} ${it.authorization}" })
        assertEquals("veli", vm.state.value.account.user?.username)
    }

    @Test fun anAccountReadThatAnswersAfterAnotherCallEndedTheSessionDoesNotBringTheSessionBack() {
        TestAccounts.seed(context, StoredState(matches = listOf(accepted("A"))))
        val held = CountDownLatch(1)
        on("GET /me") { held.await(8, TimeUnit.SECONDS); meOf("Yeni Ad") }
        on("GET /videos/video-A/analyses/latest") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.refreshAccount()
        await("the account read") { seen.any { it.key == "GET /me" } }
        // Another call meets the end of the session while the read is on its way.
        vm.watchAnalysis("A", startIfMissing = false)
        await("the end of the session") { vm.state.value.account.user == null }
        held.countDown()
        Thread.sleep(300)
        settle()
        assertNull(vm.state.value.account.user)
        assertTrue(vm.state.value.account.sessionEnded)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertFalse("the ended session is not written back", sessionFile().exists())
        assertTrue(vm.playbackHeaders().isEmpty())
    }

    @Test fun anAccountReadOfTheAccountThatLeftChangesNothingOfTheNextAccount() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        val held = CountDownLatch(1)
        on("GET /me") { held.await(8, TimeUnit.SECONDS); ok(JSONObject().put("user", userJson("ali")).put("teams", org.json.JSONArray())) }
        val vm = viewModel()
        vm.loginAndWait("ali")
        vm.refreshAccount()
        await("Ali's account read") { seen.any { it.key == "GET /me" } }
        vm.logoutAndWait()
        vm.loginAndWait("veli")
        val veli = storedSession()!!
        held.countDown()
        Thread.sleep(300)
        settle()
        assertEquals("veli", vm.state.value.account.user?.username)
        assertEquals(veli.token, storedSession()!!.token)
        assertEquals("user-veli", storedSession()!!.userId)
        assertEquals(mapOf("Authorization" to "Bearer ${veli.token}"), vm.playbackHeaders())
    }

    @Test fun anAccountCallThatMeetsAnEndedSessionLeavesTheReasonToTheLoginScreenAlone() {
        on("PUT /me/display-name") { refuse(401, "AUTH_REQUIRED") }
        on("PUT /me/password") { refuse(401, "AUTH_REQUIRED") }
        on("POST /me/delete") { refuse(401, "AUTH_REQUIRED") }
        val calls = listOf<(AppViewModel) -> Unit>(
            { it.changeDisplayName("Yeni Ad") },
            { it.changePassword("eski şifre 12345", "yeni şifre 67890") },
            { it.deleteAccount("uzun bir şifre 42") },
        )
        calls.forEachIndexed { index, call ->
            TestAccounts.seed(context)
            val vm = viewModel()
            call(vm)
            await("the end of session $index") { vm.state.value.account.user == null && !vm.state.value.account.busy }
            assertTrue(vm.state.value.account.sessionEnded)
            // The login screen says it once, in its own notice: no failure text stands above it with the same words.
            assertNull("call $index", vm.state.value.account.problem)
            assertEquals(Entry.LOGIN, vm.state.value.entry)
        }
    }

    @Test fun aRestartAfterTheOldDataWasKeptOpensHomeAndAsksNothing() {
        writeLegacy()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = viewModel()
        vm.loginAndWait("ali")
        vm.answerLegacy(move = false)
        await("the answer") { vm.state.value.account.legacy == null }
        // The old files are still there. The phone asked once, and a restart asks no more.
        val restarted = viewModel()
        assertNull(restarted.state.value.account.legacy)
        assertEquals(Entry.HOME, restarted.state.value.entry)
    }

    @Test fun aMoveOfTheOldDataThatFailsGivesItsButtonsBackEachTimeAndASecondTapIsNoSecondAnswer() {
        writeLegacy()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        // A file stands where the account's folder would be, so nothing can be written into it.
        val blocker = File(files, "accounts/user-ali").apply { parentFile!!.mkdirs(); writeText("") }
        val vm = viewModel()
        vm.loginAndWait("ali")
        repeat(2) { attempt ->
            vm.answerLegacy(move = true)
            assertTrue("attempt $attempt is on its way", vm.state.value.account.legacyMoving)
            vm.answerLegacy(move = false)
            await("attempt $attempt") { !vm.state.value.account.legacyMoving }
            assertTrue(vm.state.value.saveFailed)
            assertNotNull("the question stays", vm.state.value.account.legacy)
            assertFalse("the tap that came while the move was on its way answered nothing", vm.state.value.settings.legacyHandled)
        }
        blocker.delete()
        vm.answerLegacy(move = true)
        await("the move") { vm.state.value.account.legacy == null }
        assertFalse(vm.state.value.account.legacyMoving)
        assertEquals(listOf("old"), vm.state.value.matches.map { it.localId })
    }

    @Test fun aSessionFileWhoseAccountIdNamesNoFolderStartsOnTheLoginAndTouchesNothing() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        SessionStore(sessionFile(), PlainSessionCipher).save(com.voleybolveveleybol.volleystat.data.Session("token-x", "../x", "x", "X", "2099-01-01T00:00:00Z"))
        val vm = viewModel()
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertNull(vm.state.value.account.user)
        assertTrue(vm.playbackHeaders().isEmpty())
        assertFalse(File(files, "x").exists())
        assertFalse(File(files, "accounts").exists())
    }

    @Test fun anAccountReadThatWasOnItsWayBeforeANameChangeDoesNotPutTheOldNameBack() {
        TestAccounts.seed(context)
        val held = CountDownLatch(1)
        // The read left before the change and the server answers it with the name it held then.
        on("GET /me") { held.await(8, TimeUnit.SECONDS); meOf(TestAccounts.SESSION.displayName) }
        on("PUT /me/display-name") { meOf("Yeni Ad") }
        val vm = viewModel()
        vm.refreshAccount()
        await("the account read") { seen.any { it.key == "GET /me" } }
        vm.changeDisplayName("Yeni Ad")
        await("the confirmed name") { vm.state.value.account.confirmed == AccountChange.DISPLAY_NAME }
        held.countDown()
        Thread.sleep(300)
        settle()
        assertEquals("Yeni Ad", vm.state.value.account.user!!.displayName)
        assertEquals("Yeni Ad", storedSession()!!.displayName)
        // A read that leaves after the change is taken as before.
        on("GET /me") { meOf("Başka Telefonda Verilen Ad") }
        vm.refreshAccount()
        await("the later read") { vm.state.value.account.user!!.displayName == "Başka Telefonda Verilen Ad" }
    }

    @Test fun anAccountReadThatLeftWhileANameChangeWasOnItsWayDoesNotPutTheOldNameBack() {
        TestAccounts.seed(context)
        val held = CountDownLatch(1)
        on("PUT /me/display-name") { held.await(8, TimeUnit.SECONDS); meOf("Yeni Ad") }
        on("GET /me") { meOf(TestAccounts.SESSION.displayName) }
        val vm = viewModel()
        vm.changeDisplayName("Yeni Ad")
        await("the name change on its way") { seen.any { it.key == "PUT /me/display-name" } }
        // The profile reads the account while the change waits, and the server answers that read with the name it holds now.
        // The main looper stays still from here on, so both answers wait for it in the order they came.
        vm.refreshAccount()
        val end = System.currentTimeMillis() + 5_000
        while (seen.none { it.key == "GET /me" } && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(300)
        held.countDown()
        while (vm.state.value.account.user!!.displayName != "Yeni Ad") {
            check(System.currentTimeMillis() < end + 5_000) { "the change never landed" }
            Thread.sleep(10)
        }
        settle()
        assertEquals("Yeni Ad", vm.state.value.account.user!!.displayName)
        assertEquals("Yeni Ad", storedSession()!!.displayName)
        assertEquals(AccountChange.DISPLAY_NAME, vm.state.value.account.confirmed)
    }

    private fun accepted(id: String) = MatchRecord(
        id, "Ev $id", "Misafir $id", 1L, video("/none/$id.mp4"),
        session = com.voleybolveveleybol.volleystat.model.UploadSession("sha", "server-$id", "upload-$id", 10),
        check = com.voleybolveveleybol.volleystat.model.VideoCheck("video-$id", com.voleybolveveleybol.volleystat.model.VideoCheck.READY, null, null, null, null, null, null, emptyList()),
    )
}
