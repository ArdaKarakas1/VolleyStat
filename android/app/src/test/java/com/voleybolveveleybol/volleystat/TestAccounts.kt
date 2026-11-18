package com.voleybolveveleybol.volleystat

import android.content.Context
import com.voleybolveveleybol.volleystat.data.AccountFiles
import com.voleybolveveleybol.volleystat.data.AccountSpace
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.DeviceStore
import com.voleybolveveleybol.volleystat.data.Session
import com.voleybolveveleybol.volleystat.data.SessionStore
import com.voleybolveveleybol.volleystat.data.SignedIn
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.Settings
import java.io.File
import java.util.UUID

/** Puts a phone into the state a login leaves behind, so that a test can start on Home with data of its own. */
object TestAccounts {
    /** A session no server issued. The routes before slice B3 do not check it. */
    val SESSION = Session("test-session-token", "test-user", "test.user", "Test Kullanıcı", "2099-01-01T00:00:00Z")
    val SIGNED_IN_PHONE = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true)

    fun files(context: Context): File = context.filesDir

    fun space(context: Context, userId: String = SESSION.userId): AccountSpace = AccountFiles(context.filesDir).of(userId)

    /** Signs [session] in on this phone with [stored] as its data. The view model reads it when it starts. */
    fun seed(context: Context, stored: StoredState = StoredState(), session: Session = SESSION, settings: Settings = SIGNED_IN_PHONE) {
        val files = context.filesDir
        DeviceStore(File(files, "device.json"), File(files, "volleystat.json")).save(settings)
        SessionStore(File(files, "session.bin"), PlainSessionCipher).save(session)
        space(context, session.userId).store.save(stored)
    }

    /** Writes only device.json, for a phone where nobody is signed in. */
    fun device(context: Context, settings: Settings) =
        DeviceStore(File(context.filesDir, "device.json"), File(context.filesDir, "volleystat.json")).save(settings)

    /**
     * Opens a fresh account on a backend that runs with VOLLEYSTAT_SIGNUP=open and returns it. The
     * caller deletes it afterwards with [deleteOnBackend].
     */
    fun registerOnBackend(url: String): Pair<SignedIn, String> {
        val password = "e2e şifresi ${UUID.randomUUID()}"
        val username = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(20)
        return ApiClient(url).register(username, "E2E Kullanıcı", password, null) to password
    }

    fun deleteOnBackend(url: String, account: SignedIn, password: String) {
        runCatching { ApiClient(url, token = account.token).deleteAccount(password) }
    }

    /** The session a backend account leaves on the phone. */
    fun sessionOf(account: SignedIn) = Session(account.token, account.user.id, account.user.username, account.user.displayName, account.expiresAt)
}
