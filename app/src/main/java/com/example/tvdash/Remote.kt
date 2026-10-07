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
import android.net.Network
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoteService.ensureRunning(context)
    }
}

/**
 * Tiny HTTP server the phone talks to (/apps /launch /vol /power /key /text /yt), every request guarded by ?pin=.
 *
 * Runs as a foreground service, so Android keeps its process at high priority and restarts it after a kill
 * (START_STICKY). The listener never gives up: if its socket breaks it is reopened, and a health check every
 * 15 seconds (and on every network change) verifies the port really answers.
 */
class RemoteService : Service() {
    companion object {
        private const val TAG = "RemoteService"
        private const val HEALTH_MS = 15_000L

        /** Starts or confirms the foreground listener. Safe to call from anywhere, as often as needed. */
        fun ensureRunning(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the listener from the background", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** At most 4 requests at once and 16 waiting; anything more is refused, so memory use stays flat. */
    private val pool: ExecutorService = ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16))

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val healthTick = object : Runnable {
        override fun run() {
            runOffMain { checkHealth() }
            main.postDelayed(this, HEALTH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("remote", "Phone remote", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "remote")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phone remote is on").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (!running) {
            running = true
            thread(name = "remote-listener") { serveForever() }
            main.postDelayed(healthTick, HEALTH_MS)
            watchNetwork()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(healthTick)
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        runCatching { server?.close() }
        pool.shutdownNow()
        super.onDestroy()
    }

    /** Accept loop that never gives up: if the socket breaks it is reopened after a short, growing wait. */
    private fun serveForever() {
        var wait = 500L
        while (running) {
            try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(PORT))
                    server = s
                    wait = 500L
                    while (running) {
                        val c = s.accept()
                        try {
                            pool.execute { handle(c) }
                        } catch (e: RejectedExecutionException) {
                            runCatching { c.close() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "listener restarting", e)
                try { Thread.sleep(wait) } catch (ie: InterruptedException) { break }
                wait = (wait * 2).coerceAtMost(8_000L)
            }
        }
    }

    /** Confirms the port answers on this device and reopens it if not; also repairs a dropped accessibility service. */
    private fun checkHealth() {
        val alive = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 800); true }
        } catch (e: IOException) {
            false
        }
        if (!alive && running) {
            Log.w(TAG, "listener is not answering; reopening it")
            runCatching { server?.close() } // the accept loop sees the error and binds again
        }
        if (AccessGuard.state(this) == AccessState.DROPPED) AccessGuard.reEnable(this)
    }

    private fun watchNetwork() {
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { runOffMain { checkHealth() } }
            }
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback unavailable", e)
        }
    }

    private fun runOffMain(task: () -> Unit) {
        try {
            pool.execute {
                try { task() } catch (t: Throwable) { Log.w(TAG, "background task failed", t) }
            }
        } catch (e: RejectedExecutionException) {
            // Busy or shutting down: the next tick will try again.
        }
    }

    /** Reads one request line, refusing anything longer than [max] so a bad client cannot use up memory. */
    private fun readLineCapped(r: BufferedReader, max: Int = 4096): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = r.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= max) throw IOException("request line too long")
            sb.append(ch.toChar())
        }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 3000
            val r = c.getInputStream().bufferedReader()
            val line = readLineCapped(r) ?: return
            var headers = 0
            while (headers++ < 64 && !readLineCapped(r).isNullOrEmpty()) { /* skip the request headers */ }
            val uri = Uri.parse("http://tv" + (line.split(" ").getOrNull(1) ?: "/"))
            val authorised = uri.getQueryParameter("pin") == pin(this)
            var status = if (authorised) "200 OK" else "403 Forbidden"
            val text = if (!authorised) "" else try {
                route(uri)
            } catch (e: Exception) {
                Log.w(TAG, "command failed: ${uri.path}", e)
                status = "500 Internal Server Error"
                "error"
            }
            val body = text.toByteArray()
            val head = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        } catch (e: Exception) {
            // A broken or hostile connection must never take the listener down.
        } finally {
            runCatching { c.close() }
        }
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
        "/key" -> key(u.getQueryParameter("k") ?: "")
        "/text" -> typeOnTv(
            u.getQueryParameter("t") ?: "", u.getQueryParameter("enter") == "1", u.getQueryParameter("done") == "1"
        )
        "/launch" -> { launch(u.getQueryParameter("pkg") ?: ""); "ok" }
        "/yt" -> youtube(u)
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

    /** Remote buttons. Back and arrows/OK use accessibility actions (D-pad needs Android 13+); ff/rew are media keys. */
    private fun key(k: String): String {
        val media = when (k) {
            "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> 0
        }
        if (media != 0) {
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, media))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, media))
            return "ok"
        }
        val svc = PowerAccessibilityService.instance
        when (k) {
            "home" -> {
                wake()
                if (Settings.canDrawOverlays(this)) dashboard()
                else if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                else return "no-a11y"
            }
            "settings" -> {
                if (!Settings.canDrawOverlays(this)) return "no-overlay"
                wake()
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> {
                val action = when (k) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "up" -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                    "down" -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                    "left" -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                    "right" -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                    "ok" -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                    else -> return "ok"
                }
                if (svc == null) return "no-a11y"
                svc.performGlobalAction(action)
            }
        }
        return "ok"
    }

    private fun dashboard() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Text from the phone: first through the TV Dash keyboard (works in any app), then via accessibility. */
    private fun typeOnTv(text: String, enter: Boolean, done: Boolean): String {
        val ime = RemoteKeyboardService.instance
        if (ime != null && ime.type(text, enter, done)) return "ok"
        if (done) return "ok"
        val a11y = PowerAccessibilityService.instance
        if (a11y != null && a11y.typeText(text, enter)) return "ok"
        // ime == null means TV Dash is not the active keyboard yet, so that is the likely reason
        return if (ime == null) "no-keyboard" else "no-field"
    }

    private var lastSearchId = 0L

    /**
     * Opens YouTube search results for the phone's text. The phone repeats a request when an answer is lost, so a
     * repeated id means "already opened": answer ok without opening a second search.
     */
    @Synchronized
    private fun youtube(u: Uri): String {
        val id = u.getQueryParameter("id")?.toLongOrNull() ?: 0L
        if (id != 0L && id == lastSearchId) return "ok"
        if (!Settings.canDrawOverlays(this)) return "no-overlay"
        wake()
        val result = YouTubeLauncher(this).search(u.getQueryParameter("q") ?: "", PowerAccessibilityService.instance ?: this)
        if (result == YtResult.LAUNCHED && id != 0L) lastSearchId = id
        return result.code
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

/** Next unfinished setup step for the phone remote. Each tap on the dashboard card moves one step along. */
fun setupStep(ctx: Context) {
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    val keyboardOn = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
    val action = when {
        PowerAccessibilityService.instance == null -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        !Settings.canDrawOverlays(ctx) -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        !keyboardOn -> Settings.ACTION_INPUT_METHOD_SETTINGS
        else -> { imm.showInputMethodPicker(); return }
    }
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
