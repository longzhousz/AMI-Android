# AMI 当前交接

当前版本 **0.2.12 / versionCode 34**，最低 Android 13，数据库 schema 6、在线音频缓存 schema 1 与签名不变。应用源码 `aab8aae7651b7588cca816eee291eefd10f6483b`，之后的提交只更新交付文档。上一版证据见 [0.2.11 交接](archive/LOCAL_HANDOFF_0.2.11.md)。

## 本轮修改

修复部分在线歌曲没有封面、只显示内置占位图的问题。网易云少数专辑封面的原图为 12.2 MB／14.9 MB 的 PNG，超过封面下载的 8 MiB 上限后被直接丢弃；现在仅对超大原图改用 CDN 缩放版（`param=800y800`），正常封面仍下载原图，缩放结果与同一张封面共用缓存键，不增加重复下载或整库预取。新增 2 项 JVM 单测，覆盖缩放地址生成、非白名单地址拒绝与超大图降级信号。

## 验证范围

- detekt、JVM 单测、Lint、调试构建与优化发布构建通过；spotlessCheck 在本机 Windows 因既有 CRLF／LF 基线报 148 个文件违规（含未改动文件），未刷新基线。
- 真机：vivo V2509A 的 Debug 包 `com.longzhousz.ami`，冷启动「我喜欢」后封面墙恢复 30 张真实封面，The Music of the Night 等原缺封面歌曲显示真实专辑图，界面树中「占位封面」节点为 0。
- 发布包：包名、版本、最低系统、v2 签名、16 KiB 对齐、证书与 APK／附件 SHA-256 已核对。
- 未在真机执行发布包覆盖升级，未运行 CI 的 Android 13／17 原生回归，不新增听感、帧率、耗电或原子岛结论。

## 安装包

`AMI-Android-0.2.12-online-preview.apk`，5,597,822 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.12-music-preview` / versionCode 34。SHA-256：

```text
a33b7ffcc4bcda8ffda7a1de7adb46b5aea8cd7562aa3f956e40841583a12f95
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.11 相同。可覆盖云端 0.1.14～0.2.11，保留登录、设置、曲库、歌词与聆听记录；包名、版本、最低系统、v2 签名、16 KiB 对齐和附件摘要已核对。

[0.2.12 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.12-preview) 提供 APK、使用说明与校验清单。0.2.11 发布转为草稿，历史与标签保留，main 不放 APK，稳定中英 README 未改变。
