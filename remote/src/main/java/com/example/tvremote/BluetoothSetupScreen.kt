package com.example.tvremote

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The second way in: pair the phone with the TV over Bluetooth.
 *
 * The person allows Bluetooth, the phone scans, they pick the TV, the system pairs with its own secure pairing, and
 * then the Bluetooth keyboard link to the TV is brought up. [onConnected] is called only after that link is really
 * up, and never before. Going back with [onBack] leaves everything as it was.
 */
@Composable
fun BluetoothSetupScreen(
    pairing: BluetoothPairing,
    bluetooth: BluetoothHidTransport,
    connection: ConnectionManager,
    onBack: () -> Unit,
    onConnected: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val devices by pairing.devices.collectAsStateWithLifecycle()
    val scanning by pairing.scanning.collectAsStateWithLifecycle()
    val problem by pairing.problem.collectAsStateWithLifecycle()

    var permitted by remember { mutableStateOf(pairing.hasPermissions()) }
    var bluetoothOn by remember { mutableStateOf(pairing.isBluetoothOn()) }
    var connectingTo by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        pairing.start()
        onDispose { pairing.stop() }
    }
    BackHandler(onBack = onBack)

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = pairing.hasPermissions()
        bluetooth.attach() // the keyboard role can open now that Bluetooth is allowed
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothOn = pairing.isBluetoothOn()
        bluetooth.attach()
    }
    val askVisible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // Scan as soon as scanning is possible.
    LaunchedEffect(permitted, bluetoothOn) {
        if (permitted && bluetoothOn && bluetooth.isSupported()) pairing.startScan()
    }

    /** Chooses the TV and brings the Bluetooth keyboard link up. Succeeds only if the link really opens. */
    suspend fun connectTo(address: String, name: String) {
        failure = ""
        connectingTo = name
        if (bluetooth.targetAddress != address) bluetooth.disconnect()
        bluetooth.targetAddress = address
        var up = false
        for (attempt in 1..3) {
            if (bluetooth.connect()) {
                up = true
                break
            }
            if (attempt < 3) delay(1_500)
        }
        connectingTo = null
        if (up) {
            connection.retryNow()
            onConnected()
        } else {
            failure = (bluetooth.lastProblem ?: "The TV did not open the Bluetooth link.") +
                " Turn the TV on and try again, or pick the phone in the TV's accessory list."
        }
    }

    // Pairing finished: now connect.
    LaunchedEffect(pairing) {
        pairing.paired.collect { address ->
            val name = pairing.devices.value.firstOrNull { it.address == address }?.name ?: address
            if (connectingTo == null) connectTo(address, name)
        }
    }

    fun pick(device: NearbyDevice) {
        if (connectingTo != null) return
        failure = ""
        if (device.paired) scope.launch { connectTo(device.address, device.name) } else pairing.pair(device.address)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) { RIcon(Ico.Back, LocalContentColor.current) }
            Text("Set up Bluetooth", style = MaterialTheme.typography.titleLarge)
        }
        Text("Pair this phone with the TV. The phone then works as a Bluetooth keyboard and remote, even when TV Dash is closed. Keep the TV on.")

        when {
            !bluetooth.isSupported() -> Text(
                "This phone can't act as a Bluetooth keyboard. It needs Android 9 or newer.",
                color = MaterialTheme.colorScheme.error
            )
            !permitted -> {
                Text("Android asks for \"Nearby devices\" so the phone can find and pair the TV.")
                FitButton(
                    label = "Allow Bluetooth (Nearby devices)",
                    onClick = { askPermissions.launch(pairing.requiredPermissions()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            !bluetoothOn -> {
                Text("Bluetooth is turned off on the phone.")
                FitButton(
                    label = "Turn on Bluetooth",
                    onClick = { askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                Text("1. On the TV, open Settings > Remotes & accessories > Add accessory. The TV starts looking.")
                Text("2. Pick your TV in the list below. Scan again if it is not there yet.")
                Text("3. Confirm the pairing code on the phone and on the TV.")

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitOutlinedButton(
                        label = if (scanning) "Scanning..." else "Scan again",
                        onClick = { pairing.startScan() },
                        enabled = !scanning && connectingTo == null,
                        modifier = Modifier.weight(1f)
                    )
                    FitOutlinedButton(
                        label = "Make phone visible",
                        onClick = { askVisible.launch(bluetooth.discoverableIntent()) },
                        enabled = connectingTo == null,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (scanning || connectingTo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(connectingTo?.let { "Connecting to $it..." } ?: "Looking for devices...")
                    }
                }

                devices.forEach { device ->
                    FitOutlinedButton(
                        label = when {
                            device.pairing -> "${device.name} (pairing...)"
                            device.paired -> "${device.name} (paired)"
                            else -> device.name
                        },
                        onClick = { pick(device) },
                        enabled = connectingTo == null && !device.pairing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!scanning && devices.isEmpty()) {
                    Text(
                        "No devices found yet. Open Add accessory on the TV, then scan again. On Android 11 and older, Location must be on to scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val message = failure.ifEmpty { problem.orEmpty() }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}
