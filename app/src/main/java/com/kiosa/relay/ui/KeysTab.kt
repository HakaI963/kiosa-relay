package com.kiosa.relay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiosa.relay.RelayController
import com.kiosa.relay.core.ApiKey
import com.kiosa.relay.core.KeyStatus
import kotlinx.coroutines.launch

@Composable
fun KeysTab() {
    val keys by RelayController.keys.collectAsState()
    val snapshot by RelayController.engine.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ApiKey?>(null) }
    var deleting by remember { mutableStateOf<ApiKey?>(null) }
    var testing by remember { mutableStateOf<ApiKey?>(null) }
    var testResult by remember { mutableStateOf<Pair<ApiKey, Pair<Boolean, String>>?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "API Keys",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Used strictly in list order — top key first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { editing = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }

        if (keys.isEmpty()) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("No API keys yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add one or more KiosAPI keys (sk-…). Keys are stored encrypted on this device and are only ever sent to the upstream API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(keys, key = { it.id }) { k ->
                val view = snapshot.keys.firstOrNull { it.id == k.id }
                val status = view?.status ?: KeyStatus.IDLE
                val remaining = view?.let { ((it.cooldownUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0) } ?: 0

                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(k.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    k.secret.take(7) + "…" + k.secret.takeLast(4),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(status, remaining.toInt())
                        }
                        view?.let {
                            if (it.lastError != null && status == KeyStatus.ERROR) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    it.lastError ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = ErrRed,
                                    maxLines = 2,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "ok ${view?.success ?: 0} · 429s ${view?.rateLimited ?: 0} · fail ${view?.fail ?: 0}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text("enabled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = k.enabled, onCheckedChange = { on -> RelayController.updateKey(k.copy(enabled = on)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { RelayController.moveKey(k.id, up = true) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = { RelayController.moveKey(k.id, up = false) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = { editing = k; showDialog = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Create, contentDescription = "Edit", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = { deleting = k }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ErrRed)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    testing = k
                                    scope.launch {
                                        testResult = k to RelayController.testKey(k)
                                        testing = null
                                    }
                                },
                                enabled = testing == null,
                            ) { Text(if (testing == k) "Testing…" else "Test connection") }
                        }
                    }
                }
            }
        }

        Text(
            "Tip: the 3 RPM limit applies per ACCOUNT — keys from one account share a single limit. Add keys from different accounts for more throughput.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        KeyDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { label, secret ->
                val e = editing
                if (e == null) RelayController.addKey(label, secret)
                else RelayController.updateKey(e.copy(label = label, secret = secret))
                showDialog = false
            },
        )
    }

    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete key?") },
            text = { Text("Remove \"${k.label}\" from the pool? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { RelayController.deleteKey(k.id); deleting = null }) {
                    Text("Delete", color = ErrRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    testResult?.let { (k, res) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text(if (res.first) "Key works" else "Key test failed") },
            text = { Text("${k.label}\n\n${res.second}") },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun KeyDialog(initial: ApiKey?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var secret by remember { mutableStateOf(initial?.secret ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add API key" else "Edit key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (e.g. Account A)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("API key (sk-…)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (secret.isNotBlank()) onSave(label, secret) },
                enabled = secret.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
