# L06 Volume Guard

A small, device-local project that lowers audio when the Bluetooth output named **L06** becomes active.

- **Android:** listens for A2DP connection broadcasts. On L06 connection, it checks that L06 is the active A2DP output and sets `STREAM_MUSIC` to 25% (two attempts, about 2.4 and 3.3 seconds after connect). It requires the Nearby devices permission and the user to enable its AccessibilityService in Android Settings. The service does not read screen contents or perform gestures. It does not keep changing volume after the short connection window.
- **macOS:** a LaunchAgent checks the default CoreAudio output once per second. When that output changes to `L06`, it sets system output volume to 20% once. Later manual changes are left alone until the next transition.

The public source matches by the displayed device name `L06`. It contains no personal Bluetooth address, phone serial, private signing key, installation logs, or device-specific APK. The Android app must be built and signed locally for installation. Public repository visibility does not grant rights to reuse this code; no license is provided.

## Android

Sources are under `android/`. Build with Android SDK Platform 34, Build Tools 34.0.0, and Java 17. The app can be built with the Android command-line tools (`javac`, `d8`, `aapt2`, `zipalign`, `apksigner`). Sign the generated APK with a key you control; never commit the key or password. Install with `adb install -r <signed-apk>`, grant Nearby devices, then enable **L06 Volume Guard** under Accessibility settings.

The app targets Android 13 or later. The device name must be available as `L06` in the Bluetooth profile and active audio output.

## macOS

Run `macos/install.sh` on Apple Silicon or Intel macOS with Xcode Command Line Tools installed. It compiles the Swift monitor, installs a per-user LaunchAgent, and starts it. The monitor needs no accessibility or Bluetooth permission; it reads the current CoreAudio output name and uses AppleScript to set system output volume.

Logs: `~/Library/Logs/L06VolumeGuard.log`

Stop/uninstall:

```sh
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/com.codex.l06-volume-guard.plist"
rm "$HOME/Library/LaunchAgents/com.codex.l06-volume-guard.plist"
rm -r "$HOME/Library/Application Support/L06VolumeGuard"
```

## Behavior limits

The Android implementation affects only that phone, and the macOS implementation affects only that Mac. Neither changes the headset's stored internal volume or controls Windows. On macOS, the system must select L06 as the default output for the trigger to run. Both implementations set volume once after detecting the connection/output transition; they do not continuously prevent a user from raising volume.
