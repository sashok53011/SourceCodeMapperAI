package online.devhorizon.sourcecodemapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import online.devhorizon.sourcecodemapper.MainViewModel
import online.devhorizon.sourcecodemapper.model.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lang = state.settings.language

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(txt(lang, "app_name")) },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.screen == Screen.HOME,
                    onClick = { vm.setScreen(Screen.HOME) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(txt(lang, "tab_home")) }
                )
                NavigationBarItem(
                    selected = state.screen == Screen.REPO || state.screen == Screen.PROGRESS,
                    onClick = { vm.setScreen(Screen.REPO) },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    label = { Text(txt(lang, "tab_repo")) }
                )
                NavigationBarItem(
                    selected = state.screen == Screen.REPORT,
                    onClick = { vm.setScreen(Screen.REPORT) },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text(txt(lang, "tab_report")) }
                )
                NavigationBarItem(
                    selected = state.screen == Screen.SETTINGS,
                    onClick = { vm.setScreen(Screen.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(txt(lang, "tab_settings")) }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(state, vm)
                Screen.REPO, Screen.PROGRESS -> RepoScreen(state, vm)
                Screen.REPORT -> ReportScreen(state, vm)
                Screen.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
}
