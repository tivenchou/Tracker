package com.example.tracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 設定頁面的Activity，處理錄影相關設定
 */
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.tracker.ui.theme.TrackerTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrackerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen()
                }
            }
        }
    }

    companion object {
        fun updateSettings(
            enableRecording: Boolean,
            startDelay: Int,
            stopDelay: Int,
            savePath: String
        ) {
            // 實作更新設定的邏輯
        }
    }
}

/**
 * 設定頁面的Composable
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    
    // 設定狀態變數
    var enableRecording by remember { mutableStateOf(false) }
    var startDelay by remember { mutableStateOf(5) } // 預設5秒
    var stopDelay by remember { mutableStateOf(5) } // 預設5秒
    var savePath by remember { 
        mutableStateOf(
            // 優先檢查SD卡是否存在
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                // SD卡可用，使用SD卡上的Movies目錄
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Tracker").absolutePath
            } else {
                // SD卡不可用，使用內部儲存
                File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Tracker").absolutePath
            }
        ) 
    }
    
    // 檢查是否有寫入外部儲存權限
    var hasWritePermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    
    // 請求權限的Launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        enableRecording = isGranted
        if (!isGranted) {
            Toast.makeText(context, "需要儲存權限才能啟用錄影功能", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 選擇資料夾的Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            savePath = DocumentFile.fromTreeUri(context, it)?.name ?: savePath
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 錄影開關
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Text("啟用錄影功能")
            Switch(
                checked = enableRecording,
                onCheckedChange = {
                    if (hasWritePermission) {
                        enableRecording = it
                    } else {
                        enableRecording = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            // 對於Android 10及以下版本
                            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                },
                enabled = hasWritePermission
            )
        }
        
        // 開始錄影延遲
        Text("開始錄影延遲 (秒)", modifier = Modifier.padding(top = 16.dp))
        Slider(
            value = startDelay.toFloat(),
            onValueChange = { startDelay = it.toInt() },
            valueRange = 2f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Text("$startDelay 秒")
        
        // 停止錄影延遲
        Text("停止錄影延遲 (秒)", modifier = Modifier.padding(top = 16.dp))
        Slider(
            value = stopDelay.toFloat(),
            onValueChange = { stopDelay = it.toInt() },
            valueRange = 2f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Text("$stopDelay 秒")
        
        // 儲存路徑
        Text("儲存路徑", modifier = Modifier.padding(top = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = savePath,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            Button(
                onClick = { 
                    folderPickerLauncher.launch(null)
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("選擇")
            }
        }
        
        // 返回按鈕
        Button(
            onClick = { 
                // 將設定值傳遞給CameraPreview
                SettingsActivity.updateSettings(
                    enableRecording,
                    startDelay,
                    stopDelay,
                    savePath
                )
                (context as? Activity)?.finish() 
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text("返回")
        }
    }
}

/**
 * 生成帶有時間戳記的檔案名稱
 * @return 格式為 Tracker_YYYY_MM_DD_hh_mm_ss.mp4 的檔案名稱
 */
fun generateTimestampFileName(): String {
    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
    return "Tracker_${sdf.format(Date())}.mp4"
}