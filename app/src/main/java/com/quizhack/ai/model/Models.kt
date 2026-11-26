package com.quizhack.ai.model

/**
 * Represents a parsed quiz question with its options and any associated image
 */
data class QuizQuestion(
    val questionText: String,
    val options: List<String>,
    val hasImage: Boolean = false,
    val imageDescription: String? = null,
    val rawText: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toSearchQuery(): String {
        val base = questionText.replace(Regex("[?।?]"), "").trim()
        return if (hasImage && imageDescription != null) {
            "$base $imageDescription"
        } else {
            base
        }
    }
}

/**
 * Represents the answer found by the search engine
 */
data class QuizAnswer(
    val bestAnswer: String,
    val confidence: Float, // 0.0 to 1.0
    val optionScores: Map<String, Float>,
    val source: String,
    val explanation: String = ""
)

/**
 * App configuration
 */
data class AppConfig(
    var isServiceEnabled: Boolean = false,
    var searchEngine: SearchEngineType = SearchEngineType.GOOGLE,
    var useAI: Boolean = true,
    var geminiApiKey: String = "",
    var showOverlay: Boolean = true,
    var voiceCommandEnabled: Boolean = false,
    var targetApps: MutableList<String> = mutableListOf()
)

enum class SearchEngineType {
    GOOGLE, WIKIPEDIA, AI_GEMINI
}
