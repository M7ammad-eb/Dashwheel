package com.openauto.dash.companion

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Support
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openauto.dash.link.DisplayPair
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.TypeText
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The companion's only screen. Before a car is paired, only the pairing:
 * scanning the code the launcher shows (or opening its dashwheel://pair
 * link), then confirming. Then four tabs: Home, which follows the moment
 * (the car linked: Road watch, typing, help; parked: where it is, the last
 * drive), Drives, Road watch and Tools; Settings (the link, what the car is
 * allowed, the paired cars, updates) opens from the gear above them.
 */
/** The companion's own download, which the launcher's pairing screen also shows as a QR code. */
private const val COMPANION_APK = "dashwheel-companion.apk"

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the checklist re-reads what the driver just allowed. */
    private var resumes by mutableIntStateOf(0)
    private var offer by mutableStateOf<PairingOffer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The companion is dark whatever the phone's theme: light icons on both bars.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        PairedUnits.load(this)
        CarSpot.load(this)
        DriveJournal.load(this)
        CarBluetooth.load(this)
        ObdRelay.load(this)
        FuelJournal.load(this)
        CarNewsStore.load(this)
        // The car's link may be off (at home after a drive): the computer can still fetch the clips.
        ClipServer.start(this)
        takeOffer(intent)
        setContent {
            CompanionTheme {
                CompanionScreen(
                    resumes = resumes,
                    offer = offer,
                    onScanned = { text ->
                        if (DashCodes.find(text) != null) {
                            // A dashboard's QR code: straight to the car, nothing to pair.
                            val sent = DashCodes.send(text)
                            Toast.makeText(this, getString(if (sent) R.string.dash_share_sent else R.string.keyboard_not_connected), Toast.LENGTH_LONG).show()
                        } else {
                            val parsed = PairingOffer.parse(text)
                            if (parsed == null) explainWrongCode(text)
                            offer = parsed
                        }
                    },
                    onOfferDone = { offer = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOffer(intent)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        LinkService.sync(this)
        CompanionUpdate.check(this)
        // Back from the system settings, maybe with contacts or the calendar just allowed.
        PhoneLists.recheck()
    }

    /**
     * The launcher shows two codes; the download one (the release's APK, or
     * the Play listing in the Play edition) is the easy one to scan by mistake.
     */
    private fun explainWrongCode(text: String) {
        val downloadCode = text.contains(COMPANION_APK, ignoreCase = true) ||
            text.contains("play.google.com/store/apps/details", ignoreCase = true)
        val message = if (downloadCode) {
            getString(R.string.scanned_download_code)
        } else {
            getString(R.string.invalid_code_read, text.trim().take(60))
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun takeOffer(intent: Intent?) {
        // The "update ready" notification: straight to Android's installer.
        if (intent?.getBooleanExtra(CompanionUpdate.EXTRA_INSTALL, false) == true) {
            CompanionUpdate.installWhenReady(this)
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        offer = PairingOffer.parse(data.toString())
        if (offer == null) explainWrongCode(data.toString())
        // Handled once: a rotation must not ask again.
        setIntent(Intent(this, MainActivity::class.java))
    }
}

@Composable
private fun CompanionScreen(resumes: Int, offer: PairingOffer?, onScanned: (String) -> Unit, onOfferDone: () -> Unit) {
    val context = LocalContext.current
    val units by PairedUnits.units.collectAsState()
    val state by LinkServer.state.collectAsState()
    val drives by DriveJournal.drives.collectAsState()
    val fills by FuelJournal.fills.collectAsState()
    val news by CarNewsStore.notices.collectAsState()
    var enabled by remember { mutableStateOf(PairedUnits.isEnabled(context)) }
    var removing by remember { mutableStateOf<PairedUnit?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onScanned) }
    val scanPrompt = stringResource(R.string.pair_hint)
    val startScan = {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(scanPrompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
    val setup = rememberSetupChecks(resumes)
    val paired = units.isNotEmpty()
    val connected = state is LinkState.Connected
    val carBluetooth by CarBluetooth.car.collectAsState()
    val inCar by CarBluetooth.inCar.collectAsState()
    // Which Bluetooth list is open: the car's own ("Start in the car") or the OBD adapter's.
    var picking by remember { mutableStateOf<BluetoothPick?>(null) }
    var wanted by remember { mutableStateOf(BluetoothPick.CAR) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) picking = wanted else Toast.makeText(context, R.string.car_auto_permission, Toast.LENGTH_LONG).show()
    }
    val pick: (BluetoothPick) -> Unit = { what ->
        wanted = what
        if (CarBluetooth.hasPermission(context)) picking = what
        else ask(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) { askBluetooth.launch(it.single()) }
    }
    val onAuto: (Boolean) -> Unit = { on -> if (on) pick(BluetoothPick.CAR) else CarBluetooth.choose(context, null) }
    // What "Type on the car" holds, kept here so a rotation, another tab or the link dropping
    // doesn't empty it while the car's field still shows the text.
    var typed by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    // Paired, the screen splits in tabs; before that there is only the pairing. Settings and
    // a tool open over the tabs. Remembered before the full screens below return early, or
    // they would be forgotten while one is open: Car from photos closed from Tools landed on Home.
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<Tool?>(null) }
    // The car from three photos takes the whole screen while open.
    var fromPhotos by rememberSaveable { mutableStateOf(false) }
    if (fromPhotos) {
        CarFromPhotosScreen(connected, onClose = { fromPhotos = false })
        return
    }
    // ...and so does the car from a 3D model.
    var fromModel by rememberSaveable { mutableStateOf(false) }
    // Opened with a model chosen through "Choose a file" ([CarLookCard]).
    var modelChosen by rememberSaveable { mutableStateOf(false) }
    if (fromModel) {
        CarFromModelScreen(connected, onClose = { fromModel = false; modelChosen = false }, newModel = modelChosen)
        return
    }

    // The touchpad takes the whole screen too, while the car is linked.
    var touchpad by rememberSaveable { mutableStateOf(false) }
    if (touchpad && paired) {
        TouchpadScreen(connected, onClose = { touchpad = false })
        return
    }

    val shown = if (paired) tab else Tab.HOME
    // Back closes the settings or the tool.
    val opened = tool?.takeIf { paired }
    BackHandler(enabled = settings || opened != null) { if (opened != null) tool = null else settings = false }
    val update by CompanionUpdate.status.collectAsState()
    // A dot on the gear where something waits: setup left to allow (once there is a car to allow it for), an update to install.
    val flagged = (paired && setup.missing > 0) || update is CompanionUpdate.Status.Ready
    val onToggle: (Boolean) -> Unit = {
        enabled = it
        PairedUnits.setEnabled(context, it)
        LinkService.sync(context)
    }

    Scaffold(
        containerColor = CompanionColors.Background,
        bottomBar = {
            if (paired && !settings && opened == null) {
                NavigationBar(containerColor = CompanionColors.Surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = shown == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = CompanionColors.Blue.copy(alpha = 0.22f))
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                opened != null -> {
                    item { BackRow(stringResource(R.string.tab_tools), onBack = { tool = null }) }
                    item {
                        when (opened) {
                            Tool.PHOTO -> PhotoAskCard(connected)
                            Tool.RESCUE -> RescueCard(connected)
                            Tool.LOOK -> CarLookCard(
                                connected, onFromPhotos = { fromPhotos = true }, onFromModel = { fromModel = true },
                                onModelChosen = { modelChosen = true; fromModel = true }
                            )
                            Tool.REGISTRATION -> RegistrationCard(connected)
                            Tool.OBD -> ObdRelayCard(onPick = { pick(BluetoothPick.OBD) })
                        }
                    }
                }
                settings -> {
                    item { BackRow(stringResource(R.string.settings_title), onBack = { settings = false }) }
                    if (paired) {
                        item { SectionTitle(stringResource(R.string.settings_connection), top = 2.dp) }
                        item { ConnectionCard(enabled, onToggle, carBluetooth?.name, onAuto) }
                    }
                    item { SectionTitle(stringResource(R.string.setup_title), top = if (paired) 14.dp else 2.dp) }
                    item { SetupPanel(setup) }
                    if (paired) {
                        item { SectionTitle(stringResource(R.string.cars_title)) }
                        items(units, key = { it.id }) { unit -> CarRow(unit, onRemove = { removing = unit }) }
                        item {
                            TextButton(onClick = startScan, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.pair_another))
                            }
                        }
                    }
                    item { SectionTitle(stringResource(R.string.update_section)) }
                    item { AppVersionCard(update) }
                }
                shown == Tab.HOME -> {
                    item { TopRow(onScan = startScan, onSettings = { settings = true }, flagged = flagged) }
                    item {
                        HeroCard(
                            look = when {
                                !paired -> StatusLook.NO_CAR
                                !enabled -> StatusLook.OFF
                                connected -> StatusLook.CONNECTED
                                carBluetooth != null && !inCar -> StatusLook.ASLEEP
                                state == LinkState.Unavailable -> StatusLook.UNAVAILABLE
                                else -> StatusLook.WAITING
                            },
                            carName = (state as? LinkState.Connected)?.unitName?.takeIf { it.isNotBlank() } ?: units.firstOrNull()?.name,
                            carBluetooth = carBluetooth?.name,
                            enabled = enabled,
                            canToggle = paired,
                            onToggle = onToggle,
                            onPair = startScan
                        )
                    }
                    updateCard(context, update)
                    if (!paired) {
                        // Nothing is asked before there is a car to give it to.
                        item { HintCard(stringResource(R.string.pair_setup_later)) }
                    } else {
                        if (setup.missing > 0) item { SetupNudge(setup.missing, onOpen = { settings = true }) }
                        if (news.isNotEmpty()) item { CarNewsCard(news) }
                        if (connected) {
                            // In the car: what is used on the way.
                            item { RoadWatchStartCard() }
                            item { KeyboardCard(typed, onField = { typed = it }, connected = connected) }
                            item { Panel { ToolRow(Icons.Filled.Mouse, stringResource(R.string.touchpad_title), stringResource(R.string.touchpad_detail)) { touchpad = true } } }
                            item { QuickTools(onOpen = { tool = it }) }
                        } else {
                            // Parked: where the car is, and how the last drive went.
                            item { CarSpotCard() }
                            drives.firstOrNull()?.let { latest -> item { LastDriveGlance(latest, onAll = { tab = Tab.DRIVES }) } }
                        }
                    }
                }
                shown == Tab.DRIVES -> {
                    item { SectionTitle(stringResource(R.string.drives_title), top = 2.dp) }
                    val latest = drives.firstOrNull()
                    if (latest == null) {
                        item { HintCard(stringResource(R.string.drives_none)) }
                    } else {
                        // "Under way" only while the car is linked: once it is gone, the drive it reported last is simply the last one.
                        item { LatestDriveCard(latest, underWay = latest.ongoing && connected) }
                        item { WeekCard(drives) }
                        item { MonthCard(drives, fills) }
                        driveHistory(drives.drop(1).take(PAST_DRIVES_SHOWN))
                    }
                }
                shown == Tab.ROAD -> {
                    item { SectionTitle(stringResource(R.string.road_title), top = 2.dp) }
                    item { RoadWatchCard(connected) }
                    item { ClipsCard() }
                }
                else -> {
                    item { SectionTitle(stringResource(R.string.tab_tools), top = 2.dp) }
                    item {
                        ToolGroup(stringResource(R.string.tools_fix)) {
                            ToolRow(Icons.Filled.PhotoCamera, stringResource(R.string.photo_title), stringResource(R.string.tools_photo_detail)) { tool = Tool.PHOTO }
                            ToolRow(Icons.Filled.Support, stringResource(R.string.rescue_title), stringResource(R.string.rescue_detail), CompanionColors.Red) { tool = Tool.RESCUE }
                        }
                    }
                    item {
                        ToolGroup(stringResource(R.string.tools_yours)) {
                            ToolRow(Icons.Filled.Image, stringResource(R.string.car_look_title), stringResource(R.string.tools_look_detail), CompanionColors.Teal) { tool = Tool.LOOK }
                            ToolRow(Icons.Filled.Description, stringResource(R.string.reg_title), stringResource(R.string.tools_reg_detail), CompanionColors.Teal) { tool = Tool.REGISTRATION }
                        }
                    }
                    item {
                        ToolGroup(stringResource(R.string.tools_data)) {
                            val (line, tint) = obdRelayLine()
                            ToolRow(Icons.Filled.Speed, stringResource(R.string.obd_relay_title), line, tint) { tool = Tool.OBD }
                        }
                    }
                    item {
                        // Not a tool to open: the phone's own share sheet ([SendToCarActivity]), said here so it is found.
                        Panel { ToolRow(Icons.Filled.Share, stringResource(R.string.send_to_car), stringResource(R.string.tools_send_detail), onClick = null) }
                    }
                }
            }
        }
    }

    if (offer != null && offer.kind == PairingOffer.Kind.DISPLAY) {
        // A second screen's code: this phone only carries it to the car it is linked to.
        val car = (state as? LinkState.Connected)?.unitName
        AlertDialog(
            onDismissRequest = onOfferDone,
            containerColor = CompanionColors.SurfaceHigh,
            icon = { Icon(Icons.Filled.Tv, contentDescription = null) },
            title = { Text(stringResource(R.string.display_confirm_title, offer.unitName)) },
            text = { Text(stringResource(if (car != null) R.string.display_confirm_body else R.string.display_needs_car)) },
            confirmButton = {
                if (car != null) {
                    Button(onClick = {
                        LinkServer.send(DisplayPair(offer.toUri()))
                        Toast.makeText(context, context.getString(R.string.display_sent, car), Toast.LENGTH_LONG).show()
                        onOfferDone()
                    }) { Text(stringResource(R.string.display_confirm_send)) }
                }
            },
            dismissButton = { TextButton(onClick = onOfferDone) { Text(stringResource(R.string.cancel)) } }
        )
    } else if (offer != null) {
        AlertDialog(
            onDismissRequest = onOfferDone,
            containerColor = CompanionColors.SurfaceHigh,
            icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
            title = { Text(stringResource(R.string.confirm_title, offer.unitName)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    PairedUnits.add(context, offer)
                    enabled = true
                    PairedUnits.setEnabled(context, true)
                    LinkService.sync(context)
                    onOfferDone()
                }) { Text(stringResource(R.string.confirm_allow)) }
            },
            dismissButton = { TextButton(onClick = onOfferDone) { Text(stringResource(R.string.cancel)) } }
        )
    }

    picking?.let { what ->
        val obd = what == BluetoothPick.OBD
        BluetoothPicker(
            title = stringResource(if (obd) R.string.obd_relay_pick else R.string.car_auto_pick),
            hint = stringResource(if (obd) R.string.obd_relay_pick_hint else R.string.car_auto_pick_hint),
            devices = remember(what) { CarBluetooth.bonded(context, if (obd) OBD_NAMES else units.map { it.name }) },
            onPick = { device ->
                picking = null
                if (obd) ObdRelay.choose(context, device) else CarBluetooth.choose(context, device)
            },
            onDismiss = { picking = null }
        )
    }

    removing?.let { unit ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = CompanionColors.SurfaceHigh,
            title = { Text(stringResource(R.string.remove_title, unit.name)) },
            text = { Text(stringResource(R.string.remove_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        PairedUnits.remove(context, unit.id)
                        LinkService.sync(context)
                        removing = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** The screen's tabs, once a car is paired; Settings opens from the gear on Home. */
private enum class Tab(val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    DRIVES(R.string.drives_title, Icons.Filled.Route),
    ROAD(R.string.road_title, Icons.Filled.Traffic),
    TOOLS(R.string.tab_tools, Icons.Filled.Build)
}

/** What the Tools tab opens over itself: one card, full screen. */
private enum class Tool { PHOTO, RESCUE, LOOK, REGISTRATION, OBD }

/** The top of Home: the scanner for any of the car's codes, and Settings with its dot. */
@Composable
private fun TopRow(onScan: () -> Unit, onSettings: () -> Unit, flagged: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "DASHWHEEL", style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp,
            color = CompanionColors.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        // Pairing, a second screen's code, a dashboard's: the scan tells them apart.
        IconButton(onClick = onScan) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.scan_code))
        }
        IconButton(onClick = onSettings) {
            BadgedBox(badge = { if (flagged) Badge(containerColor = CompanionColors.Amber) }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
    }
}

/** The top of a screen opened over the tabs: back, and where it goes back to. */
@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Two tiles under Home's typing while the car is linked: the help most wanted in the car. */
@Composable
private fun QuickTools(onOpen: (Tool) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickTile(Icons.Filled.PhotoCamera, stringResource(R.string.photo_title), CompanionColors.Blue, Modifier.weight(1f)) { onOpen(Tool.PHOTO) }
        QuickTile(Icons.Filled.Support, stringResource(R.string.rescue_title), CompanionColors.Red, Modifier.weight(1f)) { onOpen(Tool.RESCUE) }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

/** One group of the Tools tab: its name, then its rows on one card. */
@Composable
private fun ToolGroup(title: String, rows: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
            color = CompanionColors.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Panel { rows() }
    }
}

/** A row of the Tools tab, opening its tool; [onClick] null for one that only says where to find it. */
@Composable
private fun ToolRow(icon: ImageVector, title: String, detail: String, tint: Color = CompanionColors.Blue, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Box(Modifier.weight(1f)) { CardHeading(icon, title, detail, tint) }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = CompanionColors.Muted)
    }
}

/** Settings' link card: the sharing switch, and "Start in the car" while sharing is on. */
@Composable
private fun ConnectionCard(enabled: Boolean, onToggle: (Boolean) -> Unit, carBluetooth: String?, onAuto: (Boolean) -> Unit) {
    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CardHeading(
                        Icons.Filled.Wifi, stringResource(R.string.share_toggle),
                        // On, Home says how the link is doing; off, what that means.
                        if (enabled) null else stringResource(R.string.status_off_detail),
                        if (enabled) CompanionColors.Teal else CompanionColors.Muted
                    )
                }
                Spacer(Modifier.width(12.dp))
                val label = stringResource(R.string.share_toggle)
                Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = label })
            }
            if (enabled) StartInCarRow(carBluetooth, onAuto)
        }
    }
}

