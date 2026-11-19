# 本地导入与在线账号：数据边界与代码地图

> 快照：`main @ af4a654`（0.3.1 / versionCode 38），2026-11-17。
>
> 性质：只读代码审查，未运行单元测试、仪器测试、模拟器或真机。文件路径、类名和函数名是长期索引；第 3.7 节给当前快照的 `file:line` 速查，行号会随代码移动。

## 0. 怎么用这份文档

- 要改**本地导入/扫描/重扫**：先看第 2、3.1、3.4、4、7、8 节，再动第 9 节的边界。
- 要改**在线歌单/红心/登录**：先看第 3.2、3.3、5、6、8 节。
- 要**重构或整理代码**：直接看第 3.6 节“按问题找代码”、第 3.7 节“关键位置”、第 11 节“建议顺序”和第 12 节检查清单。
- 任何改动前，先读第 8 节“不变量”；那是本地与在线不互相破坏的前提。

## 1. 结论

**本地导入与登录后的网易云账号不会冲突。** 两者是独立数据面：

- 本地歌曲写入 `songs`、`song_lyrics`、`lyric_overrides`、`netease_lyrics`、`scan_cache` 及私有文件目录；普通歌曲 ID 是 SAF 文件身份的 SHA-256（64 位十六进制），CUE 虚拟曲目是 `sha256(identity#曲号)`。
- 在线歌单、专辑、曲库、红心只读；在线歌曲是合成对象 `id="netease:<数字>"`、`uri="ami-cloud://song/..."`，**从不写 `songs` 表**。
- 账号内容唯一的写接口是红心 `song/like`；导入链路完全不调用 `CloudMusic` / `NeteaseAccount`。

需要区分“冲突”和“联动”：同一首歌本地一份、在线一份时，会出现两个条目，本地收藏与云端红心各自独立、互不同步。听歌统计只在**歌曲 key** 上按网易云 ID 合并为 `netease:<id>`；艺人/专辑维度不会合并——本地歌曲没有 `primaryArtistId`/`albumId`，正常播放时用艺人/专辑名称摘要，云歌用 `netease-artist:`/`netease-album:` 结构 ID。这是当前设计，不是数据串写。

## 2. 数据面总览

```text
用户选择 SAF 文件夹
        │
        ▼
roots / lyricRoots（SharedPreferences "ami"）
        │  WorkManager "library-import"
        ▼
ImportWorker（importLock、前台通知、可取消）
        │
        ▼
LibraryScanner
  ├─ walk(authority:documentId) ──去重──> DocumentEntry
  ├─ scan_cache 指纹 ──未变则跳过──> 重新读取变化的文件
  ├─ NCM 解码 ─────────────────────> filesDir/AudioCache
  ├─ 封面 ────────────────────────> filesDir/Covers
  ├─ 同名 LRC / 数字文件名歌词 ────> song_lyrics / netease_lyrics
  └─ 事务 upsert ─────────────────> ami.sqlite3: songs / scan_cache
                                            │
                                            ▼
                                   LibrarySnapshot / UI

网易云账号
  ├─ WebView 登录 ──> SecretStore（Android Keystore）
  ├─ 歌单/专辑/曲目/红心 ──读取──> 内存页缓存（不落歌单表）
  ├─ 当前在线曲目元数据 ────────> noBackupFilesDir/netease-queue.json
  ├─ 当前活动队列（本地+在线 ID）─> ami.sqlite3: playback_queue/checkpoint
  └─ 唯一写：song/like（红心）──> 网易云服务器
```

## 3. 代码地图

### 3.1 本地导入

| 职责 | 文件 | 关键符号 | 备注 |
| --- | --- | --- | --- |
| 选择文件夹、持久授权 | `MainActivity.kt` | `folderPicker`、`lyricFolderPicker`、`takePersistableUriPermission` | 停止扫描时没有释放系统授权 |
| 根目录与调度 | `LibraryRepository.kt` | `roots()`、`addRoot()`、`scan()`、`cancelScan()`、`importLock` | 手动重扫 `KEEP`，新增根目录 `APPEND_OR_REPLACE` |
| 后台任务 | `ImportWorker.kt` | `doWork()`、`getForegroundInfo()` | 先持 `importLock`，再前台、置 `running`、扫描 |
| 扫描主流程 | `LibraryScanner.kt` | `scan()`、`readSong()`、`add()`、`flush()` | CUE/NCM/封面/LRC 都在这里 |
| SAF 遍历与身份 | `DocumentFiles.kt` | `walk()`、`identity()`、`readBounded()` | `identity = authority:documentId` |
| 增量指纹 | `ScanCache.kt`、`core/ScanFingerprint.kt` | `stamp()`、`ScanRecord.reusable()`、`ScanFingerprint.of()` | `modified<=0` 或 `size<0` 时不可复用 |
| 数据库与表结构 | `LibraryDatabase.kt` | `songs`、`song_lyrics`、`lyric_overrides`、`netease_lyrics`、`scan_cache`、`upsert()` | `upsert` 的 UPDATE 保留用户字段 |
| 歌词文件夹导入 | `LyricImporter.kt` | `folders()`、`attach()` | 数字文件名映射到 `netease_lyrics` |
| 备份链接补写 | `BackupSongLinks.kt` | `captureBackupLinks()`、`applyBackupLinks()` | 扫描**正常完成**时尝试应用 pending 链接，取消/异常时不会执行 |

