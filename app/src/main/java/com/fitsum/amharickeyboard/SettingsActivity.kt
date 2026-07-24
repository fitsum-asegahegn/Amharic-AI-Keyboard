package com.fitsum.amharickeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnEnable = findViewById<Button>(R.id.btnEnableKeyboard)
        val btnSelect = findViewById<Button>(R.id.btnSelectKeyboard)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelect.setOnClickListener {
            val imeManager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imeManager.showInputMethodPicker()
        }
    }
}
