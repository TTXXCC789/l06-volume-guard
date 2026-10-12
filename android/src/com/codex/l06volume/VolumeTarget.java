package com.codex.l06volume;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;
import java.util.List;

final class VolumeTarget {
    private static final String TAG = "L06VolumeGuard";
    private static final String L06_ADDRESS = BuildConfig.L06_ADDRESS;
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    static boolean isL06Address(String address) {
        return address != null && L06_ADDRESS.equalsIgnoreCase(address);
    }

    static int targetIndex(int maximum) {
        return Math.round(maximum * 0.25f);
    }

    static AudioAttributes mediaAttributes() { return MEDIA; }

    static boolean isL06MediaRoute(Context context) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        try {
            // Connected devices are not necessarily the selected media output.
            List<AudioDeviceInfo> route = audio.getAudioDevicesForAttributes(MEDIA);
            if (route.isEmpty()) return false;
            for (AudioDeviceInfo device : route) {
                if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || !isL06Address(device.getAddress())) return false;
            }
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Cannot confirm L06 media route", e);
            return false;
        }
    }

    static boolean apply(Context context, String reason) {
        if (!isL06MediaRoute(context)) return false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = targetIndex(max);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            if (before != target) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                Log.i(TAG, reason + " before=" + before + " target=" + target + "/" + max
                        + " after=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            }
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC) == target;
        } catch (SecurityException e) {
            Log.w(TAG, "System rejected volume change", e);
            return false;
        }
    }
}
