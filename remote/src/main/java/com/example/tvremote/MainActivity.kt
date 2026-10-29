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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tvlink.TvTls
import com.example.updater.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

    override fun onResume() {
        super.onResume()
        // The Activity is visible here, satisfying modern Android foreground-service start restrictions.
        BluetoothConnectionService.startIfConfigured(this)
    }
}

/**
 * The long-lived pieces of the remote, built once. The TV address, PIN, MAC address and the verified certificate
 * fingerprint are read from the saved settings each time they are needed, so changing the TV takes effect without
 * rebuilding anything.
 */
class RemoteGraph(context: Context) {
    private val app: Context = context.applicationContext
    val prefs: SharedPreferences = app.getSharedPreferences("remote", 0)

    private fun saved(key: String): String = prefs.getString(key, "")?.trim().orEmpty()

    val btData = BluetoothDataClientProvider.acquireGraphClient(app) { saved("pin") }
    val link = TvLink({ saved("host") }, { saved("pin") }, btData, { saved("tls_fp") })
    val tvPower = GrennoTvRemoteManager(app)
    val bluetooth = BluetoothHidTransport(app)
    val connection = ConnectionManager(app, link, bluetooth)
    val phoneWake = GrennoPhoneWake(tvPower, link) { saved("mac") }

    /** Finds and pairs Bluetooth devices on the setup screen. */
    val pairing = BluetoothPairing(app)

    /** The gate in front of the remote: open only after a way in has really worked. */
    val setupGate = SetupGate(prefs)
}

/** Keeps the [RemoteGraph] alive across screen rotations and closes what it opened when the screen is finished. */
class RemoteGraphHolder(context: Context) : ViewModel() {
    val graph = RemoteGraph(context)

    override fun onCleared() {
        graph.pairing.close()
        graph.tvPower.cancelWakeOnLan()
        BluetoothDataClientProvider.releaseGraphClient(graph.btData)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RemoteGraphHolder::class.java)) { "Unknown ViewModel class ${modelClass.name}" }
            return RemoteGraphHolder(context) as T
        }
    }
}

/** A TV certificate the person has been asked to verify by comparing its security code with the one on the TV. */
private class PendingVerification(
    val host: String,
    val pin: String,
    val mac: String,
    val fingerprint: String,
    /** A different certificate was verified before: TV Dash was reinstalled, or this is another TV. */
    val replacesPrevious: Boolean
)

/**
 * The app: the way-in screens (IP address and PIN, or Bluetooth), the remote (Layer 3), and the Apps list.
 *
 * THE RULE: the remote opens only when [setupComplete] is true, and only two places ever set it: the TV answered the
 * IP address and PIN, or the Bluetooth keyboard link came up. Leaving the form blank, a wrong address, a wrong PIN,
 * a certificate that was not verified, or a pairing that did not connect all leave the person where they are.
 *
 * Wi-Fi is encrypted. Before the IP address and PIN are sent, the phone reads the TV's certificate and asks the person
 * to compare a short security code with the one on the TV's dashboard. Only then is the certificate pinned and the PIN sent.
 */
@Composable
fun RemoteApp(graph: RemoteGraph, viewModel: RemoteViewModel) {
    val ctx = LocalContext.current
    val prefs = graph.prefs
    val link = graph.link
    val gate = graph.setupGate
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var mac by remember { mutableStateOf(prefs.getString("mac", "") ?: "") }
    var hostError by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }
    var macError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    var setupComplete by remember { mutableStateOf(gate.isComplete) }
    var changingTv by remember { mutableStateOf(false) }
    var setupPage by remember { mutableStateOf("ip") }

    // The certificate the person verified, and the one being verified right now.
    var pinnedFingerprint by remember { mutableStateOf(gate.fingerprint) }
    var verification by remember { mutableStateOf<PendingVerification?>(null) }
    var verifiedThisSession by remember { mutableStateOf("") }
    var automaticPromptDismissed by remember { mutableStateOf(false) }

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

    fun toast(message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }

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
            val parsed = runCatching {
                val a = JSONArray(r)
                List(a.length()) { a.getJSONObject(it).let { o -> o.getString("n") to o.getString("p") } }
            }.getOrNull()
            if (parsed == null) {
                msg = "The TV sent an unexpected answer. Update TV Dash, then try again."
                return@launch
            }
            apps = parsed
            msg = ""
        }
    }

