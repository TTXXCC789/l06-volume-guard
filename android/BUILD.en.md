# Build Android 1.6

[中文版](BUILD.md)

The app requires Android 13 (API 33) or later. Building requires JDK 17 or later, Android SDK Platform 34, Build Tools 34.0.0, `curl`, `unzip`, `zip`, and either `sha256sum` or `shasum`. The project includes pinned SDK dependencies; if `lib/` is missing, the build downloads Shizuku API 13.1.5 and AndroidX annotation 1.3.0.

Run the following from this directory, using your local installation paths:

```sh
export L06_JAVA="$JAVA_HOME"
export L06_SDK="$ANDROID_SDK_ROOT"
```

Install `platforms;android-34` and `build-tools;34.0.0` in your SDK. The script has macOS Homebrew defaults; use the variables above to override them.

The default D8 is pinned to 9.3.16. The script downloads it from [Google's official release](https://storage.googleapis.com/r8-releases/raw/9.3.16/r8lib.jar) to `build/tooling/r8-9.3.16.jar`, verifies its SHA-256, and invokes it through Java. This version successfully built the project with JDK 27. The older D8 bundled with Build Tools 34 failed with an internal error in that environment, so it is not the default. Set `L06_D8=/path/to/d8` only if you want to select an explicit CLI override. See the [official invocation instructions](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#running-d8) and [R8 license](https://r8.googlesource.com/r8/+/65eb2ed58d2ac1dbce414b51a8ea877c3ce5f68a/LICENSE). The downloaded build tool is not committed to the repository or included in the phone APK.

## Select your own headset

Use `adb devices -l` to find your phone, then set `PHONE_SERIAL` to its serial number. Connect your headset and select it as the media output. You can inspect the current audio device addresses without changing settings:

```sh
adb -s "$PHONE_SERIAL" shell dumpsys audio
```

Identify the address of the intended Bluetooth A2DP device. The address must contain six hexadecimal bytes separated by colons. Choose one configuration method:

- Set the `L06_BLUETOOTH_ADDRESS` environment variable. It takes precedence over the local file.
- Save only the address, on one line, in `.local/headset-address.txt`.

```sh
mkdir -p .local
# Fill in .local/headset-address.txt with an editor, then restrict its permissions.
chmod 600 .local/headset-address.txt
```

A missing, empty, or malformed address stops the build before compilation. `build.sh` generates one `build/v1.6/generated/BuildConfig.java` shared by the app and its Shizuku service. This selects the headset at build time; the running app still checks the actual media output before changing volume.

## Sign and build

Use your own PKCS12 signing file with the alias `l06volume`. You can create a new key for a new installation. To update an installed app, you must keep its original signing key:

```sh
"$L06_JAVA/bin/keytool" -genkeypair -alias l06volume -keyalg RSA -keysize 2048 \
  -validity 10000 -storetype PKCS12 -keystore .local/signing.p12
```

Set the password when prompted. With an editor, create `.local/signing-password.txt` containing only that password on one line. Restrict both files to your user, then build:

```sh
chmod 600 .local/signing.p12 .local/signing-password.txt
./build.sh .local/signing.p12 .local/signing-password.txt
```

An optional third argument selects the APK output path. The default output is `build/v1.6/L06-Volume-Guard-1.6.apk`. The script runs the GuardWindow tests, compiles, packages, signs, and verifies the signature.

Keep `.local/`, `build/`, and generated APKs local. An APK contains the headset address configured at build time. Do not upload your APK, signing key, password, phone serial number, or device logs to a public repository.

## Install on your own phone

Confirm that `PHONE_SERIAL` selects your own phone. Install official Shizuku separately, then start and authorize it through its official UI. The app does not grant permissions automatically.

```sh
adb -s "$PHONE_SERIAL" install -r build/v1.6/L06-Volume-Guard-1.6.apk
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.BLUETOOTH_CONNECT
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.POST_NOTIFICATIONS
# One-time setup only if you use the phone-only startup preparation feature.
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.WRITE_SECURE_SETTINGS
```

Open L06 Volume Guard, tap “启动 / 恢复自动守护” (Start / Restore Automatic Guard), and follow the system prompts to authorize Shizuku. Verify the approximately 25% volume only after confirming that your intended headset is the media output. The app explains the personal-hotspot, pairing, and cancellation steps for phone-only startup preparation.
