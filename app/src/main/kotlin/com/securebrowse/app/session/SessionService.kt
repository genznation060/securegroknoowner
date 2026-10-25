package com.securebrowse.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.securebrowse.app.R
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.policy.SessionRules

/**
 * Keeps the countdown process alive while another allowed app is in front.
 * The alarm receiver is what still fires if this service is killed.
 */
class SessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onTick() {
        val keep = try {
            SessionRuntime.tick(this)
        } catch (_: Exception) {
            false
        }
        if (!keep) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startInForeground()
        handler.postDelayed(tick, 1_000)
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Focus session", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager?.createNotificationChannel(channel)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val session = SecureBrowseApp.graph(this).sessions.current()
        val text = if (session == null) {
            "Focus session"
        } else {
            val left = SessionRules.formatRemaining(SessionRules.remainingMillis(session, System.currentTimeMillis()))
            getString(R.string.session_banner, left)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_session)
            .setContentTitle(getString(R.string.session_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "focus-session"
        private const val NOTIFICATION_ID = 41
    }
}
