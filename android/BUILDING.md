# Build and sign Android APK

Requirements: JDK 17, Android SDK Platform 34, Build Tools 34.0.0. Example shell commands from this repository root (adjust `ANDROID_SDK_ROOT`):

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/34.0.0"
mkdir -p android/build/classes
javac --release 17 -classpath "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" \
  -d android/build/classes android/src/com/codex/l06volume/*.java
JAVA_HOME=$(/usr/libexec/java_home -v 17) "$BUILD_TOOLS/d8" --min-api 33 \
  --output android/build android/build/classes/com/codex/l06volume/*.class
"$BUILD_TOOLS/aapt2" compile --dir android/res -o android/build/resources.zip
"$BUILD_TOOLS/aapt2" link -o android/build/unsigned.apk --manifest android/AndroidManifest.xml \
  -I "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" \
  -R android/build/resources.zip --auto-add-overlay --min-sdk-version 33 --target-sdk-version 34
(cd android/build && zip -j unsigned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 android/build/unsigned.apk android/build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks /path/to/your-keystore.jks \
  --out android/build/L06-Volume-Guard.apk android/build/aligned.apk
```

The signing key is intentionally not included. Use a key you control and keep it private. Then install:

```sh
adb install -r android/build/L06-Volume-Guard.apk
```
