package com.omrihak.nfccards.companion.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.omrihak.nfccards.companion.setup.ProvisioningQr

private const val WEB_SETUP_URL = "https://omrihak.github.io/nfc-card-game/manager/setup.html"

/**
 * Turns an old phone into a locked-down player: factory reset, tap the welcome screen six times,
 * scan the QR code shown here. See README, "Kiosk mode".
 */
@Composable
fun SetupScreen(pairingToken: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The player is signed with the same key as this app, so this app's own certificate is the player's.
    val checksum = remember { ownChecksum(context) }
    // The Wi-Fi name isn't prefilled: reading it needs the location permission.
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var security by rememberSaveable { mutableStateOf(ProvisioningQr.Security.WPA) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BeforeYouStart()

        Text("Steps", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Charge the old phone.",
            "Factory reset it: Settings → System → Reset → Erase all data (on Samsung: General management → Reset).",
            "On the very first welcome screen, tap the same spot 6 times quickly. A QR code reader opens " +
                "(older phones may ask for Wi-Fi first to download it).",
            "Scan the QR code below.",
            "Wait. The phone downloads Tap and Play, installs it and locks itself into it. This takes a few minutes. " +
                "It is already paired with this phone: the Player tab finds it on the Wi-Fi by itself.",
        ).forEachIndexed { i, step ->
            Row {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 24.dp))
                Text(step)
            }
        }

        Text("Wi-Fi for the player", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Optional. Without it, the phone asks for Wi-Fi during setup. The password only goes into the QR code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it.take(32) },
            label = { Text("Wi-Fi name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.selectableGroup()) {
            listOf(
                ProvisioningQr.Security.WPA to "WPA / WPA2",
                ProvisioningQr.Security.WEP to "WEP",
                ProvisioningQr.Security.NONE to "Open",
            ).forEach { (value, label) ->
                Row(
                    Modifier
                        .selectable(selected = security == value, role = Role.RadioButton, onClick = { security = value })
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = security == value, onClick = null)
                    Text(label, Modifier.padding(start = 4.dp))
                }
            }
        }
        if (security != ProvisioningQr.Security.NONE) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(63) },
                label = { Text("Wi-Fi password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.toggleable(value = hidden, role = Role.Checkbox, onValueChange = { hidden = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = hidden, onCheckedChange = null)
            Text("Hidden network", Modifier.padding(start = 4.dp))
        }

        if (checksum == null) {
            Text("Couldn't read this app's signature, so there is no QR code.", color = MaterialTheme.colorScheme.error)
        } else {
            if (ProvisioningQr.isDebugKey(checksum)) {
                Text(
                    "This app is a development build (signed with the checked-in debug key). The QR code only works " +
                        "if the downloaded player is signed with the same key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val json = ProvisioningQr.json(checksum, ProvisioningQr.Wifi(ssid.trim(), password, security, hidden), pairingToken)
            Text("Scan this with the reset phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "It also pairs the player with this phone, so keep it to yourself, like a key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QrCode(json)
        }
    }
}

@Composable
private fun BeforeYouStart() {
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Before you start", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Make the parent cards first: Settings and Release. Once locked, they are the only way back into " +
                    "the phone's settings (otherwise only another factory reset). Write them to stickers from the Player tab, " +
                    "or print them from the web page.",
            )
            Text("• Don't add a Google account to the old phone. Android refuses to set it up this way if it has one.")
            Text("• Everything on the old phone is erased.")
            TextButton(onClick = { uriHandler.openUri(WEB_SETUP_URL) }, contentPadding = PaddingValues(0.dp)) {
                Text("Print parent cards (web page)")
            }
        }
    }
}

/** Big, black on white with a quiet zone, whatever the theme. Keeps the screen on and bright while shown. */
@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) { ProvisioningQr.matrix(text) }
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White)
            .semantics { contentDescription = "Setup QR code" },
    ) {
        val module = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // Slightly oversized squares, so no hairline gaps show between neighbours.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * module, y * module), Size(module + 0.5f, module + 0.5f))
            }
        }
    }
}

/** Checksum of the certificate this app is signed with, or null if it can't be read. */
private fun ownChecksum(context: Context): String? = runCatching {
    val pm = context.packageManager
    val cert = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }
    cert?.let { ProvisioningQr.checksumOf(it.toByteArray()) }
}.getOrNull()
