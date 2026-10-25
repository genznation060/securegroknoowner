package com.securebrowse.app.browser

import android.app.Activity
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.UrlPolicy

class SecureWebChromeClient(
    private val activity: Activity,
    private val policy: () -> BrowserPolicy,
    private val onProgress: (Int) -> Unit,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        return true
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        return show(url, message, result)
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        return show(url, message, result)
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true

    private fun show(url: String?, message: String?, result: JsResult): Boolean {
        val decision = UrlPolicy.evaluate(url, policy(), UrlPolicy.Purpose.MAIN_FRAME)
        if (!decision.allowed || activity.isFinishing) {
            result.cancel()
            return true
        }
        var completed = false
        fun finish(confirm: Boolean) {
            if (completed) return
            completed = true
            if (confirm) result.confirm() else result.cancel()
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(decision.host ?: "This page")
            .setMessage(message?.take(500).orEmpty())
            .setOnCancelListener { finish(false) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish(false) }
            .setPositiveButton(android.R.string.ok) { _, _ -> finish(true) }
            .show()
        return true
    }
}
