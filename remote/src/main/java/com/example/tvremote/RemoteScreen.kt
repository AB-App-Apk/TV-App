package com.example.tvremote

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val AmberBanner = Color(0xFFFFB300)
private val OnAmberBanner = Color(0xFF1F1300)

/**
 * Layer 3: the remote control screen.
 *
 * It only draws and forwards. Every button calls one explicit handler on [viewModel]; the ViewModel asks Layer 1,
 * which picks the transport. This screen never sees Wi-Fi or Bluetooth details, only the [RemoteConnectionState].
 *
 * What the state does to the screen:
 *  - FullyConnected: the whole remote.
 *  - WiFiOnly: the whole remote, with an amber banner anchored at the top (it does not scroll away).
 *  - BluetoothOnly: the whole remote, no change. Layer 1 and 2 handle the routing silently.
 *  - Disconnected: the remote is blurred behind a dimmed full-screen "looking for the TV" card with a progress circle.
 *    The card has "Change TV" and "Bluetooth setup" so a lost connection can always be fixed from here.
 *
 * One-time events from the ViewModel are collected in one effect: haptic tap, toast, the connection-lost dialog, and
 * opening the apps list.
 *
 * The keyboard panel keeps the request format that Layers 1 and 2 verified: it calls
 * [RemoteViewModel.onKeyboardInputChanged] after a 120 ms pause in typing, [RemoteViewModel.onKeyboardClosed] when
 * it closes, and sends the YouTube search through [youTube] when YouTube mode is on.
 *
 * The top strip holds the connection status and a "How to pair" button that opens the Bluetooth pairing steps.
 * The "Guidelines" button lives in the bottom strip, next to "Check for updates". It opens a dialog with the
 * Architectural State Ledger, the project's reference text, which can be selected and copied.
 *
 * @param footer the bottom strip (Bluetooth setup, Guidelines, app update). It is given a function that opens the
 * Guidelines dialog. It is shown in only one place at a time: under the remote, or inside the reconnecting card while
 * that is up.
 * @param notice an error line shown under the connection status, empty for none.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    youTube: YouTubeSearchController,
    youTubeMode: Boolean,
    onYouTubeModeChange: (Boolean) -> Unit,
    onOpenAppsList: () -> Unit,
    onChangeTv: () -> Unit,
    modifier: Modifier = Modifier,
    notice: String = "",
    footer: @Composable ColumnScope.(onGuidelines: () -> Unit) -> Unit = {}
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val searchState by youTube.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val openAppsList by rememberUpdatedState(onOpenAppsList)
    val currentFooter by rememberUpdatedState(footer)

    var showDisconnectAlert by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showGuidelines by rememberSaveable { mutableStateOf(false) }
    var showHowToPair by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()

    // The footer holds setup screens with their own state, so it moves between the remote and the reconnecting card
    // without being rebuilt, and is never on screen twice.
    val footerHost = remember {
        movableContentOf {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                currentFooter(this, { showGuidelines = true })
            }
        }
    }

    // One-time events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                UIEvent.TriggerHapticFeedback -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                UIEvent.ShowDisconnectAlert -> showDisconnectAlert = true
                is UIEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                UIEvent.OpenAppsList -> openAppsList()
            }
        }
    }

    // The connection-lost dialog has no use once the connection is back.
    LaunchedEffect(state) {
        if (state !== RemoteConnectionState.Disconnected) {
            showDisconnectAlert = false
            showSetup = false
        }
    }

    // Stream what is typed on the phone keyboard to the TV. The short pause batches fast typing. The request text is
    // exactly the one the keyboard has always sent: "text?t=<all text so far>&enter=0".
    LaunchedEffect(text, typing, youTubeMode) {
        if (typing && !youTubeMode && text != sent) {
            delay(120)
            sent = text
            viewModel.onKeyboardInputChanged(text, false)
        }
    }

    val disconnected = state === RemoteConnectionState.Disconnected

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (disconnected) Modifier.blur(6.dp) else Modifier)
                .systemBarsPadding()
                .imePadding()
        ) {
            // The state decides what is anchored above the scrolling remote.
            when (state) {
                RemoteConnectionState.WiFiOnly -> WifiOnlyBanner()
                RemoteConnectionState.FullyConnected,
                RemoteConnectionState.BluetoothOnly,
                RemoteConnectionState.Disconnected -> Unit
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionStrip(state, hint, Modifier.weight(1f))
                FitOutlinedButton(label = "How to pair", onClick = { showHowToPair = true })
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.error)

                PowerRow(viewModel)
                VolumeRow(viewModel)
                SystemRow(viewModel)
                DirectionPad(viewModel)
                MediaRow(
                    viewModel = viewModel,
                    onKeyboardClicked = {
                        typing = !typing
                        if (typing) {
                            text = ""
                            sent = ""
                            youTube.clear()
                        } else if (!youTubeMode) {
                            viewModel.onKeyboardClosed()
                        }
                    }
                )

                if (typing) {
                    KeyboardPanel(
                        text = text,
                        onTextChange = { text = it },
                        youTubeMode = youTubeMode,
                        onYouTubeModeChange = onYouTubeModeChange,
                        searchState = searchState,
                        onSubmit = {
                            if (youTubeMode) {
                                youTube.submit(text)
                            } else {
                                viewModel.onKeyboardInputChanged(text, true)
                                typing = false
                            }
                        },
                        onClose = {
                            typing = false
                            if (!youTubeMode) viewModel.onKeyboardClosed()
                        },
                        onRetrySearch = { youTube.retry() }
                    )
                } else {
                    KeyboardPlaceholder()
                }

                FitTextButton(label = "Change TV", onClick = onChangeTv)
                if (!disconnected) footerHost()
            }
        }

        if (disconnected) {
            ReconnectingOverlay(
                hint = hint,
                showSetup = showSetup,
                onToggleSetup = { showSetup = !showSetup },
                onChangeTv = onChangeTv,
                setup = { footerHost() }
            )
        }
    }

    if (showGuidelines) GuidelinesDialog(onDismiss = { showGuidelines = false })
    if (showHowToPair) HowToPairDialog(onDismiss = { showHowToPair = false })

    if (showDisconnectAlert) {
        AlertDialog(
            onDismissRequest = { showDisconnectAlert = false },
            title = { Text("Connection lost") },
            text = {
                Text(
                    "The remote lost its link to the TV and is searching again in the background. " +
                        "Buttons pressed in the next few seconds are held and sent as soon as the link returns."
                )
            },
            confirmButton = { FitTextButton(label = "OK", onClick = { showDisconnectAlert = false }) }
        )
    }
}

// ------------------------------------------------------------------ state visuals

@Composable
private fun WifiOnlyBanner(modifier: Modifier = Modifier) {
    Surface(
        color = AmberBanner,
        contentColor = OnAmberBanner,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Backup connection active", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Only the network link to TV Dash is working. The Bluetooth keyboard is not connected, " +
                    "so the remote is running on its backup path.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun statusLabel(state: RemoteConnectionState): String = when (state) {
    RemoteConnectionState.FullyConnected -> "Connected: TV Dash and Bluetooth keyboard"
    RemoteConnectionState.WiFiOnly -> "Connected: TV Dash only"
    RemoteConnectionState.BluetoothOnly -> "Connected: Bluetooth keyboard only (TV Dash is closed)"
    RemoteConnectionState.Disconnected -> "Looking for the TV..."
}

/** One quiet line saying how the remote is connected, and the reason under it when Layer 1 has one. */
@Composable
private fun ConnectionStrip(state: RemoteConnectionState, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(statusLabel(state), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shown while Disconnected: a dimmed, full-screen layer that takes every tap, with a progress circle while Layer 1
 * retries in the background (1 second, then 2, 4, 8, up to 30 seconds between tries).
 */
@Composable
private fun ReconnectingOverlay(
    hint: String,
    showSetup: Boolean,
    onToggleSetup: () -> Unit,
    onChangeTv: () -> Unit,
    setup: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .systemBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.padding(24.dp).fillMaxWidth()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    "Looking for the TV...",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Text(
                    "The remote keeps trying in the background, waiting a little longer after each try. " +
                        "It reconnects by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitOutlinedButton(label = "Change TV", onClick = onChangeTv, modifier = Modifier.weight(1f))
                    FitOutlinedButton(
                        label = if (showSetup) "Hide setup" else "Bluetooth setup",
                        onClick = onToggleSetup,
                        modifier = Modifier.weight(1f)
                    )
                }
                AnimatedVisibility(visible = showSetup) { setup() }
            }
        }
    }
}