### 3.2 在线账号

| 职责 | 文件 | 关键符号 | 备注 |
| --- | --- | --- | --- |
| 登录页面 | `NeteaseLoginActivity.kt` | `WebView`、`cloud.login(cookies)` | 只接受本应用官方 HTTPS 页面会话 |
| 凭据与账号 | `NeteaseAccount.kt` | `login()`、`logout()`、`accountKey()`、`request()`、`likes`、`stream()` | 凭据加密；`accountKey=SHA-256(uid)` |
| 网络与协议 | `data/NeteaseTransport.kt`、`core/NeteaseWeb.kt`、`data/NeteaseAudio.kt` | EAPI/网页请求、cookie 规范化、音源解析 | 不读取其他应用 Cookie |
| 红心状态机 | `NeteaseLikes.kt` | `refresh()`、`set()`、`reset()` | 乐观更新、失败回滚、session 隔离 |
| 在线曲库门面 | `data/CloudMusic.kt` | `library()`、`tracks()`、`toggleLike()`、`rememberQueue()`、`restore()`、`resolve()` | 歌单页在内存；队列 JSON 落盘 |
| 在线状态持有者 | `OnlineStateHolder.kt` | `resetOnlineLibrary()`、`refreshOnlineLibrary()`、`refreshCloudLikes()`、`playCloud()` | 负责刷新、播放队列和错误提示 |
| 在线 UI | `ui/OnlineLibraryScreen.kt`、`ui/PlayerScreen.kt` | 收藏/红心按钮、我喜欢页 | 云歌用 `likes.ids`，本地歌用 `songs.favorite` |
| 账号切换/退出 | `data/CloudMusic.kt`、`ui/SettingsScreen.kt` | `login()`、`disconnect()` | `resetOnlineLibrary()` 在 `OnlineStateHolder`；清在线缓存和分页，不清本地曲库 |

### 3.3 播放、备份与 Agent

| 职责 | 文件 | 关键符号 | 备注 |
| --- | --- | --- | --- |
| 播放队列持久化 | `PlaybackStore.kt`、`LibraryDatabase.kt` | `savePlayback()`、`playbackSnapshot()`、`playbackSongs()` | `playback_queue` 保存完整活动队列，可含 `netease:` ID，无账号字段 |
| 服务恢复 | `playback/PlaybackService.kt` | `db.playbackSongs(...) + cloud.restore(...)` | `PlaybackSnapshot.restore()` 决定最终顺序，缺失项丢弃 |
| 个人数据备份 | `PersonalDataBackup.kt` | 表白名单、`write()`、`read()` | 不含 Cookie、云端红心、在线歌单 |
| 备份合并 | `BackupMerge.kt`、`BackupSongLinks.kt` | `mergePersonalData()`、`applyBackupLinks()` | 只合并本地表；红心以服务器为准 |
| Agent 动作 | `AgentActions.kt` | `likeRead()`、`likeSet()`、`online.*` | `target=cloud` 才写云端红心 |
| Agent 权限闸门 | `AgentBridge.kt` | `hasPermissions()`、`dispatch()`、`confirm()` | `agentOnline`/`agentLibrary` 按 `target` 在 Bridge 层追加 |
| 在线缓存 | `OnlineAudioCache.kt`、`OnlineCacheWorker.kt`、`data/CloudMusic.kt` | `switchAccount()`、`fillAudioCache()` | `fillAudioCache()` 定义在 `data/CloudMusic.kt`；独立目录 `cacheDir/online-audio`，按账号 scope |
| 本地缓存 | `LibraryRepository.kt` | `audioDir`、`coverDir`、`cleanup()` | 只清未被 `songs` 引用的本地文件；不取 `importLock` |

### 3.4 UI 与状态层

| 职责 | 文件 | 关键符号 | 备注 |
| --- | --- | --- | --- |
| 应用与仓库持有 | `AmiApplication.kt` | `repository`、`artwork`、`maintainArtworkCache()` | 进程级单例 |
| 意图入口 | `AmiViewModel.kt` | `favorite()`、`play()`、`importLyrics()`、`online`/`library` holder | UI 只调意图方法 |
| 本地状态与备份 | `LibraryStateHolder.kt` | `favorite()`、`remove()`、`importLyrics()`、`exportData()/restoreData()` | 备份操作在 `importLock` 内；手动写不持锁 |
| 播放状态 | `PlaybackStateHolder.kt` | `findSong()`、`loadLyrics()`、`currentSong` | 本地/云歌查找与歌词分流 |
| 在线状态 | `OnlineStateHolder.kt` | `refreshOnlineLibrary()`、`favorite()`、`resetOnlineLibrary()` | 见 3.2 |
| 主界面路由 | `ui/AmiApp.kt` | 本地页 0、在线页 0/1、导入条 | 「我喜欢」按来源显示本地收藏或网易云红心；两边都有时复用「本地／网易云」切换 |
| 设置入口 | `ui/SettingsScreen.kt` | 本地音乐、扫描、文件夹授权、退出登录 | 文件选择实际在 `MainActivity` |

### 3.5 核心数据结构

