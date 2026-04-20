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

        var projectionResultCode: Int   = 0
        var projectionResultData: Intent? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    // One AudioTrack per physical output device
    private var speakerTrack: AudioTrack? = null
    private var wiredTrack: AudioTrack?   = null
    private var btTrack: AudioTrack?      = null

    private var useSpeaker   = false
    private var useWired     = false
    private var useBluetooth = false
    private var isCapturing  = false

    private val SAMPLE_RATE  = 44100
    private val CHANNEL_IN   = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_STEREO
    private val ENCODING     = AudioFormat.ENCODING_PCM_16BIT

    private var deviceBroadcastReceiver: BroadcastReceiver? = null
    private var audioFocusRequest: AudioFocusRequest? = null

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
                        val hasHeadset = intent.getIntExtra("state", 0) == 1
                        val microphone = intent.getIntExtra("microphone", 0)
                        notifyDeviceChange()
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        notifyDeviceChange()
                    }
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        if (isCapturing && useWired) {
                            // Wired headphones disconnected, mute wired output
                            wiredTrack?.pause()
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
        registerReceiver(deviceBroadcastReceiver, filter)
    }

    private fun notifyDeviceChange() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            updateDeviceStatus()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, false)
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
                requestAudioFocus()
                startForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startCapture()
            }
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_OUTPUTS -> {
                useSpeaker   = intent.getBooleanExtra(EXTRA_USE_SPEAKER, false)
                useBluetooth = intent.getBooleanExtra(EXTRA_USE_BLUETOOTH, false)
                useWired     = intent.getBooleanExtra(EXTRA_USE_WIRED, false)
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
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
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
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            pauseAllTracks()
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            stopCapture()
                        }
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
        val data = projectionResultData ?: return

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(code, data)

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val bufferSize = minBuf.coerceAtLeast(8192)

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

            // Build one AudioTrack per output
            speakerTrack = buildTrack(bufferSize).also { 
                println("Speaker track created: $it")
            }
            wiredTrack   = buildTrack(bufferSize).also { 
                println("Wired track created: $it")
            }
            btTrack      = buildTrack(bufferSize).also { 
                println("Bluetooth track created: $it")
            }

            // Pin each track to its physical device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pinTracksToDevices()
            }

            // Apply routing and START playing
            applyDeviceRouting()

            audioRecord?.startRecording()
            isCapturing = true
            
            println("Audio capture started successfully")

            scope.launch {
                val buffer = ShortArray(bufferSize / 2)
                var writeCount = 0
                
                while (isCapturing) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                        if (read <= 0) continue
                        
                        writeCount++
                        
                        // Write audio data to all enabled and playing tracks
                        // We write to all of them to ensure simultaneous playback
                        val jobs = mutableListOf<Job>()
                        
                        if (useSpeaker) {
                            jobs += launch { 
                                try {
                                    val result = speakerTrack?.write(buffer, 0, read) ?: 0
                                    if (result < 0) {
                                        println("Speaker track write error: $result")
                                    }
                                } catch (e: Exception) {
                                    println("Error writing to speaker track: ${e.message}")
                                }
                            }
                        }
                        
                        if (useWired) {
                            jobs += launch { 
                                try {
                                    val result = wiredTrack?.write(buffer, 0, read) ?: 0
                                    if (result < 0) {
                                        println("Wired track write error: $result")
                                    }
                                } catch (e: Exception) {
                                    println("Error writing to wired track: ${e.message}")
                                }
                            }
                        }
                        
                        if (useBluetooth) {
                            jobs += launch { 
                                try {
                                    val result = btTrack?.write(buffer, 0, read) ?: 0
                                    if (result < 0) {
                                        println("Bluetooth track write error: $result")
                                    }
                                } catch (e: Exception) {
                                    println("Error writing to bluetooth track: ${e.message}")
                                }
                            }
                        }
                        
                        if (jobs.isNotEmpty()) {
                            jobs.forEach { it.join() }
                        }
                        
                        // Log every 100 writes for debugging
                        if (writeCount % 100 == 0) {
                            println("Audio write cycle $writeCount - Speaker: $useSpeaker, Wired: $useWired, BT: $useBluetooth")
                        }
                    } catch (e: Exception) {
                        println("Error in capture loop: ${e.message}")
                        e.printStackTrace()
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
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var speakerDevice: AudioDeviceInfo? = null
            var wiredDevice: AudioDeviceInfo?   = null
            var btDevice: AudioDeviceInfo?      = null

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> speakerDevice = d
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> wiredDevice = d
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> btDevice = d
                }
            }

            speakerDevice?.let { 
                speakerTrack?.preferredDevice = it
                println("Pinned speaker track to device: ${it.productName}")
            }
            wiredDevice?.let { 
                wiredTrack?.preferredDevice = it
                println("Pinned wired track to device: ${it.productName}")
            }
            btDevice?.let { 
                btTrack?.preferredDevice = it
                println("Pinned Bluetooth track to device: ${it.productName}")
            }
            
            // Apply AudioManager settings to enforce routing
            applyAudioManagerRouting(am, devices)
        } catch (e: Exception) {
            println("Error pinning tracks to devices: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun applyAudioManagerRouting(am: AudioManager, devices: Array<AudioDeviceInfo>) {
        try {
            // Get current routing
            val hasSpeaker = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val hasWired = devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
            val hasBluetooth = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

            // Configure audio routing based on selected outputs
            // If speaker is selected AND wired/bluetooth also selected, keep speaker ON
            // Otherwise, disable speaker if other devices are available
            val allOutputsSelected = (useSpeaker && useBluetooth) || (useSpeaker && useWired) || (useBluetooth && useWired)
            
            if (useSpeaker && hasSpeaker) {
                am.setSpeakerphoneOn(true)
                println("Audio routing: Speaker ON")
            }
            
            if (useBluetooth && hasBluetooth) {
                // Force Bluetooth A2DP if available
                val btDevices = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btDevices.isNotEmpty()) {
                    println("Audio routing: Bluetooth device available - ${btDevices.first().productName}")
                }
            }
            
            if (useWired && hasWired) {
                println("Audio routing: Wired headphones in use")
            }
        } catch (e: Exception) {
            println("Error applying AudioManager routing: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateDeviceStatus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var hasSpeaker = false
            var hasWired = false
            var hasBluetooth = false

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> hasWired = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> hasBluetooth = true
                }
            }

            println("Device status: Speaker=$hasSpeaker, Wired=$hasWired, Bluetooth=$hasBluetooth")
            println("Current selection: Speaker=$useSpeaker, Wired=$useWired, Bluetooth=$useBluetooth")

            // If wired was selected but not connected, disable it
            if (useWired && !hasWired) {
                println("Disabling wired - device not available")
                useWired = false
                wiredTrack?.pause()
            }

            // If Bluetooth was selected but not connected, disable it
            if (useBluetooth && !hasBluetooth) {
                println("Disabling Bluetooth - device not available")
                useBluetooth = false
                btTrack?.pause()
            }

            // Re-pin tracks in case device topology changed
            pinTracksToDevices()
            
            // Re-apply routing to ensure proper device setup
            applyDeviceRouting()
        } catch (e: Exception) {
            println("Error updating device status: ${e.message}")
        }
    }

    private fun applyDeviceRouting() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            
            // Log current state
            println("Applying device routing - Speaker: $useSpeaker, Wired: $useWired, Bluetooth: $useBluetooth")
            
            // Speaker output
            if (useSpeaker) {
                speakerTrack?.play()
                println("▶ Speaker track playing")
            } else {
                speakerTrack?.pause()
                println("⏸ Speaker track paused")
            }
            
            // Wired output
            if (useWired) {
                wiredTrack?.play()
                println("▶ Wired track playing")
            } else {
                wiredTrack?.pause()
                println("⏸ Wired track paused")
            }
            
            // Bluetooth output - Don't use SCO (that's for calls), use A2DP for audio
            if (useBluetooth) {
                // Check if Bluetooth is actually available
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                } else {
                    emptyArray()
                }
                
                val btAvailable = devices.any { 
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
                }
                
                if (btAvailable) {
                    btTrack?.play()
                    println("▶ Bluetooth track playing (A2DP audio)")
                } else {
                    btTrack?.pause()
                    println("⚠ Bluetooth requested but no device available")
                }
            } else {
                btTrack?.pause()
                println("⏸ Bluetooth track paused")
            }
            
            // Ensure speaker phon is on if speaker is selected
            if (useSpeaker) {
                am.setSpeakerphoneOn(true)
            } else if (!useWired && !useBluetooth) {
                // Only disable speaker if no other devices are selected
                am.setSpeakerphoneOn(false)
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
        isCapturing = false
        scope.cancel()

        pauseAllTracks()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        listOf(speakerTrack, wiredTrack, btTrack).forEach {
            it?.stop()
            it?.release()
        }
        speakerTrack = null
        wiredTrack   = null
        btTrack      = null

        mediaProjection?.stop()
        mediaProjection = null

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            // Reset speaker phone setting
            am.setSpeakerphoneOn(false)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            audioFocusRequest = null
        } catch (e: Exception) {
            println("Error during cleanup: ${e.message}")
        }

        stopForeground(true)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orian Audio Router")
            .setContentText("Routing system audio...")
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
        if (deviceBroadcastReceiver != null) {
            try {
                unregisterReceiver(deviceBroadcastReceiver)
            } catch (e: Exception) {
                println("Error unregistering receiver: ${e.message}")
            }
        }
        super.onDestroy()
    }
}
