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

    private fun sha256Hex(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
