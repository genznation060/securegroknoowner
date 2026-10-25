package com.securebrowse.app.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.HostNames
import com.securebrowse.policy.UrlPolicy

interface BrowserCallbacks {
    fun onBlocked(decision: UrlPolicy.Decision)
    fun onPage(url: String?)
    fun onProgress(value: Int)
    fun onError(message: String)
    fun onRendererGone()
}

class SecureWebViewClient(
    private val policy: () -> BrowserPolicy,
    private val callbacks: BrowserCallbacks,
) : WebViewClient() {
    private val main = Handler(Looper.getMainLooper())

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val decision = UrlPolicy.evaluate(
            raw = request.url?.toString(),
            policy = policy(),
            purpose = if (request.isForMainFrame) UrlPolicy.Purpose.MAIN_FRAME else UrlPolicy.Purpose.SUBRESOURCE,
            isRedirect = request.isRedirect,
            hasUserGesture = request.hasGesture(),
        )
        if (!decision.allowed) {
            if (request.isForMainFrame) callbacks.onBlocked(decision)
            return true
        }
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val decision = UrlPolicy.evaluate(
            raw = request.url?.toString(),
            policy = policy(),
            purpose = if (request.isForMainFrame) UrlPolicy.Purpose.MAIN_FRAME else UrlPolicy.Purpose.SUBRESOURCE,
            isRedirect = request.isRedirect,
            hasUserGesture = request.hasGesture(),
        )
        if (decision.allowed) return null
        if (request.isForMainFrame) {
            main.post { callbacks.onBlocked(decision) }
        }
        return ResourceGuard.intercept(request.url?.toString(), request.isForMainFrame, policy())
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        callbacks.onPage(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        callbacks.onPage(url)
        callbacks.onProgress(100)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        val host = runCatching { android.net.Uri.parse(error.url).host }.getOrNull()?.let(HostNames::normalize)
        callbacks.onBlocked(
            UrlPolicy.Decision(
                allowed = false,
                reason = UrlPolicy.BlockReason.SSL,
                host = host,
                purpose = UrlPolicy.Purpose.MAIN_FRAME,
                isRedirect = false,
                hasUserGesture = false,
            ),
        )
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE) return
        callbacks.onError("This page failed to load.")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone()
        return true
    }
}
