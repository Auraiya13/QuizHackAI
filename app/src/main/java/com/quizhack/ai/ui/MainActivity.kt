package com.quizhack.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quizhack.ai.R
import com.quizhack.ai.service.*
import com.quizhack.ai.utils.PrefsManager

/**
 * Main Activity - Setup and control center for Quiz Hack AI
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_AUDIO = 1003
        private const val REQUEST_NOTIFICATION = 1004
    }

    private lateinit var switchService: Switch
    private lateinit var switchVoice: Switch
    private lateinit var switchAI: Switch
    private lateinit var etApiKey: EditText
    private lateinit var btnStartOverlay: Button
    private lateinit var btnVoiceTest: Button
    private lateinit var btnScreenshot: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView

    private var voiceHandler: VoiceCommandHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PrefsManager.init(this)
        initViews()
        setupListeners()
        checkPermissions()
        updateStatus()
    }

    private fun initViews() {
        switchService = findViewById(R.id.switchService)
        switchVoice = findViewById(R.id.switchVoice)
        switchAI = findViewById(R.id.switchAI)
        etApiKey = findViewById(R.id.etApiKey)
        btnStartOverlay = findViewById(R.id.btnStartOverlay)
        btnVoiceTest = findViewById(R.id.btnVoiceTest)
        btnScreenshot = findViewById(R.id.btnScreenshot)
        tvStatus = findViewById(R.id.tvStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)

        switchAI.isChecked = PrefsManager.useAI
        switchVoice.isChecked = PrefsManager.voiceEnabled
        etApiKey.setText(PrefsManager.geminiApiKey)
    }

    private fun setupListeners() {
        switchService.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.isServiceEnabled = isChecked
            if (isChecked) {
                startQuizService()
            } else {
                stopQuizService()
            }
            updateStatus()
        }

        switchVoice.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.voiceEnabled = isChecked
            if (isChecked) {
                checkAudioPermission()
            }
        }

        switchAI.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.useAI = isChecked
            etApiKey.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                PrefsManager.geminiApiKey = s?.toString() ?: ""
            }
        })

        btnStartOverlay.setOnClickListener {
            if (FloatingOverlayService.instance != null) {
                stopQuizService()
            } else {
                if (checkOverlayPermission()) {
                    startQuizService()
                }
            }
        }

        btnVoiceTest.setOnClickListener {
            testVoiceCommand()
        }

        btnScreenshot.setOnClickListener {
            captureScreenshotAndSolve()
        }

        tvAccessibilityStatus.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<TextView>(R.id.tvApiKeyHelp)?.setOnClickListener {
            showApiKeyHelpDialog()
        }
    }

    private fun startQuizService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "कृपया पहले Accessibility Service चालू करें", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            switchService.isChecked = false
            return
        }

        if (!checkOverlayPermission()) {
            switchService.isChecked = false
            return
        }

        startOverlayService()
        updateStatus()
    }

    private fun stopQuizService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        }
        startService(intent)
        updateStatus()
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Quiz Hack चालू हो गया!", Toast.LENGTH_SHORT).show()
    }

    private fun testVoiceCommand() {
        if (voiceHandler == null) {
            voiceHandler = VoiceCommandHandler(this).apply {
                initialize()
                onQuestionRecognized = { question ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "🎤 $question", Toast.LENGTH_SHORT).show()
                        QuizAccessibilityService.instance?.processVoiceQuery(question)
                    }
                }
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Voice Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
                onListeningStateChanged = { listening ->
                    runOnUiThread {
                        btnVoiceTest.text = if (listening) "🎤 Listening..." else "🎤 Test Voice"
                    }
                }
            }
        }

        if (voiceHandler?.isCurrentlyListening() == true) {
            voiceHandler?.stopListening()
        } else {
            voiceHandler?.startListening("hi-IN")
        }
    }

    private fun captureScreenshotAndSolve() {
        val service = QuizAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "पहले Accessibility Service चालू करें", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (PrefsManager.geminiApiKey.isBlank() && PrefsManager.useAI) {
            AlertDialog.Builder(this)
                .setTitle("API Key Missing")
                .setMessage("सही जवाब के लिए Gemini API key जरूरी है. Settings mein key paste karo (free: aistudio.google.com). Bina key ke fallback search use hoga (kam accurate).")
                .setPositiveButton("Continue") { _, _ -> runScreenshot(service) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            runScreenshot(service)
        }
    }

    private fun runScreenshot(service: QuizAccessibilityService) {
        btnScreenshot.isEnabled = false
        btnScreenshot.text = "📸 Capturing..."
        service.captureAndSolveNow { question, answer, options, error ->
            runOnUiThread {
                btnScreenshot.isEnabled = true
                btnScreenshot.text = "📸 Screenshot & Solve"
                if (error != null) {
                    AlertDialog.Builder(this)
                        .setTitle("Result")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val msg = buildString {
                    append("Question: ${question?.questionText ?: ""}\n\n")
                    if (options.isNotEmpty()) append("Options:\n" + options.mapIndexed { i, o -> "${'A'+i}) $o" }.joinToString("\n") + "\n\n")
                    append("Answer: ${answer?.bestAnswer ?: "N/A"}\n")
                    append("Confidence: ${((answer?.confidence ?: 0f)*100).toInt()}%\n")
                    append("Source: ${answer?.source ?: ""}\n")
                    if (!answer?.explanation.isNullOrBlank()) append("\nExplanation: ${answer?.explanation}")
                }
                AlertDialog.Builder(this)
                    .setTitle("✅ Answer")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION
                )
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("फ्लोटिंग विंडो दिखाने के लिए 'Display over other apps' परमिशन चालू करें।")
                .setPositiveButton("Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_OVERLAY)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        return true
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${QuizAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service")
            .setMessage("Settings में जाकर:\n\n1. 'Installed Apps' में 'Quiz Hack AI' खोलें\n2. Service चालू (ON) करें\n\nयह स्क्रीन से सवाल पढ़ने के लिए आवश्यक है।")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showApiKeyHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key कैसे लें?")
            .setMessage("""
                1. aistudio.google.com खोलें
                2. 'Get API Key' पर क्लिक करें
                3. Key कॉपी करके यहाँ पेस्ट करें
                
                ⚡ यह मुफ़्त है और इससे जवाब सबसे सटीक आते हैं!
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (accessibilityEnabled) {
            "✅ Accessibility Service: चालू है"
        } else {
            "❌ Accessibility Service: बंद है (यहाँ टैप करें)"
        }

        val overlayRunning = FloatingOverlayService.instance != null
        if (overlayRunning) {
            tvStatus.text = "🟢 Active - फ्लोटिंग विंडो स्क्रीन पर है"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            btnStartOverlay.text = "🛑 Stop Overlay"
            btnStartOverlay.setBackgroundColor(Color.parseColor("#E53935"))
            switchService.isChecked = true
        } else {
            tvStatus.text = "🔴 Inactive"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            btnStartOverlay.text = "🎯 Start Overlay"
            btnStartOverlay.setBackgroundColor(Color.parseColor("#00C853"))
            switchService.isChecked = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHandler?.destroy()
    }
}
