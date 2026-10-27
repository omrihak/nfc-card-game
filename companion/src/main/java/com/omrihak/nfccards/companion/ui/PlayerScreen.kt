package com.omrihak.nfccards.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omrihak.nfccards.companion.cards.Card as CardEntry
import com.omrihak.nfccards.companion.player.AdminStickers
import com.omrihak.nfccards.companion.player.Connection
import com.omrihak.nfccards.companion.player.Pairing
import com.omrihak.nfccards.companion.player.PlayerStatus
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Remote control for the player phone: what's playing, pause / resume, stop, volume, and play any card.
 * Until the player knows this phone, it shows the pairing code instead. Parent tools (settings, update,
 * release, admin stickers) sit folded away at the bottom of the controls.
 */
@Composable
fun PlayerScreen(
    connection: Connection,
    status: PlayerStatus?,
    cards: List<CardEntry>,
    signedIn: Boolean?,
    pairingToken: String,
    canWriteStickers: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
    onVolumeStep: (Int) -> Unit,
    onPlay: (CardEntry) -> Unit,
    onConnectManually: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onUpdate: () -> Unit,
    onRelease: () -> Unit,
    onWriteSticker: (CardEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val online = connection is Connection.Connected && status != null
    val tools: @Composable () -> Unit = {
        ParentToolsCard(status, online, canWriteStickers, onOpenSettings, onUpdate, onRelease, onWriteSticker)
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ConnectionCard(connection, onConnectManually) }
        if (connection is Connection.NotPaired) {
            item { PairCard(pairingToken, canWriteStickers, onWriteSticker) }
            item { tools() }
            return@LazyColumn
        }
        item { NowPlayingCard(status, online, onPause, onResume, onStop) }
        item { VolumeCard(status, online, onVolume, onVolumeStep) }
        item { tools() }
        item {
            Text("Play a card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        when {
            signedIn != true -> item {
                Text(
                    "Sign in on the Cards tab to list your cards here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cards.isEmpty() -> item {
                Text("No cards yet. Make one on the Cards tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> items(cards, key = { it.contentUrl }) { card ->
                val current = status?.card == card.contentUrl && status.state != "idle"
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (current) "Playing now" else typeLabel(card.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onPlay(card) }, enabled = online && !current) { Text("Play") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: Connection, onConnectManually: (String) -> Unit) {
    var showManual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, text) = when (connection) {
                    is Connection.Connected -> Color(0xFF2E9E4F) to "Connected to ${connection.name}"
                    is Connection.NotPaired -> Color(0xFF3B7DD8) to "Found ${connection.name}, not paired yet"
                    is Connection.Unreachable -> Color(0xFFD9822B) to "Can't reach ${connection.name}"
                    Connection.Searching -> Color.Gray to "Looking for the player on this Wi-Fi…"
                }
                Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            if (connection !is Connection.Connected && connection !is Connection.NotPaired) {
                Text(
                    "The player can only be controlled while it is on Wi-Fi, with the Tap and Play app open, " +
                        "and this phone is on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showManual) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Player's IP address") },
                        placeholder = { Text("192.168.1.23") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onConnectManually(address); showManual = false }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConnectManually(address); showManual = false }, enabled = address.isNotBlank()) {
                        Text("Connect")
                    }
                }
                Text(
                    "Find it on the player phone in Settings → Wi-Fi → (your network) → IP address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (connection !is Connection.Connected && connection !is Connection.NotPaired) {
                TextButton(onClick = { showManual = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("Enter the player's address instead")
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    status: PlayerStatus?,
    online: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val title = when {
                status == null -> "—"
                status.state == "idle" -> "Nothing (waiting for a card)"
                status.state == "loading" -> "Loading ${status.title ?: "a card"}…"
                else -> status.title ?: if (status.state == "video") "A video" else "A song"
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status != null && status.isPlayingSomething) {
                val position = status.positionMs ?: 0
                val duration = status.durationMs
                if (duration != null && duration > 0) {
                    LinearProgressIndicator(
                        progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    buildString {
                        append(if (status.paused) "Paused · " else "Playing · ")
                        append(formatTime(position))
                        if (duration != null && duration > 0) append(" / ").append(formatTime(duration))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val playing = online && status?.isPlayingSomething == true
                Button(
                    onClick = { if (status?.paused == true) onResume() else onPause() },
                    enabled = playing,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(if (status?.paused == true) "▶  Resume" else "❚❚  Pause", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = online && status?.state != "idle",
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("■  Stop", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(status: PlayerStatus?, online: Boolean, onVolume: (Int) -> Unit, onVolumeStep: (Int) -> Unit) {
    // While dragging, the slider shows the finger's position rather than the last polled volume.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = status?.maxVolume ?: 15
    val level = if (dragging) dragValue.roundToInt() else status?.volume ?: 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (status == null) "—" else "$level / $max", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { onVolumeStep(-1) }, enabled = online && level > 0) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Slider(
                        value = level.toFloat(),
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            onVolume(dragValue.roundToInt())
                        },
                        valueRange = 0f..max.toFloat(),
                        steps = (max - 1).coerceAtLeast(0),
                        enabled = online,
                    )
                }
                FilledTonalButton(onClick = { onVolumeStep(1) }, enabled = online && level < max) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun PairCard(token: String, canWriteStickers: Boolean, onWriteSticker: (CardEntry) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pair with the player", style = MaterialTheme.typography.titleLarge)
            Text(
                "Hold this up to the player's camera (the small circle in its corner). " +
                    "The player bounces with a green ring, and this page turns into the remote control.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            QrCode(Pairing.uri(token), Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            if (canWriteStickers) {
                TextButton(onClick = { onWriteSticker(AdminStickers.pairing(token)) }) {
                    Text("Or write it to a sticker and tap that on the player")
                }
            }
            Text(
                "Only phones paired this way can control the player. Keep the code to yourself: " +
                    "anyone who scans it can pair too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Black on white whatever the theme, with a quiet zone, so a camera reads it easily. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { Pairing.qr(text) }
    Canvas(modifier.background(Color.White)) {
        // Whole pixels per module, centred: no hairline gaps between modules.
        val cell = floor(minOf(size.width / matrix.width, size.height / matrix.height))
        val origin = Offset((size.width - cell * matrix.width) / 2f, (size.height - cell * matrix.height) / 2f)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun ParentToolsCard(
    status: PlayerStatus?,
    online: Boolean,
    canWriteStickers: Boolean,
    onOpenSettings: () -> Unit,
    onUpdate: () -> Unit,
    onRelease: () -> Unit,
    onWriteSticker: (CardEntry) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmRelease by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Parent tools",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status != null) {
                Text(
                    "Player version ${status.version ?: "?"} · " +
                        if (status.deviceOwner) "full kiosk mode" else "not in full kiosk mode",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            updateLine(status)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status?.update == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenSettings, enabled = online, modifier = Modifier.fillMaxWidth()) {
                Text("Open settings on player")
            }
            OutlinedButton(
                onClick = onUpdate,
                enabled = online && status?.deviceOwner == true && !status.isUpdating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Update player")
            }
            if (status != null && !status.deviceOwner) {
                Text(
                    "The player can only update itself in full kiosk mode. Otherwise install the new " +
                        "version on the player phone by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { confirmRelease = true },
                enabled = online,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove kiosk (release)…")
            }
            Text(
                "Admin stickers, a backup for when there's no Wi-Fi or this phone is lost. Tap one on the " +
                    "player. Keep them away from the toddler.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onWriteSticker(AdminStickers.settings) },
                    enabled = canWriteStickers,
                    modifier = Modifier.weight(1f),
                ) { Text("Settings sticker") }
                FilledTonalButton(
                    onClick = { onWriteSticker(AdminStickers.release) },
                    enabled = canWriteStickers,
                    modifier = Modifier.weight(1f),
                ) { Text("Release sticker") }
            }
        }
    }

    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text("Remove kiosk mode for good?") },
            text = {
                Text(
                    "The player gives up device owner permanently: it no longer locks the toddler in, and " +
                        "the status bar, Home and the lock screen come back. It then opens Settings, so the app " +
                        "can be uninstalled. Turning full kiosk mode on again means setting the phone up again " +
                        "(usually a factory reset).\n\nTo just change a setting, use \"Open settings on player\".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelease = false
                    onRelease()
                }) { Text("Remove kiosk", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Cancel") } },
        )
    }
}

private fun updateLine(status: PlayerStatus?): String? = when (status?.update) {
    "downloading" -> "Downloading the update…"
    "installing" -> "Installing… The player restarts by itself in a moment."
    "failed" -> "Update failed: ${status.updateMessage ?: "unknown error"}"
    else -> status?.updateMessage
}

internal fun typeLabel(type: String) = if (type == "video") "Video" else "Song"

private fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
