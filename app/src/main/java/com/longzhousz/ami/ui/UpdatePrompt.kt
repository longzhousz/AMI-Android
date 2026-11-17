package com.longzhousz.ami.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.data.AppUpdates
import com.longzhousz.ami.data.ManualUpdateCheck

@Composable internal fun UpdatePrompt(
    updates: AppUpdates,
    settingsVisible: Boolean,
    onMessage: (String) -> Unit = {},
    onInstall: () -> Unit,
) {
    val update by updates.ready.collectAsStateWithLifecycle()
    val manual by updates.manualCheck.collectAsStateWithLifecycle()
    var offered by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(updates, settingsVisible, update?.code, manual) {
        if (!settingsVisible) {
            offered = null
        } else {
            when (manual) {
                ManualUpdateCheck.AVAILABLE -> {
                    val available = updates.ready.value
                    if (available != null) {
                        updates.claimPrompt(available.code)
                        offered = available.code
                        updates.consumeManualCheck(manual)
                    } else {
                        onMessage("更新状态已变化，请重新检查")
                        updates.consumeManualCheck(manual)
                    }
                }

                ManualUpdateCheck.LATEST, ManualUpdateCheck.FAILED, ManualUpdateCheck.UNSUPPORTED -> {
                    onMessage(
                        when (manual) {
                            ManualUpdateCheck.LATEST -> "当前已是最新版本"
                            ManualUpdateCheck.FAILED -> "检查更新未完成，请检查网络后重试"
                            else -> "当前构建不支持应用内更新"
                        },
                    )
                    updates.consumeManualCheck(manual)
                }

                ManualUpdateCheck.IDLE -> {
                    if (update != null && updates.claimPrompt(update!!.code)) offered = update!!.code
                }

                ManualUpdateCheck.CHECKING -> {
                    Unit
                }
            }
        }
    }
    if (settingsVisible && update != null && offered == update!!.code) {
        GlassAlertDialog(
            { offered = null },
            title = { Text("发现新版本") },
            text = { Text("AMI ${update!!.version} 已下载，是否现在安装？") },
            confirmButton = {
                TextButton({
                    offered = null
                    onInstall()
                }) { Text("安装") }
            },
            dismissButton = { TextButton({ offered = null }) { Text("稍后") } },
        )
    }
}
