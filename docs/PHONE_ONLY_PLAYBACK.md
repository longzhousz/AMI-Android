# 只用手机：AMI 网易云随播试用

2026-10-27。0.1.12 / versionCode 13，数据库 schema 4。运行时只需要同一手机上的网易云音乐 App 与 AMI，无需电脑连接端或 root。

## 使用

1. 安装独立的「AMI 随播试用」，包名 `com.longzhousz.ami.phonepreview`，保留已有 AMI 及其数据。
2. 点击「连接手机上的网易云」，在系统页面批准通知使用权。若系统禁用开关，按提示在应用信息页面允许受限设置，再开启。这里只使用网易云音乐会话，不读取或保存聊天等普通通知正文。
3. 在网易云选择歌单、搜索并播放，再回 AMI。显示原有歌词页、封面连续转场、暂停、上下首及可用的进度控制。底部「网易云」可继续选歌，齿轮进入原设置。

歌单、搜索和账号留在网易云；音质、会员与可播放范围由官方 App 决定。系统音乐卡片仍属于网易云，不能据此宣称 AMI 获得原子岛准入。AMI 不缓存随播音频，网易云自己的缓存由其设置管理。

## 歌词

标准媒体会话**不保证提供整首同步歌词**，本版不依赖读取网易云私有目录、登录凭据或内部音频地址。

优先复用 AMI 已导入的歌词：可识别歌曲 ID 时按 ID 查询，否则按歌名、歌手和接近的时长匹配本地歌曲；不加载整套曲库正文。缺少时直接访问 [LRCLIB 公开接口](https://lrclib.net/docs)，只发送歌名、歌手、专辑和时长。结果再次校验，排除明显不同版本；同步歌词优先，只有纯文本时显示普通歌词。

公开库覆盖不足、网络不可达或元数据不全时显示「暂无歌词」，音乐继续由网易云播放。不保证与网易云原词相同，不猜测时间轴。需要翻译时，在设置配置自己的 DeepSeek Key 后主动触发，沿用原有模型预设，不显示生成进度或任务面板。

独立包不能自动读取旧包的私有歌词、收藏和密钥。可在设置授权少量歌词样本或网易云导出的歌词文件夹，无需为随播重新导入整套音频；不要卸载旧包或清数据解决签名差异。

## 方案依据

[NeriPlayer](https://github.com/cwuom/NeriPlayer) 和 [MeloX Android](https://github.com/lladlam/MeloX-Android) 展示了手机端第三方客户端的能力，但完整接管账号、歌单与播放需要另一套平台接口维护，且代码使用 GPL-3.0，不能未经许可核对就整包复制。

本版独立实现标准 `MediaSessionManager` / `MediaController.TransportControls` 随播连接，复用 AMI 的 `PlayerContent`。类似的无 root 取曲目方式见 [desktop-lyrics](https://github.com/tcrrry/desktop-lyrics)。没有复制这些项目的代码，也没有使用第三方网易云逆向 API 或开放平台私钥。

## 实现与构建

- `playback/PhoneSession.kt` 仅接受 `com.netease.cloudmusic`；依靠回调更新，按真实控制能力启用按钮。帧时钟只读本地快照，不逐帧跨进程查询或网络轮询。
- `PhoneMusicViewModel.kt` 在离开前台时取消歌词查询、注销会话回调、释放封面引用；切歌丢弃旧结果。数据库、歌词解析和缩略图处理在 IO 线程。
- `data/PhoneLyrics.kt` 按需读取本地内容或公开歌词库；`core/PhonePlayback.kt` 按单调时间与速度推算进度。
- `PhonePlayerActivity.kt` 启动进入随播，隐藏本地收藏、队列、随机和循环选项；进入时停止 AMI 自己的播放器并保留队列。音频焦点、后台播放与系统通知由网易云负责。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assemblePhonePreview
```

APK 在 `app/build/outputs/apk/phonePreview/`。无需 Node.js。普通 Debug / Preview 的「网易云」入口也能打开随播；`cloudPreview` 保留旧电脑试验。

设备自动化使用测试 UID 中的合成会话，覆盖目标应用过滤、控制传递、暂停时钟、切歌旧结果取消、本地歌词匹配、公开歌词响应及共享歌词/封面页。它不能代替网易云当前版本与 X300 的真机验收。

## 交给本地 Codex

USB 只用于开发验证，最终必须拔线并关闭电脑连接端验收。可以直接发送：

> 读取 AGENTS.md 和 docs/PHONE_ONLY_PLAYBACK.md。目标 vivo X300 / OriginOS 7，验证网易云官方 App 后台播放、AMI 随播歌词和控制。先检查 git 状态、adb devices -l、系统/网易云版本、现有包名与签名；保留旧 AMI，不卸载、不清数据，不用 root / LSPosed，不读取网易云私有目录或登录凭据。安装独立 phonePreview，按系统流程批准通知使用权。在网易云播放后回 AMI，检查曲名、歌手、封面、进度、暂停、上下首和拖动。用两首不同歌手、现场版和无匹配歌词的样本检查没有旧歌词或错误版本。检查封面转场、回网易云选歌再返回、熄屏恢复、拔 USB 且关闭电脑端后的继续播放。日志只放 artifacts/，只分析网易云与本应用的媒体会话，不采集普通通知正文，不增加诊断界面。

取不到歌曲时先区分授权、监听服务绑定、网易云活跃会话及定制版包名。取不到歌词时，在 Debug 记录媒体键名、歌曲 ID 是否有效、歌名/歌手/时长和匹配结果；`dumpsys media_session` 没打印歌词，不证明全部 extras 都没有歌词。只有实际数据证明存在可用附加歌词字段后再适配。时序问题先查播放位置、单调时间和速度，再测拖动与蓝牙延迟；不要改成逐帧跨进程轮询。

## 本次交付验证

应用代码 `27cacb61d0b1ad22d48dba5d264b42163205c1b1` 的 [CI 40499169470](https://github.com/longzhousz/AMI-Android/actions/runs/40499169470) 已通过：57 项 JVM 测试，Lint 无 Error / Fatal；Android 11 / API 30 与 Android 17 / API 37 各 75 项设备回归，无失败、错误或跳过；Preview / PhonePreview / Release 构建；两套系统的优化 PhonePreview 安装、启动、系统通知使用权页面与返回检查。

合成会话测试不使用真实网易云账号或音频。公开歌词服务已进行少量真实搜索可达性检查，但不代表任何具体歌曲或中国境内网络始终可用。本次没有连接 X300，没有完成该机网易云版本、锁屏长时播放、蓝牙延迟或耗电验收。

交付包为本地优化构建，完成签名和 16 KiB 对齐检查。CI 优化包使用 CI 签名，与本地交付包不是同一签名二进制；本地交付包未安装到用户手机。

```text
文件：AMI-Android-0.1.12-phone-only-preview.apk
包名：com.longzhousz.ami.phonepreview
APK SHA-256：80ef3309193335ec8ba7225e52ab23f2b6be7e1fcc6729d33de6b5fea20b2558
签名证书 SHA-256：238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39
```

## 官方参考

- [Android 媒体会话授权与访问](https://developer.android.com/reference/android/media/session/MediaSessionManager)
- [Android 播放控制](https://developer.android.com/reference/android/media/session/MediaController.TransportControls)
- [Android 媒体元数据](https://developer.android.com/reference/android/media/MediaMetadata)
- [LRCLIB 接口与时长匹配规则](https://lrclib.net/docs)
