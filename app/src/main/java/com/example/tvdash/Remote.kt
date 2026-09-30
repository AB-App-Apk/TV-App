package com.example.tvdash

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

const val PORT = 8765

fun pin(ctx: Context): String {
    val sp = ctx.getSharedPreferences("tvdash", Context.MODE_PRIVATE)
    return sp.getString("pin", null)
        ?: "%04d".format(SecureRandom().nextInt(10000)).also { sp.edit().putString("pin", it).apply() }
}

fun localIp(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress ?: "No network"
}

/** Enabled by the user in Settings > Accessibility. Lets us put the TV to sleep. */
class PowerAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: PowerAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun sleep() { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
    }
}

/** Tiny HTTP server the phone talks to: /apps /launch /vol /power, all guarded by ?pin= */
class RemoteService : Service() {
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("remote", "Phone remote", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "remote")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phone remote is on").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (!started) { started = true; thread { serve() } }
        return START_STICKY
    }

    private fun serve() {
        try {
            val s = ServerSocket(PORT)
            while (true) { val c = s.accept(); thread { handle(c) } }
        } catch (e: Exception) { started = false }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 3000
            val r = c.getInputStream().bufferedReader()
            val line = r.readLine() ?: return
            while (!r.readLine().isNullOrEmpty()) { }
            val uri = Uri.parse("http://tv" + (line.split(" ").getOrNull(1) ?: "/"))
            val ok = uri.getQueryParameter("pin") == pin(this)
            val body = (if (ok) route(uri) else "").toByteArray()
            val head = "HTTP/1.1 ${if (ok) "200 OK" else "403 Forbidden"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        } catch (e: Exception) {
        } finally { c.close() }
    }

    private fun route(u: Uri): String = when (u.path) {
        "/apps" -> JSONArray().also { a ->
            appList().forEach { (n, p) -> a.put(JSONObject().put("n", n).put("p", p)) }
        }.toString()
        "/vol" -> {
            val am = getSystemService(AudioManager::class.java)
            when (u.getQueryParameter("d")) {
                "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                else -> toggleMute(am)
            }
            "ok"
        }
        "/power" -> if (u.getQueryParameter("s") == "on") { wake(); "ok" }
                    else PowerAccessibilityService.instance?.let { it.sleep(); "ok" } ?: "no-a11y"
        "/launch" -> { launch(u.getQueryParameter("pkg") ?: ""); "ok" }
        else -> "ok"
    }

    /** Mute by setting volume to 0 and restoring it later; works even when the TV ignores stream mute. */
    private fun toggleMute(am: AudioManager) {
        val st = AudioManager.STREAM_MUSIC
        val sp = getSharedPreferences("tvdash", Context.MODE_PRIVATE)
        if (am.isStreamMute(st)) am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
        val cur = am.getStreamVolume(st)
        if (cur > 0) {
            sp.edit().putInt("prevVol", cur).apply()
            am.setStreamVolume(st, 0, AudioManager.FLAG_SHOW_UI)
        } else {
            val back = sp.getInt("prevVol", am.getStreamMaxVolume(st) / 3).coerceAtLeast(1)
            am.setStreamVolume(st, back, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "tvdash:wake")
            .acquire(3000)
    }

    private fun launch(pkg: String) {
        wake()
        val i = (packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        (PowerAccessibilityService.instance ?: this).startActivity(i)
    }

    private fun appList(): List<Pair<String, String>> {
        val pm = packageManager
        fun q(c: String) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(c), 0)
        return (q(Intent.CATEGORY_LEANBACK_LAUNCHER) + q(Intent.CATEGORY_LAUNCHER))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
    }
}

