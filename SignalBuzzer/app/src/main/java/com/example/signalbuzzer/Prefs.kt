package com.example.signalbuzzer

import android.content.Context
import java.util.UUID

object Prefs {
    private const val PREFS_NAME = "signal_buzzer_prefs"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ROLE = "role"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    const val ROLE_SENDER = "sender"
    const val ROLE_RECEIVER = "receiver"

    fun isServiceEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, false)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun getRoomId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROOM_ID, null)
    }

    fun setRoomId(context: Context, roomId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    fun getRole(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, ROLE_RECEIVER) ?: ROLE_RECEIVER
    }

    fun setRole(context: Context, role: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role).apply()
    }

    // Unique per-install ID so a phone never blinks because of its own signal.
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
}
