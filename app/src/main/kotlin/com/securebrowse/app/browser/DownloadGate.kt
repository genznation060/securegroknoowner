package com.securebrowse.app.browser

import android.app.Activity
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowse.app.R
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.BlockMessages
import com.securebrowse.policy.UrlPolicy
import java.util.Locale

object DownloadGate {
    fun offer(
        activity: Activity,
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
        policy: BrowserPolicy,
        onBlocked: (UrlPolicy.Decision) -> Unit,
    ) {
        val decision = UrlPolicy.evaluateDownload(url, policy)
        if (!decision.allowed || decision.openExternally) {
            onBlocked(decision)
            return
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
            onBlocked(decision.copy(allowed = false, reason = UrlPolicy.BlockReason.UNSUPPORTED_SCHEME))
            return
        }
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(BlockMessages.title(decision))
            .setMessage(activity.getString(R.string.download_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                enqueue(activity, uri, contentDisposition, mimeType)
            }
            .show()
    }

    private fun enqueue(activity: Activity, uri: Uri, contentDisposition: String?, mimeType: String?) {
        val manager = activity.getSystemService(DownloadManager::class.java) ?: return
        val name = safeName(contentDisposition, uri)
        val request = DownloadManager.Request(uri)
            .setMimeType(mimeType)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        try {
            manager.enqueue(request)
            Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.blocked_title, Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeName(contentDisposition: String?, uri: Uri): String {
        val header = contentDisposition?.let { HEADER.find(it)?.groupValues?.getOrNull(1) }
        val fallback = uri.lastPathSegment?.substringBefore('?')
        val raw = (header ?: fallback).orEmpty()
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').take(80)
        return cleaned.ifEmpty { "download.bin" }.lowercase(Locale.US)
    }

    private val HEADER = Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", RegexOption.IGNORE_CASE)
}
