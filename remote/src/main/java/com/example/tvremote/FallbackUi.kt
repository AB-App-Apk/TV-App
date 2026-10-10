package com.example.tvremote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One line that says how the remote is connected, plus the reason when something is wrong. */
@Composable
fun TransportStatusLine(manager: TransportManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val notice by manager.notice.collectAsState()
    Column(modifier) {
        Text(
            state.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (state == TransportState.OFFLINE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        if (notice.isNotEmpty() && state != TransportState.WIFI_ACTIVE) {
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Setup for the Bluetooth fallback: allow Bluetooth, make the phone visible for pairing, choose the paired TV. */
@Composable
fun BluetoothFallbackPanel(manager: TransportManager, bluetooth: BluetoothHidTransport, modifier: Modifier = Modifier) {
    var allowed by remember { mutableStateOf(bluetooth.hasPermission()) }
    var chosen by remember { mutableStateOf(bluetooth.targetName()) }
    var picking by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = bluetooth.hasPermission()
        if (allowed) {
            bluetooth.attach()
            manager.retryBluetooth()
        }
    }
    val askVisible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bluetooth fallback", style = MaterialTheme.typography.titleMedium)
        Text(
            if (chosen != null) "TV for Bluetooth: $chosen" else "No TV chosen yet.",
            style = MaterialTheme.typography.bodySmall
        )
        when {
            !bluetooth.isSupported() ->
                Text("This phone can't act as a Bluetooth keyboard (needs Android 9 or newer).", style = MaterialTheme.typography.bodySmall)
            !allowed ->
                Button(onClick = { askPermission.launch(bluetooth.requiredPermissions()) }) { Text("Allow Bluetooth (Nearby devices)") }
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) { Text("Choose TV") }
                    OutlinedButton(onClick = { askVisible.launch(bluetooth.discoverableIntent()) }) { Text("Make phone visible") }
                }
        }
        Text(
            "To pair once: tap Make phone visible, then on the TV open Settings > Remotes & accessories > Add accessory " +
                "and select this phone. Then tap Choose TV and pick the TV. Bluetooth only sends the basic keys.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (picking) {
        val devices = remember { bluetooth.bondedDevices() }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Choose your TV") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired Bluetooth devices yet. Pair the phone with the TV first.")
                    devices.forEach { device ->
                        TextButton(onClick = {
                            bluetooth.targetAddress = device.address
                            chosen = bluetooth.targetName()
                            picking = false
                            manager.retryBluetooth()
                        }) { Text(bluetooth.nameOf(device)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        )
    }
}
