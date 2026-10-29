# 历史归档

归档日期：2026-10-29。当前版本为 **0.1.18**，请先阅读 [当前文档](../README.md) 和 [当前交接](../LOCAL_HANDOFF.md)。本目录保存旧界面、旧验证结论与不再推荐的试验方式；文中的“当前版本”只指当时的版本。

| 历史范围 | 记录 |
| --- | --- |
| 原 README 与逐版变更 | [README_HISTORY.md](README_HISTORY.md) |
| 迁移及多版叠加交接 | [LOCAL_HANDOFF_HISTORY.md](LOCAL_HANDOFF_HISTORY.md) |
| 0.1.1～0.1.4 验证 | [ANDROID_VERIFICATION.md](ANDROID_VERIFICATION.md) |
| 0.1.7～0.1.9 优化方向与设备证据 | [ANDROID_ROADMAP.md](ANDROID_ROADMAP.md)、[ANDROID_DEVICE_QA.md](ANDROID_DEVICE_QA.md)、[PLAYER_ARTWORK_MOTION.md](PLAYER_ARTWORK_MOTION.md) |
| 0.1.10 NCM 缺图 | [NCM_ARTWORK.md](NCM_ARTWORK.md) |
| 0.1.11 Windows / 官方 CLI 连接试验 | [NETEASE_PREVIEW.md](NETEASE_PREVIEW.md) |
| 0.1.12 网易云 App 随播试验 | [PHONE_ONLY_PLAYBACK.md](PHONE_ONLY_PLAYBACK.md) |
| 0.1.13 应用内播放初版 | [NETEASE_DIRECT.md](NETEASE_DIRECT.md) |
| 0.1.14 在线曲库与音质 | [ONLINE_LIBRARY.md](ONLINE_LIBRARY.md) |
| 0.1.15 自动音频缓存 | [PLAYBACK_CACHE.md](PLAYBACK_CACHE.md) |
| 0.1.16 下拉交接与封面缓存 | [PLAYER_HANDOFF.md](PLAYER_HANDOFF.md) |
| 0.1.17 页面记忆与播放衔接 | [PLAYBACK_FLOW_0.1.17.md](PLAYBACK_FLOW_0.1.17.md) |

当前实现与证据见 [PLAYBACK_FLOW.md](../PLAYBACK_FLOW.md) 和 [本轮体验说明](../LISTENING_EXPERIENCE.md)，旧文档中的菜单、包名、测试数量和缓存调度说明不覆盖当前规则。

## GitHub 整理

main 保留当前完整应用源码与文档，不放安装包副本或按版本复制的源码目录。最新 APK 位于 [Releases](https://github.com/longzhousz/AMI-Android/releases/latest)。0.1.3 的发布与三个原附件转为草稿保留，标签 `android-v0.1.3-preview` 仍可用于旧版兼容性追溯。

下列旧分支的提交都已包含在 main，旧草稿 PR #3～#6 关闭、分支入口删除；Git 提交历史不重写。需要时可从对应提交重新建分支：

| 旧分支 | 完整提交号 |
| --- | --- |
| `feat/android-fluid-motion` | `7d581ebe619f9ebc7a3c934a367b5d9d72fd4b75` |
| `feat/translation-model-discovery` | `f0636f7e78c2e50c23de7334e0bfa1e8d83591da` |
| `feat/library-background-performance` | `21ee4e38898a05acacd62c53078553505445bfc0` |
| `feat/lazy-lyrics-playback-polish` | `8e045b419f00b460c08caf1a8787691aad9a11b5` |
| `feat/player-artwork-motion` | `636c2b39f6b3b737a6c16a5a59d70fadf4934827` |

本地下载目录的旧版 APK、截图和说明按版本移入其 `archive/` 子目录；这些二进制、登录资料和本机产物不提交源码仓库。
