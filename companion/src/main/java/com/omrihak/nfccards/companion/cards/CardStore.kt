package com.omrihak.nfccards.companion.cards

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** An entry of `Tap and Play/cards.json`. [raw] keeps any fields this app doesn't know, so it can't drop them. */
data class Card(val raw: JSONObject) {
    val name: String get() = raw.optString("name")
    /** "audio" | "video" | "word" (words the phone speaks, no file) | "game" (a deck of word cards) */
    val type: String get() = raw.optString("type", "audio")
    /** For word cards: the languages it has words in ("en", "he"). Empty for other cards. */
    val langs: List<String> get() = raw.optJSONArray("langs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    /** The content.json share link: what goes on the sticker, and what the player plays. */
    val contentUrl: String get() = raw.optString("contentUrl")
    val folderId: String? get() = raw.optString("folderId").ifEmpty { null }
}

/**
 * The cards in the parent's Drive, in exactly the layout the web manager (`manager/app.js`) uses,
 * so both see and edit the same list:
 *
 *   Tap and Play/
 *     cards.json            index of every card
 *     <card name>/
 *       <song or video>     public
 *       <picture>           public, optional
 *       content.json        public; its share link is what goes on the NFC sticker
 */
class CardStore(private val drive: Drive) {

    private var root: DriveFile? = null

    private suspend fun root(): DriveFile = root ?: drive.findOrCreateFolder(ROOT_FOLDER).also { root = it }

    suspend fun cards(): List<Card> = readIndex().second

    /**
     * Creates the card's folder, uploads [media] (+ optional [image]) and content.json, makes them
     * public and adds the card to the index. [onProgress] gets a step description and an overall fraction.
     */
    suspend fun create(
        name: String,
        type: String,
        media: Upload,
        image: Upload?,
        onProgress: (String, Float) -> Unit,
    ): Card = make(name, type, media, image, JSONObject(), JSONObject(), onProgress)

    /** A word card: no file, just the words the phone speaks (keyed by language), and an optional picture. */
    suspend fun createWord(
        name: String,
        words: Map<String, Word>,
        image: Upload?,
        onProgress: (String, Float) -> Unit,
    ): Card = make(
        name, TYPE_WORD, null, image, JSONObject().put("words", wordsJson(words)),
        JSONObject().put("langs", JSONArray(words.keys.toList())), onProgress,
    )

    /** A "Find the card" game over the given word cards. [rounds] null leaves the player's default. */
    suspend fun createGame(
        name: String,
        lang: String,
        rounds: Int?,
        deck: List<Card>,
        image: Upload?,
        onProgress: (String, Float) -> Unit,
    ): Card = make(
        name, TYPE_GAME, null, image, gameFields(lang, rounds, deck.map { it.contentUrl }),
        JSONObject().put("lang", lang), onProgress,
    )

    private suspend fun make(
        name: String,
        type: String,
        media: Upload?,
        image: Upload?,
        contentFields: JSONObject,
        indexFields: JSONObject,
        onProgress: (String, Float) -> Unit,
    ): Card {
        onProgress("Creating folder…", 0f)
        val folder = drive.createFolder(name, root().id)

        // Progress is weighted by size; the small steps after the uploads are not counted.
        val total = ((media?.source?.size ?: 0) + (image?.source?.size ?: 0)).coerceAtLeast(1).toFloat()
        var done = 0L
        suspend fun uploadOne(upload: Upload, label: String): DriveFile {
            val file = drive.upload(upload.source, upload.fileName, folder.id) { fraction ->
                onProgress("Uploading $label…", (done + fraction * upload.source.size) / total)
            }
            done += upload.source.size
            drive.makePublic(file.id)
            return file
        }

        val mediaFile = media?.let { uploadOne(it, if (type == TYPE_VIDEO) "video" else "song") }
        val imageFile = image?.let { uploadOne(it, "picture") }

        onProgress("Writing content.json…", 1f)
        val createdAt = Instant.now().toString()
        val content = contentJson(
            type, mediaFile?.let { Drive.shareLink(it.id) }, imageFile?.let { Drive.shareLink(it.id) }, name, createdAt, contentFields,
        )
        val contentBytes = content.toByteArray()
        val contentFile = drive.upload(
            UploadSource(contentBytes.size.toLong(), "application/json") { contentBytes.inputStream() },
            "content.json",
            folder.id,
        )
        drive.makePublic(contentFile.id)

        val entry = JSONObject()
            .put("name", name)
            .put("type", type)
            .put("contentUrl", Drive.shareLink(contentFile.id))
            .put("folderId", folder.id)
            .put("createdAt", createdAt)
        indexFields.keys().forEach { entry.put(it, indexFields.get(it)) }
        val card = Card(entry)
        onProgress("Adding to the card list…", 1f)
        val (index, cards) = readIndex()
        writeIndex(index, cards + card)
        return card
    }

