import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:just_audio/just_audio.dart';
import 'package:audio_session/audio_session.dart';

@pragma('vm:entry-point')
void startCallback() {
  FlutterForegroundTask.setTaskHandler(AudioTaskHandler());
}

class AudioTaskHandler extends TaskHandler {
  final AudioPlayer _player = AudioPlayer();

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
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
  }

  @override
  void onReceiveData(Object data) async {
    if (data is Map) {
      final cmd = data['cmd'];
      final url = data['url'] as String?;
      switch (cmd) {
        case 'play':
          if (url != null) await _player.setUrl(url);
          await _player.play();
        case 'pause':
          await _player.pause();
        case 'stop':
          await _player.stop();
        case 'volume':
          await _player.setVolume((data['value'] as num).toDouble());
      }
    }
  }

  @override
  Future<void> onDestroy(DateTime timestamp) async {
    await _player.dispose();
  }

  @override
  void onRepeatEvent(DateTime timestamp) {}
}
