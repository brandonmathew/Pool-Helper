package com.poolhelper.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

enum class UpdateChannel { STABLE, BETA }

sealed class UpdateStatus {
    data class Checking(val message: String = "Checking for updates") : UpdateStatus()
    data class UpToDate(val message: String) : UpdateStatus()
    data class Downloading(val version: String, val percent: Int) : UpdateStatus()
    data class ReadyToInstall(val version: String, val notes: String, val apk: File) : UpdateStatus()
    data class Failed(val message: String) : UpdateStatus()
}

/**
 * In-app auto-update (locked design):
 * - Checks GitHub Releases itself; downloads the APK in-app with its own
 *   downloader. NOT Android DownloadManager, no browser.
 * - SHA-256 verified against the .sha256 asset published with the release.
 * - Channel switch: STABLE = latest non-prerelease, BETA = latest prerelease.
 * - Install is the system installer prompt (one tap); everything before it
 *   is automatic.
 */
object AppUpdater {

    private const val RELEASES_API =
        "https://api.github.com/repos/brandonmathew/Pool-Helper/releases?per_page=30"
    private val executor = Executors.newSingleThreadExecutor()

    fun installedVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }

    /** Compare semver-ish names: "2.0.0-beta.3" > "2.0.0-beta.2", release > its betas. */
    fun compareVersions(a: String, b: String): Int {
        fun parse(v: String): Pair<List<Int>, List<String>> {
            val clean = v.removePrefix("v")
            val parts = clean.split("-", limit = 2)
            val core = parts[0].split(".").map { it.toIntOrNull() ?: 0 }
            val pre = if (parts.size > 1) parts[1].split(".") else emptyList()
            return core to pre
        }
        val (coreA, preA) = parse(a)
        val (coreB, preB) = parse(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val x = coreA.getOrElse(i) { 0 }
            val y = coreB.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        if (preA.isEmpty() && preB.isEmpty()) return 0
        if (preA.isEmpty()) return 1 // release beats prerelease
        if (preB.isEmpty()) return -1
        for (i in 0 until maxOf(preA.size, preB.size)) {
            if (i >= preA.size) return -1
            if (i >= preB.size) return 1
            val x = preA[i]
            val y = preB[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun check(context: Context, channel: UpdateChannel, autoDownload: Boolean = true, callback: (UpdateStatus) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            callback(UpdateStatus.Checking())
            try {
                val releases = JSONArray(get(RELEASES_API))
                val release = pickRelease(releases, channel)
                    ?: run {
                        callback(UpdateStatus.UpToDate("No ${channel.name.lowercase()} release published yet"))
                        return@execute
                    }
                val tag = release.optString("tag_name")
                val latest = tag.removePrefix("v")
                val current = installedVersion(appContext)
                val cmp = compareVersions(latest, current)
                when {
                    cmp == 0 -> callback(UpdateStatus.UpToDate("Up to date ($current)"))
                    cmp < 0 -> callback(
                        UpdateStatus.UpToDate(
                            if (channel == UpdateChannel.STABLE)
                                "Installed $current is newer than the latest stable $latest. " +
                                    "A beta cannot be replaced by an older stable; the next stable will install normally."
                            else "Installed $current is newer than the latest beta $latest"
                        )
                    )
                    else -> {
                        if (!autoDownload) {
                            callback(UpdateStatus.UpToDate("Update available: $latest (auto-download off)"))
                            return@execute
                        }
                        val assets = release.optJSONArray("assets") ?: JSONArray()
                        var apkUrl: String? = null
                        var shaUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            if (name.endsWith(".apk")) apkUrl = asset.optString("browser_download_url")
                            if (name.endsWith(".sha256")) shaUrl = asset.optString("browser_download_url")
                        }
                        if (apkUrl == null) {
                            callback(UpdateStatus.Failed("Release $tag has no APK asset"))
                            return@execute
                        }
                        val dir = File(appContext.cacheDir, "updates").also { it.mkdirs() }
                        val apkFile = File(dir, "PoolHelper-$latest.apk")
                        download(apkUrl, apkFile) { pct -> callback(UpdateStatus.Downloading(latest, pct)) }

                        if (shaUrl != null) {
                            val expected = get(shaUrl).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                            val actual = sha256(apkFile)
                            if (expected == null || expected != actual) {
                                apkFile.delete()
                                callback(UpdateStatus.Failed("SHA-256 mismatch for $latest; download discarded"))
                                return@execute
                            }
                        }
                        callback(
                            UpdateStatus.ReadyToInstall(
                                latest,
                                release.optString("body").take(2000),
                                apkFile,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                callback(UpdateStatus.Failed("Update check failed: ${e.message}"))
            }
        }
    }

    private fun pickRelease(releases: JSONArray, channel: UpdateChannel): JSONObject? {
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val pre = r.optBoolean("prerelease")
            if (channel == UpdateChannel.STABLE && !pre) return r
            if (channel == UpdateChannel.BETA && pre) return r
        }
        return null
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "PoolHelper-Updater")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, dest: File, progress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "PoolHelper-Updater")
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var readTotal = 0L
                var n: Int
                var lastPct = -1
                while (input.read(buf).also { n = it } >= 0) {
                    if (n == 0) continue
                    output.write(buf, 0, n)
                    readTotal += n
                    if (total > 0) {
                        val pct = (readTotal * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(pct)
                        }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
