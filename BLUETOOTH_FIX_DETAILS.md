# Bluetooth Audio Routing - Fix Details

## Problem Identified
When selecting both Speaker and Bluetooth, only the Speaker audio was being heard.

## Root Causes Fixed

### 1. **Incorrect Bluetooth Profile Usage**
   - **Old**: Used `startBluetoothSco()` which is for voice calls (SCO profile)
   - **New**: Removed SCO handling; using A2DP profile for audio streaming
   - **Why**: SCO is narrow-band voice audio, not suitable for music/system audio

### 2. **Audio Track Writing Logic**
   - **Old**: Checked `playState` before writing, which could skip writes
   - **New**: Write to all selected tracks regardless of state, letting AudioTrack handle buffering
   - **Why**: Audio needs continuous writing regardless of internal playback state

### 3. **Device Routing**
   - **Old**: Set `preferredDevice` without verifying the tracks were actually playing
   - **New**: 
     - Explicitly call `play()` on tracks when enabled
     - Verify Bluetooth device exists before playing to it
     - Re-route when device topology changes
   - **Why**: `preferredDevice` is just a hint; we must explicitly control playback

### 4. **Speaker Management**
   - **Old**: Didn't manage `setSpeakerphoneOn()`
   - **New**: Explicitly manage speaker based on what's selected
   - **Why**: Ensures speaker stays active even when Bluetooth/wired are enabled

### 5. **Better Logging**
   - Added detailed logging of:
     - Track creation
     - Device routing decisions
     - Write errors
     - Device availability
   - **Why**: Helps debug why audio isn't routing properly

## Changes Made

### AudioCaptureService.kt
```kotlin
// Key improvements:
1. Write audio to ALL enabled tracks (not just if playing)
2. Call explicit play() on tracks when they're selected
3. Verify Bluetooth device exists before routing
4. Removed SCO handling - use A2DP for audio
5. Better device management with logging
6. Re-apply routing when devices change
```

## How It Works Now

1. **Capture**: System audio is captured via MediaProjection
2. **Multiple Tracks**: Three AudioTracks created (speaker, wired, Bluetooth)
3. **Routing**: Each track pinned to its physical device via `preferredDevice`
4. **Writing**: Audio written to all enabled tracks simultaneously
5. **Playback**: Tracks play to their respective devices

## To Test

1. **Connect Bluetooth device** and pair it
2. **Start the app** and grant permissions
3. **Select**: Speaker ✓ and Bluetooth ✓
4. **Play audio** from any app
5. **Expected**: Hear audio from BOTH speaker AND Bluetooth simultaneously

## Debugging (Check Logcat)

Watch for these logs:
```
▶ Speaker track playing
▶ Bluetooth track playing (A2DP audio)
Audio write cycle 100 - Speaker: true, Wired: false, BT: true
```

If you see:
- ⏸ Bluetooth track paused → Bluetooth not detected as available
- ⚠ Bluetooth requested but no device available → Device not properly connected
- Error: Check Logcat for specific error messages
