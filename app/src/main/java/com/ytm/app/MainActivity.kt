package com.ytm.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ytm.app.ui.CollectionScreen
import com.ytm.app.ui.HomeScreen
import com.ytm.app.ui.LibraryScreen
import com.ytm.app.ui.MiniPlayer
import com.ytm.app.ui.NowPlayingScreen
import com.ytm.app.ui.PairLoginScreen
import com.ytm.app.ui.PasteLoginScreen
import com.ytm.app.ui.QueueScreen
import com.ytm.app.ui.SearchScreen
import com.ytm.app.ui.SetupScreen
import com.ytm.app.ui.WebLoginScreen
import com.ytm.app.ui.YtmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YtmTheme { AppRoot() } }
    }
}

private val tabs = listOf(
    Triple("home", "Home", Icons.Default.Home),
    Triple("search", "Search", Icons.Default.Search),
    Triple("library", "Library", Icons.Default.LibraryMusic)
)

fun collectionRoute(kind: String, id: String, title: String) =
    "collection/$kind/${Uri.encode(id)}?title=${Uri.encode(title)}"

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }

    val isTab = route in tabs.map { it.first }
    val showBars = isTab || route?.startsWith("collection") == true

    fun goHomeAfterLogin() {
        vm.resetLogin()
        nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBars) Column {
                MiniPlayer(app.player) { nav.navigate("now") }
                if (isTab) NavigationBar {
                    tabs.forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        NavHost(
            nav,
            startDestination = if (app.prefs.loggedIn) "home" else "setup",
            modifier = Modifier.padding(pad)
        ) {
            composable("setup") {
                SetupScreen(vm, onWeb = { nav.navigate("login/web") }, onPaste = { nav.navigate("login/paste") },
                    onPair = { nav.navigate("login/pair") })
            }
            composable("login/web") {
                WebLoginScreen(vm, onBack = { nav.popBackStack() },
                    onPaste = { nav.navigate("login/paste") { popUpTo("login/web") { inclusive = true } } },
                    onDone = { goHomeAfterLogin() })
            }
            composable("login/paste") {
                PasteLoginScreen(vm, onBack = { nav.popBackStack() }, onDone = { goHomeAfterLogin() })
            }
            composable("login/pair") {
                PairLoginScreen(vm, onBack = { nav.popBackStack() }, onDone = { goHomeAfterLogin() })
            }
            composable("queue") { QueueScreen(vm, onBack = { nav.popBackStack() }) }
            composable("home") {
                HomeScreen(vm, onOpen = { nav.navigate(collectionRoute("yt", it.id, it.title)) },
                    onRelogin = { nav.navigate("setup") }, onLogout = {
                        vm.logout(); nav.navigate("setup") { popUpTo(nav.graph.id) { inclusive = true } }
                    })
            }
            composable("search") {
                SearchScreen(vm, onOpen = { nav.navigate(collectionRoute("yt", it.id, it.title)) },
                    onRelogin = { nav.navigate("setup") })
            }
            composable("library") {
                LibraryScreen(vm,
                    onOpenYt = { nav.navigate(collectionRoute("yt", it.id, it.title)) },
                    onOpenLocal = { id, name -> nav.navigate(collectionRoute("local", id.toString(), name)) },
                    onRelogin = { nav.navigate("setup") })
            }
            composable(
                "collection/{kind}/{id}?title={title}",
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" }
                )
            ) { e ->
                CollectionScreen(
                    vm,
                    kind = e.arguments?.getString("kind").orEmpty(),
                    id = e.arguments?.getString("id").orEmpty(),
                    title = e.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpen = { nav.navigate(collectionRoute("yt", it.id, it.title)) },
                    onRelogin = { nav.navigate("setup") }
                )
            }
            composable("now") { NowPlayingScreen(vm, onBack = { nav.popBackStack() }, onQueue = { nav.navigate("queue") }) }
        }
    }
}
