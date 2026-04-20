import 'package:flutter/material.dart';
import 'services/audio_capture_service.dart';
import 'models/audio_device.dart';
import 'widgets/output_chip.dart';
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
    home: const AudioRouterPage(),
  );
}

class AudioRouterPage extends StatefulWidget {
  const AudioRouterPage({super.key});
  @override
  State<AudioRouterPage> createState() => _AudioRouterPageState();
}

class _AudioRouterPageState extends State<AudioRouterPage> {
  bool _isCapturing = false;
  bool _speaker = true;
  bool _bluetooth = false;
  bool _wired = false;
  String? _error;
  List<AudioDevice> _availableDevices = [];

  @override
  void initState() {
    super.initState();
    _setupDeviceMonitoring();
  }

  void _setupDeviceMonitoring() {
    // Listen for device changes from native side
    AudioCaptureService.onDevicesChanged.listen(
      (devices) {
        if (mounted) {
          setState(() {
            _availableDevices = devices;
            _error = null;
          });
        }
      },
      onError: (error) {
        if (mounted) {
          setState(() => _error = 'Device monitoring error: $error');
        }
      },
    );
  }

  Future<void> _requestAndStart() async {
    setState(() => _error = null);

    // 1. App-level permissions (notification, bluetooth, battery opt)
    final granted = await AppPermissions.requestAll(context);
    if (!mounted || !granted) {
      _showError('Required permissions denied.');
      return;
    }

    // 2. RECORD_AUDIO permission
    final hasAudio = await AppPermissions.requestRecordAudio(context);
    if (!mounted || !hasAudio) {
      _showError('Record audio permission required.');
      return;
    }

    // 3. MediaProjection permission (shows system dialog)
    try {
      final ok = await AudioCaptureService.requestCapturePermission();
      if (!mounted || !ok) {
        _showError('Screen capture permission denied.');
        return;
      }

      // 4. Start the native capture service
      final success = await AudioCaptureService.startCapture(
        speaker: _speaker,
        bluetooth: _bluetooth,
        wired: _wired,
      );

      if (!mounted) return;

      if (success) {
        setState(() => _isCapturing = true);
      } else {
        _showError('Failed to start audio capture.');
      }
    } catch (e) {
      if (mounted) _showError('Error: $e');
    }
  }

  Future<void> _stopCapture() async {
    try {
      final success = await AudioCaptureService.stopCapture();
      if (mounted && success) {
        setState(() => _isCapturing = false);
      }
    } catch (e) {
      if (mounted) _showError('Error stopping capture: $e');
    }
  }

  Future<void> _updateOutputs() async {
    if (!_isCapturing) return;
    try {
      await AudioCaptureService.updateOutputs(
        speaker: _speaker,
        bluetooth: _bluetooth,
        wired: _wired,
      );
    } catch (e) {
      if (mounted) _showError('Error updating outputs: $e');
    }
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
        elevation: 0,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Error message
            if (_error != null)
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.redAccent.withAlpha(50),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.redAccent),
                ),
                child: Row(
                  children: [
                    const Icon(
                      Icons.error_outline,
                      color: Colors.redAccent,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _error!,
                        style: const TextStyle(
                          color: Colors.redAccent,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            if (_error != null) const SizedBox(height: 16),

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
                  Icon(
                    Icons.info_outline,
                    color: Colors.deepPurpleAccent.withAlpha(200),
                  ),
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

            // Connected Devices
            if (_availableDevices.isNotEmpty) ...[
              const Text(
                'Available Devices',
                style: TextStyle(
                  color: Colors.grey,
                  fontSize: 12,
                  letterSpacing: 1,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: _availableDevices
                    .map(
                      (device) => Chip(
                        label: Text(device.name),
                        avatar: Icon(_getDeviceIcon(device.type), size: 18),
                        backgroundColor: Colors.deepPurpleAccent.withAlpha(50),
                        labelStyle: const TextStyle(fontSize: 12),
                      ),
                    )
                    .toList(),
              ),
              const SizedBox(height: 24),
            ],

            // Output selector
            const Text(
              'Select Output Routes',
              style: TextStyle(
                color: Colors.grey,
                fontSize: 12,
                letterSpacing: 1,
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: OutputChip(
                    icon: Icons.speaker,
                    label: 'Speaker',
                    selected: _speaker,
                    available: true,
                    onChanged: (v) => _toggleOutput('speaker', v),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutputChip(
                    icon: Icons.bluetooth_audio,
                    label: 'Bluetooth',
                    selected: _bluetooth,
                    available: _availableDevices.any(
                      (d) => d.type == AudioDeviceType.bluetooth,
                    ),
                    onChanged: (v) => _toggleOutput('bluetooth', v),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutputChip(
                    icon: Icons.headphones,
                    label: 'Wired',
                    selected: _wired,
                    available: _availableDevices.any(
                      (d) => d.type == AudioDeviceType.wired,
                    ),
                    onChanged: (v) => _toggleOutput('wired', v),
                  ),
                ),
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
                    _isCapturing ? 'Routing system audio...' : 'Not capturing',
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
                    borderRadius: BorderRadius.circular(14),
                  ),
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

  IconData _getDeviceIcon(AudioDeviceType type) {
    switch (type) {
      case AudioDeviceType.speaker:
        return Icons.speaker;
      case AudioDeviceType.bluetooth:
        return Icons.bluetooth_audio;
      case AudioDeviceType.wired:
        return Icons.headphones;
    }
  }
}
