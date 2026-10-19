package com.example.tvremote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/** One line that says how the remote is connected, plus a plain reason when something is not working. */
@Composable
fun ConnectionStatusLine(manager: ConnectionManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val hint by manager.hint.collectAsState()
    val label = when (state) {
        RemoteConnectionState.FullyConnected -> "Connected: TV Dash and Bluetooth keyboard"
        RemoteConnectionState.WiFiOnly -> "Connected: TV Dash (Bluetooth keyboard not set up)"
        RemoteConnectionState.BluetoothOnly -> "Connected: Bluetooth keyboard only (TV Dash is closed)"
        RemoteConnectionState.Disconnected -> "Looking for the TV..."
    }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Setup for the Bluetooth fallback: allow Bluetooth, make the phone visible for pairing, choose the paired TV. */
@Composable
fun BluetoothFallbackPanel(bluetooth: BluetoothHidTransport, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    var allowed by remember { mutableStateOf(bluetooth.hasPermission()) }
    var chosen by remember { mutableStateOf(bluetooth.targetName()) }
    var picking by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = bluetooth.hasPermission()
        if (allowed) {
            bluetooth.attach()
            onChanged()
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
                FitButton(
                    label = "Allow Bluetooth (Nearby devices)",
                    onClick = { askPermission.launch(bluetooth.requiredPermissions()) },
                    modifier = Modifier.fillMaxWidth()
                )
            else ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitOutlinedButton(label = "Choose TV", onClick = { picking = true }, modifier = Modifier.weight(1f))
                    FitOutlinedButton(
                        label = "Make phone visible",
                        onClick = { askVisible.launch(bluetooth.discoverableIntent()) },
                        modifier = Modifier.weight(1f)
                    )
                }
        }
        Text(
            "To pair once: tap Make phone visible, then on the TV open Settings > Remotes & accessories > Add accessory " +
                "and select this phone. Then tap Choose TV and pick the TV. With TV Dash running, Bluetooth then carries " +
                "everything Wi-Fi does (apps, YouTube search, Settings). If TV Dash is not running, the Bluetooth keyboard carries the " +
                "basic keys and your typing (for example a Wi-Fi password). " +
                "The TV asks once to allow Nearby devices for TV Dash: choose Allow.",
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
                        FitTextButton(
                            label = bluetooth.nameOf(device),
                            onClick = {
                                bluetooth.targetAddress = device.address
                                chosen = bluetooth.targetName()
                                picking = false
                                onChanged()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = { FitTextButton(label = "Cancel", onClick = { picking = false }) }
        )
    }
}
