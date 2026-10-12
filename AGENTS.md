# 开发交接约定 / Development handoff instructions

[English instructions](#english)

L06 Volume Guard is a device-specific Android app that sets the selected L06 media output to about 25% after reconnection, then leaves volume control to the user.

## 开始工作

先读根目录 `README.md`、`android/BUILD.md` 和 `docs/HANDOFF.md`。当前 Android 基线为 **1.6 / versionCode 7**。`android/` 是手机应用；现有 `macos/` 是独立的旧 20% 工具，保持其用途和行为，除非用户明确要求修改。

以当前源码和实际结果为准。交接说明区分独立验证与用户反馈，不要把用户反馈改写成自动化验收，也不要把设备特定行为推广到所有 Android 手机。

## 必须保持的行为

- Android 目标为 `round(最大媒体音量 × 0.25)`；已测设备为 **38/150，约 25.3%**。
- 仅配置的 L06 确实成为媒体输出时调整 `STREAM_MUSIC`。APP 和 shell 服务都检查实际媒体路由、设备类型和设备地址；连接状态或设备名称不能代替这些检查。
- 保持用户当前选定的耳机和私有设备地址配置，除非用户要求更换目标设备。
- 重连触发一次 **15 秒**校正窗口，处理系统和耳机延迟恢复音量；重复连接提示不能无限延长窗口。窗口结束后保留用户手动调整，断开时停止校正。
- 启动或控制后端恢复时检查已连接的 L06。守护已被用户停用时，开机、覆盖安装、连接广播和重试不得重新开启它。
- 这台 vivo 的后台调音依赖官方 Shizuku 与真实 shell 身份的专用 UserService。仅 Binder 已连接不代表可用，必须确认音频初始化及服务 UID。保持调用 UID 白名单和清除 Binder 调用身份的顺序。
- shell Binder 仅提供固定 L06、固定 25% 的调音与自身状态查询。不要加入任意命令执行、任意设备地址、任意音量目标或通用数据读取接口。
- Shizuku 授权只能走官方用户确认流程；不得改写其授权记录。手机端准备与 Shizuku 官方启动是不同步骤。
- 1.6 的手机端准备由用户点击启动，并请求约 2 分钟的准备窗口；系统冻结可能延迟清理。成功、取消、超时和中断恢复都要清理端口发现、连接、回调、唤醒锁和设置恢复记录。不要把它变成永久重试，也不要承诺重启后自动启动 Shizuku。

## 私有配置与公开仓库

设备地址由构建配置生成 `BuildConfig`，具体配置方式见 `android/BUILD.md`。不要把真实地址写回公开源码、示例、文档、测试或日志。

`android/.local/` 及其他本地私有配置、签名密钥、密码文件、设备序列号、原始设备日志和截图都不得提交或上传。加密的签名密钥仍是私有资料。构建时使用私有输入；覆盖安装沿用原签名。上传前检查实际暂存内容与构建产物，不能只检查 `.gitignore`。

## 修改与验证

有意义的行为变更需要代码审查和对应验证。先跑现有 `GuardWindow` 测试并完成构建，再按改动范围检查：重连、后台或锁屏校正、15 秒后手动调音、非 L06 输出、守护停用、后端恢复，以及手机端准备的取消、超时和恢复清理。不要为简单文档修改操作手机或重复整个验收。

设备操作、手机重启、权限确认和发布应有用户授权；已有明确授权时按范围继续，不要重复索要确认。没有连接到设备时报告验证限制，不编造实机结果。报告中注明软件版本、验证方式和重要边界，不包含设备标识或私有路径。

## English

### Getting started

Read the root `README.en.md`, `android/BUILD.en.md`, and `docs/HANDOFF.en.md` first. The current Android baseline is **1.6 / versionCode 7**. The phone app lives in `android/`; the existing `macos/` utility independently targets 20%. Preserve that utility's purpose and behavior unless the user requests a change.

Use current source and observed results as the baseline. Distinguish independent device verification from user reports. Do not present user feedback as automated acceptance testing or generalize device-specific behavior to all Android phones.

### Behavior to preserve

- The Android target is `round(maximum media volume × 0.25)`: **38/150, approximately 25.3%**, on the tested device.
- Adjust `STREAM_MUSIC` only when the configured L06 is actually the media output. Both the app and shell service check the media route, device type, and address; a connection event or device name cannot replace those checks.
- Preserve the user's selected headset and private address configuration unless the user requests another target.
- Reconnection starts one **15-second** correction window to handle delayed volume restoration. Duplicate hints must not extend it indefinitely. Respect manual changes afterward and stop correction on disconnection.
- Check an already-connected L06 when monitoring starts or the control backend recovers. Boot, package replacement, connection broadcasts, and retries must preserve the user's disabled-guard preference.
- Background volume control on this vivo requires official Shizuku and a dedicated UserService running with the actual shell identity. A connected Binder alone is insufficient: validate audio initialization and service UID. Preserve caller UID allowlisting and its ordering before clearing the Binder calling identity.
- The shell Binder exposes only the fixed L06/25% operation and its own status queries. Do not add arbitrary shell execution, caller-supplied addresses or volume targets, or general data access.
- Request Shizuku authorization through its official user confirmation flow. Never rewrite authorization records. Phone-side preparation and starting official Shizuku are separate steps.
- Version 1.6 preparation starts from a user click and requests an approximately two-minute preparation window; system freezing can delay cleanup. Success, cancellation, timeout, and interrupted-operation recovery must clean up discovery, connections, callbacks, wake locks, and settings recovery records. Do not turn this into indefinite retries or promise automatic Shizuku startup after reboot.

### Private configuration and the public repository

Generate `BuildConfig` from the device-address build configuration described in `android/BUILD.en.md`. Never put the real address into public source, examples, documentation, tests, or logs.

Do not commit or upload `android/.local/`, other private configuration, signing keys, password files, device serial numbers, raw device logs, or screenshots. An encrypted signing key is still private. Build with private inputs and retain the original signing identity for in-place updates. Inspect staged content and build artifacts before uploading; checking `.gitignore` alone is insufficient.

### Changes and verification

Review meaningful behavioral changes and verify the affected paths. Run the existing `GuardWindow` tests and build first. As appropriate, check reconnection, background or lock-screen correction, manual volume changes after 15 seconds, other media outputs, guard disabling, backend recovery, and preparation cancellation, timeout, and recovery cleanup. Do not operate the phone or repeat full device acceptance testing for a simple documentation edit.

Device operations, reboots, permission confirmation, and publishing require user authorization. Continue within existing explicit authorization without asking again. When no device is connected, report the validation limits instead of inventing device results. Reports should identify software version, verification method, and material limits without exposing device identifiers or private paths.
