package com.quizhack.ai.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.quizhack.ai.model.QuizAnswer
import com.quizhack.ai.model.QuizQuestion
import com.quizhack.ai.utils.PrefsManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * AI-powered answer engine using Gemini API + Google Search + ML Kit
 */
class AnswerEngine(private val context: Context) {

    companion object {
        private const val TAG = "AnswerEngine"
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        private const val GOOGLE_SEARCH_URL = "https://www.googleapis.com/customsearch/v1"
        private const val WIKIPEDIA_API_URL = "https://en.wikipedia.org/w/api.php"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build()
    )

    /**
     * Main method: Find the best answer for a quiz question
     * Uses multiple strategies and picks the best result
     */
    suspend fun findAnswer(question: QuizQuestion): QuizAnswer = withContext(Dispatchers.IO) {
        val apiKey = PrefsManager.geminiApiKey

        // Strategy 1: Use Gemini AI if API key available (best accuracy)
        if (apiKey.isNotBlank()) {
            try {
                val aiAnswer = askGeminiAI(question, apiKey)
                if (aiAnswer != null && aiAnswer.confidence > 0.5f) {
                    return@withContext aiAnswer
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini AI failed, falling back to search", e)
            }
        }

        // Strategy 2: Search Wikipedia
        try {
            val wikiAnswer = searchWikipedia(question)
            if (wikiAnswer != null && wikiAnswer.confidence > 0.4f) {
                return@withContext wikiAnswer
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia search failed", e)
        }

        // Strategy 3: Google search fallback
        try {
            val googleAnswer = searchGoogle(question)
            if (googleAnswer != null) {
                return@withContext googleAnswer
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google search failed", e)
        }

        // Fallback: Return best guess
        QuizAnswer(
            bestAnswer = "कोई निश्चित जवाब नहीं मिला",
            confidence = 0.0f,
            optionScores = emptyMap(),
            source = "none",
            explanation = "सभी search methods विफल रहे"
        )
    }

    /**
     * Ask Gemini AI to answer the question
     */
    private suspend fun askGeminiAI(question: QuizQuestion, apiKey: String): QuizAnswer? =
        suspendCoroutine { continuation ->
            val optionsText = if (question.options.isNotEmpty()) {
                "\n\nOptions:\n" + question.options.mapIndexed { i, opt ->
                    "${('A' + i)}) $opt"
                }.joinToString("\n")
            } else ""

            val imageContext = if (question.hasImage && question.imageDescription != null) {
                "\n\n[Image context: ${question.imageDescription}]"
            } else ""

            val prompt = """You are an expert quiz solver for Indian quiz apps (GK, current affairs, sports, Bollywood, history, science). Answer accurately.

Question: ${question.questionText}$optionsText$imageContext

Rules:
- If options are given, "answer" MUST be exactly one of the options (copy text verbatim).
- Use real-world facts; prefer the most commonly accepted answer.
- For image questions, identify the subject from image context first.

Respond in this exact JSON format:
{
  "answer": "the correct answer text",
  "confidence": 0.95,
  "explanation": "brief explanation why this is correct",
  "option_scores": {"Option A": 0.9, "Option B": 0.05, "Option C": 0.03, "Option D": 0.02}
}

If options are provided, "answer" must be exactly one of the options.
Give confidence between 0.0 and 1.0."""

            val jsonBody = """
            {
                "contents": [{
                    "parts": [{"text": ${Gson().toJson(prompt)}}]
                }],
                "generationConfig": {
                    "temperature": 0.1,
                    "maxOutputTokens": 500
                }
            }
            """.trimIndent()

            val request = Request.Builder()
                .url("$GEMINI_API_URL?key=$apiKey")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: return continuation.resume(null)
                        val json = JsonParser.parseString(body).asJsonObject
                        val text = json
                            .getAsJsonArray("candidates")
                            .get(0).asJsonObject
                            .getAsJsonObject("content")
                            .getAsJsonArray("parts")
                            .get(0).asJsonObject
                            .get("text").asString

                        // Extract JSON from response (might be wrapped in markdown)
                        val jsonMatch = Regex("""\{[\s\S]*\}""").find(text)
                        if (jsonMatch != null) {
                            val answerJson = JsonParser.parseString(jsonMatch.value).asJsonObject

                            val optionScores = mutableMapOf<String, Float>()
                            answerJson.getAsJsonObject("option_scores")?.entrySet()?.forEach {
                                optionScores[it.key] = it.value.asFloat
                            }

                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = answerJson.get("answer").asString,
                                    confidence = answerJson.get("confidence").asFloat,
                                    optionScores = optionScores,
                                    source = "Gemini AI",
                                    explanation = answerJson.get("explanation")?.asString ?: ""
                                )
                            )
                        } else {
                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = text.take(200),
                                    confidence = 0.6f,
                                    optionScores = emptyMap(),
                                    source = "Gemini AI",
                                    explanation = text
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Gemini response", e)
                        continuation.resume(null)
                    }
                }
            })
        }

    /**
     * Search Wikipedia for the answer
     */
    private suspend fun searchWikipedia(question: QuizQuestion): QuizAnswer? =
        suspendCoroutine { continuation ->
            val query = question.toSearchQuery()
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("en.wikipedia.org")
                .addPathSegments("w/api.php")
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("srlimit", "3")
                .addQueryParameter("srprop", "snippet")
                .build()

            val request = Request.Builder().url(url).build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: return continuation.resume(null)
                        val json = JsonParser.parseString(body).asJsonObject
                        val results = json.getAsJsonObject("query")
                            ?.getAsJsonArray("search") ?: return continuation.resume(null)

                        if (results.size() == 0) return continuation.resume(null)

                        // Combine snippets
                        val snippets = results.map {
                            it.asJsonObject.get("snippet").asString
                                .replace(Regex("<[^>]*>"), "") // Remove HTML tags
                        }.joinToString(" ")

                        // Match against options
                        if (question.options.isNotEmpty()) {
                            val scores = question.options.associateWith { option ->
                                calculateRelevance(option, snippets)
                            }
                            val bestOption = scores.maxByOrNull { it.value }

                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = bestOption?.key ?: "Unknown",
                                    confidence = (bestOption?.value ?: 0f).coerceAtMost(0.85f),
                                    optionScores = scores,
                                    source = "Wikipedia",
                                    explanation = snippets.take(200)
                                )
                            )
                        } else {
                            val title = results.get(0).asJsonObject.get("title").asString
                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = title,
                                    confidence = 0.6f,
                                    optionScores = emptyMap(),
                                    source = "Wikipedia",
                                    explanation = snippets.take(200)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Wikipedia response", e)
                        continuation.resume(null)
                    }
                }
            })
        }

    /**
     * Google search fallback
     */
    private suspend fun searchGoogle(question: QuizQuestion): QuizAnswer? =
        suspendCoroutine { continuation ->
            // Using Google's web search via simple scraping approach
            val query = question.toSearchQuery()
            val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: return continuation.resume(null)

                        // Extract text snippets from search results
                        val snippetPattern = Regex("""<span[^>]*>(.*?)</span>""")
                        val snippets = snippetPattern.findAll(body)
                            .map { it.groupValues[1].replace(Regex("<[^>]*>"), "") }
                            .filter { it.length > 20 }
                            .take(5)
                            .joinToString(" ")

                        if (question.options.isNotEmpty()) {
                            val scores = question.options.associateWith { option ->
                                calculateRelevance(option, "$body $snippets")
                            }
                            val bestOption = scores.maxByOrNull { it.value }

                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = bestOption?.key ?: "Unknown",
                                    confidence = (bestOption?.value ?: 0f).coerceAtMost(0.75f),
                                    optionScores = scores,
                                    source = "Google Search",
                                    explanation = snippets.take(200)
                                )
                            )
                        } else {
                            continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Google response", e)
                        continuation.resume(null)
                    }
                }
            })
        }

    /**
     * Calculate relevance score of an option against search results
     */
    private fun calculateRelevance(option: String, searchText: String): Float {
        val lowerText = searchText.lowercase()
        val lowerOption = option.lowercase().trim()
        val words = lowerOption.split(Regex("\\s+"))

        var score = 0f

        // Exact match (highest score)
        if (lowerText.contains(lowerOption)) {
            score += 0.5f
        }

        // Word-level matching
        val matchedWords = words.count { lowerText.contains(it) }
        score += (matchedWords.toFloat() / words.size.coerceAtLeast(1)) * 0.4f

        // Frequency bonus
        val count = Regex(Regex.escape(lowerOption)).findAll(lowerText).count()
        score += (count.coerceAtMost(5) * 0.02f)

        return score.coerceIn(0f, 1f)
    }

    /**
     * Analyze a screenshot bitmap for image content (using ML Kit Image Labeling)
     */
    suspend fun analyzeImage(bitmap: Bitmap): String? = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)

        imageLabeler.process(image)
            .addOnSuccessListener { labels ->
                if (labels.isNotEmpty()) {
                    val description = labels.take(5).joinToString(", ") {
                        "${it.text} (${String.format("%.0f", it.confidence * 100)}%)"
                    }
                    continuation.resume(description)
                } else {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Image labeling failed", e)
                continuation.resume(null)
            }
    }

    /**
     * Analyze image using Gemini Vision API for more detailed understanding
     */
    suspend fun analyzeImageWithAI(bitmap: Bitmap, question: String): QuizAnswer? {
        val apiKey = PrefsManager.geminiApiKey
        if (apiKey.isBlank()) return null

        return suspendCoroutine { continuation ->
            // Convert bitmap to base64
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val jsonBody = """
            {
                "contents": [{
                    "parts": [
                        {"text": "Look at this image and answer the quiz question: $question\nProvide the answer in JSON: {\"answer\": \"...\", \"confidence\": 0.9, \"explanation\": \"...\"}"},
                        {
                            "inline_data": {
                                "mime_type": "image/jpeg",
                                "data": "$imageBase64"
                            }
                        }
                    ]
                }],
                "generationConfig": {
                    "temperature": 0.1,
                    "maxOutputTokens": 300
                }
            }
            """.trimIndent()

            val request = Request.Builder()
                .url("$GEMINI_API_URL?key=$apiKey")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: return continuation.resume(null)
                        val json = JsonParser.parseString(body).asJsonObject
                        val text = json.getAsJsonArray("candidates")
                            .get(0).asJsonObject
                            .getAsJsonObject("content")
                            .getAsJsonArray("parts")
                            .get(0).asJsonObject
                            .get("text").asString

                        val jsonMatch = Regex("""\{[\s\S]*\}""").find(text)
                        if (jsonMatch != null) {
                            val answerJson = JsonParser.parseString(jsonMatch.value).asJsonObject
                            continuation.resume(
                                QuizAnswer(
                                    bestAnswer = answerJson.get("answer").asString,
                                    confidence = answerJson.get("confidence").asFloat,
                                    optionScores = emptyMap(),
                                    source = "Gemini Vision AI",
                                    explanation = answerJson.get("explanation")?.asString ?: ""
                                )
                            )
                        } else {
                            continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        continuation.resume(null)
                    }
                }
            })
        }
    }
}
