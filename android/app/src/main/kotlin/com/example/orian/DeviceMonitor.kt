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
                    // Send initial state
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
                    AudioManager.ACTION_HEADSET_PLUG -> {
                        sendDeviceState()
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        sendDeviceState()
                    }
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        sendDeviceState()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        
        try {
            context.registerReceiver(deviceBroadcastReceiver, filter)
        } catch (e: Exception) {
            println("Error registering device receiver: ${e.message}")
        }
    }

    private fun unregisterDeviceReceiver() {
        if (deviceBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(deviceBroadcastReceiver)
            } catch (e: Exception) {
                println("Error unregistering device receiver: ${e.message}")
            }
        }
    }

    private fun sendDeviceState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val deviceMap = mutableMapOf<String, Any>()
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            var hasSpeaker = false
            var hasWired = false
            var hasBluetooth = false
            var bluetoothName = "Bluetooth Device"

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> hasWired = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        hasBluetooth = true
                        bluetoothName = (d.productName ?: "Bluetooth Device").toString()
                    }
                }
            }

            deviceMap["speaker"] = hasSpeaker
            deviceMap["wired"] = hasWired
            deviceMap["bluetooth"] = hasBluetooth
            deviceMap["bluetoothName"] = bluetoothName

            try {
                eventSink?.success(deviceMap)
            } catch (e: Exception) {
                println("Error sending device state: ${e.message}")
            }
        }
    }
}

// Need to import:
// import android.bluetooth.BluetoothAdapter
