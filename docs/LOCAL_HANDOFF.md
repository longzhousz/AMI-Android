# AMI 当前交接

当前源码版本 **0.2.1 / versionCode 23**，最低 **Android 13 / API 33**，compile / target API 37。应用源码 `8627aa05161e0cc1449d6ffa7afe73a5cd0afb6d`；后续提交为交付文档和模拟器检查脚本。本地曲库 schema 5、在线音频缓存 schema 1 保持不变。

## 本轮范围

采用 [Backdrop 1.0.6](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6) 的原生 GPU 模糊、AGSL 折射、色散、高光与阴影。悬浮导航和迷你播放条采样真实页面；播放控件、搜索、设置和聆听卡片、菜单、队列及对话框统一玻璃材质。原图封面保持方形，迷你缩略图保留 9 dp 圆角；文字显式继承当前主题颜色。选型与资源边界见 [材质说明](LIQUID_GLASS.md)。

专辑详情、播放页增加封面取色的流动光晕，复用原来的小图与配色缓存，仅可见且 RESUMED 时绘制，覆盖后冻结、返回时连续恢复。不新增后台轮询或逐帧 CPU 图片回读。

「我喜欢」顶部显示红心歌单原顺序中最近 15 首封面；复用已载入前缀，不整库抓图。歌曲行移除红心；播放页、搜索、专辑和队列保留网易云喜欢／取消喜欢。

高音质、歌词动效、单窗口下拉交接、缓存调度和「我的聆听」继续保留。歌词首次定位延后到布局完成，避免 Compose 1.10 / Android 17 嵌套重排。没有更改账号协议、队列或数据库结构，运行只需要手机。

## 验证状态

- 本地 **100 项 JVM** 测试、Lint、Debug、仪器测试及优化 MusicPreview 构建通过。
- [Android 13／17 完整回归](https://github.com/longzhousz/AMI-Android/actions/runs/41032259727) 各 **130 项**，无失败、错误或跳过，后台播放恢复检查通过；日志中的结果检查器已核验数量。
- 合成曲目截图已检查玻璃、封面墙、播放页和专辑页；新增测试检查墙的 15 首上限、前缀缓存、红心修补、分页，以及光晕运动与暂停。
- [同一份签名 APK 检查](https://github.com/longzhousz/AMI-Android/actions/runs/41034739772) 在 Android 13／17 完成两次启动、导航、设置、周／月／年图鉴与日期选择，未记录 AMI ANR 或崩溃；每套系统的 XML 证据与事件日志均由发布门禁再次校验。
- Android 17 最初在模拟器开机满载期出现 FocusEvent 超时。最终检查先等待系统 CPU 负载稳定，再使用完全相同的 APK；没有隐藏 AMI 无响应、放宽应用超时或减少玻璃效果。该结果不代替真机启动与帧率测量。
- [发布校验](https://github.com/longzhousz/AMI-Android/actions/runs/41035691917) 核对两套系统各 130 项、全部签名 UI 证据、无应用 ANR／崩溃、源码后续无应用修改，以及下载附件的大小和 SHA-256；确认通过后发布 **android-v0.2.1-preview** 为最新版本。

没有连接真实手机或网易云账号。模拟器结果不能证明 vivo X300 的实际帧率、续航、耳机输出或原子岛表现。

## 已生成安装包

`AMI-Android-0.2.1-online-preview.apk`，5,419,210 bytes，包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.1-music-preview` / versionCode 23。SHA-256：

```text
1337e5fbfd871dbb9e961f12886daac1631d95f947ccb8c96d9ea31f55b8f817
```

v2 签名、16 KiB 对齐、嵌入源码及许可资产已核对。证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.1.21 相同。APK、中文说明、校验清单上传后已下载逐字节核对；后续没有改变 APK。

Android 13 及以上可以覆盖云端 0.1.14～0.1.21，保留登录、设置、曲库、歌词与聆听记录；不卸载、不清数据。旧版交付见 [0.1.21 归档](archive/LOCAL_HANDOFF_0.1.21.md)。

## 交付与后续

[0.2.1 最新发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.1-preview) 已公开，APK、中文说明和校验清单与已验证产物一致；0.1.21 旧发布转为草稿，Git 历史与标签保留。临时 `release/0.2.1-upload` 分支已删除，分发二进制与合成诊断截图没有合入 main。

云端本地执行环境在交付中途断开，后续诊断、验证和发布通过 GitHub Actions 完成。源码与证据以本页链接为准，本地 scratch 状态文件可能停留在中间阶段。恢复该执行环境后，再将下载目录旧版 0.1.21 的三个文件原样移入历史归档并核对原摘要；这不影响 GitHub 当前下载。不要重新签名或重建已核对的 APK。
