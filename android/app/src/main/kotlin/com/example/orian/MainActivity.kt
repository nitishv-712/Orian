package com.example.orian

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL  = "com.example.orian/audio_capture"
        const val DEVICE_CHANNEL  = "com.example.orian/device_monitor"
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var pendingResult: MethodChannel.Result? = null
    private lateinit var deviceMonitor: DeviceMonitor

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Wire up the device-monitor EventChannel
        deviceMonitor = DeviceMonitor(this)
        deviceMonitor.setupChannel(flutterEngine, DEVICE_CHANNEL)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "requestCapturePermission" -> {
                        if (pendingResult != null) {
                            result.error("ALREADY_PENDING", "Permission request already in progress", null)
                            return@setMethodCallHandler
                        }
                        try {
                            pendingResult = result
                            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                        } catch (e: Exception) {
                            pendingResult = null
                            result.error("PERMISSION_ERROR", e.message, null)
                        }
                    }

                    "startCapture" -> {
                        if (AudioCaptureService.projectionResultData == null) {
                            result.error("NO_PERMISSION", "Call requestCapturePermission first", null)
                            return@setMethodCallHandler
                        }
                        val intent = Intent(this, AudioCaptureService::class.java).apply {
                            action = AudioCaptureService.ACTION_START
                            putExtra(
                                AudioCaptureService.EXTRA_USE_BLUETOOTH,
                                call.argument<Boolean>("bluetooth") ?: false
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    }

                    "stopCapture" -> {
                        startService(Intent(this, AudioCaptureService::class.java).apply {
                            action = AudioCaptureService.ACTION_STOP
                        })
                        result.success(true)
                    }

                    "updateOutputs" -> {
                        startService(Intent(this, AudioCaptureService::class.java).apply {
                            action = AudioCaptureService.ACTION_UPDATE_OUTPUTS
                            putExtra(
                                AudioCaptureService.EXTRA_USE_BLUETOOTH,
                                call.argument<Boolean>("bluetooth") ?: false
                            )
                        })
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                AudioCaptureService.projectionResultCode = resultCode
                AudioCaptureService.projectionResultData = data
                pendingResult?.success(true)
            } else {
                AudioCaptureService.projectionResultCode = 0
                AudioCaptureService.projectionResultData = null
                pendingResult?.success(false)
            }
            pendingResult = null
        }
    }
}
