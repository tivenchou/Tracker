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
import org.opencv.video.Video
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

    // 用於儲存上一幀的灰度圖
    private var prevGrayMat = Mat()
    // 用於儲存當前幀的灰度圖
    private var grayMat = Mat()
    // 用於儲存旋轉後的圖像
    private var rotatedMat = Mat()
    // 用於儲存光流結果
    private var flow = Mat()
    // 用於形態學操作的核心 (可選，用於後處理光流)
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(5.0, 5.0))
    // 標記是否為第一幀
    private var isFirstFrame = true

    // 光流法參數 - Farneback
    private val pyrScale = 0.5 // 金字塔縮放因子
    private val levels = 3     // 金字塔層數
    private var winsize = 20   // 平均窗口大小，較大值對快速運動更魯棒，但可能模糊運動細節 (原為 15)
    private val iterations = 3 // 每次金字塔層的迭代次數
    private val polyN = 5      // 像素鄰域大小，用於多項式展開
    private val polySigma = 1.1 // 高斯標準差，用於平滑導數
    private val flags = 0      // 操作標誌

    // 移動偵測閾值
    private val thresholdValue = 10.0 // 光流大小的閾值，用於判斷是否為顯著移動 (原為 5.0)
    private val minContourArea = 700.0 // 最小輪廓面積，過濾小噪點 (原為 500.0)

    /**
     * 分析圖像幀，使用光流法和輪廓分析偵測移動物體中心。
     * @param image The image proxy containing the frame data.
     */
    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        Log.d("ObjectTrackerAnalyzer", "analyze() called")
        val rotationDegrees = image.imageInfo.rotationDegrees
        Log.d("ObjectTrackerAnalyzer", "Image rotation: $rotationDegrees degrees")

        // 將 ImageProxy 轉換為 Mat
        val currentMat = imageProxyToMat(image)
        if (currentMat.empty()) {
            Log.e("ObjectTrackerAnalyzer", "Failed to convert ImageProxy to Mat")
            image.close()
            return
        }
        Log.d("ObjectTrackerAnalyzer", "Input Mat size: ${currentMat.cols()}x${currentMat.rows()}")

        // 將圖像轉為灰度
        Imgproc.cvtColor(currentMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        // 根據需要旋轉圖像
        if (rotationDegrees == 0) {
            grayMat.copyTo(rotatedMat) // 深拷貝
            Log.d("ObjectTrackerAnalyzer", "No rotation needed. Rotated Mat (copied from gray) size: ${rotatedMat.cols()}x${rotatedMat.rows()}")
        } else {
            when (rotationDegrees) {
                90 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_180)
                270 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> {
                    // 對於其他角度，可以選擇不處理或使用 warpAffine (但要注意尺寸問題)
                    // 為了簡化，這裡假設只處理 0, 90, 180, 270 度旋轉
                    // 如果需要支援任意角度，需要更複雜的邊界計算和 warpAffine 參數設定
                    grayMat.copyTo(rotatedMat) // 預設不旋轉
                    Log.w("ObjectTrackerAnalyzer", "Unsupported rotation degrees: $rotationDegrees. Using original image.")
                }
            }
            Log.d("ObjectTrackerAnalyzer", "Image rotated. Rotated Mat size: ${rotatedMat.cols()}x${rotatedMat.rows()}")
        }

        if (rotatedMat.empty()) {
            Log.e("ObjectTrackerAnalyzer", "Rotated Mat is empty!")
            image.close()
            currentMat.release()
            return
        }

        val analysisWidth = rotatedMat.cols()
        val analysisHeight = rotatedMat.rows()

        if (isFirstFrame) {
            rotatedMat.copyTo(prevGrayMat)
            isFirstFrame = false
            Log.d("ObjectTrackerAnalyzer", "First frame processed, prevGrayMat initialized.")
            onObjectTracked(null, analysisWidth, analysisHeight) // 第一幀回調 null
            image.close()
            currentMat.release()
            return
        }

        if (prevGrayMat.empty() || prevGrayMat.size() != rotatedMat.size() || prevGrayMat.type() != rotatedMat.type()) {
            Log.e("ObjectTrackerAnalyzer", "prevGrayMat is invalid or incompatible. Re-initializing.")
            rotatedMat.copyTo(prevGrayMat)
            onObjectTracked(null, analysisWidth, analysisHeight) // 錯誤情況回調 null
            image.close()
            currentMat.release()
            return
        }

        // 計算光流
        try {
            // 調整參數：winsize 12->10, iterations 2->3, poly_sigma 1.1->1.0
            Video.calcOpticalFlowFarneback(prevGrayMat, rotatedMat, flow, 0.5, 3, 10, 3, 5, 1.0, 0)
            Log.d("ObjectTrackerAnalyzer", "Optical flow calculated. Flow size: ${flow.cols()}x${flow.rows()}, type: ${flow.type()}")
        } catch (e: Exception) {
            Log.e("ObjectTrackerAnalyzer", "Error calculating optical flow", e)
            rotatedMat.copyTo(prevGrayMat) // 更新 prevGrayMat 以便下次嘗試
            onObjectTracked(null, analysisWidth, analysisHeight) // 錯誤情況回調 null
            image.close()
            currentMat.release()
            return
        }

        // --- 計算移動中心點 (使用最大輪廓中心) ---
        var trackedPoint: Point? = null
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val flowMagnitude = Mat()
        val motionMask = Mat()
        val flowParts = mutableListOf<Mat>()

        try {
            if (!flow.empty()) {
                // 1. 計算光流大小 (magnitude)
                Core.split(flow, flowParts)
                if (flowParts.size >= 2) {
                    val flowX = flowParts[0]
                    val flowY = flowParts[1]
                    Core.magnitude(flowX, flowY, flowMagnitude)

                    // 2. 閾值化光流大小，生成移動區域的二值遮罩
                    val thresholdValue = 5.0 // 閾值，可調整 (原為 3.0, 之前嘗試過5.0，現在改回並觀察效果)
                    Imgproc.threshold(flowMagnitude, motionMask, thresholdValue, 255.0, Imgproc.THRESH_BINARY)
                    motionMask.convertTo(motionMask, CvType.CV_8U)

                    // 3. 形態學開運算去除噪點
                    Imgproc.morphologyEx(motionMask, motionMask, Imgproc.MORPH_OPEN, kernel)

                    // 4. 尋找輪廓
                    Imgproc.findContours(motionMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                    // 5. 找到移動最快的輪廓 (基於平均光流強度)
                    var maxAvgFlowMagnitude = 0.0
                    var fastestContour: MatOfPoint? = null
                    val minContourArea = 150.0 // 最小輪廓面積閾值，可調整

                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area > minContourArea) {
                            // 為當前輪廓創建一個遮罩
                            val contourMask = Mat.zeros(flowMagnitude.size(), CvType.CV_8UC1)
                            Imgproc.drawContours(contourMask, listOf(contour), -1, Scalar(255.0), Core.FILLED)

                            // 計算輪廓內的平均光流強度
                            val meanFlow = Core.mean(flowMagnitude, contourMask)
                            val avgFlowInContour = meanFlow.`val`[0] // 取第一個通道的值 (灰度圖)
                            contourMask.release()

                            if (avgFlowInContour > maxAvgFlowMagnitude) {
                                maxAvgFlowMagnitude = avgFlowInContour
                                fastestContour?.release() // 釋放之前的最快輪廓
                                fastestContour = contour // 更新最快輪廓
                            } else {
                                contour.release() // 釋放非最快輪廓
                            }
                        } else {
                            contour.release() // 釋放面積過小的輪廓
                        }
                    }

                    // 6. 計算最快輪廓的中心點 (質心)
                    if (fastestContour != null) {
                        val moments = Imgproc.moments(fastestContour)
                        if (moments.m00 != 0.0) { // 避免除以零
                            val centerX = moments.m10 / moments.m00
                            val centerY = moments.m01 / moments.m00
                            trackedPoint = Point(centerX, centerY)
                            Log.d("ObjectTrackerAnalyzer", "Fastest contour found. Avg Flow: $maxAvgFlowMagnitude, Center: ($centerX, $centerY)")
                        } else {
                            Log.d("ObjectTrackerAnalyzer", "Fastest contour found but m00 is zero.")
                        }
                        fastestContour.release() // 釋放找到的最快輪廓
                    } else {
                        Log.d("ObjectTrackerAnalyzer", "No significant fast-moving contour found.")
                    }
                } else {
                    Log.e("ObjectTrackerAnalyzer", "Failed to split flow Mat into components.")
                }
            } else {
                 Log.w("ObjectTrackerAnalyzer", "Flow Mat is empty, cannot process contours.")
            }

        } catch (e: Exception) {
            Log.e("ObjectTrackerAnalyzer", "Error processing optical flow contours", e)
        } finally {
            // 確保釋放所有中間 Mat
            flowMagnitude.release()
            motionMask.release()
            hierarchy.release()
            flowParts.forEach { it.release() }
            // contours 中的 Mat 已在循環中或 finally 塊中釋放
        }
        // --- 計算移動中心點結束 ---

        // 呼叫回調函數
        Log.d("ObjectTrackerAnalyzer", "Calling onObjectTracked with point: $trackedPoint, width: $analysisWidth, height: $analysisHeight")
        onObjectTracked(trackedPoint, analysisWidth, analysisHeight)

        // 更新上一幀圖像
        rotatedMat.copyTo(prevGrayMat)

        // 釋放當前幀相關 Mat
        currentMat.release()
        // grayMat, prevGrayMat, rotatedMat, flow 是類別成員，不在此釋放
        // rotatedMat 在每次分析開始時都會被重新賦值 (透過 copyTo 或 Core.rotate)
        // 因此不需要在這裡單獨釋放它，它會在下一次分析中被重用/覆蓋。

        // 關閉 ImageProxy
        image.close()
        Log.d("ObjectTrackerAnalyzer", "analyze() finished")
    }

    /**
     * 將 YUV_420_888 ImageProxy 轉換為 RGBA Mat。
     * 處理了 YUV 平面和 NV21 格式的轉換。
     * @param imageProxy 輸入的 ImageProxy。
     * @return 轉換後的 RGBA Mat。
     */
    @ExperimentalGetImage
    private fun imageProxyToMat(imageProxy: ImageProxy): Mat { // <-- 確保參數類型正確
        val planes = imageProxy.planes
        val width = imageProxy.width
        val height = imageProxy.height

        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y channel
        yBuffer.get(nv21, 0, ySize)

        // Copy VU data (NV21 format: YYYYYYYY... VUVUVU...)
        val pixelStride = planes[1].pixelStride
        val rowStride = planes[1].rowStride

        // Check if planes are contiguous and stride is as expected
        // This is a simplified check; real-world scenarios might be more complex
        // Assuming NV21 format (Y plane, followed by interleaved VU plane)
        val yMat = Mat(height, width, CvType.CV_8UC1, planes[0].buffer)
        // For NV21, the UV plane has half the width and height, but the row stride might be the same as Y
        // The buffer contains V first, then U for NV21
        val uvMat = Mat(height / 2, width / 2, CvType.CV_8UC2, planes[2].buffer) // Use V buffer for UV plane in NV21

        val rgbaMat = Mat()
        Imgproc.cvtColorTwoPlane(yMat, uvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)

        // Release intermediate Mats if they were created with direct buffer access
        // (Not strictly necessary here as we used buffer directly, but good practice if copies were made)
        // yMat.release()
        // uvMat.release()

        return rgbaMat
    }

    // --- (可能需要的其他輔助函數或屬性) ---
}

// --- (可能需要的其他 Composable 或輔助函數) ---