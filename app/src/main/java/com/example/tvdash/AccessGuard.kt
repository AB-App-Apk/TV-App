package com.example.tvdash

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/** Where the accessibility service stands, as the dashboard shows it. */
enum class AccessState {
    /** Connected and working. */
    OK,

    /** Turned on in Settings, but Android has not bound it yet (normal for a few seconds after the app starts). */
    CONNECTING,

    /** Turned on in Settings but not bound for a while: Android stopped it (low memory or a crash). */
    DROPPED,

    /** Not turned on in Settings. */
    OFF,
}

/** True when Settings list [service] as turned on (and the master accessibility switch is on). */
fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val resolver = context.contentResolver
    if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
    val expected = ComponentName(context, service)
    val settingValue = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(settingValue)
    while (splitter.hasNext()) {
        // Compare components, not text: Settings may store "pkg/pkg.Class" or the short form "pkg/.Class".
        if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
    }
    return false
}

/** Detection, recovery and routing for the accessibility service. */
object AccessGuard {
    private const val TAG = "AccessGuard"
    private const val GRACE_MS = 20_000L
    private const val RETRY_GAP_MS = 60_000L

    private val processStart = SystemClock.elapsedRealtime()
    @Volatile private var lastRepair = 0L

    fun onConnected() { Log.i(TAG, "service connected") }
    fun onDisconnected() { Log.w(TAG, "service disconnected") }

    fun state(ctx: Context): AccessState = when {
        PowerAccessibilityService.instance != null -> AccessState.OK
        !isAccessibilityServiceEnabled(ctx, PowerAccessibilityService::class.java) -> AccessState.OFF
        SystemClock.elapsedRealtime() - processStart < GRACE_MS -> AccessState.CONNECTING
        else -> AccessState.DROPPED
    }

    /** One-time setup on a computer: adb shell pm grant com.example.tvdash android.permission.WRITE_SECURE_SETTINGS */
    fun canSelfHeal(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Turns the service off and on again in Settings, which makes Android rebind it even after a kill or crash.
     * Works only when canSelfHeal() is true. Automatic attempts are spaced a minute apart; [force] is for a user tap.
     */
    fun reEnable(ctx: Context, force: Boolean = false): Boolean {
        if (!canSelfHeal(ctx)) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRepair < RETRY_GAP_MS) return false
        lastRepair = now
        return try {
            val resolver = ctx.contentResolver
            val me = ComponentName(ctx, PowerAccessibilityService::class.java)
            val others = (Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val all = (others + me.flattenToString()).joinToString(":")
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all)
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "re-enable, second step failed", e)
                }
            }, 700)
            Log.i(TAG, "re-enabling the service")
            true
        } catch (e: Exception) {
            Log.w(TAG, "re-enable failed", e)
            false
        }
    }

    /** What a tap on the dashboard warning does: repair silently if allowed, otherwise open the exact screen. */
    fun recover(ctx: Context) {
        if (!reEnable(ctx, force = true)) openServiceScreen(ctx)
    }

    /** Opens this service's own settings page (Android 14+), or the general Accessibility list. */
    fun openServiceScreen(ctx: Context) {
        val me = ComponentName(ctx, PowerAccessibilityService::class.java)
        val screens = buildList {
            if (Build.VERSION.SDK_INT >= 34) {
                add(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me.flattenToString()))
            }
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        for (screen in screens) {
            if (runCatching { ctx.startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
