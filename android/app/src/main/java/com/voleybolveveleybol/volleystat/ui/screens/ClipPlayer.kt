package com.voleybolveveleybol.volleystat.ui.screens

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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.voleybolveveleybol.volleystat.R

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
