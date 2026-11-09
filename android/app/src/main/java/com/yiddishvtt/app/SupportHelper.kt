package com.yiddishvtt.app

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SupportHelper {

    private const val SUPPORT_EMAIL = "support@example.com"

    fun sendFeedback(context: Context) {
        runSafely(context) {
            val body = buildString {
                append(context.getString(R.string.support_describe_feedback))
                append("\n\n\n\n")
                append(diagnosticBlock(context, logs = null))
            }
            launchEmail(context, subject = context.getString(R.string.support_subject_feedback), body = body)
        }
    }

    fun sendIssueReport(context: Context, logs: String?) {
        runSafely(context) {
            val body = buildString {
                append(context.getString(R.string.support_describe_issue))
                append("\n\n\n\n")
                append(diagnosticBlock(context, logs))
            }
            launchEmail(context, subject = context.getString(R.string.support_subject_issue), body = body)
        }
    }

    private inline fun runSafely(context: Context, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("SupportHelper", "Email flow failed", t)
            try {
                Toast.makeText(
                    context,
                    "Email error: ${t.javaClass.simpleName}: ${t.message ?: ""}. Write to $SUPPORT_EMAIL",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) { }
        }
    }

    private fun launchEmail(context: Context, subject: String, body: String) {
        val cappedBody = if (body.length > BODY_CAP) {
            body.substring(0, BODY_CAP) + "\n\n[...truncated to fit email intent...]"
        } else body

        // 1) Primary: ACTION_SENDTO + mailto: URI — only email clients
        //    resolve this intent. Body travels via extras so we never
        //    blow the Binder transaction limit even with log dumps.
        val sendTo = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, cappedBody)
        }
        if (context !is android.app.Activity) sendTo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(sendTo)
            return
        } catch (_: Exception) { /* fall through */ }

        // 2) Fallback: ACTION_SEND via createChooser. Bypasses the need
        //    for a registered default SENDTO handler — shows the share
        //    sheet which always lists Gmail / Outlook / K-9 / etc.
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, "To: $SUPPORT_EMAIL\n\n$cappedBody")
            }
            val chooser = Intent.createChooser(share, "Send email")
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            return
        } catch (_: Exception) { /* fall through */ }

        // 3) Last resort: copy the message to the clipboard and tell the
        //    user the email address. Some Play Store devices ship with
        //    no email client at all — don't leave them stuck.
        try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val fullText = "To: $SUPPORT_EMAIL\nSubject: $subject\n\n$cappedBody"
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("support-email", fullText))
        } catch (_: Exception) { }
        Toast.makeText(
            context,
            "No email app — copied message to clipboard. Paste it in any email app and send to $SUPPORT_EMAIL",
            Toast.LENGTH_LONG
        ).show()
    }

    // ~60 KB leaves headroom under the 1 MB Binder transaction limit once
    // the OS adds its own overhead.
    private const val BODY_CAP = 60_000

    private fun diagnosticBlock(context: Context, logs: String?): String {
        val sb = StringBuilder()
        sb.append("--- Diagnostic Info (Please do not remove) ---\n")
        sb.append("App: ${context.getString(R.string.app_name)} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n\n")

        sb.append("[Permissions]\n")
        sb.append("Mic: ${permStatus(context, Manifest.permission.RECORD_AUDIO)}\n")
        sb.append("Overlay: ${Settings.canDrawOverlays(context)}\n")
        sb.append("Notifications: ${if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "GRANTED" else "DENIED"}\n\n")

        sb.append("[Services]\n")
        sb.append("Main Service Running: ${YiddishVttService.isRunning}\n")
        sb.append("Accessibility Connected: ${YiddishAccessibilityService.isServiceRunning()}\n")
        sb.append("Internet Available: ${hasInternet(context)}\n\n")

        sb.append("[Device]\n")
        sb.append(memoryInfo(context))
        sb.append("\n")

        if (!logs.isNullOrBlank()) {
            sb.append("[Logs]\n")
            sb.append(logs)
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun permStatus(context: Context, perm: String): String =
        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.activeNetwork != null
    }

    private fun memoryInfo(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "RAM: unknown\n"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)
        return "RAM: ${availMb}MB available / ${totalMb}MB total\nLow Memory: ${mi.lowMemory}\n"
    }

    // Reads logcat since a given timestamp. Modern Android restricts this to
    // the calling app's own logs, which is exactly what we want for a report.
    fun captureLogsSince(startTimeMillis: Long, maxLines: Int = 500): String {
        return try {
            // logcat -T accepts epoch seconds with millis as "<sec>.<ms>".
            // The "MM-dd HH:mm:ss.SSS" format we used before silently
            // matched nothing on most devices, so reports came back empty.
            val safeStart = if (startTimeMillis <= 0L) System.currentTimeMillis() - 60_000 else startTimeMillis
            val since = "%d.%03d".format(safeStart / 1000, safeStart % 1000)
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-T", since))
            val reader = process.inputStream.bufferedReader()
            val lines = reader.readLines().takeLast(maxLines)
            reader.close()
            val out = lines.joinToString("\n")
            if (out.isBlank()) "(no log entries captured since $since)" else out
        } catch (e: Exception) {
            "Log capture failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
