package com.quizhack.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.quizhack.ai.model.QuizAnswer
import com.quizhack.ai.model.QuizQuestion
import com.quizhack.ai.search.AnswerEngine
import com.quizhack.ai.utils.PrefsManager
import com.quizhack.ai.utils.QuestionParser
import kotlinx.coroutines.*

/**
 * Core Accessibility Service - Captures quiz questions on screen
 * Inspired by SubhamTyagi/loco-answers (Trivia Hack)
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
    private var answerEngine: AnswerEngine? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        answerEngine = AnswerEngine(applicationContext)
        Log.d(TAG, "QuizAccessibilityService created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only auto-process if user explicitly turned on autoScan
        if (event == null || !PrefsManager.isServiceEnabled || !PrefsManager.autoScan) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Auto scan logic if enabled
            }
        }
    }

    /**
     * Manual On-Demand Trigger: called when user taps "GET ANSWER" on the floating overlay
     */
    fun captureAndSolveNow(callback: (QuizQuestion?, QuizAnswer?, List<String>, String?) -> Unit) {
        serviceScope.launch {
            try {
                // 1. Try extracting text from active window hierarchy
                val rootNode = rootInActiveWindow
                var screenText = ""
                if (rootNode != null) {
                    screenText = extractTextFromNode(rootNode)
                }

                val (questionText, options) = if (screenText.isNotBlank()) {
                    QuestionParser.parse(screenText)
                } else {
                    Pair("", emptyList())
                }

                // If text hierarchy found valid question & at least 2 options, solve immediately!
                if (questionText.isNotBlank() && options.size >= 2) {
                    val question = QuizQuestion(
                        questionText = questionText,
                        options = options,
                        rawText = screenText
                    )
                    solveQuestion(question, callback)
                    return@launch
                }

                // 2. If node text was insufficient, fallback to Screen OCR (Android 11+)
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
                                    runOcrOnBitmap(bitmap, callback)
                                } else {
                                    callback(null, null, emptyList(), "स्क्रीन से इमेज नहीं मिली!")
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "Screenshot failed: $errorCode")
                                // If OCR fails, try with whatever text we had
                                if (questionText.isNotBlank()) {
                                    solveQuestion(QuizQuestion(questionText, options, rawText = screenText), callback)
                                } else {
                                    callback(null, null, emptyList(), "स्क्रीन पर कोई सवाल नहीं दिखा!")
                                }
                            }
                        }
                    )
                } else {
                    // For Android < 11
                    if (questionText.isNotBlank()) {
                        solveQuestion(QuizQuestion(questionText, options, rawText = screenText), callback)
                    } else {
                        callback(null, null, emptyList(), "कृपया सवाल स्क्रीन पर आने पर टैप करें!")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureAndSolveNow error", e)
                callback(null, null, emptyList(), "एरर: ${e.message}")
            }
        }
    }

    /**
     * Run ML Kit OCR on screenshot bitmap
     */
    private fun runOcrOnBitmap(
        bitmap: Bitmap,
        callback: (QuizQuestion?, QuizAnswer?, List<String>, String?) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val ocrText = visionText.text
                val (qText, opts) = QuestionParser.parse(ocrText)

                if (qText.isNotBlank()) {
                    val question = QuizQuestion(
                        questionText = qText,
                        options = opts,
                        rawText = ocrText
                    )
                    serviceScope.launch {
                        solveQuestion(question, callback)
                        bitmap.recycle()
                    }
                } else {
                    callback(null, null, emptyList(), "स्क्रीन पर सवाल नहीं मिला!")
                    bitmap.recycle()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                callback(null, null, emptyList(), "OCR फेल हो गया!")
                bitmap.recycle()
            }
    }

    /**
     * Solve the parsed question using AnswerEngine
     */
    private suspend fun solveQuestion(
        question: QuizQuestion,
        callback: (QuizQuestion?, QuizAnswer?, List<String>, String?) -> Unit
    ) {
        try {
            val answer = answerEngine?.findAnswer(question)
            if (answer != null) {
                callback(question, answer, question.options, null)
            } else {
                callback(question, null, question.options, "जवाब नहीं मिला!")
            }
        } catch (e: Exception) {
            callback(question, null, question.options, "सर्च एरर: ${e.message}")
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

        node.text?.let {
            if (it.isNotBlank()) {
                sb.appendLine(it)
            }
        }

        node.contentDescription?.let {
            if (it.isNotBlank() && node.text.isNullOrBlank()) {
                sb.appendLine(it)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractTextRecursive(child, sb)
            child?.recycle()
        }
    }

    /**
     * Voice query support
     */
    fun processVoiceQuery(query: String) {
        val (q, opts) = QuestionParser.parse(query)
        val question = QuizQuestion(
            questionText = if (q.isNotBlank()) q else query,
            options = opts,
            rawText = query
        )
        serviceScope.launch {
            val answer = answerEngine?.findAnswer(question)
            if (answer != null) {
                FloatingOverlayService.instance?.displayAnswer(
                    question.questionText,
                    answer.bestAnswer,
                    answer.confidence,
                    question.options
                )
            }
        }
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
