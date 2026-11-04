package com.kiosa.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var testResult by remember { mutableStateOf<Pair<ApiKey, Pair<Boolean, String>>?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("API KEYS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Used strictly in order: key 1 until rate-limited, then key 2…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { editing = null; showDialog = true }) { Text("+ Add key") }
        }

        if (keys.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("No API keys yet", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Add one or more KiosAPI keys (sk-…). Keys are stored encrypted on this device and never sent anywhere except the upstream API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(keys, key = { it.id }) { k ->
                val view = snapshot.keys.firstOrNull { it.id == k.id }
                val status = view?.status ?: KeyStatus.IDLE
                val remaining = view?.let { ((it.cooldownUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0) } ?: 0
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(status)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(k.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    k.secret.take(7) + "…" + k.secret.takeLast(4),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(
                                when (status) {
                                    KeyStatus.COOLDOWN -> "cooldown ${remaining}s"
                                    KeyStatus.ACTIVE -> "active"
                                    KeyStatus.ERROR -> "error"
                                    KeyStatus.DISABLED -> "disabled"
                                    else -> "idle"
                                },
                                statusColor(status),
                            )
                        }
                        view?.let {
                            Text(
                                "ok ${it.success} · 429s ${it.rateLimited} · fail ${it.fail}" + (it.lastError?.let { e -> "  ·  $e" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("enabled", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = k.enabled, onCheckedChange = { on -> RelayController.updateKey(k.copy(enabled = on)) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { RelayController.moveKey(k.id, up = true) }) { Text("↑") }
                            TextButton(onClick = { RelayController.moveKey(k.id, up = false) }) { Text("↓") }
                            TextButton(onClick = {
                                scope.launch {
                                    testResult = k to RelayController.testKey(k)
                                }
                            }) { Text("Test") }
                            TextButton(onClick = { editing = k; showDialog = true }) { Text("Edit") }
                            TextButton(onClick = { deleting = k }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
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
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    testResult?.let { (k, res) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text(if (res.first) "Key OK" else "Key test failed") },
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
                Text(
                    "Note: the 3 RPM limit applies per ACCOUNT — multiple keys from the same account share one limit. Add keys from different accounts for more throughput.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