/** "Start in the car": sharing follows the car's Bluetooth instead of running all day. */
@Composable
private fun StartInCarRow(carBluetooth: String?, onAuto: (Boolean) -> Unit) {
    val label = stringResource(R.string.car_auto_title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CompanionColors.Background.copy(alpha = 0.45f))
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = CompanionColors.Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                carBluetooth?.let { stringResource(R.string.car_auto_on, it) } ?: stringResource(R.string.car_auto_off),
                style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted
            )
        }
        Switch(checked = carBluetooth != null, onCheckedChange = onAuto, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** Home's pointer to Settings while something is left to allow. */
@Composable
private fun SetupNudge(missing: Int, onOpen: () -> Unit) {
    Panel(onClick = onOpen) {
        Row(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeading(
                    Icons.Filled.Settings, stringResource(R.string.setup_finish),
                    pluralStringResource(R.plurals.setup_left, missing, missing), CompanionColors.Amber
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = CompanionColors.Muted)
        }
    }
}

/** Home's card for a new companion build, unless "Later" was said to it. */
private fun LazyListScope.updateCard(context: Context, update: CompanionUpdate.Status) {
    if (update == CompanionUpdate.Status.None || CompanionUpdate.isDismissed(context, update)) return
    item { UpdatePanel(update, canDismiss = true) }
}

/** The installed version, and the update on offer or a button to look for one. */
@Composable
private fun AppVersionCard(update: CompanionUpdate.Status) {
    val context = LocalContext.current
    if (update != CompanionUpdate.Status.None) {
        UpdatePanel(update, canDismiss = false)
        return
    }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    // The Play edition has no updater of its own: the version, and where updates come from.
    if (Edition.play) {
        Panel {
            Column(Modifier.padding(18.dp)) {
                CardHeading(Icons.Filled.SystemUpdate, stringResource(R.string.update_installed, version), stringResource(R.string.update_play_detail), CompanionColors.Teal)
            }
        }
        return
    }
    // "Up to date" only once GitHub has answered; a check that failed says so.
    val check by CompanionUpdate.checked.collectAsState()
    val (line, tint) = when (val c = check) {
        CompanionUpdate.Check.Never -> stringResource(R.string.update_auto_detail) to CompanionColors.Blue
        CompanionUpdate.Check.Checking -> stringResource(R.string.update_checking) to CompanionColors.Blue
        is CompanionUpdate.Check.Done ->
            stringResource(R.string.update_checked_at, android.text.format.DateFormat.getTimeFormat(context).format(Date(c.at))) to CompanionColors.Teal
        CompanionUpdate.Check.Failed -> stringResource(R.string.update_check_failed) to CompanionColors.Amber
    }
    Panel {
        Column(Modifier.padding(18.dp)) {
            CardHeading(Icons.Filled.SystemUpdate, stringResource(R.string.update_installed, version), line, tint)
            OutlinedButton(
                onClick = { CompanionUpdate.check(context, force = true) },
                enabled = check != CompanionUpdate.Check.Checking,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.update_check))
            }
        }
    }
}

