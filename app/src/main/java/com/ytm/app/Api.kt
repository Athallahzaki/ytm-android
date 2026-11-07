package com.ytm.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val TYPE_WORDS = setOf("Song", "Video", "Album", "Single", "EP", "Playlist", "Artist")

data class Item(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumb: String? = null,
    val duration: Int = 0 // seconds, 0 = unknown
) {
    val thumbUrl: String? get() = thumb ?: if (type == "song") "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
    /** Subtitles look like "Song • Artist • Album"; pick the artist-ish part. */
    val artist: String
        get() = subtitle.split("•").map { it.trim() }.firstOrNull { it.isNotEmpty() && it !in TYPE_WORDS } ?: ""
}

data class Section(val title: String, val items: List<Item>)
data class PlaylistInfo(val id: Long, val name: String, val trackCount: Int)

data class PairInfo(val code: String, val secret: String, val url: String)

class ApiException(val code: Int, message: String) : IOException(message)

private fun JSONObject.toItem() = Item(
    optString("type"), optString("id"), optString("title"), optString("subtitle"),
    optString("thumbnail").ifEmpty { null }, optInt("duration")
)

private fun parseSections(text: String): List<Section> {
    val arr = JSONObject(text).optJSONArray("sections") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        val items = s.optJSONArray("items") ?: JSONArray()
        Section(s.optString("title"), (0 until items.length()).map { j -> items.getJSONObject(j).toItem() })
    }
}

class Api(private val prefs: Prefs) {
    /** Shared with ExoPlayer so streaming requests carry the bearer token too. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            prefs.token?.let { b.header("Authorization", "Bearer $it") }
            chain.proceed(b.build())
        }
        .build()

    private val downloadClient = client.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    fun streamUrl(id: String) = "${prefs.serverUrl}/api/v1/stream/$id"

    private suspend fun call(
        method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(prefs.serverUrl + path)
        headers.forEach { (k, v) -> rb.header(k, v) }
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        rb.method(method, payload ?: if (method == "POST" || method == "PUT") "".toRequestBody() else null)
        client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP ${resp.code}")
                throw ApiException(resp.code, msg)
            }
            text
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun health(): Boolean = try { call("GET", "/healthz"); true } catch (e: Exception) { false }

    // ---- auth ----
    private suspend fun register(cookie: String) {
        val headers = if (prefs.regKey.isNotEmpty()) mapOf("X-Registration-Key" to prefs.regKey) else emptyMap()
        val res = call("POST", "/api/v1/auth/register",
            JSONObject().put("username", prefs.username).put("cookie", cookie), headers)
        prefs.token = JSONObject(res).getString("token")
    }

    /** First login registers a user; later logins (expired cookie) just refresh the cookie. */
    suspend fun login(cookie: String) {
        if (prefs.loggedIn) {
            try {
                call("PUT", "/api/v1/auth/cookie", JSONObject().put("cookie", cookie))
                return
            } catch (e: ApiException) {
                if (e.code != 401) throw e
                prefs.token = null // server doesn't know this token anymore
            }
        }
        register(cookie)
    }

    // ---- YouTube Music ----
    suspend fun home() = parseSections(call("GET", "/api/v1/home?mode=${prefs.mode}"))
    suspend fun search(q: String, songsOnly: Boolean) =
        parseSections(call("GET", "/api/v1/search?mode=${prefs.mode}&q=${enc(q)}" + if (songsOnly) "&filter=only" else ""))
    suspend fun library() = parseSections(call("GET", "/api/v1/library/playlists?mode=${prefs.mode}"))
    suspend fun ytPlaylist(id: String) = parseSections(call("GET", "/api/v1/ytplaylist/${enc(id)}?mode=${prefs.mode}"))

    // ---- local playlists ----
    suspend fun playlists(): List<PlaylistInfo> {
        val arr = JSONArray(call("GET", "/api/v1/playlists"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlaylistInfo(o.getLong("id"), o.getString("name"), o.optInt("trackCount"))
        }
    }

    suspend fun createPlaylist(name: String, tracks: List<Item> = emptyList()) {
        val arr = JSONArray()
        tracks.forEach { arr.put(JSONObject().put("videoId", it.id).put("title", it.title).put("artist", it.artist)) }
        call("POST", "/api/v1/playlists", JSONObject().put("name", name).put("tracks", arr))
    }

    suspend fun setOrder(pid: Long, videoIds: List<String>) {
        call("PUT", "/api/v1/playlists/$pid/order", JSONObject().put("videoIds", JSONArray(videoIds)))
    }

    /** Creates a real (private) playlist on the user's YouTube account. */
    suspend fun createYtPlaylist(title: String, videoIds: List<String>): String {
        val res = call("POST", "/api/v1/ytplaylists", JSONObject()
            .put("title", title).put("privacy", "PRIVATE").put("mode", prefs.mode)
            .put("videoIds", JSONArray(videoIds)))
        return JSONObject(res).getString("playlistId")
    }

    // ---- auto-mix, play tracking, prefetch ----
    suspend fun radio(videoId: String): List<Item> =
        parseSections(call("GET", "/api/v1/queue?mode=${prefs.mode}&videoId=${enc(videoId)}"))
            .flatMap { it.items }.filter { it.type == "song" }

    suspend fun played(id: String) { call("POST", "/api/v1/played/$id") }
    suspend fun prepare(id: String) { call("POST", "/api/v1/prepare/$id") }

    // ---- pair with a computer ----
    suspend fun pairStart(): PairInfo {
        val headers = if (prefs.regKey.isNotEmpty()) mapOf("X-Registration-Key" to prefs.regKey) else emptyMap()
        val o = JSONObject(call("POST", "/api/v1/pair/start", JSONObject().put("username", prefs.username), headers))
        return PairInfo(o.getString("code"), o.getString("secret"), o.getString("url"))
    }

    /** True once the computer has submitted the cookie and the server finished linking. */
    suspend fun pairStatus(info: PairInfo): Boolean {
        val o = JSONObject(call("GET", "/api/v1/pair/status?code=${enc(info.code)}&secret=${enc(info.secret)}"))
        if (o.getString("status") != "done") return false
        o.optString("token").takeIf { it.isNotEmpty() }?.let { prefs.token = it }
        return true
    }
    suspend fun deletePlaylist(id: Long) { call("DELETE", "/api/v1/playlists/$id") }

    suspend fun playlist(id: Long): Pair<String, List<Item>> {
        val o = JSONObject(call("GET", "/api/v1/playlists/$id"))
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val items = (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            Item("song", t.getString("videoId"), t.optString("title"), t.optString("artist"))
        }
        return o.getString("name") to items
    }

    suspend fun addTrack(pid: Long, item: Item) {
        call("POST", "/api/v1/playlists/$pid/tracks",
            JSONObject().put("videoId", item.id).put("title", item.title).put("artist", item.artist))
    }

    suspend fun removeTrack(pid: Long, videoId: String) { call("DELETE", "/api/v1/playlists/$pid/tracks/$videoId") }

    suspend fun addHistory(item: Item) {
        call("POST", "/api/v1/history",
            JSONObject().put("videoId", item.id).put("title", item.title).put("artist", item.artist))
    }

    // ---- offline download ----
    suspend fun downloadTo(id: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${prefs.serverUrl}/api/v1/download/$id").build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "download failed (HTTP ${resp.code})")
            resp.body!!.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }
}
