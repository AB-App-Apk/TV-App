package com.example.tvdash

import android.app.AppOpsManager
import android.net.Uri
import android.content.ComponentCallbacks2
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
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
import androidx.compose.ui.focus.focusProperties
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
    private companion object {
        const val TAG = "MainActivity"
        const val ACTION_INSTALL_COMPLETE = "com.example.tvdash.INSTALL_COMPLETE"
        const val PREF_BLUETOOTH_PERMISSION_REQUESTED = "bluetooth_connect_permission_requested"
    }

    private var restrictedSettingsPrompted = false
    private var permissionOverlayDismissed = false
    private var showBluetoothPermissionRationale by mutableStateOf(false)
    private var bluetoothPermissionPermanentlyDenied by mutableStateOf(false)

    /** Lets TV Dash accept the phone's Bluetooth link. Android 12+ shows this as "Nearby devices". */
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) permissionOverlayDismissed = false
        refreshBluetoothPermission(showOverlay = !granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, RemoteService::class.java))
        refreshBluetoothPermission(showOverlay = true)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize()
                            .focusProperties { canFocus = !showBluetoothPermissionRationale }
                    ) { HomeScreen() }
                    if (showBluetoothPermissionRationale) {
                        BluetoothPermissionRationaleOverlay(
                            permanentlyDenied = bluetoothPermissionPermanentlyDenied,
                            onAllow = { requestBluetoothPermission() },
                            onOpenSettings = { openHiddenSystemSettings() },
                            onContinueWithoutBluetooth = {
                                permissionOverlayDismissed = true
                                showBluetoothPermissionRationale = false
                            }
                        )
                    }
                }
            }
        }

        // Retain compatibility with direct status intents, but never launch nested intents
        // from this exported launcher activity. The session installer uses a private receiver.
        handleInstallCompletion(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallCompletion(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshBluetoothPermission(showOverlay = true)
        checkAndUnlockAccessibilityProfile()
    }

    private fun refreshBluetoothPermission(showOverlay: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothPermissionPermanentlyDenied = false
            showBluetoothPermissionRationale = false
            return
        }

        val bluetoothPermissionGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (bluetoothPermissionGranted) {
            bluetoothPermissionPermanentlyDenied = false
            showBluetoothPermissionRationale = false
            return
        }

        val requestedBefore = getSharedPreferences("tvdash", Context.MODE_PRIVATE)
            .getBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, false)
        bluetoothPermissionPermanentlyDenied = requestedBefore &&
            !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        if (showOverlay && !permissionOverlayDismissed) {
            showBluetoothPermissionRationale = true
        }
    }

    private fun requestBluetoothPermission() {
        if (bluetoothPermissionPermanentlyDenied) {
            openHiddenSystemSettings()
            return
        }
        getSharedPreferences("tvdash", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_BLUETOOTH_PERMISSION_REQUESTED, true)
            .apply()
        askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun checkAndUnlockAccessibilityProfile() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        restrictedSettingsPrompted
    ) return

    val appOps = getSystemService(AppOpsManager::class.java) ?: return

    val mode = try {
    appOps.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), packageName)
     } catch (e: Exception) {
    Log.w(TAG, "Unable to check restricted-settings state", e)
    return
     }
    if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
    restrictedSettingsPrompted = true
    openHiddenSystemSettings()
   }
}

    private fun openHiddenSystemSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch application details settings intent", e)
        }
    }

    /**
     * Handles legacy direct installer status intents using bounded, non-blocking logging.
     * User-confirmation intents are handled only by the non-exported InstallStatusReceiver:
     * this exported launcher activity must never trust and launch a nested intent supplied
     * through an externally forgeable activity action.
     */
    private fun handleInstallCompletion(callback: Intent) {
        if (callback.action != ACTION_INSTALL_COMPLETE) return

        val status = callback.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            Int.MIN_VALUE
        )
        val message = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?.take(512)
            ?: "No status message supplied by PackageInstaller."

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package installation completed successfully.")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(
                    TAG,
                    "Package installation requires user confirmation; the private receiver handles the confirmation UI."
                )
            }

            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "Package installation failed (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w(TAG, "Package installation was aborted (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "Package installation was blocked (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "Package installation conflicted with an installed package (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "Package is incompatible with this device (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "PackageInstaller rejected the package (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Insufficient storage for package installation (status=$status): $message")
            }

            else -> {
                Log.w(TAG, "PackageInstaller returned status=$status: $message")
            }
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
        // Re-check app tiles only. Accessibility recovery remains user-triggered, so resuming
        // from Settings cannot silently rewrite secure system settings or reopen a settings loop.
        reload++
    }
    LaunchedEffect(Trim.level) { if (Trim.level > 0) apps = emptyList() }
    val access = AccessGuard.state(ctx)
    LaunchedEffect(reload) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }

    val status by produceState(readStatus(ctx)) {
        while (true) { delay(1000); value = readStatus(ctx) }
    }

    // The first time, the certificate is made here or in the listener, whichever gets there first: off the main thread.
    val securityCode by produceState("Preparing...") {
        value = withContext(Dispatchers.IO) { runCatching { TvTlsStore.shortCode(ctx) }.getOrDefault("Unavailable") }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        if (access != AccessState.OK) item(span = { GridItemSpan(maxLineSpan) }) {
            val baseMessage = when (access) {
                AccessState.CONNECTING -> "Waiting for Android to start the accessibility service. Select to open its settings if it stays stuck."
                AccessState.DROPPED -> "Android stopped the accessibility service. Select to try recovery."
                else -> "Accessibility is off. Enable TV Dash in Accessibility settings. If Android turns the switch off again, allow restricted settings from TV Dash app info first, if that option is available."
            }
            DashCard(
                "Remote control",
                when (access) { AccessState.CONNECTING -> "Starting..."; AccessState.DROPPED -> "Paused"; else -> "Off" },
                baseMessage,
                Modifier.fillMaxWidth()
            ) { AccessGuard.recover(ctx) }
        }
        if (access == AccessState.OFF) item(span = { GridItemSpan(maxLineSpan) }) {
            DashCard(
                "Restricted settings",
                "Open TV Dash app info",
                "On Android 13+: open the ⋮ menu and choose Allow restricted settings, if available. Then return and enable TV Dash under Accessibility.",
                Modifier.fillMaxWidth()
            ) { AccessGuard.openAppInfoScreen(ctx) }
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
            DashCard(
                "Security code",
                securityCode,
                "The phone shows this same code when you connect. If it is different, do not continue.",
                Modifier.fillMaxWidth()
            ) { }
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
