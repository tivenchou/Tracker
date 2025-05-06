package com.example.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.content.ContextCompat
import com.example.tracker.ui.theme.TrackerTheme
import org.opencv.core.Point // Import OpenCV Point

/**
 * 主活動，負責處理權限請求和設定 Compose UI。
 */
class MainActivity : ComponentActivity() {

    // ActivityResultLauncher 用於請求權限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 權限已授予，更新狀態
            hasCameraPermission.value = true
        } else {
            // 權限被拒絕
            // 可以在這裡顯示提示訊息給使用者，告知功能無法使用
            println("Camera permission denied")
        }
    }

    // 可變狀態，用於追蹤相機權限是否已授予
    private var hasCameraPermission = mutableStateOf(false)

    /**
     * Activity 建立時呼叫。
     * @param savedInstanceState 如果 Activity 被重新初始化，則包含先前儲存的狀態。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 檢查相機權限
        checkCameraPermission()

        setContent {
            TrackerTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // 根據權限狀態顯示不同的畫面
                    if (hasCameraPermission.value) {
                        // 如果有權限，顯示物件追蹤畫面
                        ObjectTrackingScreen()
                    } else {
                        // 如果沒有權限，顯示權限請求提示畫面
                        PermissionRequestScreen { requestCameraPermission() }
                    }
                }
            }
        }
    }

    /**
     * 檢查應用程式是否已獲得相機權限。
     */
    private fun checkCameraPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) -> {
                // 權限已授予
                hasCameraPermission.value = true
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
     * 啟動權限請求流程。
     */
    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}

/**
 * 顯示請求權限畫面的 Composable。
 * @param onRequestPermission 當使用者需要觸發權限請求時呼叫的回調函數 (在此範例中未使用，請求由 Activity 發起)。
 */
@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
fun ObjectTrackingScreen() {
    // 追蹤到的物體中心座標狀態 (來自 OpenCV Point)，初始設為 null
    var trackedObjectPoint by remember { mutableStateOf<Point?>(null) }
    // 分析幀的尺寸
    var analysisWidth by remember { mutableStateOf(1) } // 避免除以零
    var analysisHeight by remember { mutableStateOf(1) } // 避免除以零

    Box(modifier = Modifier.fillMaxSize()) {
        // 顯示相機預覽，並在分析到幀時更新追蹤座標和分析尺寸
        CameraPreview(modifier = Modifier.fillMaxSize()) { point, width, height ->
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
                    start = Offset(centerOnDisplay.x - crosshairSize / 2, centerOnDisplay.y),
                    end = Offset(centerOnDisplay.x + crosshairSize / 2, centerOnDisplay.y),
                    strokeWidth = 5f
                )
                // 繪製垂直線
                drawLine(
                    color = Color.Red,
                    start = Offset(centerOnDisplay.x, centerOnDisplay.y - crosshairSize / 2),
                    end = Offset(centerOnDisplay.x, centerOnDisplay.y + crosshairSize / 2),
                    strokeWidth = 5f
                )
            }
        }
    }
}