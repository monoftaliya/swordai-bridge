package com.swordai.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.swordai.bridge.AppGraph
import com.swordai.bridge.agent.EmergencyStop

/**
 * Видимая индикация работы ИИ поверх экрана: плашка с текущим действием и кнопкой «СТОП»
 * и короткая подсветка точки нажатия. Использует окно TYPE_ACCESSIBILITY_OVERLAY,
 * поэтому не требует разрешения «поверх других окон».
 */
class OverlayIndicator(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private var pill: LinearLayout? = null
    private var pillText: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { removePill() }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Показать плашку с описанием текущего действия. */
    fun show(text: String) {
        main.post {
            if (!AppGraph.settings.current.overlayIndicator) return@post
            ensurePill()
            pillText?.text = text
            main.removeCallbacks(hideRunnable)
        }
    }

    /** Действие завершено: плашка исчезнет через несколько секунд. */
    fun idle() {
        main.post {
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, 4_000)
        }
    }

    fun hide() {
        main.post { removePill() }
    }

    /** На время жеста плашка не принимает касания, чтобы не перехватить его. */
    fun setTouchable(touchable: Boolean) {
        main.post {
            val view = pill ?: return@post
            val params = pillParams ?: return@post
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                // окно могло быть удалено
            }
        }
    }

    /** Краткая подсветка точки нажатия. */
    fun flashTap(x: Int, y: Int) {
        main.post {
            if (!AppGraph.settings.current.overlayIndicator) return@post
            val size = dp(56)
            val view = View(service).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x553D7BFF)
                    setStroke(dp(2), 0xFF8A5CFF.toInt())
                }
                scaleX = 0.5f
                scaleY = 0.5f
            }
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - size / 2
                this.y = y - size / 2
            }
            try {
                windowManager.addView(view, params)
                view.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(450).withEndAction {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        // уже удалено
                    }
                }.start()
            } catch (e: Exception) {
                // окно наложения недоступно
            }
        }
    }

    private fun ensurePill() {
        if (pill != null) return
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xEE0C0F18.toInt())
                setStroke(dp(1), 0xFF8A5CFF.toInt())
            }
        }
        val dot = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3D7BFF.toInt())
            }
        }
        container.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        val label = TextView(service).apply {
            setTextColor(0xFFE8EBFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
        }
        container.addView(label)
        val stop = TextView(service).apply {
            text = "СТОП"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFE5364F.toInt())
            }
            contentDescription = "Аварийная остановка SwordAI"
            setOnClickListener { EmergencyStop.trigger(service, "экранная плашка") }
        }
        container.addView(
            stop,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10) },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
        }
        try {
            windowManager.addView(container, params)
            pill = container
            pillText = label
            pillParams = params
        } catch (e: Exception) {
            pill = null
        }
    }

    private fun removePill() {
        main.removeCallbacks(hideRunnable)
        val view = pill ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // уже удалено
        }
        pill = null
        pillText = null
        pillParams = null
    }
}
