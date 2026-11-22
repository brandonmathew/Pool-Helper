package com.poolhelper.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var updateStatusView: TextView
    private lateinit var channelGroup: RadioGroup
    private var overlay: DebugOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlay = DebugOverlay(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Pool Helper"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Version ${AppUpdater.installedVersion(this@MainActivity)}"
            textSize = 13f
        })

        statusView = TextView(this).apply { textSize = 13f; setPadding(0, 24, 0, 24) }
        root.addView(statusView)

        root.addView(button("Start capture") { requestCapture() })
        root.addView(button("Stop capture") {
            startService(CaptureService.stopIntent(this))
        })
        root.addView(button("Toggle debug overlay") { toggleOverlay() })
        root.addView(button("Overlay permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("Accessibility settings (enable gestures)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Share saved frames") { shareFrames() })
        root.addView(button("Test crash handler") {
            Thread { throw RuntimeException("Test crash triggered from the main screen") }.start()
        })

        root.addView(TextView(this).apply {
            text = "Update channel"
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })
        channelGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply { id = ID_STABLE; text = "Stable" })
            addView(RadioButton(this@MainActivity).apply { id = ID_BETA; text = "Beta" })
            check(if (savedChannel() == UpdateChannel.BETA) ID_BETA else ID_STABLE)
            setOnCheckedChangeListener { _, _ ->
                // Switching channel re-checks immediately (locked behaviour).
                checkUpdate()
            }
        }
        root.addView(channelGroup)

        updateStatusView = TextView(this).apply { textSize = 13f; setPadding(0, 8, 0, 8) }
        root.addView(updateStatusView)
        root.addView(button("Check for update") { checkUpdate() })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { onClick() }
        }

    private fun refreshStatus() {
        val profile = CalibrationStore.load(this)
        val a11y = if (AccessibilityGestureExecutor().isReady()) "enabled" else "not enabled"
        statusView.text = buildString {
            appendLine("Capture: ${CaptureStats.summary()}")
            appendLine("Accessibility gestures: $a11y")
            appendLine("Overlay permission: ${if (Settings.canDrawOverlays(this@MainActivity)) "granted" else "not granted"}")
            appendLine(
                "Calibration: physics=${"%.2f".format(profile.physicsConfidence)} " +
                    "aim=${"%.2f".format(profile.aimConfidence)} " +
                    "power=${"%.2f".format(profile.powerConfidence)} " +
                    if (profile.isCalibrated()) "(calibrated)" else "(auto-calibrates when uncalibrated)"
            )
        }
    }

    /** Zip the saved sample frames and open the share sheet with the zip. */
    private fun shareFrames() {
        val dir = java.io.File(filesDir, "frames")
        val frames = dir.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        if (frames.isEmpty()) {
            Toast.makeText(this, "No saved frames yet — run capture in the game first", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val shareDir = java.io.File(cacheDir, "share").also { it.mkdirs() }
                val zipFile = java.io.File(shareDir, "pool-helper-frames.zip")
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zip ->
                    for (f in frames) {
                        zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                        java.io.FileInputStream(f).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", zipFile,
                )
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share saved frames"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(CaptureService.startIntent(this, resultCode, data))
                Toast.makeText(this, "Capture started. Open 8 Ball Pool.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Capture consent declined", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleOverlay() {
        val ov = overlay ?: return
        if (ov.isShown()) {
            ov.hide()
        } else if (!ov.show()) {
            Toast.makeText(this, "Overlay permission needed", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun savedChannel(): UpdateChannel {
        val name = getSharedPreferences("updater", MODE_PRIVATE).getString("channel", "STABLE")
        return if (name == "BETA") UpdateChannel.BETA else UpdateChannel.STABLE
    }

    private fun selectedChannel(): UpdateChannel {
        val channel = if (channelGroup.checkedRadioButtonId == ID_BETA) UpdateChannel.BETA else UpdateChannel.STABLE
        getSharedPreferences("updater", MODE_PRIVATE).edit().putString("channel", channel.name).apply()
        return channel
    }

    private fun checkUpdate() {
        val channel = selectedChannel()
        AppUpdater.check(this, channel) { status ->
            runOnUiThread {
                updateStatusView.text = when (status) {
                    is UpdateStatus.Checking -> status.message
                    is UpdateStatus.UpToDate -> status.message
                    is UpdateStatus.Downloading -> "Downloading ${status.version}: ${status.percent}%"
                    is UpdateStatus.Failed -> status.message
                    is UpdateStatus.ReadyToInstall -> {
                        Toast.makeText(this, "Update ${status.version} downloaded and verified", Toast.LENGTH_LONG).show()
                        startActivity(AppUpdater.installIntent(this, status.apk))
                        "Update ${status.version} ready — complete the install in the system prompt"
                    }
                }
            }
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val ID_STABLE = 2001
        private const val ID_BETA = 2002
    }
}