| 结构 | 文件 | 作用 |
| --- | --- | --- |
| `Song` | `core/Models.kt` | 本地/在线共用的歌曲模型；`isCloudSong` 判定的输入 |
| `CloudTrack`、`CloudLikes`、`isCloudSong()` | `core/CloudMusic.kt` | 在线曲目、红心集合、云歌判定 |
| `LibrarySnapshot` | `core/LibrarySnapshot.kt` | 本地曲库的不可变视图、搜索、收藏更新 |
| `PlaybackSnapshot` | `core/PlaybackSnapshot.kt` | 队列、索引、位置、循环/随机；恢复时按 ID 映射 |
| `ScanRecord` | `data/LibraryDatabase.kt` | 扫描指纹、生成的歌曲 IDs、来源与产物文件 |

### 3.6 按问题找代码

| 想改什么 | 先看哪里 |
| --- | --- |
| 支持新音频格式 | `LibraryScanner.audioExtensions`、`readSong()`、`core/NcmDecoder.kt` |
| 改进度/取消 | `ImportWorker.kt`、`LibraryRepository.scan()/cancelScan()`、`AmiApp.kt` 顶部导入条 |
| 改重扫保留哪些用户数据 | `LibraryDatabase.upsert()` 的 ContentValues |
| 改封面优先级 | `LibraryScanner.readSong()` 的 `localCover`/`remoteCover` |
| 改 CUE 行为 | `LibraryScanner.scan()` 的 CUE 分支、`core/Cue.kt`、`ScanRecord.reusable()` |
| 改歌词关联/优先级 | `LyricImporter.kt`、`LibraryDatabase.readSongs()/songLyrics()` |
| 改本地收藏 | `LibraryRepository.favorite()/setFavorite()`、`LibraryDatabase.favorite()`、`LibrarySnapshot.withFavorite()` |
| 改听歌统计 key | `core/ListeningStats.kt:listeningTrack()`、`PlaybackService` 的统计 extras、`LibraryDatabase.playbackSongs()` |
| 改播放队列恢复/检查点 | `PlaybackStore`、`PlaybackSnapshot.restore()`、`PlaybackService` 恢复段 |
| 改在线歌单读取 | `NeteaseAccount.request()` 的 `playlists/albums/tracks/search`、`CloudMusic.library()/tracks()` |
| 改红心 | `NeteaseLikes`、`NeteaseAccount.likes`、`CloudMusic.toggleLike()`、`AmiViewModel.favorite()` |
| 改登录/退出 | `NeteaseLoginActivity`、`NeteaseAccount.login()/logout()`、`CloudMusic.login()/disconnect()` |
| 改在线队列 | `OnlineStateHolder.startCloudQueue()`、`CloudMusic.rememberQueue()/restore()`、`PlaybackService` 恢复段 |
| 改在线音源解析/封面缓存 | `CloudMusic.resolve()`、`NeteaseAccount.stream()`、`OnlineAudioCache`、`OnlineArtworkCache` |
| 改备份 | `PersonalDataBackup.tables`、`BackupMerge`、`BackupSongLinks` |
| 改 Agent 权限/动作 | `AgentActions.likeRead()/likeSet()`、`AgentBridge.hasPermissions()/dispatch()/confirm()`、`AgentProtocol` |

### 3.7 关键位置速查（当前快照）

| 入口 | 位置 |
| --- | --- |
| 文件夹选择 | `MainActivity.kt:61`、`MainActivity.kt:50`（歌词） |
| 添加根目录 / 调度 / 导入锁 | `LibraryRepository.kt:133`、`:142`、`:55` |
| 导入任务 | `ImportWorker.kt:44` |
| 扫描主流程 / flush / add | `LibraryScanner.kt:54`、`:133`、`:152` |
| CUE 分支 / readSong | `LibraryScanner.kt:182`、`:282` |
| 文件身份 / 增量复用 | `DocumentFiles.kt:30`、`ScanCache.kt:9` |
| upsert / 读歌曲 / 队列歌曲 / 收藏 | `LibraryDatabase.kt:349`、`:113`、`:295`、`:400` |
| 歌词导入 | `LyricImporter.kt:23` |
| 登录 / 请求 / 红心定义 / 音源 | `NeteaseAccount.kt:107`、`:141`、`:51`、`:429` |
| 红心状态机 | `NeteaseLikes.kt:41` |
| 在线登录 / 退出 / 曲库 / 恢复 | `data/CloudMusic.kt:194`、`:208`、`:236`、`:393` |
| 在线缓存补齐 / 音源解析 | `data/CloudMusic.kt:510`、`:538` |
| 在线状态 | `OnlineStateHolder.kt:53`、`:59`、`:84`、`:231` |
| 播放查找 / 歌词分流 | `PlaybackStateHolder.kt:89`、`:91` |
| Agent 红心 / 权限 | `AgentActions.kt:183`、`:199`；`AgentBridge.kt:265` |
| 备份表 / 合并 | `PersonalDataBackup.kt:36`；`BackupMerge.kt:8` |
| 播放恢复 | `PlaybackService.kt:385` |
| 统计 key | `ListeningStats.kt:77` |
| 本地页 / 在线页 / 导入条 | `AmiApp.kt:256`、`:284`、`:199` |
| 本地设置 / 退出登录 | `SettingsScreen.kt:143`、`:255` |

