package com.example.network

import android.util.Log
import com.example.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class GeminiRequest(
  val contents: List<Content>,
  val generationConfig: GenerationConfig? = null,
)

@Serializable
data class Content(
  val role: String = "user",
  val parts: List<Part>,
)

@Serializable
data class Part(
  val text: String? = null,
)

@Serializable
data class GenerationConfig(
  @SerialName("responseMimeType") val responseMimeType: String? = null,
)

@Serializable
data class GeminiResponse(
  val candidates: List<Candidate>? = null,
)

@Serializable
data class Candidate(
  val content: Content? = null,
)

interface GeminiApiService {
  @POST("v1beta/models/gemini-2.5-flash:generateContent")
  suspend fun generateContent(
    @Header("x-goog-api-key") apiKey: String,
    @Body request: GeminiRequest,
  ): GeminiResponse
}

class GeminiService {
  private val apiKey: String = BuildConfig.GEMINI_API_KEY

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  private val api: GeminiApiService =
    Retrofit.Builder()
      .baseUrl("https://generativelanguage.googleapis.com/")
      .client(client)
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build()
      .create(GeminiApiService::class.java)

  fun hasValidApiKey(): Boolean {
    return apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"
  }

  suspend fun generateJson(prompt: String): Result<String> {
    if (!hasValidApiKey()) {
      Log.w("GeminiService", "GEMINI_API_KEY is not configured.")
      return Result.failure(IllegalStateException("not_granted"))
    }
    return try {
      val request =
        GeminiRequest(
          contents = listOf(Content(parts = listOf(Part(text = prompt)))),
          generationConfig = GenerationConfig(responseMimeType = "application/json"),
        )
      val response = api.generateContent(apiKey, request)
      val text =
        response.candidates
          ?.firstOrNull()
          ?.content
          ?.parts
          ?.mapNotNull { it.text }
          ?.joinToString("")
          ?.trim()
      if (!text.isNullOrEmpty()) {
        val cleaned =
          text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        Result.success(cleaned)
      } else {
        Result.failure(Exception("Empty response from Gemini API"))
      }
    } catch (e: Exception) {
      Log.e("GeminiService", "Error calling Gemini API", e)
      Result.failure(e)
    }
  }
}
