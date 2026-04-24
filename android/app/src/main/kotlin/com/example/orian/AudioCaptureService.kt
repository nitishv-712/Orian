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
import android.util.Log
import kotlinx.coroutines.*

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START          = "START"
        const val ACTION_STOP           = "STOP"
        const val ACTION_UPDATE_OUTPUTS = "UPDATE_OUTPUTS"
        const val EXTRA_USE_BLUETOOTH   = "bluetooth"
        const val CHANNEL_ID            = "audio_capture_channel"
        const val NOTIF_ID              = 2001

        var projectionResultCode: Int     = 0
        var projectionResultData: Intent? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord?         = null

    // Only one track — pinned to Bluetooth A2DP.
    // We NEVER replay audio to speaker or wired jack because:
    //   - The original app (VLC etc.) already plays there
    //   - Replaying = double audio = noise/echo
    // Our job is ONLY to mirror to BT simultaneously.
    private var btTrack: AudioTrack? = null

    private var useBluetooth = false
    private var isCapturing  = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_IN  = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val ENCODING    = AudioFormat.ENCODING_PCM_16BIT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                startForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startCapture()
            }
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_OUTPUTS -> {
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                applyRouting()
            }
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture() {
        Log.d("OrianAudio", "startCapture() called, useBluetooth=$useBluetooth")
        val code = projectionResultCode
        val data = projectionResultData
        if (data == null) {
            Log.e("OrianAudio", "projectionResultData is null — permission not granted")
            return
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(code, data)
        Log.d("OrianAudio", "MediaProjection obtained: $mediaProjection")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            .coerceAtLeast(8192)

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

        Log.d("OrianAudio", "AudioRecord state=${audioRecord?.state} (1=INITIALIZED)")

        btTrack = buildTrack(bufferSize)
        Log.d("OrianAudio", "AudioTrack state=${btTrack?.state} (1=INITIALIZED)")

        pinBtTrack()
        applyRouting()

        audioRecord?.startRecording()
        Log.d("OrianAudio", "AudioRecord recordingState=${audioRecord?.recordingState} (3=RECORDING)")
        isCapturing = true

        scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            var totalFrames = 0L
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read < 0) {
                    Log.e("OrianAudio", "AudioRecord.read() error code: $read")
                    break
                }
                if (read == 0) continue
                totalFrames += read
                if (totalFrames % 44100 == 0L) {
                    Log.d("OrianAudio", "Captured ${totalFrames} frames so far, useBluetooth=$useBluetooth, btTrack playing=${btTrack?.playState}")
                }
                if (useBluetooth) btTrack?.write(buffer, 0, read)
            }
            Log.d("OrianAudio", "Capture loop ended, totalFrames=$totalFrames")
        }
    }

    private fun pinBtTrack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            Log.d("OrianAudio", "Available output devices: ${outputs.map { "${it.type}:${it.productName}" }}")
            val btDevice = outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (btDevice != null) {
                btTrack?.preferredDevice = btDevice
                Log.d("OrianAudio", "BT A2DP device pinned: ${btDevice.productName}")
            } else {
                Log.w("OrianAudio", "No Bluetooth A2DP device found")
            }
        }
    }

    private fun applyRouting() {
        if (useBluetooth) {
            pinBtTrack()
            btTrack?.play()
            Log.d("OrianAudio", "btTrack.play() called, playState=${btTrack?.playState}")
        } else {
            btTrack?.pause()
            Log.d("OrianAudio", "btTrack paused (bluetooth not selected)")
        }
    }

    private fun buildTrack(bufferSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                bufferSize, AudioTrack.MODE_STREAM
            )
        }
    }

    private fun stopCapture() {
        isCapturing = false
        scope.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        btTrack?.stop()
        btTrack?.release()
        btTrack = null

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(true)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orian Audio Router")
            .setContentText("Mirroring audio to Bluetooth...")
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
