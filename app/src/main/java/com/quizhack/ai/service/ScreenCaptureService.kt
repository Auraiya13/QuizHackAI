package com.quizhack.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.quizhack.ai.R
import com.quizhack.ai.search.AnswerEngine
import com.quizhack.ai.model.QuizQuestion
import com.quizhack.ai.utils.QuestionParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

/**
 * Screen Capture Service - Captures screenshots for OCR processing
 * Uses MediaProjection API as an alternative to Accessibility screenshot
 */
class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_CAPTURE = "com.quizhack.ai.CAPTURE"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1002

        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var answerEngine: AnswerEngine? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    override fun onCreate() {
        super.onCreate()
        instance = this
        answerEngine = AnswerEngine(applicationContext)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> captureScreen()
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    setupMediaProjection(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "QuizHackCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Log.d(TAG, "MediaProjection setup complete")
    }

    /**
     * Capture a single screenshot and process it
     */
    fun captureScreen() {
        serviceScope.launch {
            try {
                val image = imageReader?.acquireLatestImage() ?: return@launch

                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop to actual size
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                if (bitmap != croppedBitmap) bitmap.recycle()

                // Run OCR on screenshot
                processScreenshot(croppedBitmap)

            } catch (e: Exception) {
                Log.e(TAG, "Screenshot capture failed", e)
            }
        }
    }

    /**
     * Process screenshot with ML Kit OCR
     */
    private fun processScreenshot(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                if (extractedText.isNotBlank() && QuestionParser.isLikelyQuizQuestion(extractedText)) {
                    val (question, options) = QuestionParser.parse(extractedText)

                    val quizQuestion = QuizQuestion(
                        questionText = question,
                        options = options,
                        rawText = extractedText
                    )

                    serviceScope.launch {
                        // Also analyze image content
                        val imageDesc = answerEngine?.analyzeImage(bitmap)
                        val finalQuestion = if (imageDesc != null) {
                            quizQuestion.copy(hasImage = true, imageDescription = imageDesc)
                        } else {
                            quizQuestion
                        }

                        val answer = answerEngine?.findAnswer(finalQuestion)
                        if (answer != null) {
                            val intent = Intent(applicationContext, FloatingOverlayService::class.java).apply {
                                action = FloatingOverlayService.ACTION_SHOW_ANSWER
                                putExtra(FloatingOverlayService.EXTRA_QUESTION, finalQuestion.questionText)
                                putExtra(FloatingOverlayService.EXTRA_ANSWER, answer.bestAnswer)
                                putExtra(FloatingOverlayService.EXTRA_CONFIDENCE, answer.confidence)
                                putExtra(FloatingOverlayService.EXTRA_EXPLANATION, answer.explanation)
                            }
                            startService(intent)
                        }
                        bitmap.recycle()
                    }
                } else {
                    bitmap.recycle()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                bitmap.recycle()
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Capturing quiz questions...")
            .setSmallIcon(R.drawable.ic_brain)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        instance = null
    }
}
