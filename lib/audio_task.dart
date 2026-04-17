import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:just_audio/just_audio.dart';
import 'package:audio_session/audio_session.dart';

@pragma('vm:entry-point')
void startCallback() {
  FlutterForegroundTask.setTaskHandler(AudioTaskHandler());
}

class AudioTaskHandler extends TaskHandler {
  // One player per output route
  final AudioPlayer _speakerPlayer = AudioPlayer();
  final AudioPlayer _btPlayer = AudioPlayer();
  final AudioPlayer _wiredPlayer = AudioPlayer();

  String? _currentUrl;
  bool _useSpeaker = true;
  bool _useBluetooth = false;
  bool _useWired = false;

  List<AudioPlayer> get _activePlayers => [
    if (_useSpeaker) _speakerPlayer,
    if (_useBluetooth) _btPlayer,
    if (_useWired) _wiredPlayer,
  ];

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

    session.interruptionEventStream.listen((event) async {
      if (event.begin) {
        await _pauseAll();
      } else if (event.type == AudioInterruptionType.pause ||
          event.type == AudioInterruptionType.unknown) {
        await _playAll();
      }
    });

    session.becomingNoisyEventStream.listen((_) => _pauseAll());
  }

  Future<void> _loadAll(String url) async {
    await Future.wait([
      _speakerPlayer.setUrl(url),
      _btPlayer.setUrl(url),
      _wiredPlayer.setUrl(url),
    ]);
  }

  Future<void> _playAll() async {
    await Future.wait(_activePlayers.map((p) => p.play()));
  }

  Future<void> _pauseAll() async {
    await Future.wait([
      _speakerPlayer.pause(),
      _btPlayer.pause(),
      _wiredPlayer.pause(),
    ]);
  }

  Future<void> _stopAll() async {
    await Future.wait([
      _speakerPlayer.stop(),
      _btPlayer.stop(),
      _wiredPlayer.stop(),
    ]);
  }

  @override
  void onReceiveData(Object data) async {
    if (data is! Map) return;
    final cmd = data['cmd'] as String?;

    switch (cmd) {
      case 'outputs':
        // Update which outputs are active; if playing, sync immediately
        _useSpeaker = data['speaker'] as bool? ?? _useSpeaker;
        _useBluetooth = data['bluetooth'] as bool? ?? _useBluetooth;
        _useWired = data['wired'] as bool? ?? _useWired;

        // Pause players that were deselected, play ones that were selected
        if (!_useSpeaker) await _speakerPlayer.pause();
        if (!_useBluetooth) await _btPlayer.pause();
        if (!_useWired) await _wiredPlayer.pause();

        // Sync position for newly activated players
        final pos = _speakerPlayer.position;
        if (_useBluetooth && !_btPlayer.playing) {
          await _btPlayer.seek(pos);
          if (_speakerPlayer.playing || _wiredPlayer.playing) {
            await _btPlayer.play();
          }
        }
        if (_useWired && !_wiredPlayer.playing) {
          await _wiredPlayer.seek(pos);
          if (_speakerPlayer.playing || _btPlayer.playing) {
            await _wiredPlayer.play();
          }
        }
        if (_useSpeaker && !_speakerPlayer.playing) {
          await _speakerPlayer.seek(pos);
          if (_btPlayer.playing || _wiredPlayer.playing) {
            await _speakerPlayer.play();
          }
        }

      case 'play':
        final url = data['url'] as String?;
        if (url != null && url != _currentUrl) {
          _currentUrl = url;
          await _loadAll(url);
        }
        if (_currentUrl != null) await _playAll();

      case 'pause':
        await _pauseAll();

      case 'stop':
        await _stopAll();
        _currentUrl = null;

      case 'volume':
        final value = (data['value'] as num?)?.toDouble().clamp(0.0, 1.0);
        if (value != null) {
          await Future.wait([
            _speakerPlayer.setVolume(value),
            _btPlayer.setVolume(value),
            _wiredPlayer.setVolume(value),
          ]);
        }
    }
  }

  @override
  Future<void> onDestroy(DateTime timestamp) async {
    await _stopAll();
    await Future.wait([
      _speakerPlayer.dispose(),
      _btPlayer.dispose(),
      _wiredPlayer.dispose(),
    ]);
  }

  @override
  void onRepeatEvent(DateTime timestamp) {}
}
