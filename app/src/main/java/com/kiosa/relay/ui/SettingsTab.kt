package com.kiosa.relay.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        SectionCard("SERVER", subtitle = "Port and LAN changes need a server restart (Stop → Start).") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listen on LAN", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Desktop agents connect via http://<phone-ip>:port. Off = this phone only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Require access key", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Agents must send this key as Bearer token / x-api-key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.requireAccessKey,
                    onCheckedChange = { RelayController.updateSettings { s -> s.copy(requireAccessKey = it) } },
                )
            }
            CopyField("Relay access key", settings.accessKey, secret = true)
            OutlinedButton(onClick = {
                RelayController.regenerateAccessKey()
                Toast.makeText(ctx, "New access key generated", Toast.LENGTH_SHORT).show()
            }) { Text("Regenerate access key") }
        }

        SectionCard("RATE LIMITS & TIMING") {
            NumField("429 cooldown (seconds)", cooldown, { cooldown = it })
            NumField("Error cooldown (seconds)", errorCooldown, { errorCooldown = it })
            NumField("Auth error cooldown (401/403, seconds)", authCooldown, { authCooldown = it })
            NumField("Max wait when all keys cooling (0 = fail fast)", maxWait, { maxWait = it })
            NumField("Upstream request timeout (seconds)", timeout, { timeout = it })
            OutlinedTextField(
                value = upstream,
                onValueChange = { upstream = it },
                label = { Text("Upstream base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Save changes") }
        }

        SectionCard("HOW IT WORKS") {
            Text(
                "• Keys are used strictly in list order — key 1 until its 3 RPM is exhausted, then key 2, then key 3. Never round-robin.\n" +
                    "• A 429 puts that key on cooldown (${settings.cooldownSec}s) and the next key takes over instantly.\n" +
                    "• If every key is cooling, the relay waits for the soonest revival (up to ${settings.maxWaitSec}s) so your agent just waits instead of crashing.\n" +
                    "• The 3 RPM limit is per ACCOUNT: keys from one account share it. Different accounts = real extra capacity.\n" +
                    "• /v1/models does not count against the chat quota.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("ABOUT") {
            Text("Kiosa Relay v1.0.1", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Local multi-key relay for KiosAPI.\n" +
                    "Keys are stored encrypted on-device — never committed, never synced.\n" +
                    "github.com/HakaI963/kiosa-relay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
