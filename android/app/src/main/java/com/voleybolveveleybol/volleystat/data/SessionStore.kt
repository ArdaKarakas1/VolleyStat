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
    /**
     * The operator reset the password and the user signed in with the temporary one: Home says so
     * until the user picks a new one (design 3.4). Every answer of the server about the user sets it.
     */
    val passwordTemporary: Boolean = false,
) {
    /** A session that reaches a log or a crash report never carries its token. */
    override fun toString() =
        "Session(userId=$userId, username=$username, displayName=$displayName, expiresAt=$expiresAt, deletionUnanswered=$deletionUnanswered, " +
            "passwordTemporary=$passwordTemporary, token=<hidden>)"
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

    /**
     * The saved session, or null. A file that is damaged, cut short or sealed with another key is no
     * session. A failure that may pass (a keystore error at the start of the app) is tried once more,
     * as device.bin is (second review, R2-C). If it stays, this start has no session, and the file
     * stays as it is for the next start: nothing here deletes or rewrites it.
     */
    fun load(): Session? {
        val json = try {
            readSealed(file, cipher)
        } catch (error: Exception) {
            return null
        }
        return try {
            Session(
                json.getString("token"), json.getString("userId"), json.getString("username"),
                json.getString("displayName"), json.getString("expiresAt"), json.optBoolean("deletionUnanswered", false),
                // A session.bin of the first rounds of this slice has no such field: its password counts as an ordinary one.
                passwordTemporary = json.optBoolean("passwordTemporary", false),
            ).takeIf { it.token.isNotEmpty() && it.userId.isNotEmpty() }
        } catch (damaged: org.json.JSONException) {
            null
        }
    }

    /** Throws when the phone can not seal or write the session; the old file then stays as it was. */
    @Synchronized
    fun save(session: Session) {
        val json = JSONObject().put("token", session.token).put("userId", session.userId).put("username", session.username)
            .put("displayName", session.displayName).put("expiresAt", session.expiresAt).put("deletionUnanswered", session.deletionUnanswered)
            .put("passwordTemporary", session.passwordTemporary)
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
 * The JSON sealed in [file]. A file that is missing or damaged reads as empty: a tag that does not
 * match (a changed file, or one sealed with a key the phone no longer has), another format, a cut
 * short file, or sealed text that is no JSON. Any other failure (a keystore error that may pass, a
 * read error) is tried once more and then thrown, so the caller leaves the file as it was (review
 * finding S4). AndroidKeyStore throws IllegalBlockSizeException, InvalidKeyException and other
 * GeneralSecurityExceptions for errors that pass, so none of those counts as damage.
 */
private fun readSealed(file: File, cipher: SessionCipher): JSONObject {
    if (!file.isFile) return JSONObject()
    var tries = 0
    while (true) {
        try {
            return JSONObject(String(cipher.open(file.readBytes()), Charsets.UTF_8))
        } catch (damaged: javax.crypto.AEADBadTagException) {
            return JSONObject()
        } catch (damaged: SealedFileDamaged) {
            return JSONObject()
        } catch (damaged: org.json.JSONException) {
            return JSONObject()
        } catch (error: Exception) {
            if (++tries == 2) throw error
        }
    }
}

/**
 * The accounts whose deletion got no answer and whose session then left the phone (review findings
 * S1 and F1), newest first, as (user id, username). The login screen offers to delete each one's
 * data until the user answers or the account logs in again. The list outlives the process, so the
 * folder of an account the server may have deleted never drops out of reach.
 *
 * [ids] (deletions.ids) says which accounts are in doubt. It is plain JSON and holds only user ids,
 * which already stand in plain text as the names of the account folders (accounts/<id>/), so it adds
 * no personal data, and it never depends on the keystore. Only where it can not be read does the
 * sealed list stand in for it (see [held]). [file] (deletions.bin) keeps each
 * one's username for the text of the offer, sealed like session.bin. A username that can not be read
 * (a new keystore key, a damaged file, a keystore that fails now) leaves its account in doubt
 * without a name, and the offer then names no account. deletions.bin never names an account that
 * deletions.ids no longer holds (see [remove]).
 */
class DeletionDoubtStore(
    private val file: File,
    private val cipher: SessionCipher,
    private val ids: File = File(file.parentFile, "${file.nameWithoutExtension}.ids"),
) {

    /** The accounts in doubt with the usernames the phone can read now. */
    @Synchronized
    fun load(): List<Pair<String, String?>> {
        val names = runCatching { readNames() }.getOrNull()
        return runCatching { held(names) }.getOrDefault(emptyList()).map { it to names?.get(it) }
    }

    /** Puts [userId] first. Throws when the phone can not write the list; the username waits for a keystore that can seal it. */
    @Synchronized
    fun add(userId: String, username: String?) {
        val names = runCatching { readNames() }.getOrNull()
        val next = listOf(userId) + held(names).filterNot { it == userId }
        writeIds(next)
        if (names != null && username != null) runCatching { writeNames(next, names + (userId to username)) }
    }

    /**
     * Throws when the phone can not write the list. The sealed list leaves [userId] before the plain one
     * does, and a sealed list the keystore can not rewrite now is deleted: a damaged deletions.ids falls
     * back to deletions.bin (see [held]), so deletions.bin must never name an account whose doubt ended
     * (third checker's C1). The accounts still in doubt then lose only their usernames.
     */
    @Synchronized
    fun remove(userId: String) {
        val names = runCatching { readNames() }.getOrNull()
        val held = held(names)
        if (userId !in held) return
        val next = held - userId
        if (next.isEmpty()) {
            file.delete()
            ids.delete()
            return
        }
        val resealed = names != null && runCatching { writeNames(next, names) }.isSuccess
        if (!resealed) file.delete()
        writeIds(next)
    }

    /**
     * The ids in deletions.ids. Where that list is missing or can not be read, deletions.bin names the
     * same accounts in the same order: a phone of the first round of this slice kept the list there
     * only, and a damaged plain list (storage that failed) leaves the sealed one as the only record.
     * Its order counts until the next write of the plain list. While the keystore can not open it, this
     * throws, so that no write replaces either file (second checker's finding on F1).
     */
    private fun held(names: Map<String, String>?): List<String> {
        if (ids.isFile) {
            val accounts = runCatching { JSONObject(ids.readText()).getJSONArray("accounts") }.getOrNull()
            if (accounts != null) return (0 until accounts.length()).map { accounts.optString(it) }.filter { it.isNotEmpty() }.distinct()
        }
        check(names != null || !file.isFile) { "The keystore can not open the list of the accounts in doubt now" }
        return names?.keys?.toList().orEmpty()
    }

    /** The usernames in deletions.bin, in its order. Throws when the keystore can not open it now. */
    private fun readNames(): Map<String, String> {
        val accounts = readSealed(file, cipher).optJSONArray("accounts") ?: return emptyMap()
        return (0 until accounts.length()).mapNotNull { index ->
            accounts.optJSONObject(index)?.let { it.optString("userId") to it.optString("username") }?.takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
        }.toMap(LinkedHashMap())
    }

    private fun writeIds(accounts: List<String>) =
        replaceAtomically(ids, JSONObject().put("version", 1).put("accounts", org.json.JSONArray(accounts)).toString().toByteArray(Charsets.UTF_8))

    private fun writeNames(accounts: List<String>, names: Map<String, String>) {
        val known = accounts.mapNotNull { id -> names[id]?.let { JSONObject().put("userId", id).put("username", it) } }
        if (known.isEmpty()) {
            file.delete()
            return
        }
        replaceAtomically(file, cipher.seal(JSONObject().put("accounts", org.json.JSONArray(known)).toString().toByteArray(Charsets.UTF_8)))
    }
}

/**
 * registrations.bin: the usernames whose registration got no answer (third review, A1). The server
 * may have opened those accounts, so a repeat that meets a taken username or a spent code asks with
 * a login whether the account is there. The file keys each username by its SHA-256, as device.bin
 * does, and is sealed like session.bin: no plain file of the phone names the account, not even by
 * its digest.
 */
class UnansweredRegistrationStore(private val file: File, private val cipher: SessionCipher) {

    /** The digests of the marked usernames. A file the phone can not open now gives none, and stays as it is. */
    fun load(): Set<String> = runCatching { read() }.getOrDefault(emptySet())

    /** Marks [username]. Throws, and leaves the file as it was, when the phone can not open or seal the file now. */
    @Synchronized
    fun add(username: String) {
        val marks = read()
        val key = sha256Hex(username)
        if (key !in marks) write(marks + key)
    }

    /** Takes the mark of [username] off. Throws, and leaves the file as it was, when the phone can not open or seal the file now. */
    @Synchronized
    fun remove(username: String) {
        val marks = read()
        val key = sha256Hex(username)
        if (key in marks) write(marks - key)
    }

    private fun read(): Set<String> {
        val marks = readSealed(file, cipher).optJSONArray("usernames") ?: return emptySet()
        return (0 until marks.length()).map { marks.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun write(marks: Set<String>) {
        if (marks.isEmpty()) {
            file.delete()
            return
        }
        replaceAtomically(file, cipher.seal(JSONObject().put("usernames", org.json.JSONArray(marks.toList())).toString().toByteArray(Charsets.UTF_8)))
    }
}

/** The SHA-256 of [text] in hex: how the phone's plain or sealed files name an account without naming it. */
fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
