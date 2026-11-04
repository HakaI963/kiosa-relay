package com.kiosa.relay.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Relay" to Icons.Filled.Home,
        "Keys" to Icons.Filled.List,
        "Log" to Icons.Filled.Info,
        "Settings" to Icons.Filled.Settings,
    )

    KiosaTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) },
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                0 -> Box2(Modifier.padding(padding)) { RelayTab() }
                1 -> Box2(Modifier.padding(padding)) { KeysTab() }
                2 -> Box2(Modifier.padding(padding)) { LogTab() }
                else -> Box2(Modifier.padding(padding)) { SettingsTab() }
            }
        }
    }
}

@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}
