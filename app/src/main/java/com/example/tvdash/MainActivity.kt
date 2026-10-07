package com.example.tvdash

import android.content.ComponentCallbacks2
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
import androidx.core.content.ContextCompat
import com.example.updater.UpdateController
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, RemoteService::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { HomeScreen() }
        }
    }

    /** When the user leaves for a heavy app, give back the memory the app icons use; they reload on return. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Trim.level++
    }
}

object Trim { var level by mutableIntStateOf(0) }


@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val upd = remember { UpdateController(ctx, "TvDash.apk") }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { upd.start(); onDispose { upd.stop() } }
    LaunchedEffect(Unit) { upd.check() }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reload++
        if (AccessGuard.state(ctx) == AccessState.DROPPED) AccessGuard.reEnable(ctx)
    }
    LaunchedEffect(Trim.level) { if (Trim.level > 0) apps = emptyList() }
    val access = AccessGuard.state(ctx)
    LaunchedEffect(reload) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }

    val status by produceState(readStatus(ctx)) {
        while (true) { delay(1000); value = readStatus(ctx) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        if (access != AccessState.OK) item(span = { GridItemSpan(maxLineSpan) }) {
            DashCard(
                "Remote control",
                when (access) { AccessState.CONNECTING -> "Starting..."; AccessState.DROPPED -> "Paused"; else -> "Off" },
                when (access) {
                    AccessState.CONNECTING -> "Waiting for Android to start the accessibility service."
                    AccessState.DROPPED -> "Android stopped the accessibility service. Select to turn it back on."
                    else -> "Accessibility is off. Select to turn it on."
                },
                Modifier.fillMaxWidth()
            ) { AccessGuard.recover(ctx) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                DashCard("Time", status.time.format(DateTimeFormatter.ofPattern("h:mm a")),
                    status.time.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    Modifier.weight(1f)) { openSettings(ctx, Settings.ACTION_DATE_SETTINGS) }
                DashCard("Network", status.network, "Tap for settings", Modifier.weight(1f)) {
                    openSettings(ctx, Settings.ACTION_WIFI_SETTINGS)
                }
                DashCard("Storage", status.storage, "Free space", Modifier.weight(1f)) {
                    openSettings(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                }
                DashCard("Phone remote", "PIN ${pin(ctx)}", localIp(ctx), Modifier.weight(1f)) {
                    setupStep(ctx)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DashCard("Updates", "Build ${upd.installed}", upd.status.ifEmpty { "Select to check for updates" }, Modifier.fillMaxWidth()) {
                if (!upd.busy) scope.launch { if (upd.available) upd.update() else upd.check() }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        }
        items(apps, key = { it.pkg }) { app -> AppCard(app) }
    }
}

