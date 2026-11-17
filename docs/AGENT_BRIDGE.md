# Agent Bridge 接口

AMI 是 agent-music-interface。Agent 通过用户显式授权的 SAF 交换文件夹交换 JSON，不需要网络端口、常驻服务或 root。只有应用在前台时每 3 秒检查一次文件夹，单次最多处理 20 个请求，每个请求不超过 32 KiB。

## 启用与请求格式

在「设置 → 更多设置 → Agent 与自动化」选择交换文件夹、生成令牌、启用外部调用，并按用途打开权限。令牌仅生成时显示一次，不写入结果或日志。

写入 `<request_id>.request.json`：

```json
{
  "request_id": "job-2026-001",
  "token": "<在设置中生成的令牌>",
  "action": "like.set",
  "payload": { "songId": "song-1", "liked": true }
}
```

`request_id` 只允许 1–80 个 ASCII 字母、数字、`_` 或 `-`。AMI 写出 `<request_id>.result.json`，随后删除请求文件：

```json
{
  "request_id": "job-2026-001",
  "action": "like.set",
  "status": "success",
  "ok": true,
  "created_at": "2026-11-17T00:00:00Z"
}
```

`status` 可以是 `success`、`duplicate`、`invalid_request`、`unauthorized`、`blocked`、`unknown_action`、`requires_ui`、`requires_confirmation`、`cancelled`、`timeout`、`conflict` 或 `failed`。只有 `success` 的 `ok` 为 `true`；失败时可能带固定枚举 `error`，不会带原始异常文本、URL、路径或令牌。

`payload` 禁止携带 `uri`、`path`、`token`、`cookie`、`apiKey`、`lyrics` 等字段，以及 `://` 或以 `/` 开头的字符串。命中时返回 `invalid_request` 和 `unsupported_field`。需要 SAF 授权或登录的操作只能在界面完成，对应 action 返回 `requires_ui`。

## 权限与动作

八组权限的默认值为：`agentPlayback` 开；`agentQueue`、`agentReorder`、`agentTranslation`、`agentCache`、`agentOnline`、`agentLibrary`、`agentStats` 关。`like.read` 与 `like.set` 根据 `payload.target` 追加检查：`cloud` 用 `agentOnline`，本地红心用 `agentLibrary`。`lyrics.import`、`lyrics.clear` 同时需要 `agentLibrary`。

| action | 所需权限 |
| --- | --- |
| `player.pause` / `player.play` / `player.toggle` / `player.next` / `player.previous` / `player.seek` / `player.jump` / `player.mode` / `player.stop` / `player.get` | `agentPlayback` |
| `queue.get` | `agentPlayback` |
| `queue.set` / `queue.add` / `queue.remove` / `queue.clear` | `agentPlayback`, `agentQueue` |
| `queue.reorder` | `agentPlayback`, `agentQueue`, `agentReorder` |
| `album.open` | `agentLibrary` |
| `album.play` | `agentPlayback` |
| `album.reorderTracks` | `agentReorder` |
| `like.read` / `like.set` | `agentOnline` 或 `agentLibrary`，按 `target` |
| `online.likes.read` / `online.library.read` / `online.tracks.read` / `online.search` / `online.discover` / `online.library.refresh` / `online.likes.refresh` / `online.prepare.cancel` | `agentOnline` |
| `online.play` | `agentOnline`, `agentPlayback` |
| `lyrics.translateCurrent` / `lyrics.clear` / `lyrics.models.read` / `lyrics.models.refresh` / `lyrics.model.set` / `lyrics.style.set` | `agentTranslation` |
| `lyrics.import` | `agentLibrary` |
| `library.scan` / `library.scan.cancel` / `library.remove` / `library.roots.read` / `library.roots.remove` | `agentLibrary` |
| `cache.getStats` / `cache.cleanupDryRun` / `cache.cleanupApply` / `online.cache.cleanupApply` | `agentCache` |
| `stats.report` / `stats.clear` | `agentStats` |
| `bridge.status` / `agent.cancel` / `agent.requests` | 无额外权限 |

### 播放与当前队列

