# AMI 当前交接

当前版本 **0.2.5 / versionCode 27**，最低 Android 13 / API 33，compile / target API 37。应用源码 `56b8370f7176dc839522b4c5691ae97591d2a33b`；后续提交仅整理文档和测试环境。本地曲库 schema 5、在线音频缓存 schema 1 不变。

## 已登录但未取得音源

本轮反馈为另一部手机上《会魔法的老人》等部分歌曲返回“网易云未返回这首歌的可播放音源”，用户明确该账号有 VIP 且已登录。截图只能定位到音源解析失败，不能证明会员失效、设备解码问题或确定的服务端原因。

发现并修正将 AMI 版本号作为网易云客户端 appver 的协议错误。Android 9.5.61、桌面 3.1.17.204416 的兼容上下文独立于应用版本，加密 header、HTTP Cookie 和 UA 一致；Cookie 保留原会话，处理百分号编码时保留加号。Android 端点失败后尝试固定第一方桌面端点，再用同一账号走网页端点及原有 HQ 回退，各客户端本轮失败后不再重复尝试，最多七次请求。真实网页登录失效仍立即停止。

缺少 __csrf 时，每轮登录仅向官方 HTTPS 首页补齐一次校验／会话字段，只接受 __csrf 和 NMTID，不接受另一个 MUSIC_U，不读取页面内容或跨域跟随。网络不持有账号锁，迟到结果不能保存到新账号。音源兼容 data 为对象或数组、单项缺 code；仍验证顶层状态、歌曲 ID、第一方地址、完整时长与试听标记。仅播放本账号获得的完整音源，最高授权音质优先。

实际失败时，提示增加「复制诊断」操作；也可在播放页右上角复制。诊断仅记录固定路径、数字状态、音源／试听标记和公开歌曲 ID，加上本地版本机型；不含 Cookie、UID、签名地址或原始消息。每账号最多三首、每首最多八条请求记录，仅内存保存，成功／登录退出清除，不自动上传；健康状态不增加菜单项或面板。详见 [播放流程](../PLAYBACK_FLOW.md) 与 [手机排查方法](../USER_GUIDE.md#播放失败排查)。

## 验证

- 本地 **112 项 JVM** 全部通过，Lint 无错误，Debug／AndroidTest 及优化 MusicPreview 构建通过。
- [Android 13 完整回归](https://github.com/longzhousz/AMI-Android/actions/runs/41296640799/job/124818778435)与 [Android 17 完整回归](https://github.com/longzhousz/AMI-Android/actions/runs/41296640799/job/124818778336)各 **146 项**，均无失败、错误或跳过；后台回收及系统媒体键恢复通过。下载的原始 XML 与结果已核对。
- [Android 13 定向验证](https://github.com/longzhousz/AMI-Android/actions/runs/41297486170/job/124821286373) **8 项**通过，覆盖同账号回退、完整缓存／缺文件回退、URL 到期与失效、旧账号隔离、一次会话补齐、高音质实际响应、诊断脱敏及显式复制到系统剪贴板；后台回收恢复也通过。逐项原生结果均验证，应用源码与上述版本一致，临时分支只调整测试脚本和模拟器配置。
- 常规 Android 13 Google APIs 模拟器的早期压缩包检查因系统未稳定而失败；签名包在 Ubuntu 24.04／AOSP 33／2 GiB／SwiftShader 上通过。Android 17 首次签名包检查的 system_server 在 TaskSnapshot 图形缓冲中崩溃，采用已通过完整回归的宿主镜像重试。未因此修改或降低应用画质；失败尝试保留在 Actions，不计为通过。
- 实际云端签名 APK 的 [Android 13 检查](https://github.com/longzhousz/AMI-Android/actions/runs/41297834240)和 [Android 17 检查](https://github.com/longzhousz/AMI-Android/actions/runs/41298120713)均在覆盖安装 0.2.4 后完成两次启动、设置、聆听图鉴与歌词导入入口，验证后台更新任务完成；三个发布附件下载后与本地 APK、中文说明和校验清单逐字节一致。v2 签名、16 KiB 对齐、嵌入源码、版本及相同云端证书已核对。

没有连接表弟手机或真实网易云账号，不能据此宣称其问题已经修好。下一步请他覆盖更新后重试同一首歌；若失败，复制诊断，并确认官方 App 同账号、同网络能否完整播放。不要索要凭据或要求卸载清数据。vivo X300 的真实帧率、耗电、音效及原子岛也没有新增真机证据。

播放控制、四种模式、下一首准备、专辑共享转场、液态玻璃、歌词与缓存规则保留，见 [0.2.4 历史交接](LOCAL_HANDOFF_0.2.4.md) 和当前功能文档。

## 安装包

`AMI-Android-0.2.5-online-preview.apk`，5,472,158 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.5-music-preview` / versionCode 27。SHA-256：

```text
a933e135e73790e9fc3d9e2d627789d380faccbd33fb5286ae428a380ed62dbe
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.4 相同，可覆盖云端 0.1.14～0.2.4，保留登录、设置、曲库、歌词和聆听记录，不卸载或清数据。

[0.2.5 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.5-preview)包含 APK、中文说明和校验清单。0.2.3 起启动后可后台获取更新，进入设置才询问安装。旧版发布转草稿，历史及标签保留；临时上传／验证分支清理，安装包不进入 main。
