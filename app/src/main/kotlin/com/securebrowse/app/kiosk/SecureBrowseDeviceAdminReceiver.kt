package com.securebrowse.app.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SecureBrowseDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device-admin-enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device-admin-disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "lock-task-entering")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "lock-task-exiting")
    }

    private companion object {
        const val TAG = "SecureBrowse"
    }
}
