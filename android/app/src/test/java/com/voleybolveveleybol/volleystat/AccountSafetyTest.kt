package com.voleybolveveleybol.volleystat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.DeviceTokenStore
import com.voleybolveveleybol.volleystat.data.SessionStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
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

/**
 * What the phone keeps of an account when the server's answer to its deletion was lost (review
 * finding S1). The app never claims a deletion the server did not confirm, and it never leaves the
 * account's matches, rosters and recordings on the phone with no way to reach them. The other
 * accounts of the phone never see the old single-account data that one account was asked about
 * (S2), and a session never travels to a server other than the one that opened it (S5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountSafetyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val files get() = context.filesDir
    private val server = MockWebServer()

    private class Seen(val key: String, val authorization: String?, val body: String)
    private val seen = CopyOnWriteArrayList<Seen>()
    private val script = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val password = "uzun bir şifre 42"
    private val user = TestAccounts.SESSION

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                seen += Seen(key, request.getHeader("Authorization"), request.body.readUtf8())
                return script[key]?.invoke(request) ?: refuse(404, "NOT_FOUND")
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun on(request: String, answer: (RecordedRequest) -> MockResponse) { script[request] = answer }
    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun refuse(status: Int, code: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", JSONObject())).toString())
        .apply { if (status == 401) setHeader("WWW-Authenticate", "Bearer") }
    private fun lost() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    private fun userJson() = JSONObject().put("id", user.userId).put("username", user.username).put("displayName", user.displayName)
        .put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)
    private fun signedIn(token: String) = ok(
        JSONObject().put("user", userJson()).put("session", JSONObject().put("token", token).put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-1")
    )

    private fun viewModel() = AppViewModel(context).also { it.baseUrlOverride = server.url("/v1").toString() }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun deviceTokens() = DeviceTokenStore(File(files, "device.bin"), PlainSessionCipher)
    private val folder get() = File(files, "accounts/${user.userId}")

    /** A signed-in phone with a match, a roster, a camera recording and device tokens for this account and another one. */
    private fun seedAccountWithData(): File {
        val recording = File(TestAccounts.space(context).recordings.apply { mkdirs() }, "kayit-1.mp4").apply { writeBytes(ByteArray(10)) }
        val video = LocalVideo(recording.path, VideoSource.CAMERA, recording.name, 10, "video/mp4", null, null, null, null)
        TestAccounts.seed(context, StoredState(matches = listOf(MatchRecord("m", "Ev", "Konuk", 1L, video)), teams = listOf(Team("t1", "A Takımı"))))
        deviceTokens().remember(user.username, "device-1")
        deviceTokens().remember("baska", "device-2")
        return recording
    }

    /** The first deletion reaches the server and its answer is lost: the app knows nothing and keeps everything. */
    private fun AppViewModel.deleteAndLoseTheAnswer() {
        on("POST /me/delete") { lost() }
        deleteAccount(password)
        await("the lost answer") { !state.value.account.busy && seen.isNotEmpty() }
        assertEquals(AuthProblem.Unreachable, state.value.account.problem)
        assertNotNull(state.value.account.user)
    }

    @Test fun aRepeatedDeletionThatFindsTheAccountGoneOffersToDeleteThePhonesDataAndDeletesOnlyOnTheUsersWord() {
        val recording = seedAccountWithData()
        val vm = viewModel()
        vm.deleteAndLoseTheAnswer()

        // The server deleted the account: the repeat meets an ended session, and a login with the same password fails.
        on("POST /me/delete") { refuse(401, "AUTH_REQUIRED") }
        on("POST /auth/login") { refuse(401, "LOGIN_FAILED") }
        vm.deleteAccount(password)
        await("the end of the session") { vm.state.value.account.user == null && !vm.state.value.account.busy }

        assertEquals(listOf("POST /me/delete", "POST /me/delete", "POST /auth/login"), seen.map { it.key })
        val login = JSONObject(seen.last().body)
        assertEquals(user.username, login.getString("username"))
        assertEquals(password, login.getString("password"))
        assertEquals("the login counts under this phone's own key", "device-1", login.getString("deviceToken"))
        assertNull("the login request carries no session", seen.last().authorization)

        val account = vm.state.value.account
        assertEquals(UnconfirmedDeletion(user.userId, user.username), account.unconfirmedDeletion)
        assertNull("the offer itself says what happened; no failure text stands above it", account.problem)
        assertNull("nothing says that the server deleted the account", account.confirmed)
        assertFalse(account.sessionEnded)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertFalse(File(files, "session.bin").exists())
        // Nothing on the phone goes before the user says so.
        assertTrue(recording.exists())
        assertTrue(File(folder, "volleystat.json").exists())
        assertEquals("device-1", deviceTokens().tokenFor(user.username))

        vm.deleteUnconfirmedAccountData()
        assertFalse(folder.exists())
        assertFalse(recording.exists())
        assertNull(deviceTokens().tokenFor(user.username))
        assertEquals("another account's token stays", "device-2", deviceTokens().tokenFor("baska"))
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertEquals(AccountChange.PHONE_DATA_DELETED, vm.state.value.account.confirmed)
        assertEquals("the phone asked the server nothing for it", 3, seen.size)
    }

    @Test fun aRepeatedDeletionWhoseLoginFindsTheAccountSendsTheDeletionAgainWithTheNewSession() {
        val recording = seedAccountWithData()
        val vm = viewModel()
        vm.deleteAndLoseTheAnswer()

        // The first deletion did not happen: the session ended for another reason, and the account is still there.
        var deletions = 0
        on("POST /me/delete") { if (++deletions == 1) refuse(401, "AUTH_REQUIRED") else MockResponse().setResponseCode(204) }
        on("POST /auth/login") { signedIn("fresh-token") }
        vm.deleteAccount(password)
        await("the deletion") { vm.state.value.account.user == null && !vm.state.value.account.busy }

        assertEquals(listOf("POST /me/delete", "POST /me/delete", "POST /auth/login", "POST /me/delete"), seen.map { it.key })
        assertEquals("Bearer ${user.token}", seen[1].authorization)
        assertEquals("Bearer fresh-token", seen[3].authorization)
        assertEquals(password, JSONObject(seen[3].body).getString("password"))
        assertEquals(AccountChange.DELETED, vm.state.value.account.confirmed)
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertFalse(folder.exists())
        assertFalse(recording.exists())
        assertFalse(File(files, "session.bin").exists())
        assertNull(deviceTokens().tokenFor(user.username))
    }

    @Test fun aLostDeletionAnswerSurvivesARestartAndAnyLaterEndOfTheSessionOffersThePhonesData() {
        seedAccountWithData()
        viewModel().deleteAndLoseTheAnswer()

        // The app restarts, and the profile reads the account: the server no longer knows the session.
        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        val restarted = viewModel()
        assertNotNull(restarted.state.value.account.user)
        restarted.refreshAccount()
        await("the end of the session") { restarted.state.value.account.user == null }
        assertEquals(UnconfirmedDeletion(user.userId, user.username), restarted.state.value.account.unconfirmedDeletion)
        assertTrue("the folder stays until the user says so", File(folder, "volleystat.json").exists())
        assertEquals("no login is tried without a password the user typed for it", listOf("POST /me/delete", "GET /me"), seen.map { it.key })
    }

    @Test fun aLoginOfThatAccountWithdrawsTheOfferAndFindsItsData() {
        seedAccountWithData()
        val vm = viewModel()
        vm.deleteAndLoseTheAnswer()
        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        vm.refreshAccount()
        await("the offer") { vm.state.value.account.unconfirmedDeletion != null }

        // The account is still there, for example because its password was changed on another phone.
        on("POST /auth/login") { signedIn("fresh-token") }
        vm.login(user.username, password)
        await("the login") { vm.state.value.account.user != null }
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertEquals(listOf("A Takımı"), vm.state.value.teams.map { it.name })
        // The offer is gone for good: a later end of the new session is an ordinary one.
        vm.refreshAccount()
        await("the end of the new session") { vm.state.value.account.user == null }
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertTrue(vm.state.value.account.sessionEnded)
    }

    @Test fun anAnswerThatShowsTheAccountIsStillThereEndsTheDoubt() {
        seedAccountWithData()
        val vm = viewModel()
        vm.deleteAndLoseTheAnswer()

        // The server answers for the session: it was not deleted.
        var reads = 0
        on("GET /me") { if (++reads == 1) ok(JSONObject().put("user", userJson()).put("teams", org.json.JSONArray())) else refuse(401, "AUTH_REQUIRED") }
        vm.refreshAccount()
        await("the answer") { reads == 1 && !SessionStore(File(files, "session.bin"), PlainSessionCipher).load()!!.deletionUnanswered }
        vm.refreshAccount()
        await("the end of the session") { vm.state.value.account.user == null }
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertTrue(vm.state.value.account.sessionEnded)
    }

    @Test fun aRefusedRepeatShowsTheAccountIsStillThere() {
        seedAccountWithData()
        val vm = viewModel()
        vm.deleteAndLoseTheAnswer()
        on("POST /me/delete") { refuse(403, "PASSWORD_WRONG") }
        vm.deleteAccount("yanlış şifre 1234")
        await("the answer") { !vm.state.value.account.busy && seen.size == 2 }
        assertEquals(AuthProblem.PasswordWrong, vm.state.value.account.problem)
        assertFalse(SessionStore(File(files, "session.bin"), PlainSessionCipher).load()!!.deletionUnanswered)

        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        vm.refreshAccount()
        await("the end of the session") { vm.state.value.account.user == null }
        assertNull(vm.state.value.account.unconfirmedDeletion)
    }

    // ----- review finding F1: the offer outlives the process, so no account's folder is ever out of reach -----

    /** Loses the answer of a deletion, then repeats it: the server no longer knows the session, and the login with the same password fails. */
    private fun AppViewModel.deleteUntilTheOfferShows() {
        deleteAndLoseTheAnswer()
        on("POST /me/delete") { refuse(401, "AUTH_REQUIRED") }
        on("POST /auth/login") { refuse(401, "LOGIN_FAILED") }
        deleteAccount(password)
        await("the offer") { state.value.account.unconfirmedDeletion != null && !state.value.account.busy }
    }

    @Test fun theOfferOutlivesARestartAndGoesOnlyWhenTheUserAnswersIt() {
        val recording = seedAccountWithData()
        viewModel().deleteUntilTheOfferShows()

        // The system ends the app while the login screen shows the offer. The next start shows it again.
        val restarted = viewModel()
        assertEquals(Entry.LOGIN, restarted.state.value.entry)
        assertEquals(UnconfirmedDeletion(user.userId, user.username), restarted.state.value.account.unconfirmedDeletion)
        assertFalse("device.json names nobody", File(files, "device.json").readText().let { it.contains(user.userId) || it.contains(user.username) })
        assertTrue(recording.exists())

        restarted.deleteUnconfirmedAccountData()
        assertFalse(folder.exists())
        assertFalse(recording.exists())
        assertNull(deviceTokens().tokenFor(user.username))
        assertNull(restarted.state.value.account.unconfirmedDeletion)
        assertNull("the answer is kept too", viewModel().state.value.account.unconfirmedDeletion)
    }

    @Test fun aLoginOfTheAccountInDoubtWithdrawsTheOfferAlsoForTheNextStart() {
        seedAccountWithData()
        val vm = viewModel()
        vm.deleteUntilTheOfferShows()
        // The account is still there after all: its password was changed on another phone, and this is the new one.
        on("POST /auth/login") { signedIn("fresh-token") }
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }
        vm.login(user.username, "yeni şifre 67890")
        await("the login") { vm.state.value.account.user != null && !vm.state.value.account.busy }
        vm.logout()
        await("the logout") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertNull(viewModel().state.value.account.unconfirmedDeletion)
        assertTrue(File(folder, "volleystat.json").exists())
    }

    @Test fun twoAccountsInDoubtAreOfferedOneAfterTheOther() {
        val recording = seedAccountWithData()
        val vm = viewModel()
        vm.deleteUntilTheOfferShows()

        // Veli logs in on the same phone, and the answer of his deletion is lost too.
        on("POST /auth/login") { signedInAs("veli", "token-veli") }
        vm.loginAs("veli")
        vm.addTeam("Veli'nin Takımı")
        val veliFolder = File(files, "accounts/user-veli")
        assertTrue(File(veliFolder, "volleystat.json").exists())
        on("POST /me/delete") { lost() }
        vm.deleteAccount(password)
        await("the lost answer") { !vm.state.value.account.busy && vm.state.value.account.problem == AuthProblem.Unreachable }
        on("GET /me") { refuse(401, "AUTH_REQUIRED") }
        vm.refreshAccount()
        await("the end of Veli's session") { vm.state.value.account.user == null }

        val restarted = viewModel()
        assertEquals("the latest doubt comes first", UnconfirmedDeletion("user-veli", "veli"), restarted.state.value.account.unconfirmedDeletion)
        restarted.deleteUnconfirmedAccountData()
        assertFalse(veliFolder.exists())
        assertEquals("the earlier doubt is still offered", UnconfirmedDeletion(user.userId, user.username), restarted.state.value.account.unconfirmedDeletion)
        assertTrue(recording.exists())
        restarted.deleteUnconfirmedAccountData()
        assertFalse(folder.exists())
        assertNull(restarted.state.value.account.unconfirmedDeletion)
    }

    @Test fun aKeptDoubtWhoseIdNamesNoFolderIsNeverOffered() {
        seedAccountWithData()
        viewModel().deleteUntilTheOfferShows()
        // A doubt the phone can not act on would stand on the login screen for good.
        val store = com.voleybolveveleybol.volleystat.data.DeletionDoubtStore(File(files, "deletions.bin"), PlainSessionCipher)
        store.add("../x", "x")
        assertEquals(UnconfirmedDeletion(user.userId, user.username), viewModel().state.value.account.unconfirmedDeletion)
    }

    @Test fun aDoubtAboutTheAccountThatIsSignedInLeavesTheFile() {
        seedAccountWithData()
        // A login whose removal of the doubt did not reach the disk: the session is there, and so is the doubt.
        com.voleybolveveleybol.volleystat.data.DeletionDoubtStore(File(files, "deletions.bin"), PlainSessionCipher).add(user.userId, user.username)
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }
        val vm = viewModel()
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertEquals(emptyList<Pair<String, String>>(), com.voleybolveveleybol.volleystat.data.DeletionDoubtStore(File(files, "deletions.bin"), PlainSessionCipher).load())
        vm.logout()
        await("the logout") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertNull(viewModel().state.value.account.unconfirmedDeletion)
    }

    // ----- review finding S2: the question about the data of app 0.7 belongs to the account that was asked -----

    private fun signedInAs(username: String, token: String) = ok(
        JSONObject().put("user", JSONObject().put("id", "user-$username").put("username", username).put("displayName", username)
            .put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false))
            .put("session", JSONObject().put("token", token).put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-$username")
    )

    private fun AppViewModel.loginAs(username: String) {
        login(username, password)
        await("the login of $username") { !state.value.account.busy && state.value.account.user?.username == username }
    }

    @Test fun anAccountThatWasNotAskedNeverSeesTheOldDataEvenWhenTheAskedOnesSessionIsLost() {
        val recordings = File(files, "recordings").apply { mkdirs() }
        val recording = File(recordings, "eski.mp4").apply { writeBytes(ByteArray(10) { 1 }) }
        val video = LocalVideo(recording.path, VideoSource.CAMERA, recording.name, 10, "video/mp4", null, null, null, null)
        com.voleybolveveleybol.volleystat.data.LocalStore(File(files, "volleystat.json"))
            .save(StoredState(matches = listOf(MatchRecord("old", "Ev", "Konuk", 1L, video, name = "Ali'nin Finali"))))
        TestAccounts.device(context, com.voleybolveveleybol.volleystat.model.Settings(welcomeSeen = true))
        var logins = 0
        on("POST /auth/login") { signedInAs(JSONObject(seen.last().body).getString("username"), "token-${++logins}") }
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }

        val vm = viewModel()
        vm.loginAs("ali")
        assertEquals(listOf("Ali'nin Finali"), vm.state.value.account.legacy?.titles)
        // Ali's session can no longer be read (a lost keystore key, a damaged file) and the app starts again.
        File(files, "session.bin").writeBytes(byteArrayOf(1, 2, 3))
        val restarted = viewModel()
        assertEquals(Entry.LOGIN, restarted.state.value.entry)

        restarted.loginAs("veli")
        assertNull("Veli was never asked, and sees no title of the old data", restarted.state.value.account.legacy)
        assertEquals(Entry.HOME, restarted.state.value.entry)
        assertTrue(restarted.state.value.matches.isEmpty())
        assertNull("nor after a restart", viewModel().state.value.account.legacy)
        assertTrue("the old data stays where it is, unread", File(files, "volleystat.json").exists())
        assertTrue(recording.exists())
        assertFalse(restarted.state.value.settings.legacyHandled)
        // device.json holds no account id, only its digest.
        assertFalse(File(files, "device.json").readText().contains("user-ali"))

        restarted.logout()
        await("the logout") { restarted.state.value.account.user == null && !restarted.state.value.account.busy }
        restarted.loginAs("ali")
        assertEquals("Ali gets the same question again", listOf("Ali'nin Finali"), restarted.state.value.account.legacy?.titles)
        restarted.answerLegacy(move = true)
        await("the move") { restarted.state.value.account.legacy == null }
        assertEquals(listOf("old"), restarted.state.value.matches.map { it.localId })
        assertTrue(restarted.state.value.matches.single().video.location.startsWith(File(files, "accounts/user-ali").path))
    }

    @Test fun aQuestionPutBeforeThePhoneRememberedWhomItWasPutToBelongsToTheSignedInAccount() {
        com.voleybolveveleybol.volleystat.data.LocalStore(File(files, "volleystat.json")).save(StoredState(teams = listOf(Team("t-old", "Eski Takım"))))
        // A phone of this slice before the fix: a session, a question waiting, and no record of whom it was put to.
        TestAccounts.seed(context, settings = com.voleybolveveleybol.volleystat.model.Settings(welcomeSeen = true, accountSeen = true))
        val vm = viewModel()
        assertEquals(1, vm.state.value.account.legacy?.teams)
        val stored = com.voleybolveveleybol.volleystat.data.DeviceStore(File(files, "device.json"), File(files, "volleystat.json")).load()
        assertEquals(com.voleybolveveleybol.volleystat.data.sha256Hex(user.userId), stored.legacyAskedUser)
    }

    // ----- review finding S5: a session goes only to the server that opened it -----

    @Test fun theServerChoiceWaitsForALogoutSoTheTokenNeverGoesToAnotherServer() {
        TestAccounts.seed(context)
        on("GET /health") { ok(JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", 100)) }
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }
        val deviceFile = File(files, "device.json")
        val before = deviceFile.readText()
        val vm = viewModel()
        val target = vm.state.value.settings.serverTarget
        val other = com.voleybolveveleybol.volleystat.model.ServerTarget.entries.first { it != target }

        vm.setServerTarget(other)
        assertEquals("the phone keeps the server of the session", target, vm.state.value.settings.serverTarget)
        assertEquals(before, deviceFile.readText())

        vm.logout()
        await("the logout") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        vm.setServerTarget(other)
        assertEquals(other, vm.state.value.settings.serverTarget)
    }

    @Test fun aDeletionThatMeetsAnEndedSessionWithoutAnEarlierLostAnswerIsAnOrdinaryEnd() {
        seedAccountWithData()
        on("POST /me/delete") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.deleteAccount(password)
        await("the end of the session") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertEquals("no login is tried", listOf("POST /me/delete"), seen.map { it.key })
        assertTrue(vm.state.value.account.sessionEnded)
        assertNull(vm.state.value.account.unconfirmedDeletion)
        assertTrue(File(folder, "volleystat.json").exists())
    }
}
