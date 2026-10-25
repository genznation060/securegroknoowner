package com.securebrowse.app.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SessionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SessionRuntime.tick(context)
    }
}
