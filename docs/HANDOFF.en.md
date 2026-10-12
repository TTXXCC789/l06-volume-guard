# L06 Volume Guard handoff

[中文说明](HANDOFF.md)

L06 Volume Guard restores the selected Bluetooth headset's media volume to about 25% after reconnection. Its correction window is bounded so later manual volume changes remain effective.

## Current goal and baseline

The current Android version is **1.6 / versionCode 7**, with source in `android/`. See [android/BUILD.en.md](../android/BUILD.en.md) for building and the [root README](../README.en.md) for usage. The repository also retains the older `macos/` utility targeting 20%; it is independent of the phone app.

The user's core requirement is to leave L06 powered on and have media volume automatically fall to about 25% when the phone disconnects and reconnects. The tested device has a maximum volume index of 150 and a target of 38. A 15-second correction window handles old volume values restored afterward by the system or headset. The user can freely adjust volume after that window. Speakers and other headsets must remain unaffected.

## Implementation map

| Android file | Responsibility |
| --- | --- |
| `MainActivity.java` | Permissions, guard status, user-initiated phone-side preparation, and opening official Shizuku |
| `VolumeGuardService.java`, `GuardWindow.java` | Media route monitoring, the 15-second correction window, and background lifecycle |
| `ConnectionReceiver.java`, `StartupReceiver.java` | Recovering an enabled guard on connection, boot, and package replacement |
| `VolumeTarget.java` | Checking the actual L06 media route and setting volume from the foreground |
| `ShizukuBridge.java`, `ShellVolumeService.java` | Restricted IPC after authorization, audio initialization with the actual shell identity, and setting the fixed volume target |
| `OfflineStartupService.java` | User-initiated, bounded startup preparation and cleanup after cancellation, timeout, success, or interruption |

This vivo / Android 16 device blocks third-party apps from setting media volume in the background. Adding a foreground service or accessibility service alone does not solve that restriction. The working backend is a dedicated UserService started by official Shizuku; device testing confirmed volume control under **UID 2000**. The app and service independently verify L06's device type, configured address, and media route. The service checks the caller UID and does not accept arbitrary shell commands.

Version 1.5 added startup recovery and backend reconnection. Version 1.6 added phone-side startup preparation: after the one-time `WRITE_SECURE_SETTINGS` setup grant, the user enables the phone's own personal hotspot, starts preparation in the L06 app, and then taps “Start” in official Shizuku. Saved pairing can normally be reused; first-time or expired pairing still requires the official pairing flow. Preparation requests an approximately two-minute preparation window; system freezing can delay cleanup. It does not confirm Shizuku authorization on the user's behalf or promise automatic startup after reboot.

Preparation temporarily changes the wireless debugging setting and saves a recovery record. Success, cancellation, or timeout restores the original value and releases resources. An interruption retains the recovery record; the next recovery attempt verifies the original value before clearing it. A previously reachable port does not prove that Shizuku is running, and a live monitoring service does not prove that background volume control is ready. Preparation depends on system behavior and must be revalidated on other models or after system updates.

## Verified results and evidence limits

| Item | Result and evidence level |
| --- | --- |
| Version 1.5 lock-screen reconnection | Independent device verification: all 19 samples were taken while locked, media volume returned to 38/150, and a delayed restoration of the old volume was corrected again |
| Manual adjustment after correction | Independent device verification: after the window ended, a manual setting of 30/150 remained unchanged for about 6.19 seconds |
| Backend recovery | Independent device verification: the volume subservice reconnected after exiting, and the app reconnected automatically after the main Shizuku service restarted |
| Version 1.6 preparation timeout | Independent device verification: preparation ended after approximately 120,034 ms, the setting returned to 0, the recovery record was no longer pending, and the wake lock was released; preparation continued while the home screen was displayed |
| Version 1.6 interruption recovery | Independent device verification: the interrupted-operation record was cleared. In that run, the system had already reset the setting before the app verified and cleaned up, so this does not independently prove every app-driven recovery branch |
| Version 1.6 official startup integration | Independent device verification: official Shizuku started successfully, the control service ran under UID 2000, and the guard restored the actual volume to 38/150 |
| Phone-only startup after unplugging USB and a full reboot | **User report:** startup succeeded using mobile data and the phone's own hotspot, followed by L06 at about 25%. The user ultimately clarified that no external Wi-Fi was connected; that clarification is the final account |
| Reconnection after turning the hotspot off | **User report:** L06 still fell to about 25% after reconnection with the phone's hotspot turned off |

The independently recorded startup integration was a recovery test while the phone was already running; it did not replace the user's full cold-boot test. Its UI trace included a brief association with an external Wi-Fi network, without confirmed IP acquisition or successful connectivity. Do not use that trace to claim the independent test never associated with external Wi-Fi, or to contradict the user's later clarification about a separate unplugged reboot test.

The system can still briefly restore a higher volume before correction, so volume is not guaranteed to remain at or below 25% throughout reconnection. Actual sound pressure, long-term battery consumption, and every Android model have not been validated. Recovery after a user force-stop, permission revocation, or guard disabling remains subject to system behavior and the user's choices.

## Continuing development

Preserve the core reconnection behavior and existing cleanup first. Device addresses, signing material, and passwords come from private local configuration and must not enter the public repository; see [AGENTS.md](../AGENTS.md) and the build guide. After changing background volume control, recovery, or preparation, verify the affected paths and continue separating independent measurements from user reports.

Future reports should identify the version, startup method, and whether a real device was tested. Do not describe this project as a general root replacement, an automatic boot activator, or an arbitrary shell execution tool.
