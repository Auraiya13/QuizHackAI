package com.quizhack.ai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
        private const val REQUEST_MEDIA_PROJECTION = 1002
        private const val REQUEST_AUDIO = 1003
        private const val REQUEST_NOTIFICATION = 1004
    }

    private lateinit var switchService: Switch
    private lateinit var switchVoice: Switch
    private lateinit var switchAI: Switch
    private lateinit var etApiKey: EditText
    private lateinit var btnStartOverlay: Button
    private lateinit var btnVoiceTest: Button
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
        tvStatus = findViewById(R.id.tvStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)

        // Load saved values
        switchAI.isChecked = PrefsManager.useAI
        switchVoice.isChecked = PrefsManager.voiceEnabled
        etApiKey.setText(PrefsManager.geminiApiKey)
    }

    private fun setupListeners() {
        // Service toggle
        switchService.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.isServiceEnabled = isChecked
            if (isChecked) {
                startQuizService()
            } else {
                stopQuizService()
            }
            updateStatus()
        }

        // Voice toggle
        switchVoice.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.voiceEnabled = isChecked
            if (isChecked) {
                checkAudioPermission()
            }
        }

        // AI toggle
        switchAI.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.useAI = isChecked
            etApiKey.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // API key
        etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                PrefsManager.geminiApiKey = s?.toString() ?: ""
            }
        })

        // Start overlay button
        btnStartOverlay.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayService()
            }
        }

        // Voice test button
        btnVoiceTest.setOnClickListener {
            testVoiceCommand()
        }

        // Accessibility settings button
        tvAccessibilityStatus.setOnClickListener {
            openAccessibilitySettings()
        }

        // API Key help
        findViewById<TextView>(R.id.tvApiKeyHelp)?.setOnClickListener {
            showApiKeyHelpDialog()
        }
    }

    private fun startQuizService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "कृपया पहले Accessibility Service enable करें", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            switchService.isChecked = false
            return
        }

        if (!checkOverlayPermission()) {
            switchService.isChecked = false
            return
        }

        startOverlayService()
        tvStatus.text = "🟢 Active - Quiz questions monitoring..."
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
    }

    private fun stopQuizService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        }
        startService(intent)
        tvStatus.text = "🔴 Inactive"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun testVoiceCommand() {
        if (voiceHandler == null) {
            voiceHandler = VoiceCommandHandler(this).apply {
                initialize()
                onQuestionRecognized = { question ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "🎤 $question", Toast.LENGTH_SHORT).show()
                        // Send to accessibility service for processing
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

    // --- Permissions ---

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
                .setMessage("Quiz answers को दूसरी apps के ऊपर दिखाने के लिए overlay permission चाहिए")
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
            .setTitle("Accessibility Service Setup")
            .setMessage("Settings में जाकर:\n\n1. 'Quiz Hack AI' ढूँढें\n2. Toggle ON करें\n3. Permission Allow करें\n\nयह quiz questions को automatically पढ़ने के लिए ज़रूरी है।")
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
                1. Google AI Studio पर जाएँ:
                   aistudio.google.com
                   
                2. 'Get API Key' पर click करें
                
                3. 'Create API Key' पर click करें
                
                4. Key copy करके यहाँ paste करें
                
                ⚡ Free tier में काफी requests मिलते हैं!
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
            "✅ Accessibility Service: Enabled"
        } else {
            "❌ Accessibility Service: Disabled (Tap to enable)"
        }

        val serviceRunning = QuizAccessibilityService.isRunning && PrefsManager.isServiceEnabled
        tvStatus.text = if (serviceRunning) {
            "🟢 Active - Monitoring quiz questions"
        } else {
            "🔴 Inactive"
        }

        switchService.isChecked = serviceRunning
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHandler?.destroy()
    }
}
