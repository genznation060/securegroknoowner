package com.securebrowse.app.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.app.session.SessionRuntime
import com.securebrowse.app.ui.MainActivity

/**
 * Device-owner apps may start an activity from boot. Unmanaged installs do
 * not get that exemption, and this receiver does not try to take it.
 * An in-progress focus session is re-applied or cleared before the activity opens.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val graph = SecureBrowseApp.graph(context)
        SessionRuntime.reconcileAfterBoot(context)
        val live = SessionRuntime.active(context) != null
        val auto = graph.currentPolicy().kioskAutoStart && KioskController.isDeviceOwner(context)
        if (!live && !auto) return
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launch)
        } catch (_: Exception) {
            // Background activity starts are blocked unless this app is device owner.
        }
    }
}
