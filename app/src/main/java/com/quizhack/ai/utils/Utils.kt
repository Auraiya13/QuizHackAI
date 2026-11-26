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
    private const val KEY_AUTO_SCAN = "auto_scan"

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

    // Default to manual trigger (false), so it doesn't annoy the user automatically
    var autoScan: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCAN, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCAN, value).apply()
}

/**
 * Utility to parse quiz text into question and options
 */
object QuestionParser {

    // Common noise words in Indian quiz apps to ignore
    private val noiseWords = listOf(
        "daily quiz", "quiz", "playing for", "powered by", "question:",
        "question", "submit", "next", "back", "score", "coins", "points",
        "lifeline", "time left", "timer", "50:50", "audience poll", "skip",
        "rank", "leaderboard", "share", "refer", "prize pool"
    )

    private val optionPatterns = listOf(
        Regex("""(?:^|\n)\s*([A-Da-d])[.):\s]+(.+)"""),
        Regex("""(?:^|\n)\s*([1-4])[.):\s]+(.+)"""),
        Regex("""(?:^|\n)\s*(?:Option\s*)?([A-Da-d])[.):\s]+(.+)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Clean and parse raw screen text into Question + Options
     */
    fun parse(rawText: String): Pair<String, List<String>> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() && !isPureNoise(line)
            }

        if (lines.isEmpty()) return Pair("", emptyList())

        val options = mutableListOf<String>()
        val questionLines = mutableListOf<String>()
        var foundOptions = false

        for (line in lines) {
            var matched = false
            for (pattern in optionPatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    val optText = match.groupValues[2].trim()
                    if (optText.isNotBlank() && !isNoiseOption(optText)) {
                        options.add(optText)
                        foundOptions = true
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                if (!foundOptions) {
                    // Lines before any options are likely part of the question
                    questionLines.add(line)
                } else if (options.size < 4 && !isNoiseOption(line)) {
                    // Line after options started, could be a continuing option
                    options.add(line)
                }
            }
        }

        // If no explicit A/B/C/D prefixes found, treat the last 2-4 lines as options
        if (options.isEmpty() && lines.size >= 3) {
            val validLines = lines.filter { !isNoiseOption(it) }
            if (validLines.size >= 3) {
                val optCount = when {
                    validLines.size >= 5 -> 4
                    validLines.size == 4 -> 3
                    else -> 2
                }
                val qParts = validLines.dropLast(optCount)
                val opts = validLines.takeLast(optCount)
                val qText = qParts.joinToString(" ").trim()
                return Pair(qText, opts)
            }
        }

        val question = questionLines.joinToString(" ").trim()
        return Pair(question, options.take(4))
    }

    private fun isPureNoise(line: String): Boolean {
        val lower = line.lowercase().trim()
        if (lower.matches(Regex("""^(question\s*\d+\s*/\s*\d+|\d+\s*/\s*\d+|q\s*\d+).*"""))) return true
        if (lower.matches(Regex("""^\d+s$"""))) return true // e.g. "10s"
        return noiseWords.any { lower == it }
    }

    private fun isNoiseOption(line: String): Boolean {
        val lower = line.lowercase().trim()
        return lower in listOf("submit", "next", "skip", "quit", "exit", "back")
    }

    fun isLikelyQuizQuestion(text: String): Boolean {
        val hasQuestionMark = text.contains("?") || text.contains("?")
        val hasOptions = optionPatterns.any { it.containsMatchIn(text) }
        val linesCount = text.lines().count { it.isNotBlank() }
        return (hasQuestionMark || hasOptions || linesCount >= 4)
    }
}
