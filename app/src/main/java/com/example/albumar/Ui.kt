package com.example.albumar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.bigButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    textSize = 18f
    setTextColor(Color.WHITE)
    isAllCaps = false
    background = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.parseColor("#3949AB"))
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
    ).apply { setMargins(dp(32), dp(10), dp(32), dp(10)) }
    setOnClickListener { onClick() }
}

fun Context.label(text: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Color.parseColor("#212121"))
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    setPadding(0, dp(6), 0, dp(6))
}

fun Context.smallButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

@Suppress("unused")
fun View.centerGravity() { (this as? LinearLayout)?.gravity = Gravity.CENTER }
