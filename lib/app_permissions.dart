import 'dart:io';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

class AppPermissions {
  /// Requests all required permissions. Returns true only if all critical
  /// permissions are granted.
  static Future<bool> requestAll(BuildContext context) async {
    // Notification permission (Android 13+ / iOS)
    final notification = await Permission.notification.request();

    // Bluetooth connect (Android 12+)
    if (Platform.isAndroid) {
      await Permission.bluetoothConnect.request();
    }

    if (notification.isPermanentlyDenied && context.mounted) {
      _showSettingsDialog(context);
      return false;
    }

    return notification.isGranted || notification.isLimited;
  }

  static Future<bool> requestRecordAudio(BuildContext context) async {
    final status = await Permission.microphone.request();
    if (status.isPermanentlyDenied && context.mounted) {
      _showSettingsDialog(context);
      return false;
    }
    return status.isGranted;
  }

  static void _showSettingsDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Permission Required'),
        content: const Text(
          'A required permission is permanently denied.\n'
          'Please enable it in Settings to continue.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }
}
