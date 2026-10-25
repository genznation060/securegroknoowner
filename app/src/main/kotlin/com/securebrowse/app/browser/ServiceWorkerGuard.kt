package com.securebrowse.app.browser

import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.securebrowse.policy.BrowserPolicy

object ServiceWorkerGuard {
    fun install(policy: () -> BrowserPolicy) {
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return ResourceGuard.intercept(request.url?.toString(), isMainFrame = false, policy())
                }
            })
        } catch (_: Throwable) {
            // A device without a WebView provider cannot install the guard.
            // Main-frame checks still run when a WebView exists.
        }
    }
}
