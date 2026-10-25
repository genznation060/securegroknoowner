package com.securebrowse.app.browser

import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.UrlPolicy
import java.io.ByteArrayInputStream
import android.webkit.WebResourceResponse

object ResourceGuard {
    fun intercept(url: String?, isMainFrame: Boolean, policy: BrowserPolicy): WebResourceResponse? {
        val purpose = if (isMainFrame) UrlPolicy.Purpose.MAIN_FRAME else UrlPolicy.Purpose.SUBRESOURCE
        val decision = UrlPolicy.evaluate(url, policy, purpose)
        if (decision.allowed) return null
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
