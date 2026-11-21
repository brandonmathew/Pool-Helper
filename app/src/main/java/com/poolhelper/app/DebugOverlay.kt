package com.poolhelper.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Debug overlay (locked: overlay only, NO floating start/stop button).
 * Touch-through: FLAG_NOT_TOUCHABLE means it can never intercept game input.
 * Stage 1 shows capture statistics; detection and shot graphics join in
 * Stage 2.
 */
class DebugOverlay(private val context: Context) {

    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            view?.text = "Pool Helper\n" + CaptureStats.summary()
            handler.postDelayed(this, 500)
        }
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(): Boolean {
        if (view != null) return true
        if (!canDraw()) return false
        val wm = context.getSystemService(WindowManager::class.java)
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            setPadding(16, 12, 16, 12)
            text = "Pool Helper"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 120
        }
        return try {
            wm.addView(tv, params)
            view = tv
            handler.post(ticker)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val tv = view ?: return
        try {
            context.getSystemService(WindowManager::class.java).removeView(tv)
        } catch (_: Exception) {
        }
        view = null
    }

    fun isShown(): Boolean = view != null
}
