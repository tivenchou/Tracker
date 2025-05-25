package com.example.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Context
import android.view.SurfaceView
import android.util.Size
import android.util.Log
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.tracker.ui.theme.TrackerTheme
import org.opencv.core.Point
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import org.opencv.android.OpenCVLoader
import java.time.InstantSource.system
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 主活動，負責處理權限請求和設定 Compose UI。
 */
class MainActivity : ComponentActivity() {
    // 可變狀態，用於追蹤相機權限是否已授予
    private val hasCameraPermissionState = mutableStateOf(false)

    // 可變狀態，用於追蹤儲存權限是否已授予
    private val hasStoragePermissionState = mutableStateOf(false)

    // ActivityResultLauncher 用於請求相機權限
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 權限已授予，更新狀態
            hasCameraPermissionState.value = true
            // 只有在相機權限被授予後才檢查儲存權限
            checkStoragePermission()
        } else {
            // 權限被拒絕，直接跳轉到應用程式設定頁面
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + applicationContext.packageName)
            startActivity(intent)
        }
    }
    
    // ActivityResultLauncher 用於請求儲存權限
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // 權限被拒絕，直接跳轉到應用程式設定頁面
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:" + applicationContext.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // 如果跳轉失敗，嘗試使用更通用的設定頁面
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    // 檢查儲存權限 (支援 Android 11+)
    private fun checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用 MANAGE_EXTERNAL_STORAGE 權限
            if (!Environment.isExternalStorageManager()) {
                try {
                    // 直接跳轉到所有檔案存取權限設定頁面
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    intent.data = Uri.parse("package:" + applicationContext.packageName)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    // 如果跳轉失敗，顯示提示並跳轉到應用程式詳細設定
                    Toast.makeText(
                        this,
                        "請前往設定 > 應用程式 > 特殊應用程式存取權限 > 所有檔案存取權限，並啟用此應用程式",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    fallbackIntent.data = Uri.parse("package:" + applicationContext.packageName)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallbackIntent)
                }
            }
        } else {
            // Android 10 及以下版本使用傳統的 WRITE_EXTERNAL_STORAGE 權限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestStoragePermission()
            }
        }
    }


    /**
     * Activity 建立時呼叫。
     * @param savedInstanceState 如果 Activity 被重新初始化，則包含先前儲存的狀態。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化OpenCV

        System.loadLibrary("opencv_java4")
        MobileAds.initialize(this)

        Log.d("MainActivity", "onCreate: 開始執行")
        // 檢查相機權限
        checkCameraPermission()
        
        // 儲存權限檢查已移到相機權限回調中執行

        setContent {
            TrackerTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // 根據權限狀態顯示不同的畫面
                    if (hasCameraPermissionState.value) {
                        // 如果有權限，顯示物件追蹤畫面
                        ObjectTrackingScreen(hasStoragePermissionState)
                    } else {
                        // 如果沒有權限，顯示權限請求提示畫面
                        PermissionRequestScreen { requestCameraPermission() }
                    }
                }
            }
        }
    }

    /**
     * 當Activity恢復時重新檢查權限狀態
     */
    override fun onResume() {
        super.onResume()
        // 重新檢查相機和儲存權限狀態
        hasCameraPermissionState.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        // 重新檢查儲存權限狀態
        val hasStoragePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        
        // 更新儲存權限狀態
        hasStoragePermissionState.value = hasStoragePermission
        
        if (!hasStoragePermission) {
            checkStoragePermission()
        }
    }
    


    /**
     * 檢查應用程式是否已獲得相機權限。
     */
    private fun checkCameraPermission() {
        Log.d("MainActivity", "checkCameraPermission: 開始檢查權限")
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) -> {
                Log.d("MainActivity", "checkCameraPermission: 相機權限已授予")
                // 權限已授予
                hasCameraPermissionState.value = true
            }
            else -> {
                // 權限未授予，需要請求
                // 注意：直接在 check 裡面請求可能不是最佳實踐，
                // 通常會在使用者明確觸發需要相機的功能時才請求。
                // 但為了簡化範例，這裡先直接請求。
                requestCameraPermission()
            }
        }
    }

    /**
     * 啟動相機權限請求流程。
     */
    private fun requestCameraPermission() {
        Log.d("MainActivity", "requestCameraPermission: 請求相機權限")
        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    /**
     * 啟動儲存權限請求流程。
     */
    private fun requestStoragePermission() {
        Log.d("MainActivity", "requestStoragePermission: 請求儲存權限")
        requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/**
 * 顯示請求權限畫面的 Composable。
 * @param onRequestPermission 當使用者需要觸發權限請求時呼叫的回調函數 (在此範例中未使用，請求由 Activity 發起)。
 */
@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
        Text("需要相機權限才能使用此功能。")
        // 實際應用中可以加入一個按鈕來觸發 onRequestPermission
        // 例如： Button(onClick = onRequestPermission) { Text("請求權限") }
        // 在此範例中，權限請求由 Activity 在 checkCameraPermission 中自動發起
    }
}

