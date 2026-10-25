package com.securebrowse.app.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.securebrowse.app.R
import com.securebrowse.app.SecureBrowseApp
import com.securebrowse.app.databinding.ActivityPassphraseBinding
import com.securebrowse.app.session.AdminSession
import com.securebrowse.policy.AuthResult
import java.util.concurrent.Executors

class PassphraseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPassphraseBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mode: String by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        binding = ActivityPassphraseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.passRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val configured = try {
            SecureBrowseApp.graph(this).admin.isConfigured()
        } catch (_: Exception) {
            showError(getString(R.string.credential_damaged))
            binding.passSubmit.isEnabled = false
            false
        }
        val changing = mode == MODE_CHANGE && configured
        val setup = !configured
        binding.passTitle.setText(if (setup) R.string.pass_setup_title else if (changing) R.string.pass_change_title else R.string.pass_unlock_title)
        binding.passBody.setText(if (setup) R.string.pass_setup_body else if (changing) R.string.pass_change_body else R.string.pass_unlock_body)
        binding.confirmInput.isVisible = setup || changing
        binding.currentInput.isVisible = changing
        binding.passSubmit.setOnClickListener { submit(setup, changing) }
    }

    private fun submit(setup: Boolean, changing: Boolean) {
        val secret = charsOf(binding.secretInput)
        val confirm = if (binding.confirmInput.isVisible) charsOf(binding.confirmInput) else null
        val current = if (binding.currentInput.isVisible) charsOf(binding.currentInput) else null
        binding.secretInput.text?.clear()
        binding.confirmInput.text?.clear()
        binding.currentInput.text?.clear()
        if (confirm != null && !secret.contentEquals(confirm)) {
            secret.fill('\u0000')
            confirm.fill('\u0000')
            current?.fill('\u0000')
            showError(getString(R.string.pass_mismatch))
            return
        }
        confirm?.fill('\u0000')
        binding.passSubmit.isEnabled = false
        binding.passError.isVisible = false
        executor.execute {
            val result = try {
                val admin = SecureBrowseApp.graph(this).admin
                when {
                    setup -> admin.setup(secret)
                    changing && current != null -> admin.change(current, secret)
                    else -> admin.verify(secret)
                }
            } catch (_: Exception) {
                secret.fill('\u0000')
                current?.fill('\u0000')
                null
            }
            runOnUiThread {
                binding.passSubmit.isEnabled = true
                when (result) {
                    AuthResult.Accepted -> {
                        AdminSession.grant()
                        setResult(RESULT_OK)
                        finish()
                    }
                    is AuthResult.InvalidSecret -> showError(result.message)
                    is AuthResult.Rejected -> showError(getString(R.string.pass_wrong))
                    is AuthResult.Locked -> showError(getString(R.string.pass_locked))
                    AuthResult.Unconfigured, null -> showError(getString(R.string.credential_damaged))
                }
            }
        }
    }

    private fun charsOf(field: android.widget.EditText): CharArray {
        val value = CharArray(field.length())
        field.text?.getChars(0, field.length(), value, 0)
        return value
    }

    private fun showError(message: String) {
        binding.passError.text = message
        binding.passError.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_CHANGE = "change"
    }
}
