package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What lives where on the phone (design 10.3): device.json for the phone, one folder per account
 * for its matches, rosters, draft and recordings, and the single-account files of app 0.7 that
 * the first login offers to move into an account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val files get() = folder.root
    private val legacyFile get() = File(files, "volleystat.json")

    private fun camera(path: String, thumbnail: String? = null) =
        LocalVideo(path, VideoSource.CAMERA, File(path).name, 10, "video/mp4", null, null, null, thumbnail)

    /** What app 0.7 wrote: version 2, with its settings in the same file. */
    private fun writeVersion2(matches: String = "[]", teams: String = "[]", draft: String = "{}", welcomeSeen: Boolean = true, target: String = "USB_DEVICE") =
        legacyFile.writeText(
            """{"version": 2, "settings": {"serverTarget": "$target", "displayName": "Deneme", "welcomeSeen": $welcomeSeen},
               "draft": $draft, "teams": $teams, "matches": $matches}"""
        )

    // ----- device.json -----

    @Test fun aFreshPhoneStartsWithNothingSeen() {
        assertEquals(Settings(), DeviceStore(File(files, "device.json"), legacyFile).load())
        assertFalse(Settings().welcomeSeen)
        assertFalse(Settings().accountSeen)
        assertFalse(Settings().legacyHandled)
        assertEquals(ServerTarget.EMULATOR, Settings().serverTarget)
    }

    @Test fun theDeviceSettingsSurviveARestart() {
        val settings = Settings(ServerTarget.USB_DEVICE, welcomeSeen = true, accountSeen = true, legacyHandled = true)
        DeviceStore(File(files, "device.json"), legacyFile).save(settings)
        assertEquals(settings, DeviceStore(File(files, "device.json"), legacyFile).load())
        // device.json holds no personal data and no secret.
        val json = JSONObject(File(files, "device.json").readText())
        assertEquals(setOf("version", "serverTarget", "welcomeSeen", "accountSeen", "legacyHandled"), json.keys().asSequence().toSet())
    }

    @Test fun anUpdateFromApp07KeepsTheWelcomeAndTheServerChoiceOfItsSingleFile() {
        writeVersion2(welcomeSeen = true, target = "USB_DEVICE")
        val settings = DeviceStore(File(files, "device.json"), legacyFile).load()
        assertEquals(Settings(ServerTarget.USB_DEVICE, welcomeSeen = true, accountSeen = false, legacyHandled = false), settings)
    }

    @Test fun onceWrittenDeviceJsonWinsOverTheOldFile() {
        writeVersion2(welcomeSeen = true, target = "USB_DEVICE")
        val store = DeviceStore(File(files, "device.json"), legacyFile)
        store.save(Settings(ServerTarget.EMULATOR, welcomeSeen = true, legacyHandled = true))
        assertEquals(ServerTarget.EMULATOR, store.load().serverTarget)
    }

    @Test fun anUnreadableDeviceFileReadsAsAFreshPhone() {
        File(files, "device.json").writeText("{ not json")
        assertEquals(Settings(), DeviceStore(File(files, "device.json"), legacyFile).load())
    }

    // ----- LocalStore version 3 -----

    @Test fun aVersion2FileLoadsAndIsWrittenBackAsVersion3WithoutSettings() {
        writeVersion2(
            matches = """[{"localId": "m1", "homeTeam": "Ev", "awayTeam": "Konuk", "createdAtMs": 10,
                          "video": {"location": "/data/rec.mp4", "source": "CAMERA", "fileName": "rec.mp4", "sizeBytes": 10}}]""",
            teams = """[{"id": "t1", "name": "A Takımı", "players": [{"id": "p1", "name": "Ayşe", "jersey": 7}]}]""",
            draft = """{"homeTeam": "A Takımı", "rules": {"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15}}""",
        )
        val loaded = LocalStore(legacyFile).load()
        assertEquals(listOf("m1"), loaded.matches.map { it.localId })
        assertEquals(listOf(Team("t1", "A Takımı", listOf(Player("p1", "Ayşe", 7)))), loaded.teams)
        assertEquals(MatchRules(2, 21, 15), loaded.draft.rules)

        val target = File(files, "v3.json")
        LocalStore(target).save(loaded)
        val json = JSONObject(target.readText())
        assertEquals(3, json.getInt("version"))
        assertFalse("settings live in device.json from version 3 on", json.has("settings"))
        assertEquals(loaded, LocalStore(target).load())
    }

    // ----- account folders -----

    @Test fun everyAccountHasItsOwnFolder() {
        val folders = AccountFiles(files)
        val a = folders.of("user-a")
        val b = folders.of("user-b")
        assertEquals(File(files, "accounts/user-a/volleystat.json"), a.storeFile)
        assertEquals(File(files, "accounts/user-a/recordings"), a.recordings)
        assertEquals(File(files, "accounts/user-b/recordings"), b.recordings)
        a.store.save(StoredState(teams = listOf(Team("t1", "A Takımı"))))
        assertEquals(StoredState(), b.store.load())
        assertEquals(listOf(Team("t1", "A Takımı")), folders.of("user-a").store.load().teams)
    }

    @Test fun anIdThatIsNotAPlainNameNeverBecomesAFolder() {
        val folders = AccountFiles(files)
        listOf("", "..", "../x", "a/b", "a\\b", ".", "x".repeat(65), "ü").forEach { id ->
            try {
                folders.of(id)
                fail("'$id' must not name a folder")
            } catch (expected: IllegalArgumentException) {
            }
        }
        folders.of("0f8fad5b-d9cb-469f-a165-70867728950e")
        folders.of("a1B2_c3")
    }

    @Test fun deletingAnAccountRemovesItsFolderAndNothingElse() {
        val folders = AccountFiles(files)
        val a = folders.of("user-a")
        val b = folders.of("user-b")
        a.recordings.mkdirs()
        File(a.recordings, "kayit-1.mp4").writeBytes(ByteArray(5))
        a.store.save(StoredState(teams = listOf(Team("t1", "A"))))
        b.store.save(StoredState(teams = listOf(Team("t2", "B"))))
        folders.delete("user-a")
        assertFalse(File(files, "accounts/user-a").exists())
        assertEquals(listOf(Team("t2", "B")), b.store.load().teams)
    }

    // ----- the single-account data of app 0.7 -----

    @Test fun nothingOldMeansNoQuestion() {
        val folders = AccountFiles(files)
        assertNull(folders.legacyContent())
        // An empty file and an empty recordings folder ask nothing either.
        writeVersion2()
        File(files, "recordings").mkdirs()
        File(File(files, "recordings"), "bos.mp4").writeBytes(ByteArray(0))
        assertNull(folders.legacyContent())
    }

    @Test fun theQuestionListsTheTitlesOfTheOldMatches() {
        writeVersion2(
            matches = """[
              {"localId": "m1", "homeTeam": "Ev", "awayTeam": "Konuk", "createdAtMs": 10, "name": "Final",
               "video": {"location": "/a.mp4", "source": "GALLERY", "fileName": "a.mp4", "sizeBytes": 10}},
              {"localId": "m2", "homeTeam": "Bir", "awayTeam": "İki", "createdAtMs": 20,
               "video": {"location": "/b.mp4", "source": "GALLERY", "fileName": "b.mp4", "sizeBytes": 10}}]""",
            teams = """[{"id": "t1", "name": "A Takımı"}]""",
        )
        val content = AccountFiles(files).legacyContent()!!
        assertEquals(listOf("Final", "Bir - İki"), content.titles)
        assertEquals(1, content.teams)
        assertEquals(0, content.recordings)
        assertFalse(content.draft)
    }

    @Test fun recordingsAloneOrADraftAloneAreAlsoOldData() {
        File(files, "recordings").mkdirs()
        File(File(files, "recordings"), "kayit-1.mp4").writeBytes(ByteArray(5))
        assertEquals(1, AccountFiles(files).legacyContent()!!.recordings)
        File(File(files, "recordings"), "kayit-1.mp4").delete()
        writeVersion2(draft = """{"name": "Yarım kalan maç"}""")
        assertTrue(AccountFiles(files).legacyContent()!!.draft)
    }

    @Test fun movingPutsTheOldMatchesRecordingsAndThumbnailsIntoTheAccountAndPointsThePathsThere() {
        val oldRecordings = File(files, "recordings").apply { mkdirs() }
        val oldThumbnails = File(files, "thumbnails").apply { mkdirs() }
        val recording = File(oldRecordings, "kayit-1.mp4").apply { writeBytes(ByteArray(10) { 1 }) }
        val loose = File(oldRecordings, "kayit-2.mp4").apply { writeBytes(ByteArray(10) { 2 }) }
        val thumbnail = File(oldThumbnails, "thumb-1.jpg").apply { writeBytes(ByteArray(3)) }
        LocalStore(legacyFile).save(
            StoredState(
                matches = listOf(MatchRecord("m1", "Ev", "Konuk", 10, camera(recording.path, thumbnail.path))),
                teams = listOf(Team("t1", "A Takımı", listOf(Player("p1", "Ayşe", 7)))),
                draft = Draft(homeTeam = "A Takımı", video = camera(loose.path), pendingCapturePath = File(oldRecordings, "kayit-3.mp4").path),
            )
        )
        val folders = AccountFiles(files)
        val space = folders.of("user-a")
        folders.moveLegacyInto(space)

        val moved = space.store.load()
        val match = moved.matches.single()
        assertEquals(File(space.recordings, "kayit-1.mp4").path, match.video.location)
        assertEquals(File(space.thumbnails, "thumb-1.jpg").path, match.video.thumbnailPath)
        assertEquals(File(space.recordings, "kayit-2.mp4").path, moved.draft.video!!.location)
        assertEquals(File(space.recordings, "kayit-3.mp4").path, moved.draft.pendingCapturePath)
        assertEquals(listOf(Team("t1", "A Takımı", listOf(Player("p1", "Ayşe", 7)))), moved.teams)
        assertEquals(10, File(space.recordings, "kayit-1.mp4").length())
        assertEquals(10, File(space.recordings, "kayit-2.mp4").length())
        assertTrue(File(space.thumbnails, "thumb-1.jpg").exists())
        // Nothing of the old data stays where another account could find it.
        assertFalse(legacyFile.exists())
        assertFalse(recording.exists())
        assertFalse(loose.exists())
        assertNull(folders.legacyContent())
    }

    @Test fun movingKeepsWhatTheAccountAlreadyHasAndAddsEachOldRecordOnce() {
        LocalStore(legacyFile).save(
            StoredState(
                matches = listOf(MatchRecord("m1", "Ev", "Konuk", 10, camera("/elsewhere/a.mp4")), MatchRecord("m2", "Bir", "İki", 20, camera("/elsewhere/b.mp4"))),
                teams = listOf(Team("t1", "Eski")),
                draft = Draft(homeTeam = "Eski taslak"),
            )
        )
        val folders = AccountFiles(files)
        val space = folders.of("user-a")
        space.store.save(
            StoredState(
                matches = listOf(MatchRecord("m1", "Hesaptaki", "Kayıt", 30, camera("/elsewhere/c.mp4"))),
                teams = listOf(Team("t1", "Hesaptaki")),
                draft = Draft(homeTeam = "Hesabın taslağı"),
            )
        )
        folders.moveLegacyInto(space)
        val merged = space.store.load()
        assertEquals(listOf("m1", "m2"), merged.matches.map { it.localId })
        assertEquals("Hesaptaki", merged.matches.first().homeTeam)
        assertEquals(listOf("Hesaptaki"), merged.teams.map { it.name })
        assertEquals("the account's own unfinished setup stays", "Hesabın taslağı", merged.draft.homeTeam)
        // A gallery video stays where it is: only the app's own folders move.
        assertEquals("/elsewhere/b.mp4", merged.matches[1].video.location)
    }

    @Test fun aRecordingWhoseNameIsTakenInTheAccountIsMovedUnderAnotherName() {
        val oldRecordings = File(files, "recordings").apply { mkdirs() }
        val recording = File(oldRecordings, "kayit-1.mp4").apply { writeBytes(ByteArray(4) { 4 }) }
        LocalStore(legacyFile).save(StoredState(matches = listOf(MatchRecord("m1", "Ev", "Konuk", 10, camera(recording.path)))))
        val folders = AccountFiles(files)
        val space = folders.of("user-a")
        space.recordings.mkdirs()
        File(space.recordings, "kayit-1.mp4").writeBytes(ByteArray(9) { 9 })
        folders.moveLegacyInto(space)
        val location = space.store.load().matches.single().video.location
        assertTrue(location.startsWith(space.recordings.path))
        assertEquals(4, File(location).length())
        assertEquals("the account's own recording is untouched", 9, File(space.recordings, "kayit-1.mp4").length())
    }
}
