# L06 Volume Guard

[中文](README.md) · [Build guide](android/BUILD.en.md) · [Development handoff](docs/HANDOFF.en.md)

**Core function: whenever the configured L06 Bluetooth headset becomes the phone's media output again, correct its volume to about 25%, then allow manual adjustment.** Android is currently **1.6 / versionCode 7**, requires Android 13 or later, and has been tested on a vivo phone running Android 16.

## After rebooting the phone

For first installation, follow the [build guide](android/BUILD.en.md): configure your headset, install and pair official Shizuku, grant the one-time settings permission, and allow both apps to run in the background.

1. Unlock the phone and enable **the phone's own Personal hotspot**. Another device does not need to join it.
2. Open L06 Volume Guard and tap “手机端启动准备” (Prepare on-device startup).
3. Wait for “打开 Shizuku” (Open Shizuku) to become enabled. Tap it, then press “Start” in official Shizuku.
4. Return to L06 and confirm “Shizuku：已就绪” (ready) and “后台音量守护：运行中” (guard running).
5. You can turn off the hotspot. Reconnecting L06 still corrects its volume to about 25%.

The hotspot is only used for this startup flow, not each Bluetooth reconnection. Unplugging USB does not require preparation while Shizuku remains running. First pairing, or recovery from expired pairing, still uses the official flow. This is manual recovery; it does not promise automatic Shizuku startup at boot.

The user confirmed a reboot with USB unplugged, startup using only mobile data and the phone's own hotspot, and successful reconnection at about 25% after switching the hotspot off. These are user-reported tests. Independent evidence and limits are in the [handoff](docs/HANDOFF.en.md). Other devices and system updates require verification.

## Behavior

- The measured target is **38/150, about 25.3%**, calculated by rounding 25% of maximum media volume.
- Both the app and dedicated Shizuku service check the configured address and current media route. Speakers and other headsets do not trigger adjustment.
- A **15-second** correction window handles delayed volume restoration. Later manual adjustments remain effective.
- On the tested vivo phone, official Shizuku supplies a dedicated UserService under the actual shell UID. It only adjusts this headset to the fixed target and reports its own state.
- Preparation is user initiated, requests an approximately two-minute window, and restores the original setting on success, cancellation, or timeout. Interrupted preparation retains a recovery journal.

The system may briefly restore a higher volume before correction. This is not a hard limit that keeps the entire reconnection below 25%. Acoustic levels and long-term battery use have not been measured.

## Layout and building

| Path | Purpose |
| --- | --- |
| `android/` | Current 1.6 app, tests, build script, and dependency licenses |
| `docs/` | Chinese and English handoff and validation notes |
| `macos/` | Preserved independent macOS utility: sets 20% when switching to L06 |
| `AGENTS.md` | Development guidance for a new Codex project or chat |

Select this repository folder for your new Codex project. Read the README, build guide, and handoff first.

Configure your headset through `L06_BLUETOOTH_ADDRESS` or `android/.local/headset-address.txt`. Public source contains no personal Bluetooth address. Build with your own address and signing key. You need JDK 17+, Android SDK Platform 34, and Build Tools 34.0.0. The script pins D8 9.3.16 and downloads missing tools. See the [English](android/BUILD.en.md) / [中文](android/BUILD.md) build guide. Private configuration stays in `.local/`. Device-specific APKs are not published here.

## Existing macOS utility

Run `macos/install.sh` with Xcode Command Line Tools installed. Its LaunchAgent watches the default CoreAudio output, sets 20% once when output switches to L06, and preserves later manual changes. It is independent of Android.

## Sources and licensing

[Official Shizuku](https://github.com/RikkaApps/Shizuku), [Shizuku API](https://github.com/RikkaApps/Shizuku-API), [Android foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types), and [R8 / D8](https://r8.googlesource.com/r8/+/refs/heads/main/README.md). Third-party SDK licenses are included under Android. The project's own code does not currently declare an open-source license; public visibility does not grant reuse rights.
