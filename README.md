<img align="right" src="design/ami-icon.svg" width="88" alt="AMI">

# AMI · Android 音乐播放器

把网易云里点过红心的歌，和手机里珍藏的整张专辑，放进同一个播放器。点开封面，背景光晕随专辑换色；再点一下，歌词就跟着歌声逐字亮起。

**[下载 Android 安装包](https://github.com/longzhousz/AMI-Android/releases/latest)** · [使用指南](docs/USER_GUIDE.md) · [English](#english)

适用于 **Android 13 及以上**。无广告，无需 root 或连接电脑。

## 从哪首歌开始？

- **从网易云收藏里选。** 在 AMI 内登录网易云音乐，即可浏览收藏专辑、自建歌单和红心歌曲。在 AMI 点的红心也会同步回网易云。想听点新的，就打开私人 FM 或每日推荐。
- **从手机里的专辑开始。** 打开「设置 → 本地音乐 → 导入音乐」，选择音乐文件夹。除了 FLAC、MP3 等常见格式，整轨专辑也能按 CUE 索引逐曲播放，原文件保持完整。

## 听歌时的小细节

- **重听一句歌词。** 有逐字时间轴时，每个字会在唱到时亮起。点一句就能跳回去听。长按翻译按钮可选网易云已有译文，也可以用自己的 DeepSeek 密钥按需翻译。
- **沿着封面逛曲库。** 点开网格里的一张封面，它会展开成专辑详情；返回时收回原位，接着就能逛下一张。滚动时，封面的颜色透过悬浮的液态玻璃控件。
- **回看这一周听了什么。** 「设置 → 我的聆听」里有按实际播放时长排出的前十首歌，也有标出听歌日子的日历。周报、月报和年报都在手机上生成。

## AI-native：把播放器变成 Agent 的接口

AMI 的名字来自 **agent-music-interface**。除了用手操作，你也可以让 AI Agent 直接驱动这座曲库。

在「设置 → 更多设置 → Agent 与自动化」里选一个交换文件夹、生成令牌，再按用途打开权限。Agent 把 JSON 请求写进文件夹，AMI 在前台时每 3 秒取走并执行，再写回同名的 `.result.json`。不需要网络端口、常驻服务、root 或电脑端；只要能读写这个文件夹，Claude Code、本地脚本或你自己的自动化流程都能接入。

它能做的事覆盖了播放器的日常操作：

- **播放与队列**：暂停、切歌、跳转、切换模式、读取当前状态；整队替换、追加、删除与重排。
- **曲库与专辑**：打开或播放专辑、调整持久曲序、触发扫描、移除歌曲、管理已授权文件夹。
- **网易云**：搜索、私人 FM、每日推荐、收藏与自建歌单、在线播放、同步红心。
- **歌词与翻译**：按需翻译当前歌曲、切换模型与译文风格、导入或清除歌词。
- **缓存与统计**：读取缓存占用、试运行清理、生成聆听报告。

边界也一起写进了协议。外部调用默认关闭；打开后，权限按能力分组，默认只开「控制播放」；令牌只显示一次；删除缓存、清空统计会先返回 `requires_confirmation`，等你在应用里点确认；登录、文件夹授权、令牌、备份和 API Key 只能在界面上操作。请求不能携带路径、URI、Cookie 或密钥，结果也不含 URL、路径或凭据。请求可以单独取消，超时、重复与过期确认都会自动收尾，Agent 出错也不会留下一直悬着的请求。

AI 译文同样按需生成：模型列表跟随 DeepSeek 官方更新，可以写自己的译文风格，结果按歌曲、模型和风格缓存，不会在后台产生费用。

完整的动作、权限与交换格式见 [Agent Bridge](docs/AGENT_BRIDGE.md)。

网易云接入使用非官方兼容协议。可播放的歌曲和音质取决于账号、会员、地区及曲目授权。

## English

**AMI is an Android music player for your NetEase Cloud Music collection and local albums.** Open an album and its artwork expands from the grid. Tap the cover during playback to follow the lyrics, then tap a line to hear it again.

**[Download the APK](https://github.com/longzhousz/AMI-Android/releases/latest)** for Android 13 or newer. AMI runs on your phone, ad-free, without root or a desktop companion.

- **Your library.** Sign in to browse saved albums, playlists you created, and liked songs. Likes sync back to NetEase. For local music, choose a folder in Settings. Play FLAC, MP3, or individual tracks from a single album file with a CUE sheet.
- **Lyrics and translation.** Words light up in time with the vocals when word-level timing is available. Long-press the translation button to choose an available NetEase translation or translate on demand with your own DeepSeek API key.
- **Listening history.** See your most-played tracks by listening time and a calendar of the days you listened. Weekly, monthly, and yearly reports are generated on your device.

## AI-native: a player an agent can drive

AMI stands for **agent-music-interface**. Alongside touch, an AI agent can drive the library directly.

In Settings, pick an exchange folder, generate a token, and enable only the capabilities you want. The agent writes a JSON request into the folder; while AMI is in the foreground it reads and runs the request every 3 seconds, then writes back a matching `.result.json`. No network port, background service, root, or desktop companion is involved — any agent or script that can read and write that folder can join in.

The interface covers the everyday work of a player:

- **Playback and queue.** Pause, skip, seek, switch modes, read the current state; replace, append, remove, or reorder the queue.
- **Library and albums.** Open or play an album, edit persistent track order, scan, remove songs, manage authorized folders.
- **NetEase Cloud Music.** Search, personal FM, daily recommendations, saved and created playlists, online playback, synced likes.
- **Lyrics and translation.** Translate the current song on demand, switch models and translation style, import or clear lyrics.
- **Cache and listening stats.** Read cache usage, dry-run cleanup, generate listening reports.

The boundaries are part of the contract. External calls are off by default; once enabled, permissions are grouped by capability and only playback is on. The token is shown once. Cache cleanup and stats clearing answer `requires_confirmation` and wait for a tap in the app, while login, folder grants, tokens, backups, and API keys stay UI-only. Payloads cannot carry paths, URIs, cookies, or credentials, and results never include URLs, paths, or secrets. Individual requests can be cancelled, and timeouts, duplicates, and expired confirmations are settled cleanly, so a runaway agent cannot wedge playback.

AI translation is on-demand too: the model list follows DeepSeek's official catalog, you can provide your own translation style, and results are cached per song, model, and style — nothing is generated or billed in the background.

See [Agent Bridge](docs/AGENT_BRIDGE.md) (Chinese) for every action, permission, and the exchange format.

The unofficial NetEase integration respects account, subscription, regional, and track permissions.

## 项目与反馈 · Project links

[反馈问题或建议 / Issues](https://github.com/longzhousz/AMI-Android/issues) · [项目文档 / Docs (中文)](docs/README.md) · [本地构建 / Build guide (中文)](docs/LOCAL_DEVELOPMENT.md) · [第三方说明 / Notices](docs/THIRD_PARTY_NOTICES.md)

Built with Kotlin, Jetpack Compose, and Media3. Based on the Android project from [AMI](https://github.com/longzhousz/AMI) and maintained here as a standalone app.
