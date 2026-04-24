import 'dart:async';
import 'package:flutter/material.dart';
import 'package:orian/models/audio_device.dart';
import 'package:orian/services/audio_capture_service.dart';
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
  bool _isCapturing = false;
  bool _bluetooth = false;

  // Device state derived from the EventChannel stream
  bool _btConnected = false;
  String _btName = 'Bluetooth Device';

  StreamSubscription<List<AudioDevice>>? _deviceSub;

  @override
  void initState() {
    super.initState();
    _deviceSub = AudioCaptureService.onDevicesChanged.listen(_onDevicesChanged);
  }

  @override
  void dispose() {
    _deviceSub?.cancel();
    super.dispose();
  }

  void _onDevicesChanged(List<AudioDevice> devices) {
    final btDevice = devices
        .where((d) => d.type == AudioDeviceType.bluetooth)
        .firstOrNull;
    final connected = btDevice != null;

    setState(() {
      _btConnected = connected;
      _btName = btDevice?.name ?? 'Bluetooth Device';

      // Auto-disable BT toggle if device disconnects mid-session
      if (!connected && _bluetooth) {
        _bluetooth = false;
        if (_isCapturing) {
          AudioCaptureService.updateOutputs(bluetooth: false);
        }
      }
    });
  }

  Future<void> _requestAndStart() async {
    final granted = await AppPermissions.requestAll(context);
    if (!mounted || !granted) return;

    final hasAudio = await AppPermissions.requestRecordAudio(context);
    if (!mounted || !hasAudio) return;

    final ok = await AudioCaptureService.requestCapturePermission();
    if (!mounted) return;
    if (!ok) {
      _showSnack('Screen capture permission denied.', Colors.redAccent);
      return;
    }

    final started = await AudioCaptureService.startCapture(
      bluetooth: _bluetooth,
    );
    if (mounted && started) setState(() => _isCapturing = true);
  }

  Future<void> _stopCapture() async {
    await AudioCaptureService.stopCapture();
    if (mounted) setState(() => _isCapturing = false);
  }

  Future<void> _toggleBluetooth(bool value) async {
    setState(() => _bluetooth = value);
    if (_isCapturing) {
      await AudioCaptureService.updateOutputs(bluetooth: value);
    }
  }

  void _showSnack(String msg, Color color) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(msg), backgroundColor: color));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A0A0A),
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text('Orian — Audio Router'),
      ),
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _infoCard(),
            const SizedBox(height: 32),

            // Primary output — always automatic
            const Text(
              'PRIMARY OUTPUT  (auto)',
              style: TextStyle(
                color: Colors.grey,
                fontSize: 11,
                letterSpacing: 1,
              ),
            ),
            const SizedBox(height: 8),
            _staticChip(
              Icons.headphones,
              'Wired Headset  /  Speaker',
              'Android routes automatically based on what\'s plugged in',
            ),

            const SizedBox(height: 24),

            // Secondary output — Bluetooth toggle
            const Text(
              'SECONDARY OUTPUT',
              style: TextStyle(
                color: Colors.grey,
                fontSize: 11,
                letterSpacing: 1,
              ),
            ),
            const SizedBox(height: 8),
            _toggleChip(
              Icons.bluetooth_audio,
              _btConnected ? _btName : 'Bluetooth',
              _btConnected
                  ? 'Simultaneously stream to connected BT device'
                  : 'No Bluetooth device connected',
              _bluetooth && _btConnected,
              // Disable the callback (greyed out) when no BT device is present
              _btConnected ? _toggleBluetooth : null,
            ),

            const Spacer(),

            // Status indicator
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
                      _isCapturing ? Icons.graphic_eq : Icons.sensors,
                      size: 34,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    _isCapturing
                        ? (_bluetooth && _btConnected)
                              ? 'Routing to headset + Bluetooth'
                              : 'Routing to headset / speaker'
                        : 'Not capturing',
                    style: TextStyle(
                      color: _isCapturing
                          ? Colors.deepPurpleAccent
                          : Colors.grey,
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),

            const Spacer(),

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

  Widget _infoCard() => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFF1A1A1A),
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: Colors.white10),
    ),
    child: Row(
      children: [
        Icon(Icons.info_outline, color: Colors.deepPurpleAccent.withAlpha(200)),
        const SizedBox(width: 12),
        const Expanded(
          child: Text(
            'Play anything — VLC, YouTube, Spotify.\n'
            'This app captures system audio and mirrors it\n'
            'to your Bluetooth device simultaneously.',
            style: TextStyle(color: Colors.grey, fontSize: 12),
          ),
        ),
      ],
    ),
  );

  Widget _staticChip(IconData icon, String label, String subtitle) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    decoration: BoxDecoration(
      color: const Color(0xFF1A1A1A),
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: Colors.white12),
    ),
    child: Row(
      children: [
        Icon(icon, color: Colors.white54, size: 22),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: const TextStyle(color: Colors.white, fontSize: 14),
              ),
              const SizedBox(height: 2),
              Text(
                subtitle,
                style: const TextStyle(color: Colors.grey, fontSize: 11),
              ),
            ],
          ),
        ),
        const Icon(Icons.check_circle, color: Colors.greenAccent, size: 18),
      ],
    ),
  );

  Widget _toggleChip(
    IconData icon,
    String label,
    String subtitle,
    bool selected,
    ValueChanged<bool>? onChanged, // nullable — null = disabled state
  ) => GestureDetector(
    onTap: onChanged != null ? () => onChanged(!selected) : null,
    child: AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: selected
            ? Colors.deepPurpleAccent.withAlpha(40)
            : const Color(0xFF1A1A1A),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: selected ? Colors.deepPurpleAccent : Colors.white12,
        ),
      ),
      child: Row(
        children: [
          Icon(
            icon,
            color: selected
                ? Colors.deepPurpleAccent
                : (onChanged == null ? Colors.white24 : Colors.white54),
            size: 22,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    color: onChanged == null
                        ? Colors.white30
                        : (selected ? Colors.white : Colors.white70),
                    fontSize: 14,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: const TextStyle(color: Colors.grey, fontSize: 11),
                ),
              ],
            ),
          ),
          Switch(
            value: selected,
            onChanged: onChanged,
            activeColor: Colors.deepPurpleAccent,
          ),
        ],
      ),
    ),
  );
}