    /** Moves the card's folder to the Drive trash (restorable for 30 days) and takes it off the list. */
    suspend fun remove(card: Card) {
        card.folderId?.let { drive.trash(it) }
        val (index, cards) = readIndex()
        writeIndex(index, cards.filter { it.contentUrl != card.contentUrl })
    }

    private suspend fun readIndex(): Pair<DriveFile?, List<Card>> {
        val file = drive.find(INDEX_NAME, parent = root().id) ?: return null to emptyList()
        return file to parseIndex(drive.readText(file.id))
    }

    private suspend fun writeIndex(file: DriveFile?, cards: List<Card>) {
        val text = indexJson(cards)
        if (file != null) {
            drive.overwriteText(file.id, text)
        } else {
            val bytes = text.toByteArray()
            drive.upload(UploadSource(bytes.size.toLong(), "application/json") { bytes.inputStream() }, INDEX_NAME, root().id)
        }
    }

    /** A file to upload, with the name it gets in Drive. */
    class Upload(val fileName: String, val source: UploadSource)

    /** How a word card says a word: [text] on its own, [ask] inside a question (null: same as [text]). */
    data class Word(val text: String, val ask: String? = null)

    companion object {
        const val ROOT_FOLDER = "Tap and Play"
        const val INDEX_NAME = "cards.json"
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"
        const val TYPE_WORD = "word"
        const val TYPE_GAME = "game"

        /** Game languages the form offers: what the game speaks, and the words each card needs for it. */
        const val LANG_EN = "en"
        const val LANG_HE = "he"
        const val LANG_HE_TO_EN = "he>en"

        /** The languages every card of a game's deck must have words in. */
        fun languagesNeeded(gameLang: String): List<String> = gameLang.split('>').map { it.trim() }.distinct()

        /** Word cards that can be in a game of [gameLang]. */
        fun fitsGame(card: Card, gameLang: String): Boolean =
            card.type == TYPE_WORD && languagesNeeded(gameLang).all { it in card.langs }

        /** An unreadable index counts as empty, like in the web manager. */
        fun parseIndex(text: String): List<Card> {
            val array = try {
                JSONObject(text).optJSONArray("cards")
            } catch (e: Exception) {
                null
            } ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::Card) }
        }

        fun indexJson(cards: List<Card>): String =
            JSONObject().put("cards", JSONArray(cards.map { it.raw })).toString(2)

        /**
         * The player's format (see README "Making a card" and "Games"); `name` and `createdAt` are ignored by it.
         * [media] is null for word and game cards. [extra] holds the type's own fields (`words`, or the game settings).
         */
        fun contentJson(
            type: String,
            media: String?,
            image: String?,
            name: String,
            createdAt: String,
            extra: JSONObject = JSONObject(),
        ): String {
            val json = JSONObject().put("type", type)
            if (media != null) json.put("media", media)
            if (image != null) json.put("image", image)
            extra.keys().forEach { json.put(it, extra.get(it)) }
            return json.put("name", name).put("createdAt", createdAt).toString(2)
        }

        /** `{ "en": {"text": "dog"}, "he": {"text": "כלב", "ask": "הכלב"} }`; blank words and asks are left out. */
        fun wordsJson(words: Map<String, Word>): JSONObject {
            val json = JSONObject()
            for ((lang, word) in words) {
                val text = word.text.trim()
                if (text.isEmpty()) continue
                val entry = JSONObject().put("text", text)
                word.ask?.trim()?.takeIf { it.isNotEmpty() }?.let { entry.put("ask", it) }
                json.put(lang, entry)
            }
            return json
        }

        /** The settings of a "Find the card" game card. */
        fun gameFields(lang: String, rounds: Int?, deckLinks: List<String>): JSONObject {
            val json = JSONObject().put("game", "find-card").put("lang", lang)
            if (rounds != null) json.put("rounds", rounds)
            return json.put("deck", JSONArray(deckLinks))
        }
    }
}
