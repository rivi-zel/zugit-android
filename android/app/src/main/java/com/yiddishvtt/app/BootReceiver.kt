package com.yiddishvtt.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val settings = SettingsManager(context)
            // If the user revoked overlay permission while the app was asleep,
            // starting the foreground service would crash on its first
            // WindowManager.addView — bail out silently instead.
            if (settings.isServiceEnabled &&
                settings.serverUrl.isNotEmpty() &&
                Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, YiddishVttService::class.java).apply {
                    putExtra("mode", "normal")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
