package com.example.tvdash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable

/**
 * D-pad-friendly rationale shown before asking Android for BLUETOOTH_CONNECT.
 * Bluetooth is optional: choosing Wi-Fi-only keeps the main TV app usable.
 */
@Composable
internal fun BluetoothPermissionRationaleOverlay(
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueWithoutBluetooth: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Allow Bluetooth fallback",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "TV Dash uses Nearby devices permission to accept the phone's Bluetooth connection when Wi-Fi is unavailable. " +
                    "It does not scan for nearby devices from this screen. Wi-Fi control remains available if you skip this.",
                textAlign = TextAlign.Center
            )
            if (permanentlyDenied) {
                Text(
                    text = "Android is no longer showing the permission prompt. Open TV Dash's app settings and allow Nearby devices.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onOpenSettings) {
                    Text("Open app settings")
                }
            } else {
                Button(onClick = onAllow) {
                    Text("Allow Nearby devices")
                }
            }
            Button(onClick = onContinueWithoutBluetooth) {
                Text("Continue with Wi-Fi only")
            }
        }
    }
}