// ------------------------------------------------------------------ the remote

@Composable
private fun PowerRow(viewModel: RemoteViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FitButton(label = "On", onClick = { viewModel.onPowerClicked() }, modifier = Modifier.weight(1f).height(56.dp))
        FitButton(
            label = "Off",
            onClick = { viewModel.onPowerOffClicked() },
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        )
    }
}

@Composable
private fun VolumeRow(viewModel: RemoteViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FitTonalButton(label = "Vol −", onClick = { viewModel.onVolumeDown() }, modifier = Modifier.weight(1f).height(56.dp))
        FitTonalButton(label = "Mute", onClick = { viewModel.onMuteClicked() }, modifier = Modifier.weight(1f).height(56.dp))
        FitTonalButton(label = "Vol +", onClick = { viewModel.onVolumeUp() }, modifier = Modifier.weight(1f).height(56.dp))
    }
}

@Composable
private fun SystemRow(viewModel: RemoteViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton(Ico.Back, "Back", Modifier.weight(1f)) { viewModel.onBackClicked() }
        RemoteButton(Ico.Home, "Home", Modifier.weight(1f)) { viewModel.onHomeClicked() }
        RemoteButton(Ico.Settings, "Settings", Modifier.weight(1f)) { viewModel.onSettingsClicked() }
        RemoteButton(Ico.Apps, "Apps", Modifier.weight(1f)) { viewModel.onAppsClicked() }
    }
}