## 4. 本地导入流程

调用链：`SettingsScreen/AmiApp → MainActivity.folderPicker → LibraryRepository.addRoot() → WorkManager "library-import" → ImportWorker → LibraryScanner → LibraryDatabase → repository.reload() → LibrarySnapshot`。

1. **授权**：设置 → 本地音乐 → 添加文件夹。`OpenDocumentTree` 取得持久读授权，`addRoot()` 把 tree URI 写入 `roots`，并排一次 `APPEND_OR_REPLACE` 扫描。
2. **调度**：`ImportWorker` 是唯一前台任务；`importLock` 与备份导出/恢复共用。手动“重新扫描”使用 `KEEP`，扫描进行中再次点击不会排队，本次仍按旧任务执行。
3. **遍历**：`DocumentFiles.walk()` 逐级查询，深度上限 48、条目上限 100000；按 `authority:documentId` 去重，同一 provider 下父子目录或重叠授权只产生一个条目。
4. **增量**：指纹由文件时间/大小、CUE 文件、同目录 LRC、封面等依赖的 stamp 组成。`ScanRecord.reusable()` 还要求记录里的歌曲 ID 都仍在 `songs`、封面和 NCM 解码文件都仍在磁盘；否则重读。
5. **读取**：`readSong()` 用 `MediaMetadataRetriever` 取标签和时长；NCM 先解码到 `filesDir/AudioCache`；封面依次尝试内嵌、NCM 容器、同目录 `cover/folder/front`、NCM 元数据里的网易云图片；同名 LRC 解码为侧载歌词；MP3/FLAC/NCM 的网易云 ID 写入 `netease_id`。CUE 虚拟曲目以 `includeLyrics=false` 读取，`neteaseId` 为空。
6. **写库**：按 64 首或约 100 万字符歌词批量 flush，一个事务内执行 `upsert`、CUE 来源行迁移与删除、`scan_cache` 增删。`upsert` 先 UPDATE；UPDATE 不包含 `favorite`、`user_order`、`imported_at`，所以重扫保留收藏、手排曲序和首次导入时间。手动歌词在 `lyric_overrides`，扫描的 `upsert` 不覆盖；CUE 接管删除来源行前，会先把来源行的 `favorite`、`user_order` 和 `lyric_overrides` 迁移到新分轨（`LibraryDatabase.inheritUserData()`）。
7. **收尾**：扫描**正常完成**时调用 `applyBackupLinks()`，再 `reload()`；错误按文件收集，取消/异常时 `finally` 仍会 flush 已完成批次，但不会执行 `applyBackupLinks()`。界面顶部导入条带取消按钮。

## 5. 在线账号流程

调用链：登录 `NeteaseLoginActivity → CloudMusic.login() → NeteaseAccount.login() → SecretStore`；浏览 `OnlineLibraryScreen → OnlineStateHolder → CloudMusic → NeteaseAccount.request()`；红心 `AmiViewModel.favorite() → OnlineStateHolder.favorite() → CloudMusic.toggleLike() → NeteaseLikes.set() → NeteaseAccount.likes.write`。

1. 登录在本应用 WebView 完成，凭据加密保存在 `noBackupFilesDir`；`accountKey` 是 uid 的 SHA-256。
2. 歌单列表、专辑、曲目、搜索、红心均按需读取；歌单与收藏专辑分别请求，一方失败不阻塞另一方，错误提示可分别重试。歌单目录和分页只在内存，当前在线播放队列的曲目元数据写入 `netease-queue.json` 并记录 `accountKey()` 生成的账号标识，当前活动队列的 ID 写入 `playback_queue`。
3. 红心由 `song/like` 写入；`NeteaseLikes` 先乐观更新 `ids+pending`，成功后修补已载入的“我喜欢”前缀，失败按 `before` 回滚。`login/logout` 都会 `reset()`，旧 session 的结果不回写新账号。
4. 登录还会停止 FM、清空音源/失败缓存，并通过 `cacheAccount()` 切换在线音频缓存；不同 uid 会清掉上一账号的缓存。退出登录清凭据、红心状态、在线音频缓存、歌单分页、在线歌词内存、在线队列元数据和 FM 续接状态，并停止、移出播放器中的在线曲目；本地 `songs`、本地收藏、`netease_lyrics`、SAF 授权、听歌统计不变。
5. 客户端没有创建、编辑、删除、收藏/取消收藏歌单或专辑的接口；除红心外，在线路径都是读操作。

## 6. 交叉点矩阵

