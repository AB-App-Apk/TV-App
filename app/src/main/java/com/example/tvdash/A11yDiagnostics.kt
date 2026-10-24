package com.example.tvdash

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only evidence for diagnosing why the Accessibility Service will not stay enabled.
 * This reports OS observations; it does not change permissions or attempt recovery.
 */
object A11yDiagnostics {
    // Android's app-op for the user-controlled "Allow restricted settings" protection.
    private const val RESTRICTED_OP = "android:access_restricted_settings"

    fun summary(ctx: Context): String = listOf(
        "Restricted settings app-op: ${restrictedSettings(ctx)}",
        "Install source: ${installSource(ctx)}",
        "Most recent recorded app process exit:\n${lastProcessExit(ctx)}",
        "Self-enable permission: ${if (AccessGuard.canSelfHeal(ctx)) "granted" else "not granted"}"
    ).joinToString("\n")

    private fun restrictedSettings(ctx: Context): String {
        // Android's restricted-settings protection was introduced in Android 13.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "not applicable (Android below 13)"
        }
        return try {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(RESTRICTED_OP, Process.myUid(), ctx.packageName)
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> "ALLOWED"
                AppOpsManager.MODE_IGNORED -> "IGNORED (possible restricted-settings block)"
                AppOpsManager.MODE_ERRORED -> "ERRORED (access denied)"
                AppOpsManager.MODE_DEFAULT -> "DEFAULT (not conclusive)"
                else -> "unknown mode $mode"
            }
        } catch (e: Exception) {
            "unknown (${e.javaClass.simpleName})"
        }
    }

    private fun installSource(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown (requires Android 11+)"
        return try {
            val info = ctx.packageManager.getInstallSourceInfo(ctx.packageName)
            val installer = info.installingPackageName ?: "none"
            val initiating = info.initiatingPackageName
            if (initiating != null && initiating != installer) {
                "$installer (initiated by $initiating)"
            } else {
                installer
            }
        } catch (e: Exception) {
            "unknown (${e.javaClass.simpleName})"
        }
    }

    private fun lastProcessExit(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown (requires Android 11+)"
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
                ?: return "none recorded by Android"

            val reason = when (last.reason) {
                ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
                ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
                ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "killed, low memory"
                ApplicationExitInfo.REASON_CRASH -> "crashed (Java)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "user/system requested stop"
                ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped app"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
                ApplicationExitInfo.REASON_OTHER -> "other/system reason"
                else -> "reason code ${last.reason}"
            }
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS z",
                Locale.getDefault()
            ).format(Date(last.timestamp))
            val description = last.description
                ?.replace(Regex("\\s+"), " ")
                ?.takeIf { it.isNotBlank() }
                ?.take(240)
                ?: "not provided by Android"

            "Reason: $reason\n" +
                "Time: $timestamp\n" +
                "Process: ${last.processName} (PID ${last.pid}), status ${last.status}\n" +
                "System detail: $description"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }
    }
}
