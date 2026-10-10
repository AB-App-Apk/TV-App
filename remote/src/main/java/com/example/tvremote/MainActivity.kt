package com.example.tvremote

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.updater.UpdateController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { RemoteScreen() }
            }
        }
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun call(host: String, pin: String, path: String): String? = try {
    val sep = if ('?' in path) '&' else '?'
    val c = URL("http://$host:8765/$path${sep}pin=$pin").openConnection() as HttpURLConnection
    c.connectTimeout = 2000
    c.readTimeout = 4000
    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
} catch (e: Exception) { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("remote", 0) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var editing by remember { mutableStateOf(host.isBlank()) }
    var screen by remember { mutableStateOf("remote") }
    var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // YouTube search: the text box below is the source of truth; the controller queues one search per submit.
    val link = remember { TvLink({ host.trim() }, { pin.trim() }) }
    val yt = remember { YouTubeSearchController(link, CommandQueue(scope)) }
    val tvPower = remember { GrennoTvRemoteManager(ctx) }
    val bluetooth = remember { BluetoothHidTransport(ctx) }
    val transports = remember { TransportManager(ctx, WifiTransport(link), bluetooth) }
    DisposableEffect(Unit) { transports.start(); onDispose { transports.stop() } }
    var mac by remember { mutableStateOf(prefs.getString("mac", "") ?: "") }
    val ytState by yt.state.collectAsState()
    var ytMode by remember { mutableStateOf(true) }
    val noTv = "Can't reach the TV. Check the IP, PIN and Wi-Fi."
    val upd = remember { UpdateController(ctx, "TvRemote.apk") }
    DisposableEffect(Unit) { upd.start(); onDispose { upd.stop() } }
    LaunchedEffect(Unit) { upd.check() }

    fun send(path: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { call(host.trim(), pin.trim(), path) }
            msg = when (r) {
                null -> noTv
                "no-a11y" -> "On the TV, turn on TV Dash in Settings > Accessibility."
                "no-overlay" -> "On the TV, allow 'Display over other apps' for TV Dash."
                "no-field" -> "Select the search box on the TV first (press OK on it), then type."
                "no-keyboard" -> "On the TV, select the Phone remote card and finish the keyboard setup (turn on and choose TV Dash phone keyboard)."
                else -> ""
            }
        }
    }

    /** Remote keys: Wi-Fi when the TV answers, the Bluetooth keyboard link when it does not. */
    fun press(command: RemoteCommand) {
        scope.launch {
            msg = when (val r = transports.send(command)) {
                is DeliveryResult.Delivered -> when (r.reply) {
                    "no-a11y" -> "On the TV, turn on TV Dash in Settings > Accessibility."
                    "no-overlay" -> "On the TV, allow 'Display over other apps' for TV Dash."
                    "rejected-403" -> "The TV rejected the PIN. Tap Change TV and enter the PIN shown on the TV."
                    else -> ""
                }
                DeliveryResult.Unsupported -> "That button needs the Wi-Fi connection to the TV."
                DeliveryResult.Offline -> noTv
            }
        }
    }

    /** Power On: TV Dash answers when the TV is awake; otherwise try the phone's IR blaster, then Wake-on-LAN. */
    fun powerOn() {
        scope.launch {
            fun say(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
            // The IR code is a toggle, so it must never be sent while the TV is awake: it would switch the TV off.
            if (link.get("ping") !is TvReply.Unreachable) {
                send("power?s=on")
                say("The TV is already on. Waking the screen if it was dark.")
                return@launch
            }
            var why = "No IR blaster on this phone."
            if (tvPower.hasIrEmitter()) {
                if (!tvPower.verifyHardwareCompatibility()) {
                    why = "This phone's IR blaster can't send 38 kHz."
                } else if (tvPower.transmitPowerToggle()) {
                    say("IR power signal sent. Point the phone's IR window at the TV.")
                    return@launch
                } else {
                    why = "The IR signal could not be sent."
                }
            }
            val started = tvPower.executeWakeOnLanFlood(
                mac,
                stopWhen = { link.get("ping") !is TvReply.Unreachable }
            ) { awake ->
                say(if (awake) "The TV is awake." else "No answer after 45 seconds. This TV may not wake from the network.")
            }
            say(
                if (started) "$why Wake-on-LAN started: one packet every 0.75 s for 45 s."
                else "$why Add the TV's MAC address under Change TV to use Wake-on-LAN."
            )
        }
    }

    fun loadApps() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { call(host.trim(), pin.trim(), "apps") }
            if (r == null) { msg = noTv; return@launch }
            val a = JSONArray(r)
            apps = List(a.length()) { a.getJSONObject(it).let { o -> o.getString("n") to o.getString("p") } }
            msg = ""
        }
    }

    LaunchedEffect(editing) { if (!editing) loadApps() }
    // Stream what is typed on the phone keyboard to the TV (short pause so fast typing is batched).
    LaunchedEffect(text, typing, ytMode) {
        if (typing && !ytMode && text != sent) { delay(120); sent = text; send("text?t=${enc(text)}&enter=0") }
    }

    if (screen == "apps" && !editing) {
        BackHandler { screen = "remote" }
        AppsPage(apps, msg, onBack = { screen = "remote" }) { pkg -> send("launch?pkg=$pkg"); screen = "remote" }
    } else {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
            if (editing) {
                Text("Connect to your TV", style = MaterialTheme.typography.titleLarge)
                Text("Open TV Dash on the TV. The Phone remote card shows the PIN and the IP address.")
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text("TV IP address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it }, label = { Text("PIN") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = mac, onValueChange = { mac = it },
                    label = { Text("TV MAC address (optional, for Wake-on-LAN)") },
                    supportingText = { Text("TV Settings > Device Preferences > About > Status") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (mac.isNotBlank() && tvPower.parseMac(mac) == null) {
                            msg = "The MAC address needs 12 hex digits, like AA:BB:CC:DD:EE:FF."
                        } else {
                            prefs.edit().putString("host", host.trim()).putString("pin", pin.trim())
                                .putString("mac", mac.trim()).apply()
                            msg = ""
                            editing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }
            } else {
                TransportStatusLine(transports)
                // Power
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { powerOn() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("On") }
                    Button(
                        onClick = { press(RemoteCommand.POWER_OFF) }, modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Off") }
                }
                // Volume
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { press(RemoteCommand.VOLUME_DOWN) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Vol −") }
                    FilledTonalButton(onClick = { press(RemoteCommand.MUTE) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Mute") }
                    FilledTonalButton(onClick = { press(RemoteCommand.VOLUME_UP) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Vol +") }
                }
                // System row. Apps only opens the Apps section of this app.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteButton(Ico.Back, "Back", Modifier.weight(1f)) { press(RemoteCommand.BACK) }
                    RemoteButton(Ico.Home, "Home", Modifier.weight(1f)) { press(RemoteCommand.HOME) }
                    RemoteButton(Ico.Settings, "Settings", Modifier.weight(1f)) { press(RemoteCommand.SETTINGS) }
                    RemoteButton(Ico.Apps, "Apps", Modifier.weight(1f)) { screen = "apps"; loadApps() }
                }
                // D-pad
                Column(
                    Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DKey(Ico.Up) { press(RemoteCommand.UP) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DKey(Ico.Left) { press(RemoteCommand.LEFT) }
                        Button(onClick = { press(RemoteCommand.OK) }, modifier = Modifier.size(88.dp), shape = CircleShape) {
                            Text("OK", style = MaterialTheme.typography.titleLarge)
                        }
                        DKey(Ico.Right) { press(RemoteCommand.RIGHT) }
                    }
                    DKey(Ico.Down) { press(RemoteCommand.DOWN) }
                }
                // Media + keyboard
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteButton(Ico.Rewind, "Rewind", Modifier.weight(1f)) { press(RemoteCommand.REWIND) }
                    RemoteButton(Ico.Keyboard, "Keyboard", Modifier.weight(1f)) {
                        typing = !typing
                        if (typing) { text = ""; sent = ""; yt.clear() } else if (!ytMode) send("text?done=1")
                    }
                    RemoteButton(Ico.Forward, "Forward", Modifier.weight(1f)) { press(RemoteCommand.FORWARD) }
                }
                if (typing) {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = ytMode, onCheckedChange = { ytMode = it })
                        Text("YouTube search", Modifier.padding(start = 8.dp))
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(if (ytMode) "Search YouTube on the TV" else "Typing on TV") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (ytMode) yt.submit(text) else { send("text?t=${enc(text)}&enter=1"); typing = false }
                        }),
                        trailingIcon = { TextButton(onClick = { typing = false; if (!ytMode) send("text?done=1") }) { Text("Close") } }
                    )
                    if (ytMode) SearchStatusLine(ytState, onRetry = { yt.retry() })
                }
                TextButton(onClick = { editing = true }) { Text("Change TV") }
                BluetoothFallbackPanel(transports, bluetooth)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    upd.status.ifEmpty { "Build ${upd.installed}" },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { scope.launch { if (upd.available) upd.update() else upd.check() } },
                    enabled = !upd.busy
                ) { Text(if (upd.available) "Update" else "Check for updates") }
            }
        }
    }
}

/** The Apps section of the remote: pick a TV app to open, then return to the remote. */
@Composable
fun AppsPage(apps: List<Pair<String, String>>, msg: String, onBack: () -> Unit, onLaunch: (String) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) { RIcon(Ico.Back, LocalContentColor.current) }
            Text("Apps", style = MaterialTheme.typography.titleLarge)
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(apps) { (name, pkg) ->
                OutlinedButton(
                    onClick = { onLaunch(pkg) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(name, maxLines = 1) }
            }
        }
    }
}