@Composable
private fun DirectionPad(viewModel: RemoteViewModel) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DKey(Ico.Up) { viewModel.onDpadUp() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DKey(Ico.Left) { viewModel.onDpadLeft() }
            FitButton(
                label = "OK",
                onClick = { viewModel.onDpadCenter() },
                modifier = Modifier.size(88.dp),
                shape = CircleShape,
                textStyle = MaterialTheme.typography.titleLarge
            )
            DKey(Ico.Right) { viewModel.onDpadRight() }
        }
        DKey(Ico.Down) { viewModel.onDpadDown() }
    }
}

@Composable
private fun MediaRow(viewModel: RemoteViewModel, onKeyboardClicked: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton(Ico.Rewind, "Rewind", Modifier.weight(1f)) { viewModel.onRewindClicked() }
        RemoteButton(Ico.Keyboard, "Keyboard", Modifier.weight(1f)) { onKeyboardClicked() }
        RemoteButton(Ico.Forward, "Forward", Modifier.weight(1f)) { viewModel.onForwardClicked() }
    }
}

// ------------------------------------------------------------------ virtual keyboard

/** The space kept for the keyboard when it is closed, so the layout does not jump and the way in is obvious. */
@Composable
private fun KeyboardPlaceholder() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "Tap Keyboard to type on the TV, for example a Wi-Fi password or a search.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

/**
 * The text box for typing on the TV. With YouTube mode off, what is typed is streamed live; with it on, the text
 * waits for Search and goes out as a YouTube search. Closing the panel tells the TV the typing is done.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyboardPanel(
    text: String,
    onTextChange: (String) -> Unit,
    youTubeMode: Boolean,
    onYouTubeModeChange: (Boolean) -> Unit,
    searchState: SearchState,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onRetrySearch: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = youTubeMode, onCheckedChange = onYouTubeModeChange)
            Text("YouTube search (sent when you press Search; off = type live)", Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            label = { Text(if (youTubeMode) "Search YouTube on the TV" else "Typing on TV") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailingIcon = { FitTextButton(label = "Close", onClick = onClose) }
        )
        if (youTubeMode) SearchStatusLine(searchState, onRetry = onRetrySearch)
    }
}

// ------------------------------------------------------------------ guidelines

/**
 * The Architectural State Ledger, as supplied. Two statements were corrected to match how the app really works:
 * the Bluetooth keyboard link needs API 28, and the Wake-on-LAN message appears only when the phone starts
 * Wake-on-LAN itself (with no link at all), not whenever TV Dash is reachable.
 */
