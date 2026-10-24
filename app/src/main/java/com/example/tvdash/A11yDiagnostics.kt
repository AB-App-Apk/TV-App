package com.example.tvdash

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only system diagnostics plus a small persistent trail of significant runtime events.
 * It never changes permissions or attempts recovery. Event details are short and contain no PINs.
 */
object A11yDiagnostics {
    // Android's app-op for the user-controlled "Allow restricted settings" protection.
    private const val RESTRICTED_OP = "android:access_restricted_settings"
    private const val EVENTS_PREFS = "a11y_diagnostics"
    private const val EVENTS_KEY = "recent_events"
    private const val MAX_EVENTS = 10
    private val eventLock = Any()

    fun summary(ctx: Context): String = listOf(
        "Diagnostics refreshed: ${timestampNow()}",
        "Accessibility master switch: ${masterSwitch(ctx)}",
        "TV Dash listed as enabled: ${serviceListed(ctx)}",
        "Accessibility service bound: ${if (PowerAccessibilityService.instance != null) "yes" else "no"}",
        "Restricted settings app-op: ${restrictedSettings(ctx)}",
        "Install source: ${installSource(ctx)}",
        "Recent runtime events (chronological):\n${recentEvents(ctx)}",
        "Most recent recorded app process exit:\n${lastProcessExit(ctx)}",
        "Self-enable permission: ${if (AccessGuard.canSelfHeal(ctx)) "granted" else "not granted"}"
    ).joinToString("\n")

    /**
     * Stores a bounded breadcrumb so the user can report service/listener transitions without logcat.
     * SharedPreferences.apply() avoids blocking Android's lifecycle callback while writing this tiny record.
     */
    fun recordEvent(ctx: Context, event: String, detail: String = "") {
        try {
            synchronized(eventLock) {
                val prefs = ctx.applicationContext.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE)
                val old = runCatching {
                    JSONArray(prefs.getString(EVENTS_KEY, "[]") ?: "[]")
                }.getOrDefault(JSONArray())
                val now = System.currentTimeMillis()
                val last = old.optJSONObject(old.length() - 1)
                val cleanDetail = detail.replace(Regex("\\s+"), " ").take(120)

                // Avoid filling the short trail with the same rapidly repeating listener error.
                if (last != null &&
                    last.optString("event") == event &&
                    last.optString("detail") == cleanDetail &&
                    now - last.optLong("time") < 15_000L
                ) return

                val start = (old.length() - MAX_EVENTS + 1).coerceAtLeast(0)
                val next = JSONArray()
                for (i in start until old.length()) {
                    old.optJSONObject(i)?.let { next.put(it) }
                }
                next.put(
                    JSONObject()
                        .put("time", now)
                        .put("pid", Process.myPid())
                        .put("event", event.take(48))
                        .put("detail", cleanDetail)
                )
                prefs.edit().putString(EVENTS_KEY, next.toString()).apply()
            }
        } catch (_: Exception) {
            // Diagnostics must never disrupt the accessibility or listener service.
        }
    }

    private fun recentEvents(ctx: Context): String {
        return try {
            val prefs = ctx.applicationContext.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE)
            val events = JSONArray(prefs.getString(EVENTS_KEY, "[]") ?: "[]")
            if (events.length() == 0) return "none recorded yet"

            val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            val start = (events.length() - 6).coerceAtLeast(0)
            (start until events.length()).mapNotNull { i ->
                events.optJSONObject(i)?.let { event ->
                    val time = formatter.format(Date(event.optLong("time")))
                    val pid = event.optInt("pid")
                    val name = event.optString("event", "unknown")
                    val detail = event.optString("detail").takeIf { it.isNotBlank() }
                    "$time pid=$pid $name" + (detail?.let { " — $it" } ?: "")
                }
            }.joinToString("\n").ifBlank { "none recorded yet" }
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }
    }

    private fun timestampNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))

    private fun masterSwitch(ctx: Context): String = try {
        if (Settings.Secure.getInt(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
        ) "ON" else "OFF"
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

    /** Checks the per-service list independently of the master switch and live service binding. */
    private fun serviceListed(ctx: Context): String = try {
        val raw = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return "no (service list empty)"
        val expected = ComponentName(ctx, PowerAccessibilityService::class.java)
        if (raw.split(':').any { ComponentName.unflattenFromString(it) == expected }) "yes" else "no"
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

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
