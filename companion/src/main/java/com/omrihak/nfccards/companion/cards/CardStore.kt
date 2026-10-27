package com.omrihak.nfccards.companion.cards

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** An entry of `Tap and Play/cards.json`. [raw] keeps any fields this app doesn't know, so it can't drop them. */
data class Card(val raw: JSONObject) {
    val name: String get() = raw.optString("name")
    /** "audio" | "video" */
    val type: String get() = raw.optString("type", "audio")
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
    ): Card {
        onProgress("Creating folder…", 0f)
        val folder = drive.createFolder(name, root().id)

        // Progress is weighted by size; the small steps after the uploads are not counted.
        val total = (media.source.size + (image?.source?.size ?: 0)).coerceAtLeast(1).toFloat()
        var done = 0L
        suspend fun uploadOne(upload: Upload, label: String): DriveFile {
            val file = drive.upload(upload.source, upload.fileName, folder.id) { fraction ->
                onProgress("Uploading $label…", (done + fraction * upload.source.size) / total)
            }
            done += upload.source.size
            drive.makePublic(file.id)
            return file
        }

        val mediaFile = uploadOne(media, if (type == TYPE_VIDEO) "video" else "song")
        val imageFile = image?.let { uploadOne(it, "picture") }

        onProgress("Writing content.json…", 1f)
        val createdAt = Instant.now().toString()
        val content = contentJson(type, Drive.shareLink(mediaFile.id), imageFile?.let { Drive.shareLink(it.id) }, name, createdAt)
        val contentBytes = content.toByteArray()
        val contentFile = drive.upload(
            UploadSource(contentBytes.size.toLong(), "application/json") { contentBytes.inputStream() },
            "content.json",
            folder.id,
        )
        drive.makePublic(contentFile.id)

        val card = Card(
            JSONObject()
                .put("name", name)
                .put("type", type)
                .put("contentUrl", Drive.shareLink(contentFile.id))
                .put("folderId", folder.id)
                .put("createdAt", createdAt),
        )
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

    companion object {
        const val ROOT_FOLDER = "Tap and Play"
        const val INDEX_NAME = "cards.json"
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"

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

        /** The player's format (see README "Making a card"); `name` and `createdAt` are ignored by it. */
        fun contentJson(type: String, media: String, image: String?, name: String, createdAt: String): String {
            val json = JSONObject().put("type", type).put("media", media)
            if (image != null) json.put("image", image)
            return json.put("name", name).put("createdAt", createdAt).toString(2)
        }
    }
}
