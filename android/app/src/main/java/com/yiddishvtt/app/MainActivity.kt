package com.yiddishvtt.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.ImageView

class MainActivity : AppCompatActivity() {

    private lateinit var btnHeroMic: ImageView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSubtitle: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var llHistory: LinearLayout
    private lateinit var tvNoHistory: TextView
    private lateinit var cardPermsReminder: LinearLayout
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        if (!settingsManager.hasCompletedOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        btnHeroMic = findViewById(R.id.btnHeroMic)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        llHistory = findViewById(R.id.llHistory)
        tvNoHistory = findViewById(R.id.tvNoHistory)
        cardPermsReminder = findViewById(R.id.cardPermsReminder)
        findViewById<MaterialButton>(R.id.btnPermsReminderAction).setOnClickListener {
            val missing = firstMissingOnboardingStep()
            val intent = Intent(this, OnboardingActivity::class.java)
            if (missing != null) {
                intent.putExtra(OnboardingActivity.EXTRA_FIX_ONLY_PAGE, missing)
            }
            startActivity(intent)
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        findViewById<View>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Bottom nav: History scrolls to the history card, Record mirrors the
        // hero FAB, Settings opens SettingsActivity.
        findViewById<View>(R.id.navHistory).setOnClickListener {
            val cardHistory = findViewById<View>(R.id.cardHistory)
            val scroll = cardHistory.parent?.parent as? androidx.core.widget.NestedScrollView
            scroll?.post { scroll.smoothScrollTo(0, cardHistory.top) }
        }
        findViewById<View>(R.id.navRecord).setOnClickListener {
            btnHeroMic.performClick()
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnHeroMic.setOnClickListener {
            if (!checkAllPermissions()) {
                Toast.makeText(this, R.string.toast_grant_permissions, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, OnboardingActivity::class.java))
                return@setOnClickListener
            }

            // Optimistic UI flip: startForegroundService is async, so the user
            // gets immediate feedback before the real state catches up.
            val turningOn = !YiddishVttService.isRunning
            if (turningOn) {
                tvHeroTitle.text = getString(R.string.status_starting)
                tvHeroSubtitle.text = getString(R.string.hero_subtitle_activating)
                btnHeroMic.setImageResource(R.drawable.ic_mic_active)
                btnHeroMic.setBackgroundResource(R.drawable.bg_heimish_hero_on)
                btnHeroMic.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.heimish_on_surface))
                tvServiceStatus.text = getString(R.string.status_active)
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.heimish_success))
                val intent = Intent(this, YiddishVttService::class.java).apply {
                    putExtra("mode", "normal")
                }
                startForegroundServiceCompat(intent)
            } else {
                tvHeroTitle.text = getString(R.string.status_stopping)
                tvHeroSubtitle.text = getString(R.string.hero_subtitle_releasing)
                btnHeroMic.setImageResource(R.drawable.ic_mic)
                btnHeroMic.setBackgroundResource(R.drawable.bg_heimish_hero_off)
                btnHeroMic.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.heimish_on_primary))
                tvServiceStatus.text = getString(R.string.status_off)
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.heimish_ink_muted))
                stopService(Intent(this, YiddishVttService::class.java))
            }
            btnHeroMic.postDelayed({ updateUI() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        if (settingsManager.hasCompletedOnboarding) {
            updateUI()
            loadHistory()
        }
    }

    private fun checkAllPermissions(): Boolean {
        return hasMicPermission() && hasOverlayPermission()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Fast path: the service instance is bound whenever a11y is on.
        if (YiddishAccessibilityService.isServiceRunning()) return true
        // Fallback for the small window between toggle-on and onServiceConnected:
        // Android may store either the short (/.Name) or full (/pkg.Name) form.
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val shortName = "$packageName/.YiddishAccessibilityService"
        val fullName = "$packageName/$packageName.YiddishAccessibilityService"
        return enabled.contains(shortName) || enabled.contains(fullName)
    }

    // First unmet requirement in the order the user should fix them.
    // Returns null if nothing is missing (shouldn't happen when the
    // reminder card is visible, but makes the call site null-safe).
    private fun firstMissingOnboardingStep(): String? {
        if (!hasMicPermission()) return "mic"
        if (!hasOverlayPermission()) return "overlay"
        if (!isAccessibilityEnabled()) return "a11y"
        return null
    }

    private fun updateUI() {
        val micOk = hasMicPermission()
        val overlayOk = hasOverlayPermission()
        val a11yOk = isAccessibilityEnabled()

        if (micOk && overlayOk) {
            tvPermStatus.text = getString(if (a11yOk) R.string.perm_all_ok else R.string.perm_partial)
            tvPermStatus.setTextColor(ContextCompat.getColor(this, if (a11yOk) R.color.heimish_success else R.color.heimish_gold_accent))
        } else {
            tvPermStatus.text = getString(R.string.status_missing)
            tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.heimish_error))
        }

        cardPermsReminder.visibility = if (micOk && overlayOk && a11yOk) View.GONE else View.VISIBLE

        if (YiddishVttService.isRunning) {
            tvServiceStatus.text = getString(R.string.status_active)
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.heimish_success))
            tvHeroTitle.text = getString(R.string.status_listening)
            tvHeroSubtitle.text = getString(R.string.hero_subtitle_tap_stop)
            btnHeroMic.setImageResource(R.drawable.ic_mic_active)
            btnHeroMic.setBackgroundResource(R.drawable.bg_heimish_hero_on)
            btnHeroMic.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.heimish_on_surface))
        } else {
            tvServiceStatus.text = getString(R.string.status_off)
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.heimish_ink_muted))
            tvHeroTitle.text = getString(R.string.status_ready)
            tvHeroSubtitle.text = getString(R.string.hero_subtitle_tap_start)
            btnHeroMic.setImageResource(R.drawable.ic_mic)
            btnHeroMic.setBackgroundResource(R.drawable.bg_heimish_hero_off)
            btnHeroMic.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.heimish_on_primary))
        }
    }

    private fun loadHistory() {
        val history = settingsManager.getTranscriptionHistory()

        // Keep tvNoHistory at index 0; clear stale rows below it.
        while (llHistory.childCount > 1) {
            llHistory.removeViewAt(1)
        }

        if (history.isEmpty()) {
            tvNoHistory.visibility = View.VISIBLE
            return
        }

        tvNoHistory.visibility = View.GONE

        for (entry in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val tvText = TextView(this).apply {
                text = entry
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.heimish_on_surface))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 2
            }

            val btnCopy = TextView(this).apply {
                text = getString(R.string.action_copy)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.heimish_secondary))
                textSize = 13f
                setPadding(24, 8, 0, 8)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("vtt", entry))
                    Toast.makeText(this@MainActivity, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(tvText)
            row.addView(btnCopy)
            llHistory.addView(row)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.heimish_hairline))
            }
            llHistory.addView(divider)
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
