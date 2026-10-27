package com.omrihak.nfccards.companion.player

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.omrihak.nfccards.companion.cards.Card
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * This phone's secret for the player's remote control. The player learns it once, from a QR code
 * shown on this phone (or a sticker), and from then on only answers requests carrying it.
 */
object Pairing {

    /** 32 random bytes as 43 URL-safe characters (the player wants at least 32). */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun uri(token: String) = "tapandplay://pair?token=$token"

    /** The QR code for [text]; dark modules are `true`. */
    fun qr(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
}

/** Stickers for the player that aren't songs: pairing and the admin cards (same URIs as the player's Kiosk). */
object AdminStickers {
    const val SETTINGS_URI = "tapandplay://settings"
    const val RELEASE_URI = "tapandplay://release"

    val settings get() = sticker("Settings admin card", SETTINGS_URI)
    val release get() = sticker("Release admin card", RELEASE_URI)
    fun pairing(token: String) = sticker("Pairing card", Pairing.uri(token))

    /** A [Card] only so [com.omrihak.nfccards.companion.cards.StickerWriter] can write it; type "admin". */
    private fun sticker(name: String, uri: String) =
        Card(JSONObject().put("name", name).put("type", TYPE).put("contentUrl", uri))

    const val TYPE = "admin"
}
