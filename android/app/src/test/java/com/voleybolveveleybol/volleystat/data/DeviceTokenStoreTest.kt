package com.voleybolveveleybol.volleystat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/**
 * device.bin (design 3.2 and 10.3): the device token of every account that logged in on this
 * phone, keyed by the SHA-256 of the username and sealed like session.bin. The token survives a
 * logout, so the next login of that account counts its failures under this phone's own key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceTokenStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }
    private val file get() = File(folder.root, "device.bin")

    @Test fun eachUsernameKeepsItsOwnTokenAcrossARestart() {
        DeviceTokenStore(file, cipher).apply {
            remember("ali", "device-ali")
            remember("veli", "device-veli")
        }
        val store = DeviceTokenStore(file, cipher)
        assertEquals("device-ali", store.tokenFor("ali"))
        assertEquals("device-veli", store.tokenFor("veli"))
        assertNull(store.tokenFor("ayse"))
    }

    @Test fun aNewTokenForTheSameUsernameReplacesTheOldOne() {
        val store = DeviceTokenStore(file, cipher)
        store.remember("ali", "first")
        store.remember("ali", "second")
        assertEquals("second", DeviceTokenStore(file, cipher).tokenFor("ali"))
    }

    @Test fun forgettingOneUsernameLeavesTheOthers() {
        val store = DeviceTokenStore(file, cipher)
        store.remember("ali", "device-ali")
        store.remember("veli", "device-veli")
        store.forget("ali")
        assertNull(DeviceTokenStore(file, cipher).tokenFor("ali"))
        assertEquals("device-veli", DeviceTokenStore(file, cipher).tokenFor("veli"))
    }

    @Test fun theFileHoldsNeitherAUsernameNorAToken() {
        DeviceTokenStore(file, cipher).remember("cagri.ozturk", "device-token-value")
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("cagri"))
        assertFalse(text.contains("device-token-value"))
    }

    @Test fun theUsernameIsKeptOnlyAsItsSha256() {
        // The plain cipher shows what the store seals: the key is the digest, never the name.
        DeviceTokenStore(file, com.voleybolveveleybol.volleystat.PlainSessionCipher).remember("ali", "device-ali")
        val text = file.readText()
        assertFalse(text.contains("\"ali\""))
        assertTrue(text.contains(sha256Hex("ali")))
        assertTrue(text.contains("device-ali"))
    }

    @Test fun aDamagedFileHoldsNoTokenAndTheNextTokenStillGetsSaved() {
        file.writeBytes(ByteArray(30) { 7 })
        val store = DeviceTokenStore(file, cipher)
        assertNull(store.tokenFor("ali"))
        store.remember("ali", "device-ali")
        assertEquals("device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
    }

    /** The real cipher, except that the next [failures] opens throw what [error] makes, as a busy keystore does. */
    private class FlakyCipher(private val inner: SessionCipher, var failures: Int = 0, val error: () -> Exception) : SessionCipher {
        override fun seal(plain: ByteArray) = inner.seal(plain)
        override fun open(sealed: ByteArray): ByteArray {
            if (failures > 0) {
                failures--
                throw error()
            }
            return inner.open(sealed)
        }
    }

    // Review finding S4: one failed read of device.bin must not cost the other accounts their tokens.

    @Test fun aKeystoreThatCanNotOpenTheFileNowKeepsEveryOtherAccountsToken() {
        listOf<() -> Exception>({ java.security.KeyStoreException("busy") }, { java.security.ProviderException("Keystore operation failed") }).forEach { busy ->
            file.delete()
            DeviceTokenStore(file, cipher).remember("ali", "device-ali")
            val flaky = FlakyCipher(cipher, failures = 2, error = busy)
            val store = DeviceTokenStore(file, flaky)
            // The store tries the file twice. Both fail, so it writes nothing: this login's token is the one that waits.
            runCatching { store.remember("veli", "device-veli") }
            assertEquals("device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
            assertNull(DeviceTokenStore(file, cipher).tokenFor("veli"))
            // A forget that can not read the file leaves it as it is too.
            flaky.failures = 2
            runCatching { store.forget("ali") }
            assertEquals("device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
            // While the keystore can not open the file, a login simply goes without a token.
            flaky.failures = 2
            assertNull(store.tokenFor("ali"))
        }
    }

    @Test fun aSingleFailedOpenIsTriedAgainAndBothTokensAreKept() {
        DeviceTokenStore(file, cipher).remember("ali", "device-ali")
        val flaky = FlakyCipher(cipher, failures = 1) { java.security.KeyStoreException("busy") }
        DeviceTokenStore(file, flaky).remember("veli", "device-veli")
        assertEquals("device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
        assertEquals("device-veli", DeviceTokenStore(file, cipher).tokenFor("veli"))
    }

    @Test fun aFileWhoseTagNoLongerMatchesIsReplacedBecauseNothingInItCanBeRead() {
        DeviceTokenStore(file, cipher).remember("ali", "device-ali")
        val bytes = file.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val store = DeviceTokenStore(file, cipher)
        assertNull(store.tokenFor("ali"))
        store.remember("veli", "device-veli")
        assertEquals("device-veli", DeviceTokenStore(file, cipher).tokenFor("veli"))
    }

    // Review finding S4, second round: only a file that is really damaged reads as empty. AndroidKeyStore throws
    // IllegalBlockSizeException from doFinal for a keystore error that is not a bad tag, and other
    // GeneralSecurityExceptions for errors that pass.

    private val passing = listOf<() -> Exception>(
        { java.security.GeneralSecurityException("transient") },
        { javax.crypto.IllegalBlockSizeException("Keystore operation failed") },
        { java.security.InvalidKeyException("Keystore operation failed") },
        { java.security.UnrecoverableKeyException("Failed to obtain information about key") },
        { java.security.InvalidAlgorithmParameterException("Keystore operation failed") },
        { java.security.ProviderException("Keystore operation failed") },
    )

    @Test fun aFailureThatIsNoDamageIsTriedAgainAndBothTokensAreKept() {
        passing.forEach { error ->
            file.delete()
            DeviceTokenStore(file, cipher).remember("ali", "device-ali")
            DeviceTokenStore(file, FlakyCipher(cipher, failures = 1, error = error)).remember("veli", "device-veli")
            val name = error().javaClass.simpleName
            assertEquals(name, "device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
            assertEquals(name, "device-veli", DeviceTokenStore(file, cipher).tokenFor("veli"))
        }
    }

    @Test fun aFailureThatIsNoDamageAndStaysLeavesTheFileAsItIsAndThrows() {
        passing.forEach { error ->
            file.delete()
            DeviceTokenStore(file, cipher).remember("ali", "device-ali")
            val before = file.readBytes()
            val flaky = FlakyCipher(cipher, failures = 2, error = error)
            val name = error().javaClass.simpleName
            assertTrue(name, runCatching { DeviceTokenStore(file, flaky).remember("veli", "device-veli") }.isFailure)
            flaky.failures = 2
            assertTrue(name, runCatching { DeviceTokenStore(file, flaky).forget("ali") }.isFailure)
            org.junit.Assert.assertArrayEquals(name, before, file.readBytes())
            assertEquals(name, "device-ali", DeviceTokenStore(file, cipher).tokenFor("ali"))
        }
    }

    @Test fun aChangedTagAnotherFormatACutShortFileAndSealedTextThatIsNoJsonAreDamage() {
        val damage = linkedMapOf<String, (ByteArray) -> ByteArray>(
            "changed tag" to { bytes -> bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() } },
            "another format" to { bytes -> bytes.copyOf().also { it[0] = 9 } },
            "another nonce length" to { bytes -> bytes.copyOf().also { it[1] = 16 } },
            "cut short" to { bytes -> bytes.copyOf(20) },
            "no JSON" to { _ -> cipher.seal("kein json".toByteArray()) },
        )
        damage.forEach { (what, change) ->
            file.delete()
            DeviceTokenStore(file, cipher).remember("ali", "device-ali")
            file.writeBytes(change(file.readBytes()))
            val store = DeviceTokenStore(file, cipher)
            assertNull(what, store.tokenFor("ali"))
            store.remember("veli", "device-veli")
            assertEquals(what, "device-veli", DeviceTokenStore(file, cipher).tokenFor("veli"))
        }
    }

    @Test fun theCipherNamesItsOwnFormatAndLengthChecksAsDamage() {
        val sealed = cipher.seal("{}".toByteArray())
        listOf(byteArrayOf(9) + sealed.copyOfRange(1, sealed.size), sealed.copyOf(20), byteArrayOf(1, 16) + sealed.copyOfRange(2, sealed.size), byteArrayOf(1)).forEach { blob ->
            try {
                cipher.open(blob)
                org.junit.Assert.fail("a damaged blob does not open")
            } catch (expected: SealedFileDamaged) {
            }
        }
    }

    // Review finding F1: deletions.bin, the accounts whose deletion is in doubt, sealed and kept like device.bin.

    @Test fun theAccountsInDoubtAreKeptNewestFirstSealedAndOneLeavesWithoutTheOthers() {
        DeletionDoubtStore(file, cipher).apply {
            add("user-ali", "ali")
            add("user-veli", "veli")
            add("user-ali", "ali")
        }
        val store = DeletionDoubtStore(file, cipher)
        assertEquals(listOf("user-ali" to "ali", "user-veli" to "veli"), store.load())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).let { it.contains("ali") || it.contains("veli") })
        store.remove("user-ali")
        assertEquals(listOf("user-veli" to "veli"), DeletionDoubtStore(file, cipher).load())
        store.remove("user-veli")
        assertFalse("nothing left, no file", file.exists())
    }

    // The checker's regression of S3 and F1: the accounts in doubt never hang on the keystore. deletions.ids names
    // them in plain text (a user id is already the plain name of the account's folder); deletions.bin keeps only
    // their usernames, sealed.

    private val ids get() = File(folder.root, "device.ids")

    @Test fun theAccountsInDoubtOutliveANewKeyAndAreKeptWithoutTheirUsernames() {
        DeletionDoubtStore(file, cipher).apply {
            add("u-1", "ali")
            add("u-2", "veli")
        }
        assertEquals("the plain list names only the folders", listOf("u-2", "u-1"), org.json.JSONObject(ids.readText()).getJSONArray("accounts").let { a -> (0 until a.length()).map(a::getString) })
        assertFalse(ids.readText().contains("ali") || ids.readText().contains("veli"))

        // The keystore lost its key: the usernames can no longer be read, the accounts in doubt still can.
        val newKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> AesGcmCipher { key } }
        val store = DeletionDoubtStore(file, newKey)
        assertEquals(listOf("u-2" to null, "u-1" to null), store.load())
        store.add("u-3", "ayse")
        assertEquals(listOf("u-3" to "ayse", "u-2" to null, "u-1" to null), DeletionDoubtStore(file, newKey).load())
        store.remove("u-2")
        assertEquals(listOf("u-3" to "ayse", "u-1" to null), DeletionDoubtStore(file, newKey).load())
        store.remove("u-3")
        store.remove("u-1")
        assertFalse("nothing left, no file", ids.exists() || file.exists())
    }

    @Test fun aDamagedFileOfUsernamesKeepsEveryAccountInDoubt() {
        DeletionDoubtStore(file, cipher).add("u-1", "ali")
        file.writeBytes(ByteArray(30) { 7 })
        assertEquals(listOf("u-1" to null), DeletionDoubtStore(file, cipher).load())
        file.delete()
        assertEquals(listOf("u-1" to null), DeletionDoubtStore(file, cipher).load())
    }

    @Test fun aKeystoreThatCanNotOpenTheUsernamesNowKeepsEveryDoubtAndANewDoubtLeavesTheSealedFileAsItIs() {
        DeletionDoubtStore(file, cipher).add("user-ali", "ali")
        val sealed = file.readBytes()
        val flaky = FlakyCipher(cipher, failures = 2) { java.security.KeyStoreException("busy") }
        val store = DeletionDoubtStore(file, flaky)
        // Veli's doubt is kept although his username can not be sealed now, and Ali's username stays sealed.
        store.add("user-veli", "veli")
        org.junit.Assert.assertArrayEquals(sealed, file.readBytes())
        assertEquals(listOf("user-veli" to null, "user-ali" to "ali"), DeletionDoubtStore(file, cipher).load())
        flaky.failures = 2
        assertEquals("a load that can not open the file gives the accounts without their usernames", listOf("user-veli" to null, "user-ali" to null), store.load())
        // A doubt that ends leaves the list even while the keystore can not open the usernames. The sealed file
        // still names ali and can not be rewritten now, so it goes (third checker's C1).
        flaky.failures = 2
        store.remove("user-ali")
        assertEquals(listOf("user-veli" to null), DeletionDoubtStore(file, cipher).load())
        assertFalse("no sealed file stays behind that still names ali", file.exists())
    }

    @Test fun theListOfAPhoneThatKeptOnlyTheSealedFileIsStillRead() {
        // A phone of the first round kept the accounts in doubt in deletions.bin only.
        val accounts = org.json.JSONArray(listOf(org.json.JSONObject().put("userId", "u-2").put("username", "veli"), org.json.JSONObject().put("userId", "u-1").put("username", "ali")))
        file.writeBytes(cipher.seal(org.json.JSONObject().put("accounts", accounts).toString().toByteArray()))
        val store = DeletionDoubtStore(file, cipher)
        assertEquals(listOf("u-2" to "veli", "u-1" to "ali"), store.load())
        store.add("u-3", "ayse")
        assertEquals(listOf("u-3" to "ayse", "u-2" to "veli", "u-1" to "ali"), DeletionDoubtStore(file, cipher).load())
        assertTrue(ids.exists())
    }

    @Test fun theListOfAPhoneThatKeptOnlyTheSealedFileIsNotReplacedWhileTheKeystoreCanNotOpenIt() {
        val accounts = org.json.JSONArray(listOf(org.json.JSONObject().put("userId", "u-1").put("username", "ali")))
        file.writeBytes(cipher.seal(org.json.JSONObject().put("accounts", accounts).toString().toByteArray()))
        val sealed = file.readBytes()
        val flaky = FlakyCipher(cipher, failures = 2) { java.security.KeyStoreException("busy") }
        assertTrue(runCatching { DeletionDoubtStore(file, flaky).add("u-2", "veli") }.isFailure)
        flaky.failures = 2
        assertTrue(runCatching { DeletionDoubtStore(file, flaky).remove("u-1") }.isFailure)
        org.junit.Assert.assertArrayEquals(sealed, file.readBytes())
        assertFalse(ids.exists())
        assertEquals(listOf("u-1" to "ali"), DeletionDoubtStore(file, cipher).load())
    }

    // The second checker's finding: a damaged deletions.ids must not drop the accounts that deletions.bin still names.

    @Test fun aDamagedPlainListIsReadFromTheSealedOneAndTheNextDoubtKeepsEveryUsername() {
        listOf("{\"version\":1,\"accou", "{\"version\":1}", "[]", "").forEach { damaged ->
            file.delete()
            ids.delete()
            DeletionDoubtStore(file, cipher).apply {
                add("user-ali", "ali")
                add("user-veli", "veli")
            }
            ids.writeText(damaged)
            assertEquals(damaged, listOf("user-veli" to "veli", "user-ali" to "ali"), DeletionDoubtStore(file, cipher).load())
            DeletionDoubtStore(file, cipher).add("user-ayse", "ayse")
            assertEquals(damaged, listOf("user-ayse" to "ayse", "user-veli" to "veli", "user-ali" to "ali"), DeletionDoubtStore(file, cipher).load())
            assertEquals(damaged, listOf("user-ayse", "user-veli", "user-ali"), org.json.JSONObject(ids.readText()).getJSONArray("accounts").let { a -> (0 until a.length()).map(a::getString) })
        }
    }

    @Test fun aDamagedPlainListIsNotReplacedWhileTheKeystoreCanNotOpenTheSealedOne() {
        DeletionDoubtStore(file, cipher).apply {
            add("user-ali", "ali")
            add("user-veli", "veli")
        }
        ids.writeText("{\"version\":1,\"accou")
        val sealed = file.readBytes()
        val flaky = FlakyCipher(cipher, failures = 2) { java.security.KeyStoreException("busy") }
        assertTrue("a new doubt can not be written now", runCatching { DeletionDoubtStore(file, flaky).add("user-ayse", "ayse") }.isFailure)
        flaky.failures = 2
        assertTrue(runCatching { DeletionDoubtStore(file, flaky).remove("user-ali") }.isFailure)
        org.junit.Assert.assertArrayEquals(sealed, file.readBytes())
        assertEquals("{\"version\":1,\"accou", ids.readText())
        assertEquals("once the keystore opens it, every account is there", listOf("user-veli" to "veli", "user-ali" to "ali"), DeletionDoubtStore(file, cipher).load())
    }

    // The third checker's C1: a damaged deletions.ids falls back to deletions.bin, so deletions.bin must never name an
    // account whose doubt already ended. Such an account is alive (it logged in again), and Telefondan Sil would
    // delete its matches and recordings.

    /** The real cipher, except that every open and seal fails while [failing] is true, as a keystore that can not work now. */
    private class Switchable(private val inner: SessionCipher, @Volatile var failing: Boolean = false) : SessionCipher {
        override fun seal(plain: ByteArray): ByteArray { if (failing) throw java.security.KeyStoreException("busy"); return inner.seal(plain) }
        override fun open(sealed: ByteArray): ByteArray { if (failing) throw java.security.KeyStoreException("busy"); return inner.open(sealed) }
    }

    @Test fun aDoubtThatEndsWhileTheKeystoreCanNotOpenTheUsernamesNeverComesBackFromTheSealedList() {
        val keystore = Switchable(cipher)
        DeletionDoubtStore(file, keystore).apply {
            add("user-ali", "ali")
            add("user-veli", "veli")
        }
        // Ali logs in again, so his doubt ends; the keystore can not work at that moment.
        keystore.failing = true
        DeletionDoubtStore(file, keystore).remove("user-ali")
        keystore.failing = false
        assertFalse("no sealed list stays behind that still names ali", file.exists())
        assertEquals("veli stays in doubt and loses only his username", listOf("user-veli" to null), DeletionDoubtStore(file, keystore).load())
        ids.writeText("{\"version\":1,\"accou")
        val afterDamage = DeletionDoubtStore(file, keystore).load()
        assertTrue("ali's ended doubt never comes back", afterDamage.none { it.first == "user-ali" })
        // The cost ADR-0119 names: with the plain list damaged as well, the phone no longer knows veli's doubt either.
        assertEquals(emptyList<Pair<String, String?>>(), afterDamage)
    }

    @Test fun aDoubtThatEndsWhileTheKeystoreCanOpenButNotSealTheUsernamesNeverComesBackFromTheSealedList() {
        DeletionDoubtStore(file, cipher).apply {
            add("user-ali", "ali")
            add("user-veli", "veli")
        }
        val sealFails = object : SessionCipher {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
            override fun open(sealed: ByteArray): ByteArray = cipher.open(sealed)
        }
        DeletionDoubtStore(file, sealFails).remove("user-ali")
        assertFalse("no sealed list stays behind that still names ali", file.exists())
        assertEquals("veli stays in doubt and loses only his username", listOf("user-veli" to null), DeletionDoubtStore(file, cipher).load())
        ids.writeText("{\"version\":1,\"accou")
        assertTrue("ali's ended doubt never comes back", DeletionDoubtStore(file, cipher).load().none { it.first == "user-ali" })
    }

    @Test fun aDoubtThatEndsLeavesTheSealedListBeforeThePlainOneSoNoMomentLeavesItOnlyInTheSealedList() {
        DeletionDoubtStore(file, cipher).apply {
            add("user-ali", "ali")
            add("user-veli", "veli")
        }
        // A process that ends between the two writes leaves the files as they were when the new sealed list was made.
        val plainListAtTheSeal = mutableListOf<String>()
        val witness = object : SessionCipher {
            override fun seal(plain: ByteArray): ByteArray { plainListAtTheSeal += ids.readText(); return cipher.seal(plain) }
            override fun open(sealed: ByteArray): ByteArray = cipher.open(sealed)
        }
        DeletionDoubtStore(file, witness).remove("user-ali")
        assertEquals(1, plainListAtTheSeal.size)
        assertTrue("deletions.ids still named ali while the sealed list was rewritten without him", plainListAtTheSeal.single().contains("user-ali"))
        assertEquals(listOf("user-veli" to "veli"), DeletionDoubtStore(file, cipher).load())
        assertFalse(ids.readText().contains("user-ali"))
    }

    // The third review's A1: registrations.bin keeps the usernames whose registration got no answer.

    private val marks get() = File(folder.root, "registrations.bin")
    private val now = 1_800_000_000_000L
    private val day = UnansweredRegistrationStore.MARK_LIFETIME_MS

    @Test fun theMarksOfLostRegistrationsAreKeptSealedByDigestAndOneLeavesWithoutTheOthers() {
        UnansweredRegistrationStore(marks, cipher).apply {
            add("cagri.ozturk", now)
            add("veli", now)
            add("veli", now)
        }
        assertEquals(setOf(sha256Hex("cagri.ozturk"), sha256Hex("veli")), UnansweredRegistrationStore(marks, cipher).load(now).keys)
        val text = String(marks.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("cagri"))
        assertFalse("the sealed file shows not even the digest", text.contains(sha256Hex("cagri.ozturk")))
        UnansweredRegistrationStore(marks, cipher).remove("cagri.ozturk")
        assertEquals(setOf(sha256Hex("veli")), UnansweredRegistrationStore(marks, cipher).load(now).keys)
        UnansweredRegistrationStore(marks, cipher).remove("ayse")
        UnansweredRegistrationStore(marks, cipher).remove("veli")
        assertFalse("the last mark takes the file with it", marks.exists())
        assertTrue(UnansweredRegistrationStore(marks, cipher).load(now).isEmpty())
    }

    @Test fun aMarkIsKeptOnlyAsTheDigestOfTheUsername() {
        UnansweredRegistrationStore(marks, com.voleybolveveleybol.volleystat.PlainSessionCipher).add("ali", now)
        val text = marks.readText()
        assertFalse(text.contains("\"ali\""))
        assertTrue(text.contains(sha256Hex("ali")))
    }

    @Test fun aKeystoreThatCanNotOpenTheMarksNowLeavesTheFileAsItIs() {
        UnansweredRegistrationStore(marks, cipher).add("ali", now)
        val before = marks.readBytes()
        val flaky = FlakyCipher(cipher, failures = 2) { java.security.KeyStoreException("busy") }
        assertTrue("an open that fails twice reads as no mark", UnansweredRegistrationStore(marks, flaky).load(now).isEmpty())
        flaky.failures = 2
        assertTrue(runCatching { UnansweredRegistrationStore(marks, flaky).add("veli", now) }.isFailure)
        flaky.failures = 2
        assertTrue(runCatching { UnansweredRegistrationStore(marks, flaky).remove("ali") }.isFailure)
        org.junit.Assert.assertArrayEquals(before, marks.readBytes())
        flaky.failures = 1
        assertEquals("one failed open is tried again", setOf(sha256Hex("ali")), UnansweredRegistrationStore(marks, flaky).load(now).keys)
    }

    @Test fun aDamagedFileOfMarksHoldsNoneAndTheNextMarkStillGetsSaved() {
        marks.writeBytes(ByteArray(30) { 7 })
        assertTrue(UnansweredRegistrationStore(marks, cipher).load(now).isEmpty())
        UnansweredRegistrationStore(marks, cipher).add("ali", now)
        assertEquals(setOf(sha256Hex("ali")), UnansweredRegistrationStore(marks, cipher).load(now).keys)
    }

    // The fourth review's D2: each mark keeps the time it was made, inside the sealed file, and lasts a day.

    @Test fun eachMarkKeepsItsTimeAndOneADayOldLeavesTheFile() {
        UnansweredRegistrationStore(marks, cipher).apply {
            add("ali", now)
            // A second mark of the same username starts its day again.
            add("ali", now + 1_000)
            add("veli", now + 3_600_000)
        }
        val store = UnansweredRegistrationStore(marks, cipher)
        assertEquals(mapOf(sha256Hex("ali") to now + 1_000, sha256Hex("veli") to now + 3_600_000), store.load(now + 3_600_000))
        assertFalse("the time stays inside the sealed file", String(marks.readBytes(), Charsets.ISO_8859_1).contains("${now + 1_000}"))
        // A day after it was made the mark of ali is gone, from the answer and from the file.
        assertEquals(mapOf(sha256Hex("veli") to now + 3_600_000), store.load(now + 1_000 + day))
        assertEquals("the file no longer holds it", mapOf(sha256Hex("veli") to now + 3_600_000), store.load(now + 3_600_000))
        assertTrue(store.load(now + 3_600_000 + day).isEmpty())
        assertFalse("the last expired mark takes the file with it", marks.exists())
    }

    @Test fun aNewMarkTakesTheExpiredOnesOutOfTheFile() {
        val plain = UnansweredRegistrationStore(marks, com.voleybolveveleybol.volleystat.PlainSessionCipher)
        plain.add("ali", now)
        plain.add("veli", now + day)
        assertFalse(marks.readText().contains(sha256Hex("ali")))
        assertTrue(marks.readText().contains(sha256Hex("veli")))
    }

    /** A clock that went back would make a mark look younger than it is: such a mark counts as expired. */
    @Test fun aMarkMadeLaterThanTheClockNowSaysCountsAsExpired() {
        UnansweredRegistrationStore(marks, cipher).add("ali", now)
        assertTrue(UnansweredRegistrationStore(marks, cipher).load(now - 1).isEmpty())
        assertFalse(marks.exists())
    }

    @Test fun anExpiredMarkTheKeystoreCanNotRewriteIsStillLeftOut() {
        UnansweredRegistrationStore(marks, cipher).apply {
            add("ali", now)
            add("veli", now + 3_600_000)
        }
        val cannotSeal = object : SessionCipher {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("the keystore can not seal now")
            override fun open(sealed: ByteArray): ByteArray = cipher.open(sealed)
        }
        val before = marks.readBytes()
        assertEquals(setOf(sha256Hex("veli")), UnansweredRegistrationStore(marks, cannotSeal).load(now + day).keys)
        org.junit.Assert.assertArrayEquals("the file stays as it was", before, marks.readBytes())
        assertEquals("the next load leaves the expired mark out again", setOf(sha256Hex("veli")), UnansweredRegistrationStore(marks, cannotSeal).load(now + day).keys)
        // When every mark has expired, the file goes without the keystore.
        assertTrue(UnansweredRegistrationStore(marks, cannotSeal).load(now + 3_600_000 + day).isEmpty())
        assertFalse(marks.exists())
    }

    /** A registrations.bin of the fourth round holds digests without times: its marks count as expired (ADR-0119). */
    @Test fun aFileOfMarksWithoutTimesReadsAsNoMarkAndLeaves() {
        marks.writeBytes(cipher.seal(org.json.JSONObject().put("usernames", org.json.JSONArray(listOf(sha256Hex("ali")))).toString().toByteArray()))
        assertTrue(UnansweredRegistrationStore(marks, cipher).load(now).isEmpty())
        assertFalse(marks.exists())
        UnansweredRegistrationStore(marks, cipher).add("veli", now)
        assertEquals(setOf(sha256Hex("veli")), UnansweredRegistrationStore(marks, cipher).load(now).keys)
    }

    private fun sha256Hex(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
