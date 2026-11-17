package com.voleybolveveleybol.volleystat.data

import android.security.keystore.KeyGenParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** What the stand-in keystore holds and how it misbehaves. Each test starts it afresh. */
private object Keystore {
    val entries = HashMap<String, SecretKey>()
    /** Aliases whose key the keystore can no longer read (a corrupted key blob after an update, say). */
    val unreadable = HashSet<String>()
    /** The next this many reads of a key fail with this exception, as a busy keystore does. */
    var busyReads = 0
    var busyError: () -> Exception = { KeyStoreException("Keystore is busy") }
    var deletes = 0
    var generated = 0

    fun reset() {
        entries.clear(); unreadable.clear(); busyReads = 0; deletes = 0; generated = 0
        busyError = { KeyStoreException("Keystore is busy") }
    }
}

class StandInKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? {
        if (Keystore.busyReads > 0) {
            Keystore.busyReads--
            throw Keystore.busyError()
        }
        if (alias in Keystore.unreadable) throw UnrecoverableKeyException("Failed to obtain information about key")
        return Keystore.entries[alias]
    }
    override fun engineDeleteEntry(alias: String) {
        Keystore.deletes++
        Keystore.entries.remove(alias)
        Keystore.unreadable.remove(alias)
    }
    override fun engineContainsAlias(alias: String) = alias in Keystore.entries || alias in Keystore.unreadable
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(Keystore.entries.keys.toList())
    override fun engineSize() = Keystore.entries.size
    override fun engineIsKeyEntry(alias: String?) = true
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {}
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
    override fun engineStore(stream: OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: InputStream?, password: CharArray?) {}
}

class StandInKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null
    override fun engineInit(random: SecureRandom?) {}
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) { alias = (params as KeyGenParameterSpec).keystoreAlias }
    override fun engineInit(keysize: Int, random: SecureRandom?) {}
    override fun engineGenerateKey(): SecretKey =
        SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { Keystore.entries[alias!!] = it; Keystore.generated++ }
}

/** A JCA provider named like Android's, so [KeystoreSessionCipher] runs its own code against it on the JVM. */
class StandInKeystoreProvider : java.security.Provider("AndroidKeyStore", 1.0, "a stand-in keystore for the JVM tests") {
    init {
        put("KeyStore.AndroidKeyStore", StandInKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", StandInKeyGeneratorSpi::class.java.name)
    }
}

/**
 * The keystore key that seals session.bin and device.bin (review finding S3). A key the keystore
 * can no longer use is replaced once, so the phone can keep a session again; a keystore that is
 * only busy keeps its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreSessionCipherTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun install() {
        Security.removeProvider("AndroidKeyStore")
        Security.insertProviderAt(StandInKeystoreProvider(), 1)
        Keystore.reset()
    }

    @After fun remove() {
        Security.removeProvider("AndroidKeyStore")
    }

    private val alias = KeystoreSessionCipher.KEY_ALIAS
    private val session = Session("tok-1", "user-1", "ali", "Ali", "2026-12-17T10:00:00Z")

    @Test fun aSoundKeyIsMadeOnceAndSealsAndOpens() {
        val cipher = KeystoreSessionCipher()
        val sealed = cipher.seal("merhaba".toByteArray())
        assertArrayEquals("merhaba".toByteArray(), KeystoreSessionCipher().open(sealed))
        assertEquals(1, Keystore.generated)
        assertEquals(0, Keystore.deletes)
    }

    @Test fun aKeyTheKeystoreCanNoLongerReadIsReplacedOnceAndTheSealSucceeds() {
        KeystoreSessionCipher().seal("eski".toByteArray())
        Keystore.unreadable += alias
        val sealed = KeystoreSessionCipher().seal("yeni".toByteArray())
        assertArrayEquals("yeni".toByteArray(), KeystoreSessionCipher().open(sealed))
        assertEquals(1, Keystore.deletes)
        assertEquals(2, Keystore.generated)
    }

    @Test fun aKeyTheCipherRefusesIsReplacedOnceAndTheSealSucceeds() {
        // A key that the cipher refuses at init (InvalidKeyException), as a key that the keystore made unusable.
        Keystore.entries[alias] = SecretKeySpec(ByteArray(5), "AES")
        val sealed = KeystoreSessionCipher().seal("yeni".toByteArray())
        assertArrayEquals("yeni".toByteArray(), KeystoreSessionCipher().open(sealed))
        assertEquals(1, Keystore.deletes)
        assertEquals(1, Keystore.generated)
    }

    @Test fun aBusyKeystoreKeepsItsKeyAndTheSecondTrySucceeds() {
        val before = KeystoreSessionCipher().seal("önce".toByteArray())
        listOf<() -> Exception>({ KeyStoreException("busy") }, { ProviderException("Keystore operation failed") }).forEach { busy ->
            Keystore.busyError = busy
            Keystore.busyReads = 1
            val sealed = KeystoreSessionCipher().seal("sonra".toByteArray())
            assertArrayEquals("sonra".toByteArray(), KeystoreSessionCipher().open(sealed))
        }
        assertEquals("a busy keystore never loses its key", 0, Keystore.deletes)
        assertArrayEquals("önce".toByteArray(), KeystoreSessionCipher().open(before))
    }

    @Test fun aKeystoreThatStaysBusyFailsThisCallOnlyAndKeepsTheKey() {
        val before = KeystoreSessionCipher().seal("önce".toByteArray())
        Keystore.busyReads = 2
        try {
            KeystoreSessionCipher().seal("sonra".toByteArray())
            fail("a keystore that stays busy can not seal")
        } catch (expected: KeyStoreException) {
        }
        assertEquals(0, Keystore.deletes)
        assertArrayEquals("önce".toByteArray(), KeystoreSessionCipher().open(before))
    }

    @Test fun afterAReplacementTheOldFilesReadAsNothingAndTheNextSaveIsKept() {
        val sessionFile = File(folder.root, "session.bin")
        val tokenFile = File(folder.root, "device.bin")
        SessionStore(sessionFile, KeystoreSessionCipher()).save(session)
        DeviceTokenStore(tokenFile, KeystoreSessionCipher()).remember("ali", "device-ali")
        Keystore.unreadable += alias

        assertNull(SessionStore(sessionFile, KeystoreSessionCipher()).load())
        assertNull(DeviceTokenStore(tokenFile, KeystoreSessionCipher()).tokenFor("ali"))
        SessionStore(sessionFile, KeystoreSessionCipher()).save(session)
        DeviceTokenStore(tokenFile, KeystoreSessionCipher()).remember("ali", "device-ali-2")
        assertNotNull(SessionStore(sessionFile, KeystoreSessionCipher()).load())
        assertEquals("device-ali-2", DeviceTokenStore(tokenFile, KeystoreSessionCipher()).tokenFor("ali"))
        assertEquals(1, Keystore.deletes)
    }
}
