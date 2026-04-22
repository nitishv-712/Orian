package com.example.orian

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel

class DeviceMonitor(private val context: Context) {
    private var eventSink: EventChannel.EventSink? = null
    private var deviceBroadcastReceiver: BroadcastReceiver? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setupChannel(flutterEngine: FlutterEngine, channelName: String) {
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    registerDeviceReceiver()
                    // Send initial state so Flutter has device info immediately
                    sendDeviceState()
                }

                override fun onCancel(arguments: Any?) {
                    unregisterDeviceReceiver()
                    eventSink = null
                }
            })
    }

    private fun registerDeviceReceiver() {
        deviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_HEADSET_PLUG,
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        // FIX 1: A short delay lets the system fully update its device list
                        // before we query it — without this, the device may still appear
                        // connected immediately after an unplug event.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            sendDeviceState()
                        }, 300)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        // FIX 2: Android 14+ (API 34) requires an explicit exported/not-exported flag
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(deviceBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(deviceBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            println("Error registering device receiver: ${e.message}")
        }
    }

    private fun unregisterDeviceReceiver() {
        deviceBroadcastReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                println("Error unregistering device receiver: ${e.message}")
            }
            deviceBroadcastReceiver = null
        }
    }

    private fun sendDeviceState() {
        // FIX 3: Guard with API check; below M the getDevices API doesn't exist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val deviceMap = mutableMapOf<String, Any>()
            val devices   = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var hasSpeaker     = false
            var hasWired       = false
            var hasBluetooth   = false
            var bluetoothName  = "Bluetooth Device"

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> hasWired = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        hasBluetooth  = true
                        bluetoothName = d.productName?.toString()?.takeIf { it.isNotBlank() }
                            ?: "Bluetooth Device"
                    }
                }
            }

            deviceMap["speaker"]       = hasSpeaker
            deviceMap["wired"]         = hasWired
            deviceMap["bluetooth"]     = hasBluetooth
            deviceMap["bluetoothName"] = bluetoothName

            // FIX 4: EventSink.success must be called on the main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    eventSink?.success(deviceMap)
                } catch (e: Exception) {
                    println("Error sending device state: ${e.message}")
                }
            }
        }
    }
}