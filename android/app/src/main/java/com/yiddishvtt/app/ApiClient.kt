package com.yiddishvtt.app

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(context: Context, private val settingsManager: SettingsManager) {

    // Hold only the application context — an Activity/Service reference would
    // leak if the ApiClient outlives its creator.
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "ApiClient"

        // Process-wide OkHttp instance. OkHttp keeps its own thread & connection
        // pools; creating one per ApiClient builds up pools that never get
        // reclaimed.
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val client: OkHttpClient get() = sharedClient

    data class TranscriptionResult(
        val success: Boolean,
        val text: String? = null,
        val duration: Double? = null,
        val error: String? = null,
    )

    fun transcribe(audioFile: File): TranscriptionResult {
        val serverUrl = settingsManager.serverUrl.trimEnd('/')
        if (serverUrl.isEmpty()) {
            return TranscriptionResult(false, error = appContext.getString(R.string.api_error_service_unavailable))
        }

        val url = "$serverUrl/api/transcribe"

        val mediaType = when {
            audioFile.name.endsWith(".wav") -> "audio/wav"
            audioFile.name.endsWith(".m4a") -> "audio/mp4"
            audioFile.name.endsWith(".webm") -> "audio/webm"
            audioFile.name.endsWith(".ogg") -> "audio/ogg"
            else -> "audio/mpeg"
        }.toMediaType()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
            .addFormDataPart("user_email", settingsManager.userEmail)
            .addFormDataPart("user_name", settingsManager.userName)
            .build()

        val request = Request.Builder().url(url).post(requestBody).build()

        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(bodyStr)
                TranscriptionResult(
                    success = true,
                    text = json.optString("text", ""),
                    duration = json.optDouble("duration", 0.0),
                )
            } else {
                Log.w(TAG, "Server ${response.code}: ${bodyStr.take(500)}")
                val userMsg = when (response.code) {
                    400 -> appContext.getString(R.string.api_error_empty_recording)
                    401, 403 -> appContext.getString(R.string.api_error_signin_required)
                    413 -> appContext.getString(R.string.api_error_too_long)
                    in 500..599 -> appContext.getString(R.string.api_error_service_busy)
                    else -> appContext.getString(R.string.api_error_transcribe_generic)
                }
                TranscriptionResult(false, error = userMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network call failed", e)
            TranscriptionResult(false, error = appContext.getString(R.string.toast_no_connection))
        }
    }

    // Fire-and-forget: wake the server the instant the keyboard opens so the
    // first transcribe of the session is faster. We never block on the
    // response and never surface errors.
    fun warmUp() {
        val serverUrl = settingsManager.serverUrl.trimEnd('/')
        if (serverUrl.isEmpty()) return
        val url = "$serverUrl/api/warm"
        val empty = RequestBody.create(null, ByteArray(0))
        val request = Request.Builder().url(url).post(empty).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                // Silent: warmup failures are not user-visible.
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
