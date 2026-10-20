package com.omrihak.nfccards

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.omrihak.nfccards.nfc.NfcReader
import com.omrihak.nfccards.ui.AudioScreen
import com.omrihak.nfccards.ui.IdleScreen
import com.omrihak.nfccards.ui.VideoScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var nfcReader: NfcReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        nfcReader = NfcReader(this) { uri -> viewModel.onCardScanned(uri) }

        setContent { App(viewModel) }

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
                viewModel.onCardScanned(NfcReader.uriFrom(intent) ?: intent.dataString)
            // Debug builds only: simulate a card tap without a tag, e.g.
            // adb shell am start -n com.omrihak.nfccards/.MainActivity -a android.intent.action.VIEW -d "<card url>"
            Intent.ACTION_VIEW ->
                if (isDebuggable()) viewModel.onCardScanned(intent.dataString)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcReader.start()
    }

    override fun onPause() {
        nfcReader.stop()
        super.onPause()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun App(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val problems by viewModel.problems.collectAsState()

    BackHandler(enabled = true) { /* the toddler can't leave */ }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            UiState.Idle, UiState.Loading -> IdleScreen(busy = s == UiState.Loading, problems = problems)
            is UiState.Audio -> AudioScreen(artwork = s.artwork, player = viewModel.player)
            UiState.Video -> VideoScreen(player = viewModel.player)
        }
    }
}
