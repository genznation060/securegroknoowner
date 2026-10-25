package com.securebrowse.app.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import com.securebrowse.app.SecureBrowseApp

/**
 * Android 12+ provisioning asks the device-owner app which mode to enter.
 * SecureBrowse only supports a fully managed dedicated device. It never
 * requests device-owner rights by itself.
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }
}

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (KioskController.isDeviceOwner(this)) {
            KioskController.applyPolicies(this, SecureBrowseApp.graph(this).currentPolicy())
        }
        setResult(RESULT_OK)
        finish()
    }
}
