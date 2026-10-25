package com.securebrowse.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowse.app.R
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.app.databinding.ActivityAdminBinding
import com.securebrowse.app.databinding.ItemAppBinding
import com.securebrowse.app.databinding.ItemDomainBinding
import com.securebrowse.app.kiosk.KioskController
import com.securebrowse.app.session.InstalledApps
import com.securebrowse.app.session.SessionRuntime
import com.securebrowse.app.session.AdminSession
import com.securebrowse.app.ui.PassphraseActivity.Companion.EXTRA_MODE
import com.securebrowse.app.ui.PassphraseActivity.Companion.MODE_CHANGE
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.CompanionSuggestions
import com.securebrowse.policy.KioskAssessment
import com.securebrowse.policy.PolicyCodec
import com.securebrowse.policy.PolicyEdit
import com.securebrowse.policy.PolicyFormatException
import com.securebrowse.policy.SessionRules
import java.io.ByteArrayOutputStream
import java.io.InputStream

class AdminActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminBinding
    private var bindingUi = false
    private var ready = false

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || !AdminSession.isActive()) {
            finish()
        } else {
            ready = true
            bind()
        }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null || !AdminSession.isActive()) return@registerForActivityResult
        val json = try {
            SecureBrowseApp.graph(this).policies.exportDocument()
        } catch (_: Exception) {
            toast(getString(R.string.storage_damaged))
            return@registerForActivityResult
        }
        writeDocument(uri, json)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || !AdminSession.isActive()) return@registerForActivityResult
        val text = readDocument(uri) ?: return@registerForActivityResult
        when (val edit = SecureBrowseApp.graph(this).policies.importDocument(text)) {
            is PolicyEdit.Saved -> {
                SecureBrowseApp.graph(this).reload()
                bind()
                toast(getString(R.string.saved))
            }
            is PolicyEdit.Rejected -> toast(edit.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.adminRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.saveHomepage.setOnClickListener { saveHomepage() }
        binding.addDomain.setOnClickListener { addDomain() }
        binding.exportList.setOnClickListener {
            if (!AdminSession.isActive()) return@setOnClickListener
            createDocument.launch("securebrowse-allowlist.json")
        }
        binding.importList.setOnClickListener {
            if (!AdminSession.isActive()) return@setOnClickListener
            openDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.clearData.setOnClickListener { clearBrowsingData() }
        binding.applyPolicies.setOnClickListener { confirmApply() }
        binding.startKiosk.setOnClickListener { startKiosk() }
        binding.stopKiosk.setOnClickListener { stopKiosk() }
        binding.pinScreen.setOnClickListener { pinScreen() }
        binding.clearOwner.setOnClickListener { confirmClearOwner() }
        binding.changeSecret.setOnClickListener {
            startActivity(Intent(this, PassphraseActivity::class.java).putExtra(EXTRA_MODE, MODE_CHANGE))
        }
        binding.resetStorage.setOnClickListener { resetAllowlist() }
        binding.saveSession.setOnClickListener { persistSessionOptions() }
        binding.addSessionPackage.setOnClickListener { addSessionPackage() }
        binding.startSession.setOnClickListener { beginSession() }
        binding.endSession.setOnClickListener { finishSession() }
        listOf(15, 25, 45, 60).zip(
            listOf(binding.preset15, binding.preset25, binding.preset45, binding.preset60),
        ).forEach { (minutes, button) ->
            button.setOnClickListener {
                binding.sessionMinutesInput.setText(minutes.toString())
                persistSessionOptions()
            }
        }
        binding.appFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ready && AdminSession.isActive()) renderInstalledApps()
            }
        })
        listOf(binding.switchBlockStatusBar, binding.switchBlockNotifications).forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ ->
                if (!bindingUi) persistSessionOptions()
            }
        }
        listOf(
            binding.switchJavascript,
            binding.switchThirdParty,
            binding.switchDownloads,
            binding.switchCookies,
            binding.switchKioskAuto,
            binding.switchStrict,
        ).forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ ->
                if (!bindingUi) saveFlags()
            }
        }
        if (AdminSession.isActive()) {
            ready = true
            bind()
        } else {
            unlock.launch(Intent(this, PassphraseActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready && AdminSession.isActive()) bind()
        if (ready && !AdminSession.isActive()) finish()
    }

    private fun bind() {
        val graph = SecureBrowseApp.graph(this)
        val policy = graph.policies.getOrNull()
        bindingUi = true
        if (policy == null) {
            binding.storageError.isVisible = true
            binding.storageError.setText(R.string.storage_damaged)
            binding.resetStorage.isVisible = true
            bindingUi = false
            binding.statusBody.text = KioskAssessment.describe(KioskController.snapshot(this)).summary
            return
        }
        binding.storageError.isVisible = graph.policyDamaged != null
        binding.resetStorage.isVisible = graph.policyDamaged != null
        binding.homepageInput.setText(policy.homepage.orEmpty())
        binding.switchJavascript.isChecked = policy.javascriptEnabled
        binding.switchThirdParty.isChecked = policy.blockThirdPartyResources
        binding.switchDownloads.isChecked = policy.allowDownloads
        binding.switchCookies.isChecked = policy.allowThirdPartyCookies
        binding.switchKioskAuto.isChecked = policy.kioskAutoStart
        binding.switchStrict.isChecked = policy.strictKioskRestrictions
        binding.switchBlockStatusBar.isChecked = policy.blockStatusBar
        binding.switchBlockNotifications.isChecked = policy.blockNotifications
        binding.sessionMinutesInput.setText(policy.sessionMinutes.toString())
        bindingUi = false
        val described = KioskAssessment.describe(KioskController.snapshot(this))
        binding.statusBody.text = described.summary
        binding.kioskBody.text = buildString {
            append(
                if (KioskController.isDeviceOwner(this@AdminActivity)) {
                    "This install is the device owner."
                } else {
                    "This install is not the device owner. The allowlist applies only inside SecureBrowse."
                },
            )
            append("\n\nExit procedure: authenticate here, then Stop lock task. That does not remove device owner. Removing an adb test owner is the separate button. A device provisioned with a QR code or NFC usually needs a factory reset.")
        }
        renderDomains(policy)
        renderSession(policy)
    }

    private fun renderDomains(policy: BrowserPolicy) {
        binding.domainList.removeAllViews()
        policy.entries.forEach { entry ->
            val row = ItemDomainBinding.inflate(layoutInflater, binding.domainList, true)
            row.domainHost.text = entry.host
            row.domainMeta.setText(if (entry.allowSubdomains) R.string.subdomains_on else R.string.subdomains_off)
            row.domainRemove.setOnClickListener {
                if (!AdminSession.isActive()) return@setOnClickListener
                when (val edit = SecureBrowseApp.graph(this).policies.removeHost(entry.host)) {
                    is PolicyEdit.Saved -> {
                        SecureBrowseApp.graph(this).reload()
                        bind()
                    }
                    is PolicyEdit.Rejected -> toast(edit.message)
                }
            }
        }
    }

    private fun renderSession(policy: BrowserPolicy) {
        val now = System.currentTimeMillis()
        val running = SecureBrowseApp.graph(this).sessions.current()
        val live = running != null && !SessionRules.isExpired(running, now)
        binding.sessionStatus.text = if (live && running != null) {
            val left = SessionRules.formatRemaining(SessionRules.remainingMillis(running, now))
            val names = SessionRules.lockTaskPackages(packageName, running.packages).joinToString(", ")
            "Running · $left left. Lock task allowlist: $names. Edits below apply to the next session."
        } else if (policy.sessionPackages.isEmpty()) {
            getString(R.string.session_only_browser)
        } else {
            "Not running. Next session allows SecureBrowse and ${policy.sessionPackages.size} selected apps for ${policy.sessionMinutes} minutes."
        }
        binding.startSession.isEnabled = !live
        binding.endSession.isEnabled = live
        val risky = SessionRules.riskySelection(policy.sessionPackages)
        binding.sessionRisk.isVisible = risky.isNotEmpty()
        binding.sessionRisk.text = if (risky.isEmpty()) {
            ""
        } else {
            "These apps can be used to leave a session: ${risky.joinToString(", ")}. Allow them only if you mean to."
        }
        binding.sessionAppList.removeAllViews()
        policy.sessionPackages.forEach { packageName ->
            val row = ItemAppBinding.inflate(layoutInflater, binding.sessionAppList, true)
            row.appLabel.text = packageName
            row.appPackage.text = "Allowed in the next session"
            row.appAction.setText(R.string.remove)
            row.appAction.setTextColor(getColor(R.color.danger))
            row.appAction.setOnClickListener {
                if (!AdminSession.isActive()) return@setOnClickListener
                when (val edit = SecureBrowseApp.graph(this).policies.removeSessionPackage(packageName)) {
                    is PolicyEdit.Saved -> {
                        SecureBrowseApp.graph(this).reload()
                        bind()
                    }
                    is PolicyEdit.Rejected -> toast(edit.message)
                }
            }
        }
        renderInstalledApps()
    }

    private fun renderInstalledApps() {
        val selected = SecureBrowseApp.graph(this).policies.getOrNull()?.sessionPackages?.toSet().orEmpty()
        val query = binding.appFilter.text?.toString()?.trim()?.lowercase().orEmpty()
        val apps = InstalledApps.launchable(this).filter { app ->
            app.packageName !in selected && (
                query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
                )
        }.take(if (query.isEmpty()) 40 else 80)
        binding.installedAppList.removeAllViews()
        apps.forEach { app ->
            val row = ItemAppBinding.inflate(layoutInflater, binding.installedAppList, true)
            row.appLabel.text = app.label
            row.appPackage.text = app.packageName
            row.appAction.setText(R.string.allow_app)
            row.appAction.setOnClickListener {
                if (!AdminSession.isActive()) return@setOnClickListener
                when (val edit = SecureBrowseApp.graph(this).policies.addSessionPackage(app.packageName)) {
                    is PolicyEdit.Saved -> {
                        SecureBrowseApp.graph(this).reload()
                        bind()
                    }
                    is PolicyEdit.Rejected -> toast(edit.message)
                }
            }
        }
    }

    private fun persistSessionOptions(): Boolean {
        if (!AdminSession.isActive()) return false
        val minutes = binding.sessionMinutesInput.text?.toString()?.trim()?.toIntOrNull()
        return when (
            val edit = SecureBrowseApp.graph(this).policies.updateSessionOptions(
                sessionMinutes = minutes,
                blockStatusBar = binding.switchBlockStatusBar.isChecked,
                blockNotifications = binding.switchBlockNotifications.isChecked,
            )
        ) {
            is PolicyEdit.Saved -> {
                SecureBrowseApp.graph(this).reload()
                true
            }
            is PolicyEdit.Rejected -> {
                toast(edit.message)
                false
            }
        }
    }

    private fun addSessionPackage() {
        if (!AdminSession.isActive()) return
        val raw = binding.sessionPackageInput.text?.toString().orEmpty()
        if (SessionRules.normalizePackage(raw) == packageName) {
            toast("SecureBrowse is always allowed during a session.")
            return
        }
        when (val edit = SecureBrowseApp.graph(this).policies.addSessionPackage(raw)) {
            is PolicyEdit.Saved -> {
                SecureBrowseApp.graph(this).reload()
                binding.sessionPackageInput.text?.clear()
                bind()
            }
            is PolicyEdit.Rejected -> toast(edit.message)
        }
    }

    private fun beginSession() {
        if (!AdminSession.isActive()) return
        if (!persistSessionOptions()) return
        toast(SessionRuntime.start(this))
        bind()
    }

    private fun finishSession() {
        if (!KioskAssessment.exitAllowed(AdminSession.isActive())) return
        SessionRuntime.end(this)
        bind()
    }

    private fun sessionBusy(): Boolean {
        if (SessionRuntime.active(this) == null) return false
        toast(getString(R.string.session_busy))
        return true
    }

    private fun saveHomepage() {
        if (!AdminSession.isActive()) return
        when (val edit = SecureBrowseApp.graph(this).policies.setHomepage(binding.homepageInput.text?.toString())) {
            is PolicyEdit.Saved -> {
                SecureBrowseApp.graph(this).reload()
                toast(getString(R.string.saved))
            }
            is PolicyEdit.Rejected -> toast(edit.message)
        }
    }

    private fun addDomain() {
        if (!AdminSession.isActive()) return
        val raw = binding.domainInput.text?.toString().orEmpty()
        val before = SecureBrowseApp.graph(this).currentPolicy().entries.map { it.host }.toSet()
        when (val edit = SecureBrowseApp.graph(this).policies.addHost(raw, binding.switchSubdomains.isChecked)) {
            is PolicyEdit.Saved -> {
                SecureBrowseApp.graph(this).reload()
                binding.domainInput.text?.clear()
                bind()
                val host = edit.policy.entries.firstOrNull { it.host !in before }?.host
                if (host != null) offerCompanions(host)
            }
            is PolicyEdit.Rejected -> toast(edit.message)
        }
    }

    private fun offerCompanions(host: String) {
        val policy = SecureBrowseApp.graph(this).currentPolicy()
        val missing = CompanionSuggestions.forHost(host).filter { suggestion ->
            policy.entries.none { it.host == suggestion }
        }
        if (missing.isEmpty()) return
        val checked = BooleanArray(missing.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.suggestions_title)
            .setMessage(R.string.suggestions_body)
            .setMultiChoiceItems(missing.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton(R.string.add_suggested) { _, _ ->
                missing.forEachIndexed { index, suggestion ->
                    if (checked[index]) {
                        SecureBrowseApp.graph(this).policies.addHost(suggestion, allowSubdomains = true)
                    }
                }
                SecureBrowseApp.graph(this).reload()
                bind()
            }
            .show()
    }

    private fun saveFlags() {
        if (!AdminSession.isActive()) return
        val edit = SecureBrowseApp.graph(this).policies.updateFlags(
            javascriptEnabled = binding.switchJavascript.isChecked,
            blockThirdPartyResources = binding.switchThirdParty.isChecked,
            allowDownloads = binding.switchDownloads.isChecked,
            allowThirdPartyCookies = binding.switchCookies.isChecked,
            kioskAutoStart = binding.switchKioskAuto.isChecked,
            strictKioskRestrictions = binding.switchStrict.isChecked,
        )
        if (edit is PolicyEdit.Rejected) toast(edit.message)
        else SecureBrowseApp.graph(this).reload()
    }

    private fun clearBrowsingData() {
        if (!AdminSession.isActive()) return
        com.securebrowse.app.ui.BrowsingEpoch.clearGlobal()
        SecureBrowseApp.graph(this).bumpBrowsingEpoch()
        toast(getString(R.string.saved))
    }

    private fun confirmApply() {
        if (!AdminSession.isActive()) return
        if (sessionBusy()) return
        if (binding.switchStrict.isChecked) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.strict_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> applyNow() }
                .show()
        } else {
            applyNow()
        }
    }

    private fun applyNow() {
        saveFlags()
        val message = KioskController.applyPolicies(this, SecureBrowseApp.graph(this).currentPolicy())
        toast(message)
        bind()
    }

    private fun startKiosk() {
        if (!AdminSession.isActive()) return
        if (sessionBusy()) return
        if (!KioskController.isDeviceOwner(this)) {
            toast(getString(R.string.policies_need_owner))
            return
        }
        binding.switchKioskAuto.isChecked = true
        saveFlags()
        toast(KioskController.applyPolicies(this, SecureBrowseApp.graph(this).currentPolicy()))
        try {
            startLockTask()
        } catch (_: IllegalArgumentException) {
            toast(getString(R.string.policies_need_owner))
        }
    }

    private fun stopKiosk() {
        if (!KioskAssessment.exitAllowed(AdminSession.isActive())) return
        if (sessionBusy()) return
        binding.switchKioskAuto.isChecked = false
        saveFlags()
        try {
            stopLockTask()
            toast(getString(R.string.saved))
        } catch (_: IllegalArgumentException) {
            toast(getString(R.string.saved))
        }
    }

    private fun pinScreen() {
        if (sessionBusy()) return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.not_device_owner_pin)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    startLockTask()
                } catch (_: IllegalArgumentException) {
                    toast(getString(R.string.not_device_owner_pin))
                }
            }
            .show()
    }

    private fun confirmClearOwner() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_owner_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                toast(KioskController.clearDeviceOwnerForAdbTest(this))
                bind()
            }
            .show()
    }

    private fun resetAllowlist() {
        if (!AdminSession.isActive()) return
        SecureBrowseApp.graph(this).policies.replace(BrowserPolicy())
        SecureBrowseApp.graph(this).reload()
        bind()
    }

    private fun writeDocument(uri: Uri, json: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: toast("Could not write the file.")
        } catch (_: Exception) {
            toast("Could not write the file.")
        }
    }

    private fun readDocument(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = readCapped(input, PolicyCodec.MAX_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        } catch (ex: PolicyFormatException) {
            toast(ex.message ?: "Could not read the file.")
            null
        } catch (_: Exception) {
            toast("Could not read the file.")
            null
        }
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > max) throw PolicyFormatException("Allowlist file is too large.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
