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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

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
    val firstButton = remember { FocusRequester() }
    val continueButton = remember { FocusRequester() }
    val firstButtonHasFocus = remember { mutableStateOf(false) }
    LaunchedEffect(permanentlyDenied) {
        firstButtonHasFocus.value = false
        delay(150)
        runCatching { firstButton.requestFocus() }
        delay(100)
        if (!firstButtonHasFocus.value) {
            runCatching { continueButton.requestFocus() }
        }
    }
    Box(modifier = Modifier
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
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .focusRequester(firstButton)
                        .onFocusChanged { firstButtonHasFocus.value = it.isFocused }
                ) {
                    Text("Open app settings")
                }
            } else {
                Button(
                    onClick = onAllow,
                    modifier = Modifier
                        .focusRequester(firstButton)
                        .onFocusChanged { firstButtonHasFocus.value = it.isFocused }
                ) {
                    Text("Allow Nearby devices")
                }
            }
            Button(
                onClick = onContinueWithoutBluetooth,
                modifier = Modifier.focusRequester(continueButton)
            ) {
                Text("Continue with Wi-Fi only")
            }
        }
    }
}
