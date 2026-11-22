package com.poolhelper.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Shows the saved crash report with copy-to-clipboard, restart and close.
 * Runs in a fresh process after the crash handler ends the crashed one.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Loop-guard timestamp first, before any UI work: if this activity
        // itself crashes within 30 s, the handler defers to the system.
        try {
            val dir = File(filesDir, "crash").also { it.mkdirs() }
            File(dir, "activity_opened.txt").writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}

        val report = CrashHandler.savedReport(this) ?: "No crash report found."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }
        root.addView(TextView(this).apply {
            text = "Pool Helper crashed"
            textSize = 22f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "The details below are also saved on this phone. Copy them and send them with a bug report."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        val reportView = TextView(this).apply {
            text = report
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(reportView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "Copy crash details"
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Pool Helper crash report", report))
                Toast.makeText(this@CrashActivity, "Crash details copied", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Restart Pool Helper"
            setOnClickListener {
                startActivity(
                    Intent(this@CrashActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
                finish()
            }
        })
        root.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    companion object {
        const val EXTRA_FROM_HANDLER = "from_crash_handler"
    }
}
