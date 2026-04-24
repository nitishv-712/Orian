enum AudioDeviceType { speaker, bluetooth, wired }

class AudioDevice {
  final AudioDeviceType type;
  final String name;
  final bool isConnected;

  const AudioDevice({
    required this.type,
    required this.name,
    required this.isConnected,
  });

  @override
  String toString() =>
      'AudioDevice(type: $type, name: $name, connected: $isConnected)';
}
