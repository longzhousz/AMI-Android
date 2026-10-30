# 本地构建与 USB 真机调试

本工程可以在 Windows、macOS 或 Linux 上独立开发。将项目迁到电脑后，本地开发助手可以通过该电脑的 ADB 操作已授权的手机，不再需要云端隧道。

## 获取完整代码

使用有权限访问此私有仓库的 GitHub 账号克隆：

```sh
git clone https://github.com/longzhousz/AMI-Android.git
cd AMI-Android
git branch --show-current
```

输出应为 `main`。它包含 0.1.19 及其依赖的全部前序改动，不需要逐个合并 PR。不能使用 Git 时，直接在 GitHub 默认分支选择 Code → Download ZIP。

已经克隆过且工作区干净时：

```sh
git fetch origin
git switch main
git pull --ff-only
```

## 工具链

| 项目 | 要求 |
| --- | --- |
| Java / Gradle JDK | JDK 21 |
| Gradle | 9.6.0，使用自带 Wrapper |
| Android Gradle Plugin | 9.4.1，Android Studio 版本需支持它 |
| Android SDK Platform | `platforms;android-37.0`，compile / target API 37 |
| Android Build Tools | `build-tools;36.0.0` |
| 手机连接 | `platform-tools` 中的 ADB |
| Python | Python 3，仅诊断脚本需要 |

无需 Rust、Node、NDK、云端代理或本地后端服务。普通音乐播放不需要 DeepSeek 密钥。

推荐在 Android Studio 打开**仓库根目录**，在 SDK Manager 安装以上 SDK 和工具，在 Gradle 设置中选 JDK 21，然后同步工程。首次下载 Gradle、插件与依赖需要网络；镜像、代理或 JDK 不匹配导致的同步失败应先处理环境，不要直接改项目的版本锁定。

命令行也可安装 SDK：

```sh
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
sdkmanager --licenses
```

Windows 对应命令为 `sdkmanager.bat`。让 `JAVA_HOME` 指向 JDK 21，并将其 `bin` 与 SDK 的 `platform-tools` 加到 PATH。SDK 路径通过 `ANDROID_HOME` 或根目录 `local.properties` 提供；Android Studio 通常会生成后者。

以下仅为本机配置示例，不提交到 Git：

```properties
# Windows 使用正斜杠，盘符冒号转义以通过 Lint；替换为实际用户名与路径
sdk.dir=C\:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

macOS 常见路径为 `/Users/YOUR_NAME/Library/Android/sdk`，Linux 常见路径为 `/home/YOUR_NAME/Android/Sdk`，以实际安装位置为准。不要复制云端的 `/workspace` 路径或代理参数。

## 构建

macOS / Linux：

```sh
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

可安装的调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。需要检查压缩、资源缩减后的行为时运行 `assemblePreview`，产物为 `app/build/outputs/apk/preview/app-preview.apk`。

| 构建类型 | 包名 | 适用场景 |
| --- | --- | --- |
| Debug | `com.longzhousz.ami` | Android Studio 调试、查看应用私有数据、定位封面读取 |
| Preview | `com.longzhousz.ami.preview` | 压缩版体验、系统媒体接入；不可调试 |
| Release | `com.longzhousz.ami` | 正式发行；默认产物未签名 |
| PhonePreview | `com.longzhousz.ami.phonepreview` | 只用手机的网易云随播，`assemblePhonePreview` |
| MusicPreview | `com.longzhousz.ami.musicpreview` | 当前云端交付的在线版，`assembleMusicPreview` |
| NeteasePreview | `com.longzhousz.ami.neteasepreview` | 保留旧本地签名安装的构建类型，`assembleNeteasePreview` |
| CloudPreview | `com.longzhousz.ami.cloudpreview` | 历史 Windows 连接端试验，`assembleCloudPreview` |

当前云端交付使用 MusicPreview 包，输出为 `app/build/outputs/apk/musicPreview/app-musicPreview.apk`。无需电脑服务、局域网权限或通知使用权；官方登录操作由用户在手机上完成，不通过 ADB 输入或导出登录凭据。详细流程与边界见 [使用说明](USER_GUIDE.md)。其余试验构建类型仍保留源码，历史使用和验证记录统一位于 [archive/](archive/README.md)。

Preview 使用本机开发签名。不同电脑或云端的开发签名通常不同，`adb install -r` 也不能绕过签名检查。Debug 与现有 Preview 包名不同，通常可以并存；如果手机已经有同包名的正式版，也要先核对签名。

**不要为了解决 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 卸载现有应用或清除数据。** 现有曲库、收藏、译文和文件夹授权在应用私有存储中，卸载会丢失。要覆盖现有 Preview，需另行安全取得原开发签名；本仓库不包含该密钥。可先保持现有 Preview，另装 Debug 并仅导入少量样本。

