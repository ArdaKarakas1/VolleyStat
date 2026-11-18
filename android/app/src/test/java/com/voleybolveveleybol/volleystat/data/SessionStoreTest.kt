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

    /** The third review's A2: the temporary password is kept with the session, and a file of an earlier round reads as an ordinary password. */
    @Test fun aTemporaryPasswordIsKeptAndAnOlderFileWithoutItReadsAsAnOrdinaryPassword() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session.copy(passwordTemporary = true))
        assertTrue(SessionStore(file, cipher).load()!!.passwordTemporary)
        val older = org.json.JSONObject().put("token", session.token).put("userId", session.userId).put("username", session.username)
            .put("displayName", session.displayName).put("expiresAt", session.expiresAt).put("deletionUnanswered", false)
        file.writeBytes(cipher.seal(older.toString().toByteArray(Charsets.UTF_8)))
        assertEquals(session, SessionStore(file, cipher).load())
        assertFalse(SessionStore(file, cipher).load()!!.passwordTemporary)
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

    // The second review's R2-C: one failure of the keystore at the start of the app must not read a live
    // session as none. The store tries a failure that is no damage once more, as device.bin does.

    /** Fails the next [failures] opens with [error], and counts every open. */
    private class FlakyCipher(private val inner: SessionCipher, var failures: Int, val error: () -> Exception) : SessionCipher {
        var opens = 0
        override fun seal(plain: ByteArray) = inner.seal(plain)
        override fun open(sealed: ByteArray): ByteArray {
            opens++
            if (failures > 0) {
                failures--
                throw error()
            }
            return inner.open(sealed)
        }
    }

    private val passing = listOf<() -> Exception>(
        { javax.crypto.IllegalBlockSizeException("Keystore operation failed") },
        { java.security.InvalidKeyException("Keystore operation failed") },
        { java.security.KeyStoreException("busy") },
        { java.security.ProviderException("Keystore operation failed") },
        { GeneralSecurityException("transient") },
    )

    @Test fun oneFailedOpenIsTriedAgainAndTheSessionIsRead() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        passing.forEach { error ->
            assertEquals(error().javaClass.simpleName, session, SessionStore(file, FlakyCipher(cipher, failures = 1, error = error)).load())
        }
    }

    @Test fun aLoadThatStillFailsReadsAsNoSessionAndLeavesTheFileAsItIs() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        val before = file.readBytes()
        passing.forEach { error ->
            val flaky = FlakyCipher(cipher, failures = 2, error = error)
            assertNull(SessionStore(file, flaky).load())
            assertEquals("one more try, and no third", 2, flaky.opens)
            assertArrayEquals(before, file.readBytes())
            assertEquals("the next start reads the session", session, SessionStore(file, cipher).load())
        }
    }

    @Test fun aDamagedFileIsReadOnce() {
        val file = File(folder.root, "session.bin")
        SessionStore(file, cipher).save(session)
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val counting = FlakyCipher(cipher, failures = 0) { error("never") }
        assertNull(SessionStore(file, counting).load())
        assertEquals("a tag that does not match is damage", 1, counting.opens)
        file.writeBytes(bytes.copyOf(7))
        assertNull(SessionStore(file, counting).load())
        assertEquals("so is a file cut short", 2, counting.opens)
        file.writeBytes(cipher.seal("kein json".toByteArray()))
        assertNull(SessionStore(file, counting).load())
        assertEquals("and sealed text that is no JSON", 3, counting.opens)
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
