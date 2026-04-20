enum AudioDeviceType { speaker, bluetooth, wired }

class AudioDevice {
  final AudioDeviceType type;
  final String name;
  final bool isConnected;

  AudioDevice({
    required this.type,
    required this.name,
    required this.isConnected,
  });

  @override
  String toString() =>
      'AudioDevice(type: $type, name: $name, connected: $isConnected)';
}

class AudioRouterState {
  final bool isCapturing;
  final bool speaker;
  final bool bluetooth;
  final bool wired;
  final List<AudioDevice> availableDevices;
  final String? error;

  AudioRouterState({
    required this.isCapturing,
    this.speaker = true,
    this.bluetooth = false,
    this.wired = false,
    this.availableDevices = const [],
    this.error,
  });

  AudioRouterState copyWith({
    bool? isCapturing,
    bool? speaker,
    bool? bluetooth,
    bool? wired,
    List<AudioDevice>? availableDevices,
    String? error,
  }) {
    return AudioRouterState(
      isCapturing: isCapturing ?? this.isCapturing,
      speaker: speaker ?? this.speaker,
      bluetooth: bluetooth ?? this.bluetooth,
      wired: wired ?? this.wired,
      availableDevices: availableDevices ?? this.availableDevices,
      error: error ?? this.error,
    );
  }
}
