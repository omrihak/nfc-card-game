package com.omrihak.nfccards.kiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.omrihak.nfccards.remote.Pairing

/*
 * QR-code provisioning (see README, "Kiosk mode"): on a freshly reset phone, the setup wizard downloads
 * this app, and since Android 10 asks it two questions before making it device owner. Both answers are
 * fixed, so these activities show nothing and finish at once. Android only starts them with the
 * BIND_DEVICE_ADMIN permission (see the manifest), so other apps can't.
 */

/** "Which kind of provisioning?" Always a fully managed device (device owner), never a work profile. */
@SuppressLint("InlinedApi") // Only started on Android 10+, where these exist.
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            RESULT_OK,
            Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE),
        )
        finish()
    }
}

/**
 * "Set your policies now." The app is device owner by this point. Making it the home screen here means
 * that whatever comes after the setup wizard is this app, which then locks itself in ([Kiosk.enter]).
 * The companion's pairing token is stored here too, before the app first opens, so the companion finds
 * the player already paired.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pairing(this).onProvisioned(intent)
        try {
            Kiosk.setAsHome(this)
        } catch (e: SecurityException) {
            Log.w("Kiosk", "Not device owner after provisioning?", e)
        }
        setResult(RESULT_OK)
        finish()
    }
}