| 交叉点 | 本地 | 在线 | 是否互相覆盖 |
| --- | --- | --- | --- |
| 歌曲身份 | `sha256(authority:documentId)`；CUE 为 `sha256(identity#曲号)` | `netease:<数字>` / `ami-cloud://` | 不会；命名空间互斥 |
| `songs` 表 | 扫描、收藏、排序、删除、备份链接 | 不写 | 不会 |
| 收藏 | `songs.favorite` | `CloudLikes.ids`（`originalId`） | 不会；`AmiViewModel.favorite` 按 `isCloudSong()` 分流 |
| 歌词 | `song_lyrics` / `lyric_overrides` / `netease_lyrics` | `cloud.lyrics` 网络读取，只缓存内存 | 不会；本地数字文件名歌词会覆盖同 `music_id` 的本地缓存 |
| 听歌统计 | 本地歌按 `neteaseId` 或 `local:digest`；艺人/专辑用名称摘要 | 云歌按 `netease:<id>`；艺人/专辑用结构 ID | 只有歌曲 key 合并；艺人/专辑维度不合并 |
| 播放队列 | `playback_queue` 保存完整活动队列，可含本地与 `netease:` ID | `netease-queue.json` 保存云歌元数据、FM token 与账号标识 | 恢复时按 queue 顺序合并；账号不一致的在线项不载入，本地项保留（见 R3） |
| 备份 | 本地表白名单、统计、译文、`netease_lyrics` | 不含 Cookie、红心、歌单 | 不会向云端重放 |
| Agent | `like.set` 默认本地 | `target=cloud` 才写云端；权限在 `AgentBridge` 追加 | 分流；云端路径接受任意数字 ID（见 R9） |

## 7. 场景处理

| 情况 | 程序行为 |
| --- | --- |
| 未登录导入 | 正常，只写本地。 |
| 登录后导入 | 正常；导入不碰账号接口，在线页不会出现本地歌曲。 |
| 导入中登录/退出 | 导入继续；登录会停止 FM、切换在线音频缓存账号并清掉旧账号缓存，但不动本地曲库和本地红心。 |
| 同一首本地 + 在线 | 两个条目；两个红心；统计只在歌曲 key 上按网易云 ID 合并。 |
| 重复/嵌套授权 | 同 provider 按 `authority:documentId` 去重；不同 provider 暴露同一文件会重复。 |
| 未变化重扫 | 指纹命中，不重新打开音频/LRC/封面；收藏、曲序、导入时间保留。 |
| 文件元数据/歌词/封面变化 | 只重读依赖变化的文件；用户字段保留。 |
| 删除/改名/移动源文件 | 删除后普通 SAF 音频旧行不可播，NCM 旧行仍指向解码副本；改名/移动在 documentId 变化时旧行不可播（同 provider 是否变化未验证）；不自动清理旧行，可能新增一行。 |
| 停止扫描 | 只删偏好里的根目录，不删已导入歌曲，也不取消正在运行的扫描；不会释放系统 SAF 持久授权。 |
| 系统撤销授权 | 下次扫描该根报 `SecurityException`，旧歌曲保留，每次扫描重复报错。 |
| 手动从曲库移除 | 删行并从播放器队列移除；源文件保留；重扫按同 ID 复活，但 `imported_at` 重置，原收藏/曲序已丢。 |
| NCM | 解码副本放私有 `AudioCache`，源文件不动；清理只删未被引用的文件。 |
| CUE | 成功导入会删来源整轨行，删前把收藏、手排曲序和手动歌词迁移到新分轨；删除/编辑 CUE 会留下旧虚拟曲目；一个引用文件失败会让整张 CUE 本次失败。 |
| 备份/恢复 | 只动本地表和统计；不含账号登录、云端红心、在线歌单。 |
| 退出/换账号 | 清在线凭据、红心、在线音频缓存、分页、在线队列元数据与 FM 续接状态，并停止、移出播放器中的在线曲目；本地曲库、本地喜欢、歌词、统计保留。 |
| 换账号后恢复队列 | `netease-queue.json` 的账号标识与当前账号不一致时不载入任何在线曲目并删除旧元数据；本地曲目按原顺序和进度恢复。 |
| Agent 红心 | 默认写本地；显式 `target=cloud` 才写当前账号；权限在 `AgentBridge.hasPermissions()` 按 target 检查。 |

## 8. 重构必须保持的不变量

1. **ID 命名空间互斥**：本地普通歌曲 ID 是 64 位十六进制；CUE 虚拟曲目是 `sha256(identity#曲号)`；在线 ID 必须以 `netease:` 开头且 `uri` 以 `ami-cloud://song/` 开头（`Song.isCloudSong()`）。不要让导入路径生成 `netease:` ID。
2. **`songs` 只由本地路径写**：扫描、收藏、排序、删除、备份链接。在线歌单、红心、在线缓存不得写 `songs` 或 `lyric_overrides`。
3. **收藏与红心分流**：本地 `songs.favorite`；云端 `CloudLikes.ids`。UI、ViewModel、Agent、备份都必须按 `isCloudSong()` 或显式 `target` 分流。
4. **重扫保留用户字段**：`upsert` 的 UPDATE 不能加入 `favorite`、`user_order`、`imported_at`；手动歌词在 `lyric_overrides`，`upsert` 不覆盖。CUE 接管会在同一事务内、删除来源 `songs` 行之前，把 `favorite`、`user_order` 和 `lyric_overrides` 迁移到分轨 ID（`inheritUserData()`）；删行后的 `ON DELETE CASCADE` 不会再影响已迁移数据。
5. **账号内容只读，红心例外**：不要新增创建/编辑/删除歌单的调用；红心写入必须保留 session、pending、失败回滚和账号切换失效。
6. **导入不调用账号接口**：`LibraryScanner` 不调用 `CloudMusic`/`NeteaseAccount`；除读取用户授权的 SAF 文档外，唯一外部请求是无凭据的网易云封面 CDN。
7. **缓存目录分离**：本地 NCM/封面在 `filesDir/AudioCache`、`filesDir/Covers`；在线音频在 `cacheDir/online-audio`；在线封面在 `cacheDir/OnlineArtwork`。清理逻辑不要跨目录。
8. **备份白名单**：不导出 Cookie、API Key、云端红心、在线歌单、SAF 授权、播放队列和原始路径；恢复不向云端重放。
9. **队列恢复按 ID 映射**：`playback_queue`/`playback_checkpoint` 保存完整活动队列，可含 `netease:` ID，且没有账号字段；`netease-queue.json` 保存云歌元数据并记录 `accountKey()` 账号标识。恢复顺序由 `PlaybackSnapshot.restore()` 决定；账号不一致时不得载入在线项，本地项仍按原队列位置恢复。退出登录必须清掉账号相关的队列元数据与 FM 状态。
10. **账号切换 session 隔离**：`NeteaseLikes.reset()` 后，旧请求和旧回滚不得写新账号状态。
11. **Agent 权限在 Bridge**：`agentOnline`/`agentLibrary` 的 target 追加检查在 `AgentBridge.hasPermissions()`，不要只改 `AgentProtocol` 的权限表。

