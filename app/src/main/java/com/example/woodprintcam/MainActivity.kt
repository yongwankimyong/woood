package com.example.woodprintcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.woodprintcam.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 사용 가능한 프레임 종류
enum class FrameType {
    NONE, WOOD, POLAROID, HEART, FILMSTRIP, STARS, RAINBOW, SNOW, GOLD, BUBBLE
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastCapturedBitmap: Bitmap? = null
    private var originalBitmap: Bitmap? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedFrame = FrameType.NONE

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraUi()
                startCamera()
            } else {
                showPermissionDeniedUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) {
            showCameraUi()
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.shutterButton.setOnClickListener { startCountdownAndCapture() }
        binding.retakeButton.setOnClickListener { returnToCameraMode() }
        binding.printButton.setOnClickListener { printCapturedPhoto() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }

        binding.frameNoneBtn.setOnClickListener { selectFrame(FrameType.NONE) }
        binding.frameWoodBtn.setOnClickListener { selectFrame(FrameType.WOOD) }
        binding.framePolaroidBtn.setOnClickListener { selectFrame(FrameType.POLAROID) }
        binding.frameHeartBtn.setOnClickListener { selectFrame(FrameType.HEART) }
        binding.frameFilmBtn.setOnClickListener { selectFrame(FrameType.FILMSTRIP) }
        binding.frameStarBtn.setOnClickListener { selectFrame(FrameType.STARS) }
        binding.frameRainbowBtn.setOnClickListener { selectFrame(FrameType.RAINBOW) }
        binding.frameSnowBtn.setOnClickListener { selectFrame(FrameType.SNOW) }
        binding.frameGoldBtn.setOnClickListener { selectFrame(FrameType.GOLD) }
        binding.frameBubbleBtn.setOnClickListener { selectFrame(FrameType.BUBBLE) }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showCameraUi() {
        binding.permissionText.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
    }

    private fun showPermissionDeniedUi() {
        binding.permissionText.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        Toast.makeText(this, R.string.permission_denied_msg, Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startCountdownAndCapture() {
        binding.shutterButton.isEnabled = false
        binding.countdownText.visibility = android.view.View.VISIBLE

        var secondsLeft = 3
        binding.countdownText.text = secondsLeft.toString()

        val tick = object : Runnable {
            override fun run() {
                secondsLeft -= 1
                if (secondsLeft > 0) {
                    binding.countdownText.text = secondsLeft.toString()
                    countdownHandler.postDelayed(this, 1000)
                } else {
                    binding.countdownText.visibility = android.view.View.GONE
                    binding.shutterButton.isEnabled = true
                    takePhoto()
                }
            }
        }
        countdownHandler.postDelayed(tick, 1000)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = applyBrightFilter(imageProxyToBitmap(image))
                    image.close()
                    runOnUiThread { showCapturedPhoto(bitmap) }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "촬영 실패: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = Matrix()
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f)
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun applyBrightFilter(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val brightness = 30f
        val contrast = 1.15f
        val saturation = 1.6f

        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val brightnessContrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val finalMatrix = ColorMatrix().apply {
            postConcat(saturationMatrix)
            postConcat(brightnessContrastMatrix)
        }

        paint.colorFilter = ColorMatrixColorFilter(finalMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun showCapturedPhoto(bitmap: Bitmap) {
        originalBitmap = bitmap
        selectedFrame = FrameType.NONE
        updateFramedPreview()

        binding.capturedImageView.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        binding.shutterButton.visibility = android.view.View.GONE
        binding.resultButtonRow.visibility = android.view.View.VISIBLE
        binding.frameSelectorScroll.visibility = android.view.View.VISIBLE
    }

    private fun returnToCameraMode() {
        lastCapturedBitmap = null
        originalBitmap = null
        binding.capturedImageView.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.shutterButton.visibility = android.view.View.VISIBLE
        binding.resultButtonRow.visibility = android.view.View.GONE
        binding.frameSelectorScroll.visibility = android.view.View.GONE
    }

    private fun selectFrame(frame: FrameType) {
        selectedFrame = frame
        updateFramedPreview()
    }

    private fun updateFramedPreview() {
        val base = originalBitmap ?: return
        val framed = applyFrame(base, selectedFrame)
        lastCapturedBitmap = framed
        binding.capturedImageView.setImageBitmap(framed)
    }

    private fun applyFrame(source: Bitmap, frame: FrameType): Bitmap {
        return when (frame) {
            FrameType.NONE -> source
            FrameType.WOOD -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.wood_medium),
                borderWidthRatio = 0.05f,
                cornerSymbol = null
            )
            FrameType.HEART -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.shutter_red),
                borderWidthRatio = 0.018f,
                cornerSymbol = "♥"
            )
            FrameType.STARS -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.wood_gold),
                borderWidthRatio = 0.018f,
                cornerSymbol = "★"
            )
            FrameType.FILMSTRIP -> drawFilmStripFrame(source)
            FrameType.POLAROID -> drawPolaroidFrame(source)
            FrameType.RAINBOW -> drawRainbowFrame(source)
            FrameType.SNOW -> drawSnowFrame(source)
            FrameType.GOLD -> drawGoldFrame(source)
            FrameType.BUBBLE -> drawBubbleFrame(source)
        }
    }

    private fun drawBorderFrame(
        source: Bitmap,
        borderColor: Int,
        borderWidthRatio: Float,
        cornerSymbol: String?
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val borderWidth = result.width * borderWidthRatio

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        val inset = borderWidth / 2f
        canvas.drawRect(inset, inset, result.width - inset, result.height - inset, borderPaint)

        if (cornerSymbol != null) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                textSize = result.width * 0.07f
                textAlign = Paint.Align.CENTER
            }
            val margin = result.width * 0.07f
            canvas.drawText(cornerSymbol, margin, margin, textPaint)
            canvas.drawText(cornerSymbol, result.width - margin, margin, textPaint)
            canvas.drawText(cornerSymbol, margin, result.height - margin / 2f, textPaint)
            canvas.drawText(cornerSymbol, result.width - margin, result.height - margin / 2f, textPaint)
        }
        return result
    }

    private fun drawFilmStripFrame(source: Bitmap): Bitmap {
        val barHeight = (source.height * 0.09f).toInt()
        val result = Bitmap.createBitmap(source.width, source.height + barHeight * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, 0f, barHeight.toFloat(), null)

        val holePaint = Paint().apply { color = Color.WHITE }
        val holeSize = barHeight * 0.5f
        val holeSpacing = source.width / 10f
        var x = holeSpacing / 2f
        while (x < source.width) {
            canvas.drawRect(x, barHeight * 0.25f, x + holeSize, barHeight * 0.75f, holePaint)
            canvas.drawRect(
                x,
                result.height - barHeight * 0.75f,
                x + holeSize,
                result.height - barHeight * 0.25f,
                holePaint
            )
            x += holeSpacing
        }
        return result
    }

    private fun drawPolaroidFrame(source: Bitmap): Bitmap {
        val margin = (source.width * 0.05f).toInt()
        val bottomMargin = (source.height * 0.18f).toInt()
        val result = Bitmap.createBitmap(
            source.width + margin * 2,
            source.height + margin + bottomMargin,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, margin.toFloat(), margin.toFloat(), null)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = bottomMargin * 0.4f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "♥ LOVE ♥",
            result.width / 2f,
            source.height + margin + bottomMargin * 0.6f,
            textPaint
        )
        return result
    }

    private fun drawRainbowFrame(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val borderWidth = result.width * 0.045f

        val rainbowColors = intArrayOf(
            Color.parseColor("#FF5252"), Color.parseColor("#FFB300"),
            Color.parseColor("#FFEE58"), Color.parseColor("#66BB6A"),
            Color.parseColor("#42A5F5"), Color.parseColor("#7E57C2"),
            Color.parseColor("#FF5252")
        )
        val shader = android.graphics.LinearGradient(
            0f, 0f, result.width.toFloat(), result.height.toFloat(),
            rainbowColors, null, android.graphics.Shader.TileMode.CLAMP
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            this.shader = shader
        }
        val inset = borderWidth / 2f
        canvas.drawRect(inset, inset, result.width - inset, result.height - inset, borderPaint)
        return result
    }

    private fun drawSnowFrame(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val borderWidth = result.width * 0.03f

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E3F2FD")
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        val inset = borderWidth / 2f
        canvas.drawRect(inset, inset, result.width - inset, result.height - inset, borderPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val random = java.util.Random(42)
        repeat(40) {
            val onTopBottom = random.nextBoolean()
            val x: Float
            val y: Float
            if (onTopBottom) {
                x = random.nextFloat() * result.width
                y = if (random.nextBoolean()) borderWidth else result.height - borderWidth
            } else {
                x = if (random.nextBoolean()) borderWidth else result.width - borderWidth
                y = random.nextFloat() * result.height
            }
            val radius = borderWidth * (0.08f + random.nextFloat() * 0.12f)
            canvas.drawCircle(x, y, radius, dotPaint)
        }
        return result
    }

    private fun drawGoldFrame(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val gold = ContextCompat.getColor(this, R.color.wood_gold)

        val outerWidth = result.width * 0.025f
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = Paint.Style.STROKE
            strokeWidth = outerWidth
        }
        val outerInset = outerWidth / 2f
        canvas.drawRect(outerInset, outerInset, result.width - outerInset, result.height - outerInset, outerPaint)

        val innerWidth = result.width * 0.008f
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = Paint.Style.STROKE
            strokeWidth = innerWidth
        }
        val innerInset = result.width * 0.045f
        canvas.drawRect(
            innerInset, innerInset,
            result.width - innerInset, result.height - innerInset,
            innerPaint
        )
        return result
    }

    private fun drawBubbleFrame(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val borderWidth = result.width * 0.02f

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        val inset = borderWidth / 2f
        canvas.drawRect(inset, inset, result.width - inset, result.height - inset, borderPaint)

        val pastelColors = intArrayOf(
            Color.parseColor("#FFCDD2"), Color.parseColor("#C8E6C9"),
            Color.parseColor("#BBDEFB"), Color.parseColor("#FFF9C4"),
            Color.parseColor("#E1BEE7")
        )
        val random = java.util.Random(7)
        repeat(18) {
            val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pastelColors[random.nextInt(pastelColors.size)]
                alpha = 220
            }
            val edge = random.nextInt(4)
            val x: Float
            val y: Float
            when (edge) {
                0 -> { x = random.nextFloat() * result.width; y = borderWidth }
                1 -> { x = random.nextFloat() * result.width; y = result.height - borderWidth }
                2 -> { x = borderWidth; y = random.nextFloat() * result.height }
                else -> { x = result.width - borderWidth; y = random.nextFloat() * result.height }
            }
            val radius = result.width * (0.012f + random.nextFloat() * 0.02f)
            canvas.drawCircle(x, y, radius, colorPaint)
        }
        return result
    }

    // 안드로이드 표준 인쇄 프레임워크로 전달.
    // 캐논 셀피가 Wi-Fi/Mopria로 이미 연결되어 있으면 인쇄 다이얼로그의
    // 프린터 목록에 자동으로 나타납니다.
    private fun printCapturedPhoto() {
        val bitmap = lastCapturedBitmap ?: return
        val printHelper = androidx.print.PrintHelper(this).apply {
            scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
            colorMode = androidx.print.PrintHelper.COLOR_MODE_COLOR
            orientation = androidx.print.PrintHelper.ORIENTATION_PORTRAIT
        }
        printHelper.printBitmap(getString(R.string.print_job_name), bitmap)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
