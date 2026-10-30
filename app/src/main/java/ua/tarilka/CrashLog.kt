package ua.tarilka

import android.content.Context
import java.io.File

/**
 * If the app crashes, a short technical description (the error and where in the code) is kept on the phone.
 * Next time the app offers to send it with feedback; the person sees the whole text first.
 * No diary data: only the error's type and code locations, the app and Android versions.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(context.filesDir, FILE).writeText(describe(context, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Keeps an error that was caught (the app goes on), the same way as a crash. */
    fun record(context: Context, e: Throwable) {
        runCatching { File(context.filesDir, FILE).writeText(describe(context, e)) }
    }

    /** Type and stack lines only: exception messages can contain entered text, so they are left out. */
    private fun describe(context: Context, e: Throwable): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Tarilka $version · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 3) {
            appendLine((if (depth == 0) "" else "Caused by: ") + t.javaClass.name)
            t.stackTrace.take(12).forEach { appendLine("  at $it") }
            t = t.cause
            depth++
        }
    }.take(3000)

    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
