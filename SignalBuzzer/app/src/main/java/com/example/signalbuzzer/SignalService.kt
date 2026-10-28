package com.example.signalbuzzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class SignalService : Service() {

    companion object {
        const val CHANNEL_ID = "signal_buzzer_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.signalbuzzer.STOP"
    }

    // Shared state
    private var dbRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var isFirstEvent = true
    private var currentRoomId: String? = null
    private var isBlinking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // WakeLock keeps CPU awake even when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    // Sender-only: MediaSession + silent AudioTrack to intercept volume keys
    private var mediaSession: MediaSession? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlayingSilence = false

    // ────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Prefs.getRole(this) == Prefs.ROLE_RECEIVER) {
                stopFlashAndVibration()
            }
            return START_STICKY
        }

        val role = Prefs.getRole(this)
        val roomId = Prefs.getRoomId(this) ?: "(not set)"
        startForeground(NOTIFICATION_ID, buildIdleNotification(role, roomId))

        when (role) {
            Prefs.ROLE_SENDER -> configureSender()
            Prefs.ROLE_RECEIVER -> configureReceiver()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        tearDownSender()
        tearDownReceiver()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────
    // Role configuration
    // ────────────────────────────────────────────────────────────────

    /**
     * Configure this instance as a SENDER.
     * - Detaches any Firebase listener (senders don't listen for pings)
     * - Starts a MediaSession with remote VolumeProvider so hardware volume keys
     *   are routed to our callback even when the screen is off
     * - Plays silent audio on STREAM_MUSIC so the OS keeps our MediaSession as
     *   the "active" media session (required for screen-off key routing)
     */
    private fun configureSender() {
        // Tear down receiver side (just in case role switched)
        stopFlashAndVibration()
        detachListener()

        setupMediaSession()
        startSilencePlayback()
    }

    /**
     * Configure this instance as a RECEIVER.
     * - Tears down sender-side resources
     * - Attaches a Firebase listener that triggers flash + vibration on signal
     */
    private fun configureReceiver() {
        // Tear down sender side (just in case role switched)
        tearDownSender()

        val roomId = Prefs.getRoomId(this) ?: return
        if (roomId != currentRoomId) {
            attachListener(roomId)
        }
    }

    private fun tearDownSender() {
        stopSilencePlayback()
        releaseMediaSession()
    }

    private fun tearDownReceiver() {
        stopFlashAndVibration()
        detachListener()
    }

    // ────────────────────────────────────────────────────────────────
    // Sender — MediaSession + silent AudioTrack
    // ────────────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        // Only create once
        if (mediaSession != null) return
        try {
            mediaSession = MediaSession(this, "SignalBuzzerSender").apply {
                // Register a callback so the session is "live"
                setCallback(object : MediaSession.Callback() {})

                // Remote volume provider: called whenever a hardware volume key is pressed
                val volumeProvider = object : VolumeProvider(
                    VOLUME_CONTROL_RELATIVE,
                    100,
                    50
                ) {
                    override fun onAdjustVolume(direction: Int) {
                        // direction == 1  → Volume Up pressed
                        // direction == -1 → Volume Down pressed
                        // direction == 0  → key released (ignore)
                        if (direction != 0) {
                            sendSignalToFirebase()
                        }
                    }
                }
                setPlaybackToRemote(volumeProvider)

                // Set a playing state so the session is treated as active
                val state = PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY
                                or PlaybackState.ACTION_PAUSE
                                or PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
                setPlaybackState(state)

                isActive = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaSession = null
    }

    /**
     * Plays silent PCM audio on STREAM_MUSIC. This is required because Android
     * only routes volume-key events to a MediaSession when the session belongs to
     * the currently-active media app. Playing (even silent) audio ensures our app
     * stays "active" in the media routing table while the screen is off.
     */
    private fun startSilencePlayback() {
        if (isPlayingSilence) return
        isPlayingSilence = true

        Thread {
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

                val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(encoding)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfig,
                        encoding,
                        minBuf,
                        AudioTrack.MODE_STREAM
                    )
                }

                audioTrack = track
                val silence = ShortArray(minBuf / 2) // 16-bit samples
                track.play()

                while (isPlayingSilence) {
                    track.write(silence, 0, silence.size)
                }

                track.stop()
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply {
            isDaemon = true
            name = "SignalBuzzer-SilenceThread"
            start()
        }
    }

    private fun stopSilencePlayback() {
        isPlayingSilence = false
        // The thread loop will exit; audioTrack.stop/release is called there
        audioTrack = null
    }

    // ────────────────────────────────────────────────────────────────
    // Sender — Firebase write
    // ────────────────────────────────────────────────────────────────

    private fun sendSignalToFirebase() {
        val roomId = Prefs.getRoomId(this) ?: return
        val deviceId = Prefs.getDeviceId(this)
        FirebaseDatabase.getInstance()
            .getReference("rooms").child(roomId).child("ping")
            .setValue(mapOf("ts" to ServerValue.TIMESTAMP, "from" to deviceId))
        mainHandler.post {
            Toast.makeText(applicationContext, "Signal sent ✓", Toast.LENGTH_SHORT).show()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Receiver — Firebase listener
    // ────────────────────────────────────────────────────────────────

    private fun attachListener(roomId: String) {
        detachListener()
        currentRoomId = roomId
        isFirstEvent = true
        val deviceId = Prefs.getDeviceId(this)
        dbRef = FirebaseDatabase.getInstance()
            .getReference("rooms").child(roomId).child("ping")

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Skip the first immediate snapshot (stale data from server)
                if (isFirstEvent) { isFirstEvent = false; return }

                val from = snapshot.child("from").getValue(String::class.java)
                // Only react to signals NOT sent by this device
                if (from != null && from != deviceId) {
                    // RECEIVER ONLY: blink flash + vibrate
                    if (Prefs.getRole(this@SignalService) == Prefs.ROLE_RECEIVER) {
                        blinkFlashAndVibrate()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef?.addValueEventListener(listener!!)
    }

    private fun detachListener() {
        listener?.let { dbRef?.removeEventListener(it) }
        listener = null
        dbRef = null
        currentRoomId = null
    }

    // ────────────────────────────────────────────────────────────────
    // Receiver — Flash + Vibration
    // ────────────────────────────────────────────────────────────────

    /**
     * Blinks the camera torch for 1 second and vibrates for 2 seconds.
     * ONLY called on RECEIVER devices.
     */
    private fun blinkFlashAndVibrate() {
        // Guard: never trigger on Sender devices
        if (Prefs.getRole(this) != Prefs.ROLE_RECEIVER) return
        if (isBlinking) return
        isBlinking = true

        // 1. Vibrate for 2 seconds
        startVibration(2000L)

        // 2. Turn torch ON
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val cameraId = try { cameraManager?.cameraIdList?.firstOrNull() } catch (e: Exception) { null }
        try {
            if (cameraId != null) cameraManager?.setTorchMode(cameraId, true)
        } catch (e: Exception) { e.printStackTrace() }

        updateNotification(buzzing = true)

        // 3. Turn torch OFF after 1 second
        mainHandler.postDelayed({
            try {
                if (cameraId != null) cameraManager?.setTorchMode(cameraId, false)
            } catch (e: Exception) { e.printStackTrace() }
            isBlinking = false
            updateNotification(buzzing = false)
        }, 1000L)
    }

    private fun startVibration(durationMs: Long) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelVibration() {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopFlashAndVibration() {
        cancelVibration()
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraId != null) cameraManager?.setTorchMode(cameraId, false)
        } catch (e: Exception) { e.printStackTrace() }
        isBlinking = false
        updateNotification(buzzing = false)
    }

    // ────────────────────────────────────────────────────────────────
    // WakeLock
    // ────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalBuzzer::WakeLock")
            wakeLock?.acquire()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { e.printStackTrace() }
        wakeLock = null
    }

    // ────────────────────────────────────────────────────────────────
    // Notifications
    // ────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Signal Buzzer", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))
                .createNotificationChannel(channel)
        }
    }

    private fun buildIdleNotification(role: String, roomId: String): android.app.Notification {
        val (title, text) = if (role == Prefs.ROLE_SENDER) {
            "Signal Buzzer – Sender Active" to "Press Vol ↑ / Vol ↓ to send (room: $roomId)"
        } else {
            "Signal Buzzer – Receiver Active" to "Listening in room: $roomId"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildAlertNotification(): android.app.Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, SignalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Signal received!")
            .setContentText("Flash blinking · Vibrating 2 s")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "STOP", stopPi)
            .build()
    }

    private fun updateNotification(buzzing: Boolean) {
        val role = Prefs.getRole(this)
        val roomId = Prefs.getRoomId(this) ?: "(not set)"
        val note = if (buzzing) buildAlertNotification() else buildIdleNotification(role, roomId)
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, note)
    }
}
