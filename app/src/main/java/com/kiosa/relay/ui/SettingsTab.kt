package com.kiosa.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.kiosa.relay.RelayController

@Composable
fun SettingsTab() {
    val settings by RelayController.settings.collectAsState()
    val ctx = LocalContext.current

    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var cooldown by remember(settings.cooldownSec) { mutableStateOf(settings.cooldownSec.toString()) }
    var errorCooldown by remember(settings.errorCooldownSec) { mutableStateOf(settings.errorCooldownSec.toString()) }
    var authCooldown by remember(settings.authCooldownSec) { mutableStateOf(settings.authCooldownSec.toString()) }
    var maxWait by remember(settings.maxWaitSec) { mutableStateOf(settings.maxWaitSec.toString()) }
    var timeout by remember(settings.requestTimeoutSec) { mutableStateOf(settings.requestTimeoutSec.toString()) }
    var upstream by remember(settings.upstreamBaseUrl) { mutableStateOf(settings.upstreamBaseUrl) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("SERVER") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listen on LAN", fontWeight = FontWeight.SemiBold)
                    Text("Desktop agents connect via http://<phone-ip>:port. Off = localhost only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.lanEnabled,
                    onCheckedChange = { RelayController.updateSettings { s -> s.copy(lanEnabled = it) } },
                )
            }
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Require relay access key", fontWeight = FontWeight.SemiBold)
                    Text("Agents must send this key as Bearer token.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.requireAccessKey,
                    onCheckedChange = { RelayController.updateSettings { s -> s.copy(requireAccessKey = it) } },
                )
            }
            CopyRow("Relay access key", settings.accessKey, secret = true)
            TextButton(onClick = {
                RelayController.regenerateAccessKey()
                Toast.makeText(ctx, "New access key generated", Toast.LENGTH_SHORT).show()
            }) { Text("Regenerate access key") }
            Text(
                "Changing port or LAN setting requires a server restart (Stop → Start).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("RATE LIMITS & TIMING") {
            OutlinedTextField(value = cooldown, onValueChange = { cooldown = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("429 cooldown (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = errorCooldown, onValueChange = { errorCooldown = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Error cooldown (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(value = authCooldown, onValueChange = { authCooldown = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Auth error cooldown (seconds, 401/403)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(value = maxWait, onValueChange = { maxWait = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Max wait when all keys cooling (seconds, 0 = fail fast)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(value = timeout, onValueChange = { timeout = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Upstream request timeout (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(
                value = upstream,
                onValueChange = { upstream = it },
                label = { Text("Upstream base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = {
                    RelayController.updateSettings { s ->
                        s.copy(
                            port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: s.port,
                            cooldownSec = cooldown.toIntOrNull()?.coerceIn(5, 3600) ?: s.cooldownSec,
                            errorCooldownSec = errorCooldown.toIntOrNull()?.coerceIn(1, 3600) ?: s.errorCooldownSec,
                            authCooldownSec = authCooldown.toIntOrNull()?.coerceIn(10, 86400) ?: s.authCooldownSec,
                            maxWaitSec = maxWait.toIntOrNull()?.coerceIn(0, 3600) ?: s.maxWaitSec,
                            requestTimeoutSec = timeout.toIntOrNull()?.coerceIn(30, 3600) ?: s.requestTimeoutSec,
                            upstreamBaseUrl = upstream.trim(),
                        )
                    }
                    Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Save rate & server settings") }
        }

        SectionCard("APPEARANCE") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { RelayController.updateSettings { s -> s.copy(themeMode = mode) } },
                        label = { Text(label) },
                    )
                }
            }
        }

        SectionCard("HOW IT WORKS") {
            Text(
                "• Keys are tried strictly in list order — key 1 until its 3 RPM is exhausted, then key 2, then key 3… never round-robin.\n" +
                    "• A 429 response puts that key on cooldown (${settings.cooldownSec}s by default) and the next key takes over instantly.\n" +
                    "• If every key is cooling, the relay waits for the soonest revival (up to ${settings.maxWaitSec}s) so your coding agent doesn't crash — it just waits.\n" +
                    "• The 3 RPM limit is per ACCOUNT: keys from one account share it. Add keys from different accounts for real extra capacity.\n" +
                    "• /v1/models requests don't count against the chat quota.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("ABOUT") {
            Text("Kiosa Relay v1.0.0", fontWeight = FontWeight.SemiBold)
            Text(
                "Local multi-key relay for KiosAPI (https://kiosapi.com).\n" +
                    "Keys are stored encrypted on-device and never committed or synced.\n" +
                    "github.com/HakaI963/kiosa-relay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
