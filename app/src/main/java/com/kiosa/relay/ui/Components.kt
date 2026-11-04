package com.kiosa.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiosa.relay.R
import com.kiosa.relay.core.KeyStatus

fun statusColor(status: KeyStatus): Color = when (status) {
    KeyStatus.ACTIVE -> OkGreen
    KeyStatus.IDLE -> CyanGrey
    KeyStatus.COOLDOWN -> WarnAmber
    KeyStatus.ERROR -> ErrRed
    KeyStatus.DISABLED -> Color(0xFF9AA7AC)
}

fun logKindColor(kind: String): Color = when (kind) {
    "ok" -> OkGreen
    "cooldown" -> WarnAmber
    "error", "reject" -> ErrRed
    else -> CyanGrey
}

@Composable
fun StatusDot(status: KeyStatus, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .background(statusColor(status), CircleShape),
    )
}

@Composable
fun StatusPill(status: KeyStatus, cooldownSec: Int? = null) {
    val c = statusColor(status)
    val label = when (status) {
        KeyStatus.COOLDOWN -> if (cooldownSec != null && cooldownSec > 0) "cooldown ${cooldownSec}s" else "cooldown"
        KeyStatus.ACTIVE -> "active"
        KeyStatus.ERROR -> "error"
        KeyStatus.DISABLED -> "disabled"
        KeyStatus.IDLE -> "idle"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, accent: Color) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CopyField(label: String, value: String, secret: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var revealed by remember { mutableStateOf(!secret) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (revealed) value else maskSecret(value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (secret) {
                IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        painter = painterResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = if (revealed) "Hide" else "Show",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun CodeBlock(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = CyanInk,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LightCyan.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

private fun maskSecret(v: String): String =
    if (v.length > 14) v.take(7) + "••••••••" + v.takeLast(4) else "••••••••••••"
