import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'audio_task.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  _initForegroundTask();
  runApp(const MyApp());
}

void _initForegroundTask() {
  FlutterForegroundTask.init(
    androidNotificationOptions: AndroidNotificationOptions(
      channelId: 'audio_channel',
      channelName: 'Audio Playback',
      channelDescription: 'Playing audio in background',
      onlyAlertOnce: true,
    ),
    iosNotificationOptions: const IOSNotificationOptions(
      showNotification: true,
      playSound: false,
    ),
    foregroundTaskOptions: ForegroundTaskOptions(
      eventAction: ForegroundTaskEventAction.repeat(5000),
      autoRunOnBoot: false,
    ),
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: ThemeData.dark(),
    home: const AudioControlPage(),
  );
}

class AudioControlPage extends StatefulWidget {
  const AudioControlPage({super.key});
  @override
  State<AudioControlPage> createState() => _AudioControlPageState();
}

class _AudioControlPageState extends State<AudioControlPage> {
  bool _isRunning = false;
  double _volume = 1.0;
  final _urlController = TextEditingController(
    text: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
  );

  Future<void> _startService() async {
    final result = await FlutterForegroundTask.startService(
      serviceId: 1,
      notificationTitle: 'Orian Audio',
      notificationText: 'Playing audio...',
      callback: startCallback,
    );
    if (result is ServiceRequestSuccess) setState(() => _isRunning = true);
  }

  Future<void> _stopService() async {
    await FlutterForegroundTask.stopService();
    setState(() => _isRunning = false);
  }

  void _send(Map<String, dynamic> data) =>
      FlutterForegroundTask.sendDataToTask(data);

  @override
  Widget build(BuildContext context) {
    return WithForegroundTask(
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              TextField(
                controller: _urlController,
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  labelText: 'Audio URL',
                  labelStyle: TextStyle(color: Colors.grey),
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 24),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  if (!_isRunning)
                    _btn(
                      Icons.power_settings_new,
                      'Start',
                      Colors.green,
                      _startService,
                    )
                  else ...[
                    _btn(Icons.play_arrow, 'Play', Colors.blue, () {
                      _send({'cmd': 'play', 'url': _urlController.text});
                    }),
                    const SizedBox(width: 16),
                    _btn(
                      Icons.pause,
                      'Pause',
                      Colors.orange,
                      () => _send({'cmd': 'pause'}),
                    ),
                    const SizedBox(width: 16),
                    _btn(
                      Icons.stop,
                      'Stop',
                      Colors.red,
                      () => _send({'cmd': 'stop'}),
                    ),
                    const SizedBox(width: 16),
                    _btn(Icons.power_off, 'Kill', Colors.grey, _stopService),
                  ],
                ],
              ),
              const SizedBox(height: 24),
              if (_isRunning) ...[
                const Text('Volume', style: TextStyle(color: Colors.white)),
                Slider(
                  value: _volume,
                  onChanged: (v) {
                    setState(() => _volume = v);
                    _send({'cmd': 'volume', 'value': v});
                  },
                ),
              ],
              const SizedBox(height: 12),
              Text(
                _isRunning ? '● Running in background' : '○ Service stopped',
                style: TextStyle(
                  color: _isRunning ? Colors.greenAccent : Colors.grey,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _btn(IconData icon, String label, Color color, VoidCallback onTap) =>
      ElevatedButton.icon(
        style: ElevatedButton.styleFrom(backgroundColor: color),
        onPressed: onTap,
        icon: Icon(icon),
        label: Text(label),
      );

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }
}
