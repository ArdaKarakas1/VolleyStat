package com.voleybolveveleybol.volleystat.data

import android.security.keystore.KeyGenParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    /** What the next finishes of the cipher throw, one each, as AndroidKeyStore's doFinal does. */
    val finishFailures = ArrayDeque<() -> Exception>()
    /** How often the cipher was asked to finish an operation. */
    var finishes = 0
    var deletes = 0
    var generated = 0

    fun reset() {
        entries.clear(); unreadable.clear(); busyReads = 0; deletes = 0; generated = 0; finishes = 0
        readFailures.clear(); initFailures.clear(); finishFailures.clear()
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
    /** Keystore2's KEY_PERMANENTLY_INVALIDATED, which the framework turns into a KeyPermanentlyInvalidatedException on API 33 and later. */
    const val KEY_PERMANENTLY_INVALIDATED = 17
    /** KeyMint's INVALID_OPERATION_HANDLE: Keystore2 pruned the operation between its start and its finish. */
    const val INVALID_OPERATION_HANDLE = -28
    /** KeyMint's VERIFICATION_FAILED: the tag of a GCM blob does not match. */
    const val VERIFICATION_FAILED = -30
    const val INVALID_KEY_BLOB = -33
}

/** What Keystore2's getKey throws for every keystore error but KEY_NOT_FOUND and KEY_PERMANENTLY_INVALIDATED. */
internal fun failedKeyRead(code: Int): Exception =
    UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(keystoreError(code)) }

/** What AndroidKeyStore's Cipher.init throws for a keystore error of the operation. */
internal fun failedCipherInit(code: Int): Exception = java.security.InvalidKeyException("Keystore operation failed", keystoreError(code))

/** What AndroidKeyStore's doFinal throws for every keystore error but INVALID_ARGUMENT and VERIFICATION_FAILED. */
internal fun failedFinish(code: Int): Exception = javax.crypto.IllegalBlockSizeException().apply { initCause(keystoreError(code)) }

/** What AndroidKeyStore's doFinal throws when the tag does not match: VERIFICATION_FAILED, as the cause of an AEADBadTagException. */
internal fun tagThatDoesNotMatch(): Exception = javax.crypto.AEADBadTagException().apply { initCause(keystoreError(KeystoreCode.VERIFICATION_FAILED)) }

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