/**
 * 物件追蹤畫面的主 Composable。
 * 整合 CameraPreview 並繪製追蹤標記。
 */
@Composable
fun ObjectTrackingScreen(hasStoragePermissionState: MutableState<Boolean>) {
    val context = LocalContext.current
    // 追蹤到的物體中心座標狀態 (來自 OpenCV Point)，初始設為 null
    var trackedObjectPoint by remember { mutableStateOf<Point?>(null) }
    // 分析幀的尺寸
    var analysisWidth by remember { mutableStateOf(1) } // 避免除以零
    var analysisHeight by remember { mutableStateOf(1) } // 避免除以零
    // 新增錄影狀態
    var isRecording by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 頂部區域 - 設定按鈕和權限提示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // 儲存權限提示文字
            if (!hasStoragePermissionState.value) {
                Text(
                    text = "沒有取得儲存權限，無法開啟錄影功能",
                    color = Color.Red,
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 設定按鈕
            IconButton(
                onClick = {
                    if (hasStoragePermissionState.value) {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    } else {
                        // 顯示權限提示
                        Toast.makeText(
                            context,
                            "需要STORAGE權限才能使用設定功能",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = hasStoragePermissionState.value
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "設定",
                    tint = if (hasStoragePermissionState.value) LocalContentColor.current else Color.Gray
                )
            }
        }

        // 中間區域 - 相機預覽
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize()) { point, width, height, isRecording, enableRecording ->
                // 更新追蹤到的點和分析尺寸
                trackedObjectPoint = point
                if (width > 0) analysisWidth = width
                if (height > 0) analysisHeight = height
            }

            // 如果有追蹤到物體，則在其中心繪製十字標線
            trackedObjectPoint?.let { point ->
                Canvas(modifier = Modifier.fillMaxSize()) { // `this` is DrawScope
                    // 計算縮放比例 (分析尺寸 -> 畫布尺寸)
                    val scaleX = size.width / analysisWidth
                    val scaleY = size.height / analysisHeight

                    // 轉換座標 (將 OpenCV Point 座標轉換為 Compose Offset 座標)
                    val displayX = (point.x * scaleX).toFloat()
                    val displayY = (point.y * scaleY).toFloat()
                    val centerOnDisplay = Offset(displayX, displayY)

                    val crosshairSize = 50f // 十字標線的大小
                    // 繪製水平線
                    drawLine(
                        color = Color.Red,
                        start = Offset(
                            centerOnDisplay.x - crosshairSize / 2,
                            centerOnDisplay.y
                        ),
                        end = Offset(centerOnDisplay.x + crosshairSize / 2, centerOnDisplay.y),
                        strokeWidth = 5f
                    )
                    // 繪製垂直線
                    drawLine(
                        color = Color.Red,
                        start = Offset(
                            centerOnDisplay.x,
                            centerOnDisplay.y - crosshairSize / 2
                        ),
                        end = Offset(centerOnDisplay.x, centerOnDisplay.y + crosshairSize / 2),
                        strokeWidth = 5f
                    )
                }
            }

            // 顯示「錄影中」圖示
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopEnd)
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                color = Color(0x99FF0000), // 半透明紅色背景
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.Red, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "錄影中",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

        }

        // 底部區域 - 廣告
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(Color.LightGray)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    AdView(context).apply {
                        setAdSize(AdSize.BANNER)
                        adUnitId = "ca-app-pub-3940256099942544~6300978111" // 測試廣告ID
                        loadAd(AdRequest.Builder().build())
                    }
                }
            )
        }
    }
}