Release 签名仍使用 `AMI_KEYSTORE_PATH`、`AMI_KEYSTORE_PASSWORD`、`AMI_KEY_ALIAS`、`AMI_KEY_PASSWORD`，见 [README 构建说明](../README.md#构建)。本地调试不需要这些变量。

## 连接 vivo X300 / OriginOS 7

1. 手机开启开发者选项与 USB 调试，通过可传数据的 USB 线连接电脑。
2. 手机解锁并确认电脑的调试授权。Windows 如无法识别设备，检查 ADB 驱动；Linux 检查 USB 设备权限。
3. 运行 `adb devices -l`，确认状态为 `device`。`unauthorized` 表示手机尚未确认授权，`offline` 也不能开始调试。
4. 后续命令将 `YOUR_SERIAL` 换成这一设备的序列号；连接多台设备时始终指定目标。

先检查手机上已有的安装，无需覆盖安装：

```sh
adb -s YOUR_SERIAL shell pm list packages com.longzhousz.ami
```

需要独立 Debug 安装时，确认包名与签名后执行：

```sh
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_SERIAL shell am start -n com.longzhousz.ami/.MainActivity
```

在 Debug 应用中通过「设置 → 导入音乐」授权样本文件夹；旧 Preview 的 SAF 文件夹权限不会自动转给另一个包名。可以直接在 Android Studio 选择手机和 Debug 变体运行，或用可选的 scrcpy 查看/控制屏幕；ADB 本身已能截图、点击、滑动、读取界面与日志。

项目已有 UI 树助手，以下命令适用于当前屏幕：

```sh
python3 qa/ui.py YOUR_SERIAL dump
python3 qa/ui.py YOUR_SERIAL tap "设置"
```

Windows 安装 Python 3 后可将 `python3` 替换为 `py -3`。本地开发助手的终端也需能找到 `adb`。连接后它仍需读取当前界面再操作，不根据旧截图盲点。

## 采集诊断

先在手机播放一首待排查歌曲。在仓库根目录为**当前云端在线包**采集：

```sh
python3 .github/scripts/ami-device-check.py --serial YOUR_SERIAL --package com.longzhousz.ami.musicpreview --scenario snapshot --output artifacts/vivo-snapshot
python3 .github/scripts/ami-device-check.py --serial YOUR_SERIAL --package com.longzhousz.ami.musicpreview --scenario island --output artifacts/vivo-island
```

调试 Debug 时将 `--package` 改为 `com.longzhousz.ami`；其他安装先查实际包名。脚本只采集状态，不点击、不安装、不清数据、不替用户修改设置。`island` 是证据分类，不会自动判断原子岛成功。

熄屏播放采集使用同一包名，先由用户开始播放并熄屏，再运行：

```sh
python3 .github/scripts/ami-device-check.py --serial YOUR_SERIAL --package com.longzhousz.ami.musicpreview --scenario screen-off --duration 1800 --output artifacts/vivo-screen-off
```

蓝牙、来电与进程恢复使用 `--scenario bluetooth`、`call`、`recovery`。实际插拔耳机、接听电话或回收操作由用户执行，脚本只保存证据。标准媒体面板、厂商原子岛、真实耳机输出分别记录；USB 供电与调试会影响耗电观察。

针对原子岛的简单日志查看：

```sh
adb -s YOUR_SERIAL logcat -d -s AmiOriginOS
```

现有 Preview 不可使用 `run-as` 读取应用私有数据库。需要看私有封面文件或数据库时，使用独立 Debug 样本安装；不要要求 root 或以清数据换取可调试状态。缺图排查顺序见 [交接文档](LOCAL_HANDOFF.md#缺封面排查)。

采集产物放在 Git 已忽略的 `artifacts/`。系统日志可能带曲名、路径或其他应用信息，分享前检查内容。

## 自动化测试边界

JVM 单元测试和 Lint 无需手机。完整 `connectedDebugAndroidTest` 会写入夹具、清理/重建测试曲库并操作播放，应在专用模拟器或独立测试安装上运行，避免影响日常曲库。

只有一个专用测试设备连接时运行：

```sh
./gradlew connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`。CI 另有需要 AOSP userdebug / root 的进程回收夹具，不要直接在零售版 X300 上运行整套 CI 主机脚本。当前自动化结果见 [播放流程](PLAYBACK_FLOW.md)，厂商能力边界见 [OriginOS 兼容性](ORIGINOS_COMPATIBILITY.md)。历史夹具原理见 [旧设备验证记录](archive/ANDROID_DEVICE_QA.md)。
