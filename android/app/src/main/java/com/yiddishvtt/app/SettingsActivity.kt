package com.yiddishvtt.app

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.widget.SeekBar

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var tvUserName: TextView
    private lateinit var tvUserEmail: TextView
    private var repositionStartedInThisSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)

        val topAppBar = findViewById<MaterialToolbar>(R.id.settingsAppBar)
        tvUserName = findViewById(R.id.tvUserName)
        tvUserEmail = findViewById(R.id.tvUserEmail)
        val tvVersion = findViewById<TextView>(R.id.tvVersionFooter)
        tvVersion.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        updateUserDisplay()

        topAppBar.setNavigationOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btnKeyboardSetup).setOnClickListener {
            startActivity(Intent(this, KeyboardSetupActivity::class.java))
        }

        val swCopyToClipboard = findViewById<SwitchMaterial>(R.id.swCopyToClipboard)
        swCopyToClipboard.isChecked = settingsManager.copyToClipboard
        swCopyToClipboard.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.copyToClipboard = isChecked
        }

        findViewById<MaterialButton>(R.id.btnHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnFeedback).setOnClickListener {
            SupportHelper.sendFeedback(this)
        }

        findViewById<MaterialButton>(R.id.btnReportIssue).setOnClickListener {
            startIssueReportFlow()
        }

        findViewById<MaterialButton>(R.id.btnBuyMeCoffee).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/support"))
            startActivity(intent)
        }

        val btnLanguage = findViewById<MaterialButton>(R.id.btnLanguage)
        btnLanguage.text = currentLanguageLabel()
        btnLanguage.setOnClickListener { showLanguagePicker(btnLanguage) }

        findViewById<MaterialButton>(R.id.btnReposition).setOnClickListener {
            settingsManager.isPositionLocked = false
            settingsManager.hasButtonPosition = false
            repositionStartedInThisSession = true
            Toast.makeText(this, R.string.toast_button_unlocked, Toast.LENGTH_LONG).show()

            val intent = Intent(this, YiddishVttService::class.java)
            stopService(intent)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Focus a hidden EditText to surface the keyboard for positioning.
            val helper = findViewById<EditText>(R.id.etHiddenPositionHelper)
            helper?.postDelayed({
                helper.requestFocus()
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(helper, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }
    }

    override fun onStop() {
        super.onStop()
        // "till he leaves the screen, then it gets locked" — if the user
        // entered reposition mode in this session, tell the service to
        // commit the current position and return to normal click mode.
        if (repositionStartedInThisSession) {
            repositionStartedInThisSession = false
            val intent = Intent(this, YiddishVttService::class.java).apply {
                action = YiddishVttService.ACTION_LOCK_POSITION
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    // Language codes match the values-XX resource qualifiers. Hebrew uses the
    // legacy "iw" code (Android stores it that way).
    private val languageCodes = arrayOf("", "en", "iw")

    private fun currentLanguageLabel(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = if (locales.isEmpty) "" else locales[0]?.language ?: ""
        val labelRes = when (tag) {
            "en" -> R.string.language_english
            "iw", "he" -> R.string.language_hebrew
            else -> R.string.language_system_default
        }
        return getString(R.string.settings_language) + ": " + getString(labelRes)
    }

    private fun showLanguagePicker(trigger: MaterialButton) {
        val labels = arrayOf(
            getString(R.string.language_system_default),
            getString(R.string.language_english),
            getString(R.string.language_hebrew)
        )
        val current = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (current.isEmpty) "" else current[0]?.language ?: ""
        val checked = when (currentTag) {
            "en" -> 1
            "iw", "he" -> 2
            else -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_choose_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val tag = languageCodes[which]
                getSharedPreferences("yiddish_vtt_prefs", MODE_PRIVATE)
                    .edit().putBoolean("has_picked_language", true).apply()

                dialog.dismiss()

                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val lm = getSystemService(android.app.LocaleManager::class.java)
                    lm?.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
                } else {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag)
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateUserDisplay() {
        tvUserName.text = settingsManager.userName
        tvUserEmail.text = settingsManager.userEmail
    }

    // Issue-report flow: mark a timestamp, ask the user to reproduce the bug,
    // then on "I'm done" collect logs since the timestamp and email them.
    private fun startIssueReportFlow() {
        val capturingId = settingsManager.issueCaptureStartedAt
        if (capturingId > 0L) {
            promptFinishCapture()
        } else {
            promptStartCapture()
        }
    }

    private fun promptStartCapture() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_report_title)
            .setMessage(R.string.dialog_report_message)
            .setPositiveButton(R.string.action_start) { _, _ ->
                settingsManager.issueCaptureStartedAt = System.currentTimeMillis()
                Toast.makeText(this, R.string.toast_recording_logs, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptFinishCapture() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_send_report_title)
            .setMessage(R.string.dialog_send_report_message)
            .setPositiveButton(R.string.action_send) { _, _ ->
                val startedAt = settingsManager.issueCaptureStartedAt
                settingsManager.issueCaptureStartedAt = 0L
                val logs = SupportHelper.captureLogsSince(startedAt)
                SupportHelper.sendIssueReport(this, logs)
            }
            .setNeutralButton(R.string.action_discard) { _, _ ->
                settingsManager.issueCaptureStartedAt = 0L
                Toast.makeText(this, R.string.toast_report_discarded, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_keep_recording, null)
            .show()
    }
}
