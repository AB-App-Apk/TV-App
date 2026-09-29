package com.example.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

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
    val scope = rememberCoroutineScope()
    val noTv = "Can't reach the TV. Check the IP, PIN and Wi-Fi."

    fun send(path: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { call(host.trim(), pin.trim(), path) }
            msg = when (r) {
                null -> noTv
                "no-a11y" -> "On the TV, turn on TV Dash in Settings > Accessibility."
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
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { send("power?s=on") }, modifier = Modifier.weight(1f).height(64.dp)) { Text("On") }
                Button(
                    onClick = { send("power?s=off") }, modifier = Modifier.weight(1f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Off") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { send("vol?d=down") }, modifier = Modifier.weight(1f).height(64.dp)) { Text("Vol −") }
                FilledTonalButton(onClick = { send("vol?d=mute") }, modifier = Modifier.weight(1f).height(64.dp)) { Text("Mute") }
                FilledTonalButton(onClick = { send("vol?d=up") }, modifier = Modifier.weight(1f).height(64.dp)) { Text("Vol +") }
            }
            Text("Apps", style = MaterialTheme.typography.titleMedium)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps) { (name, pkg) ->
                    OutlinedButton(onClick = { send("launch?pkg=$pkg") }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = { editing = true }) { Text("Change TV") }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
    }
}
