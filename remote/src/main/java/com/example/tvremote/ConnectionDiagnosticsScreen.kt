package com.example.tvremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only diagnostics plus an explicit ping. The ping uses the same TV link/router as normal remote requests,
 * so it does not send a key or alter TV state.
 */
@Composable
fun ConnectionDiagnosticsScreen(graph: RemoteGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidState by graph.bluetooth.getConnectionState().collectAsStateWithLifecycle()
    val overallState by graph.connection.state.collectAsStateWithLifecycle()
    val hint by graph.connection.hint.collectAsStateWithLifecycle()

    val wifiStatus by produceState(initialValue = phoneWifiStatus(context)) {
        while (true) {
            delay(1_000L)
            value = phoneWifiStatus(context)
        }
    }
    val bluetoothStatus by produceState(initialValue = phoneBluetoothStatus(context)) {
        while (true) {
            delay(1_000L)
            value = phoneBluetoothStatus(context)
        }
    }

    val hostConfigured = remember { !graph.prefs.getString("host", "").isNullOrBlank() }
    val bluetoothTargetConfigured = remember { !graph.prefs.getString("bt_target", "").isNullOrBlank() }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("Not tested yet") }
    var lastTestPath by remember { mutableStateOf<String?>(null) }
    var lastSuccessfulResponse by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Connection diagnostics", style = MaterialTheme.typography.titleLarge)
        }

        DiagnosticCard(title = "Phone connectivity") {
            DiagnosticRow("Wi-Fi", wifiStatus)
            DiagnosticRow("Bluetooth radio", bluetoothStatus)
            DiagnosticRow("Nearby devices permission", nearbyPermissionStatus(context))
        }

        DiagnosticCard(title = "TV links") {
            DiagnosticRow("TV address saved", if (hostConfigured) "Yes" else "No — configure the TV IP")
            DiagnosticRow("Bluetooth TV selected", if (bluetoothTargetConfigured) "Yes" else "No — pair/select the TV")
            DiagnosticRow("Bluetooth keyboard (HID)", hidStatusLabel(hidState))
            DiagnosticRow("Combined remote state", overallStatusLabel(overallState))
            DiagnosticRow("TV Dash data link", testResult)
            DiagnosticRow("Most recent test path", lastTestPath ?: "Not tested")
            DiagnosticRow("Last successful TV response", lastSuccessfulResponse ?: "None during this screen session")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!testing) {
                        scope.launch {
                            testing = true
                            val startedAt = SystemClock.elapsedRealtime()
                            try {
                                val reply = graph.link.get("ping")
                                val elapsed = SystemClock.elapsedRealtime() - startedAt
                                val path = carrierLabel(graph.link.carrier.value)
                                lastTestPath = path
                                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                when (reply) {
                                    is TvReply.Ok -> {
                                        testResult = "Success — TV Dash replied in ${elapsed} ms"
                                        lastSuccessfulResponse = "$time • $path • ${elapsed} ms"
                                    }
                                    is TvReply.Rejected -> {
                                        testResult = "TV replied, but rejected the request (HTTP ${reply.http}). Check the saved PIN."
                                    }
                                    TvReply.Unreachable -> {
                                        testResult = "No reply. Check TV power, saved IP/PIN, Wi-Fi and Bluetooth pairing."
                                    }
                                }
                            } catch (e: Exception) {
                                testResult = "Test failed: ${e.message ?: "unexpected error"}"
                            } finally {
                                testing = false
                            }
                        }
                    }
                },
                enabled = !testing
            ) {
                Text(if (testing) "Testing…" else "Run connection test")
            }
            OutlinedButton(
                onClick = {
                    graph.connection.retryNow()
                    graph.connection.retryKeyboard()
                    graph.btData.reconnectNow()
                    testResult = "Reconnect requested. Run the connection test to confirm."
                },
                enabled = !testing
            ) { Text("Retry links") }
        }

        if (hint.isNotBlank()) {
            DiagnosticCard(
                title = "Suggested fix",
                containerColor = Color(0xFFFFB300),
                contentColor = Color(0xFF1F1300)
            ) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            DiagnosticCard(
                title = "Suggested fix",
                containerColor = Color(0xFFFFB300),
                contentColor = Color(0xFF1F1300)
            ) {
                Text(
                    "If the test fails, confirm TV Dash is running, the saved IP/PIN are current, Bluetooth is enabled " +
                        "on both devices, the TV is paired, and Nearby devices permission is granted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text(
            "The test sends a ping only. It does not press buttons, change volume, or expose the saved PIN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun phoneWifiStatus(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "Unavailable"
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "Offline"
    return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        "Connected"
    } else {
        "Not using Wi-Fi"
    }
}

private fun phoneBluetoothStatus(context: Context): String {
    val adapter = try {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    } catch (_: SecurityException) {
        return "Permission required"
    } ?: return "Unavailable"
    return try {
        if (adapter.isEnabled) "On" else "Off"
    } catch (_: SecurityException) {
        "Permission required"
    }
}

private fun nearbyPermissionStatus(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "Not required on this Android version"
    val needed = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )
    val missing = needed.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    return if (missing.isEmpty()) "Granted" else "Missing — allow Nearby devices for TvRemote"
}

private fun hidStatusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

private fun overallStatusLabel(state: RemoteConnectionState): String = when (state) {
    RemoteConnectionState.FullyConnected -> "TV Dash and Bluetooth keyboard"
    RemoteConnectionState.WiFiOnly -> "TV Dash only"
    RemoteConnectionState.BluetoothOnly -> "Bluetooth keyboard only"
    RemoteConnectionState.Disconnected -> "Disconnected / reconnecting"
}

private fun carrierLabel(carrier: Carrier): String = when (carrier) {
    Carrier.WIFI -> "Wi-Fi / HTTP"
    Carrier.BLUETOOTH -> "Bluetooth data channel (RFCOMM)"
}
