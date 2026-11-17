package io.kirora.demo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.kirora.demo.DemoStore

private enum class Tab(val label: String) { CONNECT("Connect"), PUSH("Push"), EVENTS("Events") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoApp(store: DemoStore) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { store.notices.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Kirora") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                when (item) {
                                    Tab.CONNECT -> Icons.Default.Link
                                    Tab.PUSH -> Icons.Default.Notifications
                                    Tab.EVENTS -> Icons.Default.Bolt
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.CONNECT -> ConnectScreen(store, modifier)
            Tab.PUSH -> PushScreen(store, modifier)
            Tab.EVENTS -> EventsScreen(store, modifier)
        }
    }
}
