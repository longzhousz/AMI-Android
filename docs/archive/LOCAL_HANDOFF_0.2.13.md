# AMI 当前交接

当前版本 **0.2.13 / versionCode 35**，最低 Android 13，数据库 schema 6、在线音频缓存 schema 1 与签名不变。应用源码 `e600e7ff47888a9fb195f74b59bc6d7b80c86a5c`，之后的提交只更新交付文档。上一版证据见 [0.2.12 交接](archive/LOCAL_HANDOFF_0.2.12.md)。

## 本轮修改

- 红心写入：网易云 `weapi/radio/like` 端点被服务端以 `-460` 拒绝（body 参数、空 body＋query、两者都带、加 `X-Real-IP` 头均失败），导致加入／取消红心都报错。改走官方移动客户端 eapi 的 `song/like`，由 `NeteaseTransport.postClient()` 复用项目已有的 `read(clientPath)` 通道；读取红心列表的 `song/like/get` 不变。上一版「把参数放 query」的假设已撤除。
- 提示双层框：Material3 `Snackbar` 自带 Surface 与外层 `liquidGlass` 叠加成两层圆角框；`GlassSnackbarHost` 改为直接在单个 `GlassSurface` 里组合文字与操作按钮。
- 专辑返回封面跳动：0.2.11 用 `remember` 共享 transform 再由 `SideEffect` 动态改 `targetContentZIndex`，反向转场复用旧层级导致共享封面首帧跳变；现在每次导航在 `transitionSpec` 里新建 transform。
- 新增 JVM 单测 `NeteaseTransportTest.likeWritesUseTheMobileClientEndpointWithTheEncryptedTrackAndLikeFlag`，覆盖客户端端点、客户端 UA 与加密载荷。

## 验证范围

- detekt、JVM 单测、Lint、调试构建与优化发布构建通过；spotlessCheck 在本机 Windows 仍因既有 CRLF／LF 基线报 1 个未改动文件（`ArtworkImportTest.kt`）违规，本轮改动文件均无违规，未刷新基线。
- 真机：vivo V2509A，发布变体 `com.longzhousz.ami.musicpreview`。红心完成端到端验证——加入 `Intro` 后冷启动「我喜欢」出现该歌曲，取消后冷启动该歌曲、歌手与专辑封面全部消失，账号恢复原状，全程无错误提示；提示条单层玻璃框在真机截图确认。
- 专辑返回动画只做代码层修复与构建验证，**未做逐帧动画测量**，需实际返回专辑页主观确认。
- 发布包：包名、版本、最低系统、v2 签名、16 KiB 对齐、证书与 APK／附件 SHA-256 已核对。
- 未在真机执行发布包覆盖升级，未运行 CI 的 Android 13／17 原生回归，不新增听感、帧率、耗电或原子岛结论。

## 安装包

`AMI-Android-0.2.13-online-preview.apk`，5,597,822 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.13-music-preview` / versionCode 35。SHA-256：

```text
1173d2b870eaa21914b37c104b08518ce3da75ddf39428172de01188083b1aa1
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.12 相同。可覆盖云端 0.1.14～0.2.12，保留登录、设置、曲库、歌词与聆听记录；包名、版本、最低系统、v2 签名、16 KiB 对齐和附件摘要已核对。

[0.2.13 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.13-preview) 提供 APK、使用说明与校验清单。0.2.12 发布转为草稿，历史与标签保留，main 不放 APK，稳定中英 README 未改变。
