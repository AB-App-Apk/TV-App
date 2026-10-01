package com.example.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.updater.UpdateController
import kotlinx.coroutines.Dispatchers
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

private fun call(host: String, pin: String, path: String): String? = try {
    val sep = if ('?' in path) '&' else '?'
    val c = URL("http://$host:8765/$path${sep}pin=$pin").openConnection() as HttpURLConnection
    c.connectTimeout = 2000
    c.readTimeout = 4000
    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
} catch (e: Exception) { null }

@Composable
fun RemoteScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("remote", 0) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var editing by remember { mutableStateOf(host.isBlank()) }
    var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
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

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
            // System row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteButton(Ico.Home, "Home", Modifier.weight(1f)) { send("key?k=home") }
                RemoteButton(Ico.Settings, "Settings", Modifier.weight(1f)) { send("key?k=settings") }
                RemoteButton(Ico.Apps, "Apps", Modifier.weight(1f)) { send("key?k=apps") }
                RemoteButton(Ico.Keyboard, "Keyboard", Modifier.weight(1f)) { typing = true }
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
            // Media
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteButton(Ico.Rewind, "Rewind", Modifier.weight(1f)) { send("key?k=rew") }
                RemoteButton(Ico.Forward, "Forward", Modifier.weight(1f)) { send("key?k=ff") }
            }
            Text("Open an app", style = MaterialTheme.typography.titleMedium)
            apps.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (name, pkg) ->
                        OutlinedButton(onClick = { send("launch?pkg=$pkg") }, modifier = Modifier.weight(1f)) {
                            Text(name, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
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

    if (typing) {
        val enc = { URLEncoder.encode(text, "UTF-8") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Type on TV") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Text for the search bar") }
                )
            },
            confirmButton = {
                TextButton(onClick = { send("text?t=${enc()}&enter=1"); typing = false }) { Text("Search") }
            },
            dismissButton = {
                TextButton(onClick = { send("text?t=${enc()}&enter=0") }) { Text("Send") }
            }
        )
    }
}