/** A newer build: download it, follow the download, install it. */
@Composable
private fun UpdatePanel(update: CompanionUpdate.Status, canDismiss: Boolean) {
    val context = LocalContext.current
    var hidden by remember(update) { mutableStateOf(false) }
    if (hidden) return
    val line = when (update) {
        is CompanionUpdate.Status.Available -> stringResource(R.string.update_available, update.release.name)
        is CompanionUpdate.Status.Downloading -> stringResource(R.string.update_downloading, update.percent)
        is CompanionUpdate.Status.Ready -> stringResource(R.string.update_ready_text, update.release.name)
        is CompanionUpdate.Status.Failed -> stringResource(R.string.update_failed)
        CompanionUpdate.Status.None -> return
    }
    val tint = if (update is CompanionUpdate.Status.Failed) CompanionColors.Amber else CompanionColors.Blue
    Panel {
        Column(Modifier.padding(18.dp)) {
            CardHeading(Icons.Filled.SystemUpdate, stringResource(R.string.update_title), line, tint)
            if (update is CompanionUpdate.Status.Downloading) {
                LinearProgressIndicator(
                    progress = { update.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(6.dp).clip(RoundedCornerShape(50)),
                    color = CompanionColors.Blue, trackColor = CompanionColors.Line, drawStopIndicator = {}
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (update) {
                        is CompanionUpdate.Status.Ready -> Button(onClick = { CompanionUpdate.install(context) }) { Text(stringResource(R.string.update_install)) }
                        is CompanionUpdate.Status.Failed -> Button(onClick = { CompanionUpdate.download(context) }) { Text(stringResource(R.string.update_retry)) }
                        else -> Button(onClick = { CompanionUpdate.download(context) }) { Text(stringResource(R.string.update_download)) }
                    }
                    if (canDismiss) {
                        TextButton(onClick = { CompanionUpdate.dismiss(context); hidden = true }) { Text(stringResource(R.string.update_later)) }
                    }
                }
            }
        }
    }
}

/** What the hero says, from the link and the switch. */
private enum class StatusLook { NO_CAR, OFF, ASLEEP, WAITING, UNAVAILABLE, CONNECTED }

/**
 * Home's status: the car's name, the link's state in its colour with what to
 * do about it, and the sharing switch ("Start in the car" is in Settings).
 * With no car paired, the button to pair one. The link's technical last
 * event stays behind "Details".
 */
@Composable
private fun HeroCard(
    look: StatusLook,
    carName: String?,
    /** The car's Bluetooth sharing is tied to ("Start in the car"), or null. */
    carBluetooth: String?,
    enabled: Boolean,
    canToggle: Boolean,
    onToggle: (Boolean) -> Unit,
    onPair: () -> Unit
) {
    // The link's last event, for when the car does not connect (not translated: technical).
    val lastEvent = LinkServer.lastEvent.collectAsState().value
    var details by rememberSaveable { mutableStateOf(false) }
    val tint = when (look) {
        StatusLook.CONNECTED -> CompanionColors.Teal
        StatusLook.WAITING -> CompanionColors.Amber
        StatusLook.UNAVAILABLE -> CompanionColors.Red
        StatusLook.OFF -> CompanionColors.Muted
        StatusLook.NO_CAR, StatusLook.ASLEEP -> CompanionColors.Blue
    }
    val status = when (look) {
        StatusLook.NO_CAR -> stringResource(R.string.status_no_car)
        StatusLook.OFF -> stringResource(R.string.status_off)
        StatusLook.ASLEEP -> stringResource(R.string.status_sleeping)
        StatusLook.WAITING -> stringResource(R.string.status_waiting)
        StatusLook.UNAVAILABLE -> stringResource(R.string.status_unavailable)
        StatusLook.CONNECTED -> stringResource(R.string.status_linked)
    }
    val detail = when (look) {
        StatusLook.NO_CAR -> stringResource(R.string.pair_hint)
        StatusLook.OFF -> stringResource(R.string.status_off_detail)
        StatusLook.ASLEEP -> stringResource(R.string.status_sleeping_detail, carBluetooth.orEmpty())
        StatusLook.WAITING -> stringResource(R.string.status_waiting_detail)
        StatusLook.UNAVAILABLE -> stringResource(R.string.status_unavailable_detail)
        StatusLook.CONNECTED -> stringResource(R.string.status_connected_detail)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.30f), CompanionColors.Surface, CompanionColors.Surface)))
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = tint.copy(alpha = 0.18f), modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp)) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        carName ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.titleSmall, color = tint, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (canToggle) {
                    // The switch speaks for itself to TalkBack: "Share with the car, on".
                    val label = stringResource(R.string.share_toggle)
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = label })
                }
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = CompanionColors.Muted, modifier = Modifier.padding(top = 14.dp))
            if ((look == StatusLook.WAITING || look == StatusLook.UNAVAILABLE) && lastEvent != null) {
                TextButton(onClick = { details = !details }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.status_details), style = MaterialTheme.typography.labelLarge)
                    Icon(if (details) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                if (details) {
                    Text(lastEvent, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = CompanionColors.Muted)
                }
            }
            if (look == StatusLook.NO_CAR) {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pair_car))
                }
            }
        }
    }
}

