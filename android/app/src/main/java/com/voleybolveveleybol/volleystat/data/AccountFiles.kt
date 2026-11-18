package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.MatchRecord
import java.io.File

/** The files of one account: filesDir/accounts/<userId>/ (design 10.3). The app reads only the signed-in account's folder. */
class AccountSpace(val root: File) {
    val storeFile = File(root, "volleystat.json")
    val store = LocalStore(storeFile)
    /** The account's camera recordings. */
    val recordings = File(root, "recordings")
    /** Frames the app took from the account's videos for the match lists. */
    val thumbnails = File(root, "thumbnails")
}

/** What the single-account files of app 0.7 hold, for the question of the first login. */
data class LegacyContent(val titles: List<String>, val teams: Int, val recordings: Int, val draft: Boolean)

/**
 * The account folders under [filesDir], and the single-account files that app 0.7 kept directly in
 * it: volleystat.json, recordings/ and thumbnails/.
 */
class AccountFiles(private val filesDir: File) {
    private val accounts = File(filesDir, "accounts")
    val legacyStore = File(filesDir, "volleystat.json")
    private val legacyRecordings = File(filesDir, "recordings")
    private val legacyThumbnails = File(filesDir, "thumbnails")

    /** The folder of [userId]. An id that is not a plain name (a server's mistake or worse) never becomes a path. */
    fun of(userId: String): AccountSpace {
        require(SAFE_ID.matches(userId)) { "Not a usable account id" }
        return AccountSpace(File(accounts, userId))
    }

    /** Deletes everything the phone keeps for [userId]: its matches, rosters, draft, recordings and thumbnails. */
    fun delete(userId: String) {
        of(userId).root.deleteRecursively()
    }

    /** What the old files hold, or null when they hold nothing worth a question. */
    fun legacyContent(): LegacyContent? {
        val stored = if (legacyStore.isFile) LocalStore(legacyStore).load() else StoredState()
        val recordings = legacyRecordings.listFiles().orEmpty().count { it.isFile && it.length() > 0 }
        if (stored.matches.isEmpty() && stored.teams.isEmpty() && !stored.draft.started && recordings == 0) return null
        return LegacyContent(stored.matches.map(MatchRecord::title), stored.teams.size, recordings, stored.draft.started)
    }

    /**
     * Moves the old matches, rosters, draft, recordings and thumbnails into [space] and points every
     * path at its new place. What the account already holds stays: an old record joins only when
     * the account has none with its id, and the old draft only when the account has no started one.
     * The old files are gone afterwards, so no other account can find them.
     */
    fun moveLegacyInto(space: AccountSpace) {
        val old = if (legacyStore.isFile) LocalStore(legacyStore).load() else StoredState()
        val moved = moveFiles(legacyRecordings, space.recordings) + moveFiles(legacyThumbnails, space.thumbnails)
        fun place(path: String?) = path?.let { moved[File(it).absolutePath] ?: relocated(it, space) }
        fun relocate(match: MatchRecord) = match.copy(video = match.video.copy(location = place(match.video.location)!!, thumbnailPath = place(match.video.thumbnailPath)))
        val draft = old.draft.copy(
            video = old.draft.video?.let { it.copy(location = place(it.location)!!, thumbnailPath = place(it.thumbnailPath)) },
            pendingCapturePath = place(old.draft.pendingCapturePath),
        )
        val own = space.store.load()
        space.store.save(
            StoredState(
                matches = own.matches + old.matches.filter { match -> own.matches.none { it.localId == match.localId } }.map(::relocate),
                teams = own.teams + old.teams.filter { team -> own.teams.none { it.id == team.id } },
                draft = if (own.draft.started) own.draft else draft,
            )
        )
        legacyStore.delete()
        legacyRecordings.delete()
        legacyThumbnails.delete()
    }

    /** A path into an old folder whose file is not there (an empty placeholder, say) still points into the account. */
    private fun relocated(path: String, space: AccountSpace): String {
        val file = File(path).absoluteFile
        return when (file.parentFile) {
            legacyRecordings.absoluteFile -> File(space.recordings, file.name).path
            legacyThumbnails.absoluteFile -> File(space.thumbnails, file.name).path
            else -> path
        }
    }

    /** Moves every file of [from] into [to]; a name the target already holds gets a free one. Returns old path to new path. */
    private fun moveFiles(from: File, to: File): Map<String, String> {
        val files = from.listFiles().orEmpty().filter { it.isFile }
        if (files.isEmpty()) return emptyMap()
        to.mkdirs()
        return files.associate { file ->
            var target = File(to, file.name)
            var number = 1
            while (target.exists()) target = File(to, "${file.nameWithoutExtension}-${number++}.${file.extension}".removeSuffix("."))
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            file.absolutePath to target.path
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
