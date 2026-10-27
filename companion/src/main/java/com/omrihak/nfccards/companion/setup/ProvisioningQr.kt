package com.omrihak.nfccards.companion.setup

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest
import java.util.Base64

/**
 * The QR code that turns a freshly reset phone into a player (Android "QR code provisioning"): the
 * setup wizard downloads the player APK, checks its signature, makes it device owner and joins the Wi-Fi.
 * The same JSON is made by manager/setup.js; keep the two in step.
 */
object ProvisioningQr {

    const val ADMIN_COMPONENT = "com.omrihak.nfccards/.kiosk.AdminReceiver"
    const val APK_URL = "https://github.com/omrihak/nfc-card-game/releases/latest/download/tap-and-play.apk"

    /** Checksum of the checked-in app/debug.keystore certificate (see [isDebugKey]). */
    const val DEBUG_KEY_CHECKSUM = "slEXBZ79eV16nRC5HFZqGrKSOYTx5gCkeZ8Niz8FCH8"

    private const val EXTRA = "android.app.extra.PROVISIONING_"

    enum class Security { WPA, WEP, NONE }

    data class Wifi(val ssid: String, val password: String = "", val security: Security = Security.WPA, val hidden: Boolean = false)

    /**
     * The provisioning JSON. [checksum] is that of the key the player APK is signed with ([checksumOf]).
     * [wifi] is left out when null or without a name; the phone then asks for Wi-Fi itself.
     */
    fun json(checksum: String, wifi: Wifi?): String {
        val fields = linkedMapOf<String, Any>(
            "DEVICE_ADMIN_COMPONENT_NAME" to ADMIN_COMPONENT,
            "DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" to APK_URL,
            "DEVICE_ADMIN_SIGNATURE_CHECKSUM" to checksum,
            // Keep Settings, the camera app etc.: lock task mode already keeps the child in the player,
            // and the parent needs Settings after a settings card.
            "LEAVE_ALL_SYSTEM_APPS_ENABLED" to true,
            // Old (Android 7-9) phones that aren't encrypted would otherwise encrypt first, which takes long
            // and needs a reboot in the middle. The phone holds nothing private, just cached songs. Phones
            // that are already encrypted (all Android 10+) ignore this.
            "SKIP_ENCRYPTION" to true,
        )
        if (wifi != null && wifi.ssid.isNotEmpty()) {
            fields["WIFI_SSID"] = wifi.ssid
            fields["WIFI_SECURITY_TYPE"] = wifi.security.name
            if (wifi.security != Security.NONE && wifi.password.isNotEmpty()) fields["WIFI_PASSWORD"] = wifi.password
            if (wifi.hidden) fields["WIFI_HIDDEN"] = true
        }
        return fields.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$EXTRA$key\":" + if (value is String) quote(value) else value.toString()
        }
    }

    /** SHA-256 of a signing certificate, base64url without padding: what Android compares the APK against. */
    fun checksumOf(certificate: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate))

    /** True for the checked-in debug key, whose QR only works with a player built from this repository. */
    fun isDebugKey(checksum: String) = checksum == DEBUG_KEY_CHECKSUM

    /** QR modules with a 4-module quiet zone. */
    fun matrix(text: String): BitMatrix {
        val hints = mutableMapOf<EncodeHintType, Any>(
            // Shown on a clean screen: low error correction means fewer, bigger modules, which read best.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 4,
        )
        // Plain ASCII needs no charset marker, which the oldest setup wizards might not understand.
        if (text.any { it.code > 0x7e }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