/**
 * The phone as the car's keyboard ([CarKeyboard]): the text field selected on
 * the car's screen follows this one as it is typed, and Enter runs it there.
 */
@Composable
private fun KeyboardCard(field: TextFieldValue, onField: (TextFieldValue) -> Unit, connected: Boolean) {
    val context = LocalContext.current
    // Only the answers to what was sent from here: not a share from earlier.
    var sentHere by rememberSaveable { mutableStateOf(false) }
    val answer by CarKeyboard.answer.collectAsState()

    fun change(value: TextFieldValue) {
        val changed = value.text != field.text
        onField(value)
        if (changed) {
            sentHere = true
            CarKeyboard.send(value.text, TypeText.Mode.REPLACE)
        }
    }

    fun enter() {
        sentHere = true
        CarKeyboard.send(field.text, TypeText.Mode.REPLACE, enter = true)
    }

    Panel {
        Column(Modifier.padding(18.dp)) {
            // Home shows it only while the car is linked; greyed for the moment the link drops, the text kept for its return.
            CardHeading(
                Icons.Filled.Keyboard, stringResource(R.string.keyboard_title),
                stringResource(if (connected) R.string.keyboard_detail else R.string.keyboard_not_connected),
                if (connected) CompanionColors.Blue else CompanionColors.Muted
            )
            OutlinedTextField(
                value = field,
                onValueChange = ::change,
                enabled = connected,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                placeholder = { Text(stringResource(R.string.keyboard_hint)) },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { enter() }),
                maxLines = 4
            )
            answer?.takeIf { sentHere && connected }?.let { a ->
                val good = a.status == CarKeyboard.Status.TYPED || a.status == CarKeyboard.Status.TYPING_ON
                Text(
                    stringResource(CarKeyboard.message(a.status)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (good) CompanionColors.Teal else CompanionColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // The car can't see its fields: one tap turns that on there.
                if (a.status == CarKeyboard.Status.TYPING_OFF) {
                    Button(onClick = { CarKeyboard.turnOn() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.keyboard_turn_on))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = connected, onClick = {
                    // The phone's clipboard, at the cursor: the car's field follows.
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) {
                        val from = minOf(field.selection.start, field.selection.end)
                        val to = maxOf(field.selection.start, field.selection.end)
                        val joined = field.text.substring(0, from) + text + field.text.substring(to)
                        change(TextFieldValue(joined, TextRange(from + text.length)))
                    }
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keyboard_paste))
                }
                TextButton(onClick = { change(TextFieldValue("")) }, enabled = connected) { Text(stringResource(R.string.keyboard_clear)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::enter, enabled = connected) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keyboard_enter))
                }
            }
        }
    }
}

