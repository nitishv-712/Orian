/// This file is deprecated and not currently used in the audio capture flow.
/// The audio capture is handled entirely by the native Android service
/// (AudioCaptureService.kt) which uses MediaProjection API.
///
/// This file may be useful in the future if we need to handle:
/// - Foreground task improvements
/// - Custom audio session handling
/// - Fallback audio mechanisms
///
/// For now, the audio routing is handled natively for better performance
/// and to avoid callback conflicts between Flutter and native services.

import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:audio_session/audio_session.dart';

@pragma('vm:entry-point')
void startCallback() {
  FlutterForegroundTask.setTaskHandler(AudioTaskHandler());
}

/// Placeholder task handler for future audio session management
class AudioTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    // Configure audio session for the app
    try {
      final session = await AudioSession.instance;
      await session.configure(
        AudioSessionConfiguration(
          avAudioSessionCategory: AVAudioSessionCategory.playAndRecord,
          avAudioSessionCategoryOptions:
              AVAudioSessionCategoryOptions.defaultToSpeaker |
              AVAudioSessionCategoryOptions.allowBluetooth |
              AVAudioSessionCategoryOptions.allowBluetoothA2dp,
          androidAudioAttributes: const AndroidAudioAttributes(
            contentType: AndroidAudioContentType.music,
            usage: AndroidAudioUsage.media,
          ),
          androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
        ),
      );
    } catch (e) {
      print('Error configuring audio session: $e');
    }
  }

  @override
  void onReceiveData(Object data) {
    // Not used - audio routing is handled natively
  }

  @override
  Future<void> onDestroy(DateTime timestamp) async {
    // Cleanup if needed
  }

  @override
  void onRepeatEvent(DateTime timestamp) {
    // Not used for this implementation
  }
}
