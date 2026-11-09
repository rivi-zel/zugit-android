package com.yiddishvtt.app

import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class YiddishRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var transcribeJob: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener ?: return
        try {
            val file = File(cacheDir, "vtt_ime_${System.currentTimeMillis()}.m4a")
            audioFile = file
            recorder = newRecorder(file).also { it.start() }
            listener.beginningOfSpeech()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder start failed", e)
            listener.error(SpeechRecognizer.ERROR_AUDIO)
        }
    }

    override fun onStopListening(listener: Callback?) {
        listener ?: return
        listener.endOfSpeech()
        val file = audioFile
        stopRecorder()
        if (file == null || !file.exists() || file.length() < 1024) {
            listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return
        }
        transcribeJob = scope.launch {
            val client = ApiClient(applicationContext, SettingsManager(applicationContext))
            val result = withContext(Dispatchers.IO) { client.transcribe(file) }
            file.delete()
            withContext(Dispatchers.Main) {
                if (result.success && !result.text.isNullOrBlank()) {
                    val bundle = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(result.text)
                        )
                    }
                    listener.results(bundle)
                } else {
                    listener.error(SpeechRecognizer.ERROR_NETWORK)
                }
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        transcribeJob?.cancel()
        stopRecorder()
        audioFile?.delete()
        audioFile = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopRecorder()
    }

    private fun stopRecorder() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun newRecorder(out: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        return r
    }

    companion object {
        private const val TAG = "YiddishRecognition"
    }
}
