package com.ytm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.ytm.app.App
import com.ytm.app.MainViewModel
import com.ytm.app.PlayerController
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MiniPlayer(player: PlayerController, onOpen: () -> Unit) {
    val ui by player.ui.collectAsStateWithLifecycle()
    val item = ui.item ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item.thumbUrl, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { player.togglePlay() }) {
                Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
        }
    }
}

@Composable
fun NowPlayingScreen(vm: MainViewModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val player = app.player
    val ui by player.ui.collectAsStateWithLifecycle()
    val downloaded by app.downloads.items.collectAsStateWithLifecycle()
    val active by app.downloads.active.collectAsStateWithLifecycle()

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.position(); duration = player.duration()
            delay(500)
        }
    }

    val item = ui.item
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
            Spacer(Modifier.weight(1f))
            if (item != null) {
                val isDl = downloaded.any { it.id == item.id }
                IconButton(onClick = { if (!isDl) app.downloads.download(item) else app.downloads.delete(item.id) }) {
                    Icon(if (isDl) Icons.Default.DownloadDone else Icons.Default.Download,
                        if (isDl) "Remove download" else "Download",
                        tint = if (item.id in active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (item == null) {
            Text("Nothing playing", Modifier.padding(top = 80.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        AsyncImage(
            model = item.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        val seekable = duration > 0
        Slider(
            value = dragging ?: position.toFloat().coerceAtMost(if (seekable) duration.toFloat() else 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()) }; dragging = null },
            valueRange = 0f..(if (seekable) duration.toFloat() else 1f),
            enabled = seekable
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(dragging?.toLong() ?: position), style = MaterialTheme.typography.labelMedium)
            Text(if (seekable) fmt(duration) else "live", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.toggleShuffle() }) {
                Icon(Icons.Default.Shuffle, "Shuffle",
                    tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { player.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
            FilledIconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(72.dp)) {
                Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", Modifier.size(40.dp))
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                    tint = if (ui.repeat == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
    }
}
