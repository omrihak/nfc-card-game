package com.omrihak.nfccards.companion.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omrihak.nfccards.companion.cards.Card as CardEntry
import com.omrihak.nfccards.companion.cards.CardStore
import com.omrihak.nfccards.companion.cards.Progress

/** The web manager's features: sign in to Drive, make a card, and the list of cards. */
@Composable
fun CardsScreen(
    signedIn: Boolean?,
    cards: List<CardEntry>,
    loading: Boolean,
    progress: Progress?,
    playerOnline: Boolean,
    canWriteStickers: Boolean,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: (name: String, type: String, media: Uri, image: Uri?, onDone: () -> Unit) -> Unit,
    onCreateWord: (name: String, words: Map<String, CardStore.Word>, image: Uri?, onDone: () -> Unit) -> Unit,
    onCreateGame: (name: String, lang: String, rounds: Int?, deck: List<CardEntry>, image: Uri?, onDone: () -> Unit) -> Unit,
    onPlay: (CardEntry) -> Unit,
    onWriteSticker: (CardEntry) -> Unit,
    onCopyLink: (CardEntry) -> Unit,
    onRemove: (CardEntry) -> Unit,
    onOpenWebManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (signedIn) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> SignIn(onSignIn, modifier)
        true -> {
            var confirmRemove by remember { mutableStateOf<CardEntry?>(null) }
            LazyColumn(
                modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NewCardForm(progress, onCreate, onCreateWord) }
                item { NewGameForm(cards, progress, onCreateGame) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Cards", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (loading) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = onRefresh) { Text("Refresh") }
                        }
                    }
                }
                if (cards.isEmpty() && !loading) {
                    item { Text("No cards yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(cards, key = { it.contentUrl }) { card ->
                    CardRow(
                        card,
                        playerOnline,
                        canWriteStickers,
                        onPlay = { onPlay(card) },
                        onWriteSticker = { onWriteSticker(card) },
                        onCopyLink = { onCopyLink(card) },
                        onRemove = { confirmRemove = card },
                    )
                }
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            "To print cards (picture on the front, QR code on the back), use the web card manager.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenWebManager, contentPadding = PaddingValues(0.dp)) {
                            Text("Open the web card manager")
                        }
                    }
                }
            }
            confirmRemove?.let { card ->
                AlertDialog(
                    onDismissRequest = { confirmRemove = null },
                    title = { Text("Remove \"${card.name}\"?") },
                    text = {
                        Text(
                            "Its Drive folder goes to the trash (you can restore it from there for 30 days). " +
                                "Stickers and printed cards with this card's link stop working on players that haven't played it yet.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onRemove(card)
                            confirmRemove = null
                        }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun SignIn(onSignIn: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your cards live in your Google Drive", style = MaterialTheme.typography.titleLarge)
        Text(
            "Sign in to make new cards and see the ones you have. Files go to a \"Tap and Play\" folder; " +
                "the app can only see the files it made itself (or the web card manager made).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSignIn) { Text("Sign in with Google") }
    }
}

@Composable
private fun NewCardForm(
    progress: Progress?,
    onCreate: (String, String, Uri, Uri?, () -> Unit) -> Unit,
    onCreateWord: (String, Map<String, CardStore.Word>, Uri?, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var wordEn by rememberSaveable { mutableStateOf("") }
    var wordHe by rememberSaveable { mutableStateOf("") }
    var wordHeAsk by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(CardStore.TYPE_AUDIO) }
    var media by rememberSaveable { mutableStateOf<Uri?>(null) }
    var image by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) media = uri }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) image = uri }
    val busy = progress != null
    val mediaName = remember(media) { media?.let { displayName(context, it) } }
    val imageName = remember(image) { image?.let { displayName(context, it) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New card", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Baby Shark") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.selectableGroup()) {
                listOf(
                    CardStore.TYPE_AUDIO to "Song",
                    CardStore.TYPE_VIDEO to "Video",
                    CardStore.TYPE_WORD to "Word card",
                ).forEach { (value, label) ->
                    Row(
                        Modifier
                            .selectable(
                                selected = type == value,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = {
                                    if (type != value) media = null
                                    type = value
                                },
                            )
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == value, onClick = null, enabled = !busy)
                        Text(label, Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (type == CardStore.TYPE_WORD) {
                Text(
                    "Tapped on its own, a word card shows its picture and says the words. It is also what the games use. " +
                        "The phone speaks the text, so there is nothing to record.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = wordEn,
                    onValueChange = { wordEn = it.take(60) },
                    label = { Text("English word") },
                    placeholder = { Text("e.g. dog") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = wordHe,
                    onValueChange = { wordHe = it.take(60) },
                    label = { Text("Hebrew word") },
                    placeholder = { Text("למשל כלב") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = wordHeAsk,
                    onValueChange = { wordHeAsk = it.take(60) },
                    label = { Text("Hebrew word inside a question (optional)") },
                    placeholder = { Text("למשל הכלב") },
                    supportingText = { Text("For \"איפה הכלב?\". Empty: the Hebrew word is used as is.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                FilePick(
                    label = if (type == CardStore.TYPE_VIDEO) "Video file" else "Song (audio file)",
                    file = mediaName,
                    enabled = !busy,
                    onPick = { pickMedia.launch(if (type == CardStore.TYPE_VIDEO) "video/*" else "audio/*") },
                    onClear = null,
                )
            }
            FilePick(
                label = "Picture (optional, shown on the player and printed on the card)",
                file = imageName,
                enabled = !busy,
                onPick = { pickImage.launch("image/*") },
                onClear = { image = null },
            )
            if (progress != null) {
                Text(progress.text, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                val isWord = type == CardStore.TYPE_WORD
                Button(
                    onClick = {
                        val reset = {
                            name = ""
                            media = null
                            image = null
                            wordEn = ""
                            wordHe = ""
                            wordHeAsk = ""
                            type = CardStore.TYPE_AUDIO
                        }
                        if (isWord) {
                            onCreateWord(name.trim(), wordsFromForm(wordEn, wordHe, wordHeAsk), image, reset)
                        } else {
                            onCreate(name.trim(), type, media ?: return@Button, image, reset)
                        }
                    },
                    enabled = name.isNotBlank() && if (isWord) wordEn.isNotBlank() || wordHe.isNotBlank() else media != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create card") }
            }
        }
    }
}

/** The words typed in the word card form, only for the languages that were filled in. */
internal fun wordsFromForm(en: String, he: String, heAsk: String): Map<String, CardStore.Word> = buildMap {
    if (en.isNotBlank()) put(CardStore.LANG_EN, CardStore.Word(en.trim()))
    if (he.isNotBlank()) put(CardStore.LANG_HE, CardStore.Word(he.trim(), heAsk.trim().ifEmpty { null }))
}

/** "Find the card": a game card over some word cards. Lay out those cards for her, then tap the game card. */
@Composable
private fun NewGameForm(
    cards: List<CardEntry>,
    progress: Progress?,
    onCreateGame: (String, String, Int?, List<CardEntry>, Uri?, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(CardStore.LANG_EN) }
    var rounds by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf<Uri?>(null) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) image = uri }
    val busy = progress != null
    val imageName = remember(image) { image?.let { displayName(context, it) } }
    val wordCards = cards.filter { it.type == CardStore.TYPE_WORD }
    val fitting = wordCards.filter { CardStore.fitsGame(it, lang) }
    val deck = fitting.filter { it.contentUrl in ticked }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New game", style = MaterialTheme.typography.titleMedium)
            Text(
                "Find the card: the phone asks \"Where is the dog?\" and she taps the dog card. Lay out the cards of " +
                    "the game in front of her (2 to 4 is plenty), tick the same ones here, and tap the game card to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Animals (English)") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.selectableGroup()) {
                listOf(
                    CardStore.LANG_EN to "English: asks and says the words in English",
                    CardStore.LANG_HE to "Hebrew: asks and says the words in Hebrew",
                    CardStore.LANG_HE_TO_EN to "Hebrew to English: asks in Hebrew, then says the English word",
                ).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = lang == value,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { lang = value },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = lang == value, onClick = null, enabled = !busy)
                        Text(label, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                    }
                }
            }
            OutlinedTextField(
                value = rounds,
                onValueChange = { rounds = it.filter(Char::isDigit).take(2) },
                label = { Text("Rounds (optional)") },
                placeholder = { Text("5") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Cards in the game", style = MaterialTheme.typography.labelLarge)
            if (fitting.isEmpty()) {
                Text(
                    if (wordCards.isEmpty()) {
                        "No word cards yet. Make some above (choose \"Word card\")."
                    } else {
                        "None of your word cards has the words this language needs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            fitting.forEach { card ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = card.contentUrl in ticked,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onClick = {
                            ticked = if (card.contentUrl in ticked) ticked - card.contentUrl else ticked + card.contentUrl
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = card.contentUrl in ticked, onCheckedChange = null, enabled = !busy)
                    Text(card.name, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilePick(
                label = "Picture (optional, printed on the game card)",
                file = imageName,
                enabled = !busy,
                onPick = { pickImage.launch("image/*") },
                onClear = { image = null },
            )
            if (progress != null) {
                Text(progress.text, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = {
                        onCreateGame(name.trim(), lang, rounds.toIntOrNull()?.takeIf { it >= 1 }, deck, image) {
                            name = ""
                            rounds = ""
                            image = null
                            ticked = emptySet()
                        }
                    },
                    enabled = name.isNotBlank() && deck.size >= 2,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (deck.size < 2) "Create game card (tick at least 2 cards)" else "Create game card") }
            }
        }
    }
}

@Composable
private fun FilePick(label: String, file: String?, enabled: Boolean, onPick: () -> Unit, onClear: (() -> Unit)?) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPick, enabled = enabled) { Text(if (file == null) "Choose…" else "Change") }
            Text(
                file ?: "None chosen",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (file != null && onClear != null) TextButton(onClick = onClear, enabled = enabled) { Text("Clear") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardRow(
    card: CardEntry,
    playerOnline: Boolean,
    canWriteStickers: Boolean,
    onPlay: () -> Unit,
    onWriteSticker: () -> Unit,
    onCopyLink: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(card.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(typeLabel(card.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow {
                TextButton(onClick = onPlay, enabled = playerOnline) { Text("Play on player") }
                if (canWriteStickers) TextButton(onClick = onWriteSticker) { Text("Write to sticker") }
                TextButton(onClick = onCopyLink) { Text("Copy link") }
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