/** An icon in a tinted circle, a title and a line under it: the head of most cards. */
@Composable
internal fun CardHeading(icon: ImageVector, title: String, detail: String?, tint: Color = CompanionColors.Blue) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted) }
        }
    }
}

/** What the car wants the driver to know ([CarNewsStore]): servicing, faults, the battery, the filter. */
@Composable
private fun CarNewsCard(news: List<com.openauto.dash.link.CarNotice>) {
    Panel {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.news_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            news.forEach { n ->
                val (icon, tint) = when (n.kind) {
                    com.openauto.dash.link.CarNotice.FAULT -> Icons.Filled.Warning to CompanionColors.Red
                    com.openauto.dash.link.CarNotice.BATTERY -> Icons.Filled.BatteryAlert to CompanionColors.Amber
                    com.openauto.dash.link.CarNotice.FILTER -> Icons.Filled.Route to CompanionColors.Amber
                    com.openauto.dash.link.CarNotice.SENSOR -> Icons.Filled.Insights to CompanionColors.Amber
                    else -> Icons.Filled.Build to CompanionColors.Blue
                }
                CardHeading(icon, n.title, n.text, tint)
            }
        }
    }
}

/** Where the car was left (see [CarSpot]), how long ago, and walking directions to it. */
@Composable
private fun CarSpotCard() {
    val context = LocalContext.current
    val spot by CarSpot.spot.collectAsState()
    val s = spot
    // "Left 50 min ago" moves on by itself; read once, it stayed at the minute the card was composed.
    val now = rememberWallClock(DateUtils.MINUTE_IN_MILLIS).longValue
    Panel {
        Column(Modifier.padding(18.dp)) {
            val line = when {
                s == null -> stringResource(R.string.car_spot_none)
                s.saved -> stringResource(R.string.car_spot_saved, agoText(s.at, now))
                else -> stringResource(R.string.car_spot_left, agoText(s.at, now))
            }
            CardHeading(Icons.Filled.LocalParking, stringResource(R.string.car_spot_title), line)
            if (s != null) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { open(context, Intent(Intent.ACTION_VIEW, walkingDirections(s))) },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.car_spot_walk))
                    }
                    OutlinedButton(
                        onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${s.lat},${s.lng}"))) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.car_spot_map))
                    }
                }
            }
        }
    }
}

