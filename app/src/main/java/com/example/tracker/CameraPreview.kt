package com.example.tracker

import android.content.Context
import android.util.Log
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
import org.opencv.core.MatOfDouble // 新增導入
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import java.io.File
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import org.opencv.core.Scalar
import org.opencv.imgproc.Moments // 修正導入
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import org.opencv.videoio.VideoWriter // 新增導入VideoWriter類
import org.opencv.core.MatOfPoint2f // 新增導入 for 光流法
import org.opencv.core.MatOfByte    // 新增導入 for 光流法
import org.opencv.core.TermCriteria // 新增導入 for 光流法

// 移除重複導入
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.common.util.concurrent.ListenableFuture
import org.opencv.core.Size



/**
 * 顯示相機預覽畫面的 Composable。
 * @param modifier Modifier for this composable.
 * @param onObjectTracked 回調函數，用於處理分析到的物體中心點 (Point?) 和分析尺寸。
 */



@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current, // 新增預設參數
    onObjectTracked: (point: Point?, analysisWidth: Int, analysisHeight: Int, isRecording: Boolean, enableRecording: Boolean) -> Unit // 傳遞中心點和分析尺寸
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = remember { ProcessCameraProvider.getInstance(context) as ListenableFuture<ProcessCameraProvider> }
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
                        it.setAnalyzer(cameraExecutor, ObjectTrackerAnalyzer(context, lifecycleOwner, onObjectTracked))
                    }
                Log.d("CameraPreview", "Attempting to bind camera use cases to lifecycle...")
                cameraProvider.unbindAll() // 修正為正確的API調用
                Log.d("CameraPreview", "Unbound all previous use cases.")
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                ) // 確保使用正確的CameraX API
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
private class ObjectTrackerAnalyzer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner, // 新增此參數
    private val onObjectTracked: (Point?, Int, Int, Boolean, Boolean) -> Unit
) : ImageAnalysis.Analyzer {
    // 錄影相關狀態
    private var recordingStartTime = 0L
    private var recordingStopTime = 0L
    private var lastDetectedTime = 0L
    private var lastUnDetectedTime = 0L
    private var videoWriter: org.opencv.videoio.VideoWriter? = null
    // 使用VideoWriter的create方法替代直接建構函式
    private var outputFile: File? = null
    
    /**
     * 使用VideoWriter.create方法初始化影片寫入器
     * @param filename 輸出檔案路徑
     * @param fourcc 影片編碼格式
     * @param fps 影片幀率
     * @param frameSize 影片幀尺寸
     * @param isColor 是否為彩色影片
     * @return 初始化成功的VideoWriter實例，失敗返回null
     */
    /**
     * 使用VideoWriter.create方法初始化影片寫入器
     * @param filename 輸出檔案路徑
     * @param fourcc 影片編碼格式
     * @param fps 影片幀率
     * @param frameSize 影片幀尺寸
     * @param isColor 是否為彩色影片
     * @return 初始化成功的VideoWriter實例，失敗返回null
     */
    // 定義影片編碼格式 (MP4V)
    private val videoWriterCodec = VideoWriter.fourcc('M', 'P', '4', 'V')
    
    private fun createVideoWriter(filename: String, fourcc: Int, fps: Double, frameSize: Size, isColor: Boolean): VideoWriter? {
        return try {
            VideoWriter(filename, fourcc, fps, org.opencv.core.Size(frameSize.width.toDouble(), frameSize.height.toDouble()), isColor)
        } catch (e: Exception) {
            Log.e("ObjectTrackerAnalyzer", "Failed to create VideoWriter: ${e.message}")
            null
        }
    }
    
    // 錄影設定 (從設定頁面獲取)
    private var isRecording by mutableStateOf(false)
    private var enableRecording by mutableStateOf(false)
    private var startDelay by mutableStateOf(5000) // 預設5秒
    private var stopDelay by mutableStateOf(5000) // 預設5秒
    private var savePath by mutableStateOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
    )
    private lateinit var sharedViewModel: SharedViewModel

    init {
        sharedViewModel = (context.applicationContext as MyApplication).sharedViewModel
        sharedViewModel.sharedEnableRecording.observe(lifecycleOwner, Observer { data ->  enableRecording = data ?: false})
        sharedViewModel.sharedIsRecording.observe(lifecycleOwner, Observer { data ->  isRecording = data ?: false})
    }
    
    // 錄影控制方法
    private fun startRecording() {
        if (!enableRecording || isRecording) {
            Log.d("ObjectTrackerAnalyzer", "Recording not started: enableRecording=$enableRecording, isRecording=$isRecording")
            return
        }
        
        // 新增路徑檢查
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.e("ObjectTrackerAnalyzer", "Storage not mounted")
            return
        }
        
        // 改用createVideoWriter方法
        videoWriter = createVideoWriter(
            outputFile?.absolutePath ?: return,
            videoWriterCodec,
            30.0,
            org.opencv.core.Size(rotatedMat.width().toDouble(), rotatedMat.height().toDouble()),
            true
        ) ?: return
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Tracker_${timestamp}.mp4"
        
        Log.d("ObjectTrackerAnalyzer", "Attempting to start recording with filename: $fileName")
        
        val externalDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Log.d("ObjectTrackerAnalyzer", "Using external storage")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Log.d("ObjectTrackerAnalyzer", "Using internal storage")
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        }
        
        outputFile = File(externalDir, fileName)
        Log.d("ObjectTrackerAnalyzer", "Output file path: ${outputFile?.absolutePath}")
        
        try {
            videoWriter = org.opencv.videoio.VideoWriter(outputFile?.absolutePath, videoWriterCodec, 30.0, 
                org.opencv.core.Size(rotatedMat.width().toDouble(), rotatedMat.height().toDouble()))
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            Log.d("ObjectTrackerAnalyzer", "Recording successfully started")
        } catch (e: Exception) {
            Log.e("ObjectTrackerAnalyzer", "Failed to start recording", e)
            videoWriter?.release()
            videoWriter = null
        }
    }
    
    private fun stopRecording() {
        if (!isRecording) {
            Log.d("ObjectTrackerAnalyzer", "Recording not stopped: isRecording=$isRecording")
            return
        }
        
        try {
            videoWriter?.release()
            isRecording = false
            recordingStopTime = System.currentTimeMillis()
            Log.d("ObjectTrackerAnalyzer", "Recording stopped. Duration: ${(recordingStopTime - recordingStartTime)/1000} seconds")
        } catch (e: Exception) {
            Log.e("ObjectTrackerAnalyzer", "Failed to stop recording", e)
        } finally {
            videoWriter = null
        }
    }
    // 用於光流法
    private var prevGrayMat = Mat()
    private var prevPoints: MatOfPoint2f? = null
    private val shakeThreshold = 5.0 // 晃動閾值，可調整
    private val movementThresholdStable = 2.0 // 判斷為穩定的晃動閾值

    // MOG2 背景扣除器
    private var bgSubtractor: BackgroundSubtractorMOG2? = null
    private val minHistory = 100 // history 最小值
    private val maxHistory = 1000 // history 最大值
    private var currentHistory = 500 // 當前 history 值，初始為預設值
    private val historyStep = 50 // history 調整步伐

    // MOG2 varThreshold 相關參數
    private val minVarThreshold = 8.0 // varThreshold 最小值
    private val maxVarThreshold = 50.0 // varThreshold 最大值
    private var currentVarThreshold = 16.0 // 當前 varThreshold 值，OpenCV 預設為 16
    private val varThresholdStep = 2.0 // varThreshold 調整步伐 (暫未使用，直接映射)
    private val minStdDevForContrast = 10.0 // 用於對比度調整的最小標準差
    private val maxStdDevForContrast = 60.0 // 用於對比度調整的最大標準差

    // 用於儲存當前幀的灰度圖
    private var grayMat = Mat()
    // 用於儲存旋轉後的圖像
    private var rotatedMat = Mat()
    // 用於儲存前景遮罩
    private var fgMask = Mat()
    // 用於形態學操作的核心
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(5.0, 5.0))
    // 最小輪廓面積，過濾小噪點
    // private val minContourArea = 50.0 // 250 根據需要調整 (舊的固定值)
    private val minMinContourArea = 10.0 // minContourArea 的最小值
    private val maxMinContourArea = 200.0 // minContourArea 的最大值
    private var currentMinContourArea = 100.0 // 當前的 minContourArea，初始值
    private val minContourAreaStep = 10.0 // minContourArea 調整的步長
    private var frameCountForMinContourAreaAdjust = 0 // 用於 minContourArea 調整的幀計數器

    /**
     * 初始化背景扣除器。
     * 確保只在 OpenCV 初始化後調用。
     */
    /**
     * 初始化背景扣除器 (MOG2)。
     * @param history 背景模型的歷史幀數。
     * @param varThreshold 像素值與模型之間平方馬氏距離的閾值，用於判斷像素是否屬於背景。
     * 調整參數以提高對顏色與背景相近的移動物體的偵測能力。
     * 確保只在 OpenCV 初始化後調用。
     */
    private fun initializeBgSubtractor(history: Int, varThreshold: Double) {
        // 釋放舊的 bgSubtractor (如果存在)
        bgSubtractor?.clear()
        bgSubtractor = Video.createBackgroundSubtractorMOG2(history, varThreshold, true)
        Log.d("ObjectTrackerAnalyzer", "BackgroundSubtractorMOG2 initialized with history=$history, varThreshold=$varThreshold and detectShadows=true.")
    }

    /**
     * 分析圖像幀，使用光流法偵測相機晃動，若晃動不大則使用 MOG2 背景扣除和輪廓分析偵測移動物體中心。
     * @param image The image proxy containing the frame data.
     */
    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        Log.d("ObjectTrackerAnalyzer", "analyze() called")
        val rotationDegrees = image.imageInfo.rotationDegrees
        Log.d("ObjectTrackerAnalyzer", "Image rotation: $rotationDegrees degrees")

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
        // 這裡假設 plane[1] 是 U，plane[2] 是 V，並且它們的 rowStride 和 pixelStride 允許直接複製。
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

        var needsBgSubtractorReinit = bgSubtractor == null
        // --- 計算對比度並調整 varThreshold ---
        val meanMat = MatOfDouble()
        val stdDevMat = MatOfDouble()
        Core.meanStdDev(rotatedMat, meanMat, stdDevMat)
        val contrastStdDev = if (stdDevMat.rows() > 0 && stdDevMat.cols() > 0) stdDevMat[0, 0][0] else 0.0
        meanMat.release()
        stdDevMat.release()

        val oldVarThreshold = currentVarThreshold
        // 線性映射標準差到 varThreshold 範圍
        val normalizedStdDev = (contrastStdDev - minStdDevForContrast) / (maxStdDevForContrast - minStdDevForContrast)
        val clampedNormalizedStdDev = kotlin.math.max(0.0, kotlin.math.min(1.0, normalizedStdDev))
        currentVarThreshold = minVarThreshold + clampedNormalizedStdDev * (maxVarThreshold - minVarThreshold)
        Log.d("ObjectTrackerAnalyzer", "Contrast stdDev: $contrastStdDev, New varThreshold: $currentVarThreshold")

        if (kotlin.math.abs(oldVarThreshold - currentVarThreshold) > 0.1) { // 如果 varThreshold 變化顯著
            needsBgSubtractorReinit = true
            Log.d("ObjectTrackerAnalyzer", "varThreshold changed: $oldVarThreshold -> $currentVarThreshold. Reinitializing MOG2.")
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
                Log.d("ObjectTrackerAnalyzer", "Average movement: $avgMovement, Valid points: $validPointsCount, Current history: $currentHistory")

                val oldHistory = currentHistory
                if (avgMovement > shakeThreshold) { // 晃動較大
                    Log.w("ObjectTrackerAnalyzer", "Camera shake detected (movement: $avgMovement > threshold: $shakeThreshold)")
                    currentHistory = kotlin.math.max(minHistory, currentHistory - historyStep)
                    // 如果晃動過大，則跳過物件偵測
                    onObjectTracked(null, rotatedMat.width(), rotatedMat.height(), isRecording ,enableRecording)
                    // 更新 prevGrayMat 和 prevPoints 以便下一幀比較
                    rotatedMat.copyTo(prevGrayMat)
                    // 重新偵測特徵點，因為畫面可能已大幅改變
                    val newFeatures = MatOfPoint()
                    Imgproc.goodFeaturesToTrack(rotatedMat, newFeatures, 100, 0.3, 7.0)
                    prevPoints = MatOfPoint2f(*newFeatures.toArray())
                    newFeatures.release()

                    if (oldHistory != currentHistory) {
                        needsBgSubtractorReinit = true
                        Log.d("ObjectTrackerAnalyzer", "History changed due to shake: $oldHistory -> $currentHistory. Reinitializing MOG2.")
                    }

                    currentGrayMat.release()
                    image.close()
                    return // 晃動大時直接返回，不進行 MOG2
                } else if (avgMovement < movementThresholdStable) { // 畫面穩定
                    currentHistory = kotlin.math.min(maxHistory, currentHistory + historyStep)
                    Log.d("ObjectTrackerAnalyzer", "Camera stable (movement: $avgMovement < threshold: $movementThresholdStable)")
                } else { // 晃動不大也不算特別穩定，history 維持或微調 (可選)
                    // 可以選擇在此處不改變 history，或者根據晃動程度進行更細微的調整
                }

                if (oldHistory != currentHistory) {
                    needsBgSubtractorReinit = true
                    Log.d("ObjectTrackerAnalyzer", "History changed: $oldHistory -> $currentHistory. Reinitializing MOG2.")
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
        if (needsBgSubtractorReinit) {
            initializeBgSubtractor(currentHistory, currentVarThreshold)
        }

        // 如果晃動不大，則繼續 MOG2
        // 應用背景扣除 (注意：MOG2 應該在 rotatedMat 上操作，因為它是當前處理的灰階圖)
        bgSubtractor?.apply(rotatedMat, fgMask, 0.01) // learningRate 設為較小值以穩定背景模型

        if (fgMask.empty()) {
            Log.d("ObjectTrackerAnalyzer", "Foreground mask is empty.")
            onObjectTracked(null, rotatedMat.width(), rotatedMat.height(), isRecording, enableRecording)
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
            if (area > currentMinContourArea && area > maxArea) { // 使用 currentMinContourArea
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

        // 動態調整 minContourArea
        frameCountForMinContourAreaAdjust++
        if (frameCountForMinContourAreaAdjust >= 30) {
            if (currentHistory == maxHistory && centerPoint == null) {
                // 當 history 為最大值且未偵測到移動物體時，降低 minContourArea
                currentMinContourArea = kotlin.math.max(minMinContourArea, currentMinContourArea - minContourAreaStep)
                Log.d("ObjectTrackerAnalyzer", "Adjusting minContourArea down to: $currentMinContourArea")
            } else if (currentHistory != maxHistory) {
                // 當 history 不為最大值時，增加 minContourArea (直到偵測到物體或達到上限)
                // 這裡的邏輯是如果 history 不是最大，代表可能有晃動或剛穩定，可以嘗試提高偵測靈敏度
                currentMinContourArea = kotlin.math.min(maxMinContourArea, currentMinContourArea + minContourAreaStep)
                Log.d("ObjectTrackerAnalyzer", "Adjusting minContourArea up to: $currentMinContourArea")
            }
            frameCountForMinContourAreaAdjust = 0 // 重置計數器
        }

        // 錄影控制邏輯
        val currentTime = System.currentTimeMillis()
        if (centerPoint != null) {
            lastUnDetectedTime = currentTime
            Log.d("Recording", "lastUnDetectedTime: $lastUnDetectedTime")
            // 如果偵測到物體且未開始錄影，檢查是否達到開始延遲
            if (!isRecording && enableRecording && ((currentTime - lastDetectedTime) >= startDelay)) {
                startRecording()
            }
        } else {
            lastDetectedTime = currentTime
            Log.d("Recording", "lastDetectedTime: $lastDetectedTime")
            // 如果未偵測到物體且正在錄影，檢查是否達到停止延遲
            if (isRecording && ((currentTime - lastUnDetectedTime) >= stopDelay)) {
                stopRecording()
            }
        }
        
        // 如果有錄影且偵測到物體，寫入當前幀
        if (isRecording && centerPoint != null && videoWriter != null) {
            try {
                // 將灰階圖轉換為BGR格式以便錄影
                val bgrMat = Mat()
                Imgproc.cvtColor(rotatedMat, bgrMat, Imgproc.COLOR_GRAY2BGR)
                videoWriter?.write(bgrMat)
                bgrMat.release()
            } catch (e: Exception) {
                Log.e("ObjectTrackerAnalyzer", "Failed to write video frame", e)
            }
        }

        // 回調結果
        onObjectTracked(centerPoint, rotatedMat.width(), rotatedMat.height(),isRecording, enableRecording)
        image.close()
        Log.d("ObjectTrackerAnalyzer", "analyze() finished")
    }
}

// --- (可能需要的其他 Composable 或輔助函數) ---