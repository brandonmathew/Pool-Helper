package com.poolhelper.app

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Pure crash-report formatting, unit-tested on the JVM. */
object CrashReport {
    fun format(
        timestamp: String,
        appVersion: String,
        androidRelease: String,
        apiLevel: Int,
        device: String,
        threadName: String,
        error: Throwable,
    ): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("Pool Helper crash report")
            appendLine("Time: $timestamp")
            appendLine("App: $appVersion")
            appendLine("Android: $androidRelease (API $apiLevel)")
            appendLine("Device: $device")
            appendLine("Thread: $threadName")
            appendLine()
            append(sw.toString())
        }
    }
}

/**
 * Global uncaught-exception handler. On a crash it writes the report to
 * files/crash/last_crash.txt and opens [CrashActivity], then ends the
 * process. Two guards keep a broken crash activity from looping forever:
 * a same-process re-entry flag, and a timestamp the crash activity writes
 * when it opens — if a second crash arrives within 30 s of the crash
 * activity opening, the system default handler takes over instead.
 */
class CrashHandler(
    private val appContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (handlingCrash || crashActivityOpenedRecently()) {
            defaultHandler?.uncaughtException(thread, error)
            return
        }
        handlingCrash = true
        try {
            val report = buildReport(thread, error)
            val dir = File(appContext.filesDir, "crash").also { it.mkdirs() }
            File(dir, "last_crash.txt").writeText(report)
            val intent = Intent(appContext, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_FROM_HANDLER, true)
            }
            appContext.startActivity(intent)
        } catch (_: Exception) {
            defaultHandler?.uncaughtException(thread, error)
            return
        }
        // Give the crash activity a moment to start, then end this process so
        // the crashed state does not linger behind it.
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun crashActivityOpenedRecently(): Boolean {
        val f = File(appContext.filesDir, "crash/activity_opened.txt")
        if (!f.exists()) return false
        val openedAt = runCatching { f.readText().trim().toLong() }.getOrNull() ?: return false
        return System.currentTimeMillis() - openedAt < 30_000
    }

    private fun buildReport(thread: Thread, error: Throwable): String {
        val info = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        } catch (_: Exception) { null }
        val version = if (info != null) {
            "${info.versionName} (code ${info.longVersionCode})"
        } else "unknown"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        return CrashReport.format(
            timestamp, version,
            android.os.Build.VERSION.RELEASE ?: "?", android.os.Build.VERSION.SDK_INT,
            device, thread.name, error,
        )
    }

    companion object {
        @Volatile private var handlingCrash = false

        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, previous),
            )
        }

        /** The saved report from the most recent crash, or null. */
        fun savedReport(context: Context): String? {
            val f = File(context.filesDir, "crash/last_crash.txt")
            return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        }
    }
}