## 9. 已确认的边界与债务

严重度是**用户可感知程度**，不是崩溃概率。所有条目均为静态代码审查，未在真机复现。

| ID | 严重度 | 行为 | 证据 | 测试覆盖 |
| --- | --- | --- | --- | --- |
| R1 | 高 | CUE 成功接管已单独导入的整轨时，删除来源 `songs` 行，收藏、手排曲序随行丢失，外键级联删除手动歌词；删除/改写 CUE 后旧虚拟曲目不清除，可能和重新导入的整轨并存。 | `LibraryScanner.kt` CUE 分支与 `add(removed=...)`；`LibraryDatabase.kt` 的 `ON DELETE CASCADE` | 未覆盖；`MusicCoreTest` 只覆盖 CUE 解析 |
| R2 | 中 | 删除、改名、移动源文件或撤销授权后不清理旧行；删除后旧行不可播，改名/移动取决于 provider 是否保持 documentId；NCM 旧行仍指向解码副本。 | `LibraryScanner.scan()` 只遍历当前文件；`forgetRoot()` 只删偏好 | 部分：`IncrementalScanTest` 覆盖“手动删行后重扫复活” |
| R3 | 中 | 在线队列不按账号隔离：`playback_queue` 含 `netease:` ID 且无账号字段，`netease-queue.json` 只按 FM token 校验 `radioAccount`；`disconnect()` 不清 `catalog`/`queueTracks`，同一进程退出后仍可从内存恢复旧队列。 | `CloudMusic.kt` 的 `queueFile`、`writeQueue`、`restore`、`disconnect`；`PlaybackStore`/`LibraryDatabase.savePlayback()` | 未覆盖 |
| R4 | 中 | 冷启动恢复本地队列时 `playbackSongs` 不取 `netease_id` 和 `album_artist`，同一首本地 NCM 的统计可能从 `netease:<id>` 退化为 `local:<digest>`；`album_artist` 与 `artist` 不同时，恢复后的专辑聚合 key 也会变化。`primaryArtistId`/`albumId` 不是 `songs` 表字段，不应作为本次修复目标。 | `LibraryDatabase.kt:playbackSongs()`、`ListeningStats.kt:listeningTrack()`、`PlaybackService` 统计 extras | `ListeningStatsTest.cacheAndImportedNcmShareAnIdentityButDifferentRecordingsDoNot` 覆盖正常路径，未覆盖恢复路径 |
| R5 | 中 | `importLock` 只覆盖扫描和备份；`favorite`、`reorder`、`remove`、`attach` 手动歌词都不持锁。扫描持有开工时的 `present/records` 快照，CUE 接管或并发手动写可能产生短暂竞态。 | `LibraryStateHolder.kt`、`LibraryRepository.kt`、`LibraryScanner.kt` | 未覆盖 |
| R6 | 中 | 单个 CUE 虚拟曲目无法单独删除：该 ID 不在 `present` 会让整张 CUE 记录不可复用，重扫重建全部虚拟曲目并再次删除来源整轨行。 | `ScanRecord.reusable()`、`LibraryScanner.kt` CUE 分支 | 未覆盖；`MusicCoreTest` 只覆盖 CUE 解析 |
| R7 | 低 | 停止扫描文件夹只删偏好，不调用 `releasePersistableUriPermission`；应用仍保留系统级持久读授权。 | `MainActivity.kt`、`LibraryRepository.forgetRoot()` | 未覆盖 |
| R8 | 低 | 重扫时同名 LRC 暂时读取失败，已有自动侧载歌词可能被清空，直到下一次成功扫描恢复；手动歌词不受影响。 | `LibraryScanner.readSong()` 的 `sidecar` 异常分支、`upsert()` | 未覆盖 |
| R9 | 低 | Agent 的 `like.set target=cloud` 接受任意 1–20 位数字 ID，不校验歌曲是否属于当前 catalog/队列；需要 `agentOnline` 权限和显式 target，权限追加在 `AgentBridge.hasPermissions()`。 | `AgentActions.likeSet()`、`NeteaseLikes.set()`、`AgentBridge.kt` | 协议测试未覆盖该边界 |
| R10 | 低 | `cleanup()` 不取 `importLock`，只做一次 `check(!running)` 就开始列文件删除；这是 check-then-delete 的 TOCTOU：扫描可在检查之后启动并写入 NCM/封面，删除随后发生。 | `LibraryRepository.cleanup()`、`ImportWorker.doWork()` | 未覆盖 |
| R11 | 信息 | 在线歌单目录/分页只在内存，但当前在线播放队列、封面、播放检查点会落盘；因此“在线数据完全不落本地”的说法不准确。 | `CloudMusic.kt`、`PlaybackStore.kt`、`OnlineArtworkCache.kt` | 部分：`OnlineLibraryTest` 覆盖队列不写 URL/凭据 |
| R12 | 信息 | 本地数字文件名歌词会以 `CONFLICT_REPLACE` 覆盖同 `music_id` 的 `netease_lyrics` 缓存；只影响本地歌曲和手机随播，在线播放歌词走网络。 | `LyricImporter.kt`、`LibraryDatabase.cacheLyrics()` | `NeteaseImportTest` 覆盖关联与手动歌词 |

