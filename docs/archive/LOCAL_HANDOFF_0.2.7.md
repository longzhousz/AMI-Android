# AMI 当前交接

当前版本 **0.2.7 / versionCode 29**，最低 Android 13 / API 33，compile / target API 37。应用源码 `d9cf3c091a37b814b331f5fccc5d788c3e4e4832`；后续提交只更新文档。本地曲库 schema 5、在线音频缓存 schema 1 不变。

## 柔和暂停

手动暂停约 360 ms 内按余弦包络减弱播放器增益，末尾暂停并恢复原增益。MediaLibrarySession 使用原生 ForwardingSimpleBasePlayer 扩展，未完成操作立即反馈暂停状态；应用、锁屏和耳机通过同一会话。底层 ExoPlayer 保留音频焦点与 noisy 处理，不改变系统音量。快速继续播放取消渐弱；seek、换队列、stop、release 及底层被中断时清理旧操作，不让旧任务暂停新歌曲。渐弱结束后不保留音量帧任务，不新增偏好或音频副本。

## 完整歌名

封面页与歌词页中被省略的标题点击原位展开，再点收起。只动画标题区域的尺寸，让歌手名始终在标题下方移动；封面和歌词按原有布局让出空间。极长标题在最高 160 dp 的区域内滑阅，底部主控保留。短标题不增加点击操作，换歌重置展开状态，不增加弹窗、额外文案或设置。

播放队列、音质、缓存、图标、翻译和数据规则保留；图标历史见 [0.2.6 交接](LOCAL_HANDOFF_0.2.6.md)，当前播放规则见 [PLAYBACK_FLOW.md](../PLAYBACK_FLOW.md)。

## 验证范围

- 本地 112 项 JVM 全通过，Lint 无错误，Debug、AndroidTest 与优化 MusicPreview 构建通过。
- [定向原生验证](https://github.com/longzhousz/AMI-Android/actions/runs/42063546810) Android 13／17 各 18 项通过，并验证后台回收及系统媒体恢复。核对每个原生测试名、数量和状态，覆盖真实 ExoPlayer 增益采样、快速恢复／换歌、系统底层中断、静音／未准备状态、标题中途反向动画、跨页／换歌、大字体、系统媒体会话、三种实际音频播放流程与六种歌词场景。截图与增益采样已查看。
- [Android 17 完整回归](https://github.com/longzhousz/AMI-Android/actions/runs/42063541908/job/127371736421) 153 项全部通过，后台恢复通过；Android 13 同轮在第 122 项执行期间模拟器断连，报告不完整，不计为通过，已单独重跑。该系统的上述 18 项定向检查与签名 APK 覆盖验证均已通过。
- [交付签名 APK 验证](https://github.com/longzhousz/AMI-Android/actions/runs/42065391400) 在 Android 13／17 覆盖安装 0.2.6，完成两次启动、设置、聆听图鉴及歌词导入入口；APK 摘要与交付文件一致。
- v2 签名、16 KiB 对齐、最低系统、包名、版本及嵌入源码已核对；三个发布附件重新下载后与本地逐字节一致。

首次定向 Android 13 的 AOSP 模拟器中途断连，未计为通过；最终原生验证改用项目已有的 Google APIs 33／4 GiB 配置，未降低应用画质。上一版常规 APK 启动检查也曾在应用启动前因开机 CPU 未稳定而失败，当前启动检查对齐已通过交付签名检查的 Ubuntu 24.04／AOSP 33 配置。失败记录保留在 Actions。

没有连接 vivo X300 或表弟手机，未新增真机听感、帧率、耗电、网易云账号或原子岛验证。增益采样来自原生播放器参数，不是手机扬声器录音。

## 安装包

`AMI-Android-0.2.7-online-preview.apk`，5,509,150 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.7-music-preview` / versionCode 29。SHA-256：

```text
9f4ba4995ad72237ea7fc896616acad23384d4a090efbf2ad61f30c0aacd3f94
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.6 相同，可覆盖云端 0.1.14～0.2.6，保留登录、设置、曲库、歌词和聆听记录，不卸载或清数据。

[0.2.7 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.7-preview)含 APK、中文说明和校验清单。启动后可后台获取更新，进入设置才询问安装。旧发布转草稿，历史标签保留；安装包不进入 main。
