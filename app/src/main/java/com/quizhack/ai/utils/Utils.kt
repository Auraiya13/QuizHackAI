package com.quizhack.ai.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app preferences and API keys
 */
object PrefsManager {
    private const val PREFS_NAME = "quiz_hack_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_USE_AI = "use_ai"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_TARGET_APPS = "target_apps"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var useAI: Boolean
        get() = prefs.getBoolean(KEY_USE_AI, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_AI, value).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    var targetApps: Set<String>
        get() = prefs.getStringSet(KEY_TARGET_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_TARGET_APPS, value).apply()
}

/**
 * Utility to parse quiz text into question and options
 */
object QuestionParser {

    // Common patterns for quiz options
    private val optionPatterns = listOf(
        Regex("""(?:^|\n)\s*([A-Da-d])[.):\s]+(.+)"""),           // A) Option or A. Option
        Regex("""(?:^|\n)\s*([1-4])[.):\s]+(.+)"""),                // 1) Option or 1. Option
        Regex("""(?:^|\n)\s*(?:Option\s*)?([A-Da-d])[.):\s]+(.+)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Parse raw screen text into question and options
     */
    fun parse(rawText: String): Pair<String, List<String>> {
        val lines = rawText.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Pair("", emptyList())

        val options = mutableListOf<String>()
        val questionLines = mutableListOf<String>()
        var foundOptions = false

        for (line in lines) {
            var matched = false
            for (pattern in optionPatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    options.add(match.groupValues[2].trim())
                    foundOptions = true
                    matched = true
                    break
                }
            }
            if (!matched && !foundOptions) {
                questionLines.add(line.trim())
            }
        }

        // If no structured options found, try splitting by common separators
        if (options.isEmpty() && lines.size >= 4) {
            // Assume first line(s) are question, rest are options
            val question = lines.first()
            val possibleOptions = lines.drop(1).takeLast(4)
            return Pair(question, possibleOptions.map { it.trim() })
        }

        val question = questionLines.joinToString(" ").trim()
        return Pair(question, options)
    }

    /**
     * Check if the text likely contains a quiz question
     */
    fun isLikelyQuizQuestion(text: String): Boolean {
        val hasQuestionMark = text.contains("?") || text.contains("?")
        val hasOptions = optionPatterns.any { it.containsMatchIn(text) }
        val hasMultipleLines = text.lines().filter { it.isNotBlank() }.size >= 3
        return (hasQuestionMark || hasOptions) && hasMultipleLines
    }
}
