# AMI Android

本地音乐播放器。源自 [AMI](https://github.com/longzhousz/AMI)，安卓版本在此独立维护。Kotlin、Jetpack Compose、Media3；面向 Android 17（target / compile API 37），最低 Android 8.0（API 26）。

## 使用

1. 安装预览 APK，打开「设置 → 导入音乐」，在系统文件选择器中选择音乐文件夹。
2. 授予该文件夹的读取权限。无需「所有文件访问」或全盘媒体权限。
3. 专辑、曲目、同名 LRC、内嵌 / 同目录封面会自动整理。长任务通过 WorkManager 前台任务继续执行。
4. 曲库默认显示歌曲，点击歌曲即可播放，也可切换为专辑。底部迷你播放器进入完整播放页。支持锁屏、通知、耳机控制、音频焦点、拔耳机暂停、进度拖动、随机 / 循环、队列和定时停止。
5. 歌词页点击某行可跳转。可在设置中配置 DeepSeek API Key、模型及有序翻译风格。仅主动翻译时发送歌词，不上传音频、路径或封面。

### 自动更新翻译模型（0.1.6）

「设置 → 翻译模型」默认使用 **自动跟随最新 Flash**，调用 DeepSeek 官方的移动别名 `deepseek-flash`，不再把旧版本名称当成永久默认值。也可选择自动跟随 Pro、从官方列表手动选择，或输入模型 ID；手动选择不会被列表刷新覆盖。Flash 与 Pro 分开跟随，不会因发现新模型而自行切换到另一计费系列。

保存 API Key 后，应用在前台每天至多成功更新一次 `GET https://api.deepseek.com/models`，显示服务端返回的模型名称。保存或更换密钥后立即检查；翻译前也会检查过期列表。支持手动更新和关闭自动更新。列表、显示名、更新时间与选择保存在本机；网络异常时保留已有预设并退避重试，无密钥时使用内置预设，不发起模型请求。获取列表只发送鉴权信息，不发送歌词或调用翻译。

更新预设保留已有离线译文，不自动重译。播放页「重新翻译」会跳过旧缓存，使用当次选定的模型；同一首歌的所有分批请求固定使用同一模型与风格，避免设置变化混入同一份译文。旧版默认 Flash 选择会迁移到自动跟随，旧版 Pro 与其他手动模型保持原选择。

官方协议与别名说明：[模型列表](https://api-docs.deepseek.com/api/list-models/)、[首次 API 调用](https://api-docs.deepseek.com/)、[更新日志](https://api-docs.deepseek.com/updates/)。后续优化顺序见 [Android 路线图](docs/ANDROID_ROADMAP.md)。

### 动效与播放交互（0.1.5）

完整播放页与曲库共用一个窗口，从迷你播放器所在的位置展开；下拉页头或封面时，面板随手指移动，松手后按距离与速度决定收起或回弹。动画中可重新抓住面板、反向拖回，按钮与系统返回也使用同一套收起动画。歌词列表和进度条保留各自的手势，不会被收起手势抢占。

曲库、收藏、设置、专辑详情和歌曲 / 专辑视图使用统一的短转场，列表筛选和排序保留稳定条目与位移动画。调整曲序时，抓起的曲目跟随手指，相邻曲目弹簧让位，松手后回到目标位置。换歌时封面、标题和背景渐变过渡；队列选曲后先完成收起动画。

播放状态按 Media3 事件更新，取消每 350 毫秒重建整个队列的轮询。进度条和歌词使用只在前台运行的局部帧时钟，暂停、切到后台或隐藏迷你播放器后停止对应的帧更新。拖进度时预览跟随手指，松手才提交一次 seek，并等待播放器确认，避免旧进度把滑块拉回。

歌词跟随改用可打断的弹簧滚动，远距离跳转直接定位，手动拖动立即暂停跟随。歌词文字以透明度和轻微缩放表达层次，移除逐行模糊；封面取色结果有界缓存，背景颜色动画只更新绘制层。

### 界面精简（0.1.4）

歌曲页和收藏页直接展示搜索与音乐列表；导入统一位于设置。移除重复标题、口号、宣传卡片和无操作作用的标签。专辑页采用紧凑封面与曲目列表，排序和随机播放保留。

### 沉浸式歌词（0.1.3）

有歌词时，完整播放页默认展示歌词。大字号、温暖白色的当前句、前后句柔和淡出与轻微景深、封面取色渐变背景，延续 Windows 版的留白与阅读层次。封面取色在后台采样小图；没有持续动画背景，退出或切到后台后停止歌词时钟。

歌词使用自然段落高度，中文、日文、长句及双语均完整换行。当前句平滑移动到阅读位置；手动拖动后暂停跟随，点击「回到当前句」恢复，点句子仍可跳转。无时间轴歌词保留清晰正文，自由浏览。

底部「译文」直接显示/隐藏已有翻译；右上角「歌词选项」可重新翻译、手动导入或恢复自动匹配。没有译文时显示「翻译」。横屏把控制区与歌词分栏；放大字体时取消歌词景深，优先保持文字清晰。旧版安卓使用透明度层次，不依赖系统模糊效果。

### 网易云数字歌词（0.1.2）

网易云 Android 下载歌词常保存在 `Android/data/com.netease.cloudmusic/files/LrcDownload`，文件名是歌曲 ID，可能没有扩展名。歌词内容可以是 JSON；例如 `lrc` 字段里同时包含 `{t,c,tx}` 制作信息和普通 LRC 时间轴。AMI 会解析这些内容，并按 NCM 元数据中的 `musicId` 自动匹配，歌曲和歌词无需改名。带网易云标签的 MP3 / FLAC 也支持读取 ID。

Android 11 及以上限制普通应用直接读取其他应用的 `Android/data`。按以下方式使用：

1. 用已有访问权限的文件管理器，或通过电脑，将 `LrcDownload` 整个复制/导出到普通目录，例如 `Music/AMI-Lyrics`。应用无需 Shizuku 或所有文件权限。
2. 在 AMI 打开「设置 → 网易云歌词 → 选择歌词文件夹」，选择复制后的文件夹并授权读取。允许选择包含 `LrcDownload` 的上级文件夹，会递归查找数字歌词文件。
3. 导入会重新扫描已授权曲库、补读 NCM 的歌曲 ID。歌词和已有译文保存在 AMI 本机数据库，之后播放可离线使用；后续下载的新歌词，可更新该文件夹后点击重新扫描。

已有同名 LRC 优先使用。播放页右上角「歌词选项」可以为单曲手动选择文件，并可恢复自动匹配；手动选择会保留到重新扫描之后。歌词原件和歌曲原件均保持完整。无法识别的缓存会在导入结果中说明原因，原有歌词会保留。

复制歌词前导入音乐、复制歌词后导入音乐均可匹配。没有网易云 ID 的普通音频使用单曲手动选择入口，避免按相似歌名误配。

### 曲库与格式

- MP3、M4A、FLAC、WAV、OGG、OPUS、AAC 等格式，实际解码能力取决于设备。APE 等格式无法保证系统解码时，会在导入结果中报告问题。
- `.cue` 通过 Media3 clipping 播放虚拟曲目；支持多个 FILE、75 帧时间码、专辑 / 曲目艺人。完整校验后才排除对应的整轨音频，源文件不被切割。CUE 虚拟曲目暂不自动关联整轨 LRC，避免错误时间对齐。
- 经典 `CTENFDAM` NCM 容器直接流式解码到应用私有 `AudioCache`，无需 Windows exe。支持经典容器，不承诺其他 / 新版加密格式；失败会显示在导入报告中。转换不覆盖原始文件。
- UTF-8 / GB18030 同名 LRC，支持重复时间标签、offset、毫秒和无时间歌词。
- 专辑优先按 album artist + album 归组，无 album artist 时按文件夹作用域归组；多艺人合辑保留各曲目艺人，CD / Disc 子文件夹归一。元数据完全缺失时仍可能需要人工整理文件夹。
- 曲序可长按手柄拖动，也支持箭头操作。重新扫描保留手动曲序、收藏和初次导入时间。
- 停止扫描文件夹只影响后续扫描；从曲库移除不会删除源文件。重新扫描会再次发现源文件。文件迁移 / 授权撤回后请重新选择文件夹。

### 存储与安全

曲库 SQLite、封面、转换音频、翻译缓存在应用私有目录。无账号、广告、云同步、联网补图或分析上报。API Key 使用 Android Keystore AES-GCM 加密，密文位于不备份目录；不写入日志或数据库。应用禁用备份；卸载会清除这些数据。

缓存清理先显示可回收大小，只删除未被曲库引用的应用缓存；导入期间禁用清理。曲库引用的 NCM 音频和所有源文件均保留。播放器恢复上次队列和进度，重新启动时保持暂停。

DeepSeek 翻译每批最多 50 行，逐行验证 index、original、非空译文；全部成功才写缓存。缓存键包括歌曲、歌词内容、目标语言、风格、模型和提示词版本。没有有效 API Key 时可继续离线播放，联网翻译需要用户自行配置。

### Agent Bridge

默认关闭。在「设置 → Agent 与自动化」选择**专门的交换文件夹**并授权读写，生成令牌。前台每 3 秒处理最多 20 个 `*.request.json`，单文件不超过 32 KiB。音乐文件夹只需要读取权限，交换文件夹是单独的授权。

```json
{
  "request_id": "pause-1",
  "token": "设置中生成的令牌",
  "action": "player.pause",
  "payload": {}
}
```

生成 `pause-1.result.json` 后删除已消费的请求。结果与日志不包含令牌或歌词正文。最近 500 个已处理 request ID 防止重复执行；跨更久时间的重放不保证，调用方必须使用唯一 ID。无法解析的请求保留在原处，请调用方修复或移除。

支持 `player.play/pause/toggle/next/previous/seek`（`positionMs`）、`album.open/play`（`albumKey`）、`album.reorderTracks`（`albumKey`、`songIds`）、`lyrics.translateCurrent`、`cache.getStats/cleanupDryRun`。专辑键和歌曲 ID 为安卓本地标识，与 Windows 路径不同。只有播放控制权限默认开启，其他权限需逐项开启；`cache.cleanupApply` 始终返回 `requires_confirmation`，请用户在应用缓存页确认。

安卓不提供 Windows 的磁盘 inbox 路径，也不开放网络服务。Bridge 仅在界面前台运行，不要求后台常驻。

## 构建

需要 JDK **21**、Android SDK、安卓构建无需 Rust、NDK 或 Node。

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
# 配置 ANDROID_HOME，或 local.properties 中写 sdk.dir=/path/to/android-sdk
./gradlew testDebugUnitTest lintDebug assemblePreview assembleRelease
```

Windows 使用 `gradlew.bat`。工程锁定 Gradle 9.6.0、AGP 9.4.1；AGP 内置 Kotlin，Compose 编译器为 2.2.21。

- 可安装预览：`app/build/outputs/apk/preview/app-preview.apk`，应用 ID `com.longzhousz.ami.preview`，优化与资源压缩开启、不可调试，使用开发签名。用于测试，不是正式发行签名。
- 调试包：`./gradlew assembleDebug`，应用 ID `com.longzhousz.ami`。
- 正式发行：默认输出未签名 release。设置以下环境变量后可生成正式签名 APK / AAB，**不要提交签名文件和密码**：`AMI_KEYSTORE_PATH`、`AMI_KEYSTORE_PASSWORD`、`AMI_KEY_ALIAS`、`AMI_KEY_PASSWORD`。

```sh
./gradlew assembleRelease bundleRelease
```

GitHub Actions 在安卓变动时运行单元测试、Lint、预览 / 发行构建并上传预览 APK。设备测试：连接模拟器后 `./gradlew connectedDebugAndroidTest`。

验证记录及尚未覆盖的设备场景见 [ANDROID_VERIFICATION.md](docs/ANDROID_VERIFICATION.md)。

## 来源

初始工程提取自 `longzhousz/AMI` 的 `android/` 目录，提交 `0cb32f405729aec3e9cc3d13eb6ea2bcf9c0aa2f`。现有 Android 播放、曲库、歌词和数据迁移代码保留。桌面版本在原仓库维护。
