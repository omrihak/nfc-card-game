package com.omrihak.nfccards

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.omrihak.nfccards.kiosk.Kiosk
import com.omrihak.nfccards.nfc.NfcReader
import com.omrihak.nfccards.qr.QrScanner
import com.omrihak.nfccards.ui.AudioScreen
import com.omrihak.nfccards.ui.BatteryIndicator
import com.omrihak.nfccards.ui.CameraBubble
import com.omrihak.nfccards.ui.GameScreen
import com.omrihak.nfccards.ui.IdleScreen
import com.omrihak.nfccards.ui.VideoScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var nfcReader: NfcReader
    private lateinit var kiosk: Kiosk
    private lateinit var qrScanner: QrScanner
    private var askedForCamera = false

    /** Screen off and camera off after [IDLE_SLEEP_MS] without music; see [watchIdle]. */
    private var sleeping = false

    /** Black, lowest brightness: what sleeping looks like when the screen can't really be turned off. */
    private var dimmed by mutableStateOf(false)
    private var lastBusyAt = SystemClock.elapsedRealtime()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) qrScanner.start() else Log.w(TAG, "Camera permission denied; QR scanning off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Show over a (non-secure) lock screen and wake the screen when launched.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        kiosk = Kiosk(this)
        nfcReader = NfcReader(this) { uri -> runOnUiThread { onCard(uri) } }
        qrScanner = QrScanner(this) { text -> onCard(text) }

        setContent { App(viewModel, qrScanner, dimmed) }
        watchIdle()

        // The companion app's "Open settings" / "Remove kiosk": same as the admin cards.
        lifecycleScope.launch {
            viewModel.adminCards.collect { kiosk.onAdminCard(it) }
        }

        // Debug builds only: say why a card failed (the toddler UI never does).
        if (isDebuggable()) {
            lifecycleScope.launch {
                viewModel.debugMessages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
        }

        // A tap while the app was closed launches it with the tag in the intent.
        if (savedInstanceState == null) handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED ->
                onCard(NfcReader.uriFrom(intent) ?: intent.dataString)
            // Debug builds only: simulate a card tap without a tag, e.g.
            // adb shell am start -n com.omrihak.nfccards/.MainActivity -a android.intent.action.VIEW -d "<card url>"
            Intent.ACTION_VIEW ->
                if (isDebuggable()) onCard(intent.dataString)
        }
    }

    private fun onCard(uri: String?) {
        if (uri != null && (kiosk.onAdminCard(uri) || viewModel.onPairingCard(uri))) return
        viewModel.onCardScanned(uri)
    }

    /** A touch wakes the player (when the screen is only dimmed, or after the parent turned it on). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastBusyAt = SystemClock.elapsedRealtime()
        if (sleeping) wake()
    }

    /**
     * To save battery: when nothing has played for a minute, stop the camera and let the screen go
     * off (in kiosk mode it is turned off right away; otherwise the system's own timeout does it).
     * Anything playing again, a touch, or the screen coming back on wakes it.
     */
    private fun watchIdle() {
        lifecycleScope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val now = SystemClock.elapsedRealtime()
                if (viewModel.isBusy()) {
                    lastBusyAt = now
                    if (sleeping) wake()
                } else if (!sleeping && now - lastBusyAt >= IDLE_SLEEP_MS) {
                    sleep()
                }
            }
        }
    }

    private fun sleep() {
        sleeping = true
        qrScanner.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kiosk.sleepScreen()
        applyDimmed(true) // black and dark right away, even if the screen then stays on until its timeout
    }

    private fun applyDimmed(on: Boolean) {
        dimmed = on
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun wake() {
        sleeping = false
        lastBusyAt = SystemClock.elapsedRealtime()
        applyDimmed(false)
        kiosk.enter() // puts "stay on while charging" back
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startQrScanner()
    }

    override fun onResume() {
        super.onResume()
        if (sleeping) wake() else lastBusyAt = SystemClock.elapsedRealtime()
        kiosk.enter()
        nfcReader.start()
        startQrScanner()
    }

    override fun onPause() {
        nfcReader.stop()
        super.onPause()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        qrScanner.release()
        super.onDestroy()
    }

    /** In kiosk mode the permission is granted by [Kiosk.enter]; otherwise ask once. */
    private fun startQrScanner() {
        when {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> qrScanner.start()
            !askedForCamera -> {
                askedForCamera = true
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private companion object {
        const val TAG = "MainActivity"
        const val IDLE_SLEEP_MS = 60_000L
        const val IDLE_CHECK_MS = 2_000L
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun App(viewModel: MainViewModel, qrScanner: QrScanner, dimmed: Boolean) {
    val state by viewModel.state.collectAsState()
    val problems by viewModel.problems.collectAsState()
    val pairings by viewModel.pairings.collectAsState()
    val stars by viewModel.stars.collectAsState()

    BackHandler(enabled = true) { /* the toddler can't leave */ }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            UiState.Idle, UiState.Loading ->
                IdleScreen(busy = s == UiState.Loading, problems = problems, pairings = pairings, stars = stars)
            is UiState.Audio -> AudioScreen(artwork = s.artwork, player = viewModel.player, onStop = viewModel::stop)
            UiState.Video -> VideoScreen(player = viewModel.player, onStop = viewModel::stop)
            is UiState.Game -> GameScreen(hint = s.hint, stars = stars, onStop = viewModel::stop)
        }
        if (qrScanner.isRunning) {
            CameraBubble(
                qrScanner.previewView,
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(8.dp),
            )
        }
        BatteryIndicator(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(8.dp),
        )
        if (dimmed) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
