package com.voleybolveveleybol.volleystat.data

import android.security.keystore.KeyGenParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    /**
     * Aliases whose key the keystore reports as corrupted, as Keystore2's getKey does: an
     * UnrecoverableKeyException whose cause is the keystore's own VALUE_CORRUPTED.
     */
    val unreadable = HashSet<String>()
    /** The next this many reads of a key fail with this exception, as a busy keystore does. */
    var busyReads = 0
    var busyError: () -> Exception = { KeyStoreException("Keystore is busy") }
    /** What the next reads of a key throw, one each. */
    val readFailures = ArrayDeque<() -> Exception>()
    /** What the next inits of the cipher throw, one each, as AndroidKeyStore's Cipher.init does. */
    val initFailures = ArrayDeque<() -> Exception>()
    var deletes = 0
    var generated = 0

    fun reset() {
        entries.clear(); unreadable.clear(); busyReads = 0; deletes = 0; generated = 0
        readFailures.clear(); initFailures.clear()
        busyError = { KeyStoreException("Keystore is busy") }
    }
}

/**
 * The exception the keystore service gives the framework, made by the framework's own class
 * (Robolectric runs the real android.security.KeyStoreException). [code] is the keystore's internal
 * code: a ResponseCode of Keystore2, or a negative KeyMint error.
 */
internal fun keystoreError(code: Int): Exception =
    Class.forName("android.security.KeyStoreException").getConstructor(Int::class.java, String::class.java).newInstance(code, "keystore code $code") as Exception

/** Keystore2's ResponseCode and KeyMint's ErrorCode, as android.system.keystore2 and the KeyMint HAL define them. */
internal object KeystoreCode {
    const val SYSTEM_ERROR = 4
    const val KEY_NOT_FOUND = 7
    const val VALUE_CORRUPTED = 8
    const val BACKEND_BUSY = 18
    const val OPERATION_BUSY = 19
    const val INVALID_KEY_BLOB = -33
}

/** What Keystore2's getKey throws for every keystore error but KEY_NOT_FOUND and KEY_PERMANENTLY_INVALIDATED. */
internal fun failedKeyRead(code: Int): Exception =
    UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(keystoreError(code)) }

/** What AndroidKeyStore's Cipher.init throws for a keystore error of the operation. */
internal fun failedCipherInit(code: Int): Exception = java.security.InvalidKeyException("Keystore operation failed", keystoreError(code))

class StandInKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? {
        if (Keystore.busyReads > 0) {
            Keystore.busyReads--
            throw Keystore.busyError()
        }
        Keystore.readFailures.removeFirstOrNull()?.let { throw it() }
        if (alias in Keystore.unreadable) throw failedKeyRead(KeystoreCode.VALUE_CORRUPTED)
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
        StandInKey(ByteArray(32).also { SecureRandom().nextBytes(it) }).also { Keystore.entries[alias!!] = it; Keystore.generated++ }
}

/**
 * A key that never leaves the stand-in keystore, as an AndroidKeyStore key never does: it has no
 * encoding, so only [StandInCipherSpi] can use it, and a failure there is not hidden by another provider.
 */
class StandInKey(val bytes: ByteArray) : SecretKey {
    override fun getAlgorithm() = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/** AES/GCM of the JVM, except that an init first throws what [Keystore.initFailures] holds. */
class StandInCipherSpi : javax.crypto.CipherSpi() {
    private val inner = javax.crypto.Cipher.getInstance(AesGcmCipher.TRANSFORMATION, "SunJCE")
    private fun failIfAsked() {
        Keystore.initFailures.removeFirstOrNull()?.let { throw it() }
    }
    private fun usable(key: Key): Key = (key as? StandInKey)?.let { SecretKeySpec(it.bytes, "AES") } ?: key
    override fun engineSetMode(mode: String?) {}
    override fun engineSetPadding(padding: String?) {}
    override fun engineGetBlockSize() = inner.blockSize
    override fun engineGetOutputSize(inputLen: Int) = inner.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = inner.iv
    override fun engineGetParameters(): java.security.AlgorithmParameters? = inner.parameters
    override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) { failIfAsked(); inner.init(opmode, usable(key), random) }
    override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) { failIfAsked(); inner.init(opmode, usable(key), params, random) }
    override fun engineInit(opmode: Int, key: Key, params: java.security.AlgorithmParameters?, random: SecureRandom?) { failIfAsked(); inner.init(opmode, usable(key), params, random) }
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = inner.update(input, inputOffset, inputLen)
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int) = inner.update(input, inputOffset, inputLen, output, outputOffset)
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = inner.doFinal(input, inputOffset, inputLen)
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int) = inner.doFinal(input, inputOffset, inputLen, output, outputOffset)
}

