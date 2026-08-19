bash

cat /home/claude/WoodPrintCam/app/src/main/java/com/example/woodprintcam/MainActivity.kt
출력

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
    private var lastCapturedBitmap: Bitmap? = null   // 프레임+필터까지 적용된, 실제 인쇄될 이미지
    private var originalBitmap: Bitmap? = null        // 필터 적용된 상태(프레임 적용 전) 이미지
    private var rawCapturedBitmap: Bitmap? = null      // 필터 적용 전 순수 원본 (회전 보정만 된 상태)
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedFrame = FrameType.NONE
    private var selectedFilterLevel = 2 // 1: 약하게, 2: 보통(기본), 3: 화사하게

    // 카메라 권한 요청 런처.
    // 사용자가 한 번 "허용"을 누르면 안드로이드 시스템이 앱 재실행 시에도 계속 허용 상태를
    // 자동으로 유지해줍니다. 따라서 매번 요청할 필요 없이, 시작할 때 이미 허용됐는지만
    // 확인(hasCameraPermission)하고, 허용 안 됐을 때만 이 런처를 호출합니다.
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
            // 최초 1회만 요청. 이후에는 hasCameraPermission()이 true를 반환하므로
            // 이 분기를 다시 타지 않습니다.
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

        binding.filterLevel1Btn.setOnClickListener { selectFilterLevel(1) }
        binding.filterLevel2Btn.setOnClickListener { selectFilterLevel(2) }
        binding.filterLevel3Btn.setOnClickListener { selectFilterLevel(3) }
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

    // 전면 <-> 후면 카메라 전환
    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    // 셔터 버튼을 누르면 화면에 3, 2, 1 숫자가 보이다가 자동으로 촬영됩니다.
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
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    runOnUiThread {
                        rawCapturedBitmap = bitmap
                        showCapturedPhoto()
                    }
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

    // ImageProxy(YUV/JPEG) -> Bitmap 변환 + 회전 보정 + 전면 카메라 좌우 반전 보정
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
        // 전면 카메라는 화면에 거울처럼 보이므로, 저장되는 사진도
        // 화면에서 본 것과 같게 좌우를 뒤집어줍니다.
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f)
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    // 밝고 화사한 느낌의 필터 (밝기 업 + 채도 업 + 살짝 선명하게)
    // level 1: 약하게 (밝은 곳에서 촬영할 때 추천), 2: 보통, 3: 화사하게
    private fun applyBrightFilter(source: Bitmap, level: Int): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val brightness: Float
        val contrast: Float
        val saturation: Float
        when (level) {
            1 -> { brightness = 8f;  contrast = 1.03f; saturation = 1.15f }
            3 -> { brightness = 30f; contrast = 1.15f; saturation = 1.6f }
            else -> { brightness = 18f; contrast = 1.08f; saturation = 1.35f }
        }

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

    private fun showCapturedPhoto() {
        selectedFrame = FrameType.NONE
        selectedFilterLevel = 2
        applyFilterAndRefresh()
        updateFilterButtonHighlight()

        binding.capturedImageView.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        binding.shutterButton.visibility = android.view.View.GONE
        binding.resultButtonRow.visibility = android.view.View.VISIBLE
        binding.filterLevelRow.visibility = android.view.View.VISIBLE
        binding.frameSelectorScroll.visibility = android.view.View.VISIBLE
    }

    private fun returnToCameraMode() {
        lastCapturedBitmap = null
        originalBitmap = null
        rawCapturedBitmap = null
        binding.capturedImageView.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.shutterButton.visibility = android.view.View.VISIBLE
        binding.resultButtonRow.visibility = android.view.View.GONE
        binding.filterLevelRow.visibility = android.view.View.GONE
        binding.frameSelectorScroll.visibility = android.view.View.GONE
    }

    // 필터 강도(1/2/3) 선택 시 호출 - 원본 사진에 새 강도로 다시 필터를 입힙니다.
    private fun selectFilterLevel(level: Int) {
        selectedFilterLevel = level
        applyFilterAndRefresh()
        updateFilterButtonHighlight()
    }

    // 순수 원본(rawCapturedBitmap)에 현재 선택된 필터 강도를 적용하고,
    // 그 위에 현재 선택된 프레임까지 다시 그려서 화면을 갱신합니다.
    private fun applyFilterAndRefresh() {
        val raw = rawCapturedBitmap ?: return
        originalBitmap = applyBrightFilter(raw, selectedFilterLevel)
        updateFramedPreview()
    }

    private fun updateFilterButtonHighlight() {
        val selectedBg = R.drawable.bg_frame_chip_selected
        val normalBg = R.drawable.bg_frame_chip
        val selectedTextColor = ContextCompat.getColor(this, R.color.wood_dark)
        val normalTextColor = ContextCompat.getColor(this, R.color.wood_cream)

        val buttons = listOf(
            1 to binding.filterLevel1Btn,
            2 to binding.filterLevel2Btn,
            3 to binding.filterLevel3Btn
        )
        for ((level, button) in buttons) {
            val isSelected = level == selectedFilterLevel
            button.setBackgroundResource(if (isSelected) selectedBg else normalBg)
            button.setTextColor(if (isSelected) selectedTextColor else normalTextColor)
        }
    }

    // 프레임 선택 시 호출 - 원본에 새로 선택한 프레임을 적용해서 미리보기 갱신
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

    // 프레임 종류에 따라 알맞은 함수로 분기
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

    // 단순 테두리 + (옵션) 네 모서리 장식 문자
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

    // 필름 스트립: 위/아래 검은 띠 + 하얀 사각형 구멍 장식
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

    // 폴라로이드: 하얀 여백 + 하단 캡션 문구
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

    // 무지개 그라데이션 테두리
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

    // 하얀 눈꽃이 흩날리는 느낌의 겨울 프레임
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
        val random = java.util.Random(42) // 고정 시드로 매번 같은 패턴
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

    // 고급스러운 이중 골드 라인 테두리
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

    // 파스텔톤 버블(동그라미)이 모서리에 흩뿌려진 귀여운 프레임
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

