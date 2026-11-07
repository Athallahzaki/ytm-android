package com.ytm.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Offline library: Opus files in the app-private files dir (not accessible to other apps). */
class Downloads(ctx: Context, private val api: Api) {
    private val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val lock = Any()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Item>> = _items

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active

    fun file(id: String): File? = File(dir, "$id.opus").takeIf { it.exists() && it.length() > 0 }

    private fun load(): List<Item> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Item("song", it.getString("id"), it.optString("title"), it.optString("subtitle"),
                it.optString("thumb").ifEmpty { null })
        }.filter { file(it.id) != null }
    }.getOrDefault(emptyList())

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title)
                .put("subtitle", it.artist).put("thumb", it.thumb ?: ""))
        }
        indexFile.writeText(arr.toString())
    }

    fun download(item: Item) {
        if (item.type != "song" || file(item.id) != null || item.id in _active.value) return
        _active.value = _active.value + item.id
        scope.launch {
            val part = File(dir, "${item.id}.part")
            try {
                gate.withPermit { api.downloadTo(item.id, part) }
                part.renameTo(File(dir, "${item.id}.opus"))
                synchronized(lock) {
                    val list = _items.value.filter { it.id != item.id } + item
                    save(list); _items.value = list
                }
            } catch (e: Exception) {
                Log.w("Downloads", "download failed for ${item.id}", e)
                part.delete()
            } finally {
                _active.value = _active.value - item.id
            }
        }
    }

    fun downloadAll(items: List<Item>) = items.forEach { download(it) }

    fun delete(id: String) {
        File(dir, "$id.opus").delete()
        synchronized(lock) {
            val list = _items.value.filter { it.id != id }
            save(list); _items.value = list
        }
    }
}
