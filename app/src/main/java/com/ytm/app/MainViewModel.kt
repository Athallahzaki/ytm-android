package com.ytm.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
data class LoginUi(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val prefs = app.prefs
    private val api = app.api

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
