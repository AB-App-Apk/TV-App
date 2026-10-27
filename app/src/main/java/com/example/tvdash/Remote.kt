package com.example.tvdash

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.tvlink.TvProtocol
import com.example.tvlink.TvTls
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
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
 * Tiny HTTPS server for the phone remote. The Wi-Fi link is encrypted with TLS using TV Dash's own self-signed
 * certificate (see [TvTlsStore]); a client that does not speak TLS gets no answer. Every request is authenticated with
 * the X-TV-PIN header, which now travels inside the encrypted link.
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

    /** The Bluetooth data channel: the phone's requests arrive here when Wi-Fi is not available. */
    @Volatile private var btServer: BluetoothServerSocket? = null
    @Volatile private var btClient: BluetoothSocket? = null
    private val btPool: ExecutorService = Executors.newFixedThreadPool(2)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val healthTick = object : Runnable {
        override fun run() {
            runOffMain { checkHealth() }
            main.postDelayed(this, HEALTH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        A11yDiagnostics.recordEvent(this, "listener-start-request")
        Log.i(TAG, "lifecycle=onStartCommand pid=" + android.os.Process.myPid() + " startId=" + startId + " elapsedMs=" + android.os.SystemClock.elapsedRealtime())
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
            thread(name = "remote-bluetooth") { serveBluetoothForever() }
            main.postDelayed(healthTick, HEALTH_MS)
            watchNetwork()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        A11yDiagnostics.recordEvent(this, "listener-destroyed")
        Log.w(TAG, "lifecycle=onDestroy pid=" + android.os.Process.myPid() + " elapsedMs=" + android.os.SystemClock.elapsedRealtime())
        running = false
        main.removeCallbacks(healthTick)
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        runCatching { server?.close() }
        runCatching { btServer?.close() }
        runCatching { btClient?.close() }
        heldKeyWatchdog.close()
        btPool.shutdownNow()
        pool.shutdownNow()
        super.onDestroy()
    }

    /**
     * Accept loop that never gives up: if the socket breaks it is reopened after a short, growing wait. The listener is
     * a TLS server socket, and the certificate is made here the first time (a fraction of a second) before it binds.
     */
    private fun serveForever() {
        var wait = 500L
        while (running) {
            try {
                A11yDiagnostics.recordEvent(this, "tls-starting")
                Log.i(TAG, "TLS startup beginning pid=" + android.os.Process.myPid() + " elapsedMs=" + android.os.SystemClock.elapsedRealtime())
                val tls = TvTlsStore.serverContext(this)
                (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).use { s ->
                    s.reuseAddress = true
                    s.enabledProtocols = TvTls.enabledProtocols(s.supportedProtocols)
                    s.bind(InetSocketAddress(PORT))
                    server = s
                    wait = 500L
                    A11yDiagnostics.recordEvent(this, "tls-listener-up", "port $PORT")
                    Log.i(TAG, "HTTPS listener is up on port $PORT pid=" + android.os.Process.myPid() + " elapsedMs=" + android.os.SystemClock.elapsedRealtime())
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
                A11yDiagnostics.recordEvent(this, "tls-listener-error", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
                Log.e(TAG, "TLS/listener startup or accept loop failed; restarting pid=" + android.os.Process.myPid() + " elapsedMs=" + android.os.SystemClock.elapsedRealtime(), e)
                try { Thread.sleep(wait) } catch (ie: InterruptedException) { break }
                wait = (wait * 2).coerceAtMost(8_000L)
            }
        }
    }

    /** Confirms the port answers on this device and reopens it if not. */
    private fun checkHealth() {
        val alive = try {
            // Only the TCP connect is checked, so no TLS handshake is needed here.
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 800); true }
        } catch (e: IOException) {
            false
        }
        if (!alive && running) {
            Log.w(TAG, "listener is not answering; reopening it")
            runCatching { server?.close() } // the accept loop sees the error and binds again
        }
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

    /** Shared by Wi-Fi and Bluetooth, with independent attempt histories for each client. */
    private val pinLimiter = PinAttemptLimiter()

    /** Network-held media keys; unmatched releases are generated after the safety timeout. */
    private val heldKeyWatchdog = HeldKeyWatchdog(
        onDown = { key, downTime ->
            val keyCode = when (key) {
                com.example.tvlink.HeldRemoteKey.FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                com.example.tvlink.HeldRemoteKey.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
                // Accessibility global actions are instantaneous; they cannot represent a native held-key state.
                com.example.tvlink.HeldRemoteKey.BACK,
                com.example.tvlink.HeldRemoteKey.OK -> return@HeldKeyWatchdog "unsupported"
            }
            val event = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            getSystemService(AudioManager::class.java).dispatchMediaKeyEvent(event)
            "ok"
        },
        onUp = { key, downTime ->
            val keyCode = when (key) {
                com.example.tvlink.HeldRemoteKey.FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                com.example.tvlink.HeldRemoteKey.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
                com.example.tvlink.HeldRemoteKey.BACK,
                com.example.tvlink.HeldRemoteKey.OK -> return@HeldKeyWatchdog
            }
            val event = KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            getSystemService(AudioManager::class.java).dispatchMediaKeyEvent(event)
        }
    )

    /** One request line in, one answer out. Wi-Fi and Bluetooth keep the same protocol and denial response. */
    private fun answer(line: String, clientKey: String, suppliedPin: String?): Pair<String, String> {
        val uri = Uri.parse("http://tv" + (line.split(" ").getOrNull(1) ?: "/"))
        if (pinLimiter.authenticate(clientKey, suppliedPin, pin(this)) != PinAuthResult.ACCEPTED) {
            return "403 Forbidden" to ""
        }
        return try {
            "200 OK" to route(uri)
        } catch (e: Exception) {
            Log.w(TAG, "command failed: ${uri.path}", e)
            "500 Internal Server Error" to "error"
        }
    }

    private fun reply(out: OutputStream, status: String, text: String, keepOpen: Boolean) {
        val body = text.toByteArray()
        val head = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n" + (if (keepOpen) "" else "Connection: close\r\n") + "\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    /**
     * Wi-Fi: one request per connection. The TLS handshake comes first. A client that is not speaking TLS, or does not
     * finish the handshake within the read timeout, is dropped before any PIN is looked at, so it cannot use up PIN attempts.
     */
    private fun handle(c: Socket) {
        try {
            c.soTimeout = 3000
            (c as? SSLSocket)?.startHandshake()
            val r = c.getInputStream().bufferedReader()
            val line = readLineCapped(r) ?: return
            val suppliedPin = TvProtocol.readPinHeader { readLineCapped(r) }
            val (status, text) = answer(line, "wifi:${c.inetAddress?.hostAddress ?: "unknown"}", suppliedPin)
            reply(c.getOutputStream(), status, text, keepOpen = false)
        } catch (e: Exception) {
            // A broken or hostile connection must never take the listener down.
        } finally {
            runCatching { c.close() }
        }
    }

    /** Bluetooth: the phone keeps one link open and sends many requests on it. */
    private fun handleBluetooth(c: BluetoothSocket) {
        try {
            val r = c.inputStream.bufferedReader()
            val out = c.outputStream
            while (running) {
                val line = readLineCapped(r) ?: break // the phone closed the link
                val suppliedPin = TvProtocol.readPinHeader { readLineCapped(r) }
                val (status, text) = answer(line, "bluetooth:${c.remoteDevice.address}", suppliedPin)
                reply(out, status, text, keepOpen = true)
            }
        } catch (e: Exception) {
            Log.i(TAG, "Bluetooth client left: ${e.message}")
        } finally {
            runCatching { c.close() }
            if (btClient === c) btClient = null
        }
    }

    /**
     * Listens on a classic Bluetooth (RFCOMM) service so the phone can talk to TV Dash without Wi-Fi. It needs the
     * phone to be paired with the TV and, on Android 12+, the "Nearby devices" permission. Until both are in
     * place it checks again every 10 seconds, so nothing has to be restarted after the user allows it.
     */
    @SuppressLint("MissingPermission")
    private fun serveBluetoothForever() {
        var wait = 1_000L
        while (running) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                if (adapter == null || !allowed || !adapter.isEnabled) {
                    sleepQuietly(10_000L)
                    continue
                }
                adapter.listenUsingRfcommWithServiceRecord(TvProtocol.BT_SERVICE_NAME, TvProtocol.BT_UUID).use { listener ->
                    btServer = listener
                    wait = 1_000L
                    Log.i(TAG, "Bluetooth data channel is listening")
                    while (running) {
                        val client = listener.accept()
                        // Only one phone at a time: a new link replaces an old one that may be stale.
                        btClient?.let { runCatching { it.close() } }
                        btClient = client
                        try {
                            btPool.execute { handleBluetooth(client) }
                        } catch (e: RejectedExecutionException) {
                            runCatching { client.close() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "Bluetooth listener restarting", e)
                sleepQuietly(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* stopping */ }
    }

    private fun route(u: Uri): String = when (u.path) {
        "/hold" -> {
            val holdId = u.getQueryParameter("id") ?: return "bad-hold"
            val key = com.example.tvlink.HeldRemoteKey.fromWire(u.getQueryParameter("key")) ?: return "bad-hold"
            val action = com.example.tvlink.HeldAction.fromWire(u.getQueryParameter("action")) ?: return "bad-hold"
            heldKeyWatchdog.handle(holdId, key, action)
        }
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