private val ARCHITECTURAL_STATE_LEDGER = """
ARCHITECTURAL STATE LEDGER: DUAL-STACK ANDROID TV REMOTE (V2)
Date: October 16, 2026
System Status: Layer 1 Fully Specified & Patched | Layer 2 Blueprint Finalized

1. PROJECT OVERVIEW & BASELINE CONSTRAINTS
* Target Platform: Android TV Remote Companion App.
* Minimum SDK Baseline: API 26+ (Android 8.0). The Bluetooth HID keyboard link itself needs API 28+ (Android 9); on an older phone the remote uses TV Dash only.
* Architecture: Jetpack Compose, Android Architecture Components, Kotlin Coroutines, and asynchronous Flow.

2. CRITICAL PRESERVATION RULES
* Virtual Keyboard Input Logic: Working flawlessly. Payload structure: text?t=<all text so far>&enter=0|1 and text?done=1.
* Abstract Transport Handling: Infrastructure UI prompts remain fully abstracted within Layer 1.

3. ARCHITECTURAL STATE & COMPONENT BLUEPRINTS
* Layer 1 (ConnectionManager.kt): Emits FullyConnected, BluetoothOnly, WiFiOnly, and Disconnected (with a 5-second FIFO buffer up to 10 commands).
* Apps Button Forwarding: Intercepts BluetoothOnly to downgrade and emit native KeyEvent.KEYCODE_ALL_APPS (284).
* Power-On State Gating: WiFiOnly/FullyConnected wake the TV through TV Dash; the Wake-on-LAN toast appears only when the phone starts Wake-on-LAN itself (no link at all). BluetoothOnly runs completely silently.
* Layer 2 (RemoteViewModel.kt): Exposes read-only StateFlow and SharedFlow<UIEvent>. Strongly typed handlers instead of generic indices.
""".trimIndent()

/** The ledger with its title and numbered section headings in bold. */
private val ledgerText: AnnotatedString = buildAnnotatedString {
    ARCHITECTURAL_STATE_LEDGER.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val heading = line.startsWith("ARCHITECTURAL STATE LEDGER") || (line.isNotEmpty() && line[0].isDigit())
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line) } else append(line)
    }
}

/** The reference text in a scrollable dialog. Hold a line to select and copy. */
@Composable
private fun GuidelinesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guidelines") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(ledgerText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { FitTextButton(label = "Close", onClick = onDismiss) }
    )
}

// ------------------------------------------------------------------ how to pair

/**
 * The steps to pair the phone with the TV so the Bluetooth keyboard link works. The button names are the ones on the
 * Bluetooth setup panel under the remote.
 */
@Composable
private fun HowToPairDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to pair") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Pairing lets the phone act as a Bluetooth keyboard for the TV. It keeps the basic keys and typing working when TV Dash is closed.")
                Text("1. Under the remote, open Bluetooth setup and tap Allow Bluetooth (Nearby devices). Accept the question.")
                Text("2. On the TV, open Settings > Remotes & accessories and choose Add accessory. The wording differs a little between TVs.")
                Text("3. On the phone, tap Make phone visible and allow it. Select the phone in the TV's list and confirm the code on both screens.")
                Text("4. In the remote, tap Choose TV and pick your TV.")
                Text("5. When it works, the line at the top says Bluetooth keyboard.")
                Text(
                    "If the TV stops reacting after an app update, remove the phone on the TV and the TV in the phone's Bluetooth settings, then pair again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { FitTextButton(label = "Close", onClick = onDismiss) }
    )
}