### R1 补充：CUE 的两种失败链

- **成功后接管**：CUE 处理成功 → 生成虚拟曲目 → `removed = sources.map(::digest)` 在同一事务删除来源整轨行。若该整轨此前被收藏、排过序或关联过手动歌词，这些用户数据随行消失；CUE 曲目的 `start/end/track` 不同，备份指纹通常匹配不回来。
- **失败后并存**：CUE 任一引用文件缺失、格式不支持、时间/曲号非法 → 整张 CUE 在 `add()` 前失败 → 不删除来源行、不标记 `handled` → 来源音频在同一轮可能按普通整轨导入，旧虚拟曲目保持原样，于是同一录音出现两份。错误只出现在导入结果列表。

### R3 补充：哪些属于账号、哪些不属于

- 按账号隔离：登录凭据、`likes`、在线音频缓存（`OnlineAudioCache` scope）、FM 续接 token（`radioAccount`）。
- 不按账号隔离：`playback_queue`/`playback_checkpoint`、`netease-queue.json` 的普通队列、`OnlineArtwork` 封面、听歌统计、本地曲库。
- 这不影响“导入 vs 账号歌单”的结论，但属于换账号时的真实残留。

## 10. 测试地图与缺口

### 10.1 现有测试能证明什么

| 测试 | 覆盖 |
| --- | --- |
| `IncrementalScanTest.kt` | 未变化重扫不打开音频/CUE/封面/LRC；侧载 LRC 变化只重开对应音频；手动删行后重扫修复；临时读 LRC 失败可重试。 |
| `ScannerIntegrationTest.kt` | 重叠根目录去重、CUE 虚拟曲目、封面、同名 LRC 关联、重扫保留收藏。 |
| `NeteaseImportTest.kt` | NCM 内嵌网易云 ID 匹配数字文件名歌词；手动歌词覆盖重扫；停止歌词根后缓存仍可用；旧数据库升级保留收藏/曲序/歌词。 |
| `LibraryIntegrationTest.kt` | `upsert` 重扫保留收藏/曲序/导入时间；缓存清理保留 NCM 解码文件、不碰源文件。 |
| `FavoriteUpdateTest.kt` | 本地收藏在 1000 首曲库中的增量更新和旧对象点击。 |
| `NeteaseLikesTest.kt` | 红心乐观更新、去重、失败回滚、旧读取不覆盖新点击。 |
| `OnlineLibraryTest.kt` | 用第一方 HTTP 夹具验证歌单/红心/队列/缓存；不接真实账号。 |
| `PersonalDataBackupTest.kt` | 备份排除凭据/媒体路径/平台授权；收藏与手动歌词等扫描后应用；合并取消整表回滚。 |
| `PlaybackStoreTest.kt` | 位置检查点不重写大队列；旧队列迁移保留位置/模式；清队列不复活旧数据。 |
| `OnlineAudioCacheTest.kt` | 缺失文件与账号切换回退且不共享账号音频；清理不碰导入音乐。 |
| `ListeningStatsTest.kt` | 缓存歌曲与导入 NCM 共享身份；不同录音不合并。 |
| `MusicCoreTest.kt` | CUE 75 帧与多文件解析、NCM 解码/取消/刷新元数据。 |
| `OnlineArtworkCacheTest.kt` | 在线封面缓存、离线复用、过期回收；不涉及账号歌单写入。 |

说明：本表非穷举；没有测试同时覆盖“登录网易云账号 + 本地 SAF 扫描”。

### 10.2 缺口

- **没有“登录状态下导入”的端到端测试**：扫描测试只用 fixture provider 和本地 repository；在线测试只用 HTTP 夹具、不接真实账号。因此“导入与在线互不影响”是代码结构结论，不是运行验证。
- 没有覆盖：CUE 接管已收藏整轨、删除/编辑 CUE、单条 CUE 曲目删除、换账号后队列恢复、冷启动统计字段完整性、导入与手动写的并发。
- 使用说明中关于在原版 App 核对红心的体验声明、真实歌单分类、真实红心同步依赖服务端，未在测试中验证。

