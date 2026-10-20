package com.omrihak.nfccards.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** One ExoPlayer, used for both audio and video. Plays local files only. Main thread only. */
class PlayerController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onEnded()
        fun onError(error: PlaybackException)
    }

    /** Observable by Compose: actually producing sound/picture right now. */
    var isPlaying by mutableStateOf(false)
        private set

    /** Observable by Compose: false when the user has paused. */
    var playWhenReady by mutableStateOf(true)
        private set

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    this@PlayerController.isPlaying = isPlaying
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    this@PlayerController.playWhenReady = playWhenReady
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) listener.onEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    listener.onError(error)
                }
            })
        }

    fun play(file: File) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
    }

    /** Tap anywhere: pause/resume, or replay if the media has finished. */
    fun toggle() {
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.playWhenReady = true
            }
            player.playbackState == Player.STATE_IDLE -> Unit
            else -> player.playWhenReady = !player.playWhenReady
        }
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        isPlaying = false
    }

    fun release() = player.release()
}
