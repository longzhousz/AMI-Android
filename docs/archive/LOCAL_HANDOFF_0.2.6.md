# AMI 当前交接

当前版本 **0.2.6 / versionCode 28**，最低 Android 13 / API 33，compile / target API 37。应用源码 `3a6aa8d7e6548327d8b56c14428679aa945742bd`；后续提交仅更新文档。本地曲库 schema 5、在线音频缓存 schema 1 不变。

## 本次图标更新

采用用户选定的珍珠白底、墨蓝双音符和五条清晰唱片纹路，不使用模糊光斑。可编辑源图位于 [design/ami-icon.svg](../../design/ami-icon.svg)，通过 [Python 脚本](../../tools/generate_icon.py)生成 Android 原生矢量资源，详见 [图标说明](../../design/README.md)。无需运行时生成或新增依赖，不打包高分辨率 PNG。

桌面普通／圆形入口共用自适应 mipmap，由系统提供遮罩；108 dp 图层留出视差和裁切区域，音符位于安全区。背景、前景分层，颜色与源图对应。系统主题图标和通知保留清晰的单色音符。旧 AI 字母图标移除。

本轮只修改版本、图标资源及 Manifest 引用。播放、音质、缓存、账号和数据逻辑沿用 0.2.5；该版音源兼容与手机诊断说明保存在 [历史交接](LOCAL_HANDOFF_0.2.5.md)，当前使用规则见 [使用说明](../USER_GUIDE.md)。

## 验证范围

- 本地 112 项 JVM 全部通过，Lint 无错误，Debug 与优化 MusicPreview 构建通过；生成脚本重复执行字节一致，SVG 与用户选定原图相同。
- [实际云端签名 APK 检查](https://github.com/longzhousz/AMI-Android/actions/runs/41945944127) 在 Android 13／17 上覆盖安装 0.2.5，完成两次启动、设置、周／月／年聆听及歌词导入入口检查；查看系统应用信息页的新图标。截图及安装包摘要已核对，未将未签名或另一个构建当成交付包。
- v2 签名、16 KiB 对齐、包名、最低系统、版本与嵌入的源码提交已核对；发布附件重新下载后与本地文件逐字节一致。

没有连接 vivo 或表弟手机，未新增真实账号、音质、原子岛、帧率或耗电验证。桌面图标实际遮罩与主题色由系统决定；若启动器缓存旧图标，移除快捷方式后重新添加，不卸载或清除 AMI 数据。

## 安装包

`AMI-Android-0.2.6-online-preview.apk`，5,476,386 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.6-music-preview` / versionCode 28。SHA-256：

```text
8aa1232c26afbb487a0facaef1a42d6a6a6d0315971a6a67bcc8cb87e7341fee
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.5 相同，可覆盖云端 0.1.14～0.2.5，保留登录、设置、曲库、歌词和聆听记录，不卸载或清数据。

[0.2.6 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.6-preview)包含 APK、中文说明和校验清单。0.2.3 起启动后可后台获取更新，进入设置才询问安装。旧版发布转草稿，历史及标签保留；安装包不进入 main。
