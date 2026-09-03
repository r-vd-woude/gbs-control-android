package com.gbscontrol.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val nativeScreens = listOf(
    AppScreen.DEVICES,
    AppScreen.HOME,
    AppScreen.PRESETS,
    AppScreen.PICTURE,
    AppScreen.FILTERS,
    AppScreen.SETTINGS,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GbsControlApp(uiState: AppUiState, viewModel: GbsViewModel) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = screen == AppScreen.LEGACY) { screen = AppScreen.SETTINGS }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GBS Control", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${uiState.host} · ${uiState.status.name.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (screen != AppScreen.LEGACY) {
                ScrollableTabRow(
                    selectedTabIndex = nativeScreens.indexOf(screen).coerceAtLeast(0),
                    edgePadding = 8.dp,
                ) {
                    nativeScreens.forEach { destination ->
                        Tab(
                            selected = screen == destination,
                            onClick = { screen = destination },
                            text = { Text(destination.label) },
                        )
                    }
                }
            }
            if (uiState.status == ConnectionStatus.CONNECTING || uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Crossfade(targetState = screen, label = "screen", modifier = Modifier.weight(1f)) { destination ->
                when (destination) {
                    AppScreen.DEVICES -> DevicesScreen(uiState, viewModel)
                    AppScreen.HOME -> HomeScreen(uiState, viewModel)
                    AppScreen.PRESETS -> PresetsScreen(uiState, viewModel)
                    AppScreen.PICTURE -> PictureScreen(uiState, viewModel)
                    AppScreen.FILTERS -> FiltersScreen(uiState, viewModel)
                    AppScreen.SETTINGS -> SettingsScreen(uiState, viewModel, onLegacy = { screen = AppScreen.LEGACY })
                    AppScreen.LEGACY -> LegacyWebScreen(uiState.host)
                }
            }
        }
    }
}
