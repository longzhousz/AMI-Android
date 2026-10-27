# 网易云连接试用：0.1.11

2026-10-27。基于 `main` 的 0.1.10（`d8b06a3`），保留 NCM 封面补全和此前播放页动效。应用版本 0.1.11 / versionCode 12，数据库 schema 仍为 4。

## 这版怎么用

AMI 保留自己的界面与 Media3 播放器。Windows 运行网易云官方 `@music163/ncm-cli` 和本仓库的小型连接端；手机读取自己的歌单、搜索、歌词，并获取官方 CLI 已授权的播放地址。音频由手机直接连接网易云 HTTPS CDN，电脑不转码、不下载音频，也无需 root 或安装 mpv。

Windows 窗口需要保持打开；手机与电脑处于同一可信局域网。首版不是独立的手机网易云客户端。CLI 的版权范围与网易云 App 不同，会员身份不能保证所有歌曲都能在 CLI 播放。标记为试听或不可用的记录不加入 AMI 播放队列，可点击跳到网易云 App；没安装时会说明原因。

## Windows 首次启动

1. 安装 [Node.js 官方 LTS](https://nodejs.org/)，版本 22 或更新。解压连接端 ZIP 到固定目录，例如 `C:\AMI-NetEase`；升级时保留目录位置。
2. 双击 `Start.cmd`。第一次通过 npm 按锁定版本安装官方 CLI，然后启动配置向导。
3. 如果还没配置 CLI，在[网易云开放平台](https://developer.music.163.com/)的应用管理打开自己的个人 CLI 应用，复制 **App ID 和 Private Key** 到官方向导。这里需要 Private Key，不是 App Secret，也不是手机号密码。私钥只在自己的电脑输入。
4. 使用向导显示的官方登录二维码或授权网址，用网易云 App 扫码并确认。云端验证时该账号已经存在一个上线的个人 CLI 应用，无需重复创建。云端已登录不代表 Windows 也已登录，首次需要在自己的电脑再授权一次。
5. 等窗口显示「AMI 网易云连接端已启动」。Windows 防火墙若询问 Node.js 联网，允许可信的**专用网络**。
6. 安装「AMI 网易云试用」APK，打开「网易云 → 连接网易云」，粘贴窗口显示的**完整连接地址**，包含 `/#` 后的连接码。看到多个地址时，选与手机所在 Wi-Fi 同网段的一个。
7. 打开歌单，或在搜索框输入并按键盘搜索键。播放后使用 AMI 原有的歌词、封面转场、队列、锁屏与媒体控制。

Android 17 首次连接时会按系统要求请求局域网 / 附近设备权限，用于访问自己电脑上的连接端；拒绝时不会继续连接，可授权后重试。Android 16 及更早系统没有这项强制请求，不需要定位或扫描 Wi-Fi。

以后双击 `Start.cmd` 即可，未失效的官方登录与连接码会复用。连接码保存在 Windows 用户目录 `%LOCALAPPDATA%\AMI\netease\pairing.json`；删除此文件并重启连接端可换码，再在 AMI 重新连接。不要把连接地址贴到公开地方或把端口映射到公网。

连接端使用官方 CLI 的 mpv 播放后端，通过 mpv 公开的 JSON IPC 协议接收地址，交给 AMI 播放；这是传输适配器，不包含音频解码器。它会把当前用户的官方 CLI 播放器选项设置为 mpv，因此同一用户的这套 CLI 应专用于 AMI。如果以前用过另一套 ncm-cli，旧后台播放器仍在运行时，先结束旧会话，再启动连接端，避免播放交接到旧实例。

## 与已有安装并存

试用构建 `cloudPreview` 的包名为 `com.longzhousz.ami.cloudpreview`，显示名为「AMI 网易云试用」。它不会覆盖用户本地签名的 0.1.10，也不会自动取得旧包的文件夹授权、收藏或私有歌词。需要测试本地歌曲时，在新包里单独授权导入；日常包继续保留使用。

Debug / Preview / CloudPreview 为可信局域网连接允许 HTTP，媒体仍走 HTTPS。正式 Release 的清单禁止明文流量，连接配置也要求 HTTPS；若要取消电脑依赖，需等待官方开放个人移动端能力或取得适用的厂商接入资格，不能简单把私钥搬进 APK。

## 存储、权限与后台

- Android 不保存 App ID / Private Key、官方 CLI 登录凭据或长效播放 URL。电脑连接地址用 Android Keystore 加密后写入不参与备份的目录。
- 在线音频由 Media3 流式读取，无下载功能或整曲磁盘缓存；在线封面只用内存缓存，歌词缓存预算 2 MiB，退到后台释放。
- 只持久化最后在线队列的必要曲目信息，最多 1000 首；不复制在线曲库到本地数据库。退出重进可恢复队列元数据，未主动播放时不请求音频。Windows 重启后若提示重新打开歌单，重新进入该歌单或搜索结果即可建立当前会话的歌曲记录。
- 歌单、搜索、歌词按操作读取；每页 30 首，点击「更多歌曲」继续。连接端短期缓存读请求，不做后台轮询，也不自动收藏、修改歌单、发评论、下载或翻译。
- 当前应用有每日调用额度，实际以开放平台显示为准。调用失败先检查官方 CLI 登录、网络、额度和曲目权限。

## 本地开发与验证

```powershell
# 仓库根目录，JDK 21 / Android SDK 37.0
.\gradlew.bat testDebugUnitTest lintDebug assembleCloudPreview
# 连接端，无账号即可运行的协议/权限回归
node --test tools/netease-bridge/bridge.test.mjs tools/netease-bridge/adapter.test.mjs
```

APK 位于 `app/build/outputs/apk/cloudPreview/`。连接端只依赖 Node 标准库及锁定的官方 CLI 0.1.7，无第三方逆向 API 服务。`Start.cmd` 从锁文件安装依赖，首次需要联网。不要提交 `node_modules`、官方配置、配对码、签名地址、私人歌单响应和浏览器登录信息。

云端真实账号已验证：登录、8 个自建/收藏歌单汇总、非空歌单分页、歌曲搜索、歌词及官方 CLI 播放交接；对 HTTPS MP3 进行小范围读取，返回 206 与有效 ID3 数据。没有连接手机，这些结果不能证明 vivo X300 上的播放、动效、蓝牙或原子岛效果。自动化 Android 测试使用本地夹具，不访问真实账号；当前 CI 的实际结果见对应 Actions 运行记录。

## 官方依据

- [开发者常见问题：个人账号仅支持 ncm-cli、CLI 与 App 版权差异](https://developer.music.163.com/st/developer/document?docId=3b75ab8e475d41ca93d91ebd4dfd383f)
- [网易云官方 ncm-cli](https://www.npmjs.com/package/@music163/ncm-cli)
- [网易云官方技能与 CLI 使用资料](https://github.com/NetEase/skills)
- [Android 17 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)

早期研究对直接接入厂商 IoT API 的判断不适用于当前个人账号。这版通过官方 CLI 验证，不使用冒用设备身份、绕过试听边界或提取网易云 App 内部凭据的方案。
