package de.flexy.pendel

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the stack trace of the last crash on the device (nothing is sent anywhere). Shown in the
 * settings so it can be copied into a bug report.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
                val info = "Pendel $version · Android ${Build.VERSION.RELEASE} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n${Instant.now()} · Thread ${thread.name}\n\n"
                File(app.filesDir, FILE).writeText(info + sw.toString().take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
