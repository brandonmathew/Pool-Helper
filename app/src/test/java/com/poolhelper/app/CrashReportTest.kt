package com.poolhelper.app

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    @Test
    fun reportContainsMetadataAndFullStackTrace() {
        val cause = IllegalStateException("root cause")
        val error = RuntimeException("wrapper failure", cause)
        val text = CrashReport.format(
            timestamp = "2026-11-21 14:00:00 -1000",
            appVersion = "1.0.0-beta.3 (code 9)",
            androidRelease = "15",
            apiLevel = 35,
            device = "Google Pixel 9",
            threadName = "test-thread",
            error = error,
        )
        assertTrue(text.contains("Pool Helper crash report"))
        assertTrue(text.contains("1.0.0-beta.3 (code 9)"))
        assertTrue(text.contains("Android: 15 (API 35)"))
        assertTrue(text.contains("Device: Google Pixel 9"))
        assertTrue(text.contains("Thread: test-thread"))
        assertTrue(text.contains("java.lang.RuntimeException: wrapper failure"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: root cause"))
    }

    @Test
    fun reportIncludesStackFrames() {
        val text = CrashReport.format(
            "t", "v", "15", 35, "d", "main",
            RuntimeException("boom"),
        )
        assertTrue(text.contains("CrashReportTest"))
    }
}
