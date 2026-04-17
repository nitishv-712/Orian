package com.example.orian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_UPDATE_OUTPUTS = "UPDATE_OUTPUTS"
        const val EXTRA_USE_BLUETOOTH = "bluetooth"
        const val EXTRA_USE_WIRED = "wired"
        const val EXTRA_USE_SPEAKER = "speaker"
        const val CHANNEL_ID = "audio_capture_channel"
        const val NOTIF_ID = 2001

        var projectionResultCode: Int = 0
        var projectionResultData: Intent? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    // Only ONE output track — routes to BT or wired headset.
    // Speaker is NEVER replayed (original app already outputs to speaker).
    private var headsetTrack: AudioTrack? = null

    private var useSpeaker = true
    private var useBluetooth = false
    private var useWired = false
    private var isCapturing = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, true)
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
                startForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startCapture()
            }
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_OUTPUTS -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, true)
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
                updateAudioRouting()
            }
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture() {
        val code = projectionResultCode
        val data = projectionResultData ?: return

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(code, data)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            .coerceAtLeast(4096)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_IN)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        // This track ONLY goes to headset (BT SCO or wired).
        // STREAM_VOICE_CALL forces routing to the connected headset,
        // NOT back to the speaker — this is what prevents echo.
        headsetTrack = buildHeadsetTrack(bufferSize)

        updateAudioRouting()
        audioRecord?.startRecording()
        isCapturing = true

        scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                // Only write to headset track if BT or wired is selected.
                // If only speaker is selected, we write nothing — the original
                // app is already playing to the speaker, no replay needed.
                if (read > 0 && (useBluetooth || useWired)) {
                    headsetTrack?.write(buffer, 0, read)
                }
            }
        }
    }

    private fun buildHeadsetTrack(bufferSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // VOICE_CALL stream routes to headset, not speaker
                        .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                bufferSize, AudioTrack.MODE_STREAM
            )
        }
    }

    private fun updateAudioRouting() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager

        when {
            useBluetooth -> {
                // Route headset track to BT SCO
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                headsetTrack?.play()
            }
            useWired -> {
                // Wired headset — stop BT SCO so audio goes to wired jack
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                headsetTrack?.play()
            }
            else -> {
                // Speaker only — pause headset track, no replay needed
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                headsetTrack?.pause()
            }
        }

        // Speaker: the original app handles this — we never touch it.
        // No muting, no replaying = no echo.
    }

    private fun stopCapture() {
        isCapturing = false
        scope.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        headsetTrack?.stop()
        headsetTrack?.release()
        headsetTrack = null

        mediaProjection?.stop()
        mediaProjection = null

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.stopBluetoothSco()
        am.isBluetoothScoOn = false

        stopForeground(true)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orian Audio Router")
            .setContentText("Routing system audio to headset...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
