package com.voleybolveveleybol.volleystat.ui.screens

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.voleybolveveleybol.volleystat.R
import kotlinx.coroutines.delay

/**
 * Plays one part of the uploaded video in a loop, without sound. The whole video stays on the
 * server; the player asks it for the byte ranges it needs. A tap restarts the clip.
 */
@Composable
fun ClipPlayer(url: String, startMs: Long, endMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val description = stringResource(R.string.review_clip_description)
    val replay = stringResource(R.string.review_replay)
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(url, startMs, endMs) {
        failed = false
        player.setMediaItem(
            MediaItem.Builder().setUri(url)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build()
        )
        player.prepare()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { player.play() }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(stringResource(R.string.review_clip_failed), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        } else {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; this.player = player } },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description }
                    .clickable(onClickLabel = replay) { player.seekTo(0); player.play() },
            )
        }
    }
}

/**
 * The range editor's hold on a player: the whole video, prepared once and then only moved through.
 * It takes any player, so a test can put one in whose state it sets by hand.
 */
internal class PlayerControl(val player: Player) : VideoControl {
    override var positionMs by mutableLongStateOf(0L)
        private set
    override var playing by mutableStateOf(false)
        private set
    override var ended by mutableStateOf(false)
        private set
    override var failed by mutableStateOf(false)
        private set
    override var ready by mutableStateOf(false)
        private set

    init {
        player.addListener(object : Player.Listener {
            // A player that failed stands idle and shows no picture.
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                ready = false
            }
        })
    }

    override fun positionNow(): Long = player.currentPosition.also { positionMs = it }

    // Seek parameters are a part of Media3 that may change between releases. The library version is pinned.
    @OptIn(UnstableApi::class)
    override fun seekTo(positionMs: Long, exact: Boolean) {
        // A key frame can be shown at once. The exact frame has to be decoded from the key frame before it.
        (player as? ExoPlayer)?.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(positionMs)
        this.positionMs = positionMs
        ended = false
        // The picture of the new place is not there yet. The next look that finds it says so.
        ready = false
    }

    override fun play() {
        player.play()
        playing = true
    }

    override fun pause() {
        player.pause()
        playing = false
    }

    override fun speed(fast: Boolean) = player.setPlaybackSpeed(if (fast) 2f else 1f)

    /** A failed player stands idle. Preparing it again reads the video from where the picture stood. */
    override fun retry() {
        failed = false
        player.prepare()
    }

    /** Looks at the player. Where playback has to stop is the editor's rule, not the player's. */
    fun look() {
        positionMs = player.currentPosition
        ended = player.playbackState == Player.STATE_ENDED
        // A player that is ready has the frame of its position on its surface, and one that ended keeps its last frame.
        ready = player.playbackState == Player.STATE_READY || ended
    }

    /** A released player drops its listeners itself. */
    fun release() = player.release()
}

/**
 * The video of the range editor, without sound, playing from [startMs]. It is prepared once for
 * the whole file, so moving through it asks the server only for the bytes of the new position.
 */
@Composable
fun rememberEditorVideo(url: String, startMs: Long): EditorVideo {
    val context = LocalContext.current
    val control = remember(url) {
        PlayerControl(ExoPlayer.Builder(context).build().apply { volume = 0f }).apply {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            seekTo(startMs, exact = true)
            play()
        }
    }
    DisposableEffect(control) { onDispose { control.release() } }
    LaunchedEffect(control) {
        while (true) {
            control.look()
            delay(50)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { control.pause() }
    val description = stringResource(R.string.editor_video_description)
    return remember(control) {
        EditorVideo(control) {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; player = control.player } },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
        }
    }
}
