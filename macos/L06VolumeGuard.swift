import Foundation
import CoreAudio

private let logURL = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Logs/L06VolumeGuard.log")
private let targetDeviceName = "L06"

func log(_ message: String) {
    let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
    guard let data = line.data(using: .utf8) else { return }
    if FileManager.default.fileExists(atPath: logURL.path), let handle = try? FileHandle(forWritingTo: logURL) {
        try? handle.seekToEnd()
        try? handle.write(contentsOf: data)
        try? handle.close()
    } else {
        try? FileManager.default.createDirectory(at: logURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: logURL, options: .atomic)
    }
}

func defaultOutputName() -> String? {
    var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var device = AudioDeviceID(0)
    var size = UInt32(MemoryLayout<AudioDeviceID>.size)
    guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr, device != 0 else { return nil }

    var nameAddress = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var name: CFString = "" as CFString
    size = UInt32(MemoryLayout<CFString>.size)
    let status = withUnsafeMutablePointer(to: &name) { pointer in
        AudioObjectGetPropertyData(device, &nameAddress, 0, nil, &size, pointer)
    }
    guard status == noErr else { return nil }
    return name as String
}

func setOutputVolumeTo20Percent() {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    process.arguments = ["-e", "set volume output volume 20"]
    do {
        try process.run()
        process.waitUntilExit()
        if process.terminationStatus == 0 {
            log("Set macOS output volume to 20% for L06")
        } else {
            log("ERROR: osascript exited with status \(process.terminationStatus)")
        }
    } catch {
        log("ERROR: could not set output volume: \(error.localizedDescription)")
    }
}

log("Started; watching default Bluetooth output named \(targetDeviceName)")
var lastWasTarget = false
while true {
    let outputName = defaultOutputName()
    let isTarget = outputName?.caseInsensitiveCompare(targetDeviceName) == .orderedSame
    if isTarget && !lastWasTarget {
        log("L06 became default output")
        setOutputVolumeTo20Percent()
    }
    lastWasTarget = isTarget
    Thread.sleep(forTimeInterval: 1.0)
}
