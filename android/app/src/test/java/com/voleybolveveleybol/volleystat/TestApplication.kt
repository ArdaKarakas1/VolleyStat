package com.voleybolveveleybol.volleystat

import android.app.Application
import com.voleybolveveleybol.volleystat.data.SessionCipher
import com.voleybolveveleybol.volleystat.data.SessionCipherSource

/**
 * The Application of the JVM tests (robolectric.properties). The JVM has no AndroidKeyStore, so
 * the view model seals its session files with [PlainSessionCipher] here.
 */
class TestApplication : Application(), SessionCipherSource {
    override val sessionCipher: SessionCipher get() = cipher

    companion object {
        /** What the view model seals with. A test that puts another cipher here puts [PlainSessionCipher] back when it ends. */
        @Volatile var cipher: SessionCipher = PlainSessionCipher
    }
}

/** A real AES-GCM seal whose key a test can replace, as the keystore replaces a key it lost (review finding S3). */
class ReplaceableKeyCipher : SessionCipher {
    @Volatile private var key: javax.crypto.SecretKey = newKey()
    private val aes = com.voleybolveveleybol.volleystat.data.AesGcmCipher { key }

    fun replaceKey() {
        key = newKey()
    }

    override fun seal(plain: ByteArray) = aes.seal(plain)
    override fun open(sealed: ByteArray) = aes.open(sealed)

    private fun newKey() = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/** A cipher that changes nothing. Only the tests use it, so that a test can read what the app wrote. */
object PlainSessionCipher : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}
