package com.ultrawidecam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.ultrawidecam.R
import com.ultrawidecam.databinding.ActivityCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null

    // Use cases
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    // Video recording
    private var activeRecording: Recording? = null
    private var recordingStartTime = 0L
    private val recordingTimerHandler = Handler(Looper.getMainLooper())
    private var dotAnimator: ObjectAnimator? = null

    // Camera Modes & Settings
    private var isFrontCamera = false
    private var isVideoMode = false
    private var isAspectRatio169 = false
    private var isTorchOn = false

    // External intent capture
    private var targetCaptureUri: Uri? = null

    // ML Kit Scanner & State
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private var isProcessingPaymentIntent = false
    private var isCaptureInProgress = false
    private var lastScannedValue = ""
    private var lastScanTime = 0L

    // Pinch-to-zoom and gestures
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // Exposure drag state
    private var dragStartY = 0f
    private var dragStartExposure = 0f
    private var isDraggingExposure = false
    private val hideExposureRunnable = Runnable {
        binding.exposureContainer.visibility = View.GONE
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val SCAN_COOLDOWN_MS = 3000L

        // Digital zoom factors relative to native 0.6x sensor
        private const val ZOOM_RATIO_06X = 1.0f
        private const val ZOOM_RATIO_10X = 1.67f
        private const val ZOOM_RATIO_20X = 3.33f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            showPermissionDeniedUI()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processQrFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Handle System Insets (Status Bar & Display Cutout on S23 Ultra)
        applySystemInsets()

        // Check if launched as System Camera by another app (e.g. WhatsApp / banking app)
        if (intent.action == MediaStore.ACTION_IMAGE_CAPTURE ||
            intent.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE ||
            intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) {
            @Suppress("DEPRECATION")
            targetCaptureUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            Log.i(TAG, "Launched via IMAGE_CAPTURE intent with target URI: $targetCaptureUri")
        }

        setupControls()
        setupTouchControls()
        setupZoomPresetButtons()
        setupModeSwitcher()
        setupActionDockControls()

        checkAndRequestPermissions()
        updateGalleryThumbnail()
    }

    override fun onResume() {
        super.onResume()
        isProcessingPaymentIntent = false
        lastScanTime = System.currentTimeMillis() + 1000L
        if (binding.permissionOverlay.visibility == View.VISIBLE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        }
        updateGalleryThumbnail()
    }

    override fun onPause() {
        super.onPause()
        if (isTorchOn) {
            disableAllTorch()
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainCameraLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val cutoutInset = insets.displayCutout?.safeInsetTop ?: 0

            // Ensure topBar is well below camera punch hole and status bar icons
            val minTopMargin = (60 * resources.displayMetrics.density).toInt()
            val safeTopMargin = maxOf(statusBarHeight, cutoutInset, minTopMargin)
            val topParams = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
            if (topParams != null && topParams.topMargin != safeTopMargin) {
                topParams.topMargin = safeTopMargin
                binding.topBar.layoutParams = topParams
            }

            // Ensure bottom controls clear navigation bar
            val safeBottomPadding = maxOf(navBarHeight, (20 * resources.displayMetrics.density).toInt()) +
                    (12 * resources.displayMetrics.density).toInt()
            binding.bottomControls.setPadding(
                binding.bottomControls.paddingLeft,
                binding.bottomControls.paddingTop,
                binding.bottomControls.paddingRight,
                safeBottomPadding
            )

            insets
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showPermissionDeniedUI() {
        binding.permissionOverlay.visibility = View.VISIBLE
        binding.btnGrantPermission.setOnClickListener {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
    }

    private fun setupControls() {
        binding.btnCapture.setOnClickListener { onShutterClicked() }

        binding.btnFlipCamera.setOnClickListener {
            if (activeRecording != null) {
                Toast.makeText(this, "Cannot flip camera while recording", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnFlipCamera.animate().rotationBy(180f).setDuration(300).start()
            isFrontCamera = !isFrontCamera
            vibrateHaptic()
            startCamera()
        }

        // Top Bar Torch with dual-strategy support (CameraX + CameraManager for ultrawide)
        binding.btnTorch.setOnClickListener {
            toggleTorch()
        }
    }

    private fun toggleTorch() {
        if (isFrontCamera) {
            Toast.makeText(this, "Torch unavailable on front camera", Toast.LENGTH_SHORT).show()
            return
        }

        val targetTorch = !isTorchOn
        val camera = activeCamera

        // Strategy 1: CameraX cameraControl if flash unit reported
        if (camera != null && camera.cameraInfo.hasFlashUnit()) {
            val future = camera.cameraControl.enableTorch(targetTorch)
            future.addListener({
                try {
                    future.get()
                    isTorchOn = targetTorch
                    updateTorchUI(isTorchOn)
                    vibrateHaptic()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle torch via CameraX: ${e.message}")
                    Toast.makeText(this@CameraActivity, "Torch activation failed", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
            return
        }

        // Strategy 2: System CameraManager on shared flash unit (Back Camera ID 0)
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: "0"

            cameraManager.setTorchMode(flashCameraId, targetTorch)
            isTorchOn = targetTorch
            updateTorchUI(isTorchOn)
            vibrateHaptic()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch via CameraManager: ${e.message}")
            Toast.makeText(this, "Flash unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableAllTorch() {
        try {
            activeCamera?.cameraControl?.enableTorch(false)
        } catch (_: Exception) {}
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: "0"
            cameraManager.setTorchMode(flashCameraId, false)
        } catch (_: Exception) {}
        isTorchOn = false
        updateTorchUI(false)
    }

    private fun updateTorchUI(on: Boolean) {
        if (on) {
            binding.btnTorch.setBackgroundResource(R.drawable.torch_btn_active_bg)
            binding.btnTorch.setTextColor(0xFFFFD600.toInt())
            binding.btnTorch.text = "⚡ Torch On"
        } else {
            binding.btnTorch.setBackgroundResource(R.drawable.dock_item_bg)
            binding.btnTorch.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTorch.text = "⚡ Torch"
        }
    }

    private fun setupModeSwitcher() {
        binding.tabPhoto.setOnClickListener {
            if (activeRecording != null) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setVideoMode(false)
        }
        binding.tabVideo.setOnClickListener {
            if (activeRecording != null) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setVideoMode(true)
        }
    }

    private fun setVideoMode(videoMode: Boolean) {
        isVideoMode = videoMode
        if (isVideoMode) {
            binding.tabVideo.setTextColor(0xFFFFFFFF.toInt())
            binding.tabPhoto.setTextColor(0x88FFFFFF.toInt())
            binding.btnCapture.setBackgroundResource(R.drawable.record_button_bg)
        } else {
            binding.tabPhoto.setTextColor(0xFFFFFFFF.toInt())
            binding.tabVideo.setTextColor(0x88FFFFFF.toInt())
            binding.btnCapture.setBackgroundResource(R.drawable.capture_button_bg)
        }
        startCamera()
    }

    private fun setupActionDockControls() {
        // Liquid Glass Rupee (₹) Payment Hub
        binding.btnPaymentHub.setOnClickListener {
            if (activeRecording != null) {
                Toast.makeText(this, "Please stop recording before opening Payment Hub", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.btnPaymentHub.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80)
                .withEndAction {
                    binding.btnPaymentHub.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }.start()
            startActivity(Intent(this, PaymentHubActivity::class.java))
        }

        // 📐 Aspect Ratio Toggle (4:3 vs 16:9)
        binding.btnRatio.setOnClickListener {
            if (activeRecording != null) {
                Toast.makeText(this, "Cannot change aspect ratio while recording", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            isAspectRatio169 = !isAspectRatio169
            binding.btnRatio.text = if (isAspectRatio169) "📐 16:9" else "📐 4:3"
            startCamera()
        }
    }

    private fun setupZoomPresetButtons() {
        binding.btnZoom06.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyZoom(ZOOM_RATIO_06X)
            applyZoomPresetUI(0)
        }
        binding.btnZoom10.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyZoom(ZOOM_RATIO_10X)
            applyZoomPresetUI(1)
        }
        binding.btnZoom20.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyZoom(ZOOM_RATIO_20X)
            applyZoomPresetUI(2)
        }
    }

    private fun applyZoom(targetRatio: Float) {
        val camera = activeCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val clampedRatio = targetRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera.cameraControl.setZoomRatio(clampedRatio)
    }

    private fun applyZoomPresetUI(selectedIndex: Int) {
        val buttons = listOf(binding.btnZoom06, binding.btnZoom10, binding.btnZoom20)
        for (i in buttons.indices) {
            val btn = buttons[i]
            if (i == selectedIndex) {
                btn.setBackgroundResource(R.drawable.zoom_btn_selected_bg)
                btn.setTextColor(0xFF000000.toInt())
            } else {
                btn.background = null
                btn.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun updateZoomPillSelection(currentRatio: Float) {
        when {
            currentRatio < 1.35f -> applyZoomPresetUI(0)
            currentRatio < 2.5f  -> applyZoomPresetUI(1)
            else                 -> applyZoomPresetUI(2)
        }
    }

    private fun setupTouchControls() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = activeCamera ?: return false
                val zoomState = camera.cameraInfo.zoomState.value ?: return false
                val currentZoom = zoomState.zoomRatio
                val newZoom = (currentZoom * detector.scaleFactor).coerceIn(
                    zoomState.minZoomRatio,
                    zoomState.maxZoomRatio
                )
                camera.cameraControl.setZoomRatio(newZoom)
                updateZoomPillSelection(newZoom)
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.pointerCount > 1) {
                isDraggingExposure = false
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.y
                    dragStartExposure = getCurrentExposure()
                    isDraggingExposure = false
                    binding.exposureContainer.removeCallbacks(hideExposureRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaY = dragStartY - event.y
                    if (Math.abs(deltaY) > 24) {
                        isDraggingExposure = true
                        val camera = activeCamera
                        val exposureState = camera?.cameraInfo?.exposureState
                        val exposureRange = exposureState?.exposureCompensationRange

                        if (exposureRange != null && exposureRange.lower < exposureRange.upper) {
                            val step = exposureState.exposureCompensationStep.toFloat()
                            val maxEv = exposureRange.upper * step
                            val minEv = exposureRange.lower * step

                            val evDelta = (deltaY / 280f) * (maxEv - minEv)
                            val newEv = (dragStartExposure + evDelta).coerceIn(minEv, maxEv)
                            val newIndex = (newEv / step).toInt()
                                .coerceIn(exposureRange.lower, exposureRange.upper)

                            camera.cameraControl.setExposureCompensationIndex(newIndex)

                            val evLabel = if (newEv >= 0) "+%.1f EV".format(newEv) else "%.1f EV".format(newEv)
                            binding.tvExposure.text = evLabel
                            binding.exposureContainer.visibility = View.VISIBLE

                            val trackUsableHeight = binding.exposureTrackContainer.height.toFloat() -
                                    binding.exposureThumb.height.toFloat()
                            if (trackUsableHeight > 0) {
                                val fraction = 1f - ((newEv - minEv) / (maxEv - minEv))
                                binding.exposureThumb.translationY = (fraction * trackUsableHeight)
                                    .coerceIn(0f, trackUsableHeight)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDraggingExposure && !scaleGestureDetector.isInProgress) {
                        handleTapToFocus(event.x, event.y)
                    }
                    isDraggingExposure = false
                    binding.exposureContainer.removeCallbacks(hideExposureRunnable)
                    binding.exposureContainer.postDelayed(hideExposureRunnable, 2000)
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDraggingExposure = false
                    binding.exposureContainer.removeCallbacks(hideExposureRunnable)
                    binding.exposureContainer.postDelayed(hideExposureRunnable, 1000)
                    true
                }

                else -> false
            }
        }
    }

    private fun getCurrentExposure(): Float {
        val state = activeCamera?.cameraInfo?.exposureState ?: return 0f
        val index = state.exposureCompensationIndex
        val step = state.exposureCompensationStep.toFloat()
        return index * step
    }

    private fun handleTapToFocus(x: Float, y: Float) {
        val camera = activeCamera ?: return
        val display = binding.viewFinder.display ?: return

        try {
            val meteringFactory = DisplayOrientedMeteringPointFactory(
                display,
                camera.cameraInfo,
                binding.viewFinder.width.toFloat(),
                binding.viewFinder.height.toFloat()
            )
            val point = meteringFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            camera.cameraControl.startFocusAndMetering(action)

            val halfWidth = if (binding.focusRing.width > 0) binding.focusRing.width / 2f else 36f * resources.displayMetrics.density
            val halfHeight = if (binding.focusRing.height > 0) binding.focusRing.height / 2f else 36f * resources.displayMetrics.density

            binding.focusRing.translationX = x - halfWidth
            binding.focusRing.translationY = y - halfHeight
            binding.focusRing.visibility = View.VISIBLE
            binding.focusRing.alpha = 1f
            binding.focusRing.animate()
                .alpha(0f)
                .setDuration(700)
                .setStartDelay(500)
                .withEndAction { binding.focusRing.visibility = View.GONE }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Focus metering failed: ${e.message}")
        }
    }

    /**
     * Binds CameraX use cases to either 0.6x Ultrawide or Front Camera.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get ProcessCameraProvider: ${e.message}")
                return@addListener
            }

            @Suppress("DEPRECATION")
            val targetAspectRatio = if (isAspectRatio169) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

            @Suppress("DEPRECATION")
            preview = Preview.Builder()
                .setTargetAspectRatio(targetAspectRatio)
                .build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            @Suppress("DEPRECATION")
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(targetAspectRatio)
                .build()

            @Suppress("DEPRECATION")
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(targetAspectRatio)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processQr(imageProxy)
                    }
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select physical camera: 0.6x Ultrawide for Back, or Front selfie camera
            val targetCameraId = if (isFrontCamera) {
                CameraHelper.getFrontCameraId(this)
            } else {
                CameraHelper.getUltraWideCameraId(this)
            }

            Log.i(TAG, "Binding camera (isFront=$isFrontCamera) with target ID: $targetCameraId")

            val cameraSelector = CameraSelector.Builder()
                .addCameraFilter { cameraInfoList ->
                    if (targetCameraId != null) {
                        val filtered = cameraInfoList.filter { cameraInfo ->
                            Camera2CameraInfo.from(cameraInfo).cameraId == targetCameraId
                        }
                        filtered.ifEmpty {
                            cameraInfoList.filter {
                                it.lensFacing == if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            }.ifEmpty { cameraInfoList }
                        }
                    } else {
                        cameraInfoList.filter {
                            it.lensFacing == if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                        }.ifEmpty { cameraInfoList }
                    }
                }
                .build()

            try {
                cameraProvider?.unbindAll()

                // Bind use cases according to active mode (no QR analysis during video filming)
                activeCamera = if (isVideoMode) {
                    cameraProvider?.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture
                    )
                } else {
                    cameraProvider?.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, imageAnalyzer
                    )
                }

                if (isFrontCamera) {
                    binding.tvCameraStatus.text = "● Front Cam"
                    binding.zoomPillContainer.visibility = View.GONE
                } else {
                    binding.tvCameraStatus.text = "● 0.6x UW"
                    binding.zoomPillContainer.visibility = View.VISIBLE
                    activeCamera?.cameraControl?.setZoomRatio(ZOOM_RATIO_06X)
                    applyZoomPresetUI(0)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera bind error: ${e.message}")
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onShutterClicked() {
        if (isVideoMode) {
            toggleVideoRecording()
        } else {
            takePhoto()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        if (isCaptureInProgress) return
        isCaptureInProgress = true

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        binding.btnCapture.isEnabled = false
        binding.btnCapture.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
            .withEndAction {
                binding.btnCapture.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
        vibrateHaptic(HapticFeedbackConstants.VIRTUAL_KEY)

        // If another app invoked ONCE_PAY via IMAGE_CAPTURE intent with EXTRA_OUTPUT
        if (targetCaptureUri != null) {
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                targetCaptureUri!!,
                ContentValues()
            ).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                        binding.btnCapture.isEnabled = true
                        isCaptureInProgress = false
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        isCaptureInProgress = false
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            )
            return
        }

        // Standard save to MediaStore Pictures/ONCE_PAY
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ONCE_PAY_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ONCE_PAY")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    binding.btnCapture.isEnabled = true
                    isCaptureInProgress = false
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Photo saved to ONCE_PAY", Toast.LENGTH_SHORT).show()
                    binding.btnCapture.isEnabled = true
                    isCaptureInProgress = false
                    updateGalleryThumbnail()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val videoCapture = this.videoCapture ?: return

        if (activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            vibrateHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ONCE_PAY_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ONCE_PAY")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCapture.output.prepareRecording(this, outputOptions)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    onRecordingStarted()
                }
                is VideoRecordEvent.Finalize -> {
                    onRecordingFinalized(recordEvent)
                }
            }
        }
        vibrateHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun onRecordingStarted() {
        binding.btnCapture.setBackgroundResource(R.drawable.record_stop_bg)
        binding.recordingHud.visibility = View.VISIBLE
        recordingStartTime = SystemClock.elapsedRealtime()

        dotAnimator = ObjectAnimator.ofFloat(binding.recordingBlinkDot, "alpha", 1f, 0.2f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        recordingTimerHandler.post(object : Runnable {
            override fun run() {
                if (activeRecording == null) return
                val elapsedSeconds = (SystemClock.elapsedRealtime() - recordingStartTime) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                binding.tvRecordingTimer.text = "%02d:%02d".format(minutes, seconds)
                recordingTimerHandler.postDelayed(this, 500)
            }
        })
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        dotAnimator?.cancel()
        binding.recordingHud.visibility = View.GONE
        binding.btnCapture.setBackgroundResource(R.drawable.record_button_bg)
        activeRecording = null

        if (!event.hasError()) {
            Toast.makeText(this, "Video saved to Movies/ONCE_PAY", Toast.LENGTH_SHORT).show()
            updateGalleryThumbnail()
        } else {
            Log.e(TAG, "Video recording error: ${event.error}")
            Toast.makeText(this, "Recording error: ${event.error}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateGalleryThumbnail() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

            var latestImageUri: Uri? = null
            var latestImageDate: Long = -1

            var latestVideoUri: Uri? = null
            var latestVideoDate: Long = -1

            val isQPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            try {
                val selection = if (isQPlus) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
                val selectionArgs = if (isQPlus) arrayOf("Pictures/ONCE_PAY%") else null

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        latestImageDate = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                        latestImageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying latest image thumbnail: ${e.message}")
            }

            try {
                val selection = if (isQPlus) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
                val selectionArgs = if (isQPlus) arrayOf("Movies/ONCE_PAY%") else null

                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        latestVideoDate = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                        latestVideoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying latest video thumbnail: ${e.message}")
            }

            val (latestUri, isVideo) = if (latestVideoDate > latestImageDate && latestVideoUri != null) {
                Pair(latestVideoUri, true)
            } else if (latestImageUri != null) {
                Pair(latestImageUri, false)
            } else if (latestVideoUri != null) {
                Pair(latestVideoUri, true)
            } else {
                Pair(null, false)
            }

            if (latestUri != null) {
                try {
                    val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(latestUri, Size(120, 120), null)
                    } else {
                        @Suppress("DEPRECATION")
                        if (isVideo) {
                            MediaStore.Video.Thumbnails.getThumbnail(
                                contentResolver,
                                ContentUris.parseId(latestUri),
                                MediaStore.Video.Thumbnails.MINI_KIND,
                                null
                            )
                        } else {
                            MediaStore.Images.Thumbnails.getThumbnail(
                                contentResolver,
                                ContentUris.parseId(latestUri),
                                MediaStore.Images.Thumbnails.MINI_KIND,
                                null
                            )
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            binding.ivGalleryThumbnail.setImageBitmap(bitmap)
                            binding.ivGalleryThumbnail.setOnClickListener {
                                try {
                                    val mimeType = if (isVideo) "video/*" else "image/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(latestUri, mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this@CameraActivity, "Could not open gallery", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed loading thumbnail: ${e.message}")
                }
            }
        }
    }

    private fun processQrFromUri(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var found = false
                    for (barcode in barcodes) {
                        val value = barcode.rawValue ?: continue
                        if (isPaymentQr(value)) {
                            found = true
                            vibrateHaptic(HapticFeedbackConstants.CONFIRM)
                            handlePaymentQr(value)
                            break
                        }
                    }
                    if (!found) {
                        Toast.makeText(this, "No valid payment QR found in image", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed opening image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processQr(imageProxy: ImageProxy) {
        if (isVideoMode || activeRecording != null || isProcessingPaymentIntent || isFinishing || isDestroyed) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val value = barcode.rawValue ?: continue
                    val now = System.currentTimeMillis()
                    if (value == lastScannedValue && now - lastScanTime < SCAN_COOLDOWN_MS) continue
                    lastScannedValue = value
                    lastScanTime = now
                    if (isPaymentQr(value)) {
                        vibrateHaptic(HapticFeedbackConstants.CONFIRM)
                        runOnUiThread { handlePaymentQr(value) }
                        break
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Barcode scan error: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun isPaymentQr(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("upi://pay", ignoreCase = true) ||
                trimmed.startsWith("paytm://pay", ignoreCase = true) ||
                trimmed.startsWith("phonepe://pay", ignoreCase = true) ||
                trimmed.matches(Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$"))
    }

    private fun handlePaymentQr(upiUrl: String) {
        if (isFinishing || isDestroyed || isProcessingPaymentIntent || isVideoMode || activeRecording != null) return
        isProcessingPaymentIntent = true

        val trimmed = upiUrl.trim()
        var payeeVpa = ""
        var payeeName = "Merchant"
        var amount = ""

        if (trimmed.startsWith("upi://", ignoreCase = true) ||
            trimmed.startsWith("paytm://", ignoreCase = true) ||
            trimmed.startsWith("phonepe://", ignoreCase = true)
        ) {
            val uri = try { Uri.parse(trimmed) } catch (_: Exception) { null }
            payeeVpa = uri?.getQueryParameter("pa") ?: ""
            payeeName = Uri.decode(uri?.getQueryParameter("pn") ?: "").ifBlank { "Merchant" }
            amount = uri?.getQueryParameter("am") ?: ""
        } else if (trimmed.matches(Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$"))) {
            payeeVpa = trimmed
            payeeName = trimmed
        }

        // 1. Auto-prime clipboard with payee VPA
        primeClipboardWithVpa(payeeVpa)

        // 2. Save to local recent payees ledger
        PaymentHistoryHelper.addRecord(this, payeeName, payeeVpa, amount)

        // 3. Directly open Google Pay with copied UPI ID
        launchDirectGPay(payeeVpa, trimmed)
    }

    /**
     * Auto-primes the system clipboard with the Payee VPA.
     */
    private fun primeClipboardWithVpa(vpa: String) {
        if (vpa.isBlank()) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", vpa))
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard: ${e.message}")
        }
    }

    /**
     * Directly launches the Google Pay app in its trusted native state with the VPA primed on clipboard,
     * avoiding 3rd-party intent payment blocks. Falls back to system chooser if GPay is not installed.
     */
    private fun launchDirectGPay(payeeVpa: String, fallbackUrl: String) {
        val gpayPackage = "com.google.android.apps.nbu.paisa.user"
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(gpayPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            if (launchIntent != null) {
                val msg = if (payeeVpa.isNotBlank()) "Copied: $payeeVpa\nOpening Google Pay..." else "Opening Google Pay..."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Google Pay not installed. Choose payment app.", Toast.LENGTH_SHORT).show()
                launchSystemUpiChooser(fallbackUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Google Pay: ${e.message}")
            launchSystemUpiChooser(fallbackUrl)
        }
    }

    /**
     * Fallback: Launches Android's native intent resolver.
     */
    private fun launchSystemUpiChooser(cleanUpiUrl: String) {
        try {
            val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUpiUrl))
            val chooserIntent = Intent.createChooser(baseIntent, "Pay using").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooserIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No UPI app found on device", Toast.LENGTH_LONG).show()
            isProcessingPaymentIntent = false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching UPI app chooser: ${e.message}")
            Toast.makeText(this, "Could not open payment app: ${e.message}", Toast.LENGTH_SHORT).show()
            isProcessingPaymentIntent = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) {
                onShutterClicked()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrateHaptic(constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        try {
            binding.root.performHapticFeedback(constant)
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        dotAnimator?.cancel()
        try {
            barcodeScanner.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing barcode scanner: ${e.message}")
        }
        cameraExecutor.shutdown()
    }
}
// Zoom engine: 0.6x UW, 1.0x Wide, 2.0x Tele

// Metering engine: Tap-to-focus with 3s auto-reset
