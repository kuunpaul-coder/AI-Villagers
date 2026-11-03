package com.aivillagers.bridge

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "AI Villagers"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = "Сборочная проверка проекта.\n\nЭто стартовая оболочка приложения.\nЛокальная Gemma и интеграция с модом Stardew Valley пока не подключены."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 24)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(status)
        }
        setContentView(layout)
    }
}
