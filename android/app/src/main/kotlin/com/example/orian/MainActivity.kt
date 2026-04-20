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
        const val CHANNEL = "com.example.orian/audio_capture"
        const val DEVICE_CHANNEL = "com.example.orian/device_monitor"
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var pendingResult: MethodChannel.Result? = null
    private var deviceMonitor: DeviceMonitor? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Setup device monitoring
        deviceMonitor = DeviceMonitor(this)
        deviceMonitor?.setupChannel(flutterEngine, DEVICE_CHANNEL)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestCapturePermission" -> {
                        try {
                            pendingResult = result
                            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                        } catch (e: Exception) {
                            result.error("PERMISSION_ERROR", e.message, null)
                        }
                    }
                    "startCapture" -> {
                        try {
                            val intent = Intent(this, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_START
                                putExtra(AudioCaptureService.EXTRA_USE_BLUETOOTH, call.argument<Boolean>("bluetooth") ?: false)
                                putExtra(AudioCaptureService.EXTRA_USE_WIRED, call.argument<Boolean>("wired") ?: false)
                                putExtra(AudioCaptureService.EXTRA_USE_SPEAKER, call.argument<Boolean>("speaker") ?: true)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("START_ERROR", e.message, null)
                        }
                    }
                    "stopCapture" -> {
                        try {
                            startService(Intent(this, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_STOP
                            })
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("STOP_ERROR", e.message, null)
                        }
                    }
                    "updateOutputs" -> {
                        try {
                            startService(Intent(this, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_UPDATE_OUTPUTS
                                putExtra(AudioCaptureService.EXTRA_USE_BLUETOOTH, call.argument<Boolean>("bluetooth") ?: false)
                                putExtra(AudioCaptureService.EXTRA_USE_WIRED, call.argument<Boolean>("wired") ?: false)
                                putExtra(AudioCaptureService.EXTRA_USE_SPEAKER, call.argument<Boolean>("speaker") ?: true)
                            })
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("UPDATE_ERROR", e.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Store projection data in service companion
                AudioCaptureService.projectionResultCode = resultCode
                AudioCaptureService.projectionResultData = data
                pendingResult?.success(true)
            } else {
                pendingResult?.success(false)
            }
            pendingResult = null
        }
    }
}
