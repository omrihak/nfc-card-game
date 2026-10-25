package com.omrihak.nfccards.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Battery(val percent: Int, val charging: Boolean)

/** Small battery icon and percentage for the parent; the system status bar is hidden. */
@Composable
fun BatteryIndicator(modifier: Modifier = Modifier) {
    val battery = rememberBattery() ?: return
    val fill = when {
        battery.charging || battery.percent > 50 -> Color(0xFF7CD67C)
        battery.percent > 20 -> Color(0xFFFFC94D)
        else -> Color(0xFFFF6B6B)
    }
    val outline = Color.White.copy(alpha = 0.8f)

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "${battery.percent}%",
            style = TextStyle(color = outline, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
        Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
            val stroke = 1.5.dp.toPx()
            val tip = 2.5.dp.toPx()
            val body = Size(size.width - tip, size.height)
            drawRoundRect(
                outline,
                Offset(stroke / 2, stroke / 2),
                Size(body.width - stroke, body.height - stroke),
                CornerRadius(2.5.dp.toPx()),
                style = Stroke(stroke),
            )
            drawRect(outline, Offset(body.width, size.height * 0.3f), Size(tip, size.height * 0.4f))
            val inset = stroke * 2
            val inner = Size(body.width - inset * 2, body.height - inset * 2)
            drawRect(fill, Offset(inset, inset), Size(inner.width * battery.percent / 100f, inner.height))
            if (battery.charging) {
                val w = body.width
                val h = size.height
                drawPath(
                    Path().apply {
                        moveTo(w * 0.55f, h * 0.12f)
                        lineTo(w * 0.35f, h * 0.55f)
                        lineTo(w * 0.5f, h * 0.55f)
                        lineTo(w * 0.45f, h * 0.88f)
                        lineTo(w * 0.65f, h * 0.45f)
                        lineTo(w * 0.5f, h * 0.45f)
                        close()
                    },
                    Color.White,
                )
            }
        }
    }
}

/** Follows the sticky ACTION_BATTERY_CHANGED broadcast; null until the first one arrives. */
@Composable
private fun rememberBattery(): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                battery = parse(intent) ?: battery
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery = parse(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

private fun parse(intent: Intent): Battery? {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return Battery((level * 100 / scale).coerceIn(0, 100), charging)
}
