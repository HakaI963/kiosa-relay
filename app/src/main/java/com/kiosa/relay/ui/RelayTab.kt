package com.kiosa.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiosa.relay.R
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- hero ----------
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(CyanDeep, Color(0xFF0E93AC), Cyan))),
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_notification),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "KIOSA RELAY",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    if (settings.paused && running) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PAUSED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(13.dp)
                            .background(if (running) Color(0xFFB8FFE3) else Color.White.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (serverState) {
                            ServerState.RUNNING -> "Relay running"
                            ServerState.STARTING -> "Starting…"
                            ServerState.ERROR -> "Start failed — check port"
                            else -> "Relay stopped"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (running) "http://$ip:$port" else "Start the server to open the endpoint",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    if (running) {
                        OutlinedButton(
                            onClick = { RelayController.stopRelay(ctx) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_stop),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { RelayController.startRelay(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CyanDeep),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_play),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Start relay")
                        }
                    }
                }
                if (running) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Pause relay — new requests get 503",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.paused,
                            onCheckedChange = { RelayController.updateSettings { s -> s.copy(paused = it) } },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color.White,
                                checkedThumbColor = CyanDeep,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.35f),
                                uncheckedThumbColor = Color.White,
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }

        // ---------- endpoint ----------
        SectionCard(
            "ENDPOINT FOR CODING AGENTS",
            subtitle = "Copy the URL and access key into your agent — phone and computer must share the same Wi-Fi.",
        ) {
            CopyField("OpenAI base URL", "http://$ip:$port/v1")
            CopyField("Anthropic base URL (Claude Code)", "http://$ip:$port")
            CopyField("Relay access key", settings.accessKey, secret = true)
            Spacer(Modifier.height(6.dp))
            CodeBlock(
                "Claude Code:\n" +
                    "  ANTHROPIC_BASE_URL=http://$ip:$port\n" +
                    "  ANTHROPIC_AUTH_TOKEN=<access key>\n\n" +
                    "OpenAI agents (Codex, Cline, Aider…):\n" +
                    "  base_url = http://$ip:$port/v1\n" +
                    "  api_key  = <access key>",
            )
            if (!settings.lanEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "LAN mode is off — use http://127.0.0.1:$port from apps on this phone only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                )
            }
        }

        // ---------- pool ----------
        SectionCard("KEY POOL", subtitle = "Strict order: key 1 until rate-limited, then key 2… never round-robin.") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatTile("relayed", snapshot.stats.relayed.toString(), OkGreen)
                StatTile("429 hits", snapshot.stats.rateLimited.toString(), WarnAmber)
                StatTile("errors", snapshot.stats.errors.toString(), ErrRed)
                StatTile("waits", snapshot.stats.waits.toString(), CyanGrey)
            }
            Spacer(Modifier.height(14.dp))
            if (snapshot.keys.isEmpty()) {
                EmptyHint("No keys yet — add your first API key in the Keys tab below.")
            }
            snapshot.keys.forEach { k ->
                val remaining = ((k.cooldownUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "ok ${k.success} · 429s ${k.rateLimited} · fail ${k.fail}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(k.status, remaining.toInt())
                    }
                    if (k.status == KeyStatus.COOLDOWN) {
                        Spacer(Modifier.height(6.dp))
                        val totalSec = settings.cooldownSec.coerceAtLeast(1).toFloat()
                        val progress = (1f - remaining / totalSec).coerceIn(0.02f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = WarnAmber,
                            trackColor = WarnAmber.copy(alpha = 0.15f),
                        )
                    }
                    if (k.lastError != null && k.status == KeyStatus.ERROR) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            k.lastError ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = ErrRed,
                            maxLines = 2,
                        )
                    }
                }
            }
        }

        // ---------- activity ----------
        SectionCard("RECENT ACTIVITY") {
            if (snapshot.log.isEmpty()) {
                EmptyHint("Nothing yet — activity appears here once the relay runs.")
            }
            snapshot.log.take(6).forEach { l ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(7.dp)
                            .background(logKindColor(l.kind), androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${l.key}: ${l.msg}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (snapshot.log.size > 6) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "… ${snapshot.log.size - 6} more — see the Log tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
