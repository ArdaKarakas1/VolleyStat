package com.voleybolveveleybol.volleystat.ui

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.voleybolveveleybol.volleystat.ui.screens.PlayerControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The range editor's hold on a player, tried with a player whose state the test sets by hand. That
 * player shows nothing and loads nothing: what is pinned here is what the hold reads from a player
 * and what it asks of one. Whether a real player behaves as this one does is tried on a device.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerControlTest {
    private class HandSetPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var playback = Player.STATE_BUFFERING
        var atMs = 0L
        var plays = false
        var speed = 1f
        var error: PlaybackException? = null
        var prepares = 0
        var released = false
        val seeks = mutableListOf<Long>()

        /** Tells the player's listeners what the test has set. */
        fun show() = invalidateState()

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("video").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(playback).setPlayerError(error)
            .setPlayWhenReady(plays, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setContentPositionMs(PositionSupplier { atMs })
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += positionMs
            atMs = positionMs
            // A player has to fetch the frame of the new place before it is ready again.
            if (playback != Player.STATE_IDLE) playback = Player.STATE_BUFFERING
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            plays = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            speed = playbackParameters.speed
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            prepares++
            error = null
            playback = Player.STATE_BUFFERING
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> {
            released = true
            return Futures.immediateVoidFuture()
        }
    }

    private val player = HandSetPlayer()
    private val control = PlayerControl(player)

    private fun playerIs(state: Int) {
        player.playback = state
        player.show()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aLookTellsWhereThePictureStandsAndWhetherItIsOnScreen() {
        player.atMs = 20_400
        control.look()
        assertEquals(20_400L, control.positionMs)
        assertFalse("the player is still fetching the frame", control.ready)
        assertFalse(control.ended)

        playerIs(Player.STATE_READY)
        control.look()
        assertTrue(control.ready)
        assertFalse(control.ended)

        // At its own end the player keeps its last frame on screen.
        playerIs(Player.STATE_ENDED)
        control.look()
        assertTrue(control.ended)
        assertTrue(control.ready)
    }

    @Test fun aMarkReadsThePositionOfThisInstantAndNotOfTheLastLook() {
        player.atMs = 20_000
        control.look()
        player.atMs = 20_080
        assertEquals(20_080L, control.positionNow())
        assertEquals("the screen shows the moment the mark was read at", 20_080L, control.positionMs)
    }

    @Test fun aSeekShowsTheNewPlaceAtOnceAndWaitsForItsPicture() {
        playerIs(Player.STATE_READY)
        control.look()
        assertTrue(control.ready)

        control.seekTo(14_000)
        assertEquals(listOf(14_000L), player.seeks)
        assertEquals(14_000L, control.positionMs)
        assertFalse("the picture of the new place is not there yet", control.ready)

        control.look()
        assertFalse(control.ready)
        playerIs(Player.STATE_READY)
        control.look()
        assertTrue(control.ready)
    }

    @Test fun aSeekAwayFromTheEndOfTheVideoIsNoLongerAtTheEnd() {
        playerIs(Player.STATE_ENDED)
        control.look()
        assertTrue(control.ended)
        control.seekTo(10_000)
        assertFalse(control.ended)
    }

    @Test fun playAndPauseAndTheSpeedReachThePlayer() {
        control.play()
        assertTrue(player.plays)
        assertTrue(control.playing)
        control.speed(true)
        assertEquals(2f, player.speed)
        control.speed(false)
        assertEquals(1f, player.speed)
        control.pause()
        assertFalse(player.plays)
        assertFalse(control.playing)
    }

    @Test fun aPlayerThatFailedIsPreparedAgainAndItsPictureIsWaitedFor() {
        playerIs(Player.STATE_READY)
        control.look()
        assertTrue(control.ready)

        // The connection breaks. A failed player stands idle and has no picture.
        player.error = PlaybackException("lost", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        playerIs(Player.STATE_IDLE)
        assertTrue(control.failed)
        assertFalse(control.ready)

        control.retry()
        assertEquals("the player reads the video again", 1, player.prepares)
        assertFalse(control.failed)
        control.look()
        assertFalse("the video is on its way, and its picture is not there yet", control.ready)

        playerIs(Player.STATE_READY)
        control.look()
        assertTrue(control.ready)
    }

    @Test fun lettingGoOfTheHoldReleasesThePlayer() {
        control.release()
        assertTrue(player.released)
    }
}
