package com.ytm.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytm.app.MainViewModel

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"

// Looks like regular mobile Chrome. Google refuses sign-in in WebViews that announce "; wv".
private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

private val USERNAME_RE = Regex("^[A-Za-z0-9_.-]{2,32}$")

@Composable
fun SetupScreen(vm: MainViewModel, onWeb: () -> Unit, onPaste: () -> Unit) {
    val prefs = vm.prefs
    val relogin = prefs.loggedIn
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var regKey by remember { mutableStateOf(prefs.regKey) }
    var user by remember { mutableStateOf(prefs.username) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun proceed(next: () -> Unit) {
        val u = user.trim()
        if (!relogin && !USERNAME_RE.matches(u)) { err = "Username: 2-32 letters, digits, _ . -"; return }
        var s = server.trim().trimEnd('/')
        if (s.isEmpty()) { err = "Enter your server address"; return }
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        prefs.serverUrl = s
        prefs.regKey = regKey.trim()
        if (u.isNotEmpty()) prefs.username = u
        busy = true; err = null
        vm.checkServer { ok ->
            busy = false
            if (ok) next() else err = "Can't reach a server at $s"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (relogin) "Sign in again" else "Connect to your server", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (relogin) "Your YouTube session needs refreshing. Sign in to update it."
            else "Enter your server details, then sign in with Google.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(server, { server = it }, label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.10:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!relogin) {
            OutlinedTextField(user, { user = it }, label = { Text("Username (your choice)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(regKey, { regKey = it }, label = { Text("Registration key (if the server requires one)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { proceed(onWeb) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in with Google")
        }
        OutlinedButton(onClick = { proceed(onPaste) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Paste cookie manually")
        }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Pair with a computer (coming soon)")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(vm: MainViewModel, onBack: () -> Unit, onPaste: () -> Unit, onDone: () -> Unit) {
    val login by vm.login.collectAsStateWithLifecycle()
    var captured by remember { mutableStateOf(false) }
    LaunchedEffect(login.done) { if (login.done) onDone() }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Sign in to YouTube Music", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onPaste) { Text("Paste instead") }
        }
        if (login.busy || captured && login.error == null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("  Linking your account…")
            }
        }
        login.error?.let {
            Text("$it\n\nIf sign-in keeps failing in this window, use \"Paste instead\".",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.setAcceptCookie(true)
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = CHROME_UA
                    cm.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url == null || Uri.parse(url).host != "music.youtube.com") return
                            val cookie = cm.getCookie("https://music.youtube.com").orEmpty()
                            // Only present once the user is signed in.
                            if (cookie.contains("SAPISID") && !captured) {
                                captured = true
                                vm.submitCookie(cookie)
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

@Composable
fun PasteLoginScreen(vm: MainViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val login by vm.login.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var cookie by remember { mutableStateOf("") }
    var localErr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(login.done) { if (login.done) onDone() }

    fun submit() {
        val c = cookie.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
        when {
            c.contains('\n') || c.contains('\r') -> localErr = "The cookie must be a single line."
            !c.contains("SAPISID") -> localErr = "This doesn't look right: no SAPISID found. Copy the whole Cookie header."
            else -> { localErr = null; vm.submitCookie(c) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Paste cookie", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "On a computer: open music.youtube.com while signed in, press F12 → Network, click any request " +
                "to music.youtube.com, and copy the whole \"Cookie\" request header. Send it to this phone " +
                "(e.g. a note to yourself) and paste it below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            cookie, { cookie = it }, label = { Text("Cookie header") },
            minLines = 5, maxLines = 8, modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { clipboard.getText()?.text?.let { cookie = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("Paste from clipboard")
        }
        (localErr ?: login.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::submit, enabled = cookie.isNotBlank() && !login.busy, modifier = Modifier.fillMaxWidth()) {
            if (login.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
    }
}
