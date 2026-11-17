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

    @Test fun aKeystoreThatCanNotOpenTheDoubtsNowKeepsThemAll() {
        DeletionDoubtStore(file, cipher).add("user-ali", "ali")
        val flaky = FlakyCipher(cipher, failures = 2) { java.security.KeyStoreException("busy") }
        val store = DeletionDoubtStore(file, flaky)
        assertTrue(runCatching { store.add("user-veli", "veli") }.isFailure)
        flaky.failures = 2
        assertTrue(runCatching { store.remove("user-ali") }.isFailure)
        flaky.failures = 2
        assertEquals("a load that can not open the file gives nothing now", emptyList<Pair<String, String>>(), store.load())
        assertEquals(listOf("user-ali" to "ali"), DeletionDoubtStore(file, cipher).load())
    }

    private fun sha256Hex(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
