# AMI 当前交接

当前版本 **0.3 / versionCode 37**，最低 Android 13，数据库 schema 6、在线音频缓存 schema 1 与签名不变。包名仍为 `com.longzhousz.ami.musicpreview`；版本名与发布文件不再带 preview。应用源码为发布提交 `35af573`（Git 标签 `android-v0.3`）。上一版证据见 [0.2.14 交接](archive/LOCAL_HANDOFF_0.2.14.md)。

## 本轮修改

- 修复账号歌单达到 100 个时，网易云在第一页 `limit` 之外额外返回「我喜欢的音乐」导致曲库读取整体失败的问题；普通歌单与红心歌单分别计数，曲库与「我喜欢」不再一起空白。
- Agent Bridge 新增 `online.library.read` 与 `online.tracks.read`：可读取收藏专辑、自建歌单、红心歌单及分页曲目，结果不含封面或音源 URL；新增权限、文档与测试。
- 包含 Agent Bridge 的 `requires_ui` 分支与请求字节数清理改动。
- 0.3 起版本名与发布文件去掉 preview；更新检查支持 `android-v<version>` 与 `AMI-Android-<version>.apk`，同时兼容旧 preview 命名。0.2.14 及更早版本不会自动发现 0.3，需手动覆盖安装一次。
- 文档同步 0.3、安装包名与 Agent 接口。

## 验证范围

- spotlessCheck、detekt、JVM 单测、Lint、Debug 构建、`assembleDebugAndroidTest` 与优化 `assembleMusicPreview` 通过；仪器测试只编译，未运行完整设备套件。
- 模拟器真实账号：0.2.14 发布包与 Debug 均能显示收藏专辑、7 个自建歌单和 379 首红心；每日推荐正常。
- Agent 交换目录实账号验证：`online.library.read` 返回 50 张专辑、7 个自建歌单与红心歌单；`online.tracks.read` 对自建／红心／专辑分别返回 30／30／12 首，分页字段正常，结果不含 URL。
- 101 行第一页夹具：旧实现失败，新实现成功读取 101 个歌单；JVM 覆盖普通 100 + 红心、仅红心、101 普通三种计数。
- 未在 100+ 歌单的真实账号上复现；未在真机覆盖安装 0.3，未新增听感、帧率、耗电或原子岛结论。

## 安装包

`AMI-Android-0.3.apk`，5,646,958 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.3` / versionCode 37。SHA-256：

```text
4164e45994a25b35e4b9e7233515dd1238575146a1ebf5c01c9b9bb0c37beea5
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与 0.2.14 相同。可覆盖云端 0.1.14～0.2.14，保留登录、设置、曲库、歌词与聆听记录。

[0.3 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.3) 提供 APK、使用说明与校验清单；发布非草稿、非预发布，附件大小与 SHA-256 已核对。历史与标签保留，main 不放 APK。
