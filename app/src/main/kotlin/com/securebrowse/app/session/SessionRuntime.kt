package com.securebrowse.app.session

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.app.kiosk.KioskController
import com.securebrowse.app.ui.MainActivity
import com.securebrowse.policy.FocusSession
import com.securebrowse.policy.SessionRules

/**
 * Starts, watches, and ends a focus session. Enforcement exists only when this
 * app is device owner. The timer itself is stored so a reboot can finish it.
 */
object SessionRuntime {
    private val gate = Any()

    fun active(context: Context, nowEpochMs: Long = System.currentTimeMillis()): FocusSession? {
        val session = SecureBrowseApp.graph(context).sessions.current() ?: return null
        if (SessionRules.isExpired(session, nowEpochMs)) return null
        return session
    }

    fun start(activity: Activity): String {
        synchronized(gate) {
            val graph = SecureBrowseApp.graph(activity)
            val now = System.currentTimeMillis()
            val existing = graph.sessions.current()
            if (existing != null && !SessionRules.isExpired(existing, now)) {
                return "A focus session is already running."
            }
            if (!KioskController.isDeviceOwner(activity)) {
                return "Select apps and the status-bar ban do nothing until SecureBrowse is the device owner. The timer alone cannot block other apps."
            }
            val policy = graph.currentPolicy()
            SessionRules.minutesRejection(policy.sessionMinutes)?.let { return it }
            val session = FocusSession(
                endsAtEpochMs = now + policy.sessionMinutes * 60_000L,
                durationMinutes = policy.sessionMinutes,
                packages = policy.sessionPackages,
                blockStatusBar = policy.blockStatusBar,
                blockNotifications = policy.blockNotifications,
            )
            graph.sessions.save(session)
            val packages = SessionRules.lockTaskPackages(activity.packageName, session.packages)
            val shield = KioskController.applySessionShield(
                activity,
                policy,
                packages,
                session.blockStatusBar,
                session.blockNotifications,
            )
            if (!shield.applied) {
                graph.sessions.clear()
                KioskController.releaseSessionShield(activity, policy)
                return shield.message
            }
            scheduleAlarm(activity, session.endsAtEpochMs)
            ensureClock(activity)
            return try {
                activity.startLockTask()
                "Session started for ${policy.sessionMinutes} minutes. ${packages.size} apps allowed. ${shield.message}"
            } catch (_: IllegalArgumentException) {
                graph.sessions.clear()
                cancelAlarm(activity)
                activity.stopService(Intent(activity, SessionService::class.java))
                KioskController.releaseSessionShield(activity, policy)
                "Lock task did not start, so the session was not kept."
            }
        }
    }

    /** Re-applies an in-progress session, or ends one whose clock has run out. */
    fun maintain(activity: Activity): FocusSession? {
        val graph = SecureBrowseApp.graph(activity)
        val session = graph.sessions.current() ?: return null
        if (SessionRules.isExpired(session, System.currentTimeMillis())) {
            end(activity)
            return null
        }
        if (KioskController.isDeviceOwner(activity)) {
            val policy = graph.currentPolicy()
            val packages = SessionRules.lockTaskPackages(activity.packageName, session.packages)
            KioskController.applySessionShield(
                activity,
                policy,
                packages,
                session.blockStatusBar,
                session.blockNotifications,
            )
        }
        ensureClock(activity)
        return session
    }

    fun reconcileAfterBoot(context: Context) {
        val graph = SecureBrowseApp.graph(context)
        val session = graph.sessions.current() ?: return
        if (SessionRules.isExpired(session, System.currentTimeMillis())) {
            end(context)
            return
        }
        if (KioskController.isDeviceOwner(context)) {
            val packages = SessionRules.lockTaskPackages(context.packageName, session.packages)
            KioskController.applySessionShield(
                context,
                graph.currentPolicy(),
                packages,
                session.blockStatusBar,
                session.blockNotifications,
            )
        }
        scheduleAlarm(context, session.endsAtEpochMs)
    }

    fun tick(context: Context): Boolean {
        val session = SecureBrowseApp.graph(context).sessions.current() ?: return false
        if (!SessionRules.isExpired(session, System.currentTimeMillis())) return true
        end(context)
        return false
    }

    fun end(context: Context) {
        val bringHome = synchronized(gate) {
            val graph = SecureBrowseApp.graph(context)
            val had = graph.sessions.current() != null
            graph.sessions.clear()
            cancelAlarm(context)
            if (KioskController.isDeviceOwner(context)) {
                KioskController.releaseSessionShield(context, graph.currentPolicy())
            }
            had
        }
        context.stopService(Intent(context, SessionService::class.java))
        if (!bringHome) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SESSION_ENDED, true)
        try {
            context.startActivity(launch)
        } catch (_: Exception) {
            // The shield is already lifted if this process is device owner.
        }
    }

    fun openAllowedApp(activity: Activity, packageName: String): String? {
        val session = active(activity) ?: return "No focus session is running."
        val allowed = SessionRules.lockTaskPackages(activity.packageName, session.packages)
        if (packageName !in allowed) return "That app is not allowed in this session."
        if (packageName == activity.packageName) return null
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "That app is not installed."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent)
            null
        } catch (_: SecurityException) {
            "Android refused to open that app."
        } catch (_: Exception) {
            "Could not open that app."
        }
    }

    fun ensureClock(context: Context) {
        val session = active(context) ?: return
        scheduleAlarm(context, session.endsAtEpochMs)
        try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        } catch (_: Exception) {
            // The alarm is the backup if a foreground service is not allowed.
        }
    }

    private fun scheduleAlarm(context: Context, endsAtEpochMs: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context)
        try {
            val exactAllowed = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            if (exactAllowed) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMs, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtEpochMs, pending)
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
