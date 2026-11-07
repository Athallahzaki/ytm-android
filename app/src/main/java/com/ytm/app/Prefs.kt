package com.ytm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Server address and API token, stored encrypted on-device. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) { sp.edit().putString("server", v.trim().trimEnd('/')).apply() }

    var regKey: String
        get() = sp.getString("regKey", "") ?: ""
        set(v) { sp.edit().putString("regKey", v).apply() }

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) { sp.edit().putString("username", v).apply() }

    /** "music" (YouTube Music) or "youtube" (regular YouTube). */
    var mode: String
        get() = sp.getString("mode", "music") ?: "music"
        set(v) { sp.edit().putString("mode", v).apply() }

    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply() }

    val loggedIn: Boolean get() = !token.isNullOrEmpty()
}
