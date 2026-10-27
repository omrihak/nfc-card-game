package com.omrihak.nfccards.kiosk

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.omrihak.nfccards.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the player in place from the latest GitHub release, when the parent asks from the companion
 * app: the player phone sits in an enclosure, and in kiosk mode nobody can open a browser on it.
 * Only as device owner, where Android installs our own package without asking.
 *
 * Process-wide, so an update in progress survives the activity being recreated. Installing kills the
 * process; [UpdateReceiver] starts the new version (it is the home screen in kiosk mode anyway).
 */
object SelfUpdater {

    const val LATEST_APK_URL = "https://github.com/omrihak/nfc-card-game/releases/latest/download/tap-and-play.apk"
    private const val TAG = "SelfUpdater"
    internal const val ACTION_INSTALL_RESULT = "com.omrihak.nfccards.INSTALL_RESULT"

    enum class State { IDLE, DOWNLOADING, INSTALLING, FAILED }

    @Volatile var state = State.IDLE
        private set

    /** Why the last update failed, or that there was nothing newer. */
    @Volatile var message: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Any thread. Does nothing while an update is already running. */
    @Synchronized
    fun start(context: Context) {
        if (state == State.DOWNLOADING || state == State.INSTALLING) return
        state = State.DOWNLOADING
        message = null
        val app = context.applicationContext
        scope.launch {
            val apk = File(app.cacheDir, "update.apk")
            try {
                download(apk)
                if (!isNewer(app, apk)) {
                    Log.i(TAG, "Latest release is not newer than this version")
                    finish(State.IDLE, "Already the latest version")
                    return@launch
                }
                state = State.INSTALLING
                install(app, apk)
                Log.i(TAG, "Update handed to the package installer")
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                finish(State.FAILED, e.message ?: e.toString())
            } finally {
                apk.delete() // the install session has its own copy
            }
        }
    }

    private fun finish(newState: State, why: String?) {
        message = why
        state = newState
    }

    private fun download(dest: File) {
        val tmp = File(dest.path + ".part")
        val connection = URL(LATEST_APK_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true // GitHub redirects to its download host (https to https)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Download failed: HTTP $code")
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(dest)) throw IOException("Could not save the update")
        Log.i(TAG, "Downloaded update (${dest.length()} bytes)")
    }

    /** Also rejects a file that isn't our app at all. */
    private fun isNewer(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0) ?: throw IOException("The download is not an app")
        if (archive.packageName != context.packageName) throw IOException("The download is a different app")
        val installed = pm.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("tap-and-play.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                // Mutable: the installer fills in the result extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            // Normally the process is gone before this arrives.
            PackageInstaller.STATUS_SUCCESS -> finish(State.IDLE, null)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen as device owner; let the parent confirm on the player rather than hang.
                Log.w(TAG, "Installer asks for confirmation")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show the install confirmation", e)
                }
                finish(State.IDLE, "Confirm the update on the player phone")
            }
            else -> {
                Log.e(TAG, "Install failed: $status $detail")
                finish(State.FAILED, "Install failed: ${detail ?: "status $status"}")
            }
        }
    }
}

/** The install result, and starting the app again once it has been replaced by an update. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SelfUpdater.ACTION_INSTALL_RESULT -> SelfUpdater.onInstallResult(context, intent)
            // Only in kiosk mode: there the app is all there is, and a device owner may start an activity
            // from the background. (Not after a developer's `adb install`, for example.)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) {
                    try {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        Log.w("SelfUpdater", "Could not restart after the update", e)
                    }
                }
        }
    }
}
