package com.ytm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytm.app.App
import com.ytm.app.Item
import com.ytm.app.MainViewModel
import com.ytm.app.Section
import com.ytm.app.UiState

@Composable
fun Thumb(url: String?, size: Dp, round: Boolean = false, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(if (round) CircleShape else RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun ItemCard(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Thumb(item.thumbUrl, 132.dp, round = item.type == "artist")
        Spacer(Modifier.height(6.dp))
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(item.thumbUrl, 52.dp, round = item.type == "artist")
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Overflow menu for a song: queue, playlists, offline download. */
@Composable
fun SongMenuButton(item: Item, vm: MainViewModel, onRemove: (() -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as App
    var open by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val downloaded by app.downloads.items.collectAsStateWithLifecycle()
    val isDownloaded = downloaded.any { it.id == item.id }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { app.player.playNext(item); open = false })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { app.player.addToQueue(item); open = false })
            DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { picker = true; open = false })
            if (isDownloaded) {
                DropdownMenuItem(text = { Text("Remove download") }, onClick = { app.downloads.delete(item.id); open = false })
            } else {
                DropdownMenuItem(text = { Text("Download") }, onClick = { app.downloads.download(item); open = false })
            }
            if (onRemove != null) {
                DropdownMenuItem(text = { Text("Remove from this playlist") }, onClick = { onRemove(); open = false })
            }
        }
    }
    if (picker) PlaylistPicker(vm, onPick = { vm.addToPlaylist(it, item); picker = false }, onDismiss = { picker = false })
}

@Composable
fun PlaylistPicker(vm: MainViewModel, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPlaylists() }
    val state by vm.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            val list = state.data.orEmpty()
            if (list.isEmpty()) Text(if (state.loading) "Loading…" else "No playlists yet. Create one in the Library tab.")
            else LazyColumn {
                items(list) { p ->
                    TextButton(onClick = { onPick(p.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One row per item; tapping a song queues the section's songs starting there. */
@Composable
fun SongOrItemRow(
    item: Item, songs: List<Item>, vm: MainViewModel,
    onOpen: (Item) -> Unit, onRemove: ((Item) -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as App
    ItemRow(
        item,
        onClick = {
            if (item.type == "song") app.player.play(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            else onOpen(item)
        },
        trailing = {
            if (item.type == "song") SongMenuButton(item, vm, onRemove?.let { r -> { r(item) } })
        }
    )
}

fun LazyListScope.sectionRows(
    sections: List<Section>, vm: MainViewModel, onOpen: (Item) -> Unit, onRemove: ((Item) -> Unit)? = null
) {
    sections.forEach { sec ->
        if (sec.title.isNotBlank()) item {
            Text(sec.title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp))
        }
        val songs = sec.items.filter { it.type == "song" }
        items(sec.items) { SongOrItemRow(it, songs, vm, onOpen, onRemove) }
    }
}

@Composable
fun <T> StateView(
    state: UiState<T>, onRetry: () -> Unit, onRelogin: () -> Unit, content: @Composable (T) -> Unit
) {
    val data = state.data
    when {
        data != null -> Box(Modifier.fillMaxSize()) {
            content(data)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        state.error != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.error, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                if (state.expired) OutlinedButton(onClick = onRelogin) { Text("Sign in again") }
            }
        }
        else -> Box(Modifier.fillMaxSize())
    }
}