/** A JCA provider named like Android's, so [KeystoreSessionCipher] runs its own code against it on the JVM. */
class StandInKeystoreProvider : java.security.Provider("AndroidKeyStore", 1.0, "a stand-in keystore for the JVM tests") {
    init {
        put("KeyStore.AndroidKeyStore", StandInKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", StandInKeyGeneratorSpi::class.java.name)
        put("Cipher.${AesGcmCipher.TRANSFORMATION}", StandInCipherSpi::class.java.name)
    }
}

/**
 * The keystore key that seals session.bin, device.bin and deletions.bin (review finding S3). A key
 * the keystore reports as lost for good is replaced once, so the phone can keep a session again.
 * Any other keystore error keeps the key: the call is tried once more and otherwise fails alone.
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

    @Test fun aKeyTheCipherRefusesForGoodIsReplacedOnceAndTheSealSucceeds() {
        // The cipher refuses the key at init as AndroidKeyStore does when KeyMint can no longer use its blob, when the
        // key is gone, or when the keystore invalidated it.
        listOf(
            { failedCipherInit(KeystoreCode.INVALID_KEY_BLOB) },
            { failedCipherInit(KeystoreCode.KEY_NOT_FOUND) },
            { failedCipherInit(KeystoreCode.VALUE_CORRUPTED) },
            { android.security.keystore.KeyPermanentlyInvalidatedException("Key permanently invalidated") },
        ).forEachIndexed { index, refusal ->
            KeystoreSessionCipher().seal("eski".toByteArray())
            Keystore.initFailures += refusal
            val sealed = KeystoreSessionCipher().seal("yeni".toByteArray())
            assertArrayEquals("yeni".toByteArray(), KeystoreSessionCipher().open(sealed))
            assertEquals(refusal().toString(), index + 1, Keystore.deletes)
            assertEquals(refusal().toString(), index + 2, Keystore.generated)
        }
    }

    // The checker's regression of S3: Keystore2's getKey turns every keystore error but KEY_NOT_FOUND into an
    // UnrecoverableKeyException, and Cipher.init turns every error of the operation into an InvalidKeyException.
    // One error that passes must not cost the key, and with it the session, the device tokens and the doubts.

    private val passing = listOf(KeystoreCode.SYSTEM_ERROR, KeystoreCode.BACKEND_BUSY, KeystoreCode.OPERATION_BUSY)

    private fun sealedFiles(): Triple<File, File, File> {
        val files = Triple(File(folder.root, "session.bin"), File(folder.root, "device.bin"), File(folder.root, "deletions.bin"))
        SessionStore(files.first, KeystoreSessionCipher()).save(session)
        DeviceTokenStore(files.second, KeystoreSessionCipher()).remember("ali", "device-ali")
        DeletionDoubtStore(files.third, KeystoreSessionCipher()).add("user-veli", "veli")
        return files
    }

    private fun assertReadable(files: Triple<File, File, File>) {
        assertEquals(session, SessionStore(files.first, KeystoreSessionCipher()).load())
        assertEquals("device-ali", DeviceTokenStore(files.second, KeystoreSessionCipher()).tokenFor("ali"))
        assertEquals(listOf("user-veli" to "veli"), DeletionDoubtStore(files.third, KeystoreSessionCipher()).load())
    }

    @Test fun aKeystoreErrorThatPassesWhileReadingTheKeyKeepsTheKeyAndTheOldFiles() {
        val files = sealedFiles()
        passing.forEach { code ->
            // The framework does not call these errors transient, so isTransientFailure() can not tell them from a lost key.
            assertFalse((keystoreError(code) as android.security.KeyStoreException).isTransientFailure)
            Keystore.readFailures += { failedKeyRead(code) }
            assertEquals("one failed read is tried again with the same key", session, SessionStore(files.first, KeystoreSessionCipher()).load())
            Keystore.readFailures += { failedKeyRead(code) }
            Keystore.readFailures += { failedKeyRead(code) }
            try {
                KeystoreSessionCipher().seal("sonra".toByteArray())
                fail("two failed reads fail this call")
            } catch (expected: UnrecoverableKeyException) {
            }
            assertReadable(files)
        }
        // A read that fails without a cause shows nothing either.
        Keystore.readFailures += { UnrecoverableKeyException("Failed to obtain information about key") }
        assertReadable(files)
        assertEquals("the key stays", 0, Keystore.deletes)
        assertEquals(1, Keystore.generated)
    }

    @Test fun aKeystoreErrorThatPassesWhenTheCipherStartsKeepsTheKeyAndTheOldFiles() {
        val files = sealedFiles()
        passing.forEach { code ->
            Keystore.initFailures += { failedCipherInit(code) }
            assertReadable(files)
            Keystore.initFailures += { failedCipherInit(code) }
            Keystore.initFailures += { failedCipherInit(code) }
            try {
                KeystoreSessionCipher().seal("sonra".toByteArray())
                fail("two failed inits fail this call")
            } catch (expected: java.security.InvalidKeyException) {
            }
            assertReadable(files)
        }
        assertEquals("the key stays", 0, Keystore.deletes)
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
