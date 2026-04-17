import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:just_audio/just_audio.dart';
import 'package:audio_session/audio_session.dart';

@pragma('vm:entry-point')
void startCallback() {
  FlutterForegroundTask.setTaskHandler(AudioTaskHandler());
}

class AudioTaskHandler extends TaskHandler {
  final AudioPlayer _player = AudioPlayer();
  String? _currentUrl;

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    // Configure audio session for simultaneous Bluetooth + wired output
    final session = await AudioSession.instance;
    await session.configure(AudioSessionConfiguration(
      avAudioSessionCategory: AVAudioSessionCategory.playAndRecord,
      avAudioSessionCategoryOptions:
          AVAudioSessionCategoryOptions.defaultToSpeaker |
          AVAudioSessionCategoryOptions.allowBluetooth,
      androidAudioAttributes: const AndroidAudioAttributes(
        contentType: AndroidAudioContentType.music,
        usage: AndroidAudioUsage.media,
      ),
      androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
    ));

    // Handle audio interruptions (calls, other apps)
    session.interruptionEventStream.listen((event) async {
      if (event.begin) {
        await _player.pause();
      } else {
        if (event.type == AudioInterruptionType.pause ||
            event.type == AudioInterruptionType.unknown) {
          await _player.play();
        }
      }
    });

    // Handle audio becoming noisy (headphone unplug)
    session.becomingNoisyEventStream.listen((_) async {
      await _player.pause();
    });
  }

  @override
  void onReceiveData(Object data) async {
    if (data is! Map) return;
    final cmd = data['cmd'] as String?;
    final url = data['url'] as String?;

    switch (cmd) {
      case 'play':
        if (url != null && url != _currentUrl) {
          _currentUrl = url;
          await _player.setUrl(url);
        }
        if (_currentUrl != null) await _player.play();
      case 'pause':
        await _player.pause();
      case 'stop':
        await _player.stop();
        _currentUrl = null;
      case 'volume':
        final value = (data['value'] as num?)?.toDouble();
        if (value != null) await _player.setVolume(value.clamp(0.0, 1.0));
    }
  }

  @override
  Future<void> onDestroy(DateTime timestamp) async {
    await _player.stop();
    await _player.dispose();
  }

  @override
  void onRepeatEvent(DateTime timestamp) {}
}
