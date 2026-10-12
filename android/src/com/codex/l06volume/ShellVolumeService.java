package com.codex.l06volume;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/** Shizuku UserService. Its Binder exposes only the fixed L06 volume operation. */
public final class ShellVolumeService extends Binder {
    public static final String DESCRIPTOR = "com.codex.l06volume.ShellVolumeService";
    public static final int TRANSACTION_APPLY = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACTION_UID = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TRANSACTION_DESTROY = 16777115;
    private static final String TAG = "L06ShellVolume";
    private static final String APP_PACKAGE = "com.codex.l06volume";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String L06_ADDRESS = BuildConfig.L06_ADDRESS;
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    private AudioManager audio;
    private int appUid = -1;
    private String initializationError;

    public ShellVolumeService() {
        attachInterface(null, DESCRIPTOR);
        try {
            Context system = obtainSystemContext();
            appUid = system.createPackageContext(APP_PACKAGE, 0).getApplicationInfo().uid;
            Context shell = system.createPackageContext(SHELL_PACKAGE, 0);
            // createPackageContext inherits its container's opPackageName. Override attribution
            // explicitly so AppOps sees a package belonging to the actual shell process UID.
            Context attributed = new ShellContext(shell);
            audio = AudioManager.class.getDeclaredConstructor(Context.class)
                    .newInstance(attributed);
            Log.i(TAG, "ready uid=" + Process.myUid() + " appUid=" + appUid);
        } catch (Exception e) {
            initializationError = e.toString();
            Log.e(TAG, "Cannot initialize shell audio", e);
        }
    }

    /** Shizuku 13 prefers this constructor. Initialization needs a shell package context. */
    public ShellVolumeService(Context ignored) {
        this();
    }

    private static Context obtainSystemContext() throws Exception {
        if (Looper.getMainLooper() == null) {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            else throw new IllegalStateException("Main looper has not been initialized");
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method current = activityThread.getDeclaredMethod("currentActivityThread");
        Object thread = current.invoke(null);
        if (thread == null) thread = activityThread.getDeclaredMethod("systemMain").invoke(null);
        return (Context) activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
    }

    private static final class ShellContext extends ContextWrapper {
        private final AttributionSource source = new AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PACKAGE).build();

        ShellContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() { return source; }
        // This overrides the framework's public, non-SDK Context method inside UserService.
        public String getOpPackageName() { return SHELL_PACKAGE; }
    }

    private boolean isL06Route() {
        List<AudioDeviceInfo> devices = audio.getAudioDevicesForAttributes(MEDIA);
        if (devices == null || devices.isEmpty()) return false;
        for (AudioDeviceInfo device : devices) {
            if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || !L06_ADDRESS.equalsIgnoreCase(device.getAddress())) return false;
        }
        return true;
    }

    /** Callers cannot choose an index, a stream, a Bluetooth address, or a shell command. */
    public synchronized Result applyIfL06() {
        int uid = Process.myUid();
        if (audio == null) return Result.error(uid, initializationError);
        if (uid != 2000 && uid != 0) return Result.error(uid, "Not running as shell/root");
        try {
            if (!isL06Route()) return new Result(Result.NOT_L06, uid, -1, -1, -1,
                    "L06 is not the media output");
            int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .25f);
            // Check again immediately before changing the current media output's volume.
            if (!isL06Route()) return new Result(Result.NOT_L06, uid, before, target, before,
                    "Media output changed");
            if (before != target) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            SystemClock.sleep(150);
            if (!isL06Route()) return new Result(Result.NOT_L06, uid, before, target, -1,
                    "Media output changed while verifying");
            int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            Log.i(TAG, "uid=" + uid + " before=" + before + " target=" + target
                    + " after=" + after);
            return new Result(after == target ? Result.APPLIED : Result.SUBMITTED,
                    uid, before, target, after,
                    after == target ? "Volume verified" : "Volume has not reached target");
        } catch (RuntimeException e) {
            Log.w(TAG, "L06 volume operation failed", e);
            return Result.error(uid, e.toString());
        }
    }

    private void enforceCaller() {
        int caller = Binder.getCallingUid();
        if (caller != appUid && caller != Process.myUid() && caller != 0) {
            throw new SecurityException("Unexpected L06 volume caller uid=" + caller);
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code != TRANSACTION_APPLY && code != TRANSACTION_UID
                && code != TRANSACTION_STATUS && code != TRANSACTION_DESTROY) {
            return super.onTransact(code, data, reply, flags);
        }
        data.enforceInterface(DESCRIPTOR);
        enforceCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            if (code == TRANSACTION_DESTROY) {
                Log.i(TAG, "destroy");
                System.exit(0);
                return true;
            }
            reply.writeNoException();
            if (code == TRANSACTION_UID) reply.writeInt(Process.myUid());
            else if (code == TRANSACTION_STATUS) {
                reply.writeInt(audio != null && appUid >= 0 ? 1 : 0);
                reply.writeInt(Process.myUid());
                reply.writeString(audio != null
                        ? "Shell audio ready, uid=" + Process.myUid() : initializationError);
            }
            else applyIfL06().writeTo(reply);
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public static final class Result {
        public static final int APPLIED = 1;
        public static final int SUBMITTED = 0;
        public static final int NOT_L06 = -1;
        public static final int ERROR = -2;
        public static final int UNAVAILABLE = -3;
        public final int code, uid, before, target, after;
        public final String message;

        public Result(int code, int uid, int before, int target, int after, String message) {
            this.code = code;
            this.uid = uid;
            this.before = before;
            this.target = target;
            this.after = after;
            this.message = message;
        }
        public boolean success() { return code == APPLIED; }
        public static Result error(int uid, String message) {
            return new Result(ERROR, uid, -1, -1, -1, message);
        }
        public static Result unavailable(String message) {
            return new Result(UNAVAILABLE, -1, -1, -1, -1, message);
        }
        void writeTo(Parcel parcel) {
            parcel.writeInt(code);
            parcel.writeInt(uid);
            parcel.writeInt(before);
            parcel.writeInt(target);
            parcel.writeInt(after);
            parcel.writeString(message);
        }
        static Result readFrom(Parcel parcel) {
            return new Result(parcel.readInt(), parcel.readInt(), parcel.readInt(),
                    parcel.readInt(), parcel.readInt(), parcel.readString());
        }
    }
}
