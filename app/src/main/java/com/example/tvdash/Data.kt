package com.example.tvdash

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class AppEntry(val label: String, val pkg: String, val image: ImageBitmap)
data class Status(val time: LocalDateTime, val network: String, val storage: String)


fun openSettings(ctx: Context, action: String) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    fun query(cat: String) =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
    return (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            val banner = ri.activityInfo.loadBanner(pm)
            val bmp = if (banner != null) banner.toBitmap(320, 180)
                      else ri.loadIcon(pm).toBitmap(180, 180)
            AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, bmp.asImageBitmap())
        }
        .sortedBy { it.label.lowercase() }
}

fun readStatus(ctx: Context): Status {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val net = when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
    }
    val fs = StatFs(Environment.getDataDirectory().path)
    val freeGb = fs.availableBytes / 1_073_741_824.0
    return Status(LocalDateTime.now(), net, "%.1f GB".format(freeGb))
}
