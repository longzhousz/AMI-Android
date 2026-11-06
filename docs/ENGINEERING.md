# 状态、检查与诊断

## 状态边界

`AmiViewModel` 保留页面使用的协调入口与工厂兼容，内部由三个持有者分工：

- `PlaybackStateHolder`：播放连接、当前曲目投影、歌词加载／预取、翻译及其设置。
- `LibraryStateHolder`：本地写入、歌词关联、Agent 意图与手动备份恢复。
- `OnlineStateHolder`：在线曲库、刷新请求、推荐队列及账号隔离的红心操作。

界面消费只读 `StateFlow`，通过方法提交意图。主题、Agent 开关和权限不再由 UI 同时改流和写偏好；Snackbar 只消费相同内容的消息，旧 Snackbar 结束不会清掉更新的错误。原有异步取消、歌词轮次和账号保护继续生效。

根组合只消费当前播放 ID 对应的歌曲投影，不订阅完整在线 catalog。catalog 或本地快照更新后，当前曲目没有变化就不向根组合发送新值。队列按需查找歌曲，不复制整份曲库。

## 格式和静态检查

统一使用 Spotless 8.11.0 / ktlint 1.8.0；先独立完成格式提交 `4ea431e`，再做行为修改。

```sh
./gradlew spotlessApply
./gradlew spotlessCheck detekt testDebugUnitTest lintDebug assembleDebug
```

Kotlin 官方风格、四空格和 LF 固定在 `.editorconfig` 与 Gradle formatter 配置中。保留 Compose 等现有通配导入；160 字符作为自动布局目标，SQL、协议和资源文本不采用无法自动修正的硬性截断。

detekt 1.23.8 只启用圈复杂度（18）、长函数（80，忽略 Composable）和宽泛异常捕获。`config/detekt-baseline.xml` 来自格式提交后的原有问题：仅迁移拆分前 ViewModel 的既有规则 ID、捕获变量更名与资源化后的参数签名。新备份逻辑按规则拆小；只有必须接住解析／SAF／数据库失败并清理资源的明确边界做带理由的局部抑制。不把全仓库重建基线作为新增问题的处理方式。

CI 执行格式、detekt、JVM、Lint、压缩构建和独立 Android 13／17 原生回归；静态报告与原生证据作为 Actions artifacts 保留。具体通过范围以当前交接为准。

## 私密错误诊断

`AppDiagnostics.errors` 是仅驻内存的 50 条环形记录，只保留枚举错误码、异常类型和 UTC 时间。不保存异常对象、堆栈、消息、路径、URL、账号标识、Cookie 或密钥，不写文件或上传。

播放命令、歌词、翻译、本地保存、在线队列与收藏、统计写入、安装和备份的异常在边界记录。现有「复制播放诊断」按需附带最近五条；没有新增诊断面板或后台任务。

只有 Debug 开启 StrictMode 的主线程磁盘／网络与资源泄漏检查。监听器写同一安全环形记录，不输出可能含私有路径的原始系统消息。正式和优化体验包不启用。

播放页与设置页的主要控件、无障碍标签及长隐私说明已开始迁移到 `res/values/strings.xml`；其余文案逐步迁移，目前不宣称已有英文界面。

更新安装权限只存在 `musicPreview` 清单。其他构建类型不携带 `REQUEST_INSTALL_PACKAGES`，构建检查应查看各变体合并后的清单。
