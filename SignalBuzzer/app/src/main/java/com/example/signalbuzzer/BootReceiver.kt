package com.example.signalbuzzer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only restart the service if the user had it turned ON
            // and a Room ID is configured
            if (Prefs.isServiceEnabled(context) && Prefs.getRoomId(context) != null) {
                val serviceIntent = Intent(context, SignalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