/**
 * EXPERIMENTAL: the driver's own car on the car's screen ([CarLookSender]):
 * a car pack or a picture of the car from the side, picked here and sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarLookCard(connected: Boolean, onFromPhotos: () -> Unit, onFromModel: () -> Unit, onModelChosen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by CarLookSender.status.collectAsState()
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // A 3D model is drawn into the car's pictures first, not sent as it is.
        scope.launch {
            when (keepIfModel(context, uri)) {
                true -> onModelChosen()
                false -> Toast.makeText(context, R.string.car_model_unreadable, Toast.LENGTH_LONG).show()
                null -> CarLookSender.send(context, uri)
            }
        }
    }
    val line = when {
        status == CarLookSender.Status.SENDING -> stringResource(R.string.car_look_sending)
        status == CarLookSender.Status.SENT -> stringResource(R.string.car_look_sent)
        status == CarLookSender.Status.TOO_BIG -> stringResource(R.string.car_look_too_big)
        status == CarLookSender.Status.FAILED -> stringResource(R.string.car_look_failed)
        !connected -> stringResource(R.string.car_look_offline)
        else -> stringResource(R.string.car_look_detail)
    }
    val tint = when (status) {
        CarLookSender.Status.SENT -> CompanionColors.Teal
        CarLookSender.Status.TOO_BIG, CarLookSender.Status.FAILED -> CompanionColors.Amber
        else -> CompanionColors.Blue
    }
    Panel {
        Column(Modifier.padding(18.dp)) {
            CardHeading(Icons.Filled.Image, stringResource(R.string.car_look_title), line, tint)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Building from photos or a model works offline; only sending needs the car.
                Button(onClick = onFromPhotos) { Text(stringResource(R.string.car_photos_open)) }
                Button(onClick = onFromModel) { Text(stringResource(R.string.car_model_open)) }
                OutlinedButton(
                    onClick = { choose.launch(arrayOf("image/*", "application/zip", "application/octet-stream", "model/*")) },
                    enabled = connected && status != CarLookSender.Status.SENDING
                ) { Text(stringResource(R.string.car_look_choose)) }
            }
        }
    }
}

/** Which paired Bluetooth device the picker is for. */
private enum class BluetoothPick { CAR, OBD }

/** How OBD adapters usually call themselves, to list them first. */
private val OBD_NAMES = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI")

/**
 * OBD through this phone ([ObdRelay]): the adapter it holds for the car, and
 * what the relay is doing. With none chosen, the car uses its own Bluetooth.
 */
