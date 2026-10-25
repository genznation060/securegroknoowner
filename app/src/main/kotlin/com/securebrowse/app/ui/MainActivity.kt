package com.securebrowse.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.securebrowse.app.R
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.app.browser.BrowserCallbacks
import com.securebrowse.app.browser.DownloadGate
import com.securebrowse.app.browser.SecureWebChromeClient
import com.securebrowse.app.browser.SecureWebViewClient
import com.securebrowse.app.browser.WebViewConfigurator
import com.securebrowse.app.databinding.ActivityMainBinding
import com.securebrowse.app.databinding.ItemAppBinding
import com.securebrowse.app.kiosk.KioskController
import com.securebrowse.app.session.SessionRuntime
import com.securebrowse.policy.BlockMessages
import com.securebrowse.policy.KioskAssessment
import com.securebrowse.policy.SessionRules
import com.securebrowse.policy.UrlPolicy
import java.net.URI

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var appliedEpoch = -1
    private var renderedApps: String? = null
    private val clock = Handler(Looper.getMainLooper())
    private val tick: Runnable = Runnable {
        renderSessionClock()
        clock.postDelayed(tick, 1_000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        attachWebView(binding.web, savedInstanceState)
        binding.backButton.setOnClickListener { goBack() }
        binding.forwardButton.setOnClickListener { webView?.takeIf { it.canGoForward() }?.goForward() }
        binding.refreshButton.setOnClickListener { refresh() }
        binding.homeButton.setOnClickListener { showHomeOrHomepage() }
        binding.adminButton.setOnClickListener { startActivity(android.content.Intent(this, AdminActivity::class.java)) }
        binding.goButton.setOnClickListener { submitAddress() }
        binding.blockedBack.setOnClickListener { hideBlocked() }
        binding.blockedHome.setOnClickListener {
            hideBlocked()
            showHomeOrHomepage()
        }
        binding.address.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitAddress()
            go
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.blockedOverlay.isVisible -> hideBlocked()
                        webView?.canGoBack() == true -> webView?.goBack()
                        KioskAssessment.describe(KioskController.snapshot(this@MainActivity)).canPreventLeaving -> Unit
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
        refreshHome()
        val graph = SecureBrowseApp.graph(this)
        if (savedInstanceState == null && graph.currentPolicy().homepage != null) {
            loadChecked(graph.currentPolicy().homepage)
        }
    }

    override fun onResume() {
        super.onResume()
        val graph = SecureBrowseApp.graph(this)
        webView?.let { WebViewConfigurator.apply(it, graph.currentPolicy(), ::onDownload) }
        if (graph.browsingEpoch != appliedEpoch) {
            appliedEpoch = graph.browsingEpoch
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            CookieManager.getInstance().flush()
        }
        refreshHome()
        maybeEnterKiosk()
        updateNavButtons()
        if (intent.getBooleanExtra(EXTRA_SESSION_ENDED, false)) {
            intent.removeExtra(EXTRA_SESSION_ENDED)
            Toast.makeText(this, R.string.session_ended, Toast.LENGTH_LONG).show()
        }
        clock.removeCallbacks(tick)
        clock.post(tick)
    }

    override fun onPause() {
        clock.removeCallbacks(tick)
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SESSION_ENDED, false)) showHomePanel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    private fun attachWebView(view: WebView, savedInstanceState: Bundle?) {
        webView = view
        bindClients(view)
        WebViewConfigurator.apply(view, SecureBrowseApp.graph(this).currentPolicy(), ::onDownload)
        if (savedInstanceState != null) {
            view.restoreState(savedInstanceState)
            val current = view.url
            val decision = UrlPolicy.evaluate(current, SecureBrowseApp.graph(this).currentPolicy(), UrlPolicy.Purpose.MAIN_FRAME)
            if (current != null && current != "about:blank" && !decision.allowed) {
                showBlocked(decision)
                showHomePanel()
            }
        }
    }

    private fun bindClients(view: WebView) {
        view.webViewClient = SecureWebViewClient(
            policy = { SecureBrowseApp.graph(this).currentPolicy() },
            callbacks = callbacks,
        )
        view.webChromeClient = SecureWebChromeClient(
            activity = this,
            policy = { SecureBrowseApp.graph(this).currentPolicy() },
            onProgress = { value ->
                binding.progress.isVisible = value in 1..99
                binding.progress.progress = value
            },
        )
    }

    private val callbacks = object : BrowserCallbacks {
        override fun onBlocked(decision: UrlPolicy.Decision) {
            if (isFinishing) return
            showBlocked(decision)
        }

        override fun onPage(url: String?) {
            if (url.isNullOrBlank() || url == "about:blank") return
            binding.address.setText(displayUrl(url))
            binding.homePanel.isVisible = false
            updateNavButtons()
        }

        override fun onProgress(value: Int) {
            binding.progress.isVisible = value in 1..99
            binding.progress.progress = value
        }

        override fun onError(message: String) {
            if (!binding.blockedOverlay.isVisible) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onRendererGone() {
            replaceCrashedWebView()
        }
    }

    private fun submitAddress() {
        loadChecked(binding.address.text?.toString())
    }

    private fun loadChecked(raw: String?) {
        val decision = UrlPolicy.evaluate(
            raw = raw,
            policy = SecureBrowseApp.graph(this).currentPolicy(),
            purpose = UrlPolicy.Purpose.MAIN_FRAME,
            hasUserGesture = true,
        )
        if (!decision.allowed || decision.openExternally) {
            showBlocked(decision)
            return
        }
        hideBlocked()
        binding.homePanel.isVisible = false
        webView?.loadUrl(raw!!.trim())
    }

    private fun refresh() {
        val current = webView?.url
        if (current.isNullOrBlank() || current == "about:blank") {
            showHomeOrHomepage()
            return
        }
        val decision = UrlPolicy.evaluate(current, SecureBrowseApp.graph(this).currentPolicy(), UrlPolicy.Purpose.MAIN_FRAME)
        if (!decision.allowed) {
            showBlocked(decision)
            return
        }
        webView?.reload()
    }

    private fun showHomeOrHomepage() {
        val homepage = SecureBrowseApp.graph(this).currentPolicy().homepage
        if (homepage.isNullOrBlank()) {
            webView?.loadUrl("about:blank")
            showHomePanel()
            binding.address.setText("")
            hideBlocked()
        } else {
            loadChecked(homepage)
        }
    }

    private fun showHomePanel() {
        binding.homePanel.isVisible = true
        refreshHome()
    }

    private fun refreshHome() {
        val graph = SecureBrowseApp.graph(this)
        val policy = graph.currentPolicy()
        val described = KioskAssessment.describe(KioskController.snapshot(this))
        binding.homeMode.text = when (described.presentation) {
            com.securebrowse.policy.LockPresentation.DEDICATED_KIOSK -> "Dedicated kiosk"
            com.securebrowse.policy.LockPresentation.SCREEN_PINNING -> "Screen pinning"
            com.securebrowse.policy.LockPresentation.NOT_LOCKED -> "Normal browser"
        }
        binding.homeSummary.text = described.summary
        binding.homeHomepage.text = policy.homepage?.let { "Homepage: $it" } ?: getString(R.string.no_homepage)
        binding.homeCount.text = getString(R.string.approved_count, policy.entries.size)
        renderSessionApps()
    }

    private fun renderSessionClock() {
        val session = SessionRuntime.active(this)
        if (session == null) {
            if (SecureBrowseApp.graph(this).sessions.current() != null) {
                SessionRuntime.end(this)
            }
            binding.sessionBanner.isVisible = false
            return
        }
        val left = SessionRules.formatRemaining(SessionRules.remainingMillis(session, System.currentTimeMillis()))
        binding.sessionBanner.isVisible = true
        binding.sessionBanner.text = getString(R.string.session_banner, left)
    }

    private fun renderSessionApps() {
        val session = SessionRuntime.active(this)
        if (session == null) {
            binding.sessionAppsLabel.isVisible = false
            binding.sessionApps.removeAllViews()
            renderedApps = null
            return
        }
        val packages = SessionRules.lockTaskPackages(packageName, session.packages).filter { it != packageName }
        val key = packages.joinToString()
        binding.sessionAppsLabel.isVisible = true
        binding.sessionAppsLabel.text = if (packages.isEmpty()) {
            getString(R.string.session_only_browser)
        } else {
            getString(R.string.session_apps_home)
        }
        if (key == renderedApps) return
        renderedApps = key
        binding.sessionApps.removeAllViews()
        packages.forEach { allowedPackage ->
            val row = ItemAppBinding.inflate(layoutInflater, binding.sessionApps, true)
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(allowedPackage, 0),
                ).toString()
            } catch (_: Exception) {
                allowedPackage
            }
            row.appLabel.text = label
            row.appPackage.text = allowedPackage
            row.appAction.text = "Open"
            row.appAction.setOnClickListener {
                val message = SessionRuntime.openAllowedApp(this, allowedPackage)
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBlocked(decision: UrlPolicy.Decision) {
        binding.blockedTitle.text = BlockMessages.title(decision)
        binding.blockedDetail.text = BlockMessages.detail(decision)
        binding.blockedOverlay.isVisible = true
    }

    private fun hideBlocked() {
        binding.blockedOverlay.isVisible = false
    }

    private fun goBack() {
        if (binding.blockedOverlay.isVisible) {
            hideBlocked()
            return
        }
        if (webView?.canGoBack() == true) webView?.goBack()
    }

    private fun updateNavButtons() {
        binding.backButton.alpha = if (webView?.canGoBack() == true) 1f else 0.4f
        binding.forwardButton.alpha = if (webView?.canGoForward() == true) 1f else 0.4f
    }

    private fun onDownload(url: String, disposition: String?, mime: String?) {
        DownloadGate.offer(this, url, disposition, mime, SecureBrowseApp.graph(this).currentPolicy(), ::showBlocked)
    }

    private fun maybeEnterKiosk() {
        val policy = SecureBrowseApp.graph(this).currentPolicy()
        if (SessionRuntime.maintain(this) != null) return
        if (!policy.kioskAutoStart || !KioskController.isDeviceOwner(this)) return
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return
        if (!dpm.isLockTaskPermitted(packageName)) {
            KioskController.applyPolicies(this, policy)
        }
        if (dpm.isLockTaskPermitted(packageName)) {
            try {
                startLockTask()
            } catch (_: IllegalArgumentException) {
                // Lock task was not permitted yet. The status line stays honest.
            }
        }
    }

    private fun replaceCrashedWebView() {
        val dead = webView ?: return
        val parent = dead.parent as? FrameLayout ?: return
        val index = parent.indexOfChild(dead)
        val params = dead.layoutParams
        parent.removeView(dead)
        dead.destroy()
        val fresh = WebView(this)
        fresh.id = R.id.web
        parent.addView(fresh, index, params)
        attachWebView(fresh, null)
        showHomePanel()
        hideBlocked()
        Toast.makeText(this, R.string.renderer_crashed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = android.webkit.WebViewClient()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun displayUrl(url: String): String {
        return try {
            val uri = URI(url)
            if (uri.userInfo != null) uri.host ?: "" else url
        } catch (_: Exception) {
            url.substringBefore('?')
        }
    }

    companion object {
        const val EXTRA_SESSION_ENDED = "com.securebrowse.app.SESSION_ENDED"
    }
}

object BrowsingEpoch {
    fun clearGlobal() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
