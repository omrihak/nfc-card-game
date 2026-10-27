package com.omrihak.nfccards.companion

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.omrihak.nfccards.companion.setup.ProvisioningQr
import com.omrihak.nfccards.companion.setup.ProvisioningQr.Security
import com.omrihak.nfccards.companion.setup.ProvisioningQr.Wifi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

class ProvisioningQrTest {

    private val checksum = "slEXBZ79eV16nRC5HFZqGrKSOYTx5gCkeZ8Niz8FCH8"
    private fun extra(key: String) = "android.app.extra.PROVISIONING_$key"

    @Test fun hasAdminApkAndChecksum() {
        val json = JSONObject(ProvisioningQr.json(checksum, null))
        assertEquals("com.omrihak.nfccards/.kiosk.AdminReceiver", json.getString(extra("DEVICE_ADMIN_COMPONENT_NAME")))
        assertEquals(
            "https://github.com/omrihak/nfc-card-game/releases/latest/download/tap-and-play.apk",
            json.getString(extra("DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION")),
        )
        assertEquals(checksum, json.getString(extra("DEVICE_ADMIN_SIGNATURE_CHECKSUM")))
        assertTrue(json.getBoolean(extra("LEAVE_ALL_SYSTEM_APPS_ENABLED")))
        assertTrue(json.getBoolean(extra("SKIP_ENCRYPTION")))
        assertEquals(5, json.length())
    }

    @Test fun leavesOutWifiWithoutName() {
        val json = JSONObject(ProvisioningQr.json(checksum, Wifi("", "secret")))
        assertFalse(json.keys().asSequence().any { it.contains("WIFI") })
    }

    @Test fun addsWifi() {
        val json = JSONObject(ProvisioningQr.json(checksum, Wifi("Home", "pass word", Security.WPA)))
        assertEquals("Home", json.getString(extra("WIFI_SSID")))
        assertEquals("pass word", json.getString(extra("WIFI_PASSWORD")))
        assertEquals("WPA", json.getString(extra("WIFI_SECURITY_TYPE")))
        assertFalse(json.has(extra("WIFI_HIDDEN")))
    }

    @Test fun openWifiHasNoPassword() {
        val json = JSONObject(ProvisioningQr.json(checksum, Wifi("Cafe", "ignored", Security.NONE, hidden = true)))
        assertEquals("NONE", json.getString(extra("WIFI_SECURITY_TYPE")))
        assertFalse(json.has(extra("WIFI_PASSWORD")))
        assertTrue(json.getBoolean(extra("WIFI_HIDDEN")))
    }

    @Test fun escapesWifiNameAndPassword() {
        val ssid = "My \"Home\" \\ Wi-Fi\tשלום"
        val password = "p\"a\\s/s\n\u0001"
        val json = JSONObject(ProvisioningQr.json(checksum, Wifi(ssid, password, Security.WEP)))
        assertEquals(ssid, json.getString(extra("WIFI_SSID")))
        assertEquals(password, json.getString(extra("WIFI_PASSWORD")))
        assertEquals("WEP", json.getString(extra("WIFI_SECURITY_TYPE")))
    }

    @Test fun checksumIsBase64UrlOfCertificateSha256() {
        // The checked-in debug key; same as `keytool -exportcert ... | openssl dgst -sha256 -binary | base64url`.
        val store = KeyStore.getInstance("PKCS12").apply {
            File("../app/debug.keystore").inputStream().use { load(it, "android".toCharArray()) }
        }
        val cert = store.getCertificate("androiddebugkey") as X509Certificate
        val sum = ProvisioningQr.checksumOf(cert.encoded)
        assertEquals(ProvisioningQr.DEBUG_KEY_CHECKSUM, sum)
        assertTrue(ProvisioningQr.isDebugKey(sum))
        assertEquals(43, sum.length) // 32 bytes, no padding
        assertFalse(sum.contains('+') || sum.contains('/') || sum.contains('='))
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", ProvisioningQr.checksumOf(ByteArray(0)))
    }

    @Test fun qrDecodesToTheJson() {
        for (wifi in listOf(null, Wifi("Home", "secret"), Wifi("בית \"שלי\"", "סיסמה"))) {
            val text = ProvisioningQr.json(checksum, wifi)
            assertEquals(text, decode(text))
        }
    }

    private fun decode(text: String): String {
        val matrix = ProvisioningQr.matrix(text)
        // Quiet zone of 4 modules all round.
        for (i in 0 until 4) assertFalse(matrix[i, i])
        val scale = 4
        val w = matrix.width * scale
        val pixels = IntArray(w * w) { i ->
            if (matrix[(i % w) / scale, (i / w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels)))
        return QRCodeReader().decode(bitmap).text
    }
}
