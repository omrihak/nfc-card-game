package com.omrihak.nfccards.remote

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.omrihak.nfccards.kiosk.Kiosk
import com.omrihak.nfccards.kiosk.SelfUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote control for the parent's companion app, over the home Wi-Fi: the HTTP server plus an
 * mDNS (NSD) announcement, so the companion finds the player without typing an IP address.
 * Without Wi-Fi nothing can reach it, and the toy works exactly as before.
 * Only paired companions are answered (see [Pairing]).
 *
 * [onAdminCard] gets [Kiosk.SETTINGS_CARD] or [Kiosk.RELEASE_CARD] when the parent asks for them
 * remotely; the activity handles them exactly like the cards. Main thread.
 */
class Remote(
    context: Context,
    player: RemoteApi.Player,
    pairing: Pairing,
    onAdminCard: (String) -> Unit,
    scope: CoroutineScope,
) {

    private val api = RemoteApi(player, MediaVolume(context), DeviceAdmin(context, onAdminCard)) { pairing.tokens }
    private val server = RemoteServer(PORT, scope) { request ->
        withContext(Dispatchers.Main.immediate) { api.handle(request) }
    }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        server.start()
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "Tap and Play (${Build.MODEL})"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Announced as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Announcement failed: $error")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        } catch (e: Exception) {
            // The companion can still connect by IP address.
            Log.w(TAG, "Could not announce the player", e)
        }
    }

    fun stop() {
        server.stop()
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: Exception) {
                Log.d(TAG, "Unregister: ${e.message}")
            }
        }
        registration = null
    }

    private class MediaVolume(context: Context) : RemoteApi.Volume {
        private val audio = context.getSystemService(AudioManager::class.java)
        override val level get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        override val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        override fun set(level: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    private class DeviceAdmin(context: Context, private val onAdminCard: (String) -> Unit) : RemoteApi.Admin {
        private val app = context.applicationContext
        private val dpm = app.getSystemService(DevicePolicyManager::class.java)

        override val version: String =
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        override val isDeviceOwner get() = dpm.isDeviceOwnerApp(app.packageName)
        override val updateState get() = SelfUpdater.state.name.lowercase()
        override val updateMessage get() = SelfUpdater.message

        override fun openSettings() = onAdminCard(Kiosk.SETTINGS_CARD)
        override fun release() = onAdminCard(Kiosk.RELEASE_CARD)

        override fun update(): Boolean {
            if (!isDeviceOwner) return false
            SelfUpdater.start(app)
            return true
        }
    }

    companion object {
        private const val TAG = "Remote"
        const val PORT = 8765
        const val SERVICE_TYPE = "_tapandplay._tcp"
    }
}
