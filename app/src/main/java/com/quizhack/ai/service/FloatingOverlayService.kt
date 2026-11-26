package com.quizhack.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.quizhack.ai.R
import com.quizhack.ai.ui.MainActivity

/**
 * Floating Overlay Service - Shubham Tyagi / Trivia Hack Style Slim Side Window
 * - Shows Option A, B, C, D in a slim vertical card
 * - Red [ GET ANSWER ] button at the bottom
 * - Long-press on [ GET ANSWER ] or tap (✕) instantly closes the service
 */
class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_SHOW_ANSWER = "com.quizhack.ai.SHOW_ANSWER"
        const val ACTION_STOP = "com.quizhack.ai.STOP_OVERLAY"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_OPTIONS = "options"

        private const val CHANNEL_ID = "quiz_hack_overlay"
        private const val NOTIFICATION_ID = 1001

        var instance: FloatingOverlayService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var widgetView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tvQuestionPreview: TextView? = null
    private var tvOptionA: TextView? = null
    private var tvOptionB: TextView? = null
    private var tvOptionC: TextView? = null
    private var tvOptionD: TextView? = null
    private var btnGetAnswer: Button? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        createSlimWidgetView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_ANSWER -> {
                val question = intent.getStringExtra(EXTRA_QUESTION) ?: ""
                val answer = intent.getStringExtra(EXTRA_ANSWER) ?: ""
                val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
                val options = intent.getStringArrayListExtra(EXTRA_OPTIONS) ?: arrayListOf()
                displayAnswer(question, answer, confidence, options)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Create the slim side widget (Identical to Shubham Tyagi's app)
     */
    private fun createSlimWidgetView() {
        widgetView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        tvQuestionPreview = widgetView?.findViewById(R.id.tvQuestionPreview)
        tvOptionA = widgetView?.findViewById(R.id.tvOptionA)
        tvOptionB = widgetView?.findViewById(R.id.tvOptionB)
        tvOptionC = widgetView?.findViewById(R.id.tvOptionC)
        tvOptionD = widgetView?.findViewById(R.id.tvOptionD)
        btnGetAnswer = widgetView?.findViewById(R.id.btnGetAnswer)

        val params = WindowManager.LayoutParams(
            dpToPx(175),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 220
        }

        // Close (✕) button
        widgetView?.findViewById<TextView>(R.id.btnWidgetClose)?.setOnClickListener {
            Toast.makeText(this, "Quiz Hack बंद हो गया", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        // Long click on header to close
        widgetView?.findViewById<View>(R.id.headerLayout)?.setOnLongClickListener {
            Toast.makeText(this, "Quiz Hack बंद हो गया", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }

        // Drag listener on the entire card/header
        setupDragListener(widgetView!!, params)

        // Single tap on [ GET ANSWER ] -> Fetch answer right now!
        btnGetAnswer?.setOnClickListener {
            onGetAnswerClicked()
        }

        // Long press on [ GET ANSWER ] -> Instantly delete/close overlay!
        btnGetAnswer?.setOnLongClickListener {
            Toast.makeText(this, "Quiz Hack बंद हो गया", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }

        windowManager.addView(widgetView, params)
    }

    /**
     * User pressed "GET ANSWER"
     */
    private fun onGetAnswerClicked() {
        btnGetAnswer?.apply {
            text = "⏳ SEARCHING..."
            setBackgroundColor(Color.parseColor("#FB8C00")) // Orange
            isEnabled = false
        }
        tvQuestionPreview?.text = "Reading question on screen..."

        val accessibility = QuizAccessibilityService.instance
        if (accessibility != null && QuizAccessibilityService.isRunning) {
            accessibility.captureAndSolveNow { question, answer, options, error ->
                mainHandler.post {
                    btnGetAnswer?.isEnabled = true
                    if (error != null || question == null || answer == null) {
                        btnGetAnswer?.apply {
                            text = "TRY AGAIN"
                            setBackgroundColor(Color.parseColor("#E53935"))
                        }
                        tvQuestionPreview?.text = error ?: "कोई सवाल नहीं मिला!"
                    } else {
                        displayAnswer(question.questionText, answer.bestAnswer, answer.confidence, options)
                    }
                }
            }
        } else {
            btnGetAnswer?.isEnabled = true
            btnGetAnswer?.apply {
                text = "GET ANSWER"
                setBackgroundColor(Color.parseColor("#E53935"))
            }
            Toast.makeText(this, "Accessibility Service चालू नहीं है!", Toast.LENGTH_SHORT).show()
            tvQuestionPreview?.text = "Accessibility Service चालू करें!"
        }
    }

    /**
     * Display answer on the A, B, C, D slots and highlight the correct one in Green!
     */
    fun displayAnswer(question: String, answer: String, confidence: Float, options: List<String>) {
        mainHandler.post {
            tvQuestionPreview?.text = if (question.isNotBlank()) question else "Answer found:"

            val optionViews = listOf(tvOptionA, tvOptionB, tvOptionC, tvOptionD)
            val labels = listOf("A", "B", "C", "D")

            var winningLabel = ""

            for (i in 0..3) {
                val optView = optionViews[i] ?: continue
                val label = labels[i]

                if (i < options.size) {
                    val optText = options[i]
                    optView.visibility = View.VISIBLE
                    optView.text = "$label: $optText"

                    // Check if this option matches the answer
                    val isMatch = optText.equals(answer, ignoreCase = true) ||
                            answer.contains(optText, ignoreCase = true) ||
                            optText.contains(answer, ignoreCase = true)

                    if (isMatch && winningLabel.isEmpty()) {
                        winningLabel = label
                        optView.setBackgroundResource(R.drawable.option_item_correct_bg)
                        optView.setTextColor(Color.WHITE)
                    } else {
                        optView.setBackgroundResource(R.drawable.option_item_bg)
                        optView.setTextColor(Color.parseColor("#212529"))
                    }
                } else {
                    optView.visibility = View.GONE
                }
            }

            // Update GET ANSWER button with the result!
            btnGetAnswer?.apply {
                val confPercent = (confidence * 100).toInt()
                text = if (winningLabel.isNotEmpty()) {
                    "✅ OPTION $winningLabel ($confPercent%)"
                } else {
                    "✅ $answer"
                }
                setBackgroundColor(Color.parseColor("#2E7D32")) // Green!
            }

            // Auto-reset button back to red [ GET ANSWER ] after 6 seconds
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                btnGetAnswer?.apply {
                    text = "GET ANSWER"
                    setBackgroundColor(Color.parseColor("#E53935"))
                }
            }, 6000)
        }
    }

    /**
     * Dragging support for moving the widget anywhere on screen
     */
    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // Don't consume so click works
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quiz Hack AI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows quiz answers overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quiz Hack AI Active")
            .setContentText("Tap GET ANSWER to solve any quiz")
            .setSmallIcon(R.drawable.ic_brain)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_close, "बंद करें (Stop)", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            widgetView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
    }
}
