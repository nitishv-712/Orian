import 'package:flutter/services.dart';
import 'package:orian/models/audio_device.dart';

class AudioCaptureService {
  static const _channel = MethodChannel('com.example.orian/audio_capture');
  static const _deviceChannel = EventChannel(
    'com.example.orian/device_monitor',
  );

  static bool _isCapturing = false;

  /// Stream of available audio output devices. Emits a new list whenever
  /// a device is connected or disconnected.
  static Stream<List<AudioDevice>> get onDevicesChanged => _deviceChannel
      .receiveBroadcastStream()
      .map<List<AudioDevice>>((event) {
        if (event is Map) return _parseDevices(event);
        return const [];
      })
      .handleError((Object error) {
        // ignore errors — caller will retain last known state
      });

  static List<AudioDevice> _parseDevices(Map<dynamic, dynamic> event) {
    final devices = <AudioDevice>[];

    if (event['speaker'] == true) {
      devices.add(
        const AudioDevice(
          type: AudioDeviceType.speaker,
          name: 'Speaker',
          isConnected: true,
        ),
      );
    }

    if (event['wired'] == true) {
      devices.add(
        const AudioDevice(
          type: AudioDeviceType.wired,
          name: 'Wired Headphones',
          isConnected: true,
        ),
      );
    }

    if (event['bluetooth'] == true) {
      devices.add(
        AudioDevice(
          type: AudioDeviceType.bluetooth,
          name: (event['bluetoothName'] as String?)?.isNotEmpty == true
              ? event['bluetoothName'] as String
              : 'Bluetooth Device',
          isConnected: true,
        ),
      );
    }

    return devices;
  }

  static Future<bool> requestCapturePermission() async {
    try {
      return await _channel.invokeMethod<bool>('requestCapturePermission') ??
          false;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> startCapture({required bool bluetooth}) async {
    try {
      await _channel.invokeMethod<bool>('startCapture', {
        'bluetooth': bluetooth,
      });
      _isCapturing = true;
      return true;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> stopCapture() async {
    try {
      await _channel.invokeMethod<bool>('stopCapture');
      _isCapturing = false;
      return true;
    } catch (e) {
      return false;
    }
  }

  static Future<bool> updateOutputs({required bool bluetooth}) async {
    if (!_isCapturing) return false;
    try {
      await _channel.invokeMethod<bool>('updateOutputs', {
        'bluetooth': bluetooth,
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  static bool get isCapturing => _isCapturing;
}