/** AES/GCM of the JVM, except that an init first throws what [Keystore.initFailures] holds and a finish what [Keystore.finishFailures] holds. */
class StandInCipherSpi : javax.crypto.CipherSpi() {
    private val inner = javax.crypto.Cipher.getInstance(AesGcmCipher.TRANSFORMATION, "SunJCE")
    private fun failIfAsked() {
        Keystore.initFailures.removeFirstOrNull()?.let { throw it() }
    }
    private fun failFinishIfAsked() {
        Keystore.finishes++
        Keystore.finishFailures.removeFirstOrNull()?.let { throw it() }
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
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray { failFinishIfAsked(); return inner.doFinal(input, inputOffset, inputLen) }
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int { failFinishIfAsked(); return inner.doFinal(input, inputOffset, inputLen, output, outputOffset) }
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

    @Test fun aKeyThatOnlyTheSecondTryShowsLostIsReplacedOnceAndTheSealSucceeds() {
        // The first failure may pass, so the same key is tried once more; that try shows the key is lost for good.
        listOf<Pair<() -> Exception, () -> Exception>>(
            { failedKeyRead(KeystoreCode.SYSTEM_ERROR) } to { android.security.keystore.KeyPermanentlyInvalidatedException("Key permanently invalidated") },
            { failedKeyRead(KeystoreCode.BACKEND_BUSY) } to { failedKeyRead(KeystoreCode.VALUE_CORRUPTED) },
        ).forEachIndexed { index, (passing, lost) ->
            KeystoreSessionCipher().seal("eski".toByteArray())
            Keystore.readFailures += passing
            Keystore.readFailures += lost
            val sealed = KeystoreSessionCipher().seal("yeni".toByteArray())
            assertArrayEquals("yeni".toByteArray(), KeystoreSessionCipher().open(sealed))
            assertEquals(lost().toString(), index + 1, Keystore.deletes)
            assertEquals(lost().toString(), index + 2, Keystore.generated)
        }
    }

    // The second review's R2-C: AndroidKeyStore's doFinal wraps a keystore error in an IllegalBlockSizeException (an
    // operation Keystore2 pruned, SYSTEM_ERROR, OPERATION_BUSY). Such an error may pass like any other keystore error.

    private val failingFinishes = listOf(KeystoreCode.INVALID_OPERATION_HANDLE, KeystoreCode.SYSTEM_ERROR, KeystoreCode.OPERATION_BUSY)

    @Test fun aKeystoreErrorAtTheFinishIsTriedOnceMoreWithTheSameKey() {
        val files = sealedFiles()
        failingFinishes.forEach { code ->
            Keystore.finishes = 0
            Keystore.finishFailures += { failedFinish(code) }
            val sealed = KeystoreSessionCipher().seal("sonra".toByteArray())
            assertEquals("$code: one more finish with the same key", 2, Keystore.finishes)
            assertArrayEquals("sonra".toByteArray(), KeystoreSessionCipher().open(sealed))
            // At the start of the app: one failed finish does not read the live session as none.
            Keystore.finishFailures += { failedFinish(code) }
            assertEquals("$code", session, SessionStore(files.first, KeystoreSessionCipher()).load())
            Keystore.finishFailures += { failedFinish(code) }
            Keystore.finishFailures += { failedFinish(code) }
            try {
                KeystoreSessionCipher().seal("sonra".toByteArray())
                fail("two failed finishes fail this call")
            } catch (expected: javax.crypto.IllegalBlockSizeException) {
            }
            assertReadable(files)
        }
        assertEquals("the key stays", 0, Keystore.deletes)
        assertEquals(1, Keystore.generated)
    }

    @Test fun aTagThatDoesNotMatchStaysDamageAndIsNotTriedAgain() {
        val files = sealedFiles()
        Keystore.finishes = 0
        Keystore.finishFailures += { tagThatDoesNotMatch() }
        try {
            KeystoreSessionCipher().open(files.first.readBytes())
            fail("a tag that does not match opens nothing")
        } catch (expected: javax.crypto.AEADBadTagException) {
        }
        assertEquals("a damaged file is read once", 1, Keystore.finishes)
        Keystore.finishes = 0
        Keystore.finishFailures += { tagThatDoesNotMatch() }
        assertNull("session.bin reads as no session", SessionStore(files.first, KeystoreSessionCipher()).load())
        assertEquals("the store does not read a damaged file again either", 1, Keystore.finishes)
        assertEquals(0, Keystore.deletes)
        assertReadable(files)
    }

    // The second review's problem 4: a phone may refuse the reflective read of the internal code. On API 33 and later
    // the public code alone must then name a corrupted or missing key; below API 33 only the internal code can.

    /** Runs [block] with the internal-code reader refusing, as a platform that hides getErrorCode() does. */
    private fun withTheInternalCodeHidden(block: () -> Unit) {
        val reflective = readInternalKeystoreCode
        readInternalKeystoreCode = { throw NoSuchMethodException("getErrorCode is hidden on this platform") }
        try {
            block()
        } finally {
            readInternalKeystoreCode = reflective
        }
    }

    /** Runs [block] as a phone of API [sdk] would. Robolectric's framework classes stay those of API 35. */
    private fun onApi(sdk: Int, block: () -> Unit) {
        val real = android.os.Build.VERSION.SDK_INT
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
        try {
            block()
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", real)
        }
    }

    /**
     * Seals once with a sound key, then once more with one [refusal] in [where] (the key read, the
     * cipher's init or its finish), and says whether the keystore's key was replaced. The seal
     * succeeds either way: with a new key, or with the same key on its second try.
     */
    private fun replacesTheKey(where: ArrayDeque<() -> Exception> = Keystore.initFailures, refusal: () -> Exception): Boolean {
        KeystoreSessionCipher().seal("eski".toByteArray())
        val deletes = Keystore.deletes
        where += refusal
        val sealed = KeystoreSessionCipher().seal("yeni".toByteArray())
        assertArrayEquals("yeni".toByteArray(), KeystoreSessionCipher().open(sealed))
        return Keystore.deletes > deletes
    }

    @Test fun withTheInternalCodeHiddenThePublicCodeStillReplacesACorruptedOrMissingKey() {
        withTheInternalCodeHidden {
            listOf(KeystoreCode.VALUE_CORRUPTED, KeystoreCode.KEY_NOT_FOUND).forEach { code ->
                assertTrue("$code at init", replacesTheKey { failedCipherInit(code) })
                assertTrue("$code at the key read", replacesTheKey(Keystore.readFailures) { failedKeyRead(code) })
                assertTrue("$code at the finish", replacesTheKey(Keystore.finishFailures) { failedFinish(code) })
            }
            passing.forEach { code -> assertFalse("$code passes", replacesTheKey { failedCipherInit(code) }) }
            // ADR-0119's limit: an invalid key blob has no public code, so such a platform keeps that key.
            assertFalse(replacesTheKey { failedCipherInit(KeystoreCode.INVALID_KEY_BLOB) })
        }
    }

    @Test fun belowApi33OnlyTheInternalCodeNamesALostKey() {
        onApi(32) {
            listOf(KeystoreCode.KEY_NOT_FOUND, KeystoreCode.VALUE_CORRUPTED, KeystoreCode.KEY_PERMANENTLY_INVALIDATED, KeystoreCode.INVALID_KEY_BLOB).forEach { code ->
                assertTrue("$code", keystoreLostTheKey(failedCipherInit(code)))
                assertTrue("$code replaces the key", replacesTheKey { failedCipherInit(code) })
            }
            passing.forEach { code -> assertFalse("$code passes", replacesTheKey { failedCipherInit(code) }) }
            // There is no public code below API 33: the app never asks for one, so a hidden internal code shows nothing.
            withTheInternalCodeHidden {
                assertFalse(keystoreLostTheKey(failedCipherInit(KeystoreCode.VALUE_CORRUPTED)))
                assertFalse(replacesTheKey { failedCipherInit(KeystoreCode.VALUE_CORRUPTED) })
            }
        }
        assertEquals(35, android.os.Build.VERSION.SDK_INT)
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
