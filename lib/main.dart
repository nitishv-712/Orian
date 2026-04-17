import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'app_permissions.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: ThemeData.dark(useMaterial3: true),
    home: const PlayerPage(),
  );
}

class PlayerPage extends StatefulWidget {
  const PlayerPage({super.key});
  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  static const _channel = MethodChannel('com.example.orian/audio_capture');

  bool _isCapturing = false;
  bool _speaker = true;
  bool _bluetooth = false;
  bool _wired = false;

  Future<void> _requestAndStart() async {
    // 1. App-level permissions (notification, bluetooth, battery opt)
    final granted = await AppPermissions.requestAll(context);
    if (!mounted || !granted) return;

    // 2. RECORD_AUDIO permission
    final hasAudio = await AppPermissions.requestRecordAudio(context);
    if (!mounted || !hasAudio) return;

    // 3. MediaProjection permission (shows system dialog)
    final bool ok = await _channel.invokeMethod('requestCapturePermission');
    if (!mounted || !ok) {
      _showError('Screen capture permission denied.');
      return;
    }

    // 4. Start the native capture service
    await _channel.invokeMethod('startCapture', {
      'speaker': _speaker,
      'bluetooth': _bluetooth,
      'wired': _wired,
    });

    if (mounted) setState(() => _isCapturing = true);
  }

  Future<void> _stopCapture() async {
    await _channel.invokeMethod('stopCapture');
    if (mounted) setState(() => _isCapturing = false);
  }

  Future<void> _updateOutputs() async {
    if (!_isCapturing) return;
    await _channel.invokeMethod('updateOutputs', {
      'speaker': _speaker,
      'bluetooth': _bluetooth,
      'wired': _wired,
    });
  }

  void _toggleOutput(String key, bool value) {
    setState(() {
      if (key == 'speaker') _speaker = value;
      if (key == 'bluetooth') _bluetooth = value;
      if (key == 'wired') _wired = value;
    });
    _updateOutputs();
  }

  void _showError(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), backgroundColor: Colors.redAccent),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A0A0A),
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text('Orian — System Audio Router'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Info card
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF1A1A1A),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: Colors.white10),
              ),
              child: Row(
                children: [
                  Icon(Icons.info_outline,
                      color: Colors.deepPurpleAccent.withAlpha(200)),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text(
                      'Play anything — VLC, YouTube, Spotify.\n'
                      'This app captures system audio and routes it\n'
                      'to your selected outputs simultaneously.',
                      style: TextStyle(color: Colors.grey, fontSize: 13),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 32),

            // Output selector
            const Text('Select Output Routes',
                style: TextStyle(color: Colors.grey, fontSize: 12,
                    letterSpacing: 1)),
            const SizedBox(height: 12),
            Row(
              children: [
                _outputChip(Icons.speaker, 'Speaker', _speaker,
                    (v) => _toggleOutput('speaker', v)),
                const SizedBox(width: 10),
                _outputChip(Icons.bluetooth_audio, 'Bluetooth', _bluetooth,
                    (v) => _toggleOutput('bluetooth', v)),
                const SizedBox(width: 10),
                _outputChip(Icons.headphones, 'Wired', _wired,
                    (v) => _toggleOutput('wired', v)),
              ],
            ),

            const Spacer(),

            // Status
            Center(
              child: Column(
                children: [
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 300),
                    width: 80,
                    height: 80,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _isCapturing
                          ? Colors.deepPurpleAccent
                          : const Color(0xFF1A1A1A),
                      border: Border.all(
                        color: _isCapturing
                            ? Colors.deepPurpleAccent
                            : Colors.white12,
                        width: 2,
                      ),
                    ),
                    child: Icon(
                      _isCapturing
                          ? Icons.graphic_eq
                          : Icons.play_circle_outline,
                      size: 36,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    _isCapturing
                        ? 'Routing system audio...'
                        : 'Not capturing',
                    style: TextStyle(
                      color: _isCapturing
                          ? Colors.deepPurpleAccent
                          : Colors.grey,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
            ),

            const Spacer(),

            // Start / Stop button
            SizedBox(
              height: 54,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isCapturing
                      ? Colors.redAccent
                      : Colors.deepPurpleAccent,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: _isCapturing ? _stopCapture : _requestAndStart,
                icon: Icon(_isCapturing ? Icons.stop : Icons.sensors),
                label: Text(
                  _isCapturing ? 'Stop Routing' : 'Start Routing',
                  style: const TextStyle(fontSize: 16),
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }

  Widget _outputChip(
    IconData icon,
    String label,
    bool selected,
    ValueChanged<bool> onChanged,
  ) =>
      Expanded(
        child: GestureDetector(
          onTap: () => onChanged(!selected),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            padding: const EdgeInsets.symmetric(vertical: 14),
            decoration: BoxDecoration(
              color: selected
                  ? Colors.deepPurpleAccent
                  : const Color(0xFF1A1A1A),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: selected
                    ? Colors.deepPurpleAccent
                    : Colors.white12,
              ),
            ),
            child: Column(
              children: [
                Icon(icon,
                    color: selected ? Colors.white : Colors.grey, size: 24),
                const SizedBox(height: 6),
                Text(label,
                    style: TextStyle(
                      color: selected ? Colors.white : Colors.grey,
                      fontSize: 12,
                    )),
              ],
            ),
          ),
        ),
      );
}
