package com.securebrowse.app.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableApp(val packageName: String, val label: String)

object InstalledApps {
    fun launchable(context: Context): List<LaunchableApp> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        return found.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val label = info.loadLabel(context.packageManager)?.toString()?.trim().orEmpty().ifEmpty { packageName }
            LaunchableApp(packageName, label)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}
