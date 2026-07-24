package com.fitsum.amharickeyboard

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private var isSymbolsMode = false

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

    val realMetrics = android.content.res.Resources.getSystem().displayMetrics
    val screenHeightPx = realMetrics.heightPixels
    val targetHeightPx = (screenHeightPx * 0.50).toInt()

    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeightPx)

    val btnId = resources.getIdentifier("btn_translate", "id", packageName)
    val translateBtn = if (btnId != 0) view.findViewById<android.widget.Button>(btnId) else null

    // TEMPORARY: show the numbers directly on the button instead of a Toast,
    // since Toasts may be silently blocked by the phone's background pop-up settings.
    translateBtn?.text = "H:$targetHeightPx/S:$screenHeightPx"

    translateBtn?.setOnClickListener { translateCurrentText() }

    val containerId = resources.getIdentifier("keys_container", "id", packageName)
    val container = if (containerId != 0) view.findViewById<LinearLayout>(containerId) else null

    if (container != null) {
        setupKeys(container)
    }

    return view
}

    private fun setupKeys(container: LinearLayout) {
        container.removeAllViews()

        val letterRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "⌫"),
            listOf("?123", ",", "SPACE", ".", "↵")
        )

        val symbolRows = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?", "⌫"),
            listOf("ABC", ",", "SPACE", ".", "↵")
        )

        val currentRows = if (isSymbolsMode) symbolRows else letterRows

        for (rowKeys in currentRows) {
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
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
                    textSize = if (key == "SPACE" || key == "?123" || key == "ABC") 14f else 22f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = keyBackground
                    setPadding(0, 0, 0, 0)

                    val weight = when (key) {
                        "SPACE" -> 4f
                        "⌫", "↵", "?123", "ABC" -> 1.5f
                        else -> 1f
                    }
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                    params.setMargins(4, 5, 4, 5)
                    layoutParams = params

                    setOnClickListener {
                        val ic = currentInputConnection
                        when (key) {
                            "?123", "ABC" -> {
                                isSymbolsMode = !isSymbolsMode
                                setupKeys(container)
                            }
                            "⌫" -> ic?.deleteSurroundingText(1, 0)
                            "SPACE" -> ic?.commitText(" ", 1)
                            "↵" -> ic?.commitText("\n", 1)
                            else -> ic?.commitText(key, 1)
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
            Toast.makeText(this, "Add your API key in app settings first", Toast.LENGTH_LONG).show()
            return
        }

        val rawText = inputConnection.getTextBeforeCursor(1000, 0)?.toString()?.trim()
        if (rawText.isNullOrEmpty()) return

        inputConnection.commitText(" ⏳...", 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val draft = callGemini(rawText, apiKey, DRAFT_PROMPT)
                val finalText = callGemini(
                    "ORIGINAL:\n$rawText\n\nDRAFT:\n$draft",
                    apiKey,
                    REVIEW_PROMPT
                )
                withContext(Dispatchers.Main) {
                    inputConnection.deleteSurroundingText(rawText.length + 5, 0)
                    inputConnection.commitText(finalText, 1)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    inputConnection.deleteSurroundingText(rawText.length + 5, 0)
                    inputConnection.commitText(rawText, 1)
                    Toast.makeText(this@AmharicKeyboardService, "Translation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun callGemini(text: String, apiKey: String, systemPrompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", org.json.JSONArray().put(JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))
            }))
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        var retryCount = 0
        val maxRetries = 3

        while (retryCount <= maxRetries) {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use { resp ->
                if (resp.isSuccessful) {
                    val responseData = resp.body?.string() ?: throw Exception("Empty response")
                    return JSONObject(responseData)
                        .getJSONArray("candidates")
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

    companion object {
        const val DRAFT_PROMPT = "You are a fluent, native-level Amharic writer. The user writes casually — phonetic Amharic in Latin letters, plain English, or a mix, with typos and no punctuation. Convert everything into natural, correctly spelled Amharic (Ge'ez script), the way a native speaker would text it. Keep only genuine proper nouns untranslated. Output ONLY the Amharic text — no headers, no English translation, no notes, no quotation marks."
        const val REVIEW_PROMPT = "You are a meticulous Amharic proofreader. Compare the ORIGINAL casual input against the DRAFT translation, word by word. Fix garbled orthography, wrong letters, anything left in English, hallucinated or dropped words. Output ONLY the corrected final Amharic text — no headers, no notes, no quotation marks."
    }
}
