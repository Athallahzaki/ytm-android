package com.ytm.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class UiState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    /** Server says the YouTube session/token is no longer valid -> offer re-login. */
    val expired: Boolean = false
)

data class CollectionData(val title: String, val sections: List<Section>)
data class PairUi(val code: String? = null, val url: String? = null, val error: String? = null)
data class LoginUi(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val prefs = app.prefs
    private val api = app.api

    private val _mode = MutableStateFlow(prefs.mode)
    val mode: StateFlow<String> = _mode
    private val _pair = MutableStateFlow(PairUi())
    val pair: StateFlow<PairUi> = _pair
    private var pairJob: Job? = null

    private val _home = MutableStateFlow(UiState<List<Section>>())
    val home: StateFlow<UiState<List<Section>>> = _home
    private val _search = MutableStateFlow(UiState<List<Section>>())
    val search: StateFlow<UiState<List<Section>>> = _search
    private val _library = MutableStateFlow(UiState<List<Section>>())
    val library: StateFlow<UiState<List<Section>>> = _library
    private val _playlists = MutableStateFlow(UiState<List<PlaylistInfo>>())
    val playlists: StateFlow<UiState<List<PlaylistInfo>>> = _playlists
    private val _collection = MutableStateFlow(UiState<CollectionData>())
    val collection: StateFlow<UiState<CollectionData>> = _collection
    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login

    /** One-shot snackbar message. */
    val message = MutableStateFlow<String?>(null)

    private fun <T> errorState(e: Exception, old: T?): UiState<T> = when (e) {
        is ApiException -> UiState(
            data = old, error = e.message ?: "Server error",
            expired = e.code == 401 || e.code == 502
        )
        is IOException -> UiState(data = old, error = "Can't reach the server. Check the address and your connection.")
        else -> UiState(data = old, error = e.message ?: "Something went wrong")
    }

    private fun <T> load(flow: MutableStateFlow<UiState<T>>, keepOld: Boolean = true, block: suspend () -> T) {
        viewModelScope.launch {
            val old = if (keepOld) flow.value.data else null
            flow.value = UiState(loading = true, data = old)
            flow.value = try {
                UiState(data = block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorState(e, old)
            }
        }
    }

    fun loadHome() = load(_home) { api.home() }
    fun loadLibrary() = load(_library) { api.library() }
    fun loadPlaylists() = load(_playlists) { api.playlists() }

    fun search(q: String, songsOnly: Boolean) {
        if (q.isBlank()) return
        load(_search) { api.search(q.trim(), songsOnly) }
    }

    fun loadCollection(kind: String, id: String, title: String) {
        load(_collection, keepOld = false) {
            if (kind == "local") {
                val (name, tracks) = api.playlist(id.toLong())
                CollectionData(name, listOf(Section("", tracks)))
            } else {
                CollectionData(title, api.ytPlaylist(id))
            }
        }
    }

    private fun act(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { message.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Action failed"
            }
        }
    }

    fun createPlaylist(name: String) = act("Playlist created") { api.createPlaylist(name); loadPlaylists() }
    fun deletePlaylist(id: Long) = act("Playlist deleted") { api.deletePlaylist(id); loadPlaylists() }
    fun addToPlaylist(pid: Long, item: Item) = act("Added to playlist") { api.addTrack(pid, item) }
    fun removeFromPlaylist(pid: Long, item: Item) = act {
        api.removeTrack(pid, item.id)
        loadCollection("local", pid.toString(), "")
    }

    fun setMode(m: String) {
        if (m == _mode.value) return
        prefs.mode = m
        _mode.value = m
        _home.value = UiState(); _search.value = UiState(); _library.value = UiState()
    }

    fun saveQueue(name: String, items: List<Item>, toYouTube: Boolean) =
        act(if (toYouTube) "Created on YouTube (private)" else "Playlist saved") {
            val songs = items.filter { it.type == "song" }
            if (toYouTube) api.createYtPlaylist(name, songs.map { it.id }) else api.createPlaylist(name, songs)
        }

    fun moveInPlaylist(pid: Long, item: Item, delta: Int) = act {
        val songs = _collection.value.data?.sections?.flatMap { it.items }
            ?.filter { it.type == "song" }.orEmpty().toMutableList()
        val i = songs.indexOfFirst { it.id == item.id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= songs.size) return@act
        songs.add(j, songs.removeAt(i))
        api.setOrder(pid, songs.map { it.id })
        loadCollection("local", pid.toString(), "")
    }

    // ---- pair with a computer ----
    fun startPairing() {
        pairJob?.cancel()
        pairJob = viewModelScope.launch {
            _pair.value = PairUi()
            try {
                val info = api.pairStart()
                _pair.value = PairUi(code = info.code, url = info.url)
                while (true) {
                    delay(2000)
                    if (api.pairStatus(info)) { _login.value = LoginUi(done = true); return@launch }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _pair.value = PairUi(error = if (e.code == 404) "The code expired. Try again." else e.message ?: "Pairing failed")
            } catch (e: Exception) {
                _pair.value = PairUi(error = "Can't reach the server.")
            }
        }
    }

    fun cancelPairing() { pairJob?.cancel(); pairJob = null }

    // ---- login ----
    fun checkServer(cb: (Boolean) -> Unit) { viewModelScope.launch { cb(api.health()) } }

    fun submitCookie(cookie: String) {
        if (_login.value.busy) return
        viewModelScope.launch {
            _login.value = LoginUi(busy = true)
            _login.value = try {
                api.login(cookie.trim())
                LoginUi(done = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                LoginUi(error = e.message ?: "Login failed")
            } catch (e: Exception) {
                LoginUi(error = "Can't reach the server: ${e.message}")
            }
        }
    }

    fun resetLogin() { _login.value = LoginUi() }

    fun logout() {
        prefs.token = null
        _home.value = UiState(); _library.value = UiState(); _search.value = UiState()
    }
}
