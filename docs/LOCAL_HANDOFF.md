# AMI 当前交接

当前版本 **0.2.14 / versionCode 36**，最低 Android 13，数据库 schema 6、在线音频缓存 schema 1 与签名不变。应用源码 `37c97a31148b38f03fe89e0b87325c13f834982c`，之后的提交只更新交付文档。上一版证据见 [0.2.13 交接](archive/LOCAL_HANDOFF_0.2.13.md)。

## 本轮修改

- 「曲库」沉浸式顶部：云音乐直连时曲库页不再整页下移，歌单网格滚动时经过透明状态栏；悬浮的发现／搜索按钮与静止首行仍按状态栏高度下移，不遮住系统图标。顶部增加跟随浅深主题的渐隐层，降低封面滑过时对系统图标的干扰。
- 刷新失败提示行在沉浸页面内补状态栏 inset，并避开悬浮工具栏；失败行出现时网格不再叠加第二次状态栏留白。
- `OnlineLibraryTest` 的刷新首行断言改为对照实际工具栏边界，不再依赖固定 76 dp，避免不同机型 inset 造成误报。
- `docs/LIQUID_GLASS.md` 补充曲库沉浸式说明；版本与安装包名同步到 0.2.14。

## 验证范围

- spotlessCheck、detekt、JVM 单测、Lint、调试构建、优化 musicPreview 构建与 `assembleDebugAndroidTest` 通过；仪器测试只编译，未在设备上运行。
- 真机：vivo V2509A，同一份源码的预览构建（`0.2.13-music-preview` / versionCode 35，改动后的工作树）。曲库静止时工具栏与首行位于状态栏下方；滚动封面色块经过状态栏；顶部渐隐像素取样确认生效；专辑详情、「我喜欢」页与登录数据无回归，无新增崩溃。
- 最终 0.2.14 发布包只做静态核对，**未在真机覆盖安装**：发布时手机已断开 ADB。
- 发布包：包名、版本、最低系统、v2 签名、16 KiB 对齐、证书与 APK／附件 SHA-256 已核对。
- 未运行 CI 的 Android 13／17 原生回归，不新增听感、帧率、耗电或原子岛结论。

## 安装包

`AMI-Android-0.2.14-online-preview.apk`，5,597,822 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.14-music-preview` / versionCode 36。SHA-256：

```text
da11a4ddc03bb924492eb1629bf077899c8a44fe3b7cf637b648a01e3ed7a5b2
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.13 相同。可覆盖云端 0.1.14～0.2.13，保留登录、设置、曲库、歌词与聆听记录；包名、版本、最低系统、v2 签名、16 KiB 对齐和附件摘要已核对。

[0.2.14 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.14-preview) 提供 APK、使用说明与校验清单。0.2.13 发布转为草稿，历史与标签保留，main 不放 APK，稳定中英 README 未改变。
