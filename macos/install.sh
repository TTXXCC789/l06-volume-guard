#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_DIR="$HOME/Library/Application Support/L06VolumeGuard"
AGENT="$HOME/Library/LaunchAgents/com.codex.l06-volume-guard.plist"
mkdir -p "$APP_DIR" "$HOME/Library/LaunchAgents"
swiftc -O "$ROOT/macos/L06VolumeGuard.swift" -o "$APP_DIR/L06VolumeGuard"
chmod 755 "$APP_DIR/L06VolumeGuard"
cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.codex.l06-volume-guard</string>
  <key>ProgramArguments</key><array><string>$APP_DIR/L06VolumeGuard</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/L06VolumeGuard-launchd.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/L06VolumeGuard-launchd.log</string>
</dict>
</plist>
PLIST
plutil -lint "$AGENT"
launchctl bootout "gui/$(id -u)" "$AGENT" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT"
echo "Installed and started com.codex.l06-volume-guard"
