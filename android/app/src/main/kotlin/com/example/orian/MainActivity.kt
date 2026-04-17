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
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestCapturePermission" -> {
                        pendingResult = result
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                    }
                    "startCapture" -> {
                        val intent = Intent(this, AudioCaptureService::class.java).apply {
                            action = AudioCaptureService.ACTION_START
                            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, call.argument<Int>("resultCode"))
                            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, call.argument<ByteArray>("resultData")?.let {
                                Intent.parseUri(String(it), 0)
                            })
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
                            putExtra(AudioCaptureService.EXTRA_USE_BLUETOOTH, call.argument<Boolean>("bluetooth") ?: false)
                            putExtra(AudioCaptureService.EXTRA_USE_WIRED, call.argument<Boolean>("wired") ?: false)
                            putExtra(AudioCaptureService.EXTRA_USE_SPEAKER, call.argument<Boolean>("speaker") ?: true)
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
                // Store projection data in service companion and return to Flutter
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