@Composable
private fun ObdRelayCard(onPick: () -> Unit) {
    val context = LocalContext.current
    val adapter by ObdRelay.adapter.collectAsState()
    val (line, tint) = obdRelayLine()
    Panel {
        Column(Modifier.padding(18.dp)) {
            CardHeading(Icons.Filled.Speed, stringResource(R.string.obd_relay_title), line, tint)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (adapter == null) {
                    Button(onClick = onPick) { Text(stringResource(R.string.obd_relay_choose)) }
                } else {
                    OutlinedButton(onClick = onPick) { Text(stringResource(R.string.obd_relay_change)) }
                    TextButton(onClick = { ObdRelay.choose(context, null) }) { Text(stringResource(R.string.obd_relay_stop)) }
                }
            }
        }
    }
}

/** What the OBD relay is doing, in words and its colour: its card and its row on the Tools tab. */
@Composable
private fun obdRelayLine(): Pair<String, Color> {
    val adapter by ObdRelay.adapter.collectAsState()
    val status by ObdRelay.status.collectAsState()
    val a = adapter
    val line = when {
        a == null -> stringResource(R.string.obd_relay_off)
        status == ObdRelay.Status.RELAYING -> stringResource(R.string.obd_relay_relaying, a.name)
        status == ObdRelay.Status.CONNECTING -> stringResource(R.string.obd_relay_connecting, a.name)
        status == ObdRelay.Status.FAILED -> stringResource(R.string.obd_relay_failed, a.name)
        else -> stringResource(R.string.obd_relay_ready, a.name)
    }
    val tint = when {
        a == null -> CompanionColors.Muted
        status == ObdRelay.Status.RELAYING -> CompanionColors.Teal
        status == ObdRelay.Status.FAILED -> CompanionColors.Amber
        else -> CompanionColors.Blue
    }
    return line to tint
}

/** The phone's paired Bluetooth devices to pick one from; the likeliest ones first. */
@Composable
private fun BluetoothPicker(title: String, hint: String, devices: List<CarBluetooth.Device>, onPick: (CarBluetooth.Device) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CompanionColors.SurfaceHigh,
        icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = CompanionColors.Muted)
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.car_auto_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.padding(top = 8.dp).height((devices.size.coerceAtMost(5) * 56).dp)) {
                    items(devices, key = { it.address }) { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(device) }
                                .padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = CompanionColors.Blue)
                            Spacer(Modifier.width(14.dp))
                            Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** One paired car, with Remove kept in its menu rather than a tap away. */
@Composable
private fun CarRow(unit: PairedUnit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Panel {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeading(
                    Icons.Filled.DirectionsCar, unit.name,
                    stringResource(R.string.paired_since, DateFormat.getDateInstance().format(Date(unit.pairedAt)))
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options), tint = CompanionColors.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove), color = CompanionColors.Red) },
                        onClick = { menu = false; onRemove() }
                    )
                }
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Panel {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CompanionColors.Muted, modifier = Modifier.padding(18.dp))
    }
}

/** "50 minutes ago", "Yesterday"... in the phone's language, as of [now]. */
private fun agoText(at: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** Walking directions in Google Maps (the app when installed, else the browser). */
private fun walkingDirections(s: CarSpotInfo): Uri =
    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lng}&travelmode=walking")

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier, top: Dp = 14.dp) {
    Text(
        text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 4.dp, top = top, bottom = 2.dp)
    )
}

/** What the setup checklist found, and how to fix each; re-read on every resume. */
private class SetupChecks(
    val listener: Boolean,
    /** Null below Android 13, where there is nothing to allow. */
    val post: Boolean?,
    val calls: Boolean,
    val agenda: Boolean,
    /** The phone's GPS for the car: location allowed all the time ([PhoneGpsShare]). */
    val location: Boolean,
    val battery: Boolean,
    /** "On my way" texts: optional, so not counted as left to do. */
    val texts: Boolean,
    val askPost: () -> Unit,
    val askCalls: () -> Unit,
    val askAgenda: () -> Unit,
    val askLocation: () -> Unit,
    val askTexts: () -> Unit
) {
    private val checks: List<Boolean> get() = listOfNotNull(listener, post, calls, agenda, location, battery)

    /** The steps that can be checked from here and are not done (the hotspot can't be). */
    val missing: Int get() = checks.count { !it }

    val total: Int get() = checks.size
}