val linkState by graph.connection.state.collectAsStateWithLifecycle()
LaunchedEffect(linkState) {
    if (linkState !== RemoteConnectionState.Disconnected) {
        msg = ""
        if (setupComplete && !changingTv && apps.isEmpty()) loadApps()
    }
}

    /** The last step: the TV must answer this address and PIN over the verified, encrypted link. */
    suspend fun finishWithIp(tvHost: String, tvPin: String, tvMac: String, fingerprint: String) {
        when (ConnectionInput.askTv(tvHost, tvPin, fingerprint)) {
            TvCheck.ACCEPTED -> {
                gate.pinFingerprint(fingerprint)
                gate.completeWithIp(tvHost, tvPin, tvMac)
                pinnedFingerprint = gate.fingerprint
                host = tvHost
                pin = tvPin
                msg = ""
                graph.connection.retryNow()
                setupComplete = true
                changingTv = false
            }
            TvCheck.WRONG_PIN -> {
                pinError = true
                toast("The TV did not accept that PIN. Check the PIN on the TV's Phone remote card.")
            }
            TvCheck.CERTIFICATE -> {
                hostError = true
                toast("The TV's certificate changed while connecting. Try again and compare the security code.")
            }
            TvCheck.UNREACHABLE -> {
                hostError = true
                toast("Can't reach the TV at that address. Is TV Dash open on the TV, on the same Wi-Fi?")
            }
        }
    }

    /**
     * The first step after the fields look right: read the TV's certificate (nothing is sent yet). A certificate that is
     * already verified goes straight on. A new or different one is shown to the person to compare first.
     */
    fun verifyTv(tvHost: String, tvPin: String, tvMac: String) {
        if (checking) return
        scope.launch {
            checking = true
            val seen = ConnectionInput.readFingerprint(tvHost)
            when {
                seen == null -> {
                    hostError = true
                    toast("Can't reach the TV securely at that address. Open TV Dash on the TV, on the same Wi-Fi, and update it to the latest build.")
                }
                TvTls.sameFingerprint(seen, pinnedFingerprint) || TvTls.sameFingerprint(seen, verifiedThisSession) ->
                    finishWithIp(tvHost, tvPin, tvMac, seen)
                else -> verification = PendingVerification(tvHost, tvPin, tvMac, seen, pinnedFingerprint.isNotBlank())
            }
            checking = false
        }
    }

    /** The first way in. Opens the remote only if the fields are right, the certificate is verified AND the TV answers. */
    fun connectWithIp() {
        if (checking) return
        hostError = false
        pinError = false
        macError = false
        when (val input = ConnectionInput.check(host, pin)) {
            is InputCheck.Invalid -> {
                hostError = input.ipWrong
                pinError = input.pinWrong
                toast(input.message)
            }
            is InputCheck.Valid -> {
                if (mac.isNotBlank() && graph.tvPower.parseMac(mac) == null) {
                    macError = true
                    toast("The MAC address needs 12 hex digits, like AA:BB:CC:DD:EE:FF.")
                    return
                }
                verifyTv(input.host, input.pin, mac.trim())
            }
        }
    }

    // A phone set up before Wi-Fi was encrypted has an address and PIN but no verified certificate, so Wi-Fi stays off
    // until it is verified once. Offer that on its own as soon as the TV answers, a few tries over about 30 seconds.
    val needsVerification = setupComplete && gate.method != "bluetooth" && host.isNotBlank() && pinnedFingerprint.isBlank()
    LaunchedEffect(needsVerification, automaticPromptDismissed) {
        if (needsVerification && !automaticPromptDismissed) {
            repeat(10) {
                if (verification != null || pinnedFingerprint.isNotBlank()) return@LaunchedEffect
                val seen = ConnectionInput.readFingerprint(host)
                if (seen != null) {
                    verification = PendingVerification(host, pin, mac, seen, replacesPrevious = false)
                    return@LaunchedEffect
                }
                delay(3_000)
            }
        }
    }

    // What the person should know while Wi-Fi cannot be used because of the certificate.
    val securityNotice by produceState("") {
        while (true) {
            value = when {
                link.certificateMismatch ->
                    "The TV's security certificate is not the one you verified. If you reinstalled TV Dash, tap Change TV, then " +
                        "Connect, and compare the security code. If not, do not continue: another device may be answering at this address."
                link.needsVerification ->
                    "Wi-Fi control is off until you verify the TV's security code: tap Change TV, then Connect."
                else -> ""
            }
            delay(2_000)
        }
    }

    val inSetup = !setupComplete || changingTv
    LaunchedEffect(inSetup) { if (!inSetup) loadApps() }

    when {
        inSetup && setupPage == "bluetooth" -> BluetoothSetupScreen(
            pairing = graph.pairing,
            bluetooth = graph.bluetooth,
            connection = graph.connection,
            onBack = { setupPage = "ip" },
            onConnected = {
                // Start the keep-alive while the app is visible; the service shares graph.btData.
                BluetoothConnectionService.startIfConfigured(ctx)
                // The second way in, and only after the Bluetooth keyboard link to the TV is really up.
                gate.completeWithBluetooth()
                setupComplete = true
                changingTv = false
                setupPage = "ip"
                screen = "remote"
            }
        )
        inSetup -> SetupForm(
            host = host, onHostChange = { host = it; hostError = false },
            pin = pin, onPinChange = { pin = it; pinError = false },
            mac = mac, onMacChange = { mac = it; macError = false },
            hostError = hostError, pinError = pinError, macError = macError,
            checking = checking,
            canCancel = setupComplete,
            onConnect = { connectWithIp() },
            onSetUpBluetooth = { setupPage = "bluetooth" },
            onCancel = { changingTv = false },
            footer = { UpdateRow(upd, scope) }
        )
        screen == "diagnostics" -> {
            BackHandler { screen = "remote" }
            ConnectionDiagnosticsScreen(graph, onBack = { screen = "remote" })
        }
        screen == "apps" -> {
            BackHandler { screen = "remote" }
            AppsPage(apps, msg, onBack = { screen = "remote" }) { pkg -> send("launch?pkg=$pkg"); screen = "remote" }
        }
        else -> RemoteScreen(
            viewModel = viewModel,
            youTube = yt,
            youTubeMode = ytMode,
            onYouTubeModeChange = { ytMode = it; prefs.edit().putBoolean("yt_mode", it).apply() },
            onOpenAppsList = { screen = "apps"; loadApps() },
            onChangeTv = { changingTv = true; setupPage = "ip" },
            notice = msg.ifEmpty { securityNotice },
            footer = { openGuidelines ->
                FitOutlinedButton(
                    label = "Connection diagnostics",
                    onClick = { screen = "diagnostics" },
                    modifier = Modifier.fillMaxWidth()
                )
                BluetoothFallbackPanel(graph.bluetooth, onChanged = { graph.connection.retryKeyboard() })
                UpdateRow(upd, scope, onGuidelines = openGuidelines)
            }
        )
    }

    verification?.let { request ->
        VerifyTvDialog(
            request = request,
            onConfirm = {
                verification = null
                verifiedThisSession = request.fingerprint
                scope.launch {
                    checking = true
                    finishWithIp(request.host, request.pin, request.mac, request.fingerprint)
                    checking = false
                }
            },
            onCancel = {
                verification = null
                automaticPromptDismissed = true
            }
        )
    }
}

