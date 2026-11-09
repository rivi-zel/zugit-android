package com.yiddishvtt.app

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences("yiddish_vtt_prefs", Context.MODE_PRIVATE)

    val serverUrl: String = ""  // Set this to your own transcription server (see README)

    var buttonX: Int
        get() = prefs.getInt("button_x", 0)
        set(value) = prefs.edit().putInt("button_x", value).apply()

    var buttonY: Int
        get() = prefs.getInt("button_y", -200)
        set(value) = prefs.edit().putInt("button_y", value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var showOnlyWithKeyboard: Boolean
        get() = prefs.getBoolean("show_only_keyboard", true)
        set(value) = prefs.edit().putBoolean("show_only_keyboard", value).apply()

    var userEmail: String
        get() = prefs.getString("user_email", "anonymous@test.com") ?: "anonymous@test.com"
        set(value) = prefs.edit().putString("user_email", value).apply()

    var userName: String
        get() = prefs.getString("user_name", "Anonymous") ?: "Anonymous"
        set(value) = prefs.edit().putString("user_name", value).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("has_completed_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_completed_onboarding", value).apply()

    var hasPickedLanguage: Boolean
        get() = prefs.getBoolean("has_picked_language", false)
        set(value) = prefs.edit().putBoolean("has_picked_language", value).apply()

    var hasButtonPosition: Boolean
        get() = prefs.getBoolean("has_button_position", false)
        set(value) = prefs.edit().putBoolean("has_button_position", value).apply()

    var isPositionLocked: Boolean
        get() = prefs.getBoolean("lock_position", true)
        set(value) = prefs.edit().putBoolean("lock_position", value).apply()

    var buttonScale: Float
        get() = prefs.getFloat("button_scale", 1.0f)
        set(value) = prefs.edit().putFloat("button_scale", value).apply()

    var showOnlyInApp: Boolean
        get() = prefs.getBoolean("show_only_in_app", false)
        set(value) = prefs.edit().putBoolean("show_only_in_app", value).apply()

    var hasGivenConsent: Boolean
        get() = prefs.getBoolean("has_given_consent", false)
        set(value) = prefs.edit().putBoolean("has_given_consent", value).apply()

    var hasSignedIn: Boolean
        get() = prefs.getBoolean("has_signed_in", false)
        set(value) = prefs.edit().putBoolean("has_signed_in", value).apply()

    // Set when the user opts out of Google Sign-In during onboarding so we
    // don't trap them in the sign-in dialog on every page revisit. They can
    // still sign in later from Settings.
    var didSkipSignIn: Boolean
        get() = prefs.getBoolean("did_skip_sign_in", false)
        set(value) = prefs.edit().putBoolean("did_skip_sign_in", value).apply()

    var hasSeenCoachTour: Boolean
        get() = prefs.getBoolean("has_seen_coach_tour", false)
        set(value) = prefs.edit().putBoolean("has_seen_coach_tour", value).apply()

    var copyToClipboard: Boolean
        get() = prefs.getBoolean("copy_to_clipboard", true)
        set(value) = prefs.edit().putBoolean("copy_to_clipboard", value).apply()

    var issueCaptureStartedAt: Long
        get() = prefs.getLong("issue_capture_started_at", 0L)
        set(value) = prefs.edit().putLong("issue_capture_started_at", value).apply()

    fun addTranscription(text: String) {
        val history = getTranscriptionHistory().toMutableList()
        history.add(0, text)
        if (history.size > 20) history.removeAt(history.size - 1)
        prefs.edit().putString("transcription_history", history.joinToString("\n---SPLIT---\n")).apply()
    }

    fun getTranscriptionHistory(): List<String> {
        val raw = prefs.getString("transcription_history", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n---SPLIT---\n").filter { it.isNotBlank() }
    }
}
