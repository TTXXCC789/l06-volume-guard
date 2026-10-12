#!/bin/sh
set -eu
L06_APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
L06_SDK=${L06_SDK:-/opt/homebrew/share/android-commandlinetools}
L06_JAVA=${L06_JAVA:-/opt/homebrew/opt/openjdk}
L06_TOOLS="$L06_SDK/build-tools/34.0.0"
L06_D8=${L06_D8:-}
L06_BUILD="$L06_APP_DIR/build/v1.6"
L06_LIB="$L06_APP_DIR/lib"
L06_SHIZUKU_VERSION=13.1.5
L06_OUTPUT=${3:-"$L06_BUILD/L06-Volume-Guard-1.6.apk"}
if [ "${L06_BLUETOOTH_ADDRESS+x}" = x ]; then
  L06_ADDRESS=$L06_BLUETOOTH_ADDRESS
elif [ -f "$L06_APP_DIR/.local/headset-address.txt" ]; then
  L06_ADDRESS=$(cat "$L06_APP_DIR/.local/headset-address.txt")
else
  printf '%s\n' 'Set L06_BLUETOOTH_ADDRESS or create .local/headset-address.txt before building.' >&2
  exit 2
fi
case "$L06_ADDRESS" in
  [0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]) ;;
  *) printf '%s\n' 'Headset address must contain exactly six hexadecimal bytes separated by colons.' >&2; exit 2 ;;
esac
: "${1:?Usage: build.sh signing.p12 password-file [output.apk]}"
: "${2:?Usage: build.sh signing.p12 password-file [output.apk]}"
rm -rf "$L06_BUILD/classes" "$L06_BUILD/tests" "$L06_BUILD/generated"
mkdir -p "$L06_BUILD/classes" "$L06_BUILD/tests" "$L06_BUILD/generated"
cat > "$L06_BUILD/generated/BuildConfig.java" <<EOF
package com.codex.l06volume;
final class BuildConfig {
    static final String L06_ADDRESS = "$L06_ADDRESS";
    private BuildConfig() { }
}
EOF
mkdir -p "$L06_LIB"
# AARs contain no resources. Merge their classes and explicitly declare provider manifest
# entries in the app manifest because this build does not run Gradle's manifest merger.
for L06_DEP in api aidl shared provider; do
  L06_JAR="$L06_LIB/shizuku-$L06_DEP-$L06_SHIZUKU_VERSION.jar"
  if [ ! -s "$L06_JAR" ]; then
    L06_AAR="$L06_LIB/shizuku-$L06_DEP-$L06_SHIZUKU_VERSION.aar"
    curl -fsSL --retry 2 --connect-timeout 20 \
      "https://repo.maven.apache.org/maven2/dev/rikka/shizuku/$L06_DEP/$L06_SHIZUKU_VERSION/$L06_DEP-$L06_SHIZUKU_VERSION.aar" \
      -o "$L06_AAR.tmp"
    mv "$L06_AAR.tmp" "$L06_AAR"
    unzip -p "$L06_AAR" classes.jar > "$L06_JAR.tmp"
    mv "$L06_JAR.tmp" "$L06_JAR"
  fi
done
L06_ANNOTATION="$L06_LIB/annotation-1.3.0.jar"
if [ ! -s "$L06_ANNOTATION" ]; then
  curl -fsSL --retry 2 --connect-timeout 20 \
    "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar" \
    -o "$L06_ANNOTATION.tmp"
  mv "$L06_ANNOTATION.tmp" "$L06_ANNOTATION"
fi
L06_CLASSPATH="$L06_SDK/platforms/android-34/android.jar:$L06_LIB/shizuku-api-$L06_SHIZUKU_VERSION.jar:$L06_LIB/shizuku-aidl-$L06_SHIZUKU_VERSION.jar:$L06_LIB/shizuku-shared-$L06_SHIZUKU_VERSION.jar:$L06_LIB/shizuku-provider-$L06_SHIZUKU_VERSION.jar:$L06_ANNOTATION"
"$L06_JAVA/bin/javac" --release 17 -d "$L06_BUILD/tests" \
  "$L06_APP_DIR/src/com/codex/l06volume/GuardWindow.java" "$L06_APP_DIR/tests/GuardWindowTest.java"
"$L06_JAVA/bin/java" -cp "$L06_BUILD/tests" com.codex.l06volume.GuardWindowTest
"$L06_JAVA/bin/javac" --release 17 -classpath "$L06_CLASSPATH" \
  -d "$L06_BUILD/classes" "$L06_APP_DIR"/src/com/codex/l06volume/*.java \
  "$L06_BUILD/generated/BuildConfig.java"
if [ -z "$L06_D8" ]; then
  L06_R8="$L06_APP_DIR/build/tooling/r8-9.3.16.jar"
  mkdir -p "$L06_APP_DIR/build/tooling"
  if [ ! -s "$L06_R8" ]; then
    curl -fsSL --retry 2 --connect-timeout 20 --max-time 180 \
      "https://storage.googleapis.com/r8-releases/raw/9.3.16/r8lib.jar" -o "$L06_R8.tmp"
    mv "$L06_R8.tmp" "$L06_R8"
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    L06_R8_HASH=$(sha256sum "$L06_R8" | awk '{print $1}')
  else
    L06_R8_HASH=$(shasum -a 256 "$L06_R8" | awk '{print $1}')
  fi
  if [ "$L06_R8_HASH" != e4f2d1af4adb39f0f67b4a501a08a93354c2b4f3a116ee4d0813cc87f2db7e51 ]; then
    printf '%s\n' 'Pinned D8 checksum mismatch. Remove build/tooling/r8-9.3.16.jar and rebuild.' >&2
    exit 2
  fi
fi
l06_d8() {
  if [ -n "$L06_D8" ]; then
    JAVA_HOME="$L06_JAVA" "$L06_D8" "$@"
  else
    "$L06_JAVA/bin/java" -cp "$L06_R8" com.android.tools.r8.D8 "$@"
  fi
}
l06_d8 --min-api 33 \
  --lib "$L06_SDK/platforms/android-34/android.jar" \
  --output "$L06_BUILD" "$L06_BUILD"/classes/com/codex/l06volume/*.class \
  "$L06_LIB"/shizuku-*-$L06_SHIZUKU_VERSION.jar "$L06_ANNOTATION"
"$L06_TOOLS/aapt2" compile --dir "$L06_APP_DIR/res" -o "$L06_BUILD/resources.zip"
"$L06_TOOLS/aapt2" link -o "$L06_BUILD/unsigned.apk" --manifest "$L06_APP_DIR/AndroidManifest.xml" \
  -I "$L06_SDK/platforms/android-34/android.jar" -R "$L06_BUILD/resources.zip" \
  -A "$L06_APP_DIR/assets" \
  --auto-add-overlay --min-sdk-version 33 --target-sdk-version 34
(cd "$L06_BUILD" && zip -q -j unsigned.apk classes.dex)
"$L06_TOOLS/zipalign" -f 4 "$L06_BUILD/unsigned.apk" "$L06_BUILD/aligned.apk"
JAVA_HOME="$L06_JAVA" "$L06_TOOLS/apksigner" sign --ks "$1" --ks-type PKCS12 \
  --ks-key-alias l06volume --ks-pass "file:$2" \
  --out "$L06_OUTPUT" "$L06_BUILD/aligned.apk"
JAVA_HOME="$L06_JAVA" "$L06_TOOLS/apksigner" verify --verbose "$L06_OUTPUT"