/** Asks the person to compare the security code. Nothing has been sent to the TV when this is shown. */
@Composable
private fun VerifyTvDialog(request: PendingVerification, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Verify your TV") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Wi-Fi control is now encrypted. On the TV, open TV Dash. The Security code card must show exactly this code:")
                Text(
                    TvTls.shortCode(request.fingerprint),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (request.replacesPrevious) {
                    Text(
                        "This is not the TV you verified before. That is normal after TV Dash is reinstalled, or when you choose " +
                            "another TV. If neither is true, tap Cancel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "If the codes differ, tap Cancel: another device on this Wi-Fi may be pretending to be the TV. " +
                        "Nothing has been sent to the TV yet, and the PIN is sent only after you confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { FitTextButton(label = "It matches", onClick = onConfirm) },
        dismissButton = { FitTextButton(label = "Cancel", onClick = onCancel) }
    )
}

/**
 * The way-in form: IP address and PIN with Connect, or Bluetooth. Connect does not open the remote by itself; it only
 * asks [onConnect], which checks the fields, the TV's security code and the TV first.
 */
@Composable
private fun SetupForm(
    host: String, onHostChange: (String) -> Unit,
    pin: String, onPinChange: (String) -> Unit,
    mac: String, onMacChange: (String) -> Unit,
    hostError: Boolean, pinError: Boolean, macError: Boolean,
    checking: Boolean,
    canCancel: Boolean,
    onConnect: () -> Unit,
    onSetUpBluetooth: () -> Unit,
    onCancel: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Connect to your TV", style = MaterialTheme.typography.titleLarge)
        Text(
            "Open TV Dash on the TV. The Phone remote card shows the PIN and the IP address. The Security code card shows " +
                "a code you will be asked to compare, so the link can be encrypted."
        )
        OutlinedTextField(
            value = host, onValueChange = onHostChange, label = { Text("TV IP address") },
            isError = hostError, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = pin, onValueChange = onPinChange, label = { Text("PIN") },
            isError = pinError, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = mac, onValueChange = onMacChange,
            label = { Text("TV MAC address (optional, for Wake-on-LAN)") },
            supportingText = { Text("TV Settings > Device Preferences > About > Status") },
            isError = macError, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        FitButton(
            label = if (checking) "Checking..." else "Connect",
            onClick = onConnect,
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
             text = "OR", // Capitalized to match your image
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth(0.9f) // Restricts the component to exactly 90% screen width
             .align(Alignment.CenterHorizontally) // Centers it perfectly inside your Column
             .padding(vertical = 16.dp) // Adjust breathing room above/below the divider
             .drawBehind {
             val strokeWidth = 1.dp.toPx() // Adjust line thickness here
             val centerY = size.height / 2
            
             // Gap size between the line endings and the text bounds
             val textGap = 12.dp.toPx() 

             // Get the actual width of the rendered text dynamically
             // If text layout isn't ready yet, defaults to a safe fallback
             val textWidth = drawContext.canvas.nativeCanvas.let { _ ->
                // Approximate width calculation based on text bounds
                // Ensures compatibility across all versions of Compose
                (size.width * 0.15f) 
            }

            val leftLineEnd = (size.width / 2) - (textWidth / 2) - textGap
            val rightLineStart = (size.width / 2) + (textWidth / 2) + textGap

            // Left Line
            drawLine(
                color = color.copy(alpha = 0.4f), // Softens the divider line like in the image
                start = Offset(0f, centerY),
                end = Offset(leftLineEnd, centerY),
                strokeWidth = strokeWidth
            )

            // Right Line
            drawLine(
                color = color.copy(alpha = 0.4f), 
                start = Offset(rightLineStart, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = strokeWidth
            )
            }
        )

        FitOutlinedButton(
            label = "Set up Bluetooth",
            onClick = onSetUpBluetooth,
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        )
        if (canCancel) FitTextButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        footer()
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
