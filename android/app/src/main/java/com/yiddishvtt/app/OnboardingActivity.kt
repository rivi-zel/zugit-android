package com.yiddishvtt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var settingsManager: SettingsManager

    private val fixOnlyPageId: String? by lazy {
        intent?.getStringExtra(EXTRA_FIX_ONLY_PAGE)
    }

    private val pages by lazy {
        val base = mutableListOf<PageData>()
        base += listOf(
            PageData(
                "welcome",
                getString(R.string.onboarding_welcome_title),
                getString(R.string.onboarding_welcome_desc),
                R.drawable.ic_mic,
                null
            ),
            PageData(
                "consent",
                getString(R.string.onboarding_consent_title),
                getString(R.string.onboarding_consent_desc),
                R.drawable.ic_mic,
                null
            ),
            PageData(
                "login",
                getString(R.string.onboarding_login_title),
                getString(R.string.onboarding_login_desc),
                R.drawable.ic_mic,
                getString(R.string.action_sign_in_google)
            ),
            PageData(
                "notifications",
                getString(R.string.onboarding_notif_title),
                getString(R.string.onboarding_notif_desc),
                R.drawable.ic_mic,
                getString(R.string.action_grant_notifications)
            ),
            PageData(
                "mic",
                getString(R.string.onboarding_mic_title),
                getString(R.string.onboarding_mic_desc),
                R.drawable.ic_mic,
                getString(R.string.action_grant_microphone)
            ),
            PageData(
                "overlay",
                getString(R.string.onboarding_overlay_title),
                getString(R.string.onboarding_overlay_desc),
                R.drawable.ic_mic,
                getString(R.string.action_allow_overlay)
            ),
            PageData(
                "a11y",
                getString(R.string.onboarding_a11y_title),
                getString(R.string.onboarding_a11y_desc),
                R.drawable.ic_mic,
                getString(R.string.action_open_accessibility)
            ),
            PageData(
                "positioning",
                getString(R.string.onboarding_position_title),
                getString(R.string.onboarding_position_desc),
                R.drawable.ic_mic,
                getString(R.string.action_got_it)
            )
        )
        val all = base.toList()
        val only = fixOnlyPageId
        if (only != null) all.filter { it.id == only } else all
    }

    companion object {
        const val RC_SIGN_IN = 9001
        const val RC_MIC = 100
        const val RC_OVERLAY = 101
        const val RC_NOTIFICATIONS = 102
        // Main screen's "finish setup" button passes the id of the first
        // missing page (mic/overlay/a11y). When set, onboarding shows only
        // that page and returns home once it's resolved.
        const val EXTRA_FIX_ONLY_PAGE = "fix_only_page"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        settingsManager = SettingsManager(this)
        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        viewPager.adapter = OnboardingAdapter(pages)
        viewPager.isUserInputEnabled = false

        btnNext.setOnClickListener {
            val currentItem = viewPager.currentItem
            val currentPage = pages[currentItem]

            if (currentPage.id == "consent" && !settingsManager.hasGivenConsent) {
                Toast.makeText(this, R.string.toast_consent_needed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (currentPage.id == "login" && !settingsManager.hasSignedIn) {
                showSignInDialog()
                return@setOnClickListener
            }

            if (currentItem < pages.size - 1) {
                viewPager.currentItem = currentItem + 1
                updateButtons()
            } else {
                completeOnboarding()
            }
        }

        btnSkip.setOnClickListener { }

        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        val currentItem = viewPager.currentItem
        val page = pages[currentItem]

        btnSkip.visibility = View.GONE
        btnNext.text = getString(if (currentItem == pages.size - 1) R.string.action_finish else R.string.action_next)
        btnNext.visibility = if (page.id == "language") View.GONE else View.VISIBLE

        if (page.id == "login" && !settingsManager.hasSignedIn && !settingsManager.didSkipSignIn) {
            showSignInDialog()
        }

        if (page.id == "positioning") {
            settingsManager.isPositionLocked = false
            settingsManager.hasButtonPosition = false
            val posIntent = Intent(this, YiddishVttService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(posIntent)
            } else {
                startService(posIntent)
            }
        }
    }

    private fun completeOnboarding() {
        settingsManager.hasCompletedOnboarding = true
        settingsManager.isPositionLocked = true
        sendLockPositionToService()
        if (fixOnlyPageId == null) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun sendLockPositionToService() {
        val intent = Intent(this, YiddishVttService::class.java).apply {
            action = YiddishVttService.ACTION_LOCK_POSITION
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private var signInDialog: AlertDialog? = null

    private fun showSignInDialog() {
        if (signInDialog?.isShowing == true) return
        signInDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_signin_title)
            .setMessage(R.string.dialog_signin_message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_sign_in_google) { d, _ ->
                d.dismiss()
                doGoogleLogin()
            }
            .show()
    }

    fun doGoogleLogin() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .requestProfile()
                .build()
            val client = GoogleSignIn.getClient(this, gso)
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        } catch (e: Exception) {
            Log.e("Onboarding", "Google Sign-In setup failed", e)
            Toast.makeText(
                this,
                getString(R.string.toast_signin_unavailable) + "\n" + (e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                RC_NOTIFICATIONS
            )
        } else {
            // Pre-Android 13 — no runtime notification permission. Just advance.
            viewPager.currentItem = viewPager.currentItem + 1
            updateButtons()
        }
    }

    fun requestMic() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
    }

    fun requestOverlay() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivityForResult(intent, RC_OVERLAY)
    }

    fun requestA11y() {
        Toast.makeText(this, R.string.toast_a11y_find_app, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        Toast.makeText(this, R.string.toast_restricted_settings, Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val email = account?.email
                if (email.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.toast_signin_no_email, Toast.LENGTH_LONG).show()
                } else {
                    settingsManager.userEmail = email
                    settingsManager.userName = account.displayName ?: email
                    settingsManager.hasSignedIn = true
                    Toast.makeText(this, getString(R.string.toast_signin_success, account.displayName ?: email), Toast.LENGTH_SHORT).show()
                    viewPager.currentItem = viewPager.currentItem + 1
                    updateButtons()
                }
            } catch (e: ApiException) {
                Log.w("Onboarding", "signInResult:failed code=" + e.statusCode, e)
                // Surface the error code so users can report it (e.g. code 10
                // means the SHA-1 is not registered in the Google Cloud OAuth
                // client — common on freshly-rotated Play Store signing keys).
                Toast.makeText(
                    this,
                    getString(R.string.toast_signin_didnt_complete) + " (code " + e.statusCode + ")",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == RC_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                viewPager.currentItem = viewPager.currentItem + 1
                updateButtons()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            viewPager.currentItem = viewPager.currentItem + 1
            updateButtons()
        }
        // Advance on notifications regardless of grant: if the user denies,
        // we still want them to reach the mic step. The service will fall back
        // to a silent foreground notification.
        if (requestCode == RC_NOTIFICATIONS) {
            viewPager.currentItem = viewPager.currentItem + 1
            updateButtons()
        }
    }

    inner class OnboardingAdapter(private val items: List<PageData>) : RecyclerView.Adapter<OnboardingAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvPageTitle)
            val tvDesc: TextView = v.findViewById(R.id.tvPageDesc)
            val btnAction: MaterialButton = v.findViewById(R.id.btnPageAction)
            val ivIcon: ImageView = v.findViewById(R.id.ivPageIcon)
            val cbConsent: CheckBox = v.findViewById(R.id.cbConsent)
            val tvPrivacyLink: TextView = v.findViewById(R.id.tvPrivacyLink)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.page_onboarding, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val page = items[position]
            holder.tvTitle.text = page.title
            holder.tvDesc.text = page.desc
            holder.ivIcon.setImageResource(page.iconId)

            if (page.id == "consent") {
                holder.cbConsent.visibility = View.VISIBLE
                holder.cbConsent.isChecked = settingsManager.hasGivenConsent
                holder.cbConsent.setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.hasGivenConsent = isChecked
                }
                holder.tvPrivacyLink.visibility = View.VISIBLE
                holder.tvPrivacyLink.paintFlags = holder.tvPrivacyLink.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                holder.tvPrivacyLink.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/privacy")))
                }
            } else {
                holder.cbConsent.visibility = View.GONE
                holder.tvPrivacyLink.visibility = View.GONE
            }

            val tilInput = holder.itemView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPageInput)
            if (page.id == "positioning") {
                tilInput.visibility = View.VISIBLE
                val intent = Intent(this@OnboardingActivity, YiddishVttService::class.java)
                startForegroundServiceCompat(intent)
                // Pop the keyboard so the user sees what the floating button
                // will actually sit above. The EditText is otherwise unused.
                val et = holder.itemView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPageInput)
                et?.postDelayed({
                    et.requestFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 300)
            } else {
                tilInput.visibility = View.GONE
            }

            if (page.actionText != null) {
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.text = page.actionText
                holder.btnAction.setOnClickListener {
                    when (page.id) {
                        "login" -> showSignInDialog()
                        "notifications" -> requestNotifications()
                        "mic" -> requestMic()
                        "overlay" -> requestOverlay()
                        "a11y" -> requestA11y()
                        "positioning" -> {
                            settingsManager.hasButtonPosition = true
                            sendLockPositionToService()
                            viewPager.currentItem = viewPager.currentItem + 1
                            updateButtons()
                        }
                    }
                }

                if (page.id == "a11y") {
                    holder.btnAction.text = getString(R.string.action_open_accessibility_step1)
                    holder.ivIcon.setOnClickListener { openAppInfo() }
                    Toast.makeText(this@OnboardingActivity, R.string.toast_a11y_grayed_tip, Toast.LENGTH_SHORT).show()
                }
            } else {
                holder.btnAction.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    data class PageData(val id: String, val title: String, val desc: String, val iconId: Int, val actionText: String?)
}
