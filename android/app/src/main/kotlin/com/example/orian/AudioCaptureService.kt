package com.example.orian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        const val ACTION_START          = "START"
        const val ACTION_STOP           = "STOP"
        const val ACTION_UPDATE_OUTPUTS = "UPDATE_OUTPUTS"
        const val EXTRA_USE_BLUETOOTH   = "bluetooth"
        const val EXTRA_USE_WIRED       = "wired"
        const val EXTRA_USE_SPEAKER     = "speaker"
        const val CHANNEL_ID            = "audio_capture_channel"
        const val NOTIF_ID              = 2001

        var projectionResultCode: Int     = 0
        var projectionResultData: Intent? = null
    }

    // FIX 1: Use a dedicated SupervisorJob so individual child failures don't cancel the whole scope
    private val serviceJob = SupervisorJob()
    private val scope       = CoroutineScope(Dispatchers.IO + serviceJob)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord?         = null

    private var speakerTrack: AudioTrack? = null
    private var wiredTrack:   AudioTrack? = null
    private var btTrack:      AudioTrack? = null

    private var useSpeaker   = false
    private var useWired     = false
    private var useBluetooth = false

    // FIX 2: Guard the capture loop with @Volatile so it's visible across threads
    @Volatile private var isCapturing = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_IN  = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val ENCODING    = AudioFormat.ENCODING_PCM_16BIT

    private var deviceBroadcastReceiver: BroadcastReceiver? = null
    private var audioFocusRequest: AudioFocusRequest?       = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerDeviceReceiver()
    }

    private fun registerDeviceReceiver() {
        deviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_HEADSET_PLUG -> {
                        notifyDeviceChange()
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        notifyDeviceChange()
                    }
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        // FIX 3: Pause ALL tracks when audio becomes noisy (headphones unplugged),
                        // not just the wired track — prevents audio leaking to speaker unexpectedly.
                        if (isCapturing) {
                            pauseAllTracks()
                            useWired = false
                            // Re-apply routing so speaker/BT take over if still selected
                            applyDeviceRouting()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        // FIX 4: On Android 14+ (API 34) registerReceiver requires an explicit RECEIVER_EXPORTED
        // or RECEIVER_NOT_EXPORTED flag or it throws an exception.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(deviceBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(deviceBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            println("Error registering device receiver: ${e.message}")
        }
    }

    private fun notifyDeviceChange() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            updateDeviceStatus()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, true)   // FIX 5: default true for speaker
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
                startForegroundNotification()
                requestAudioFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // FIX 6: Only start capture if not already capturing
                    if (!isCapturing) startCapture()
                }
            }
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_OUTPUTS -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, true)
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
                // Re-pin before applying so new devices are picked up
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pinTracksToDevices()
                }
                applyDeviceRouting()
            }
        }
        return START_STICKY
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)  // FIX 7: handle delayed focus grant
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Resume if we were paused by a transient loss
                            if (isCapturing) applyDeviceRouting()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            pauseAllTracks()
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            stopCapture()
                        }
                    }
                }
                .build()
            am.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN              -> { if (isCapturing) applyDeviceRouting() }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseAllTracks()
                        AudioManager.AUDIOFOCUS_LOSS              -> stopCapture()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture() {
        val code = projectionResultCode
        val data = projectionResultData ?: run {
            println("No projection data available — call requestCapturePermission first")
            return
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(code, data)

            val minBuf    = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            // FIX 8: Use a larger buffer (4× min) to avoid underruns during routing switches
            val bufferSize = (minBuf * 4).coerceAtLeast(8192)

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

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("AudioRecord failed to initialize")
            }

            speakerTrack = buildTrack(bufferSize)
            wiredTrack   = buildTrack(bufferSize)
            btTrack      = buildTrack(bufferSize)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pinTracksToDevices()
            }

            applyDeviceRouting()

            audioRecord?.startRecording()
            isCapturing = true

            println("Audio capture started successfully")

            scope.launch {
                // FIX 9: Use a ByteArray/ShortArray sized to EXACTLY half of bufferSize shorts
                // (bufferSize is in bytes; each short = 2 bytes)
                val buffer     = ShortArray(bufferSize / 2)
                var writeCount = 0

                while (isCapturing) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                        // FIX 10: treat ERROR_INVALID_OPERATION and other negatives explicitly
                        if (read < 0) {
                            println("AudioRecord read error: $read")
                            break
                        }
                        if (read == 0) continue

                        writeCount++

                        // FIX 11: Write sequentially instead of parallel coroutine launches.
                        // Parallel writes to separate AudioTrack instances caused buffer
                        // mis-alignment and write errors with some devices.
                        if (useSpeaker) {
                            val r = speakerTrack?.write(buffer, 0, read) ?: 0
                            if (r < 0) println("Speaker write error: $r")
                        }
                        if (useWired) {
                            val r = wiredTrack?.write(buffer, 0, read) ?: 0
                            if (r < 0) println("Wired write error: $r")
                        }
                        if (useBluetooth) {
                            val r = btTrack?.write(buffer, 0, read) ?: 0
                            if (r < 0) println("Bluetooth write error: $r")
                        }

                        if (writeCount % 500 == 0) {
                            println("Capture cycle $writeCount — Speaker:$useSpeaker Wired:$useWired BT:$useBluetooth")
                        }
                    } catch (e: Exception) {
                        if (isCapturing) {
                            println("Error in capture loop: ${e.message}")
                            e.printStackTrace()
                        }
                        break
                    }
                }
                println("Capture loop ended after $writeCount cycles")
            }
        } catch (e: Exception) {
            println("Error starting capture: ${e.message}")
            e.printStackTrace()
            stopCapture()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun pinTracksToDevices() {
        try {
            val am      = getSystemService(AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var speakerDevice: AudioDeviceInfo? = null
            var wiredDevice:   AudioDeviceInfo? = null
            var btDevice:      AudioDeviceInfo? = null

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    -> speakerDevice = d
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES   -> wiredDevice   = d
                    // FIX 12: Prefer A2DP over SCO for music. SCO is low-quality (call audio).
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP     -> btDevice      = d
                }
            }

            speakerDevice?.let {
                speakerTrack?.preferredDevice = it
                println("Pinned speaker track → ${it.productName}")
            }
            wiredDevice?.let {
                wiredTrack?.preferredDevice = it
                println("Pinned wired track → ${it.productName}")
            }
            btDevice?.let {
                btTrack?.preferredDevice = it
                println("Pinned BT track → ${it.productName}")
            } ?: run {
                // FIX 13: If A2DP not found, fall back to SCO device for BT audio
                val scoDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                scoDevice?.let {
                    btTrack?.preferredDevice = it
                    println("Pinned BT track (SCO fallback) → ${it.productName}")
                }
            }
        } catch (e: Exception) {
            println("Error pinning tracks to devices: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateDeviceStatus() {
        try {
            val am      = getSystemService(AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var hasSpeaker   = false
            var hasWired     = false
            var hasBluetooth = false

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    -> hasSpeaker   = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES   -> hasWired     = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO      -> hasBluetooth = true
                }
            }

            println("Devices: Speaker=$hasSpeaker Wired=$hasWired BT=$hasBluetooth")
            println("Selected: Speaker=$useSpeaker Wired=$useWired BT=$useBluetooth")

            if (useWired && !hasWired) {
                println("Disabling wired — device disconnected")
                useWired = false
                wiredTrack?.pause()
            }
            if (useBluetooth && !hasBluetooth) {
                println("Disabling Bluetooth — device disconnected")
                useBluetooth = false
                btTrack?.pause()
            }

            // Re-pin and re-route to pick up any new devices
            pinTracksToDevices()
            applyDeviceRouting()
        } catch (e: Exception) {
            println("Error updating device status: ${e.message}")
        }
    }

    private fun applyDeviceRouting() {
        try {
            println("Routing — Speaker:$useSpeaker Wired:$useWired BT:$useBluetooth")

            // Speaker
            if (useSpeaker) {
                speakerTrack?.play()
                println("▶ Speaker")
            } else {
                speakerTrack?.pause()
                println("⏸ Speaker")
            }

            // Wired
            if (useWired) {
                wiredTrack?.play()
                println("▶ Wired")
            } else {
                wiredTrack?.pause()
                println("⏸ Wired")
            }

            // Bluetooth — verify device is actually present before playing
            if (useBluetooth) {
                val btAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                } else false

                if (btAvailable) {
                    btTrack?.play()
                    println("▶ Bluetooth")
                } else {
                    btTrack?.pause()
                    println("⚠ Bluetooth requested but no device present")
                }
            } else {
                btTrack?.pause()
                println("⏸ Bluetooth")
            }
        } catch (e: Exception) {
            println("Error applying device routing: ${e.message}")
            e.printStackTrace()
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

    private fun pauseAllTracks() {
        speakerTrack?.pause()
        wiredTrack?.pause()
        btTrack?.pause()
    }

    private fun stopCapture() {
        // FIX 14: Set flag FIRST to stop the capture coroutine cleanly before releasing resources
        isCapturing = false

        // Give the coroutine a brief moment to notice the flag change
        Thread.sleep(50)

        scope.coroutineContext.cancelChildren()

        pauseAllTracks()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        listOf(speakerTrack, wiredTrack, btTrack).forEach { track ->
            try {
                track?.stop()
                track?.release()
            } catch (e: Exception) {
                println("Error releasing track: ${e.message}")
            }
        }
        speakerTrack = null
        wiredTrack   = null
        btTrack      = null

        mediaProjection?.stop()
        mediaProjection = null

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setSpeakerphoneOn(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            audioFocusRequest = null
        } catch (e: Exception) {
            println("Error during audio cleanup: ${e.message}")
        }

        stopForeground(true)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orian Audio Router")
            .setContentText("Routing system audio…")
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
        // FIX 15: Cancel scope job on destroy to prevent coroutine leaks
        isCapturing = false
        serviceJob.cancel()
        if (deviceBroadcastReceiver != null) {
            try {
                unregisterReceiver(deviceBroadcastReceiver)
                deviceBroadcastReceiver = null
            } catch (e: Exception) {
                println("Error unregistering receiver: ${e.message}")
            }
        }
        // Release tracks that may not have been stopped via stopCapture()
        listOf(speakerTrack, wiredTrack, btTrack).forEach { it?.release() }
        speakerTrack = null; wiredTrack = null; btTrack = null
        audioRecord?.release(); audioRecord = null
        mediaProjection?.stop(); mediaProjection = null
        super.onDestroy()
    }
}