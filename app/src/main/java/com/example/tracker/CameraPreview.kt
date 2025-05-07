package com.example.tracker

import android.content.Context
import android.util.Log
import android.util.Size // <-- 新增導入
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy // <-- 新增導入
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect // 新增導入
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Moments // 修正導入
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video // 新增導入 for Video.createBackgroundSubtractorMOG2()
import org.opencv.core.MatOfPoint2f // 新增導入 for 光流法
import org.opencv.core.MatOfByte    // 新增導入 for 光流法
import org.opencv.core.TermCriteria // 新增導入 for 光流法

// 移除重複導入
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 顯示相機預覽畫面的 Composable。
 * @param modifier Modifier for this composable.
 * @param onObjectTracked 回調函數，用於處理分析到的物體中心點 (Point?) 和分析尺寸。
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onObjectTracked: (point: Point?, analysisWidth: Int, analysisHeight: Int) -> Unit // 傳遞中心點和分析尺寸
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    // OpenCV 初始化標誌
    var isOpencvInitialized by remember { mutableStateOf(false) }
    // PreviewView 實例的狀態
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // 初始化 OpenCV
    LaunchedEffect(Unit) {
        Log.d("CameraPreview", "LaunchedEffect for OpenCV init started.")
        if (OpenCVLoader.initDebug()) {
            Log.i("CameraPreview", "OpenCV initialized successfully")
            isOpencvInitialized = true
        } else {
            Log.e("CameraPreview", "OpenCV initialization failed")
        }
    }

    // 當 OpenCV 初始化成功、CameraProvider 就绪且 LifecycleOwner 活躍时，綁定相機
    LaunchedEffect(isOpencvInitialized, cameraProviderFuture, lifecycleOwner, previewView) {
        if (isOpencvInitialized && previewView != null) {
            Log.d("CameraPreview", "LaunchedEffect for camera binding started.")
            try {
                val cameraProvider = cameraProviderFuture.get() // 等待 CameraProvider 就绪
                Log.d("CameraPreview", "CameraProvider instance obtained: $cameraProvider")

                // 建立 Preview UseCase
                val preview = Preview.Builder().build().also {
                    Log.d("CameraPreview", "Preview built.")
                    it.setSurfaceProvider(previewView!!.surfaceProvider) // 使用已建立的 PreviewView
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                Log.d("CameraPreview", "CameraSelector built.")

                // 設定圖像分析
                Log.d("CameraPreview", "Setting up ImageAnalysis...")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        Log.d("CameraPreview", "ImageAnalysis built, setting analyzer...")
                        it.setAnalyzer(cameraExecutor, ObjectTrackerAnalyzer(onObjectTracked))
                        Log.d("CameraPreview", "Analyzer set for ImageAnalysis.")
                    }

                Log.d("CameraPreview", "Attempting to bind camera use cases to lifecycle...")
                cameraProvider.unbindAll()
                Log.d("CameraPreview", "Unbound all previous use cases.")
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d("CameraPreview", "Successfully bound camera use cases to lifecycle.")
            } catch (e: Exception) {
                Log.e("CameraPreview", "Use case binding failed in LaunchedEffect", e)
            }
        } else {
            Log.d("CameraPreview", "Skipping camera binding: OpenCV initialized=$isOpencvInitialized, PreviewView=$previewView")
        }
    }

    // AndroidView 用於顯示 PreviewView
    AndroidView(
        factory = {
            Log.d("CameraPreview", "AndroidView factory called.")
            PreviewView(it).apply {
                this.scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also {
                previewView = it // 將創建的 PreviewView 存儲到狀態中
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            // update 區塊現在可以保持空白，或者用於處理 PreviewView 的其他更新
            Log.d("CameraPreview", "AndroidView update called. PreviewView: $view")
            // 注意：不再在此處執行相機綁定邏輯
        }
    )

    // 如果 OpenCV 未初始化，顯示提示（可選）
    if (!isOpencvInitialized) {
        Box(modifier = modifier.fillMaxSize()) {
            // 可以顯示一個載入指示器或錯誤訊息
            Log.e("CameraPreview", "OpenCV not initialized, cannot display camera preview.")
        }
    }

    // Composable 銷毀時釋放資源
    DisposableEffect(Unit) {
        onDispose {
            Log.d("CameraPreview", "DisposableEffect onDispose called.")
            cameraExecutor.shutdown()
            // 考慮在 Activity/Fragment 的 onDestroy 中解除綁定
            // cameraProviderFuture.get().unbindAll()
        }
    }
}

/**
 * 圖像分析器，用於處理相機幀、執行物件追蹤並傳遞結果。
 * 使用光流法 (Farneback) 偵測移動物體。
 * @param onObjectTracked 回調函數，傳遞追蹤到的物體中心點座標 (相對於預覽畫面)，如果未偵測到則傳遞 null。
 */
private class ObjectTrackerAnalyzer(private val onObjectTracked: (Point?, Int, Int) -> Unit) : ImageAnalysis.Analyzer {
    // 用於光流法
    private var prevGrayMat = Mat()
    private var prevPoints: MatOfPoint2f? = null
    private val shakeThreshold = 5.0 // 晃動閾值，可調整

    // MOG2 背景扣除器
    private var bgSubtractor: BackgroundSubtractorMOG2? = null
    // 用於儲存當前幀的灰度圖
    private var grayMat = Mat()
    // 用於儲存旋轉後的圖像
    private var rotatedMat = Mat()
    // 用於儲存前景遮罩
    private var fgMask = Mat()
    // 用於形態學操作的核心
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(5.0, 5.0))
    // 最小輪廓面積，過濾小噪點
    private val minContourArea = 50.0 // 250 根據需要調整

    /**
     * 初始化背景扣除器。
     * 確保只在 OpenCV 初始化後調用。
     */
    /**
     * 初始化背景扣除器 (MOG2)。
     * 調整參數以提高對顏色與背景相近的移動物體的偵測能力。
     * 確保只在 OpenCV 初始化後調用。
     */
    private fun initializeBgSubtractor() {
        if (bgSubtractor == null) {
            // 創建 MOG2 背景扣除器
            // history: 影響背景模型的學習速度。預設 500。
            // varThreshold: 方差閾值，用於判斷像素是否屬於背景模型。
            //   較低的值會使模型對變化更敏感，但也可能引入更多噪點。預設 16，這裡調整為 10。
            // detectShadows: 是否偵測陰影。啟用陰影偵測有助於區分陰影和實際移動物體。預設 true。
            bgSubtractor = Video.createBackgroundSubtractorMOG2(500, 10.0, true)
            Log.d("ObjectTrackerAnalyzer", "BackgroundSubtractorMOG2 initialized with varThreshold=10.0 and detectShadows=true.")
        }
    }

    /**
     * 分析圖像幀，使用 MOG2 背景扣除和輪廓分析偵測移動物體中心。
     * @param image The image proxy containing the frame data.
     */
    /**
     * 分析圖像幀，使用光流法偵測相機晃動，若晃動不大則使用 MOG2 背景扣除和輪廓分析偵測移動物體中心。
     * @param image The image proxy containing the frame data.
     */
    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        Log.d("ObjectTrackerAnalyzer", "analyze() called")
        val rotationDegrees = image.imageInfo.rotationDegrees
        Log.d("ObjectTrackerAnalyzer", "Image rotation: $rotationDegrees degrees")

        // 確保背景扣除器已初始化 (MOG2)
        // initializeBgSubtractor() // 移至晃動判斷之後

        val mediaImage = image.image ?: run {
            Log.e("ObjectTrackerAnalyzer", "Image is null, closing image and returning.")
            image.close()
            return
        }

        // 將 YUV_420_888 圖像轉換為 Mat
        val yBuffer = mediaImage.planes[0].buffer
        val uBuffer = mediaImage.planes[1].buffer
        val vBuffer = mediaImage.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        // 注意：對於 NV21，U 和 V 平面是交錯的。但 CameraX 通常提供 I420 或類似格式，其中 U 和 V 是分開的。
        // 這裡假設 U 和 V 平面數據可以直接拼接。如果格式不同，轉換邏輯需要調整。
        // 為了簡化，這裡假設 plane[1] 是 U，plane[2] 是 V，並且它們的 rowStride 和 pixelStride 允許直接複製。
        // 實際上，更可靠的方法是使用 ImageUtil 或類似庫來處理 YUV 到 RGB/Grayscale 的轉換。
        // 這裡我們直接將 Y 平面作為灰度圖使用，因為背景扣除通常在灰度圖上操作。

        val yuvMat = Mat(mediaImage.height + mediaImage.height / 2, mediaImage.width, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)

        // 將 YUV 轉換為灰度圖 (直接使用 Y 平面)
        // grayMat = Mat(mediaImage.height, mediaImage.width, CvType.CV_8UC1, yBuffer)
        // 或者從 YUV 轉換
        val currentGrayMat = Mat() // 當前幀的灰階圖，用於光流法
        Imgproc.cvtColor(yuvMat, currentGrayMat, Imgproc.COLOR_YUV2GRAY_NV21)
        yuvMat.release() // 釋放 yuvMat

        // 將 currentGrayMat 複製給 grayMat 以便後續 MOG2 使用 (如果需要)
        currentGrayMat.copyTo(grayMat)

        // 根據需要旋轉圖像
        if (rotationDegrees != 0) {
            Core.rotate(currentGrayMat, rotatedMat, when (rotationDegrees) { // 使用 currentGrayMat 進行旋轉以用於光流法
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> { // 不支援的旋轉角度
                    Log.e("ObjectTrackerAnalyzer", "Unsupported rotation: $rotationDegrees")
                    currentGrayMat.release()
                    image.close()
                    return
                }
            })
        } else {
            rotatedMat = currentGrayMat.clone() // 如果不需要旋轉，直接複製 currentGrayMat
        }

        // --- 光流法相機晃動偵測 ---
        if (!prevGrayMat.empty() && prevPoints != null && prevPoints!!.toArray().isNotEmpty()) {
            val nextPoints = MatOfPoint2f()
            val status = MatOfByte()
            val err = org.opencv.core.MatOfFloat() // Explicitly use org.opencv.core.MatOfFloat

            Video.calcOpticalFlowPyrLK(prevGrayMat, rotatedMat, prevPoints, nextPoints, status, err)

            val statusArray = status.toArray()
            val prevPointsArray = prevPoints!!.toArray()
            val nextPointsArray = nextPoints.toArray()
            var totalDx = 0.0
            var totalDy = 0.0
            var validPointsCount = 0

            for (i in prevPointsArray.indices) {
                if (statusArray[i].toInt() == 1) {
                    totalDx += kotlin.math.abs(nextPointsArray[i].x - prevPointsArray[i].x)
                    totalDy += kotlin.math.abs(nextPointsArray[i].y - prevPointsArray[i].y)
                    validPointsCount++
                }
            }

            if (validPointsCount > 0) {
                val avgDx = totalDx / validPointsCount
                val avgDy = totalDy / validPointsCount
                val avgMovement = kotlin.math.sqrt(avgDx * avgDx + avgDy * avgDy)
                Log.d("ObjectTrackerAnalyzer", "Average movement: $avgMovement, Valid points: $validPointsCount")

                if (avgMovement > shakeThreshold) {
                    Log.w("ObjectTrackerAnalyzer", "Camera shake detected (movement: $avgMovement > threshold: $shakeThreshold), skipping object detection.")
                    onObjectTracked(null, rotatedMat.width(), rotatedMat.height())
                    // 更新 prevGrayMat 和 prevPoints 以便下一幀比較
                    rotatedMat.copyTo(prevGrayMat)
                    // 重新偵測特徵點，因為畫面可能已大幅改變
                    val newFeatures = MatOfPoint()
                    Imgproc.goodFeaturesToTrack(rotatedMat, newFeatures, 100, 0.3, 7.0)
                    prevPoints = MatOfPoint2f(*newFeatures.toArray())
                    newFeatures.release()

                    currentGrayMat.release()
                    // rotatedMat is now prevGrayMat, fgMask not used yet
                    image.close()
                    return
                }
            }
            nextPoints.release()
            status.release()
            err.release()
        }

        // 更新 prevGrayMat 和 prevPoints (用於下一幀的光流計算)
        rotatedMat.copyTo(prevGrayMat)
        // 在當前幀 (rotatedMat) 上偵測好的特徵點，用於下一幀的光流計算
        val features = MatOfPoint()
        Imgproc.goodFeaturesToTrack(rotatedMat, features, 100, 0.3, 7.0) // maxCorners, qualityLevel, minDistance
        prevPoints = MatOfPoint2f(*features.toArray())
        features.release()
        // --- 光流法結束 ---

        // 確保背景扣除器已初始化 (MOG2)
        initializeBgSubtractor()

        // 如果晃動不大，則繼續 MOG2
        // 應用背景扣除 (注意：MOG2 應該在 rotatedMat 上操作，因為它是當前處理的灰階圖)
        bgSubtractor?.apply(rotatedMat, fgMask, 0.01) // learningRate 設為較小值以穩定背景模型

        if (fgMask.empty()) {
            Log.d("ObjectTrackerAnalyzer", "Foreground mask is empty.")
            onObjectTracked(null, rotatedMat.width(), rotatedMat.height())
            image.close()
            return
        }

        // 形態學操作：去噪和填補空洞
        Imgproc.erode(fgMask, fgMask, kernel) // 侵蝕
        Imgproc.dilate(fgMask, fgMask, kernel) // 膨脹
        // 可以根據需要增加更多操作，例如 Imgproc.morphologyEx with MORPH_OPEN or MORPH_CLOSE

        // 尋找輪廓
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(fgMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        var largestContour: MatOfPoint? = null
        var maxArea = 0.0
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea && area > maxArea) {
                maxArea = area
                largestContour = contour
            }
        }

        var centerPoint: Point? = null
        if (largestContour != null) {
            val moments = Imgproc.moments(largestContour)
            if (moments.m00 > 0) { // 避免除以零
                centerPoint = Point(moments.m10 / moments.m00, moments.m01 / moments.m00)
                Log.d("ObjectTrackerAnalyzer", "Object detected at: (${centerPoint.x}, ${centerPoint.y})")
            } else {
                Log.d("ObjectTrackerAnalyzer", "Largest contour m00 is zero, cannot calculate center.")
            }
            largestContour.release() // 釋放輪廓 Mat
        }

        // 釋放 Mat 資源
        currentGrayMat.release() // currentGrayMat 在此處釋放
        // prevGrayMat 在下一幀會被重用
        // rotatedMat 在此處相當於 prevGrayMat，不需要額外釋放
        // fgMask 在下一幀會被重用或重新賦值
        for (contour in contours) {
            contour.release()
        }

        // 回調結果
        onObjectTracked(centerPoint, rotatedMat.width(), rotatedMat.height())
        image.close()
        Log.d("ObjectTrackerAnalyzer", "analyze() finished")
    }
}

// --- (可能需要的其他 Composable 或輔助函數) ---