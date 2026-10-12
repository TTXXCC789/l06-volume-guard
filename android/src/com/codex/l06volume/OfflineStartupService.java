package com.codex.l06volume;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public final class OfflineStartupService extends Service {
    private static final String TAG = "L06OfflineStartup", SETTING = "adb_wifi_enabled";
    private static final String PREFS = "offline_startup", ACTION_PREPARE = "com.codex.l06volume.PREPARE_OFFLINE";
    private static final long WINDOW_MS = 120000;
    private static final Object JOURNAL_LOCK = new Object();
    private static volatile OfflineStartupService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::nudge, timeout = () -> finish("准备窗口已到期");
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private PowerManager.WakeLock wakeLock;
    private SocketChannel socket;
    private volatile boolean active;
    private boolean prepared, tcpSeen, detectedBeforeCleanup;
    private String initialSetting, error = "";
    private int generation, reads, writes, port, connectionPort, successfulPort;
    private long started, deadline, nextLog, nextPing, nextConnect, connectDeadline, lastTcpSeen;

    static boolean isPreparing() {
        OfflineStartupService service = instance;
        return service != null && service.active;
    }
    static boolean isPortReady() {
        OfflineStartupService service = instance;
        return service != null && service.active && service.lastTcpSeen > 0
                && SystemClock.elapsedRealtime() - service.lastTcpSeen < 2500;
    }
    static boolean hasSetupPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    static String getStatus(Context context) {
        String status = prefs(context).getString("status", "尚未准备。先打开手机自己的个人热点，再开始准备。");
        return (isPreparing() ? "准备中\n" : "上次记录：\n") + status;
    }
    static void start(Context context) {
        context.startForegroundService(new Intent(context, OfflineStartupService.class).setAction(ACTION_PREPARE));
    }
    static void cancel(Context context) {
        if (instance != null) context.stopService(new Intent(context, OfflineStartupService.class));
        else recoverPending(context);
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, MODE_PRIVATE); }
    private static void status(Context context, String message) { prefs(context).edit().putString("status", message).apply(); }

    /** Recover our own unfinished setting change; a live preparation still owns its journal. */
    static boolean recoverPending(Context context) {
        synchronized (JOURNAL_LOCK) {
            if (isPreparing()) return true;
            SharedPreferences journal = prefs(context);
            if (!journal.getBoolean("pending", false)) return true;
            String original = journal.getBoolean("original_null", false) ? null : journal.getString("original", null);
            try {
                if (!Objects.equals(original, Settings.Global.getString(context.getContentResolver(), SETTING))) {
                    if (!hasSetupPermission(context)) {
                        status(context, "上次准备的设置尚未恢复。请完成一次性设置授权后，再点击取消准备。");
                        return false;
                    }
                    Settings.Global.putString(context.getContentResolver(), SETTING, original);
                }
                if (!Objects.equals(original, Settings.Global.getString(context.getContentResolver(), SETTING))) {
                    status(context, "系统尚未恢复上次准备的设置；恢复记录已保留，请重试取消准备。");
                    return false;
                }
                boolean cleared = journal.edit().remove("pending").remove("original").remove("original_null").commit();
                status(context, cleared ? "上次准备的设置已恢复。" : "设置已恢复，清理记录尚未保存，请重试。");
                return cleared;
            } catch (RuntimeException e) {
                status(context, "恢复上次准备的设置失败：" + e.getClass().getSimpleName() + "；恢复记录已保留。");
                Log.w(TAG, "Journal recovery failed: " + e.getClass().getSimpleName());
                return false;
            }
        }
    }

    private static boolean shizukuAlive() {
        try { return Shizuku.pingBinder(); } catch (RuntimeException e) { return false; }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        nsd = getSystemService(NsdManager.class);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("offline-startup", "手机端启动准备", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "offline-startup")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("L06 手机端启动准备")
                .setContentText("请在 Shizuku 官方界面点击启动；返回 L06 可取消")
                .setContentIntent(open).setOngoing(true).build();
        startForeground(26, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prepared && intent != null && ACTION_PREPARE.equals(intent.getAction())) {
            prepared = true;
            prepare();
        } else if (!active) stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void prepare() {
        if (!recoverPending(this)) { stopSelf(); return; }
        if (!hasSetupPermission(this)) { status(this, "请先完成手机端启动的一次性设置授权。"); stopSelf(); return; }
        if (shizukuAlive()) { status(this, "刚刚确认 Shizuku 已运行，无需准备。"); stopSelf(); return; }
        try { initialSetting = Settings.Global.getString(getContentResolver(), SETTING); }
        catch (RuntimeException e) { status(this, "无法保存初始设置：" + e.getClass().getSimpleName()); stopSelf(); return; }
        started = SystemClock.elapsedRealtime();
        deadline = started + WINDOW_MS;
        nextLog = nextPing = nextConnect = started;
        active = true;
        synchronized (JOURNAL_LOCK) {
            SharedPreferences.Editor edit = prefs(this).edit().putBoolean("pending", true).putBoolean("original_null", initialSetting == null);
            if (initialSetting == null) edit.remove("original"); else edit.putString("original", initialSetting);
            if (!edit.commit()) { error = "初始恢复记录未保存，未改变系统设置"; finish("准备未开始"); return; }
        }
        try {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "L06OfflineStartup:bounded");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WINDOW_MS + 2000);
            discover(++generation);
            handler.postDelayed(timeout, WINDOW_MS);
            nudge();
        } catch (RuntimeException e) { error = e.getClass().getSimpleName(); finish("准备失败"); }
    }

    private void discover(int session) {
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onStartDiscoveryFailed(String type, int code) {
                handler.post(() -> { if (active && generation == session) error = "端口发现暂不可用（" + code + "）"; });
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                handler.post(() -> {
                    if (!active || generation != session) return;
                    try {
                        nsd.resolveService(service, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo info, int code) { }
                            @Override public void onServiceResolved(NsdServiceInfo info) {
                                handler.post(() -> {
                                    if (!active || generation != session || info.getHost() == null) return;
                                    try {
                                        if (info.getHost().isLoopbackAddress()
                                                || NetworkInterface.getByInetAddress(info.getHost()) != null) port = info.getPort();
                                    } catch (Exception ignored) { }
                                });
                            }
                        });
                    } catch (RuntimeException e) { error = "端口解析暂不可用"; }
                });
            }
        };
        try { nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException e) { error = "端口发现暂不可用"; }
    }

    private void nudge() {
        if (!active) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= deadline) { finish("准备窗口已到期"); return; }
        try {
            if (now >= nextPing) {
                nextPing = now + 1000;
                if (shizukuAlive()) { detectedBeforeCleanup = true; finish("检测到 Shizuku 启动"); return; }
            }
            reads++;
            if (Settings.Global.getInt(getContentResolver(), SETTING, 0) == 0) {
                if (SystemClock.elapsedRealtime() >= deadline) { finish("准备窗口已到期"); return; }
                if (Settings.Global.putInt(getContentResolver(), SETTING, 1)) writes++;
                else error = "系统未接受准备请求";
            }
            if (SystemClock.elapsedRealtime() >= deadline) { finish("准备窗口已到期"); return; }
            checkLoopback(now);
            if (now >= nextLog) { report("请在 Shizuku 官方界面点击启动", false); nextLog = now + 1000; }
        } catch (RuntimeException e) { error = "设置授权可能已撤销：" + e.getClass().getSimpleName(); finish("准备中断"); return; }
        handler.postDelayed(tick, Math.min(20, Math.max(1, deadline - SystemClock.elapsedRealtime())));
    }

    private void checkLoopback(long now) {
        if (port <= 0 || port > 65535 || now < nextConnect) return;
        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(previous).permitNetwork().build());
        try {
            if (socket == null) {
                socket = SocketChannel.open();
                socket.configureBlocking(false);
                connectionPort = port;
                connectDeadline = now + 300;
                if (socket.connect(new InetSocketAddress("127.0.0.1", port))) lastTcpSeen = now;
            } else if (socket.finishConnect()) lastTcpSeen = now;
            if (lastTcpSeen == now) {
                tcpSeen = true;
                successfulPort = connectionPort;
                closeSocket();
                nextConnect = now + 1000;
            }
            else if (now >= connectDeadline) { closeSocket(); nextConnect = now + 250; }
        } catch (Exception e) { closeSocket(); nextConnect = now + 250; }
        finally { StrictMode.setThreadPolicy(previous); }
    }

    private void closeSocket() {
        if (socket != null) { try { socket.close(); } catch (Exception ignored) { } socket = null; }
    }

    private void finish(String reason) {
        if (!active) return;
        active = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(timeout);
        try {
            closeSocket();
            if (discovery != null) {
                try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
                discovery = null;
            }
        } finally {
            boolean restored = recoverPending(this);
            if (!restored) error = "设置恢复尚未完成，恢复记录已保留；请完成设置授权后取消或重试";
            try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
            catch (RuntimeException e) { error += "；唤醒锁释放状态待确认"; }
            wakeLock = null;
            report(reason, true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void report(String reason, boolean complete) {
        try {
            String value = Settings.Global.getString(getContentResolver(), SETTING);
            boolean aliveAfter = shizukuAlive();
            boolean journalPending = prefs(this).getBoolean("pending", false);
            JSONObject result = new JSONObject().put("reason", reason).put("active", active)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("requestedWindowMs", WINDOW_MS)
                    .put("reads", reads).put("writes", writes).put("advertisedPort", port)
                    .put("connectedPort", tcpSeen ? successfulPort : 0).put("loopbackTcpEverAvailable", tcpSeen)
                    .put("recentLoopbackConnection", isPortReady())
                    .put("shizukuDetectedBeforeCleanup", detectedBeforeCleanup).put("shizukuRunning", aliveAfter)
                    .put("journalPending", journalPending).put("error", error)
                    .put("initialSetting", initialSetting == null ? JSONObject.NULL : initialSetting)
                    .put("currentSetting", value == null ? JSONObject.NULL : value)
                    .put("persistentSettingChanges", complete ? (Objects.equals(initialSetting, value) ? 0 : 1) : JSONObject.NULL);
            String text = reason + "\n" + (complete ? (aliveAfter ? "清理后刚刚确认 Shizuku 正在运行。" : "清理后尚未确认 Shizuku 运行，请打开官方应用确认。")
                    : (isPortReady() ? "本机端口刚刚可连接，可打开官方 Shizuku 点击启动。"
                    : "等待无线调试端口与官方 Shizuku 启动。"))
                    + "\n经过 " + ((SystemClock.elapsedRealtime() - started) / 1000) + " 秒"
                    + (complete ? "；原设置已恢复：" + Objects.equals(initialSetting, value) + "；恢复记录待处理：" + journalPending : "")
                    + (error.isEmpty() ? "" : "\n" + error);
            prefs(this).edit().putString("status", text).putString("summary", result.toString()).apply();
            Log.i(TAG, result.toString());
        } catch (Exception e) { status(this, reason + "；状态读取失败：" + e.getClass().getSimpleName()); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { finish("已取消准备"); if (instance == this) instance = null; super.onDestroy(); }
}
