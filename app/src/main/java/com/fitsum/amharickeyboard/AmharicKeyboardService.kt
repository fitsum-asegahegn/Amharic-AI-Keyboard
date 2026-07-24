package com.fitsum.amharickeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout

class AmharicKeyboardService : InputMethodService() {
    
    override fun onCreateInputView(): View {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xFFECEFF1.toInt())
        
        val button = Button(this)
        button.text = "ሀ"
        button.setOnClickListener {
            currentInputConnection?.commitText("ሀ", 1)
        }
        
        layout.addView(button)
        return layout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }
}
