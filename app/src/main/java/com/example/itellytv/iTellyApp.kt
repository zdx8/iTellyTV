package com.example.itellytv

import android.app.Application
import android.util.Log
import com.example.itellytv.player.Diagnostics
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * iTellyApp — application entry point.
 *
 * On debug builds we run the offline regression checks (mirrors the
 * iTelly-macOS `--diagnise` self-test). The result is logged; a CI
 * script can grep the log for "[FAIL]" to fail the build.
 *
 * Also installs a global uncaught-exception handler so the next
 * black-screen-on-launch report we get from a user actually
 * contains the stack trace in logcat. Without this the box just
 * logs the crash to system_server, the user sees "已停止运行",
 * and we have nothing to work with.
 */
class iTellyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        logPriorCrashIfAny()
        stageSamplePlaylist()
        if (BuildConfig.DEBUG) {
            Log.i("iTellyTV", Diagnostics.dumpConfig())
            try {
                val summary = Diagnostics.runOfflineRegressionChecks()
                Log.i("iTellyTV", summary.toText())
            } catch (t: Throwable) {
                // Don't crash the app over a failed diag — the macOS
                // --diagnose script also runs separately and would
                // catch a regression. Here we just want to log.
                Log.e("iTellyTV", "Diagnostics failed at startup", t)
            }
        }
    }

    /**
     * If a previous run left a [CrashLog] file, dump it to logcat at
     * ERROR level so a `adb logcat` capture from the next start will
     * contain the trace even if the device never gets `adb pull`'d
     * to retrieve the file directly.
     */
    private fun logPriorCrashIfAny() {
        try {
            val file = java.io.File(getExternalFilesDir(null), CrashLog.FILE_NAME)
            if (file.exists() && file.length() > 0) {
                Log.e("iTellyTV.Crash", "Last run crashed. Trace:")
                file.useLines { lines ->
                    lines.forEach { Log.e("iTellyTV.Crash", "  $it") }
                }
            }
        } catch (t: Throwable) {
            Log.w("iTellyTV", "Failed to read prior crash log: ${t.message}")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e(
                    "iTellyTV.Crash",
                    "Uncaught exception on thread ${thread.name}: " +
                        System.lineSeparator() + sw.toString()
                )
                CrashLog.write(this, "uncaught", throwable)
            } catch (_: Throwable) {
                // Never let our crash handler itself crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun stageSamplePlaylist() {
        val target = File(filesDir, "samples/sample.m3u")
        if (target.exists()) return
        target.parentFile?.mkdirs()
        try {
            assets.open("samples/sample.m3u").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i("iTellyTV", "Staged sample playlist: ${target.absolutePath}")
        } catch (e: Exception) {
            Log.e("iTellyTV", "Failed to stage sample playlist", e)
        }
    }
}
