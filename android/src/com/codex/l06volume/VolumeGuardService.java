package com.codex.l06volume;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public final class VolumeGuardService extends Service {
    private static final String TAG = "L06VolumeGuard";
    private static volatile VolumeGuardService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GuardWindow window = new GuardWindow();
    private final Runnable poll = this::checkRoute;
    private AudioManager audio;
    private boolean shellReady;
    private boolean monitoring;
    private final Runnable shellStateChanged = () -> handler.post(() -> {
        if (!monitoring) return;
        boolean ready = ShizukuBridge.isReady();
        if (ready && !shellReady) window.disconnect();
        shellReady = ready;
        getSystemService(NotificationManager.class).notify(1, guardNotification());
        checkRoute();
    });
    private PowerManager.WakeLock wakeLock;
    private boolean connectionBlocked;
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        && VolumeTarget.isL06Address(device.getAddress())) {
                    connectionBlocked = false;
                    wakeForConnection();
                    break;
                }
            }
            checkRoute();
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { checkRoute(); }
    };

    static boolean isRunning() { return instance != null; }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("guard", MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void start(Context context) {
        context.getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        ensureStarted(context);
    }

    /** System recovery must preserve an explicit decision to disable the guard. */
    static void ensureStarted(Context context) {
        OfflineStartupService.recoverPending(context);
        if (!isEnabled(context)) return;
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        if (isRunning()) return;
        try {
            context.startForegroundService(new Intent(context, VolumeGuardService.class));
        } catch (android.app.ForegroundServiceStartNotAllowedException | SecurityException e) {
            Log.w(TAG, "Open the app to start its foreground volume guard", e);
        }
    }

    static void stop(Context context) {
        context.getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        ShizukuBridge.unbind();
        context.stopService(new Intent(context, VolumeGuardService.class));
    }

    static void onConnectionChanged(boolean connected) {
        VolumeGuardService service = instance;
        if (service == null) {
            Log.w(TAG, "Open the app to start L06 volume guard");
            return;
        }
        service.handler.post(() -> {
            if (!service.monitoring) return;
            service.connectionBlocked = !connected;
            if (!connected) {
                service.window.disconnect();
                service.releaseWakeLock();
                service.handler.removeCallbacks(service.poll);
                service.handler.postDelayed(service.poll, 3000);
            } else {
                service.wakeForConnection();
                service.checkRoute();
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        if (!isEnabled(this) || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("volume-guard",
                "L06 后台音量守护", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, guardNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (monitoring) return;
        audio = getSystemService(AudioManager.class);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "L06VolumeGuard:connection");
        wakeLock.setReferenceCounted(false);
        monitoring = true;
        instance = this;
        ShizukuBridge.addStateListener(shellStateChanged);
        ShizukuBridge.bind(this);
        shellReady = ShizukuBridge.isReady();
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        Log.i(TAG, "Foreground service ready; checking existing L06 route");
        checkRoute();
    }

    private Notification guardNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean ready = ShizukuBridge.isReady();
        return new Notification.Builder(this, "volume-guard")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle(ready ? "L06 音量守护运行中" : "L06 连接监测中")
                .setContentText(ready ? "连接后设为约 25%，之后可自由调整" : "自动降音量等待 Shizuku 启动并授权")
                .setContentIntent(open).setOngoing(true).build();
    }

    private void checkRoute() {
        if (!monitoring) return;
        handler.removeCallbacks(poll);
        long now = SystemClock.elapsedRealtime();
        boolean wasActive = window.isActive();
        boolean active = !connectionBlocked && VolumeTarget.isL06MediaRoute(this);
        if (window.updateRoute(active, now)) {
            wakeLock.acquire(GuardWindow.DURATION_MS + 2000);
            Log.i(TAG, "L06 media route active; correcting volume for 15 seconds");
        }
        boolean correcting = window.shouldApply(now);
        if (correcting) {
            if (ShizukuBridge.isReady()) {
                ShizukuBridge.applyIfL06(result -> {
                    if (result.before != result.after || !result.success()) {
                        Log.i(TAG, "Shell guard uid=" + result.uid + " before=" + result.before
                                + " target=" + result.target + " after=" + result.after
                                + " result=" + result.message);
                    }
                });
            } else {
                VolumeTarget.apply(this, "connection guard");
            }
        } else if (active || wasActive) {
            releaseWakeLock();
        }
        // Polling also catches output switches that do not add/remove a connected device.
        handler.postDelayed(poll, correcting ? 500 : 3000);
    }

    private void wakeForConnection() {
        if (monitoring && wakeLock != null && !wakeLock.isHeld()) {
            // Keep route setup and the bounded retry window running with the screen off.
            wakeLock.acquire(20000);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void stopMonitoring() {
        monitoring = false;
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        window.disconnect();
        releaseWakeLock();
        wakeLock = null;
        if (audio != null) audio.unregisterAudioDeviceCallback(deviceCallback);
        audio = null;
        ShizukuBridge.removeStateListener(shellStateChanged);
        ShizukuBridge.unbind();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this) || !monitoring) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }
}
