package com.codex.l06volume;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Permission requests always use Shizuku's own user-facing authorization flow. */
public final class ShizukuBridge {
    private static final int REQUEST_PERMISSION = 2506;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "L06ShellCalls");
        thread.setDaemon(true);
        return thread;
    });
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile IBinder binder;
    private static volatile boolean remoteReady;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile String status = "Shizuku service not connected";
    // Connection ownership and retry scheduling are confined to the main thread.
    private static Context application;
    private static Shizuku.UserServiceArgs args;
    private static Attempt attempt;
    private static int retryIndex;
    private static final long[] RETRY_DELAYS = {2000, 5000, 15000, 30000};
    private static final Runnable RETRY = ShizukuBridge::connectIfNeeded;

    private static final class Attempt implements ServiceConnection {
        IBinder service;
        final Runnable timeout = () -> fail(this, "Shell volume service did not become ready");
        final IBinder.DeathRecipient death = () -> MAIN.post(
                () -> fail(this, "Shell volume service stopped"));

        @Override public void onServiceConnected(ComponentName name, IBinder connected) {
            MAIN.post(() -> accept(connected));
        }

        private void accept(IBinder connected) {
            if (attempt != this || application == null) return;
            if (service != null) return;
            if (!isGranted()) {
                fail(this, "Shizuku permission is required");
                return;
            }
            service = connected;
            binder = connected;
            try { connected.linkToDeath(death, 0); }
            catch (RemoteException e) {
                fail(this, "Shell volume service stopped during connection");
                return;
            }
            status = "Checking shell audio service";
            notifyState();
            WORKER.execute(() -> {
                RemoteStatus remote = readStatus(connected);
                MAIN.post(() -> {
                    if (attempt != this || binder != connected || application == null) return;
                    if (!isGranted() || !remote.initialized || remote.uid != 2000 && remote.uid != 0) {
                        fail(this, remote.message);
                        return;
                    }
                    MAIN.removeCallbacks(timeout);
                    retryIndex = 0;
                    remoteReady = true;
                    status = remote.message;
                    notifyState();
                });
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            MAIN.post(() -> fail(this, "Shell volume service disconnected"));
        }
    }

    static {
        Shizuku.addBinderReceivedListenerSticky(() -> {
            MAIN.removeCallbacks(RETRY);
            retryIndex = 0;
            status = isGranted() ? "Shizuku authorized" : "Shizuku permission is required";
            connectIfNeeded();
            notifyState();
        }, MAIN);
        Shizuku.addBinderDeadListener(() -> MAIN.post(() -> {
            // A replacement server may already have supplied a new Binder.
            if (isRunning()) return;
            cleanup(false);
            status = "Shizuku is not running";
            scheduleRetry();
            notifyState();
        }));
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> MAIN.post(() -> {
            if (requestCode != REQUEST_PERMISSION) return;
            status = grantResult == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku authorized" : "Shizuku permission denied";
            MAIN.removeCallbacks(RETRY);
            retryIndex = 0;
            connectIfNeeded();
            notifyState();
        }));
    }

    private ShizukuBridge() {}
    public interface ApplyCallback { void onResult(ShellVolumeService.Result result); }
    public static void addStateListener(Runnable listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeStateListener(Runnable listener) { LISTENERS.remove(listener); }
    private static void notifyState() {
        for (Runnable listener : LISTENERS) MAIN.post(listener);
    }
    public static boolean isRunning() { return Shizuku.pingBinder(); }
    public static boolean isGranted() {
        try {
            return isRunning() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { return false; }
    }
    public static boolean isReady() {
        IBinder current = binder;
        return remoteReady && isGranted() && current != null && current.pingBinder();
    }
    public static String getStatus() { return status; }

    /** Call only in response to the user's authorization action in the visible Activity. */
    public static void requestPermission() {
        if (!isRunning()) {
            status = "Start Shizuku before requesting permission";
        } else if (Shizuku.isPreV11()) {
            status = "Shizuku API 11 or newer is required";
        } else if (isGranted()) {
            if (application != null) bind(application);
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            status = "Allow L06 Volume Guard in Shizuku authorized applications";
        } else {
            Shizuku.requestPermission(REQUEST_PERMISSION);
        }
        notifyState();
    }

    /** This never requests or grants permission. It reconnects only while the guard is enabled. */
    public static void bind(Context context) {
        Context app = context.getApplicationContext();
        if (!VolumeGuardService.isEnabled(app)) return;
        application = app;
        if (args == null) args = new Shizuku.UserServiceArgs(
                new ComponentName(application, ShellVolumeService.class))
                .daemon(false).tag("l06-volume-shell").version(6)
                .processNameSuffix("l06_shell").debuggable(false);
        if (!MAIN.hasCallbacks(RETRY)) connectIfNeeded();
    }

    private static void connectIfNeeded() {
        if (application == null || isReady() || attempt != null) return;
        if (!isGranted()) {
            status = isRunning() ? "Shizuku permission is required" : "Shizuku is not running";
            scheduleRetry();
            return;
        }
        Attempt next = new Attempt();
        attempt = next;
        remoteReady = false;
        status = "Connecting shell volume service";
        MAIN.postDelayed(next.timeout, 10000);
        try { Shizuku.bindUserService(args, next); }
        catch (RuntimeException e) { fail(next, e.toString()); }
        notifyState();
    }

    private static void scheduleRetry() {
        if (application == null || MAIN.hasCallbacks(RETRY)) return;
        // When the server or grant is absent, wait quietly instead of repeated binding requests.
        long delay = isGranted() ? RETRY_DELAYS[Math.min(retryIndex++, RETRY_DELAYS.length - 1)] : 30000;
        MAIN.postDelayed(RETRY, delay);
    }

    private static void fail(Attempt failed, String message) {
        if (attempt != failed || application == null) return;
        cleanup(true);
        status = message;
        scheduleRetry();
        notifyState();
    }

    private static void cleanup(boolean destroy) {
        Attempt old = attempt;
        // Invalidate first: old timeout, death and connection callbacks cannot affect a new attempt.
        attempt = null;
        binder = null;
        remoteReady = false;
        if (old == null) return;
        MAIN.removeCallbacks(old.timeout);
        if (old.service != null) old.service.unlinkToDeath(old.death, 0);
        if (args != null) {
            if (destroy && isRunning()) {
                try { Shizuku.unbindUserService(args, old, true); }
                catch (RuntimeException ignored) { /* The server may have stopped concurrently. */ }
            }
            // remove=true destroys the remote service; remove=false clears the SDK connection cache.
            try { Shizuku.unbindUserService(args, old, false); }
            catch (RuntimeException ignored) { /* Local cleanup is best effort if the server died. */ }
        }
    }

    public static void unbind() {
        application = null;
        MAIN.removeCallbacks(RETRY);
        retryIndex = 0;
        cleanup(true);
        status = "Shell volume service stopped";
        notifyState();
    }

    /** Runs Binder IPC off the UI thread and returns the measured result on the main thread. */
    public static void applyIfL06(ApplyCallback callback) {
        IBinder current = binder;
        if (!isReady() || current == null) {
            MAIN.post(() -> callback.onResult(ShellVolumeService.Result.unavailable(status)));
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            MAIN.post(() -> callback.onResult(ShellVolumeService.Result.unavailable(
                    "A shell volume operation is already in progress")));
            return;
        }
        WORKER.execute(() -> {
            ShellVolumeService.Result result;
            if (!isGranted() || binder != current) {
                result = ShellVolumeService.Result.unavailable("Shizuku permission/service unavailable");
            } else {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(ShellVolumeService.DESCRIPTOR);
                    if (!current.transact(ShellVolumeService.TRANSACTION_APPLY, data, reply, 0)) {
                        throw new RemoteException("Unknown shell volume operation");
                    }
                    reply.readException();
                    result = ShellVolumeService.Result.readFrom(reply);
                } catch (Exception e) {
                    result = ShellVolumeService.Result.error(-1, e.toString());
                } finally { data.recycle(); reply.recycle(); }
            }
            ShellVolumeService.Result completed = result;
            IN_FLIGHT.set(false);
            MAIN.post(() -> callback.onResult(completed));
        });
    }

    private static final class RemoteStatus {
        final boolean initialized;
        final int uid;
        final String message;
        RemoteStatus(boolean initialized, int uid, String message) {
            this.initialized = initialized;
            this.uid = uid;
            this.message = message;
        }
    }

    private static RemoteStatus readStatus(IBinder service) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShellVolumeService.DESCRIPTOR);
            if (!service.transact(ShellVolumeService.TRANSACTION_STATUS, data, reply, 0)) {
                throw new RemoteException("Unknown shell status operation");
            }
            reply.readException();
            return new RemoteStatus(reply.readInt() != 0, reply.readInt(), reply.readString());
        } catch (Exception e) { return new RemoteStatus(false, -1, e.toString()); }
        finally { data.recycle(); reply.recycle(); }
    }
}
