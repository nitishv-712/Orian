import 'package:flutter/services.dart';
import '../models/audio_device.dart';

class AudioCaptureService {
  static const _channel = MethodChannel('com.example.orian/audio_capture');
  static const _deviceChannel = EventChannel(
    'com.example.orian/device_monitor',
  );

  static bool _isCapturing = false;
  static List<AudioDevice> _availableDevices = [];

  // Stream for device changes
  static Stream<List<AudioDevice>> get onDevicesChanged => _deviceChannel
      .receiveBroadcastStream('device_changes')
      .map<List<AudioDevice>>((event) {
        if (event is Map) {
          _availableDevices = _parseDevices(event);
          return _availableDevices;
        }
        return [];
      })
      .handleError((error, stackTrace) {
        print('Device monitor error: $error');
        return [];
      });

  static List<AudioDevice> _parseDevices(Map event) {
    final devices = <AudioDevice>[];

    if (event['speaker'] == true) {
      devices.add(
        AudioDevice(
          type: AudioDeviceType.speaker,
          name: 'Speaker',
          isConnected: true,
        ),
      );
    }

    if (event['wired'] == true) {
      devices.add(
        AudioDevice(
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
          name: event['bluetoothName'] as String? ?? 'Bluetooth Device',
          isConnected: true,
        ),
      );
    }

    return devices;
  }

  static Future<bool> requestCapturePermission() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'requestCapturePermission',
      );
      return result ?? false;
    } catch (e) {
      print('Error requesting capture permission: $e');
      return false;
    }
  }

  static Future<bool> startCapture({
    required bool speaker,
    required bool bluetooth,
    required bool wired,
  }) async {
    try {
      await _channel.invokeMethod('startCapture', {
        'speaker': speaker,
        'bluetooth': bluetooth,
        'wired': wired,
      });
      _isCapturing = true;
      return true;
    } catch (e) {
      print('Error starting capture: $e');
      return false;
    }
  }

  static Future<bool> stopCapture() async {
    try {
      await _channel.invokeMethod('stopCapture');
      _isCapturing = false;
      return true;
    } catch (e) {
      print('Error stopping capture: $e');
      return false;
    }
  }

  static Future<bool> updateOutputs({
    required bool speaker,
    required bool bluetooth,
    required bool wired,
  }) async {
    if (!_isCapturing) return false;

    try {
      await _channel.invokeMethod('updateOutputs', {
        'speaker': speaker,
        'bluetooth': bluetooth,
        'wired': wired,
      });
      return true;
    } catch (e) {
      print('Error updating outputs: $e');
      return false;
    }
  }

  static bool get isCapturing => _isCapturing;

  static List<AudioDevice> get availableDevices => _availableDevices;
}