## 11. 下次整理代码的入口（建议顺序）

先补测试，再动结构；每一步都应保持第 8 节的不变量。

1. **补“登录 + 导入”隔离集成测试**

   目标：同一测试进程内完成一次本地扫描和一次夹具账号歌单/红心读取，断言 `songs`/`lyric_overrides` 不被在线路径写、`likes` 不被本地收藏改。

   先看：`OnlineLibraryTest` 的夹具模式、`IncrementalScanTest` 的 fixture provider。

2. **修 `playbackSongs` 字段完整性（R4）**

   目标：恢复队列时带回 `netease_id` 和 `album_artist`，避免统计歌曲 key 与专辑聚合 key 漂移。`primaryArtistId`/`albumId` 不是 `songs` 表字段，不要作为本次修复目标；`track/disc/format/favorite` 只有在确认恢复链路消费时再加。

   先看：`LibraryDatabase.playbackSongs()`、`PlaybackService` 恢复段、`ListeningStats.listeningTrack()`。

   先补：恢复队列后 `listeningTrack().key/albumKey` 与正常播放一致。

3. **给在线队列加账号 scope（R3）**

   目标：同时处理 `playback_queue`/`playback_checkpoint` 和 `netease-queue.json`，或在恢复时按账号过滤；退出/换号时不恢复旧账号队列，或明确提示并清空。

   先看：`CloudMusic.writeQueue()/restore()`、`PlaybackService` 恢复段、`PlaybackStore`。

   先补：换号后恢复队列的测试。

4. **明确本地曲目清理策略（R2）**

   目标：决定“删除源文件/撤销授权”是保留幽灵行（当前）还是增加显式清理入口；不要用整库差集静默删用户数据。

   先看：`LibraryScanner.scan()`、`LibraryRepository.forgetRoot()`、`SettingsScreen` 的文件夹管理。

5. **重构 CUE 归属与删除策略（R1/R6）**

   目标：把“哪些来源行由 CUE 接管”“删除 CUE 后虚拟曲目如何迁移/清理”“收藏/手动歌词如何保留”做成显式策略，而不是散在扫描分支里。

   先看：`LibraryScanner` CUE 分支、`core/Cue.kt`、`ScanRecord.reusable()`。

   先补：CUE 接管已收藏整轨、删除 CUE、改写曲号三组测试；解析层已有 `MusicCoreTest`。

6. **收紧导入锁与手动写（R5/R10）及文档**

   目标：明确 `favorite/reorder/remove/attach` 是否需要在扫描期间串行；让 `cleanup()` 与导入锁串行或在锁内复查；把当前“只增不清理”“停止扫描保留歌曲/授权”“换号队列残留”写进用户文档或交接。

   先看：`LibraryStateHolder`、`LibraryRepository`、`USER_GUIDE.md`、`LOCAL_HANDOFF.md`。

## 12. 改动检查清单

改动本地导入/账号相关代码时，至少核对：

- [ ] 本地普通歌曲 ID 仍是 64 位十六进制，CUE 仍是 `identity#曲号`；在线 ID 仍以 `netease:` 开头、`uri` 仍以 `ami-cloud://song/` 开头。
- [ ] `songs` / `lyric_overrides` 的写入者仍只有本地路径。
- [ ] `upsert` 的 UPDATE 没有加入 `favorite`、`user_order`、`imported_at`。
- [ ] 本地收藏与云端红心仍按 `isCloudSong()` / `target` 分流。
- [ ] `LibraryScanner` 仍不调用 `CloudMusic` / `NeteaseAccount`。
- [ ] 在线路径仍只有红心一个写接口。
- [ ] 在线缓存、本地 NCM 缓存、封面缓存目录仍分离。
- [ ] `netease_lyrics` 的写入仍只来自歌词导入和备份恢复。
- [ ] 备份白名单没有加入 Cookie、云端红心、在线歌单、SAF 授权或原始路径。
- [ ] 队列恢复仍按原 IDs 顺序映射；`playback_queue` 的账号归属被显式处理。
- [ ] 恢复后的 `listeningTrack().key/albumKey` 与正常播放一致。
- [ ] 红心写入仍校验 session/pending，失败仍回滚。
- [ ] Agent 的 target 权限仍在 `AgentBridge.hasPermissions()` 追加。
- [ ] CUE 接管时手动歌词/收藏的保留或迁移策略明确。
- [ ] 新增行为有对应测试；没跑过的测试不要写成通过。
- [ ] 文档中的版本、验证范围与实际运行结果一致。

## 13. 范围外与未验证

- 未审计 `cloudPreview` / 电脑连接端分支；本文以 release 默认的手机直连登录为准。
- 未验证真实网易云接口字段形态、红心服务端副作用、真实账号的歌单分类。
- 未验证不同 SAF provider 的 `documentId` 在改名/移动后的稳定性；R2 中“新增一行”取决于该平台行为。
- 未运行 WorkManager、Media3、模拟器或真机；取消、进程死亡、播放恢复时序均为静态推演。
- 未审计音频格式解码能力、CUE 编码覆盖范围、超大曲库/歌词/备份上限。
- 未检查 `AndroidManifest` 的系统备份规则；本文只确认应用自己的备份白名单。
