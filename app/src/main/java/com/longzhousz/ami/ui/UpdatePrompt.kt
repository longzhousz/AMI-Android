package com.longzhousz.ami.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.data.AppUpdates

@Composable internal fun UpdatePrompt(updates: AppUpdates, settingsVisible: Boolean, onInstall: () -> Unit) {
    val update by updates.ready.collectAsStateWithLifecycle()
    var offered by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(updates, settingsVisible, update?.code) {
        if (settingsVisible && update != null && updates.claimPrompt(update!!.code)) offered = update!!.code
        if (!settingsVisible) offered = null
    }
    if (settingsVisible && update != null && offered == update!!.code) {
        GlassAlertDialog({ offered = null }, title = { Text("发现新版本") },
            text = { Text("AMI ${update!!.version} 已下载，是否现在安装？") },
            confirmButton = { TextButton({ offered = null; onInstall() }) { Text("安装") } },
            dismissButton = { TextButton({ offered = null }) { Text("稍后") } })
    }
}
