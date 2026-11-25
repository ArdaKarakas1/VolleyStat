package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.model.Schedule
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.TeamRole
import com.voleybolveveleybol.volleystat.model.TeamRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * Slice B4 against a running VolleyStat backend (server 0.10): the app's team calls, the captain limit, the deletion
 * and the name rule the app describes (plan D6). Skipped unless VOLLEYSTAT_E2E_URL is set; the server runs with
 * VOLLEYSTAT_SIGNUP=open, as CI starts it. Each test opens its own e2e_ account and deletes it at the end, which also
 * deletes the teams it is the only member of (design 3.7, 14.2).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TeamE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val opened = mutableListOf<Pair<SignedIn, String>>()

    @Before fun needsABackend() = assumeTrue("needs VOLLEYSTAT_E2E_URL", url != null)

    @After fun deleteTheAccounts() = opened.forEach { (account, password) -> TestAccounts.deleteOnBackend(url!!, account, password) }

    private fun account(): ApiClient {
        val (account, password) = TestAccounts.registerOnBackend(url!!)
        opened += account to password
        return ApiClient(url, token = account.token)
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun refusal(call: () -> Unit): TeamProblem = try {
        call()
        fail("the server took it")
        throw AssertionError()
    } catch (error: ApiException) {
        TeamProblem.of(error)
    }

    @Test fun aTeamIsCreatedReadAndItsSubstituteSeatsChangeOnTheRealServer() {
        val api = account()
        val id = newId()
        val (created, isNew) = api.putTeam(id, "E2E Kuzey Spor", TeamRules.DEFAULT_SUBSTITUTES, Seat.STARTER, Schedule(2, "20:00"), 7)
        assertTrue(isNew)
        assertEquals(id, created.id)
        assertEquals(TeamRole.CAPTAIN, created.myRole)
        assertEquals(7, created.myJersey)
        assertEquals(4, created.tag.length)
        assertEquals(TeamRules.STARTERS, created.seats.starter.seats)
        assertEquals(1, created.seats.starter.taken)
        assertEquals(TeamRules.DEFAULT_SUBSTITUTES, created.seats.substitute.seats)
        assertEquals("nobody joined after the captain", 0, created.newJoins)
        assertNull(created.logoVersion)
        // The repeat of a creation whose answer was lost is no second team (design 9.6).
        val (again, againNew) = api.putTeam(id, "E2E Kuzey Spor", TeamRules.DEFAULT_SUBSTITUTES, Seat.STARTER, Schedule(2, "20:00"), 7)
        assertFalse(againNew)
        assertEquals(created.tag, again.tag)
        assertEquals(listOf(id), api.teams().map { it.id })

        val changed = api.teamSeats(id, 4, mapOf(created.members.single().userId to Seat.SUBSTITUTE))
        assertEquals(4, changed.seats.substitute.seats)
        assertEquals(Seat.SUBSTITUTE, changed.mySeat)
        assertEquals(0, changed.seats.starter.taken)
        assertEquals(changed, api.team(id))
        // The server's range of the substitute count is the app's.
        assertEquals(TeamProblem.SeatsInvalid(SeatsInvalidReason.SUBSTITUTES_RANGE), refusal { api.teamSeats(id, TeamRules.SUBSTITUTES_MAX + 1, emptyMap()) })
        // The captain marks the joins seen: nothing to mark, nothing refused.
        api.joinsSeen(id, created.members.single().joinedAt)
    }

    @Test fun aFourthCaptaincyIsRefusedWithTheServersLimit() {
        val api = account()
        repeat(3) { api.putTeam(newId(), "E2E Takım ${it + 1}", 2, Seat.STARTER, null, null) }
        val refused = refusal { api.putTeam(newId(), "E2E Takım 4", 2, Seat.STARTER, null, null) }
        assertEquals(TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3), refused)
        assertEquals(3, api.teams().size)
    }

    @Test fun aDeletedTeamReadsAsGone() {
        val api = account()
        val id = newId()
        api.putTeam(id, "E2E Silinecek", 2, Seat.STARTER, null, null)
        api.deleteTeam(id)
        assertEquals(TeamProblem.Gone, refusal { api.team(id) })
        assertTrue(api.teams().isEmpty())
        // Another account never reads a team it is not a member of.
        val other = account()
        val kept = newId()
        api.putTeam(kept, "E2E Kalan", 2, Seat.STARTER, null, null)
        assertEquals(TeamProblem.NoAccess, refusal { other.team(kept) })
        assertEquals(TeamProblem.IdTaken, refusal { other.putTeam(kept, "E2E Kalan", 2, Seat.STARTER, null, null) })
    }

    @Test fun theServerTakesTheTeamNamesTheAppDescribes() {
        val api = account()
        // Three creations at most, so the day's count of the account holds (design 3.6).
        api.putTeam(newId(), "K".repeat(TeamRules.NAME_MAX), 2, Seat.STARTER, null, null)
        api.putTeam(newId(), "Kuzey Spor & Co.", 2, Seat.STARTER, null, null)
        assertEquals(TeamProblem.NameInvalid, refusal { api.putTeam(newId(), "K".repeat(TeamRules.NAME_MAX + 1), 2, Seat.STARTER, null, null) })
        assertEquals(TeamProblem.NameInvalid, refusal { api.putTeam(newId(), "K".repeat(TeamRules.NAME_MIN - 1), 2, Seat.STARTER, null, null) })
        assertEquals(setOf("K".repeat(TeamRules.NAME_MAX), "Kuzey Spor & Co."), api.teams().map { it.name }.toSet())
    }

    /**
     * Plan P7 on the real server: the logo that the platform's encoder (Skia, which the test runtime runs) writes and the
     * app rewrites as a simple lossy WebP is taken with 200 and read back byte for byte; Logoyu Kaldır; the own jersey;
     * the captain's empty Çıkarılanlar and the transfer to oneself (plan U29); a sole captain's leave deletes the team
     * (plan U8). Removal and a transfer to another member need a second member, who can join only from slice B5 on.
     */
    @Test fun theLogoAndTheMemberCallsOfTheAppWorkOnTheRealServer() {
        val api = account()
        val id = newId()
        api.putTeam(id, "E2E Logo Spor", 2, Seat.STARTER, null, null)
        val pattern = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint()
            listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE).forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(index * 213f, 0f, (index + 1) * 213f, 480f, paint)
            }
        }
        val (large, small) = LogoCodec.encode(pattern)
        val version = api.putLogo(id, large, small)
        assertTrue(version >= 1)
        assertEquals(version, api.team(id).logoVersion)
        assertTrue(large.contentEquals(api.logo(id, "large")))
        assertTrue(small.contentEquals(api.logo(id, "small")))
        // The encoder's own file, with its VP8X header and colour profile, is what the server refuses: the rewrite is needed.
        val raw = java.io.ByteArrayOutputStream().also { LogoCodec.square(pattern, LogoCodec.LARGE).compress(LogoCodec.format(), 80, it) }.toByteArray()
        assertEquals("VP8X", String(raw, 12, 4, Charsets.US_ASCII))
        assertEquals(TeamProblem.LogoInvalid("large"), refusal { api.putLogo(id, raw, small) })
        assertEquals(version, api.team(id).logoVersion)
        api.deleteLogo(id)
        assertNull(api.team(id).logoVersion)
        assertNull(api.logo(id, "large"))

        val me = api.me().id
        assertEquals(9, api.setJersey(id, me, 9).jersey)
        assertEquals(9, api.team(id).myJersey)
        assertNull(api.setJersey(id, me, null).jersey)
        assertTrue(api.blocks(id).isEmpty())
        assertEquals(TeamRole.CAPTAIN, api.transferCaptain(id, me).myRole)
        // Another account reads no Çıkarılanlar of a team it is not a member of.
        assertEquals(TeamProblem.NoAccess, refusal { account().blocks(id) })

        // The sole captain's own leave deletes the team, as Takımı Sil does.
        api.removeMember(id, me)
        assertEquals(TeamProblem.Gone, refusal { api.team(id) })
    }
}