`player.seek` 用 `positionMs`，`player.jump` 用 `index`，`player.mode` 用 `loop`、`one` 或 `shuffle`。`queue.get` 返回当前 Media3 队列的 `songIds` 和 `currentIndex`。`queue.set` 使用 `songIds` 与 `index` 重建队列；`queue.add` 使用 `songIds` 和可选 `index`；`queue.remove` 使用 `songIds`；`queue.clear` 清空队列；`queue.reorder` 用目标顺序的完整 `songIds`。队列操作在私人 FM 播放时返回 `failed`。`album.reorderTracks` 修改的是专辑持久曲序，不用于编排当前播放队列。

### 红心与在线

`like.read` / `like.set` 使用 `songId` 和显式 `liked`；`target` 省略时为本地红心，填 `cloud` 时为网易云红心。网易云红心返回 `ready` 为 false、存在未确认写入（`pending`）或未登录时拒绝执行。结果不会把封面或音源 URL 写回交换文件夹。

`online.search` 使用 `query` 和可选 `offset`；`online.discover` 使用 `kind`（`fm` 或 `daily`）。两者返回可播放状态与元数据，调用 `online.play` 时传回 `tracks` 和 `index`。`online.library.refresh`、`online.likes.refresh` 和 `online.prepare.cancel` 分别刷新在线曲库、刷新红心和取消正在准备的在线队列。

`online.library.read` 返回当前账号的收藏专辑与自建歌单 `collections`，以及「我喜欢的音乐」`liked`；每项含 `id`、`title`、`kind` 和 `count`，专辑另含 `artist`，不返回封面或音源 URL。`online.tracks.read` 使用 `id`、`kind`（`album`、`created`、`liked`，也可用 `playlist`）和可选 `offset`，按每页 30 首返回与搜索、发现相同的 `tracks`、`nextOffset` 和 `more`；取得后可直接传给 `online.play`。`online.likes.read` 仍返回红心歌曲 ID 与同步状态；需要歌曲资料时读取 `liked.id` 对应的歌单。

### 歌词、曲库、缓存与统计

`lyrics.translateCurrent` 可传 `force`；`lyrics.clear` 与 `lyrics.import` 使用现有歌曲、歌词文件夹能力；`lyrics.model.set` 使用 `selection` 与可选 `automaticUpdates`；`lyrics.style.set` 使用不超过 2000 字的 `style`。译文正文和私有一个人路径不会进入结果文件。

`library.scan` 可传 `afterCurrent`；`library.scan.cancel` 取消正在进行的扫描；`library.remove` 用 `songId`；`library.roots.read` 返回文件夹的 SHA-256 标识而不返回原始 URI；`library.roots.remove` 用 `scope`（`music` 或 `lyric`）和该标识。`cache.getStats` 与 `cache.cleanupDryRun` 返回有界统计。`stats.report` 使用 `scale` 与 `anchor`，只返回聚合结果，最多十项排行。

## 确认、取消与边界

`cache.cleanupApply`、`online.cache.cleanupApply`、`stats.clear` 先返回 `requires_confirmation`，并在交换文件夹写出 `<request_id>.pending.json`，此时不写 `result.json`、也不删除原请求。在 Agent 设置中确认或拒绝后，才按当前权限执行动作、写出最终 `<request_id>.result.json` 并删除请求；确认请求 15 分钟未处理会超时，进程重启后遗留的 pending 文件也会在轮询中按时间或原始请求缺失收尾。确认期间不允许 JSON 调用方传入 URI 或替用户点确认。

`agent.cancel` 用 `target_request_id` 取消当前仍在执行、且尚未写结果的请求；被取消请求的结果为 `cancelled`。找不到目标时返回 `conflict`。`agent.requests` 返回当前待确认和执行中请求的 ID、动作、状态、时间与字节数，不含令牌、路径或 payload。`online.prepare.cancel`、`library.scan.cancel` 是独立业务取消，不等同于请求级取消。

登录、退出、Webview Cookie、令牌生成／重置、交换文件夹和曲库文件夹授权、手动关联歌词、备份导出／恢复、更新安装只允许在界面操作；其 action 若被 JSON 调用，返回 `requires_ui`。Bridge 不接受 JSON 调用方传入的 URI 或路径，只使用此前在界面授权的 SAF 目录；打开 `agentLibrary` 后，`library.scan` 会扫描已授权音乐与歌词根目录，`lyrics.import` 会扫描已授权歌词根目录。Bridge 不保存请求 payload 或令牌，不新增网络监听端口。
