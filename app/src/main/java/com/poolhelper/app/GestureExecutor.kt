package com.poolhelper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Input primitives in screen-proportion coordinates (0..1), so no coordinate
 * is ever device-specific. Implemented only by Accessibility dispatchGesture
 * (locked: no Shizuku executor).
 */
interface GestureExecutor {
    fun isReady(): Boolean
    fun tap(x: Float, y: Float): Boolean
    /** Drag through proportion points, holding for [durationMs] in total. */
    fun drag(points: List<Pair<Float, Float>>, durationMs: Long): Boolean
    /** Press at a point, hold, then release — the power-bar gesture shape. */
    fun pressHoldRelease(x: Float, y: Float, holdMs: Long): Boolean
}

class PoolAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-only service: window content is never read
        // (canRetrieveWindowContent=false in the service config).
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PoolAccessibilityService? = null
            private set
    }
}

class AccessibilityGestureExecutor : GestureExecutor {

    private fun service(): PoolAccessibilityService? = PoolAccessibilityService.instance

    override fun isReady(): Boolean = service() != null

    private fun toPixels(x: Float, y: Float): Pair<Float, Float> {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        return Pair(x * metrics.widthPixels, y * metrics.heightPixels)
    }

    override fun tap(x: Float, y: Float): Boolean {
        val (px, py) = toPixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        return dispatch(listOf(Triple(path, 0L, 60L)))
    }

    override fun drag(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val path = Path()
        points.forEachIndexed { i, (fx, fy) ->
            val (px, py) = toPixels(fx, fy)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return dispatch(listOf(Triple(path, 0L, max(1L, durationMs))))
    }

    override fun pressHoldRelease(x: Float, y: Float, holdMs: Long): Boolean {
        val (px, py) = toPixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        return dispatch(listOf(Triple(path, 0L, max(1L, holdMs))))
    }

    /** Dispatch strokes and wait (off the main thread) for completion. */
    private fun dispatch(strokes: List<Triple<Path, Long, Long>>): Boolean {
        val svc = service() ?: return false
        val builder = GestureDescription.Builder()
        for ((path, start, duration) in strokes) {
            builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
        }
        val latch = CountDownLatch(1)
        var result = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = false
                latch.countDown()
            }
        }
        val dispatched = if (Looper.myLooper() == Looper.getMainLooper()) {
            // dispatchGesture must be called from a thread with a looper in
            // some OEM builds; the service call itself is safe on main.
            svc.dispatchGesture(builder.build(), callback, null)
        } else {
            var ok = false
            Handler(Looper.getMainLooper()).post {
                ok = svc.dispatchGesture(builder.build(), callback, null)
                if (!ok) latch.countDown()
            }
            ok
        }
        latch.await(3, TimeUnit.SECONDS)
        return dispatched || result
    }
}
