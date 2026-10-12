package com.codex.l06volume;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ScrollView;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private TextView status;
    private Button permission;
    private Button notificationPermission;
    private TextView offlineStatus;
    private Button offlinePrepare, offlineOpen;
    private boolean startAfterPermission;
    private boolean resumed;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable offlineRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshOfflineStatus();
            uiHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable shizukuStateListener = () -> runOnUiThread(() -> {
        if (!resumed) return;
        bindShizukuIfAvailable();
        refresh();
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        OfflineStartupService.recoverPending(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        root.setPadding((int) (24 * density), (int) (32 * density),
                (int) (24 * density), (int) (16 * density));
        TextView title = new TextView(this);
        title.setText("L06 音量守护 1.6");
        title.setTextSize(24);
        root.addView(title);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 28, 0, 0);
        root.addView(status);
        Button recover = new Button(this);
        recover.setText("启动 / 恢复自动守护");
        recover.setOnClickListener(view -> startOrRecoverGuard());
        root.addView(recover);
        Button apply = new Button(this);
        apply.setText("立即将 L06 设为 25%");
        apply.setOnClickListener(view -> {
            if (!hasBluetoothPermission()) {
                Toast.makeText(this, "请先授予附近设备权限。", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!VolumeTarget.isL06MediaRoute(this)) {
                Toast.makeText(this, "请先连接 L06，并将媒体声音切换到 L06。", Toast.LENGTH_SHORT).show();
                refresh();
                return;
            }
            boolean applied = VolumeTarget.apply(this, "manual");
            refresh();
            Toast.makeText(this, applied ? "已将 L06 媒体音量设为约 25%。"
                    : "未能设置音量，请确认 L06 仍为媒体输出。", Toast.LENGTH_SHORT).show();
        });
        root.addView(apply);
        permission = new Button(this);
        permission.setText("授予附近设备权限");
        permission.setOnClickListener(view -> requestBluetoothPermission());
        root.addView(permission);
        Button disable = new Button(this);
        disable.setText("停用后台音量守护");
        disable.setOnClickListener(view -> {
            startAfterPermission = false;
            VolumeGuardService.stop(this);
            refresh();
            status.postDelayed(this::refresh, 500);
        });
        root.addView(disable);
        notificationPermission = new Button(this);
        notificationPermission.setText("显示守护通知（可选）");
        notificationPermission.setOnClickListener(view -> requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS));
        root.addView(notificationPermission);
        Button background = new Button(this);
        background.setText("后台运行设置");
        background.setOnClickListener(view -> {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(details);
                Toast.makeText(this, "可在应用设置中允许后台运行和自启动。", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, "请在系统应用设置中查看 L06 音量守护。", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(background);
        TextView offlineTitle = new TextView(this);
        offlineTitle.setText("仅用手机恢复 Shizuku");
        offlineTitle.setTextSize(20);
        offlineTitle.setPadding(0, (int) (20 * density), 0, 0);
        root.addView(offlineTitle);
        offlineStatus = new TextView(this);
        offlineStatus.setTextSize(14);
        root.addView(offlineStatus);
        offlinePrepare = new Button(this);
        offlinePrepare.setText("手机端启动准备（约 2 分钟窗口）");
        offlinePrepare.setOnClickListener(view -> {
            if (!OfflineStartupService.hasSetupPermission(this)) {
                Toast.makeText(this, "此功能需在首次安装时完成一次性设置授权。", Toast.LENGTH_LONG).show();
                return;
            }
            if (!OfflineStartupService.recoverPending(this)) { refreshOfflineStatus(); return; }
            try { OfflineStartupService.start(this); }
            catch (RuntimeException exception) {
                Toast.makeText(this, "暂时无法开始准备，请保持本应用在前台后重试。", Toast.LENGTH_LONG).show();
            }
            uiHandler.postDelayed(this::refreshOfflineStatus, 500);
        });
        root.addView(offlinePrepare);
        offlineOpen = new Button(this);
        offlineOpen.setText("打开 Shizuku");
        offlineOpen.setOnClickListener(view -> {
            if (OfflineStartupService.isPortReady() || ShizukuBridge.isRunning()) openShizuku();
            else Toast.makeText(this, "请打开自己的个人热点，开始准备并等待本机端口就绪。", Toast.LENGTH_LONG).show();
        });
        root.addView(offlineOpen);
        Button offlineCancel = new Button(this);
        offlineCancel.setText("取消手机端启动准备");
        offlineCancel.setOnClickListener(view -> {
            OfflineStartupService.cancel(this);
            refreshOfflineStatus();
            uiHandler.postDelayed(this::refreshOfflineStatus, 500);
        });
        root.addView(offlineCancel);
        TextView offlineHelp = new TextView(this);
        offlineHelp.setText("先打开手机自己的个人热点，再点击准备。显示本机端口可连接后，打开 Shizuku，在官方界面点击“启动”；已配对可直接启动，仅首次或配对失效时需要重新配对。\n\n"
                + "无需外部 Wi-Fi。准备过程会保留服务通知，离开本页后继续。请求约 2 分钟准备窗口；系统若冻结应用，结束清理可能延后，可返回此页取消。此功能不会开启你已停用的音量守护。");
        offlineHelp.setTextSize(14);
        root.addView(offlineHelp);
        TextView explanation = new TextView(this);
        explanation.setText("L06 连接后 15 秒内校正至约 25%，之后可手动调整。自动调整需要后台守护和 Shizuku 都已就绪。\n\n"
                + "重启后会尝试恢复已开启的监测；你主动停用的守护不会自动开启。若 Shizuku 未启动，请先启动它，再返回本应用。\n\n"
                + "手机重启后，可按上方“仅用手机恢复 Shizuku”的步骤启动。完成后，已开启的守护会重新连接。\n\n"
                + "通知权限为可选，拒绝不会阻止守护。后台服务也可在系统的运行中服务里查看。强行停止应用后需重新打开。");
        explanation.setTextSize(14);
        explanation.setPadding(0, (int) (16 * density), 0, 0);
        root.addView(explanation);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        uiHandler.removeCallbacks(offlineRefresh);
        uiHandler.post(offlineRefresh);
        ShizukuBridge.addStateListener(shizukuStateListener);
        bindShizukuIfAvailable();
        if (hasBluetoothPermission() && VolumeGuardService.isEnabled(this)) {
            VolumeGuardService.ensureStarted(this);
            status.postDelayed(this::refresh, 500);
        }
        refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(offlineRefresh);
        ShizukuBridge.removeStateListener(shizukuStateListener);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_BLUETOOTH) {
            boolean requestedStartup = startAfterPermission;
            startAfterPermission = false;
            if (hasBluetoothPermission()) {
                if (requestedStartup) {
                    startOrRecoverGuard();
                } else if (VolumeGuardService.isEnabled(this)) {
                    VolumeGuardService.ensureStarted(this);
                    bindShizukuIfAvailable();
                    status.postDelayed(this::refresh, 500);
                }
            }
        }
        refresh();
    }

    private void startOrRecoverGuard() {
        OfflineStartupService.recoverPending(this);
        if (!hasBluetoothPermission()) {
            startAfterPermission = true;
            requestBluetoothPermission();
            return;
        }
        VolumeGuardService.start(this);
        if (!ShizukuBridge.isRunning()) {
            openShizuku();
        } else if (!ShizukuBridge.isGranted()) {
            // This flow starts only from the explicit startup button, including its permission reply.
            ShizukuBridge.requestPermission();
        } else {
            bindShizukuIfAvailable();
        }
        refresh();
        status.postDelayed(this::refresh, 500);
    }

    private void bindShizukuIfAvailable() {
        if (VolumeGuardService.isEnabled(this) && ShizukuBridge.isRunning()
                && ShizukuBridge.isGranted() && !ShizukuBridge.isReady()) {
            ShizukuBridge.bind(this);
        }
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch != null) {
            try {
                startActivity(launch);
                Toast.makeText(this, "请先在 Shizuku 中启动服务，再返回授权。", Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException exception) {
                // Fall through to the same guidance if the launcher is unavailable.
            }
        }
        Toast.makeText(this, "请打开手机上的 Shizuku，启动服务后返回授权。", Toast.LENGTH_LONG).show();
    }

    private boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
    }

    private void refreshOfflineStatus() {
        if (offlineStatus == null) return;
        offlineStatus.setText(OfflineStartupService.getStatus(this));
        offlinePrepare.setEnabled(!OfflineStartupService.isPreparing());
        offlineOpen.setEnabled(OfflineStartupService.isPortReady() || ShizukuBridge.isRunning());
    }

    private void refresh() {
        boolean permitted = hasBluetoothPermission();
        boolean running = VolumeGuardService.isRunning();
        boolean serviceEnabled = VolumeGuardService.isEnabled(this);
        boolean shizukuRunning = ShizukuBridge.isRunning();
        boolean shizukuGranted = shizukuRunning && ShizukuBridge.isGranted();
        boolean shizukuReady = shizukuGranted && ShizukuBridge.isReady();
        String shizukuState = !shizukuRunning ? "未启动"
                : !shizukuGranted ? "待授权" : shizukuReady ? "已就绪" : "连接中";
        boolean l06Route = permitted && VolumeTarget.isL06MediaRoute(this);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = VolumeTarget.targetIndex(max);
        String automatic = !serviceEnabled ? "已停用，无法自动调整"
                : !permitted ? "等待附近设备权限"
                : !running ? "未运行，点击恢复"
                : shizukuReady ? "运行中" : "监测运行，自动降音量未就绪";
        status.setText("附近设备权限：" + (permitted ? "已授予" : "未授予")
                + "\nShizuku：" + shizukuState
                + "\n后台音量守护：" + automatic
                + "\n媒体输出：" + (l06Route ? "L06" : permitted ? "当前未使用 L06" : "等待权限")
                + "\n目标音量：" + target + "/" + max + "（约 25%）"
                + (l06Route ? "\n当前音量：" + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + max : "")
                + "\n");
        permission.setVisibility(permitted ? android.view.View.GONE : android.view.View.VISIBLE);
        notificationPermission.setVisibility(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED ? android.view.View.GONE : android.view.View.VISIBLE);
        refreshOfflineStatus();
    }
}
