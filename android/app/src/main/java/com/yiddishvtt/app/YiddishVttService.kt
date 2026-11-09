package com.yiddishvtt.app

import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import com.yiddishvtt.app.R
import kotlin.math.abs

class YiddishVttService : Service() {

    companion object {
        private const val TAG = "YiddishVttService"
        private const val CHANNEL_ID = "yiddish_vtt_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_LOCK_POSITION = "com.yiddishvtt.app.LOCK_POSITION"
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var apiClient: ApiClient

    private var floatingView: View? = null
    private var controlView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var micButton: ImageView? = null
    private var pbLoading: ProgressBar? = null
    private var recordingIndicator: View? = null
    private var recBars: List<View> = emptyList()
    private val recBarAnimators: MutableList<Animator> = mutableListOf()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var isRepositioning = false
    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var vibrator: Vibrator? = null

    private val keyboardPollHandler = Handler(Looper.getMainLooper())
    private val keyboardPollRunnable = object : Runnable {
        override fun run() {
            // Some OEM keyboards dismiss without firing TYPE_WINDOWS_CHANGED, leaving
            // the button stuck. Re-poll every 2s as a safety net.
            YiddishAccessibilityService.forceKeyboardRecheck()
            keyboardPollHandler.postDelayed(this, 2000)
        }
    }

    private val keyboardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == YiddishAccessibilityService.ACTION_KEYBOARD_STATE) {
                val visible = intent.getBooleanExtra(
                    YiddishAccessibilityService.EXTRA_KEYBOARD_VISIBLE, false
                )
                onKeyboardVisibilityChanged(visible)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager(this)
        apiClient = ApiClient(this, settingsManager)
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        createNotificationChannel()

        val filter = IntentFilter(YiddishAccessibilityService.ACTION_KEYBOARD_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyboardReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keyboardReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(getString(R.string.notification_title_active))
        startForeground(NOTIFICATION_ID, notification)

        // Reposition-session ended: the caller (Settings/Onboarding) tells us
        // to commit the current position and return to normal click mode.
        if (intent?.action == ACTION_LOCK_POSITION) {
            if (floatingView != null) lockPosition()
            return START_STICKY
        }

        showFloatingButton()
        isRunning = true

        keyboardPollHandler.removeCallbacks(keyboardPollRunnable)
        keyboardPollHandler.postDelayed(keyboardPollRunnable, 2000)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        keyboardPollHandler.removeCallbacks(keyboardPollRunnable)
        try { unregisterReceiver(keyboardReceiver) } catch (_: Exception) {}
        stopRecordingBarsAnimation()
        removeFloatingButton()
        stopRecordingIfNeeded()
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastKeyboardVisibleSeen = false

    private fun onKeyboardVisibilityChanged(visible: Boolean) {
        // Fire warmup on the hidden -> visible edge. User is about to
        // tap the mic; wake the server so it's ready by the time
        // the audio arrives.
        if (visible && !lastKeyboardVisibleSeen) {
            try { apiClient.warmUp() } catch (_: Exception) {}
        }
        lastKeyboardVisibleSeen = visible

        // Never hide during repositioning or recording.
        if (isRepositioning || isRecording) {
            floatingView?.visibility = View.VISIBLE
            return
        }
        floatingView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showFloatingButton() {
        if (floatingView != null) {
            isRepositioning = !settingsManager.isPositionLocked
            if (isRepositioning) {
                floatingView?.visibility = View.VISIBLE
            }
            setupDragAndScale()
            return
        }

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_mic, null)
        micButton = floatingView?.findViewById(R.id.floatingMicButton)
        pbLoading = floatingView?.findViewById(R.id.pbMicLoading)
        recordingIndicator = floatingView?.findViewById(R.id.recordingIndicator)
        recBars = listOfNotNull(
            floatingView?.findViewById(R.id.recBar1),
            floatingView?.findViewById(R.id.recBar2),
            floatingView?.findViewById(R.id.recBar3),
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val hasPosition = settingsManager.hasButtonPosition

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (hasPosition) {
                x = settingsManager.buttonX
                y = settingsManager.buttonY
            } else {
                // Keyboard covers the lower ~45% of the screen. Place the mic
                // in the upper-middle area so it's visible during onboarding
                // where the IME is intentionally shown.
                val dm = resources.displayMetrics
                x = 32
                y = (dm.heightPixels * 0.30f).toInt()
            }
        }

        val scale = settingsManager.buttonScale
        micButton?.scaleX = scale
        micButton?.scaleY = scale
        recordingIndicator?.scaleX = scale
        recordingIndicator?.scaleY = scale

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
            return
        }

        isRepositioning = !settingsManager.isPositionLocked
        floatingView?.visibility = if (isRepositioning) View.VISIBLE else View.GONE

        setupDragAndScale()
    }

    private var coachStep = 0

    private fun setupDragAndScale() {
        val coachTip = floatingView?.findViewById<TextView>(R.id.tvCoachTip)
        val dm = resources.displayMetrics

        if (isRepositioning) {
            floatingView?.visibility = View.VISIBLE
            showFloatingControls()

            coachTip?.visibility = View.GONE

            micButton?.setOnClickListener(null)
            micButton?.setOnLongClickListener(null)
            micButton?.isClickable = false
            micButton?.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = true
                        initialX = layoutParams?.x ?: 0
                        initialY = layoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        layoutParams?.let { params ->
                            val maxX = dm.widthPixels - 80
                            val maxY = dm.heightPixels - 80
                            params.x = (initialX + dx.toInt()).coerceIn(0, maxX)
                            params.y = (initialY + dy.toInt()).coerceIn(0, maxY)
                            floatingView?.let { fv ->
                                try { windowManager.updateViewLayout(fv, params) } catch (_: Exception) {}
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Save on every lift so the latest position is durable,
                        // but do NOT lock: the user can keep dragging. Locking
                        // happens only when they leave the reposition screen
                        // (Settings/Onboarding sends ACTION_LOCK_POSITION).
                        layoutParams?.let { params ->
                            settingsManager.buttonX = params.x
                            settingsManager.buttonY = params.y
                            settingsManager.hasButtonPosition = true
                        }
                        isDragging = false
                        true
                    }
                    else -> false
                }
            }
        } else {
            isRepositioning = false
            micButton?.setOnTouchListener(null)
            micButton?.isClickable = true
            micButton?.setOnClickListener { toggleRecording() }

            // Long-press unlocks: must flip isRepositioning before re-init.
            micButton?.setOnLongClickListener {
                settingsManager.isPositionLocked = false
                isRepositioning = true
                floatingView?.visibility = View.VISIBLE
                setupDragAndScale()
                Toast.makeText(this, R.string.toast_drag_hint, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun showFloatingControls() {
        if (controlView != null) return
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        controlView = inflater.inflate(R.layout.floating_controls, null)
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        
        val sizeSlider = controlView?.findViewById<SeekBar>(R.id.sizeSlider)
        val btnDone = controlView?.findViewById<View>(R.id.btnPositionDone)
        
        sizeSlider?.max = 150
        sizeSlider?.progress = ((settingsManager.buttonScale - 0.5f) * 100).toInt()
        sizeSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val scale = 0.5f + (progress / 100f)
                    micButton?.scaleX = scale
                    micButton?.scaleY = scale
                    recordingIndicator?.scaleX = scale
                    recordingIndicator?.scaleY = scale
                    settingsManager.buttonScale = scale
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        btnDone?.setOnClickListener {
            lockPosition()
        }
        
        try {
            windowManager.addView(controlView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add control view", e)
        }
    }
    
    private fun removeFloatingControls() {
        controlView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove control view", e)
            }
        }
        controlView = null
    }

    private fun lockPosition() {
        removeFloatingControls()
        val coachTip = floatingView?.findViewById<TextView>(R.id.tvCoachTip)
        coachTip?.visibility = View.GONE
        settingsManager.isPositionLocked = true
        settingsManager.hasSeenCoachTour = true
        settingsManager.buttonX = layoutParams?.x ?: 0
        settingsManager.buttonY = layoutParams?.y ?: 0
        settingsManager.hasButtonPosition = true
        isRepositioning = false
        Toast.makeText(this, R.string.toast_position_saved, Toast.LENGTH_SHORT).show()
        setupDragAndScale()
        onKeyboardVisibilityChanged(YiddishAccessibilityService.lastKeyboardVisible)
    }

    private fun showCoachTip(tip: TextView?, text: String) {
        tip ?: return
        tip.text = text
        tip.visibility = View.VISIBLE
        tip.alpha = 0f
        tip.animate().alpha(1f).setDuration(200).start()
    }

    // Tour steps: 0 = drag, 1 = resize, 2 = tap Done.
    private fun advanceCoach(tip: TextView?) {
        coachStep++
        when (coachStep) {
            1 -> showCoachTip(tip, getString(R.string.coach_tip_resize))
            2 -> showCoachTip(tip, getString(R.string.coach_tip_tap_done))
            else -> tip?.visibility = View.GONE
        }
    }

    private fun removeFloatingButton() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove floating view", e)
            }
        }
        floatingView = null
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        try {
            audioFile = File(cacheDir, "recording_${System.currentTimeMillis()}.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            showRecordingIndicator(true)
            vibrateShort()
            updateNotification(getString(R.string.notification_recording))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, R.string.toast_couldnt_start, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            showRecordingIndicator(false)
            showLoading(true)
            vibrateShort()
            updateNotification("📤 Sending to server...")

            audioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    sendAudioToServer(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }
    }

    private fun stopRecordingIfNeeded() {
        if (isRecording) {
            try {
                mediaRecorder?.apply { stop(); release() }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder", e)
            }
            mediaRecorder = null
            isRecording = false
        }
    }

    private fun sendAudioToServer(file: File) {
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.transcribe(file)
                }

                showLoading(false)

                if (result.success) {
                    val text = result.text ?: ""
                    Log.i(TAG, "Transcription result: $text")
                    settingsManager.addTranscription(text)

                    val injected = YiddishAccessibilityService.injectText(text)
                    Log.i(TAG, "Text injection result: $injected")

                    // Copy to clipboard if user opted in, or always when injection failed (so the text is recoverable).
                    if (settingsManager.copyToClipboard || !injected) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("yiddish-vtt", text))
                    }

                    updateNotification("✅ $text")
                    if (injected) {
                        Toast.makeText(this@YiddishVttService, text, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@YiddishVttService, getString(R.string.toast_clipboard_copied, text), Toast.LENGTH_LONG).show()
                    }
                    vibrateShort()
                    delay(3000)
                    updateNotification(getString(R.string.notification_title_ready))

                } else {
                    val msg = result.error ?: getString(R.string.api_error_transcribe_generic)
                    updateNotification("❌ $msg")
                    Toast.makeText(this@YiddishVttService, msg, Toast.LENGTH_SHORT).show()
                    delay(3000)
                    updateNotification(getString(R.string.notification_title_ready))
                }

            } catch (e: Exception) {
                showLoading(false)
                Log.e(TAG, "Transcription failed", e)
                updateNotification(getString(R.string.notification_no_connection))
                Toast.makeText(this@YiddishVttService, R.string.toast_no_connection, Toast.LENGTH_SHORT).show()
                delay(3000)
                updateNotification(getString(R.string.notification_title_ready))
            } finally {
                file.delete()
            }
        }
    }

    // While recording, the mic icon is hidden and replaced by a pulsing 3-bar
    // indicator. Tap it to stop; the existing micButton click path is bypassed
    // because the indicator sits on top of the button.
    private fun showRecordingIndicator(show: Boolean) {
        val indicator = recordingIndicator ?: return
        if (show) {
            micButton?.visibility = View.INVISIBLE
            indicator.visibility = View.VISIBLE
            indicator.isClickable = true
            indicator.setOnClickListener { toggleRecording() }
            startRecordingBarsAnimation()
        } else {
            stopRecordingBarsAnimation()
            indicator.visibility = View.GONE
            indicator.setOnClickListener(null)
            indicator.isClickable = false
            // Caller decides whether to re-show micButton (showLoading may keep it
            // hidden while the transcription is in flight).
        }
    }

    private fun startRecordingBarsAnimation() {
        stopRecordingBarsAnimation()
        recBars.forEachIndexed { index, bar ->
            bar.scaleY = 0.4f
            val anim = ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.4f, 1f, 0.4f).apply {
                duration = 700
                startDelay = index * 140L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            anim.start()
            recBarAnimators.add(anim)
        }
    }

    private fun stopRecordingBarsAnimation() {
        recBarAnimators.forEach { it.cancel() }
        recBarAnimators.clear()
    }

    private fun showLoading(show: Boolean) {
        pbLoading?.visibility = if (show) View.VISIBLE else View.GONE
        micButton?.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    }
}
