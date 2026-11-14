package com.example.albumar

import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/** Эвристическая оценка того, насколько фото подойдёт для распознавания (0..100). */
object Quality {
    class Result(val score: Int, val hint: String) {
        val label get() = when { score >= 65 -> "хорошо"; score >= 40 -> "средне"; else -> "плохо" }
        val color get() = when { score >= 65 -> 0xFF2E7D32.toInt(); score >= 40 -> 0xFFEF6C00.toInt(); else -> 0xFFC62828.toInt() }
        val text get() = "Распознавание: $score/100 ($label)" + if (hint.isNotEmpty()) " — $hint" else ""
    }

    fun analyze(file: File): Result? {
        val b = BitmapUtil.load(file, 320) ?: return null
        val w = b.width; val h = b.height
        if (w < 16 || h < 16) return Result(0, "слишком маленькое изображение")
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h) { val c = px[it]; 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255) }
        var mean = 0f; for (v in g) mean += v; mean /= g.size
        var varSum = 0f; for (v in g) varSum += (v - mean) * (v - mean)
        val std = sqrt(varSum / g.size)
        var lapSum = 0.0; var lapSq = 0.0; var edges = 0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            lapSum += l; lapSq += l * l; n++
            val gx = g[i + 1] - g[i - 1]; val gy = g[i + w] - g[i - w]
            if (kotlin.math.abs(gx) + kotlin.math.abs(gy) > 40f) edges++
        }
        val lapVar = lapSq / n - (lapSum / n) * (lapSum / n)
        val c = min(std / 50f, 1f)
        val s = min((lapVar / 200.0).toFloat(), 1f)
        val d = min(edges.toFloat() / n / 0.12f, 1f)
        val score = ((0.3f * c + 0.3f * s + 0.4f * d) * 100).toInt().coerceIn(0, 100)
        val hints = ArrayList<String>()
        if (c < 0.4f) hints += "мало контраста"
        if (s < 0.3f) hints += "размытое"
        if (d < 0.4f) hints += "мало деталей (однотонное)"
        return Result(score, hints.joinToString(", "))
    }
}
