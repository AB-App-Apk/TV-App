package com.example.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val BASE = "https://github.com/AB-App-Apk/TV-App/releases/download/latest/"
private const val ACTION = "com.example.updater.RESULT"

private fun open(name: String) =
    (URL(BASE + name).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }

/** Checks the GitHub "latest" release and installs the newer APK. Shared by TvDash and TvRemote. */
class UpdateController(private val ctx: Context, private val apkName: String) {
    var status by mutableStateOf("")
    var latest by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    val installed: Int =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    val available: Boolean get() = latest > installed

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                        ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                PackageInstaller.STATUS_SUCCESS -> status = "Updated. Reopen the app."
                else -> status = "Install failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() { runCatching { ctx.unregisterReceiver(receiver) } }

    suspend fun check() {
        busy = true
        status = "Checking for updates..."
        val v = withContext(Dispatchers.IO) {
            try { open("version.txt").inputStream.bufferedReader().readText().trim().toInt() } catch (e: Exception) { null }
        }
        busy = false
        if (v == null) { status = "Can't check for updates (is the GitHub repo public?)"; return }
        latest = v
        status = if (v > installed) "Update available: build $v. Select to install." else "Up to date"
    }

    suspend fun update() {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "Allow 'Install unknown apps' for this app, then select Update again."
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "update.apk")
                val c = open(apkName)
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val total = c.contentLength.toLong()
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) status = "Downloading ${done * 100 / total}%"
                        }
                    }
                }
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    f.inputStream().use { i -> s.openWrite("update", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                    val pending = PendingIntent.getBroadcast(
                        ctx, id, Intent(ACTION).setPackage(ctx.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    s.commit(pending.intentSender)
                }
            }
            status = "Installing... confirm if asked."
        } catch (e: Exception) {
            status = "Update failed: ${e.message}"
        }
        busy = false
    }
}
