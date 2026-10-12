# L06 音量守护

[English](README.en.md) · [构建说明](android/BUILD.md) · [开发交接](docs/HANDOFF.md)

**核心功能：配置的 L06 蓝牙耳机每次重新成为手机媒体输出后，将音量校正到约 25%，之后允许手动调整。** Android 当前版本为 **1.6 / versionCode 7**，要求 Android 13 及以上，已在 vivo / Android 16 上验证。

## 手机重启后的操作

首次安装先按[构建说明](android/BUILD.md)配置耳机，安装并配对官方 Shizuku，完成一次性设置授权，并允许两个应用后台运行。

1. 解锁手机，开启**手机自己的个人热点**，不需要另一台设备加入。
2. 打开 L06 音量守护，点击“手机端启动准备”。
3. 等待“打开 Shizuku”按钮可用，点击它，再在官方 Shizuku 中点击“Start / 启动”。
4. 返回 L06，确认“Shizuku：已就绪”和“后台音量守护：运行中”。
5. 可以关闭个人热点。L06 断开后重连仍会校正到约 25%。

热点仅用于这套启动流程，不需要为每次蓝牙重连开启。拔掉 USB 时，如果 Shizuku 仍在运行，也无需重新准备。首次或配对失效时需要官方配对。此功能是手动恢复，不承诺 Shizuku 自动开机启动。

用户确认了拔掉 USB 后重启、仅使用移动数据和自身热点启动，以及关闭热点后重连仍约 25%。这些是用户实测反馈，独立记录与边界见[交接说明](docs/HANDOFF.md)。其他机型和系统更新后需要重新验证。

## 实际行为

- 已测目标为 **38/150，约 25.3%**，按最大媒体音量的 25% 四舍五入。
- APP 与专用 Shizuku 服务都核对配置地址和实际媒体路由。扬声器或其他耳机不触发调音。
- 重连后的 **15 秒**内有限重试，处理晚到的音量恢复；之后不锁定音量。
- vivo 上通过官方 Shizuku 的专用 UserService，以真实 shell 身份调音。服务只提供固定耳机的固定目标操作与自身状态查询。
- 准备由用户点击，约两分钟窗口，成功、取消或超时后恢复原设置；中断后保留恢复记录。

系统可能短暂恢复较高音量后再被校正，因此不是全程不超过 25% 的硬性上限。没有实际声压或长期电池消耗测量。

## 项目布局与构建

| 路径 | 用途 |
| --- | --- |
| `android/` | 1.6 手机应用、测试、构建脚本和依赖许可证 |
| `docs/` | 中英文交接与验证说明 |
| `macos/` | 原有独立 macOS 工具，切换到 L06 时设置 20%，本次保留 |
| `AGENTS.md` | 新 Codex 项目或会话的开发约定 |

新 Codex 项目直接选择本仓库文件夹，先阅读 README、构建说明与交接文档。

通过 `L06_BLUETOOTH_ADDRESS` 或 `android/.local/headset-address.txt` 配置自己的耳机。公开源码不包含个人地址，APK 使用自己的地址和签名构建。需要 JDK 17+、Android SDK Platform 34、Build Tools 34.0.0；脚本固定使用 D8 9.3.16，并下载缺失工具。详见[中文](android/BUILD.md) / [English](android/BUILD.en.md) 构建说明。私有配置留在 `.local/`；公开仓库不发布设备定制 APK。

## macOS 旧工具

运行 `macos/install.sh`，需要 Xcode Command Line Tools。LaunchAgent 监测默认 CoreAudio 输出，切换到 L06 时设置 20%，保留之后的手动修改。它与手机应用独立。

## 来源与许可

[官方 Shizuku](https://github.com/RikkaApps/Shizuku)、[Shizuku API](https://github.com/RikkaApps/Shizuku-API)、[Android 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types)、[R8 / D8](https://r8.googlesource.com/r8/+/refs/heads/main/README.md)。第三方 SDK 许可证随 Android 项目提供。项目自身暂未声明开源许可证；公开可见不等于授予代码复用许可。
