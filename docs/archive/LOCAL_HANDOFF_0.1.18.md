> 0.1.18 历史交付记录。当前版本与验证见 [当前交接](../LOCAL_HANDOFF.md)。

# AMI 当前交接

当前版本 **0.1.18 / versionCode 19**，交接分支 `main`，本地曲库 schema 4、在线音频缓存 schema 1 保持不变。整理日期：2026-10-29。

## 本轮范围

用户明确要求优化曲库快速回滚时的封面显示，并落实逐字歌词、发现新歌和专辑封面转场。此前缓存升级音质、弱网、锁屏耳机的建议不作为本轮主线。

封面采用有界已浏览预览与高清缓存复用，缓存命中绕过无关下载锁；YRC 附加真实词时间并保留原 LRC 索引；私人 FM／每日推荐共用一个图标入口；网格与详情共享封面、返回保留滚动状态。实现、接口依据与验证边界见 [本轮体验说明](LISTENING_EXPERIENCE_0.1.18.md)。

运行仍只需要手机，不要求 root、电脑连接端、第三方代理或开放平台私钥。沿用 `com.longzhousz.ami.musicpreview`，实际安装包已核对与云端 0.1.17 同签名，可覆盖更新并保留账号、设置、曲库与译文。下载入口为 [最新发布](https://github.com/longzhousz/AMI-Android/releases/latest)。

## 验证状态

应用代码为 `d0cfc62e77c5b1163684423a8551b4ee1582efee`。其后的提交仅修正 QA 的端点断言、截图窗口与测试唤屏隔离，并整理说明；主应用代码保持一致。

本地 79 项 JVM 测试、Lint 和优化 MusicPreview 构建通过；[CI 构建与优化包启动检查](https://github.com/longzhousz/AMI-Android/actions/runs/40844374264) 中的 build、Android 11／17 preview-smoke 作业通过。该次完整流程因测试截图窗口选择失败而为红色，截图读取已经修正；最终设备回归见下一项。

[最终完整设备回归](https://github.com/longzhousz/AMI-Android/actions/runs/40849769209) 整轮通过，QA 提交 `067e28043b2bb1ba29fe852d4f7c7643cd349d9f`：Android 11 和 Android 17 各 114 项，失败、错误和跳过均为 0；两系统的宿主进程回收、系统播放恢复、暂停与前台服务检查也通过。覆盖封面预览回显及淘汰边界、慢下载隔离、逐字亮度与暂停冻结、翻译索引保持、推荐与原生 FM 续接、旧请求取消、共享封面与网格位置保持。

[实际发布 APK 检查](https://github.com/longzhousz/AMI-Android/actions/runs/40849956211) 整轮通过：上传后重新下载同一份签名 APK 并核对 SHA-256，两系统各完成两次冷启动、导航、设置与歌词页面检查。发布的六个附件已下载核对大小、摘要与本地文件，截图均为合成媒体；临时上传分支不合入 main。

交付包 `AMI-Android-0.1.18-online-preview.apk`，2,994,055 bytes，版本 `0.1.18-music-preview` / versionCode 19；APK SHA-256 为 `7bfb77fe8980770ff92937520188949d4af79ae3af61ffc3b35c04d570c796fd`。已核对 v2 签名、16 KiB 对齐和嵌入的应用代码提交。原签名证书 SHA-256 为 `238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`。

当前没有连接 vivo X300。模拟器与合成媒体不能证明真实账号推荐、各歌曲 YRC 覆盖率、手机帧率、耗电、实际耳机输出或原子岛已验收。用户此前反馈 0.1.17 体验良好，不替代新版本验收。

## 开发与数据

先读根目录 [AGENTS.md](../../AGENTS.md)、[本地开发](../LOCAL_DEVELOPMENT.md) 与 [使用说明](../USER_GUIDE.md)。main 包含全部当前应用代码；旧分支无须逐个合并。

保持前台简洁，不新增任务列表、翻译进度、生成时间或模型记录。保留歌曲原图、原有音质请求、用户导入源文件与 NCM 转换结果；缓存回收只管理 AMI 在线副本。签名冲突不通过卸载或清数据解决。

## 缺封面排查

缺封面时区分在线 `OnlineArtworkCache` 和本地 `LibraryScanner` 链路，从少量样本核对来源、磁盘／内存命中与解码，不统一归因于服务器。真机采集和测试安装边界见本地开发说明。

## 历史资料

原 0.1.17 交付与验证见 [播放流程归档](PLAYBACK_FLOW_0.1.17.md)，更早版本见 [archive/](README.md)。Git 提交历史保留；旧版发布收为草稿，安装包、截图与说明在本地下载目录按版本归档。
