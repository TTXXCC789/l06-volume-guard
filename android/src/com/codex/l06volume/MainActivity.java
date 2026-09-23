package com.codex.l06volume;

import android.Manifest;
import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 1;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 32);
        TextView title = new TextView(this);
        title.setText("L06 音量守护");
        title.setTextSize(24);
        root.addView(title);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 28, 0, 0);
        root.addView(status);
        Button test = new Button(this);
        test.setText("诊断：现在设为 25%");
        test.setOnClickListener(view -> {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = Math.round(max * 0.25f);
            int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            status.postDelayed(() -> {
                int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                status.append("\n诊断：" + before + " → " + after + "，目标 " + target + "/" + max);
            }, 300);
        });
        root.addView(test);
        Button enable = new Button(this);
        enable.setText("启用后台音量服务");
        enable.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable);
        setContentView(root);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        }
        refresh();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        refresh();
    }

    private void refresh() {
        boolean permitted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean serviceEnabled = false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())
                    && info.getResolveInfo().serviceInfo.name.endsWith("L06VolumeAccessibilityService")) {
                serviceEnabled = true;
            }
        }
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * 0.25f);
        status.setText(!permitted
                ? "请授予“附近设备”权限，才能识别 L06 连接。"
                : serviceEnabled
                    ? "后台服务已启用。L06 每次连接后媒体音量设为 " + target + "/" + max
                      + "（约 " + Math.round(target * 100f / max) + "%）。之后可自由调节。\n\n此服务不读取屏幕内容。"
                    : "请点击“启用后台音量服务”，在系统设置中启用 L06 音量守护。\n\n服务只设置音量，不读取屏幕内容。");
    }
}
