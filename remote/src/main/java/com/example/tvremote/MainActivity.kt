package com.example.tvremote

import android.os.Bundle
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
                "no-field" -> "Select a text field on the TV first."
                else -> ""
            }
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
    LaunchedEffect(text, typing) {
        if (typing && text != sent) { delay(120); sent = text; send("text?t=${enc(text)}&enter=0") }
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
                Button(
                    onClick = {
                        prefs.edit().putString("host", host.trim()).putString("pin", pin.trim()).apply()
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }
            } else {
                // Power
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { send("power?s=on") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("On") }
                    Button(
                        onClick = { send("power?s=off") }, modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Off") }
                }
                // Volume
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { send("vol?d=down") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Vol −") }
                    FilledTonalButton(onClick = { send("vol?d=mute") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Mute") }
                    FilledTonalButton(onClick = { send("vol?d=up") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Vol +") }
                }
                // System row. Apps only opens the Apps section of this app.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteButton(Ico.Back, "Back", Modifier.weight(1f)) { send("key?k=back") }
                    RemoteButton(Ico.Home, "Home", Modifier.weight(1f)) { send("key?k=home") }
                    RemoteButton(Ico.Settings, "Settings", Modifier.weight(1f)) { send("key?k=settings") }
                    RemoteButton(Ico.Apps, "Apps", Modifier.weight(1f)) { screen = "apps"; loadApps() }
                }
                // D-pad
                Column(
                    Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DKey(Ico.Up) { send("key?k=up") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DKey(Ico.Left) { send("key?k=left") }
                        Button(onClick = { send("key?k=ok") }, modifier = Modifier.size(88.dp), shape = CircleShape) {
                            Text("OK", style = MaterialTheme.typography.titleLarge)
                        }
                        DKey(Ico.Right) { send("key?k=right") }
                    }
                    DKey(Ico.Down) { send("key?k=down") }
                }
                // Media + keyboard
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteButton(Ico.Rewind, "Rewind", Modifier.weight(1f)) { send("key?k=rew") }
                    RemoteButton(Ico.Keyboard, "Keyboard", Modifier.weight(1f)) {
                        typing = !typing
                        if (typing) { text = ""; sent = "" }
                    }
                    RemoteButton(Ico.Forward, "Forward", Modifier.weight(1f)) { send("key?k=ff") }
                }
                if (typing) {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Typing on TV") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { send("text?t=${enc(text)}&enter=1") }),
                        trailingIcon = { TextButton(onClick = { typing = false }) { Text("Close") } }
                    )
                }
                TextButton(onClick = { editing = true }) { Text("Change TV") }
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
