package com.voleybolveveleybol.volleystat.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The signed-in account as the phone keeps it (session.bin, design 10.3). [expiresAt] is the end
 * the server named at login; the server moves it on while the session is used, so the phone never
 * ends a session by it. Only a 401 from the server ends one.
 */
data class Session(
    val token: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val expiresAt: String,
    /**
     * A deletion of the account went out with this session and no answer of the server said
     * whether it happened. The server may have deleted the account. The first answer that shows the
     * session alive clears it (review finding S1).
     */
    val deletionUnanswered: Boolean = false,
) {
    /** A session that reaches a log or a crash report never carries its token. */
    override fun toString() =
        "Session(userId=$userId, username=$username, displayName=$displayName, expiresAt=$expiresAt, deletionUnanswered=$deletionUnanswered, token=<hidden>)"
}

/** Writes [bytes] to a temporary file next to [file] and then puts it in place, so a crash leaves the old file or the new one. */
internal fun replaceAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not replace ${file.name}" }
        }
    } finally {
        temporary.delete()
    }
}

/** session.bin: the one session of this phone, sealed with [cipher]. */
class SessionStore(private val file: File, private val cipher: SessionCipher) {

    /** The saved session, or null. A file that is damaged, cut short or sealed with another key is no session. */
    fun load(): Session? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(String(cipher.open(file.readBytes()), Charsets.UTF_8))
            Session(
                json.getString("token"), json.getString("userId"), json.getString("username"),
                json.getString("displayName"), json.getString("expiresAt"), json.optBoolean("deletionUnanswered", false),
            ).takeIf { it.token.isNotEmpty() && it.userId.isNotEmpty() }
        } catch (error: Exception) {
            null
        }
    }

    /** Throws when the phone can not seal or write the session; the old file then stays as it was. */
    @Synchronized
    fun save(session: Session) {
        val json = JSONObject().put("token", session.token).put("userId", session.userId).put("username", session.username)
            .put("displayName", session.displayName).put("expiresAt", session.expiresAt).put("deletionUnanswered", session.deletionUnanswered)
        replaceAtomically(file, cipher.seal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}

/**
 * device.bin: the device token the server gave each account that logged in on this phone (design
 * 3.2). A login that sends it counts its failures under this phone's own key, so a stranger who
 * knows the username can not lock the owner's phone out. The file keys each token by the SHA-256
 * of the username, so even the sealed content names nobody. A token stays through logout.
 */
class DeviceTokenStore(private val file: File, private val cipher: SessionCipher) {

    /** The token of [username], or null. A file the phone can not open now gives none, and the login goes without one. */
    fun tokenFor(username: String): String? = runCatching { read() }.getOrNull()?.optString(key(username))?.ifEmpty { null }

    /** Throws, and leaves the file as it was, when the phone can not open the file now (review finding S4). */
    @Synchronized
    fun remember(username: String, token: String) = write(read().put(key(username), token))

    @Synchronized
    fun forget(username: String) {
        val tokens = read()
        if (tokens.remove(key(username)) != null) write(tokens)
    }

    /**
     * The tokens in the file. A file that is damaged, cut short or sealed with a key the phone no
     * longer has holds nothing anyone can read, so it reads as empty and the next write replaces it:
     * that costs only the protection of the device keys until each account's next login. Any other
     * failure (a busy keystore, a read error) is tried once more and then thrown, so a write never
     * replaces the other accounts' tokens with a file that holds only the new one.
     */
    private fun read(): JSONObject = readSealed(file, cipher)

    private fun write(tokens: JSONObject) = replaceAtomically(file, cipher.seal(tokens.toString().toByteArray(Charsets.UTF_8)))

    private fun key(username: String) = sha256Hex(username)
}

/**
 * The JSON sealed in [file]. A file that is missing, damaged, cut short or sealed with a key the
 * phone no longer has reads as empty. Any other failure (a busy keystore, a read error) is tried
 * once more and then thrown, so the caller leaves the file as it was.
 */
private fun readSealed(file: File, cipher: SessionCipher): JSONObject {
    if (!file.isFile) return JSONObject()
    var tries = 0
    while (true) {
        try {
            return JSONObject(String(cipher.open(file.readBytes()), Charsets.UTF_8))
        } catch (damaged: org.json.JSONException) {
            return JSONObject()
        } catch (error: Exception) {
            val unreadable = error is java.security.GeneralSecurityException && error !is java.security.KeyStoreException
            if (unreadable) return JSONObject()
            if (++tries == 2) throw error
        }
    }
}

/**
 * deletions.bin: the accounts whose deletion got no answer and whose session then left the phone
 * (review findings S1 and F1), newest first, as (user id, username). The login screen offers to
 * delete each one's data until the user answers or the account logs in again. The list outlives
 * the process, so the folder of an account the server may have deleted never drops out of reach.
 * It names accounts, so it is sealed like session.bin.
 */
class DeletionDoubtStore(private val file: File, private val cipher: SessionCipher) {

    /** The accounts in doubt. A file the phone can not open now gives none and stays as it is. */
    fun load(): List<Pair<String, String>> = runCatching { read() }.getOrDefault(emptyList())

    /** Puts [userId] first. Throws, and leaves the file as it was, when the phone can not open the file now. */
    @Synchronized
    fun add(userId: String, username: String) = write(listOf(userId to username) + read().filterNot { it.first == userId })

    /** Throws, and leaves the file as it was, when the phone can not open the file now. */
    @Synchronized
    fun remove(userId: String) {
        val held = read()
        if (held.any { it.first == userId }) write(held.filterNot { it.first == userId })
    }

    private fun read(): List<Pair<String, String>> {
        val accounts = readSealed(file, cipher).optJSONArray("accounts") ?: return emptyList()
        return (0 until accounts.length()).mapNotNull { index ->
            accounts.optJSONObject(index)?.let { it.optString("userId") to it.optString("username") }?.takeIf { it.first.isNotEmpty() }
        }
    }

    private fun write(accounts: List<Pair<String, String>>) {
        if (accounts.isEmpty()) {
            file.delete()
            return
        }
        val json = JSONObject().put("accounts", org.json.JSONArray(accounts.map { (id, name) -> JSONObject().put("userId", id).put("username", name) }))
        replaceAtomically(file, cipher.seal(json.toString().toByteArray(Charsets.UTF_8)))
    }
}

/** The SHA-256 of [text] in hex: how the phone's plain or sealed files name an account without naming it. */
fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
