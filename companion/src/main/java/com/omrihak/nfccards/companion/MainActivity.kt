package com.omrihak.nfccards.companion

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.omrihak.nfccards.companion.cards.Card as CardEntry
import com.omrihak.nfccards.companion.cards.CardsViewModel
import com.omrihak.nfccards.companion.cards.StickerWriter
import com.omrihak.nfccards.companion.player.Connection
import com.omrihak.nfccards.companion.player.RemoteViewModel
import com.omrihak.nfccards.companion.ui.CardsScreen
import com.omrihak.nfccards.companion.ui.PlayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The parent's companion app: a remote control for the player phone (over the home Wi-Fi), and
 * the card manager (cards in the parent's Google Drive, like the web manager).
 */
class MainActivity : ComponentActivity() {

    private val remote: RemoteViewModel by viewModels()
    private val cards: CardsViewModel by viewModels()
    private lateinit var stickers: StickerWriter

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            cards.onSignInFailed(Exception("cancelled"))
            return@registerForActivityResult
        }
        try {
            cards.auth.resultFrom(result.data)
            cards.onSignedIn()
        } catch (e: Exception) {
            cards.onSignInFailed(e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        stickers = StickerWriter(this)
        setContent {
            CompanionTheme {
                CompanionApp(remote, cards, stickers, ::signIn, ::copyLink, ::openWebManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        remote.setActive(true)
    }

    override fun onStop() {
        remote.setActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        stickers.onResume()
    }

    override fun onPause() {
        stickers.onPause()
        super.onPause()
    }

    private fun signIn() {
        lifecycleScope.launch {
            try {
                val result = cards.auth.authorize()
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    signInLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    cards.onSignedIn()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cards.onSignInFailed(e)
            }
        }
    }

    private fun copyLink(card: CardEntry) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(card.name, card.contentUrl))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) cards.showMessage("Link copied")
    }

    private fun openWebManager() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_MANAGER_URL)))
    }

    private companion object {
        const val WEB_MANAGER_URL = "https://omrihak.github.io/nfc-card-game/manager/"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionApp(
    remote: RemoteViewModel,
    cardsVm: CardsViewModel,
    stickers: StickerWriter,
    onSignIn: () -> Unit,
    onCopyLink: (CardEntry) -> Unit,
    onOpenWebManager: () -> Unit,
) {
    val connection by remote.connection.collectAsState()
    val status by remote.status.collectAsState()
    val remoteError by remote.error.collectAsState()
    val signedIn by cardsVm.signedIn.collectAsState()
    val cards by cardsVm.cards.collectAsState()
    val loading by cardsVm.loading.collectAsState()
    val progress by cardsVm.progress.collectAsState()
    val message by cardsVm.message.collectAsState()
    val playerOnline = connection is Connection.Connected && status != null

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            cardsVm.clearMessage()
        }
    }
    LaunchedEffect(remoteError) {
        remoteError?.let {
            snackbar.showSnackbar(it)
            remote.clearError()
        }
    }

    val playCard: (CardEntry) -> Unit = { card ->
        remote.play(card.contentUrl)
        tab = 0
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == 0) "Player" else "Cards") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▶") }, label = { Text("Player") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▦") }, label = { Text("Cards") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        if (tab == 0) {
            PlayerScreen(
                connection = connection,
                status = status,
                cards = cards,
                signedIn = signedIn,
                onPause = remote::pause,
                onResume = remote::resume,
                onStop = remote::stop,
                onVolume = remote::setVolume,
                onVolumeStep = remote::stepVolume,
                onPlay = playCard,
                onConnectManually = remote::connectManually,
                modifier = modifier,
            )
        } else {
            CardsScreen(
                signedIn = signedIn,
                cards = cards,
                loading = loading,
                progress = progress,
                playerOnline = playerOnline,
                canWriteStickers = stickers.isAvailable,
                onSignIn = onSignIn,
                onRefresh = cardsVm::refresh,
                onCreate = cardsVm::create,
                onPlay = playCard,
                onWriteSticker = stickers::begin,
                onCopyLink = onCopyLink,
                onRemove = cardsVm::remove,
                onOpenWebManager = onOpenWebManager,
                modifier = modifier,
            )
        }
    }

    StickerDialog(stickers.state, onDismiss = stickers::dismiss)
}

@Composable
private fun StickerDialog(state: StickerWriter.State, onDismiss: () -> Unit) {
    val (title, text) = when (state) {
        StickerWriter.State.Idle -> return
        is StickerWriter.State.Waiting ->
            "Write \"${state.card.name}\"" to "Hold a sticker flat against the back of this phone, near the camera."
        is StickerWriter.State.Done -> "Written ✓" to "The sticker now plays \"${state.card.name}\"."
        is StickerWriter.State.Failed -> "Couldn't write the sticker" to state.reason
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        icon = if (state is StickerWriter.State.Waiting) ({ CircularProgressIndicator() }) else null,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (state is StickerWriter.State.Waiting) "Cancel" else "OK") }
        },
    )
}

@Composable
private fun CompanionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
