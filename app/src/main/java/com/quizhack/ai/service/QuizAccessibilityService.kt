package com.quizhack.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.quizhack.ai.model.QuizQuestion
import com.quizhack.ai.search.AnswerEngine
import com.quizhack.ai.utils.PrefsManager
import com.quizhack.ai.utils.QuestionParser
import kotlinx.coroutines.*

/**
 * Core Accessibility Service - Captures quiz questions from any app on screen
 * Inspired by SubhamTyagi/loco-answers but modernized with AI
 */
class QuizAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "QuizAccessibility"
        var instance: QuizAccessibilityService? = null
            private set
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastProcessedText = ""
    private var lastProcessedTime = 0L
    private val debounceMs = 1500L // Avoid processing same question repeatedly
    private var answerEngine: AnswerEngine? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        answerEngine = AnswerEngine(applicationContext)
        Log.d(TAG, "QuizAccessibilityService created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PrefsManager.isServiceEnabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                processScreen(event)
            }
        }
    }

    /**
     * Process screen content to find quiz questions
     */
    private fun processScreen(event: AccessibilityEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < debounceMs) return

        val rootNode = rootInActiveWindow ?: return

        // Check if current app is a target quiz app (or process all if no targets set)
        val packageName = event.packageName?.toString() ?: return
        val targetApps = PrefsManager.targetApps
        if (targetApps.isNotEmpty() && packageName !in targetApps) return

        // Extract all text from screen
        val screenText = extractTextFromNode(rootNode)
        if (screenText.isBlank() || screenText == lastProcessedText) return

        // Check if this looks like a quiz question
        if (!QuestionParser.isLikelyQuizQuestion(screenText)) return

        lastProcessedText = screenText
        lastProcessedTime = currentTime

        Log.d(TAG, "Detected quiz question: ${screenText.take(100)}...")

        // Parse question and options
        val (questionText, options) = QuestionParser.parse(screenText)
        if (questionText.isBlank()) return

        val quizQuestion = QuizQuestion(
            questionText = questionText,
            options = options,
            rawText = screenText
        )

        // Try to capture screenshot for image-based questions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshotForQuestion(quizQuestion)
        } else {
            processQuestion(quizQuestion)
        }
    }

    /**
     * Take screenshot to detect image-based questions (Android 11+)
     */
    private fun takeScreenshotForQuestion(question: QuizQuestion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        screenshot.hardwareBuffer.close()

                        if (bitmap != null) {
                            // Analyze image content for image-based questions
                            serviceScope.launch {
                                val updatedQuestion = analyzeScreenshotForImages(bitmap, question)
                                processQuestion(updatedQuestion)
                                bitmap.recycle()
                            }
                        } else {
                            processQuestion(question)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed: $errorCode")
                        processQuestion(question)
                    }
                }
            )
        }
    }

    /**
     * Analyze screenshot to detect and describe images in the question
     */
    private suspend fun analyzeScreenshotForImages(
        bitmap: Bitmap,
        question: QuizQuestion
    ): QuizQuestion = withContext(Dispatchers.Default) {
        try {
            val imageAnalysis = answerEngine?.analyzeImage(bitmap)
            if (imageAnalysis != null && imageAnalysis.isNotBlank()) {
                question.copy(
                    hasImage = true,
                    imageDescription = imageAnalysis
                )
            } else {
                question
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            question
        }
    }

    /**
     * Process the question - find answer and show overlay
     */
    private fun processQuestion(question: QuizQuestion) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Processing: ${question.questionText}")

                val answer = answerEngine?.findAnswer(question)
                if (answer != null) {
                    // Send answer to floating overlay
                    val intent = Intent(applicationContext, FloatingOverlayService::class.java).apply {
                        action = FloatingOverlayService.ACTION_SHOW_ANSWER
                        putExtra(FloatingOverlayService.EXTRA_QUESTION, question.questionText)
                        putExtra(FloatingOverlayService.EXTRA_ANSWER, answer.bestAnswer)
                        putExtra(FloatingOverlayService.EXTRA_CONFIDENCE, answer.confidence)
                        putExtra(FloatingOverlayService.EXTRA_EXPLANATION, answer.explanation)
                        putStringArrayListExtra(
                            FloatingOverlayService.EXTRA_OPTIONS,
                            ArrayList(question.options)
                        )
                        putStringArrayListExtra(
                            FloatingOverlayService.EXTRA_OPTION_SCORES,
                            ArrayList(question.options.map { opt ->
                                "${opt}:${answer.optionScores[opt] ?: 0f}"
                            })
                        )
                    }
                    startService(intent)
                    Log.d(TAG, "Answer found: ${answer.bestAnswer} (${answer.confidence})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing question", e)
            }
        }
    }

    /**
     * Recursively extract all text from accessibility node tree
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        extractTextRecursive(node, sb)
        return sb.toString().trim()
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return

        // Get text content
        node.text?.let {
            if (it.isNotBlank()) {
                sb.appendLine(it)
            }
        }

        // Also check content description (for image descriptions)
        node.contentDescription?.let {
            if (it.isNotBlank() && node.text.isNullOrBlank()) {
                sb.appendLine(it)
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractTextRecursive(child, sb)
            child?.recycle()
        }
    }

    /**
     * Public method for voice command triggered search
     */
    fun processVoiceQuery(query: String) {
        val question = QuizQuestion(
            questionText = query,
            options = emptyList(),
            rawText = query
        )
        processQuestion(question)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
        isRunning = false
        Log.d(TAG, "Service destroyed")
    }
}
