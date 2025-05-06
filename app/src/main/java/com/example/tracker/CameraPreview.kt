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
    private val minContourArea = 1000.0 // 根據需要調整

    /**
     * 初始化背景扣除器。
     * 確保只在 OpenCV 初始化後調用。
     */
    private fun initializeBgSubtractor() {
        if (bgSubtractor == null) {
            bgSubtractor = Video.createBackgroundSubtractorMOG2()
            Log.d("ObjectTrackerAnalyzer", "BackgroundSubtractorMOG2 initialized.")
        }
    }

    /**
     * 分析圖像幀，使用 MOG2 背景扣除和輪廓分析偵測移動物體中心。
     * @param image The image proxy containing the frame data.
     */
    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        Log.d("ObjectTrackerAnalyzer", "analyze() called")
        val rotationDegrees = image.imageInfo.rotationDegrees
        Log.d("ObjectTrackerAnalyzer", "Image rotation: $rotationDegrees degrees")

        // 確保背景扣除器已初始化
        initializeBgSubtractor()

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
        Imgproc.cvtColor(yuvMat, grayMat, Imgproc.COLOR_YUV2GRAY_NV21)
        yuvMat.release() // 釋放 yuvMat

        // 根據需要旋轉圖像
        if (rotationDegrees != 0) {
            Core.rotate(grayMat, rotatedMat, when (rotationDegrees) {
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> return // 不支援的旋轉角度
            })
        } else {
            rotatedMat = grayMat.clone() // 如果不需要旋轉，直接複製
        }

        // 應用背景扣除
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
        // grayMat.release() // grayMat 在下一幀會被重用或重新賦值，不需要在此釋放
        // rotatedMat.release() // rotatedMat 在下一幀會被重用或重新賦值
        // fgMask.release() // fgMask 在下一幀會被重用或重新賦值
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