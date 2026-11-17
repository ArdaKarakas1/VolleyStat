package com.voleybolveveleybol.volleystat.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the files that hold a secret (session.bin, device.bin; design 10.3). */
interface SessionCipher {
    fun seal(plain: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when [sealed] was not sealed with this cipher's key or was changed since. */
    fun open(sealed: ByteArray): ByteArray
}

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
 * nonce length, the nonce, then the ciphertext with its 128-bit tag. Any change to the blob fails
 * the tag check.
 */
open class AesGcmCipher(private val key: () -> SecretKey) : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks the nonce. AndroidKeyStore refuses a nonce chosen by the caller.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        if (nonce == null || nonce.isEmpty() || nonce.size > 255) throw GeneralSecurityException("The cipher gave no usable nonce")
        return byteArrayOf(FORMAT, nonce.size.toByte()) + nonce + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 2 || sealed[0] != FORMAT) throw GeneralSecurityException("Not a sealed file of this app")
        val nonceSize = sealed[1].toInt() and 0xFF
        if (nonceSize == 0 || sealed.size < 2 + nonceSize + TAG_BITS / 8) throw GeneralSecurityException("The sealed file is cut short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 2, nonceSize))
        return cipher.doFinal(sealed, 2 + nonceSize, sealed.size - 2 - nonceSize)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        private const val FORMAT: Byte = 1
    }
}

/**
 * The app's cipher: an AES-256 key that AndroidKeyStore creates on first use and never lets out of
 * the keystore. Uninstalling the app deletes the key; files sealed with it then read as nothing.
 */
class KeystoreSessionCipher(alias: String = KEY_ALIAS) : AesGcmCipher({ keystoreKey(alias) }) {
    companion object {
        const val KEY_ALIAS = "volleystat-session-key"
        private const val PROVIDER = "AndroidKeyStore"

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
