package com.voleybolveveleybol.volleystat.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the files that hold a secret or name an account (session.bin, device.bin, deletions.bin; design 10.3). */
interface SessionCipher {
    fun seal(plain: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when [sealed] was not sealed with this cipher's key or was changed since. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * A sealed blob that is not in this app's format or is cut short: nothing in it can ever be read.
 * Together with a tag that does not match (AEADBadTagException) it is the only failure of [SessionCipher.open]
 * that counts as damage; any other failure may pass (review finding S4).
 */
class SealedFileDamaged(message: String) : GeneralSecurityException(message)

/**
 * Where the view model gets its cipher. The app's Application does not implement it, so the app
 * uses [KeystoreSessionCipher]. The JVM tests have no keystore and run in an Application that
 * hands out a plain cipher.
 */
interface SessionCipherSource {
    val sessionCipher: SessionCipher
}

/**
 * AES-256-GCM with a fresh 96-bit nonce for every seal. A sealed blob is one format byte, the
 * nonce length (always 12), the nonce, then the ciphertext with its 128-bit tag. Any change to the
 * blob fails the tag check (AEADBadTagException) or the format checks here ([SealedFileDamaged]).
 */
open class AesGcmCipher(private val key: () -> SecretKey) : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks the nonce. AndroidKeyStore refuses a nonce chosen by the caller.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        if (nonce == null || nonce.size != NONCE_BYTES) throw GeneralSecurityException("The cipher gave no usable nonce")
        return byteArrayOf(FORMAT, nonce.size.toByte()) + nonce + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 2 || sealed[0] != FORMAT) throw SealedFileDamaged("Not a sealed file of this app")
        // Every seal of this app has a 12-byte nonce. Another length is damage, not a nonce that AndroidKeyStore would refuse.
        if (sealed[1].toInt() != NONCE_BYTES) throw SealedFileDamaged("The sealed file names another nonce length")
        if (sealed.size < 2 + NONCE_BYTES + TAG_BITS / 8) throw SealedFileDamaged("The sealed file is cut short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 2, NONCE_BYTES))
        return cipher.doFinal(sealed, 2 + NONCE_BYTES, sealed.size - 2 - NONCE_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val FORMAT: Byte = 1
    }
}

/**
 * The app's cipher: an AES-256 key that AndroidKeyStore creates on first use and never lets out of
 * the keystore. Uninstalling the app deletes the key; files sealed with it then read as nothing.
 *
 * A key the keystore reports as lost for good ([keystoreLostTheKey]) is deleted and made again
 * once, and the call is tried again with the new key (review finding S3). Files sealed with the old
 * key then read as nothing: the user logs in again and gets a new device token, and the plain list
 * of deletions.ids keeps the accounts in doubt. Any other keystore error keeps the key: the call is
 * tried once more with the same key and otherwise fails alone. Keystore2's getKey turns every
 * keystore error into an UnrecoverableKeyException and Cipher.init every error of the operation into
 * an InvalidKeyException, so neither type alone says anything about the key.
 */
class KeystoreSessionCipher(private val alias: String = KEY_ALIAS) : SessionCipher {
    private val aes = AesGcmCipher { keystoreKey(alias) }

    override fun seal(plain: ByteArray): ByteArray = withUsableKey { aes.seal(plain) }

    override fun open(sealed: ByteArray): ByteArray = withUsableKey { aes.open(sealed) }

    private fun <T> withUsableKey(operation: () -> T): T = synchronized(Companion) {
        val failure = try {
            return@synchronized operation()
        } catch (error: Exception) {
            error
        }
        if (!keystoreLostTheKey(failure)) {
            if (!mayPass(failure)) throw failure
            // An error that may pass: the same key once more, and otherwise this call alone fails.
            val again = try {
                return@synchronized operation()
            } catch (error: Exception) {
                error
            }
            if (!keystoreLostTheKey(again)) throw again
        }
        // The keystore can never use this key again: a new key, and the call once more.
        discard(alias)
        operation()
    }

    /** The failures of the keystore itself, as AndroidKeyStore reports them. A changed or damaged file is none of them. */
    private fun mayPass(error: Exception) =
        error is UnrecoverableKeyException || error is InvalidKeyException || error is KeyStoreException || error is ProviderException

    companion object {
        const val KEY_ALIAS = "volleystat-session-key"
        private const val PROVIDER = "AndroidKeyStore"

        private fun discard(alias: String) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }

        @Synchronized
        private fun keystoreKey(alias: String): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}

/**
 * Whether [error] shows that the keystore can never use the session key again: the key was
 * permanently invalidated, is gone, or its blob is corrupted. Only such a failure costs the key and
 * with it session.bin, device.bin and the usernames of deletions.bin.
 *
 * The framework's own isTransientFailure() is not asked: it calls SYSTEM_ERROR, BACKEND_BUSY and
 * OPERATION_BUSY not transient, and those are exactly the errors that pass. The public error code
 * (API 33 and later) names a corrupted or missing key. An invalid key blob has no public code of its
 * own, so the internal code counts where the platform lets an app read it; where it does not, the
 * reflection fails and the error counts as one that may pass.
 */
internal fun keystoreLostTheKey(error: Throwable): Boolean =
    generateSequence(error) { failure -> failure.cause?.takeIf { it !== failure } }.take(MAX_CAUSES).any { failure ->
        failure is KeyPermanentlyInvalidatedException || keystoreCodeShowsLoss(failure)
    }

private fun keystoreCodeShowsLoss(failure: Throwable): Boolean {
    if (failure.javaClass.name != "android.security.KeyStoreException") return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && failure is android.security.KeyStoreException &&
        (failure.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_CORRUPTED ||
            failure.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST)
    ) {
        return true
    }
    val internal = try {
        failure.javaClass.getMethod("getErrorCode").invoke(failure) as? Int
    } catch (hidden: ReflectiveOperationException) {
        null
    } catch (hidden: RuntimeException) {
        null
    }
    return internal in INTERNAL_CODES_OF_A_LOST_KEY
}

private const val MAX_CAUSES = 8

/** The keystore's KEY_NOT_FOUND, VALUE_CORRUPTED and KEY_PERMANENTLY_INVALIDATED, and KeyMint's INVALID_KEY_BLOB. Keystore2 kept the numbers of the keystore before it. */
private val INTERNAL_CODES_OF_A_LOST_KEY = setOf(7, 8, 17, -33)
