package com.voleybolveveleybol.volleystat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.voleybolveveleybol.volleystat.ui.screens.EditorVideo
import com.voleybolveveleybol.volleystat.ui.screens.VideoControl

/**
 * Stands in for the player of the range editor, which does not run in a JVM test. The picture
 * goes wherever the screen sends it, and a test puts it anywhere with [positionMs].
 */
class FakeControl(startMs: Long = 0) : VideoControl {
    override var positionMs by mutableLongStateOf(startMs)
    override var playing by mutableStateOf(false)
    override var failed by mutableStateOf(false)
    var fast = false
    /** Every position the screen asked for, with whether it asked for the exact frame. */
    val seeks = mutableListOf<Pair<Long, Boolean>>()

    override fun seekTo(positionMs: Long, exact: Boolean) {
        seeks += positionMs to exact
        this.positionMs = positionMs
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun speed(fast: Boolean) {
        this.fast = fast
    }

    val video = EditorVideo(this) { Box(Modifier.fillMaxSize()) }
}
