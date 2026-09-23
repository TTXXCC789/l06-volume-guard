package com.codex.l06volume;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;

public final class ConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "L06VolumeGuard";
    private static final AtomicLong GENERATION = new AtomicLong();

    @Override public void onReceive(Context context, Intent intent) {
        if (!BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Bluetooth permission missing");
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (device == null || !"L06".equalsIgnoreCase(device.getName())) return;

        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        long generation = GENERATION.incrementAndGet();
        Log.i(TAG, "A2DP state=" + state + " device=" + device.getName()
                + " elapsed=" + SystemClock.elapsedRealtime());
        if (state == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "A2DP connected; requesting accessibility service");
            L06VolumeAccessibilityService.onL06Connected(generation);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            L06VolumeAccessibilityService.onL06Disconnected(generation);
        }
    }

    static long currentGeneration() { return GENERATION.get(); }

    static boolean isL06Name(String name) {
        return name != null && "L06".equalsIgnoreCase(name);
    }
}
