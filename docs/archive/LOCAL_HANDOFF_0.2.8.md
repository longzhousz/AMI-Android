# AMI 当前交接

当前版本 **0.2.8 / versionCode 30**，最低 Android 13 / API 33，compile / target API 37。本地曲库升级至 schema 6，在线音频缓存 schema 1 不变。应用源码 `068cd5d7f7ef064f801c3cbdc4d9f80cd7ccd94d`，之后的提交只更新交付文档。已发布 0.2.7 的身份与证据保存在 [上一版交接](LOCAL_HANDOFF_0.2.7.md)。

## 本轮行为

设置增加「备份与恢复」，通过系统文件选择器导出／导入 JSON。保存统计、本地收藏、非机密设置、手动歌词关联和已有译文；排除会话、密钥、Agent 调用授权、媒体文件、路径与文件夹授权。导入先验证，再事务合并，保留当前数据；同源统计取较大累计值，不同安装来源相加，重复导入不重复累计。源 UUID 只占数字计数的索引，不记录逐次事件。旧 schema 5 数据原样迁入本机来源。

本地关联等待重新授权并完整扫描后匹配，歧义不猜；旧译文可沿历史歌曲 ID 查询，无需再次请求。网易云红心仍由账号服务同步。完整规则与限制见 [备份恢复](../DATA_BACKUP.md)。

播放、在线、本地状态分别由三个持有者管理，主题、Agent 和提示公开只读流，经意图方法持久化；根组合改为订阅当前歌曲投影。安装权限只在 musicPreview 清单。诊断只驻内存，保存最近 50 条固定错误码、异常类型和时间，原有复制入口附带最近五条；StrictMode 只在 Debug 启用。播放与设置主要控件及长隐私说明开始资源化。见 [状态、检查与诊断](../ENGINEERING.md)。

README 改为稳定中英产品介绍，更新记录留在 Releases；AGENTS 只保留长期协作规则，具体行为移至 [产品约束](../PRODUCT_CONSTRAINTS.md) 和专题。全量格式已先独立提交 `4ea431e`，Spotless / ktlint 与窄范围 detekt 进入 CI。

## 验证范围

- 115 项 JVM 测试、Spotless、detekt、Lint 和优化构建通过；七种变体的合并清单已核对，只有 musicPreview 带安装权限。
- [完整原生回归](https://github.com/longzhousz/AMI-Android/actions/runs/42203182026)：Android 13／17 各 164 项通过，逐项核对测试名、数量，均无失败、错误或跳过，后台回收及媒体恢复通过。覆盖 schema 3／4／5 升级、重复／增量／三设备合并、取消事务回滚、坏文件拒绝、收藏／歌词关联、离线译文复用与设置入口。
- [实际签名包覆盖验证](https://github.com/longzhousz/AMI-Android/actions/runs/42203951143)：Android 13／17 从 0.2.7 覆盖安装，完成两次启动、设置、聆听周期与歌词入口；实际安装文件摘要与交付一致，崩溃缓冲区为空。截图、窗口树与摘要已查看。
- 原生备份对话框截图已查看；没有用关闭应用动效或降低画质处理测试问题。
- 三年合成规模：3,000 首元数据、每天 50 首，逻辑数据库新增约 1.32／1.32 MiB，单次年报约 30／149 ms（Android 13／17）。该夹具不含真实封面地址，不用于承诺真实曲库固定占用、帧率或续航。

首轮 Android 17 的三项新测试把样例 Song 标为喜欢，却没有调用数据库的收藏意图，夹具未真正保存收藏；已修正夹具并完整重跑。首轮 Android 13 在第 124 项期间模拟器断连，报告不完整，不计为通过；最终保持 Google APIs 33／4 GiB 与完整动效，使用 Ubuntu 24.04 和 ANGLE + SwiftShader 后端（`-gpu swangle`）重跑。原直接 SwiftShader 路径在单个图形用例上也可使模拟器退出；ANGLE 单例验证通过后用于完整回归。失败报告保留在 [首轮记录](https://github.com/longzhousz/AMI-Android/actions/runs/42195105725)。

没有连接 vivo X300、表弟手机或真实网易云账号，不新增真机播放、音质、原子岛、帧率或耗电结论。

## 安装包

`AMI-Android-0.2.8-online-preview.apk`，5,577,526 bytes；包名 `com.longzhousz.ami.musicpreview`，版本 `0.2.8-music-preview` / versionCode 30。SHA-256：

```text
bf9ce793553d78b8f076f96ba5d9f5d9efff39d9c7928f16a121dc524d626543
```

证书 SHA-256：`238e0c33d7deebc6dd873ae8f0e844cf626b873f2ddf2ea8900d4916e2dd6f39`，与云端 0.2.7 相同；可覆盖云端 0.1.14～0.2.7，保留登录、设置、曲库、歌词和聆听记录，不卸载或清数据。v2 签名、16 KiB 对齐、最低系统、嵌入源码和三个附件已核对。

[0.2.8 发布](https://github.com/longzhousz/AMI-Android/releases/tag/android-v0.2.8-preview) 提供 APK、使用说明与校验清单，更新日志只放 Releases。旧发布及附件转草稿保留，历史标签不删除，APK 不进入 main。
