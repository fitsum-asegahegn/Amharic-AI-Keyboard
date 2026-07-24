package com.fitsum.amharickeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import com.fitsum.amharickeyboard.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AmharicKeyboardService : InputMethodService() {

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("AmharicKeyboardPrefs", MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        val translateBtn = view.findViewById<View>(R.id.btn_translate)
        translateBtn?.setOnClickListener {
            translateCurrentText()
        }
        return view
    }

    private fun translateCurrentText() {
        val apiKey = getApiKey()
        val inputConnection = currentInputConnection ?: return
        
        if (apiKey.isBlank()) {
            inputConnection.commitText(" ⚠️ Please add API key in app settings!", 1)
            return
        }

        val rawText = inputConnection.getTextBeforeCursor(1000, 0)?.toString()?.trim()
        if (rawText.isNullOrEmpty()) return

        inputConnection.commitText(" ⏳...", 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val amharicText = fetchGeminiFreeTier(rawText, apiKey)
                withContext(Dispatchers.Main) {
                    inputConnection.deleteSurroundingText(rawText.length + 5, 0)
                    inputConnection.commitText(amharicText, 1)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    inputConnection.deleteSurroundingText(5, 0)
                    inputConnection.commitText(" ❌ Error: ${e.message}", 1)
                }
            }
        }
    }

    private suspend fun fetchGeminiFreeTier(text: String, apiKey: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        
        val jsonBody = """
            {
              "system_instruction": {
                "parts": [
                  {
                    "text": "You are an expert Amharic translator and transliteration engine. When the user provides Latinized Amharic text, you must provide BOTH the proper Ethiopic script (Fidel) and an accurate English translation. Format your response exactly like this:\n\n🇪🇹 Amharic Script (Fidel)\n[Your transliterated Amharic text here]\n\n🇬🇧 English Translation\n[If the text includes Ethiopian time, add a brief note about time conversion, then provide the full English translation here]\n\nDo not include any other conversational filler outside of this structure."
                  }
                ]
              },
              "contents": [{
                "parts": [{"text": "${text.replace("\"", "\\\"")}"}]
              }]
            }
        """.trimIndent()

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        var retryCount = 0
        val maxRetries = 3

        while (retryCount <= maxRetries) {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use { resp ->
                if (resp.isSuccessful) {
                    val responseData = resp.body?.string() ?: throw Exception("Empty response")
                    val jsonObject = JSONObject(responseData as String)
                    
                    return jsonObject.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                } else if (resp.code == 429) {
                    retryCount++
                    if (retryCount > maxRetries) throw Exception("Rate limit exceeded. Try again in a minute.")
                    delay((2000 * retryCount).toLong())
                } else {
                    throw Exception("API Error: ${resp.code}")
                }
            }
        }
        throw Exception("Failed after retries")
    }
}
