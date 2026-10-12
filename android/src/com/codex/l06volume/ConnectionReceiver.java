package com.codex.l06volume;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public final class ConnectionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        try {
            if (device == null || !VolumeTarget.isL06Address(device.getAddress())) return;
        } catch (SecurityException e) {
            return;
        }
        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        if (state == BluetoothProfile.STATE_CONNECTED
                || state == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i("L06VolumeGuard", "L06 A2DP state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                VolumeGuardService.ensureStarted(context);
            }
            VolumeGuardService.onConnectionChanged(
                    state == BluetoothProfile.STATE_CONNECTED);
        }
    }
}
