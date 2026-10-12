# 构建 Android 1.6

应用要求 Android 13（API 33）及以上。构建需要 JDK 17 或以上、Android SDK Platform 34、Build Tools 34.0.0，以及 `curl`、`unzip`、`zip`、`sha256sum` 或 `shasum`。项目包含固定版本 SDK 依赖；`lib/` 缺失时会下载 Shizuku API 13.1.5 和 AndroidX annotation 1.3.0。

在本目录执行，按本机安装位置设置：

```sh
export L06_JAVA="$JAVA_HOME"
export L06_SDK="$ANDROID_SDK_ROOT"
```

SDK 中须已安装 `platforms;android-34` 和 `build-tools;34.0.0`。macOS Homebrew 的默认路径已写在 `build.sh` 中，其他安装位置用以上变量覆盖。

默认 D8 固定为 9.3.16：脚本从[Google 官方发布地址](https://storage.googleapis.com/r8-releases/raw/9.3.16/r8lib.jar)下载到 `build/tooling/`，核验 SHA-256，再通过 Java 调用。此版本在 JDK 27 上已构建通过；Build Tools 34 自带的旧 D8 在该环境出现内部错误，因此不作为默认。可通过 `L06_D8=/path/to/d8` 显式覆盖。[官方运行方式](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#running-d8)、[R8 许可证](https://r8.googlesource.com/r8/+/65eb2ed58d2ac1dbce414b51a8ea877c3ce5f68a/LICENSE)。下载的构建工具不提交到仓库，也不包含在手机 APK 中。

## 选择自己的耳机

先用 `adb devices -l` 找到自己的手机，再设置 `PHONE_SERIAL` 为它的序列号。连接耳机并将媒体输出切换到该耳机后，可只读查看当前音频设备地址：

```sh
adb -s "$PHONE_SERIAL" shell dumpsys audio
```

核对目标蓝牙 A2DP 设备的地址。地址必须是六组十六进制字节，用英文冒号分隔。选择一种配置方式：

- 将地址设为环境变量 `L06_BLUETOOTH_ADDRESS`，它优先于本地文件。
- 在 `.local/headset-address.txt` 中仅保存地址一行。

```sh
mkdir -p .local
# 用编辑器填写 .local/headset-address.txt，再限制文件权限。
chmod 600 .local/headset-address.txt
```

缺失、空白或格式不正确的地址会让构建在编译前退出。`build.sh` 为应用和 Shizuku 服务生成同一个 `build/v1.6/generated/BuildConfig.java`。这只配置构建时的目标耳机，应用运行时仍会核对实际媒体输出。

## 签名与构建

使用自己的 PKCS12 签名文件，别名必须是 `l06volume`。新项目可创建一个签名；升级已安装应用必须继续使用原签名：

```sh
"$L06_JAVA/bin/keytool" -genkeypair -alias l06volume -keyalg RSA -keysize 2048 \
  -validity 10000 -storetype PKCS12 -keystore .local/signing.p12
```

在提示中设置密码。用编辑器创建仅包含同一密码一行的 `.local/signing-password.txt`，设为仅本人可读，再构建：

```sh
chmod 600 .local/signing.p12 .local/signing-password.txt
./build.sh .local/signing.p12 .local/signing-password.txt
```

可用第三个参数指定 APK 输出路径。默认输出为 `build/v1.6/L06-Volume-Guard-1.6.apk`；脚本先运行 GuardWindow 测试，再编译、打包、签名并验证签名。

`.local/`、`build/` 和生成的 APK 应保持本地。APK 中包含构建时的耳机地址；不要把自己的 APK、签名文件、密码、手机序列号或设备日志上传到公开仓库。

## 在自己的手机安装

确认 `PHONE_SERIAL` 指向自己的手机。手机端须另行安装官方 Shizuku，并由本人在它的官方界面启动、授权；应用不会自动授予权限。

```sh
adb -s "$PHONE_SERIAL" install -r build/v1.6/L06-Volume-Guard-1.6.apk
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.BLUETOOTH_CONNECT
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.POST_NOTIFICATIONS
# 仅使用“手机端启动准备”功能时，一次性授予以下权限。
adb -s "$PHONE_SERIAL" shell pm grant com.codex.l06volume android.permission.WRITE_SECURE_SETTINGS
```

打开 L06 音量守护，点击“启动 / 恢复自动守护”，按系统提示完成 Shizuku 授权。只在确认目标耳机为媒体输出时验证约 25% 的效果。手机端准备功能的个人热点、配对和取消步骤见应用内说明。
