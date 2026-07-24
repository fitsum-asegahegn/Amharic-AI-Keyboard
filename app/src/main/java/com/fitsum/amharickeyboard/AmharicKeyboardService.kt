package com.fitsum.amharickeyboard

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
        val layoutId = resources.getIdentifier("keyboard_view", "layout", packageName)
        val view = layoutInflater.inflate(layoutId, null)
        
        val btnId = resources.getIdentifier("btn_translate", "id", packageName)
        val translateBtn = if (btnId != 0) view.findViewById<View>(btnId) else null
        translateBtn?.setOnClickListener { translateCurrentText() }

        val containerId = resources.getIdentifier("keys_container", "id", packageName)
        val container = if (containerId != 0) view.findViewById<LinearLayout>(containerId) else null

        if (container != null) {
            setupQwertyKeys(container)
        }

        return view
    }

    private fun setupQwertyKeys(container: LinearLayout) {
        val rows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "⌫"),
            listOf("?123", ",", "SPACE", ".", "↵")
        )

        val rowHeightPx = (52 * resources.displayMetrics.density).toInt()

        for (rowKeys in rows) {
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (key in rowKeys) {
                val keyBackground = GradientDrawable().apply {
                    setColor(if (key == "↵") Color.parseColor("#008070") else Color.parseColor("#2B3648"))
                    cornerRadius = 14f
                }

                val keyView = TextView(this).apply {
                    text = key
                    textSize = if (key == "SPACE" || key == "?123") 13f else 20f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = keyBackground
                    setPadding(0, 0, 0, 0)
                    
                    val weight = when (key) {
                        "SPACE" -> 4f
                        "⌫", "↵", "?123" -> 1.5f
                        else -> 1f
                    }
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                    params.setMargins(4, 4, 4, 4)
                    layoutParams = params

                    setOnClickListener {
                        val ic = currentInputConnection ?: return@setOnClickListener
                        when (key) {
                            "⌫" -> ic.deleteSurroundingText(1, 0)
                            "SPACE" -> ic.commitText(" ", 1)
                            "↵" -> ic.commitText("\n", 1)
                            else -> ic.commitText(key, 1)
                        }
                    }
                }
                rowLayout.addView(keyView)
            }
            container.addView(rowLayout)
        }
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
