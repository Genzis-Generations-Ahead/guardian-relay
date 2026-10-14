package com.guardianmesh.relay.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.trigger.TriggerService
import com.guardianmesh.relay.ui.contacts.ContactsScreen
import com.guardianmesh.relay.ui.followed.FollowedScreen
import com.guardianmesh.relay.ui.home.HomeScreen
import com.guardianmesh.relay.ui.mesh.MeshScreen
import com.guardianmesh.relay.ui.settings.SettingsScreen
import com.guardianmesh.relay.ui.sos.SosScreen
import com.guardianmesh.relay.ui.theme.GuardianTheme

/**
 * The app. One activity, one [AppViewModel], and a Compose NavHost over six screens.
 *
 * Reached through [LaunchActivity], which decides whether the calculator lock stands in
 * front of it.
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guardian.ensureInit(this)
        enableEdgeToEdge()
        setContent {
            GuardianTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GuardianNavRoot()
                }
            }
        }
    }

    /**
     * With the app open there is no media session holding the volume keys (see
     * [com.guardianmesh.relay.trigger.VolumeKeyDetector]), so forward them here. That
     * makes the volume up+down panic combo work on this screen too, and not only with
     * the screen off.
     *
     * Returning false keeps the system's own volume behaviour; the combo is detected
     * alongside it rather than instead of it.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            TriggerService.onActivityVolumeKey(keyCode)
        }
        return super.onKeyDown(keyCode, event)
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home(Routes.HOME, "Home", Icons.Filled.Home),
    Followed(Routes.FOLLOWED, "Followed", Icons.Filled.Visibility),
    Mesh(Routes.MESH, "Mesh", Icons.Filled.Bluetooth),
    Contacts(Routes.CONTACTS, "Contacts", Icons.Filled.People),
    Settings(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
}

object Routes {
    const val HOME = "home"
    const val SOS = "sos"
    const val FOLLOWED = "followed"
    const val MESH = "mesh"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
}

@Composable
private fun GuardianNavRoot(vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = LocalContext.current

    // One place for every transient message, so no screen needs its own snackbar plumbing.
    val toast by vm.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeToast()
        }
    }

    Scaffold(
        bottomBar = {
            // The SOS screen hides the bar: while an alert is live, nothing should compete
            // with "stop" and "safe exit" for the user's attention.
            if (currentRoute != Routes.SOS) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            NavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onOpenSos = { navController.navigate(Routes.SOS) },
                        onOpenFollowed = { navController.navigate(Routes.FOLLOWED) },
                        onOpenMesh = { navController.navigate(Routes.MESH) },
                        onOpenContacts = { navController.navigate(Routes.CONTACTS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }
                composable(Routes.SOS) {
                    SosScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable(Routes.FOLLOWED) {
                    FollowedScreen(vm = vm, onOpenSos = { navController.navigate(Routes.SOS) })
                }
                composable(Routes.MESH) { MeshScreen(vm = vm) }
                composable(Routes.CONTACTS) { ContactsScreen(vm = vm) }
                composable(Routes.SETTINGS) { SettingsScreen(vm = vm) }
            }
        }
    }
}
