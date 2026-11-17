package com.voleybolveveleybol.volleystat.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The signed-in account as the phone keeps it (session.bin, design 10.3). [expiresAt] is the end
 * the server named at login; the server moves it on while the session is used, so the phone never
 * ends a session by it. Only a 401 from the server ends one.
 */
data class Session(val token: String, val userId: String, val username: String, val displayName: String, val expiresAt: String) {
    /** A session that reaches a log or a crash report never carries its token. */
    override fun toString() = "Session(userId=$userId, username=$username, displayName=$displayName, expiresAt=$expiresAt, token=<hidden>)"
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
                json.getString("displayName"), json.getString("expiresAt"),
            ).takeIf { it.token.isNotEmpty() && it.userId.isNotEmpty() }
        } catch (error: Exception) {
            null
        }
    }

    /** Throws when the phone can not seal or write the session; the old file then stays as it was. */
    @Synchronized
    fun save(session: Session) {
        val json = JSONObject().put("token", session.token).put("userId", session.userId).put("username", session.username)
            .put("displayName", session.displayName).put("expiresAt", session.expiresAt)
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

    fun tokenFor(username: String): String? = read().optString(key(username)).ifEmpty { null }

    @Synchronized
    fun remember(username: String, token: String) = write(read().put(key(username), token))

    @Synchronized
    fun forget(username: String) {
        val tokens = read()
        if (tokens.remove(key(username)) != null) write(tokens)
    }

    private fun read(): JSONObject {
        if (!file.isFile) return JSONObject()
        return try {
            JSONObject(String(cipher.open(file.readBytes()), Charsets.UTF_8))
        } catch (error: Exception) {
            // A damaged file costs only the protection of the device keys until the next login.
            JSONObject()
        }
    }

    private fun write(tokens: JSONObject) = replaceAtomically(file, cipher.seal(tokens.toString().toByteArray(Charsets.UTF_8)))

    private fun key(username: String) =
        MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
