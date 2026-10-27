package com.omrihak.nfccards.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.omrihak.nfccards.MainActivity

/** Exists so the app can be made device owner (see README, "Kiosk mode"). */
class AdminReceiver : DeviceAdminReceiver() {
    /** QR-code provisioning finished: open the app, which locks itself in right away. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i("Kiosk", "Provisioned as device owner")
        Kiosk.setAsHome(context)
        // Device owners may start activities from the background.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Keeps the toddler in the app. Only does anything when the app is device owner:
 * then it is the permanent home screen, runs in lock task mode (no home, recents, notifications
 * or power menu), and the lock screen is disabled. Without device owner, the app still works
 * as a normal launcher (see README).
 *
 * The parent gets out with an admin card (a tag with [SETTINGS_CARD] or [RELEASE_CARD]).
 */
class Kiosk(private val activity: Activity) {

    private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(activity, AdminReceiver::class.java)

    private val isDeviceOwner get() = dpm.isDeviceOwnerApp(activity.packageName)

    /** Called on every resume, so the kiosk comes back after the parent is done in Settings. */
    fun enter() {
        if (!isDeviceOwner) return
        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        if (Build.VERSION.SDK_INT >= 28) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        setAsHome(activity)
        // For QR scanning, without a permission dialog.
        dpm.setPermissionGrantState(
            admin,
            activity.packageName,
            Manifest.permission.CAMERA,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
        )
        dpm.setKeyguardDisabled(admin, true)
        dpm.setStatusBarDisabled(admin, true)
        dpm.setGlobalSetting(
            admin,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS).toString(),
        )
        if (!inLockTask()) activity.startLockTask()
    }

    /** Handles admin cards. Returns false for any other card. */
    fun onAdminCard(uri: String): Boolean {
        when (uri.trim().lowercase()) {
            SETTINGS_CARD -> {
                Log.i(TAG, "Settings card: leaving kiosk until the app is opened again")
                leave()
            }
            RELEASE_CARD -> {
                Log.i(TAG, "Release card: giving up device owner")
                leave()
                if (isDeviceOwner) {
                    dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
                    dpm.setKeyguardDisabled(admin, false)
                    @Suppress("DEPRECATION")
                    dpm.clearDeviceOwnerApp(activity.packageName)
                }
            }
            else -> return false
        }
        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun leave() {
        if (isDeviceOwner) dpm.setStatusBarDisabled(admin, false)
        // Also ends plain screen pinning when not device owner.
        if (inLockTask()) activity.stopLockTask()
    }

    private fun inLockTask() =
        activity.getSystemService(ActivityManager::class.java).lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    companion object {
        private const val TAG = "Kiosk"
        const val SETTINGS_CARD = "tapandplay://settings"
        const val RELEASE_CARD = "tapandplay://release"

        /** Makes [MainActivity] the permanent home screen. Only works for the device owner. */
        fun setAsHome(context: Context) {
            context.getSystemService(DevicePolicyManager::class.java).addPersistentPreferredActivity(
                ComponentName(context, AdminReceiver::class.java),
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
                ComponentName(context, MainActivity::class.java),
            )
        }
    }
}
