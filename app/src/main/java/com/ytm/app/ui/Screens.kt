package com.ytm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytm.app.App
import com.ytm.app.Item
import com.ytm.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (Item) -> Unit, onRelogin: () -> Unit, onLogout: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val state by vm.home.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.data == null) vm.loadHome() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Home") },
            actions = {
                IconButton(onClick = { vm.loadHome() }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Refresh YouTube session") }, onClick = { menu = false; onRelogin() })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onLogout() })
                }
            }
        )
        StateView(state, onRetry = vm::loadHome, onRelogin = onRelogin) { sections ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(sections) { sec ->
                    val songs = sec.items.filter { it.type == "song" }
                    Text(sec.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(sec.items) { item ->
                            ItemCard(item) {
                                if (item.type == "song") app.player.play(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                                else onOpen(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (Item) -> Unit, onRelogin: () -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var songsOnly by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it }, singleLine = true, label = { Text("Search songs, artists, albums") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query, songsOnly) }),
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = songsOnly, onClick = { songsOnly = !songsOnly; vm.search(query, songsOnly) },
                label = { Text("Songs only") })
        }
        StateView(state, onRetry = { vm.search(query, songsOnly) }, onRelogin = onRelogin) { sections ->
            LazyColumn(Modifier.fillMaxSize()) { sectionRows(sections, vm, onOpen) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: MainViewModel, onOpenYt: (Item) -> Unit, onOpenLocal: (Long, String) -> Unit, onRelogin: () -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    var tab by rememberSaveable { mutableStateOf(0) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val downloads by app.downloads.items.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(tab) {
        when (tab) {
            0 -> vm.loadPlaylists()
            1 -> if (library.data == null) vm.loadLibrary()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Library") })
        TabRow(selectedTabIndex = tab) {
            listOf("Playlists", "YouTube", "Downloads").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> StateView(playlists, onRetry = vm::loadPlaylists, onRelogin = onRelogin) { list ->
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TextButton(onClick = { creating = true }, modifier = Modifier.padding(8.dp)) {
                            Icon(Icons.Default.Add, null); Text("  New playlist")
                        }
                    }
                    items(list) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenLocal(p.id, p.name) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${p.trackCount} tracks", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { deleting = p.id }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
            1 -> StateView(library, onRetry = vm::loadLibrary, onRelogin = onRelogin) { sections ->
                LazyColumn(Modifier.fillMaxSize()) { sectionRows(sections, vm, onOpenYt) }
            }
            else -> {
                if (downloads.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("No downloads yet. Use the ⋮ menu on a song to download it for offline playback.")
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TextButton(onClick = { app.player.play(downloads, 0) }, modifier = Modifier.padding(8.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Text("  Play all")
                        }
                    }
                    items(downloads) { item ->
                        ItemRow(item, onClick = { app.player.play(downloads, downloads.indexOf(item)) }, trailing = {
                            IconButton(onClick = { app.downloads.delete(item.id) }) { Icon(Icons.Default.Delete, "Remove") }
                        })
                    }
                }
            }
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) vm.createPlaylist(newName.trim())
                newName = ""; creating = false
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
    )
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete playlist?") },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    vm: MainViewModel, kind: String, id: String, title: String,
    onBack: () -> Unit, onOpen: (Item) -> Unit, onRelogin: () -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val state by vm.collection.collectAsStateWithLifecycle()
    LaunchedEffect(kind, id) { vm.loadCollection(kind, id, title) }
    val songs = state.data?.sections?.flatMap { it.items }?.filter { it.type == "song" }.orEmpty()
    val localId = if (kind == "local") id.toLongOrNull() else null

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.data?.title?.ifBlank { null } ?: title.ifBlank { "Playlist" }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (songs.isNotEmpty()) {
                    IconButton(onClick = { app.player.play(songs, 0) }) { Icon(Icons.Default.PlayArrow, "Play all") }
                    IconButton(onClick = { app.downloads.downloadAll(songs); vm.message.value = "Downloading ${songs.size} tracks…" }) {
                        Icon(Icons.Default.Download, "Download all")
                    }
                }
            }
        )
        StateView(state, onRetry = { vm.loadCollection(kind, id, title) }, onRelogin = onRelogin) { c ->
            LazyColumn(Modifier.fillMaxSize()) {
                sectionRows(c.sections, vm, onOpen, localId?.let { pid -> { item: Item -> vm.removeFromPlaylist(pid, item) } })
            }
        }
    }
}
