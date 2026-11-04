package com.kiosa.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiosa.relay.RelayController
import com.kiosa.relay.ServerState
import com.kiosa.relay.core.KeyStatus
import kotlinx.coroutines.delay

@Composable
fun RelayTab() {
    val ctx = LocalContext.current
    val snapshot by RelayController.engine.snapshot.collectAsState()
    val settings by RelayController.settings.collectAsState()
    val serverState by RelayController.serverState.collectAsState()

    // 1s ticker so cooldown countdowns keep ticking between engine events
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val running = serverState == ServerState.RUNNING
    val port = settings.port
    val ip = RelayController.lanIp() ?: "127.0.0.1"

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("SERVER") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (running) KeyStatus.ACTIVE else KeyStatus.IDLE)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (serverState) {
                            ServerState.RUNNING -> "Running on :$port"
                            ServerState.STARTING -> "Starting…"
                            ServerState.ERROR -> "Failed to start (check port)"
                            else -> "Stopped"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (settings.lanEnabled) "Listening on LAN — desktop agents can connect" else "localhost only — apps on this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    OutlinedButton(onClick = { RelayController.stopRelay(ctx) }) { Text("Stop") }
                } else {
                    Button(onClick = { RelayController.startRelay(ctx) }) { Text("Start") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pause relay (503 for new requests)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = settings.paused,
                    enabled = running,
                    onCheckedChange = { RelayController.updateSettings { s -> s.copy(paused = it) } },
                )
            }
        }

        SectionCard("ENDPOINT FOR CODING AGENTS") {
            CopyRow("OpenAI base URL", "http://$ip:$port/v1")
            CopyRow("Anthropic base URL (Claude Code)", "http://$ip:$port")
            CopyRow("Relay access key", settings.accessKey, secret = true)
            Text(
                "Claude Code:  ANTHROPIC_BASE_URL=http://$ip:$port  ·  ANTHROPIC_AUTH_TOKEN=<access key>\n" +
                    "OpenAI agents:  base_url = http://$ip:$port/v1  ·  api_key = <access key>\n" +
                    "Phone and computer must be on the same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("POOL STATUS") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatBox("relayed", snapshot.stats.relayed.toString(), OkGreen)
                StatBox("429s", snapshot.stats.rateLimited.toString(), WarnAmber)
                StatBox("errors", snapshot.stats.errors.toString(), ErrRed)
                StatBox("waits", snapshot.stats.waits.toString(), CyanGrey)
            }
            Spacer(Modifier.height(10.dp))
            if (snapshot.keys.isEmpty()) {
                Text(
                    "No keys yet — add API keys in the Keys tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.keys.forEach { k ->
                val remaining = ((k.cooldownUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(k.status)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "ok ${k.success}  ·  429s ${k.rateLimited}  ·  fail ${k.fail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (k.status) {
                        KeyStatus.COOLDOWN -> StatusChip("cooldown ${remaining}s", WarnAmber)
                        KeyStatus.ACTIVE -> StatusChip("active", OkGreen)
                        KeyStatus.ERROR -> StatusChip("error", ErrRed)
                        KeyStatus.DISABLED -> StatusChip("disabled", Color.Gray)
                        else -> StatusChip("idle", CyanGrey)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Strategy: keys are used strictly in order until each is rate-limited (3 RPM), then the next one. No round-robin. A 429 puts a key on a ${settings.cooldownSec}s cooldown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("RECENT ACTIVITY") {
            if (snapshot.log.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            snapshot.log.take(5).forEach { l ->
                Text(
                    "[${l.kind}] ${l.key}: ${l.msg}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}
