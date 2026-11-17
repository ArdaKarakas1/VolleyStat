package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.PlainSessionCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

/**
 * session.bin (design 10.3): the token of the signed-in account, sealed with AES-256-GCM. The
 * app's key lives in AndroidKeyStore, which the JVM does not have, so these tests seal with the
 * same AES-GCM code over a software key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }
    private val session = Session("tok-abcdefghijklmnopqrstuvwxyz0123456789", "user-1", "cagri.ozturk", "Çağrı Öztürk", "2026-12-17T10:00:00Z")

    @Test fun aSavedSessionComesBackAndTheFileHoldsNoneOfItInPlainText() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        assertEquals(session, SessionStore(file, cipher).load())
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        listOf(session.token, session.userId, session.username, "Öztürk", "token").forEach {
            assertFalse("$it must not stand in the file", text.contains(it))
        }
        assertFalse(String(bytes, Charsets.UTF_8).contains("Öztürk"))
    }

    @Test fun twoSealsOfTheSameSessionDifferBecauseEachHasItsOwnNonce() {
        val first = cipher.seal("same".toByteArray())
        val second = cipher.seal("same".toByteArray())
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals("same".toByteArray(), cipher.open(first))
        assertArrayEquals("same".toByteArray(), cipher.open(second))
    }

    @Test fun aDamagedFileReadsAsNoSession() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        val bytes = file.readBytes()
        // One flipped bit in the sealed text: the GCM tag no longer matches.
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(SessionStore(file, cipher).load())
        // Cut short, empty, or garbage: still no session, and no crash.
        file.writeBytes(bytes.copyOf(7))
        assertNull(SessionStore(file, cipher).load())
        file.writeBytes(ByteArray(0))
        assertNull(SessionStore(file, cipher).load())
        file.writeText("{\"token\": \"x\"}")
        assertNull(SessionStore(file, cipher).load())
    }

    @Test fun aFileSealedWithAnotherKeyReadsAsNoSession() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(SessionStore(file, AesGcmCipher { otherKey }).load())
    }

    @Test fun aCipherThatFailsReadsAsNoSession() {
        val file = File(folder.root, "session.bin").apply { writeBytes(ByteArray(40) { 1 }) }
        val broken = object : SessionCipher {
            override fun seal(plain: ByteArray): ByteArray = throw GeneralSecurityException("no key")
            override fun open(sealed: ByteArray): ByteArray = throw GeneralSecurityException("no key")
        }
        assertNull(SessionStore(file, broken).load())
        try {
            SessionStore(file, broken).save(session)
            fail("a session the phone can not seal must not be written")
        } catch (expected: GeneralSecurityException) {
        }
        assertEquals("the old file stays as it was", 40, file.length())
    }

    @Test fun clearRemovesTheFileAndASessionThatIsNotThereIsNoSession() {
        val file = File(folder.root, "session.bin")
        val store = SessionStore(file, cipher)
        assertNull(store.load())
        store.save(session)
        assertTrue(file.exists())
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
        store.clear()
    }

    @Test fun noTemporaryFileStaysBehind() {
        SessionStore(File(folder.root, "session.bin"), cipher).save(session)
        assertEquals(listOf("session.bin"), folder.root.list()!!.toList())
    }

    @Test fun theTokenNeverAppearsInATextThatCouldReachALog() {
        assertFalse(session.toString().contains(session.token))
        assertTrue(session.toString().contains(session.userId))
    }

    @Test fun thePlainCipherOfTheTestsWritesWhatTheStoreGaveIt() {
        // The view model tests read what the app wrote; the plain cipher must change nothing.
        val file = File(folder.root, "session.bin")
        SessionStore(file, PlainSessionCipher).save(session)
        assertEquals(session, SessionStore(file, PlainSessionCipher).load())
        assertTrue(file.readText().contains(session.username))
    }
}
