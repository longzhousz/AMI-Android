# vivo / OriginOS 音乐兼容性

核对日期：2026-10-25。用户确认目标为 **vivo X300 / OriginOS 7 / 原子岛音乐显示与控制**；完整系统构建号和原子岛组件版本尚未采集。当前工作区没有连接该真机。

## 标准音乐应用接入

AMI 使用 Media3，并按 Android 标准提供以下声明和接口：

- Application 的 `android:appCategory="audio"`；
- 独立的 `ACTION_MAIN` / `CATEGORY_APP_MUSIC` 启动入口；
- `mediaPlayback` 前台服务、MediaSession、系统媒体样式通知；
- `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` 音频属性；
- 歌曲的音乐媒体类型、标题、歌手、封面与时长元数据；
- MediaLibraryService 与 `android.media.browse.MediaBrowserService` 连接入口，供平台/旧版客户端获得媒体控制会话；
- 系统播放恢复请求等待队列恢复完成；标准媒体按键接收器支持系统在进程回收后请求恢复。普通浏览根为空，系统最近播放根最多返回一首可恢复曲目，不导出整个私人曲库。

这些是音乐应用的标准声明，不是向 vivo 注册或获取专属能力的凭据。Android 对 appCategory 的公开说明是用于分类统计等场景，不能据此保证 OriginOS 的原子随身听或音效名单一定收录 AMI。

## vivo 官方公开文档核对

[自定义通知适配规范（901）](https://dev.vivo.com.cn/documentCenter/doc/901) 要求优先使用标准通知样式，并明确媒体播放应使用系统媒体播放样式。AMI 采用 Media3 的标准媒体通知，不另造通知布局。

[音频类 AudioManager 接口使用规范（106）](https://dev.vivo.com.cn/documentCenter/doc/106) 要求正确使用音频流与路由，避免错误修改全局静音、通话模式及蓝牙路由。AMI 由 Media3 处理媒体音频属性、焦点及耳机拔出行为，不设置全局通话模式或麦克风静音。

[原子通知接入指导（894）](https://dev.vivo.com.cn/documentCenter/doc/894) 明确要求应用先在 vivo 应用商店上架，然后提交材料申请，经过准入、需求/UI评审、权限开通、开发联调后逐步上线。[原子通知规范（895）](https://dev.vivo.com.cn/documentCenter/doc/895) 与[技术规范（896）](https://dev.vivo.com.cn/documentCenter/doc/896) 是该能力的产品与接口要求。

原子通知、原子岛上的音乐能力、原子随身听并非同一个接口。当前公开文档目录中未找到“设置一个音乐标志即可让任意应用加入原子随身听”的通用接入文档，因此不把原子通知审批流程当成原子随身听的保证，也不采用冒用其他音乐软件包名或随意写入未公开参数的方式。

旧版官方客服的[原子随身听使用说明](https://kefu.vivo.com.cn/robot/imgmsgData/9ce81582ecfd49ec88d88736f29fce71/index_1.html?t=1641643370734) 描述了设置播放源的入口，但该资料较旧，不能推定当前所有 OriginOS 版本支持相同的应用列表或规则。

上述开发者站点页面使用脚本渲染，正文已通过其同域公开文档接口 `/webapi/doc/info?id=...` 核对，未登录开发者账户或提交接入申请。

## X300 / OriginOS 7 的原子岛

本次验收目标是原子岛的音乐卡片。标准媒体会话、锁屏控制和最近播放恢复的自动化结果不能替代该卡片的真机验收；原子通知的接入流程也不能证明系统音乐卡片自动收录了 AMI。

2026-10-25 再次检索 vivo 开发者公开目录，未找到面向任意音乐应用的原子岛音乐卡片通用注册接口。vivo 的原子通知指导仍要求上架、准入、权限开通与联调；[阿里云的 vivo 原子岛推送接入说明](https://help.aliyun.com/zh/document_detail/3030718.html)也明确依赖 vivo 原子岛权限。该通知路径不能直接视为音乐卡片支持。

同时核对了开源 [MeloX 的 VivoAtomicIslandBridge](https://github.com/lladlam/MeloX-Android/blob/main/android/app/src/main/kotlin/com/lladlam/melox/platform/vivo/VivoAtomicIslandBridge.kt)。它通过独立通知及 `notification.superx.*` 扩展尝试展示，每 10 秒节流；代码中的尝试发布不是取得系统准入或 X300 / OS 7 实测成功的证据。AMI 本次不引入这条额外通知通路；继续使用标准音乐通知与 MediaSession，避免在普通通知栏重复出现音乐卡片。

下一步真机证据应包含完整系统构建、`com.vivo.musicwidgetmix` 等实际存在的系统音乐组件版本、AMI 媒体会话、音乐通知、原子岛相关系统日志，以及同一首歌曲在系统媒体面板与原子岛的显示差异。未获得对应证据或厂商确认前，不把“已被系统识别为音乐软件”写成原子岛适配完成。

## 真机核对项目

1. 先区分缺失的是普通系统媒体通知/锁屏控制，还是原子随身听、原子岛、特定音效等专属入口。
2. 记录手机型号、OriginOS 完整版本和相关系统组件版本；播放时核对系统媒体面板的歌名、封面、播放/暂停、切歌与进度控制。
3. 在系统提供的播放源列表中检查 AMI 是否可选，确认正式包与预览包的实际包名。
4. 如果系统仅开放给获准应用，继续依官方渠道确认该具体能力的准入条件，而不是把“允许后台运行”当成音乐分类注册。

目前的预览包名为 `com.longzhousz.ami.preview`，正式包为 `com.longzhousz.ami`。设备上的最终识别结果需以对应包和系统版本验证。

## Android 官方依据

- [application / appCategory](https://developer.android.com/guide/topics/manifest/application-element#appCategory)
- [CATEGORY_APP_MUSIC](https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_MUSIC)
- [后台媒体会话](https://developer.android.com/media/media3/session/background-playback)
- [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content)

0.1.8 的自动化场景、证据与真机采集步骤见 [Android 设备验证](ANDROID_DEVICE_QA.md)。
