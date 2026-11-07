package com.ytm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytm.app.App
import com.ytm.app.MainViewModel
import com.ytm.app.PlayerController

private fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: MainViewModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val player = app.player
    val ui by player.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(ui.index) { if (ui.index >= 0) listState.scrollToItem(ui.index) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Queue (${ui.queue.size})") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { saving = true }, enabled = ui.queue.isNotEmpty()) { Text("Save") } }
        )
        if (ui.queue.isEmpty()) {
            Text("The queue is empty. Play something first.", Modifier.padding(24.dp))
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(ui.queue) { i, item ->
                val current = i == ui.index
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { player.jumpTo(i) }
                        .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(item.thumbUrl, 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        val meta = listOfNotNull(item.subtitle.ifBlank { null }, item.duration.takeIf { it > 0 }?.let(::mmss))
                        Text(meta.joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    QueueRowMenu(i, ui.queue.lastIndex, player)
                }
            }
        }
    }

    if (saving) SaveQueueDialog(
        onDismiss = { saving = false },
        onSave = { name, toYouTube -> vm.saveQueue(name, ui.queue, toYouTube); saving = false }
    )
}

@Composable
private fun QueueRowMenu(i: Int, last: Int, player: PlayerController) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play now") }, onClick = { player.jumpTo(i); open = false })
            if (i > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { player.moveItem(i, i - 1); open = false })
            if (i < last) DropdownMenuItem(text = { Text("Move down") }, onClick = { player.moveItem(i, i + 1); open = false })
            DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { player.removeAt(i); open = false })
        }
    }
}

@Composable
private fun SaveQueueDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Playlist name") })
                TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), true) }, enabled = name.isNotBlank()) {
                    Text("Create on YouTube instead (private)")
                }
                Text("The YouTube option adds a private playlist to your Google account.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), false) }, enabled = name.isNotBlank()) { Text("Save in app") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
