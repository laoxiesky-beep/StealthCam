package com.stealthcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealthcam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var volumeKeyDownTime = 0L
    private val LONG_PRESS_MS = 800L
    private var recordingBlinkRunnable: Runnable? = null

    // 透明度控制条自动隐藏
    private var hideSeekBarRunnable: Runnable? = null

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupAlphaControl()

        if (allPermissionsGranted()) {
            startCamera()
            startCameraService()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    // ── 透明度控制 ─────────────────────────────────────────────────────────────

    private fun setupAlphaControl() {
        // 长按预览窗口显示透明度滑条
        binding.cameraContainer.setOnLongClickListener {
            if (binding.seekBarAlpha.visibility == View.GONE) {
                showSeekBar()
            } else {
                hideSeekBar()
            }
            true
        }

        binding.seekBarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // progress 0~100 映射到透明度 0.1~1.0（最低保留10%可见）
                val alpha = 0.1f + progress / 100f * 0.9f
                binding.cameraContainer.alpha = alpha
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // 拖动时取消自动隐藏
                hideSeekBarRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 停止拖动后3秒自动隐藏
                scheduleHideSeekBar()
            }
        })

        // 初始进度100 = 完全不透明
        binding.seekBarAlpha.progress = 100
    }

    private fun showSeekBar() {
        binding.seekBarAlpha.visibility = View.VISIBLE
        scheduleHideSeekBar()
    }

    private fun hideSeekBar() {
        binding.seekBarAlpha.visibility = View.GONE
    }

    private fun scheduleHideSeekBar() {
        hideSeekBarRunnable?.let { handler.removeCallbacks(it) }
        hideSeekBarRunnable = Runnable { hideSeekBar() }
        handler.postDelayed(hideSeekBarRunnable!!, 3000)
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            @Suppress("DEPRECATION")
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(android.util.Size(4032, 3024))
                .build()

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                showToast("相机启动失败")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── 前台服务（息屏保活）────────────────────────────────────────────────────

    private fun startCameraService() {
        startForegroundService(Intent(this, CameraService::class.java))
    }

    // ── 音量键：短按拍照，长按开始录像，录像中再按停止 ─────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeKeyDownTime = System.currentTimeMillis()
                    if (!isRecording) {
                        longPressRunnable = Runnable { startVideoRecording() }
                        handler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - volumeKeyDownTime
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

            when {
                isRecording && held < LONG_PRESS_MS -> stopVideoRecording()
                !isRecording && held < LONG_PRESS_MS -> takePhoto()
            }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── 拍照 ──────────────────────────────────────────────────────────────────

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/隐形相机")
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
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    showFlash()
                }
                override fun onError(exception: ImageCaptureException) {
                    showToast("❌ 拍照失败")
                }
            }
        )
    }

    // ── 录像 ──────────────────────────────────────────────────────────────────

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("需要麦克风权限")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/隐形相机")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        runOnUiThread { showRecordingUI(true) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        runOnUiThread { showRecordingUI(false) }
                    }
                    else -> {}
                }
            }
    }

    private fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    // ── UI 反馈 ────────────────────────────────────────────────────────────────

    private fun showFlash() {
        binding.previewView.alpha = 0f
        handler.postDelayed({ binding.previewView.alpha = 1f }, 150)
    }

    private fun showRecordingUI(show: Boolean) {
        if (show) {
            // 黄点亮起并闪烁
            binding.recordingIndicator.visibility = View.VISIBLE
            recordingBlinkRunnable = object : Runnable {
                override fun run() {
                    binding.recordingIndicator.visibility =
                        if (binding.recordingIndicator.visibility == View.VISIBLE)
                            View.INVISIBLE else View.VISIBLE
                    if (isRecording) handler.postDelayed(this, 600)
                }
            }
            handler.post(recordingBlinkRunnable!!)
        } else {
            handler.removeCallbacks(recordingBlinkRunnable ?: return)
            binding.recordingIndicator.visibility = View.GONE
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            binding.toastText.text = msg
            binding.toastText.visibility = View.VISIBLE
            handler.postDelayed({ binding.toastText.visibility = View.GONE }, 2500)
        }
    }

    // ── 权限 ──────────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && allPermissionsGranted()) {
            startCamera()
            startCameraService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isRecording) stopVideoRecording()
        stopService(Intent(this, CameraService::class.java))
    }
}
