# AMI Android

网易云在线音乐与沉浸式歌词播放器，保留本地音乐支持。运行只需要手机，无需电脑连接端或 root。Kotlin、Jetpack Compose、Media3；最低 Android 8.0（API 26），compile / target API 37。

## 下载与使用

当前版本 **0.1.18**（versionCode 19）。

**[下载最新安装包](https://github.com/longzhousz/AMI-Android/releases/latest)** · [使用说明](docs/USER_GUIDE.md)

安装发布页的 `AMI-Android-0.1.18-online-preview.apk`。它与此前云端在线版沿用同一包名、签名，可覆盖 0.1.14～0.1.17 并保留数据；其他包名或本地签名安装的兼容规则见使用说明。

- 「曲库」展示网易云收藏专辑与自建歌单；「我喜欢」展示红心歌曲。下拉或双击导航图标主动刷新。
- 新选歌曲默认封面页，同一首歌收起重开记住封面／歌词。封面支持连续缩放与下拉交接，歌词可点句跳转。
- 常听歌曲自动缓存，最近使用的封面按需保留；容量可在设置调整，后台补齐与清理避开前台操作。
- 翻译按需使用 DeepSeek，可自动跟随最新模型；默认不使用网易云自带译文。
- 浏览过的封面回滚时立即复用轻量预览；支持真实逐字歌词、私人 FM／每日推荐，以及专辑封面共享转场。见 [本轮体验说明](docs/LISTENING_EXPERIENCE.md)。

## 文档

| 内容 | 入口 |
| --- | --- |
| 当前文档索引 | [docs/README.md](docs/README.md) |
| 手机使用、音质、缓存与本地音乐 | [USER_GUIDE.md](docs/USER_GUIDE.md) |
| 当前交接与验证范围 | [LOCAL_HANDOFF.md](docs/LOCAL_HANDOFF.md) |
| 本地构建、签名与 USB 调试 | [LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md) |
| 当前播放流程 | [PLAYBACK_FLOW.md](docs/PLAYBACK_FLOW.md) |
| 历史版本与旧试验 | [docs/archive/](docs/archive/README.md) |

main 维护当前源码与文档，安装包放在 Releases。旧版本说明统一归档，Git 提交历史保留。

## 构建

使用 JDK 21、仓库 Gradle Wrapper、Android SDK `platforms;android-37.0` 和 Build Tools `36.0.0`，在仓库根目录执行：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleMusicPreview
```

Windows 使用 `gradlew.bat`。在线预览包位于 `app/build/outputs/apk/musicPreview/app-musicPreview.apk`；本机开发签名通常不能覆盖云端安装包，不要通过卸载或清除数据解决签名冲突。

正式 Release 默认未签名；配置 `AMI_KEYSTORE_PATH`、`AMI_KEYSTORE_PASSWORD`、`AMI_KEY_ALIAS`、`AMI_KEY_PASSWORD` 后可运行 `assembleRelease bundleRelease`。密钥、签名文件、私人媒体和设备日志不提交 Git。完整工具链与构建类型见本地开发文档。

## 来源

源自 [AMI](https://github.com/longzhousz/AMI) 的 Android 工程，在此独立维护；不依赖桌面仓库。第三方协议参考与许可见 [THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
