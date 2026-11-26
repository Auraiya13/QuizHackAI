package com.quizhack.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import com.quizhack.ai.R
import com.quizhack.ai.ui.MainActivity

/**
 * Floating Overlay Service - Shows quiz answers on top of any app
 * Draggable, expandable/collapsible bubble UI
 */
class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_SHOW_ANSWER = "com.quizhack.ai.SHOW_ANSWER"
        const val ACTION_STOP = "com.quizhack.ai.STOP_OVERLAY"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_EXPLANATION = "explanation"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_OPTION_SCORES = "option_scores"

        private const val CHANNEL_ID = "quiz_hack_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_ANSWER -> {
                val question = intent.getStringExtra(EXTRA_QUESTION) ?: ""
                val answer = intent.getStringExtra(EXTRA_ANSWER) ?: ""
                val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
                val explanation = intent.getStringExtra(EXTRA_EXPLANATION) ?: ""
                val options = intent.getStringArrayListExtra(EXTRA_OPTIONS) ?: arrayListOf()
                val optionScores = intent.getStringArrayListExtra(EXTRA_OPTION_SCORES) ?: arrayListOf()

                showAnswer(question, answer, confidence, explanation, options, optionScores)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Create the floating bubble (collapsed state)
     */
    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)

        val params = WindowManager.LayoutParams(
            dpToPx(56),
            dpToPx(56),
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
            x = 50
            y = 200
        }

        // Make bubble draggable
        setupDragListener(bubbleView!!, params)

        // Tap to expand
        bubbleView?.setOnClickListener {
            if (!isExpanded) {
                showExpandedView()
            }
        }

        windowManager.addView(bubbleView, params)

        // Animate entry
        bubbleView?.scaleX = 0f
        bubbleView?.scaleY = 0f
        bubbleView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(300)
            ?.setInterpolator(OvershootInterpolator())
            ?.start()
    }

    /**
     * Show expanded answer card
     */
    private fun showExpandedView() {
        if (expandedView != null) return

        expandedView = LayoutInflater.from(this).inflate(R.layout.layout_answer_card, null)

        val params = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Close button
        expandedView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            hideExpandedView()
        }

        windowManager.addView(expandedView, params)
        isExpanded = true

        // Animate in
        expandedView?.alpha = 0f
        expandedView?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    /**
     * Hide expanded view
     */
    private fun hideExpandedView() {
        expandedView?.let {
            it.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    try {
                        windowManager.removeView(it)
                    } catch (_: Exception) {}
                    expandedView = null
                    isExpanded = false
                }
                .start()
        }
    }

    /**
     * Display the answer in the expanded card
     */
    private fun showAnswer(
        question: String,
        answer: String,
        confidence: Float,
        explanation: String,
        options: List<String>,
        optionScores: List<String>
    ) {
        // Auto-expand if collapsed
        if (!isExpanded) {
            showExpandedView()
        }

        // Pulse the bubble to indicate new answer
        bubbleView?.animate()
            ?.scaleX(1.3f)?.scaleY(1.3f)
            ?.setDuration(150)
            ?.withEndAction {
                bubbleView?.animate()
                    ?.scaleX(1f)?.scaleY(1f)
                    ?.setDuration(150)
                    ?.start()
            }?.start()

        expandedView?.let { card ->
            // Set question text
            card.findViewById<TextView>(R.id.tvQuestion)?.text = question

            // Set answer
            card.findViewById<TextView>(R.id.tvAnswer)?.apply {
                text = "✅ $answer"
                setTextColor(Color.parseColor("#00C853"))
            }

            // Set confidence
            val confidencePercent = (confidence * 100).toInt()
            card.findViewById<TextView>(R.id.tvConfidence)?.apply {
                text = "Confidence: $confidencePercent%"
                setTextColor(when {
                    confidencePercent >= 80 -> Color.parseColor("#00C853")
                    confidencePercent >= 50 -> Color.parseColor("#FFD600")
                    else -> Color.parseColor("#FF5252")
                })
            }

            card.findViewById<ProgressBar>(R.id.progressConfidence)?.progress = confidencePercent

            // Set explanation
            card.findViewById<TextView>(R.id.tvExplanation)?.apply {
                text = explanation
                visibility = if (explanation.isNotBlank()) View.VISIBLE else View.GONE
            }

            // Show options with scores
            val optionsContainer = card.findViewById<LinearLayout>(R.id.optionsContainer)
            optionsContainer?.removeAllViews()

            val scoreMap = mutableMapOf<String, Float>()
            optionScores.forEach { scoreStr ->
                val parts = scoreStr.split(":")
                if (parts.size == 2) {
                    scoreMap[parts[0]] = parts[1].toFloatOrNull() ?: 0f
                }
            }

            options.forEach { option ->
                val optionView = TextView(this).apply {
                    text = option
                    textSize = 14f
                    setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))

                    val score = scoreMap[option] ?: 0f
                    if (option.equals(answer, ignoreCase = true) || score > 0.5f) {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#2E7D32"))
                    } else {
                        setTextColor(Color.parseColor("#CCCCCC"))
                        setBackgroundColor(Color.parseColor("#2A2A2A"))
                    }

                    val marginParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(4)
                    }
                    layoutParams = marginParams
                }
                optionsContainer?.addView(optionView)
            }
        }
    }

    /**
     * Setup drag listener for moving the bubble
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) isDragging = true

                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
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
                "Quiz Hack AI Overlay",
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
            .setContentText("Monitoring for quiz questions...")
            .setSmallIcon(R.drawable.ic_brain)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_close, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            bubbleView?.let { windowManager.removeView(it) }
            expandedView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
    }
}
