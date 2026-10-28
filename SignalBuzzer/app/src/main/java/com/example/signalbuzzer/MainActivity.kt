package com.example.signalbuzzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

class MainActivity : AppCompatActivity() {

    private lateinit var roomLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var serviceToggleButton: Button
    private lateinit var sendTestButton: Button
    private lateinit var roleRadioGroup: RadioGroup
    private lateinit var radioSender: RadioButton
    private lateinit var radioReceiver: RadioButton
    private lateinit var changeRoomButton: Button

    // Track running state in memory (backed by Prefs)
    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        roomLabel = findViewById(R.id.roomLabel)
        statusLabel = findViewById(R.id.statusLabel)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)
        sendTestButton = findViewById(R.id.sendTestButton)
        roleRadioGroup = findViewById(R.id.roleRadioGroup)
        radioSender = findViewById(R.id.radioSender)
        radioReceiver = findViewById(R.id.radioReceiver)
        changeRoomButton = findViewById(R.id.changeRoomButton)

        // Initialize role radio
        val currentRole = Prefs.getRole(this)
        if (currentRole == Prefs.ROLE_SENDER) {
            radioSender.isChecked = true
        } else {
            radioReceiver.isChecked = true
        }
        updateRoleUI(currentRole)

        roleRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newRole = if (checkedId == R.id.radioSender) Prefs.ROLE_SENDER else Prefs.ROLE_RECEIVER
            Prefs.setRole(this, newRole)
            updateRoleUI(newRole)
            // If service is already running, restart it with new role
            if (serviceRunning) {
                startSignalService()
            }
        }

        // Restore service state
        serviceRunning = Prefs.isServiceEnabled(this)
        updateToggleUI()

        serviceToggleButton.setOnClickListener {
            if (serviceRunning) {
                turnServiceOff()
            } else {
                // Need a Room ID before starting
                if (Prefs.getRoomId(this) == null) {
                    showRoomIdDialog(onSaved = { turnServiceOn() })
                } else {
                    turnServiceOn()
                }
            }
        }

        changeRoomButton.setOnClickListener { showRoomIdDialog() }
        sendTestButton.setOnClickListener { sendSignal() }

        // Update room label
        Prefs.getRoomId(this)?.let { roomLabel.text = "Room: $it" }

        requestNotificationPermissionIfNeeded()
    }

    // ─────────────────────────────────────────────────────────────
    // Service ON / OFF
    // ─────────────────────────────────────────────────────────────

    private fun turnServiceOn() {
        if (Prefs.getRoomId(this) == null) {
            Toast.makeText(this, "Set a Room ID first", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setServiceEnabled(this, true)
        serviceRunning = true
        startSignalService()
        updateToggleUI()
    }

    private fun turnServiceOff() {
        Prefs.setServiceEnabled(this, false)
        serviceRunning = false
        stopSignalService()
        updateToggleUI()
    }

    private fun startSignalService() {
        if (Prefs.getRoomId(this) == null) return
        val intent = Intent(this, SignalService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSignalService() {
        stopService(Intent(this, SignalService::class.java))
    }

    // ─────────────────────────────────────────────────────────────
    // UI updates
    // ─────────────────────────────────────────────────────────────

    private fun updateToggleUI() {
        if (serviceRunning) {
            serviceToggleButton.text = "STOP SERVICE"
            // Disable role switching while service is on
            radioSender.isEnabled = false
            radioReceiver.isEnabled = false
            changeRoomButton.isEnabled = false
            val role = Prefs.getRole(this)
            val roomId = Prefs.getRoomId(this) ?: "(not set)"
            if (role == Prefs.ROLE_SENDER) {
                statusLabel.text = "🟢 Active — Press Vol ↑/↓ to send signal\n(works with screen off)"
                sendTestButton.visibility = View.VISIBLE
            } else {
                statusLabel.text = "🟢 Active — Listening in room: $roomId\n(works with screen off)"
                sendTestButton.visibility = View.GONE
            }
        } else {
            serviceToggleButton.text = "START SERVICE"
            radioSender.isEnabled = true
            radioReceiver.isEnabled = true
            changeRoomButton.isEnabled = true
            statusLabel.text = "🔴 Service stopped — tap START to activate"
            sendTestButton.visibility = View.GONE
        }
    }

    private fun updateRoleUI(role: String) {
        // Only update status text when service is NOT running
        if (!serviceRunning) {
            if (role == Prefs.ROLE_SENDER) {
                statusLabel.text = "Sender ready — tap START SERVICE to activate"
            } else {
                statusLabel.text = "Receiver ready — tap START SERVICE to activate"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Room ID dialog
    // ─────────────────────────────────────────────────────────────

    private fun showRoomIdDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "e.g. family123"
        val existing = Prefs.getRoomId(this)
        if (existing != null) input.setText(existing)

        AlertDialog.Builder(this)
            .setTitle("Enter Room ID")
            .setMessage("Both phones must use the exact same Room ID.")
            .setView(input)
            .setCancelable(existing != null)
            .setPositiveButton("Save") { _, _ ->
                val roomId = input.text.toString().trim()
                if (roomId.isNotEmpty()) {
                    Prefs.setRoomId(this, roomId)
                    roomLabel.text = "Room: $roomId"
                    // Update status if service running
                    if (serviceRunning) {
                        startSignalService() // re-start with new room
                        updateToggleUI()
                    }
                    onSaved?.invoke()
                } else {
                    Toast.makeText(this, "Room ID can't be empty", Toast.LENGTH_SHORT).show()
                    showRoomIdDialog(onSaved)
                }
            }
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    // Signal sending (foreground / test)
    // ─────────────────────────────────────────────────────────────

    private fun sendSignal() {
        if (Prefs.getRole(this) != Prefs.ROLE_SENDER) return
        if (!serviceRunning) {
            Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            return
        }
        val roomId = Prefs.getRoomId(this) ?: return
        val deviceId = Prefs.getDeviceId(this)
        FirebaseDatabase.getInstance()
            .getReference("rooms").child(roomId).child("ping")
            .setValue(mapOf("ts" to ServerValue.TIMESTAMP, "from" to deviceId))
        Toast.makeText(this, "Signal sent ✓", Toast.LENGTH_SHORT).show()
    }

    // Intercepts volume keys while screen is ON and app is in foreground (Sender only)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!serviceRunning) return super.dispatchKeyEvent(event)
        if (Prefs.getRole(this) != Prefs.ROLE_SENDER) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) sendSignal()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ─────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }
}
