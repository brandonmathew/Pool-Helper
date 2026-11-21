package com.poolhelper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/** Live capture statistics shared between the service, overlay, and UI. */
object CaptureStats {
    @Volatile var running: Boolean = false
    @Volatile var frames: Long = 0
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var blackRatio: Double = 0.0
    @Volatile var meanLuminance: Double = 0.0
    @Volatile var savedFrames: Int = 0
    @Volatile var verdict: String = "not started"

    fun reset() {
        frames = 0; width = 0; height = 0
        blackRatio = 0.0; meanLuminance = 0.0; savedFrames = 0
        verdict = "starting"
    }

    fun summary(): String =
        "frames=$frames ${width}x$height black=${"%.3f".format(blackRatio)} " +
            "lum=${"%.1f".format(meanLuminance)} saved=$savedFrames verdict=$verdict"
}

/**
 * MediaProjection capture service (Stage 1: capture only).
 *
 * Android 14+ rules: the mediaProjection foreground service is started
 * before getMediaProjection(), consent is per session, the whole display is
 * captured (single-app projection letterboxes and breaks coordinates), and
 * a MediaProjection.Callback is registered before createVirtualDisplay.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var frameCounter = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        CaptureStats.reset()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification("Capturing"), foregroundType())
        } else {
            startForeground(NOTIF_ID, buildNotification("Capturing"))
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj
        // Required on Android 14+: a callback must be registered first.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Fires on user stop, and on Android 15 when the screen locks.
                stopCapture()
                stopSelf()
            }
        }, null)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        workerThread = HandlerThread("capture-worker").also { it.start() }
        worker = Handler(workerThread!!.looper)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, worker)

        virtualDisplay = proj.createVirtualDisplay(
            "pool-helper-capture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, worker,
        )
        CaptureStats.running = true
        CaptureStats.width = w
        CaptureStats.height = h
    }

    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            frameCounter++
            CaptureStats.frames = frameCounter
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // Sample a grid of pixels for black-frame (FLAG_SECURE) detection.
            var black = 0
            var samples = 0
            var lumSum = 0.0
            val stepX = maxOf(1, width / 48)
            val stepY = maxOf(1, height / 48)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val idx = y * rowStride + x * pixelStride
                    if (idx + 2 < buffer.capacity()) {
                        val r = buffer.get(idx).toInt() and 0xFF
                        val g = buffer.get(idx + 1).toInt() and 0xFF
                        val b = buffer.get(idx + 2).toInt() and 0xFF
                        if (r < 8 && g < 8 && b < 8) black++
                        lumSum += (r + g + b) / 3.0
                        samples++
                    }
                    x += stepX
                }
                y += stepY
            }
            if (samples > 0) {
                val ratio = black.toDouble() / samples
                // Exponential moving average so a brief dark transition does
                // not flip the verdict, but sustained black frames do.
                CaptureStats.blackRatio = CaptureStats.blackRatio * 0.9 + ratio * 0.1
                CaptureStats.meanLuminance = lumSum / samples
                CaptureStats.verdict = when {
                    frameCounter < 90 -> "warming up"
                    CaptureStats.blackRatio > 0.98 -> "BLACK FRAMES - FLAG_SECURE suspected"
                    else -> "OK - frames usable"
                }
            }

            if (frameCounter % 240 == 0L) {
                saveFrame(width, height, rowStride, pixelStride, buffer.duplicate())
                updateNotification()
            }
        } catch (_: Exception) {
            // A torn frame must never kill capture.
        } finally {
            image.close()
        }
    }

    private fun saveFrame(width: Int, height: Int, rowStride: Int, pixelStride: Int, buffer: java.nio.ByteBuffer) {
        try {
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, width, height)
            val dir = File(filesDir, "frames").also { it.mkdirs() }
            val existing = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (existing.size >= 12) existing.take(existing.size - 11).forEach { it.delete() }
            val out = File(dir, "frame_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            CaptureStats.savedFrames++
            if (cropped !== full) cropped.recycle()
            full.recycle()
        } catch (_: Exception) {
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Pool Helper capture")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(CaptureStats.summary()))
    }

    private fun stopCapture() {
        CaptureStats.running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; projection = null
        workerThread?.quitSafely()
        workerThread = null; worker = null
        if (CaptureStats.verdict == "warming up" || CaptureStats.verdict == "starting") {
            CaptureStats.verdict = "stopped"
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.poolhelper.app.CAPTURE_START"
        const val ACTION_STOP = "com.poolhelper.app.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1001

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
