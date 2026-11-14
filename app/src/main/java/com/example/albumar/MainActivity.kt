package com.example.albumar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            addView(label("Album AR", 30f, true).apply { gravity = Gravity.CENTER })
            addView(bigButton("СМОТРЕТЬ") { startActivity(Intent(this@MainActivity, ArActivity::class.java)) })
            addView(bigButton("ГЕНЕРИРОВАТЬ") { startActivity(Intent(this@MainActivity, GenerateActivity::class.java)) })
            addView(bigButton("ВЫХОД") { finishAffinity() })
        }
        setContentView(root)
    }
}
