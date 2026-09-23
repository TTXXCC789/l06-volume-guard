package com.codex.l06volume;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public final class L06VolumeAccessibilityService extends AccessibilityService {
    private static final String TAG = "L06VolumeGuard";
    private static volatile L06VolumeAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable firstAttempt = () -> applyVolume("initial");
    private final Runnable finalAttempt = () -> applyVolume("final");
    private long generation;

    static void onL06Connected(long nextGeneration) {
        L06VolumeAccessibilityService service = instance;
        if (service == null) {
            Log.w(TAG, "Accessibility service is not enabled");
            return;
        }
        service.schedule(nextGeneration);
    }

    static void onL06Disconnected(long nextGeneration) {
        L06VolumeAccessibilityService service = instance;
        if (service != null) {
            service.generation = nextGeneration;
            service.handler.removeCallbacks(service.firstAttempt);
            service.handler.removeCallbacks(service.finalAttempt);
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility service connected; screen content access disabled");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    private void schedule(long nextGeneration) {
        generation = nextGeneration;
        handler.removeCallbacks(firstAttempt);
        handler.removeCallbacks(finalAttempt);
        handler.postDelayed(firstAttempt, 2400);
        handler.postDelayed(finalAttempt, 3300);
        Log.i(TAG, "Scheduled L06 volume set; generation=" + generation
                + " at=" + SystemClock.elapsedRealtime());
    }

    private void applyVolume(String phase) {
        if (generation != ConnectionReceiver.currentGeneration()) return;
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean connected = false;
        for (AudioDeviceInfo output : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (output.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    && ConnectionReceiver.isL06Name(output.getProductName().toString())) {
                connected = true;
                break;
            }
        }
        if (!connected) {
            Log.i(TAG, phase + " skipped: L06 is not an active A2DP output");
            return;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * 0.25f);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (before != target) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, phase + " elapsed=" + SystemClock.elapsedRealtime()
                + " before=" + before + " target=" + target + "/" + max + " after=" + after);
    }
}
