package com.voleybolveveleybol.volleystat

import android.app.Application
import com.voleybolveveleybol.volleystat.data.SessionCipher
import com.voleybolveveleybol.volleystat.data.SessionCipherSource

/**
 * The Application of the JVM tests (robolectric.properties). The JVM has no AndroidKeyStore, so
 * the view model seals its session files with [PlainSessionCipher] here.
 */
class TestApplication : Application(), SessionCipherSource {
    override val sessionCipher: SessionCipher get() = PlainSessionCipher
}

/** A cipher that changes nothing. Only the tests use it, so that a test can read what the app wrote. */
object PlainSessionCipher : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}
