package com.alalkipgen.alalpdf.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privacy-preserving, local-only crash diagnostics.
 *
 * The reader can disappear because of a Java exception, native PDFium crash or
 * Android's low-memory killer. Persisting the platform exit reason on the next
 * launch makes those cases distinguishable without adding INTERNET permission
 * or a telemetry SDK.
 */
object AppExitDiagnostics {
    private const val TAG = "AlalPdfExit"
    private const val PREFERENCES = "app_exit_diagnostics"
    private const val LAST_SEEN_EXIT = "last_seen_exit"
    private const val MAX_STACK_CHARS = 16_000
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        val appContext = context.applicationContext
        runCatching { capturePreviousExit(appContext) }
            .onFailure { Log.w(TAG, "Unable to read the previous exit reason", it) }
        if (!installed.compareAndSet(false, true)) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString().take(MAX_STACK_CHARS)
                writeReport(
                    appContext,
                    """
                    type=uncaught_exception
                    timestamp=${System.currentTimeMillis()}
                    thread=${thread.name}
                    exception=${error.javaClass.name}
                    message=${error.message.orEmpty()}

                    $stack
                    """.trimIndent(),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun capturePreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val latest = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .maxByOrNull(ApplicationExitInfo::getTimestamp)
        }.getOrNull() ?: return

        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (latest.timestamp <= preferences.getLong(LAST_SEEN_EXIT, 0L)) return

        val report = """
            type=historical_process_exit
            timestamp=${latest.timestamp}
            reason=${reasonName(latest.reason)}
            reasonCode=${latest.reason}
            status=${latest.status}
            importance=${latest.importance}
            pssKb=${latest.pss}
            rssKb=${latest.rss}
            process=${latest.processName.orEmpty()}
            description=${latest.description.orEmpty().replace('\n', ' ')}
        """.trimIndent()
        writeReport(context, report)
        preferences.edit().putLong(LAST_SEEN_EXIT, latest.timestamp).apply()
    }

    private fun writeReport(context: Context, report: String) {
        runCatching {
            val directory = File(context.filesDir, "diagnostics")
            if (!directory.exists()) directory.mkdirs()
            File(directory, "last-exit.txt").writeText(report)
        }.onFailure { error ->
            Log.w(TAG, "Unable to persist the local exit report", error)
        }
        Log.w(TAG, report)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled_native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown"
    }
}