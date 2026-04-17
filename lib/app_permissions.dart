import 'dart:io';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

class AppPermissions {
  /// Requests all required permissions. Returns true only if all critical
  /// permissions are granted.
  static Future<bool> requestAll(BuildContext context) async {
    // 1. Notification permission (Android 13+ / iOS)
    final notification = await Permission.notification.request();

    // 2. Bluetooth connect (Android 12+)
    if (Platform.isAndroid) {
      await Permission.bluetoothConnect.request();
    }

    // 3. Foreground service / battery optimization
    // Ask user to ignore battery optimization so background service isn't killed
    final ignoreBattery = await FlutterForegroundTask.isIgnoringBatteryOptimizations;
    if (!ignoreBattery) {
      await FlutterForegroundTask.requestIgnoreBatteryOptimization();
    }

    // 4. Notification permission for foreground task (Android)
    if (Platform.isAndroid) {
      final notifForService = await FlutterForegroundTask.checkNotificationPermission();
      if (notifForService != NotificationPermission.granted) {
        await FlutterForegroundTask.requestNotificationPermission();
      }
    }

    // Critical: notification must be granted for foreground service to show
    if (notification.isPermanentlyDenied && context.mounted) {
      _showSettingsDialog(context);
      return false;
    }

    return notification.isGranted || notification.isLimited;
  }

  static void _showSettingsDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Permission Required'),
        content: const Text(
          'Notification permission is permanently denied.\n'
          'Please enable it in Settings to run audio in background.',
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
