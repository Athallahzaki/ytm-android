package com.ytm.app

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var api: Api
    lateinit var downloads: Downloads
    lateinit var player: PlayerController

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        downloads = Downloads(this, api)
        player = PlayerController(this, api) { id -> downloads.file(id) != null }
    }

    /** Prefer the offline file; otherwise stream from the server. */
    fun resolveUri(id: String): Uri = downloads.file(id)?.toUri() ?: Uri.parse(api.streamUrl(id))
}
