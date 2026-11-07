package com.ytm.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerUi(
    val item: Item? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF
)

/** UI-side handle to the playback service (MediaController). */
class PlayerController(private val ctx: Context, private val api: Api) {
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            sync()
            mediaItem?.toItem()?.let { item -> scope.launch { runCatching { api.addHistory(item) } } }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
        override fun onRepeatModeChanged(repeatMode: Int) = sync()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = sync()
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            sync()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun MediaItem.toItem() = Item(
        "song", mediaId,
        mediaMetadata.title?.toString().orEmpty(),
        mediaMetadata.artist?.toString().orEmpty(),
        mediaMetadata.artworkUri?.toString()
    )

    private fun Item.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .apply { thumbUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
        ).build()

    private fun sync() {
        val c = controller ?: return
        _ui.value = PlayerUi(c.currentMediaItem?.toItem(), c.isPlaying, c.shuffleModeEnabled, c.repeatMode)
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add(block)
    }

    fun play(items: List<Item>, startIndex: Int) {
        if (items.isEmpty()) return
        withController { c ->
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.size - 1), 0L)
            c.prepare()
            c.play()
        }
    }

    fun playNext(item: Item) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item), 0)
        else c.addMediaItem(c.currentMediaItemIndex + 1, item.toMediaItem())
    }

    fun addToQueue(item: Item) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item), 0) else c.addMediaItem(item.toMediaItem())
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun position(): Long = controller?.currentPosition ?: 0L
    /** 0 when unknown (live-transcoded streams have no length until cached/downloaded). */
    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
}
