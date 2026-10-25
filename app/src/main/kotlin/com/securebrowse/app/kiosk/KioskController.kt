package com.securebrowse.app.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.KioskSnapshot
import com.securebrowse.policy.LockPresentation
import com.securebrowse.policy.ManagementPosture
import com.securebrowse.policy.SessionRules

data class ShieldResult(val applied: Boolean, val message: String)

object KioskController {
    fun adminComponent(context: Context): ComponentName {
        return ComponentName(context, SecureBrowseDeviceAdminReceiver::class.java)
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun snapshot(context: Context): KioskSnapshot {
        val owner = isDeviceOwner(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val permitted = owner && dpm?.isLockTaskPermitted(context.packageName) == true
        return KioskSnapshot(
            posture = if (owner) ManagementPosture.DEVICE_OWNER else ManagementPosture.UNMANAGED,
            lockTaskPermitted = permitted,
            lockTaskMode = presentation(context),
        )
    }

    fun presentation(context: Context): LockPresentation {
        val am = context.getSystemService(ActivityManager::class.java) ?: return LockPresentation.NOT_LOCKED
        return when (am.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> LockPresentation.DEDICATED_KIOSK
            ActivityManager.LOCK_TASK_MODE_PINNED -> LockPresentation.SCREEN_PINNING
            else -> LockPresentation.NOT_LOCKED
        }
    }

    fun applyPolicies(context: Context, policy: BrowserPolicy): String {
        val handle = ownerHandle(context)
            ?: return "SecureBrowse is not the device owner. These controls are unavailable in normal browser mode."
        return try {
            applyBaseRestrictions(context, handle, policy)
            handle.dpm.setLockTaskPackages(handle.admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                handle.dpm.setLockTaskFeatures(handle.admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            "Device-owner policies applied. Only SecureBrowse may enter lock task."
        } catch (ex: SecurityException) {
            "Device policy was rejected: ${ex.javaClass.simpleName}."
        }
    }

    /**
     * Replaces the lock-task allowlist for a focus session and, when asked,
     * disables the status-bar shade. Home and recents stay off. This does not
     * make the app device owner.
     */
    fun applySessionShield(
        context: Context,
        policy: BrowserPolicy,
        packages: List<String>,
        blockStatusBar: Boolean,
        blockNotifications: Boolean,
    ): ShieldResult {
        val handle = ownerHandle(context)
            ?: return ShieldResult(
                applied = false,
                message = "App allowlisting and the status-bar ban need device owner. A normal install cannot stop other apps or the notification shade.",
            )
        if (packages.isEmpty() || context.packageName !in packages) {
            return ShieldResult(
                applied = false,
                message = "Refusing to apply a lock-task list that does not include SecureBrowse.",
            )
        }
        return try {
            applyBaseRestrictions(context, handle, policy)
            handle.dpm.setLockTaskPackages(handle.admin, packages.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = SessionRules.featureFlags(blockStatusBar, blockNotifications)
                handle.dpm.setLockTaskFeatures(handle.admin, flags)
                handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
            }
            handle.dpm.setStatusBarDisabled(handle.admin, blockStatusBar)
            grantNotificationPost(handle, context)
            val shade = if (blockStatusBar) "Status-bar pull-down is disabled." else "Status-bar pull-down stays available."
            val alerts = if (blockNotifications) "Notification shade content is off." else "Notifications are allowed in lock task."
            ShieldResult(applied = true, message = "Session shield applied. $shade $alerts")
        } catch (ex: SecurityException) {
            ShieldResult(applied = false, message = "Device policy was rejected: ${ex.javaClass.simpleName}.")
        }
    }

    fun releaseSessionShield(context: Context, policy: BrowserPolicy): String {
        val handle = ownerHandle(context) ?: return "This app is not the device owner."
        return try {
            handle.dpm.setStatusBarDisabled(handle.admin, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
            }
            applyPolicies(context, policy)
        } catch (ex: SecurityException) {
            "Device policy was rejected: ${ex.javaClass.simpleName}."
        }
    }

    fun clearDeviceOwnerForAdbTest(context: Context): String {
        if (!isDeviceOwner(context)) return "This app is not the device owner."
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return "Device policy service is unavailable."
        return try {
            dpm.setStatusBarDisabled(adminComponent(context), false)
            dpm.clearDeviceOwnerApp(context.packageName)
            "Device-owner role removed. This succeeds only for test devices provisioned with adb."
        } catch (ex: SecurityException) {
            "The role was not removed. Provisioned devices usually need a factory reset."
        }
    }

    private class OwnerHandle(val dpm: DevicePolicyManager, val admin: ComponentName)

    private fun ownerHandle(context: Context): OwnerHandle? {
        if (!isDeviceOwner(context)) return null
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return null
        return OwnerHandle(dpm, adminComponent(context))
    }

    private fun applyBaseRestrictions(context: Context, handle: OwnerHandle, policy: BrowserPolicy) {
        handle.dpm.setUninstallBlocked(handle.admin, context.packageName, true)
        baseRestrictions().forEach { handle.dpm.addUserRestriction(handle.admin, it) }
        if (policy.strictKioskRestrictions) {
            handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            handle.dpm.setKeyguardDisabled(handle.admin, true)
        } else {
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            handle.dpm.setKeyguardDisabled(handle.admin, false)
        }
    }

    private fun grantNotificationPost(handle: OwnerHandle, context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            handle.dpm.setPermissionGrantState(
                handle.admin,
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (_: SecurityException) {
            // The countdown still runs. The status icon may be suppressed.
        }
    }

    private fun baseRestrictions(): List<String> {
        val values = mutableListOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values += UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        } else {
            @Suppress("DEPRECATION")
            values += UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        }
        return values
    }
}
