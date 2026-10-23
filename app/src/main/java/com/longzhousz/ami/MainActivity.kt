package com.longzhousz.ami

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.longzhousz.ami.ui.AmiApp

class MainActivity : ComponentActivity() {
    private val model: AmiViewModel by viewModels()
    private var lyricSongId: String? = null
    private val lyricFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val songId = lyricSongId
        lyricSongId = null
        if (uri != null && songId != null) model.importLyrics(songId, uri)
    }
    private val lyricFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            model.repository.addLyricRoot(uri)
        } catch (_: SecurityException) { model.message.value = "未获得歌词文件夹授权，请重新选择" }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.repository.addRoot(uri)
            } catch (_: SecurityException) { model.message.value = "未获得文件夹读取授权，请重新选择" }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.message.value = if (granted) "已允许导入进度通知" else "可在系统设置中开启通知"
    }
    private val bridgePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            model.repository.prefs.edit().putString("bridgeFolder", uri.toString()).apply()
            model.message.value = "已选择 Agent 交换文件夹"
        } catch (_: SecurityException) { model.message.value = "交换文件夹需要读写授权" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lyricSongId = savedInstanceState?.getString("lyricSongId")
        enableEdgeToEdge()
        setContent {
            AmiApp(model, onImport = { folderPicker.launch(null) }, onBridgeFolder = { bridgePicker.launch(null) },
                onLyricsFolder = { lyricFolderPicker.launch(null) },
                onLyricFile = { song -> lyricSongId = song.id; lyricFilePicker.launch(arrayOf("*/*")) }, onNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("lyricSongId", lyricSongId)
        super.onSaveInstanceState(outState)
    }
}
