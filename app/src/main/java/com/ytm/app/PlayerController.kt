package com.ytm.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Item> = emptyList(),
    val index: Int = -1
)

/** UI-side handle to the playback service (MediaController). */
class PlayerController(
    private val ctx: Context,
    private val api: Api,
    private val isLocal: (String) -> Boolean
) {
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainExec = ContextCompat.getMainExecutor(ctx)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            sync()
            prefetchNext()
            mediaItem?.toItem()?.let { item -> scope.launch { runCatching { api.addHistory(item) } } }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = sync()
        override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
        override fun onRepeatModeChanged(repeatMode: Int) = sync()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = sync()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) controller?.currentMediaItem?.let { markPlayed(it.mediaId) }
            sync()
        }

        // A listen counts when a track ends on its own or is skipped after ~80%.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val old = oldPosition.mediaItem ?: return
            val dur = old.mediaMetadata.durationMs ?: 0L
            val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                (reason == Player.DISCONTINUITY_REASON_SKIP && dur > 0 && oldPosition.positionMs >= dur * 8 / 10)
            if (finished) markPlayed(old.mediaId)
        }
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
        }, mainExec)
    }

    private fun markPlayed(id: String) { scope.launch { runCatching { api.played(id) } } }

    /** Ask the server to transcode the next track now so skipping/auto-advance is instant. */
    private fun prefetchNext() {
        val c = controller ?: return
        val ni = c.nextMediaItemIndex
        if (ni == C.INDEX_UNSET) return
        val id = c.getMediaItemAt(ni).mediaId
        if (!isLocal(id)) scope.launch { runCatching { api.prepare(id) } }
    }

    private fun MediaItem.toItem() = Item(
        "song", mediaId,
        mediaMetadata.title?.toString().orEmpty(),
        (mediaMetadata.subtitle ?: mediaMetadata.artist)?.toString().orEmpty(),
        mediaMetadata.artworkUri?.toString(),
        ((mediaMetadata.durationMs ?: 0L) / 1000).toInt()
    )

    private fun Item.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setSubtitle(subtitle)
                .apply {
                    thumbUrl?.let { setArtworkUri(Uri.parse(it)) }
                    if (duration > 0) setDurationMs(duration * 1000L)
                }
                .build()
        ).build()

    private fun sync() {
        val c = controller ?: return
        val n = c.mediaItemCount
        _ui.value = PlayerUi(
            item = c.currentMediaItem?.toItem(),
            isPlaying = c.isPlaying,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            queue = (0 until n).map { c.getMediaItemAt(it).toItem() },
            index = if (n == 0) -1 else c.currentMediaItemIndex
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add(block)
    }

    /** Replace the queue with [items] (playlist / album / mix) and start at [startIndex]. */
    fun play(items: List<Item>, startIndex: Int) {
        if (items.isEmpty()) return
        withController { c ->
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.size - 1), 0L)
            c.prepare()
            c.play()
        }
    }

    /** Play one track right away, then fill the queue with an auto-mix of similar tracks. */
    fun playRadio(item: Item) {
        play(listOf(item), 0)
        scope.launch {
            val more = runCatching { api.radio(item.id) }.getOrNull().orEmpty().filter { it.id != item.id }
            if (more.isEmpty()) return@launch
            mainExec.execute {
                withController { c ->
                    if (c.currentMediaItem?.mediaId == item.id) c.addMediaItems(more.map { it.toMediaItem() })
                }
            }
        }
    }

    fun playNext(item: Item) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item), 0)
        else c.addMediaItem(c.currentMediaItemIndex + 1, item.toMediaItem())
    }

    fun addToQueue(item: Item) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item), 0) else c.addMediaItem(item.toMediaItem())
    }

    // ---- queue editing ----
    fun jumpTo(index: Int) = withController { it.seekTo(index, 0L); it.play() }
    fun moveItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }

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
    /** 0 while the player hasn't read the length yet; callers fall back to Item.duration. */
    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
}