@Composable
private fun rememberSetupChecks(resumes: Int): SetupChecks {
    val context = LocalContext.current
    // Re-read on every resume: the driver comes back from the system settings.
    val listener = remember(resumes) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val battery = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var postGranted by remember(resumes) { mutableStateOf(canPostNotifications(context)) }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { postGranted = it }
    var callsGranted by remember(resumes) { mutableStateOf(CALL_PERMISSIONS.all { granted(context, it) }) }
    val askCalls = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        callsGranted = CALL_PERMISSIONS.all { granted(context, it) }
        PhoneLists.recheck()
    }
    var textsGranted by remember(resumes) { mutableStateOf(TextSender.canSend(context)) }
    val askTexts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        textsGranted = TextSender.canSend(context)
        // The car learns it can now ask for "On my way" texts.
        LinkServer.send(LinkServer.abilities(context))
    }
    var agendaGranted by remember(resumes) { mutableStateOf(granted(context, Manifest.permission.READ_CALENDAR)) }
    val askAgenda = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        agendaGranted = it
        PhoneLists.recheck()
    }
    var locationGranted by remember(resumes) { mutableStateOf(PhoneGpsShare.allowed(context)) }
    // "All the time" is a second step Android only offers once the plain location is given.
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locationGranted = PhoneGpsShare.allowed(context)
        PhoneGpsShare.recheck(context)
    }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneGpsShare.allowedWhileInUse(context)) {
            ask(context, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { askBackground.launch(it.single()) }
        }
    }
    return SetupChecks(
        listener = listener,
        post = if (Build.VERSION.SDK_INT >= 33) postGranted else null,
        calls = callsGranted,
        agenda = agendaGranted,
        location = locationGranted,
        battery = battery,
        texts = textsGranted,
        askTexts = { ask(context, arrayOf(Manifest.permission.SEND_SMS)) { askTexts.launch(it.single()) } },
        askPost = { ask(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { askPost.launch(it.single()) } },
        askCalls = { ask(context, CALL_PERMISSIONS) { askCalls.launch(it) } },
        askAgenda = { ask(context, arrayOf(Manifest.permission.READ_CALENDAR)) { askAgenda.launch(it.single()) } },
        askLocation = {
            if (PhoneGpsShare.allowedWhileInUse(context)) {
                ask(context, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { askBackground.launch(it.single()) }
            } else {
                ask(context, LOCATION_PERMISSIONS) { askForeground.launch(it) }
            }
        }
    )
}

/**
 * Asks for [permissions] with [launch]; unless Android no longer asks (refused
 * twice), where the Allow button would silently do nothing: App info is
 * opened instead, where they can still be given. "Asked before" is kept, as
 * Android gives no rationale either before the first request.
 */
private fun ask(context: Context, permissions: Array<String>, launch: (Array<String>) -> Unit) {
    val activity = context as? Activity
    val missing = permissions.filterNot { granted(context, it) }
    val asked = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    val refusedForGood = activity != null && missing.isNotEmpty() &&
        missing.all { asked.getBoolean(it, false) && !activity.shouldShowRequestPermissionRationale(it) }
    if (refusedForGood) {
        Toast.makeText(context, R.string.permission_in_settings, Toast.LENGTH_LONG).show()
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        return
    }
    asked.edit().apply { missing.forEach { putBoolean(it, true) } }.apply()
    launch(permissions)
}

private const val PERMISSION_PREFS = "permissions_asked"

/**
 * The checklist in one card: how far along it is, then one slim row per
 * step, the ones left to do first.
 */
@Composable
private fun SetupPanel(setup: SetupChecks) {
    val context = LocalContext.current
    val done = setup.total - setup.missing
    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (setup.missing > 0) R.string.setup_finish else R.string.permissions_title),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.setup_progress, done, setup.total),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (setup.missing > 0) CompanionColors.Amber else CompanionColors.Teal
                )
            }
            LinearProgressIndicator(
                progress = { if (setup.total == 0) 1f else done / setup.total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).height(6.dp).clip(RoundedCornerShape(50)),
                color = if (setup.missing > 0) CompanionColors.Amber else CompanionColors.Teal,
                trackColor = CompanionColors.Line,
                drawStopIndicator = {}
            )
            val steps = buildList {
                add(StepInfo(Icons.Filled.Notifications, R.string.step_notifications, R.string.step_notifications_detail, setup.listener) {
                    open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                })
                setup.post?.let { add(StepInfo(Icons.Filled.NotificationsActive, R.string.step_post, R.string.step_post_detail, it, onFix = setup.askPost)) }
                add(StepInfo(Icons.Filled.Call, R.string.step_calls, R.string.step_calls_detail, setup.calls, onFix = setup.askCalls))
                add(StepInfo(Icons.Filled.Event, R.string.step_agenda, R.string.step_agenda_detail, setup.agenda, onFix = setup.askAgenda))
                add(StepInfo(Icons.Filled.MyLocation, R.string.step_location, R.string.step_location_detail, setup.location, onFix = setup.askLocation))
                add(StepInfo(Icons.Filled.BatteryFull, R.string.step_battery, R.string.step_battery_detail, setup.battery) {
                    open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                })
                add(StepInfo(Icons.Filled.Wifi, R.string.step_hotspot, R.string.step_hotspot_detail, null) {
                    if (!open(context, Intent("android.settings.TETHER_SETTINGS"))) open(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
                })
                if (TextSender.hasMessaging(context)) {
                    add(StepInfo(Icons.Filled.Sms, R.string.step_texts, R.string.step_texts_detail, setup.texts, optional = true, onFix = setup.askTexts))
                }
            }.sortedBy { if (it.done == false && !it.optional) 0 else 1 }
            steps.forEach { step ->
                StepRow(step)
                // Android 13+ greys out Notification access for apps installed outside a store.
                if (step.title == R.string.step_notifications && step.done == false) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 54.dp)) {
                        Text(stringResource(R.string.step_notifications_restricted), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text(stringResource(R.string.step_app_info)) }
                    }
                }
            }
        }
    }
}

/** One thing to set up. [done] null: can't be checked from here, always offers to open it. */
private class StepInfo(val icon: ImageVector, val title: Int, val detail: Int, val done: Boolean?, val optional: Boolean = false, val onFix: () -> Unit)

@Composable
private fun StepRow(step: StepInfo) {
    val tint = when (step.done) {
        false -> if (step.optional) CompanionColors.Blue else CompanionColors.Amber
        true -> CompanionColors.Teal
        null -> CompanionColors.Blue
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(step.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(step.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(step.detail), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted,
                maxLines = if (step.done == true) 1 else 3, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        when (step.done) {
            true -> Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.done), tint = CompanionColors.Teal, modifier = Modifier.size(26.dp))
            false -> Button(onClick = step.onFix, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.action_allow)) }
            null -> FilledTonalButton(onClick = step.onFix, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.step_open_settings)) }
        }
    }
}

private fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Who is calling (state, number, contact), answering / hanging up from the
 * car, and its Quick dial: the favourites, the last calls, calling one.
 */
private val CALL_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.CALL_PHONE
)

/** The plain location, asked before "all the time" ([PhoneGpsShare]). */
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
