package com.swordai.bridge.accessibility

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Реальные размеры экрана в пикселях (совпадают с координатами жестов и getBoundsInScreen). */
object ScreenMetrics {
    data class Metrics(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int)

    @Suppress("DEPRECATION")
    fun get(context: Context): Metrics {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Metrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation)
    }
}
