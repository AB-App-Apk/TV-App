package com.example.tvremote

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.updater.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both are kept across screen rotations by the activity's ViewModel store, so the links are not rebuilt.
        val graph = ViewModelProvider(this, RemoteGraphHolder.Factory(applicationContext))[RemoteGraphHolder::class.java].graph
        val viewModel = ViewModelProvider(this, RemoteViewModel.Factory(graph.connection, graph.phoneWake))[RemoteViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { RemoteApp(graph, viewModel) }
            }
        }
    }
}

/**
 * The long-lived pieces of the remote, built once. The TV address, PIN and MAC address are read from the saved
 * settings each time they are needed, so changing the TV takes effect without rebuilding anything.
 */
class RemoteGraph(context: Context) {
    private val app: Context = context.applicationContext
    val prefs: SharedPreferences = app.getSharedPreferences("remote", 0)

    private fun saved(key: String): String = prefs.getString(key, "")?.trim().orEmpty()

    val btData = BluetoothDataClient(app) { saved("pin") }
    val link = TvLink({ saved("host") }, { saved("pin") }, btData)
    val tvPower = GrennoTvRemoteManager(app)
    val bluetooth = BluetoothHidTransport(app)
    val connection = ConnectionManager(app, link, bluetooth)
    val phoneWake = GrennoPhoneWake(tvPower, link) { saved("mac") }
}

/** Keeps the [RemoteGraph] alive across screen rotations and closes what it opened when the screen is finished. */
class RemoteGraphHolder(context: Context) : ViewModel() {
    val graph = RemoteGraph(context)

    override fun onCleared() {
        graph.tvPower.cancelWakeOnLan()
        graph.btData.close()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RemoteGraphHolder::class.java)) { "Unknown ViewModel class ${modelClass.name}" }
            return RemoteGraphHolder(context) as T
        }
    }
}

/**
 * The app: the TV setup form, the remote (Layer 3), and the Apps list. The remote itself lives in [RemoteScreen];
 * this only decides which of the three is showing and holds the setup and update logic around it.
 */
@Composable
fun RemoteApp(graph: RemoteGraph, viewModel: RemoteViewModel) {
    val ctx = LocalContext.current
    val prefs = graph.prefs
    val link = graph.link
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var mac by remember { mutableStateOf(prefs.getString("mac", "") ?: "") }
    var editing by remember { mutableStateOf(host.isBlank()) }
    var screen by remember { mutableStateOf("remote") }
    var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf("") }
    var ytMode by remember { mutableStateOf(prefs.getBoolean("yt_mode", false)) }
    // YouTube search: the text box is the source of truth; the controller queues one search per submit.
    val yt = remember { YouTubeSearchController(link, CommandQueue(scope)) }
    val noTv = "Can't reach the TV. Check the IP, PIN and Wi-Fi."
    val upd = remember { UpdateController(ctx, "TvRemote.apk") }
    DisposableEffect(Unit) { upd.start(); onDispose { upd.stop() } }
    LaunchedEffect(Unit) { upd.check() }

    /** A request to TV Dash that is not a button or typing, for example launching an app. */
    fun send(path: String) {
        scope.launch {
            val r = link.get(path).bodyOrNull()
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

    fun loadApps() {
        scope.launch {
            val r = link.get("apps").bodyOrNull()
            if (r == null) { msg = noTv; return@launch }
            val a = JSONArray(r)
            apps = List(a.length()) { a.getJSONObject(it).let { o -> o.getString("n") to o.getString("p") } }
            msg = ""
        }
    }

    LaunchedEffect(editing) { if (!editing) loadApps() }

    if (screen == "apps" && !editing) {
        BackHandler { screen = "remote" }
        AppsPage(apps, msg, onBack = { screen = "remote" }) { pkg -> send("launch?pkg=$pkg"); screen = "remote" }
    } else if (editing) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
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
            FitButton(
                label = "Connect",
                onClick = {
                    if (mac.isNotBlank() && graph.tvPower.parseMac(mac) == null) {
                        msg = "The MAC address needs 12 hex digits, like AA:BB:CC:DD:EE:FF."
                    } else {
                        prefs.edit().putString("host", host.trim()).putString("pin", pin.trim())
                            .putString("mac", mac.trim()).apply()
                        msg = ""
                        editing = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            UpdateRow(upd, scope)
        }
    } else {
        RemoteScreen(
            viewModel = viewModel,
            youTube = yt,
            youTubeMode = ytMode,
            onYouTubeModeChange = { ytMode = it; prefs.edit().putBoolean("yt_mode", it).apply() },
            onOpenAppsList = { screen = "apps"; loadApps() },
            onChangeTv = { editing = true },
            notice = msg,
            footer = { openGuidelines ->
                BluetoothFallbackPanel(graph.bluetooth, onChanged = { graph.connection.retryKeyboard() })
                UpdateRow(upd, scope, onGuidelines = openGuidelines)
            }
        )
    }
}

/**
 * The bottom strip: "Guidelines" (only where a handler is given) and "Check for updates" side by side.
 *
 * There is no status label. The result of a check or an update is told once, as a short message, and is then gone:
 * "Up to date" when the app is current. When a newer build exists the button itself turns into "Update", and while
 * something is running it shows what is happening.
 */
@Composable
private fun UpdateRow(upd: UpdateController, scope: CoroutineScope, onGuidelines: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    fun tell(message: String, long: Boolean = true) {
        Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    // The installer reports back after the tap, with a failure or a success. Those results arrive later, so watch for them.
    LaunchedEffect(upd.status) {
        if (upd.status.startsWith("Install failed") || upd.status.startsWith("Updated.")) tell(upd.status)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onGuidelines != null) {
            FitOutlinedButton(label = "Guidelines", onClick = onGuidelines, modifier = Modifier.weight(1f))
        }
        FitOutlinedButton(
            label = when {
                upd.busy -> upd.status.ifEmpty { "Please wait..." }
                upd.available -> "Update"
                else -> "Check for updates"
            },
            onClick = {
                scope.launch {
                    if (upd.available) upd.update() else upd.check()
                    val result = upd.status
                    when {
                        result == "Up to date" -> tell("Up to date", long = false)
                        result.isNotEmpty() && !result.startsWith("Checking") && !result.startsWith("Downloading") -> tell(result)
                    }
                }
            },
            enabled = !upd.busy,
            modifier = Modifier.weight(1f)
        )
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
                FitOutlinedButton(
                    label = name,
                    onClick = { onLaunch(pkg) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                )
            }
        }
    }
}
