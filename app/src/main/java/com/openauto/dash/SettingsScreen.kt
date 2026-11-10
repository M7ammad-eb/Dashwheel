package com.openauto.dash

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch

/*
 * The Settings screen: everything set once, full screen in two columns like
 * a car's own settings. It opens on an overview (SettingsOverview.kt): how
 * the adapter, the phone, the update and the servicing stand, what is not
 * allowed yet, a search, and the groups with what each holds now. A group
 * shows the categories down the left (along the top on an upright screen)
 * and its settings on the right, the deep ones (car profile, AI, servicing,
 * wheel buttons, language, the OBD adapter...) one tap further in the same
 * pane, with a back arrow (SettingsSheet.kt). One subject, one place, named
 * the way a driver thinks of it, in three runs: every day (alerts, sound,
 * look, dashboard), the car (the car, reading it, driving it) and set once
 * (phone and screens, the head unit, general with its expert rows). Groups are small
 * enough to list everything. What this unit cannot have is not listed: one
 * card per group says how many more root would bring ([RootLockCard]).
 */

internal enum class SettingsTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.settings_overview, Icons.Filled.Dashboard),
    // Every day: what the car says and shows, how it sounds and looks.
    ALERTS(R.string.settings_section_alerts, Icons.Filled.NotificationsActive),
    SOUND(R.string.settings_section_sound, Icons.Filled.VolumeUp),
    LOOK(R.string.settings_section_look, Icons.Filled.Palette),
    DASHBOARD(R.string.settings_section_dashboard, Icons.Filled.DashboardCustomize),
    // The car: who it is, reading it, driving it.
    CAR(R.string.settings_section_car, Icons.Filled.DirectionsCar),
    DIAGNOSTICS(R.string.settings_section_diagnostics, Icons.Filled.MonitorHeart),
    DRIVING(R.string.settings_section_driving, Icons.Filled.Speed),
    // Set once: what talks to the unit, the unit itself, the app, and the tools for the few.
    DEVICES(R.string.settings_section_devices, Icons.Filled.Devices),
    HEAD_UNIT(R.string.unit_card_title, Icons.Filled.DeveloperBoard),
    GENERAL(R.string.settings_section_general, Icons.Filled.Tune);

    companion object {
        /** The groups, without the overview that leads to them. */
        val groups: List<SettingsTab> = entries - OVERVIEW
    }
}

/** The settings that open further into the pane; Lab's open over Lab. */
internal enum class Deep {
    CAR, CAR_LOOK, REVERSE, SIGNALS, AI, UPKEEP, EXPLORER, WHEEL, PLACES, LANGUAGE, SECOND_SCREEN, LPG, MY_READINGS,
    OBD, UNITS, ACCESS, ABOUT, LAB, UNIT_TOOLS, MAP_SERVERS, API_KEYS, HEAD_UNIT, TPMS, SENSOR_WATCH, KEPT_CAMERAS, KEPT_LIMITS
}

/** Under this width the categories go along the top: a rail would leave the settings half a screen. */
internal val NARROW_SETTINGS = 800.dp

/** The theme choice and its setters, owned by the dashboard root. */
internal data class ThemeState(
    val mode: DashThemeMode,
    val appearance: DashAppearance,
    val effects: DashEffects,
    val onMode: (DashThemeMode) -> Unit,
    val onAppearance: (DashAppearance) -> Unit,
    val onEffects: (DashEffects) -> Unit,
    /** The bottom bar hides itself when unused, after [barHideSeconds] (BarAutoHide.kt). */
    val barAutoHide: Boolean,
    val barHideSeconds: Int,
    val onBarAutoHide: (Boolean) -> Unit,
    val onBarHideSeconds: (Int) -> Unit,
    /** The side rail in the bar's place, and tabs in it instead of swiping (Look, Navigation). */
    val rail: Boolean = false,
    val tabs: Boolean = false,
    val onRail: (Boolean) -> Unit = {},
    val onTabs: (Boolean) -> Unit = {},
    /** Puts the Home of the look in use on the dashboard (SkinLayouts.kt), with Undo. */
    val onArrangeHome: () -> Unit = {}
)

@Composable
internal fun SettingsScreen(
    m: TopBarModel,
    theme: ThemeState,
    initialTab: SettingsTab,
    /** Opens the OBD adapter picker (with its Bluetooth permission). */
    onPickObd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    // The deep pages open, the one on top showing: Lab opens its tools over itself.
    var stack by remember { mutableStateOf(emptyList<Deep>()) }
    val deep = stack.lastOrNull()
    var bootLogo by remember { mutableStateOf(false) }
    // The setting a search was for: its row is scrolled to in the pane it opens.
    var target by remember { mutableStateOf<String?>(null) }
    // Another category, or closing: the open sheet leaves first, as by its back arrow.
    val go: (SettingsTab, List<Deep>) -> Unit = { t, path ->
        OpenSheet.dismiss()
        stack = path
        tab = t
        target = null
    }
    val choose: (SettingsTab) -> Unit = { go(it, emptyList()) }
    val close = {
        OpenSheet.dismiss()
        onClose()
    }
    val open: (Deep) -> Unit = { stack = stack + it }
    val back = { stack = stack.dropLast(1) }
    // A turn of the screen rebuilds the dashboard, which opens Settings again only if they were open.
    DisposableEffect(Unit) {
        ScreenShape.settingsShown(true)
        onDispose { ScreenShape.settingsShown(false) }
    }

    val pane: @Composable (Modifier) -> Unit = { paneModifier ->
        Box(modifier = paneModifier) { CompositionLocalProvider(LocalSettingsTarget provides target) {
            if (deep != null) key(stack.size, deep) {
                CompositionLocalProvider(LocalSheetInPane provides true) {
                    when (deep) {
                        Deep.CAR -> CarSettingsDialog(onDismiss = back)
                        Deep.CAR_LOOK -> MyCarLookSheet(onDismiss = back)
                        Deep.REVERSE -> ReverseViewSheet(onDismiss = back)
                        Deep.SIGNALS -> SignalFinderSheet(onDismiss = back)
                        Deep.AI -> AiSettingsDialog(onDismiss = back)
                        Deep.UPKEEP -> UpkeepDialog(onDismiss = back)
                        Deep.EXPLORER -> PidExplorerDialog(onDismiss = back)
                        Deep.WHEEL -> SteeringWheelDialog(onDismiss = back)
                        Deep.PLACES -> PlacesSheet(onDismiss = back)
                        Deep.LANGUAGE -> LanguageSheet(onDismiss = back)
                        Deep.SECOND_SCREEN -> SecondScreenSheet(onDismiss = back)
                        Deep.LPG -> LpgTankSheet(onDismiss = back)
                        Deep.MY_READINGS -> MyReadingsSheet(onDismiss = back)
                        Deep.OBD -> ObdSheet(onPickObd = onPickObd, onDismiss = back)
                        Deep.UNITS -> UnitsSheet(onDismiss = back)
                        Deep.ACCESS -> AccessSheet(onPickObd = onPickObd, onDismiss = back)
                        Deep.ABOUT -> AboutSheet(m, onDismiss = back)
                        Deep.LAB -> LabSheet(open = open, onDismiss = back)
                        Deep.UNIT_TOOLS -> UnitToolsSheet(m, onBootLogo = { bootLogo = true }, onDismiss = back)
                        Deep.MAP_SERVERS -> MapServersSheet(onDismiss = back)
                        Deep.API_KEYS -> ApiKeysSheet(onDismiss = back)
                        Deep.HEAD_UNIT -> HeadUnitSheet(onDismiss = back)
                        Deep.TPMS -> UsbTpmsSheet(onDismiss = back)
                        Deep.SENSOR_WATCH -> SensorWatchSheet(onDismiss = back)
                        Deep.KEPT_CAMERAS -> KeptCamerasSheet(onDismiss = back)
                        Deep.KEPT_LIMITS -> KeptLimitsSheet(onDismiss = back)
                    }
                }
            }
            // Each category keeps its own place: a long one scrolled down does not move the next.
            if (deep == null) key(tab) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    when (tab) {
                        SettingsTab.OVERVIEW -> Unit
                        SettingsTab.ALERTS -> AlertsGrid()
                        SettingsTab.SOUND -> SoundPane()
                        SettingsTab.LOOK -> LookPane(theme)
                        SettingsTab.DASHBOARD -> DashboardPane(m, theme)
                        SettingsTab.CAR -> CarPane(open)
                        SettingsTab.DIAGNOSTICS -> DiagnosticsPane(open)
                        SettingsTab.DRIVING -> DrivingPane(m, open)
                        SettingsTab.DEVICES -> DevicesPane(open)
                        SettingsTab.HEAD_UNIT -> HeadUnitPane(open)
                        SettingsTab.GENERAL -> GeneralPane(m, open)
                    }
                }
                MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
            }
        } }
    }

    SolidCard(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            when {
                // The overview has the whole card: its tiles are the categories.
                tab == SettingsTab.OVERVIEW -> SettingsOverview(
                    m = m,
                    theme = theme,
                    narrow = maxWidth < NARROW_SETTINGS,
                    onGo = { t, path, searched ->
                        go(t, path)
                        target = searched?.let(context::getString)
                    },
                    onClose = close
                )
                maxWidth < NARROW_SETTINGS -> Column(modifier = Modifier.fillMaxSize()) {
                    SettingsTabs(tab, choose, close)
                    pane(Modifier.weight(1f).fillMaxWidth())
                }
                else -> Row(modifier = Modifier.fillMaxSize()) {
                    SettingsRail(tab, choose, close)
                    pane(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    if (bootLogo) BootLogoDialog(onDismiss = { bootLogo = false })
}

@Composable
internal fun SettingsHeader(onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(DashSize.TouchPrimary)
                .clip(DashShape.Medium)
                .background(DashColors.CardHi)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_close), tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(R.string.settings_title),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

private val RAIL_WIDTH = 260.dp

/** How far into the pane a dead strip still pushes the side bar: past that it only crosses rows, not their start. */
private val RAIL_STRIP_REACH = 240.dp

/** The widest the side bar grows for a dead strip, leaving the pane room. */
private val RAIL_MAX_WIDTH = 520.dp

/** Wide screens: close, the title and the categories down the left. */
@Composable
private fun SettingsRail(tab: SettingsTab, onChoose: (SettingsTab) -> Unit, onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    // A screen dead in one upright strip (Lab): the side bar grows past it, so the
    // pane's back arrow and rows start where touches are felt.
    val strip = DeadTouchStrip.active()
    val view = LocalView.current
    val density = LocalDensity.current
    var left by remember { mutableStateOf<Float?>(null) }
    val width = with(density) {
        val at = left
        if (strip == null || at == null) RAIL_WIDTH
        else DeadTouchStrip.sideBarWidth(
            strip, at, RAIL_WIDTH.toPx(), reachPx = RAIL_STRIP_REACH.toPx(), gapPx = 12.dp.toPx(), maxPx = RAIL_MAX_WIDTH.toPx()
        ).toDp()
    }
    Column(
        modifier = Modifier
            .onGloballyPositioned { coords ->
                val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
                left = coords.positionInWindow().x + onScreen[0]
            }
            .width(width)
            .fillMaxHeight()
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        SettingsHeader(onClose)
        Spacer(Modifier.height(8.dp))
        SettingsTab.entries.forEach { t ->
            val chosen = t == tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Twelve categories under the title fit a 720 px screen.
                    .heightIn(min = 44.dp)
                    .clip(DashShape.Medium)
                    .then(if (chosen) Modifier.background(DashColors.AccentBrush) else Modifier)
                    .clickable(role = Role.Tab) { tap(); onChoose(t) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
                Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(t.titleRes), color = ink, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Upright screens: close and the title, then the categories in rows of four across the top. */
@Composable
private fun SettingsTabs(tab: SettingsTab, onChoose: (SettingsTab) -> Unit, onClose: () -> Unit) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
            .padding(12.dp)
    ) {
        SettingsHeader(onClose)
        Spacer(Modifier.height(10.dp))
        SettingsTab.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                row.forEach { t ->
                    val chosen = t == tab
                    val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 68.dp)
                            .clip(DashShape.Medium)
                            .then(
                                if (chosen) Modifier.background(DashColors.AccentBrush)
                                else Modifier.border(1.dp, DashColors.Line, DashShape.Medium)
                            )
                            .clickable(role = Role.Tab) { tap(); onChoose(t) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(t.titleRes), color = ink,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Over the pane's bottom edge while there is more under it: a fade and an arrow. */
@Composable
internal fun MoreBelow(scroll: ScrollState, modifier: Modifier = Modifier) {
    if (!scroll.canScrollForward) return
    val card = DashColors.Card.copy(alpha = 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, card))),
        contentAlignment = Alignment.BottomCenter
    ) {
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
    }
}

/** The car itself: who it is, how it looks in the tiles, what it needs. */
@Composable
private fun CarPane(open: (Deep) -> Unit) {
    val car by CarProfileStore.profile.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_car))
    SettingsRow(Icons.Filled.DirectionsCar, stringResource(R.string.car_menu), car.displayName(LocalContext.current)) { open(Deep.CAR) }
    SettingsRow(Icons.Filled.Image, stringResource(R.string.mycar_title), stringResource(R.string.mycar_settings_detail)) { open(Deep.CAR_LOOK) }
    // Settings is parked-only; the Telemetry tile's tuning button opens the same row on the move.
    SpeedCorrectionRow(detail = stringResource(R.string.settings_speed_fix_detail))
    UsbTpmsRow { open(Deep.TPMS) }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_upkeep))
    SettingsRow(Icons.Filled.Handyman, stringResource(R.string.upkeep_dialog_title), stringResource(R.string.upkeep_settings_detail)) { open(Deep.UPKEEP) }
    LpgSettingsRow { open(Deep.LPG) }
}

/** Reading the car: the adapter, what the AI makes of it, the driver's own readings, the signals learned. */
@Composable
private fun DiagnosticsPane(open: (Deep) -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_diagnostics))
    SettingsRow(Icons.Filled.Bluetooth, stringResource(R.string.settings_obd_title), obdSummary()) { open(Deep.OBD) }
    SettingsRow(Icons.Filled.AutoAwesome, stringResource(R.string.ai_title), stringResource(R.string.settings_ai_detail)) { open(Deep.AI) }
    SettingsRow(Icons.Filled.Insights, stringResource(R.string.watch_title), sensorWatchSummary()) { open(Deep.SENSOR_WATCH) }
    SettingsRow(Icons.Filled.Calculate, stringResource(R.string.mine_title), stringResource(R.string.mine_settings_detail)) { open(Deep.MY_READINGS) }
    SettingsRow(Icons.Filled.SettingsInputAntenna, stringResource(R.string.signals_title), stringResource(R.string.signals_settings_detail)) { open(Deep.SIGNALS) }
}

/** The sensor watch in one line: what drifted, or that it is still learning. */
@Composable
private fun sensorWatchSummary(): String {
    val findings by SensorWatch.findings.collectAsState()
    val history by SensorWatch.history.collectAsState()
    val first = findings.firstOrNull()
    return when {
        first != null -> stringResource(R.string.watch_drifted, stringResource(first.signal.labelRes))
        history.values.none { it.size >= DriftRules.MIN_BASE + DriftRules.RECENT } -> stringResource(R.string.watch_settings_learning)
        else -> stringResource(R.string.watch_settings_detail)
    }
}

/** What talks to the unit: the phone, a second screen, the automation apps. */
@Composable
private fun DevicesPane(open: (Deep) -> Unit) {
    val context = LocalContext.current
    PhonePane()
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_screens_apps))
    SettingsRow(Icons.Filled.ConnectedTv, stringResource(R.string.settings_section_second_screen), secondScreenSummary()) { open(Deep.SECOND_SCREEN) }
    val automation by Automation.on.collectAsState()
    SettingsToggle(Icons.Filled.SmartToy, stringResource(R.string.automation_title), stringResource(R.string.automation_detail), automation) {
        Automation.setOn(context, it)
    }
}

/** Where the readings come from now, in one line: the Deep OBD app, or the adapter and whether it is linked. */
@Composable
internal fun obdSummary(): String {
    val source by DeepObdSource.source.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    val name = ObdBluetoothManager.adapter.collectAsState().value.name
    return when {
        source == ObdSource.DEEPOBD -> stringResource(R.string.settings_obd_deepobd)
        name == null -> stringResource(R.string.settings_obd_none)
        connection == ObdConnectionState.CONNECTED -> stringResource(R.string.settings_obd_linked, name)
        else -> stringResource(R.string.settings_obd_saved, name)
    }
}

/** The Android permissions Dashwheel still lacks, read again each time the launcher comes back. */
@Composable
internal fun missingAccess(): List<AccessNeed> {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The adapter has its own row and its own card: not counted here.
    return remember(generation) { AccessNeed.entries.filter { it != AccessNeed.OBD && !it.granted(context) } }
}

@Composable
internal fun accessSummary(): String {
    val missing = missingAccess()
    return if (missing.isEmpty()) stringResource(R.string.settings_access_all)
    else pluralStringResource(R.plurals.settings_access_missing, missing.size, missing.size)
}

/** The adapter: where the readings come from, which adapter, and which way the unit reaches it. */
@Composable
private fun ObdSheet(onPickObd: () -> Unit, onDismiss: () -> Unit) {
    val deepObd by DeepObdSource.source.collectAsState()
    SettingsSheet(title = stringResource(R.string.settings_obd_title), onDismiss = onDismiss, actions = {}) {
        Column {
            ObdSourceSetting()
            // Deep OBD holds the adapter then: nothing to pick here.
            if (deepObd == ObdSource.ADAPTER) {
                ObdAdapterRow(onPickObd)
                val auto by ObdBluetoothManager.autoConnect.collectAsState()
                val scope = rememberCoroutineScope()
                SettingsToggle(
                    Icons.Filled.Autorenew, stringResource(R.string.settings_obd_auto),
                    stringResource(if (auto) R.string.settings_obd_auto_on else R.string.settings_obd_auto_off), auto
                ) { on ->
                    ObdBluetoothManager.setAutoConnect(on)
                    // Off: the link closes now; on: dialled now.
                    scope.launch { if (on) VehicleMonitor.connectSaved() else ObdBluetoothManager.disconnect() }
                }
                ObdRouteSetting()
            }
        }
    }
}

/** What Dashwheel may use, as in the setup, to look up or allow later. */
@Composable
private fun AccessSheet(onPickObd: () -> Unit, onDismiss: () -> Unit) {
    SettingsSheet(title = stringResource(R.string.setup_access_title), onDismiss = onDismiss, actions = {}) {
        AccessRows(onPickObd)
    }
}

/**
 * The OBD adapter: which one is saved, a tap to pick another, and Forget.
 * Once one was saved, the only way to change it used to be a vehicle tile's
 * button while the link was down.
 */
@Composable
private fun ObdAdapterRow(onPickObd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    // Read again when the link changes: a pick made in the picker connects it.
    var forgotten by remember { mutableIntStateOf(0) }
    val adapter by ObdBluetoothManager.adapter.collectAsState()
    LaunchedEffect(connection, forgotten) { ObdBluetoothManager.refreshAdapter() }
    val name = adapter.name
    val usb = adapter.usb
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable { tap(); onPickObd() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (usb) Icons.Filled.Usb else Icons.Filled.Bluetooth, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_obd_title), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                name?.let { stringResource(R.string.settings_obd_saved, it) } ?: stringResource(R.string.settings_obd_none),
                color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
            )
        }
        if (name != null) {
            Spacer(Modifier.width(8.dp))
            CompositionLocalProvider(LocalSheetInPane provides true) {
                SheetButton(stringResource(R.string.settings_obd_forget), primary = false) {
                    scope.launch {
                        ObdBluetoothManager.forgetDevice()
                        forgotten++
                    }
                }
            }
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * Which way the unit reaches the adapter: its own Bluetooth, or the phone's
 * companion holding it and passing it on ([PhoneObd]). A change closes the
 * current link and dials again the new way.
 */
@Composable
private fun ObdRouteSetting() {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(ObdBluetoothManager.route()) }
    val offered by PhoneObd.offer.collectAsState()
    // What the three segments choose between: the Bluetooth path to the adapter.
    Text(
        stringResource(R.string.settings_obd_route_label),
        color = DashColors.TextSecondary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp)
    )
    SegmentedSwitch(
        options = ObdRoute.entries,
        chosen = chosen,
        icon = { option ->
            when (option) {
                ObdRoute.AUTO -> Icons.Filled.AutoAwesome
                ObdRoute.UNIT -> Icons.Filled.Bluetooth
                ObdRoute.USB -> Icons.Filled.Usb
                ObdRoute.PHONE -> Icons.Filled.PhoneAndroid
            }
        },
        title = { option ->
            stringResource(
                when (option) {
                    ObdRoute.AUTO -> R.string.settings_obd_route_auto
                    ObdRoute.UNIT -> R.string.settings_obd_route_unit
                    ObdRoute.USB -> R.string.settings_obd_route_usb
                    ObdRoute.PHONE -> R.string.settings_obd_route_phone
                }
            )
        },
        onChoose = { option ->
            chosen = option
            ObdBluetoothManager.setRoute(option)
            scope.launch {
                ObdBluetoothManager.disconnect()
                VehicleMonitor.connectSaved()
            }
        }
    )
    SwitchHint(
        when {
            chosen == ObdRoute.USB -> stringResource(R.string.settings_obd_route_usb_hint)
            offered != null -> stringResource(R.string.settings_obd_route_offered, offered!!)
            chosen == ObdRoute.UNIT -> stringResource(R.string.settings_obd_route_unit_hint)
            else -> stringResource(R.string.settings_obd_route_hint)
        }
    )
}

/**
 * Where the readings come from: an adapter of our own, or the Deep OBD app
 * reporting them. Only shown where Deep OBD is installed (or already chosen,
 * so it can be switched back).
 */
@Composable
private fun ObdSourceSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by DeepObdSource.source.collectAsState()
    if (source != ObdSource.DEEPOBD && !remember { DeepObdSource.installed(context) }) return
    Text(
        stringResource(R.string.settings_obd_source_label),
        color = DashColors.TextSecondary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp)
    )
    SegmentedSwitch(
        options = ObdSource.entries,
        chosen = source,
        icon = { if (it == ObdSource.ADAPTER) Icons.Filled.Bluetooth else Icons.Filled.Android },
        title = { stringResource(if (it == ObdSource.ADAPTER) R.string.settings_obd_source_adapter else R.string.settings_obd_source_deepobd) },
        onChoose = { option ->
            DeepObdSource.setSource(option)
            scope.launch {
                // The adapter takes one connection: ours is closed before Deep OBD uses it, and dialled again after.
                ObdBluetoothManager.disconnect()
                if (option == ObdSource.ADAPTER) VehicleMonitor.connectSaved()
            }
        }
    )
    SwitchHint(stringResource(if (source == ObdSource.DEEPOBD) R.string.settings_obd_source_deepobd_hint else R.string.settings_obd_source_adapter_hint))
    if (source == ObdSource.DEEPOBD) DeepObdLines()
}

/** The lines Deep OBD sent and the reading each one counts as; a tap picks the next choice. */
@Composable
private fun DeepObdLines() {
    val seen by DeepObdSource.seen.collectAsState()
    val tap = rememberTapFeedback()
    if (seen.isEmpty()) {
        SwitchHint(stringResource(R.string.settings_deepobd_waiting))
        return
    }
    Text(
        stringResource(R.string.settings_deepobd_lines),
        color = DashColors.TextSecondary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp)
    )
    for (line in seen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(DashShape.Medium)
                .clickable { tap(); DeepObdSource.cycle(line.item) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(line.item.name, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(line.item.value, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            val label = line.field?.let { stringResource(deepFieldLabel(it)) }
                ?: stringResource(if (line.chosen) R.string.settings_deepobd_field_ignored else R.string.settings_deepobd_field_none)
            Text(
                label,
                color = if (line.field != null) DashColors.Accent else DashColors.Muted,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun deepFieldLabel(field: ObdField): Int = when (field) {
    ObdField.SPEED -> R.string.settings_deepobd_field_speed
    ObdField.RPM -> R.string.settings_deepobd_field_rpm
    ObdField.COOLANT -> R.string.settings_deepobd_field_coolant
    ObdField.INTAKE -> R.string.settings_deepobd_field_intake
    ObdField.THROTTLE -> R.string.settings_deepobd_field_throttle
    ObdField.LOAD -> R.string.settings_deepobd_field_load
    ObdField.FUEL -> R.string.settings_deepobd_field_fuel
    ObdField.VOLTAGE -> R.string.settings_deepobd_field_voltage
}

/**
 * What the dashboard looks like: the look in use with the Home it brings and
 * the looks by family (LookPage.kt), then day and night beside the effects,
 * and the animation Dashwheel starts with.
 */
@Composable
private fun LookPane(theme: ThemeState) {
    LookPicker(theme)
    Spacer(Modifier.height(16.dp))
    LookLightAndEffects(theme)
    Spacer(Modifier.height(20.dp))
    StartAnimationSetting()
}

/** The dashboard around the tiles: the bottom bar, its readouts, the side rail and tabs, Moments. */
@Composable
private fun DashboardPane(m: TopBarModel, theme: ThemeState) {
    // One "Bottom bar": its auto-hide, its size, then its readouts under the same heading.
    BarAutoHideSetting(theme)
    BarSizeSetting()
    BarItemsSetting()
    Spacer(Modifier.height(20.dp))
    NavigationSetting(theme)
    Spacer(Modifier.height(12.dp))
    MomentsRow(m.onMoments)
}

/** Every sound it makes that is not an alert: the volume, the tap sound, the music after a call. */
@Composable
private fun SoundPane() {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.settings_section_volume))
    SpeedVolumeSetting()
    // Only for a unit whose sound ignores Android's volume; on the QF firmware Automatic is the only way that works.
    if (MediaVolume.choiceOffered) {
        Spacer(Modifier.height(20.dp))
        VolumeWaySetting()
    }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_taps_music))
    SettingsToggle(
        Icons.Filled.VolumeUp, stringResource(R.string.settings_tap_sound),
        stringResource(R.string.settings_tap_sound_detail), FeedbackStore.sound
    ) { FeedbackStore.save(context, it) }
    val resume by MediaResume.on.collectAsState()
    LaunchedEffect(Unit) { MediaResume.load(context) }
    SettingsToggle(
        Icons.Filled.PlayCircle, stringResource(R.string.settings_resume_music),
        stringResource(R.string.settings_resume_music_detail), resume
    ) { MediaResume.save(context, it) }
}

/** Units, as a page of their own under General: the row says what they are now. */
@Composable
private fun UnitsSheet(onDismiss: () -> Unit) {
    SettingsSheet(title = stringResource(R.string.units_title), onDismiss = onDismiss, actions = {}) {
        Column { UnitsSetting(heading = false) }
    }
}

/**
 * The bottom bar's settings on their own, from a long press on the bar: its
 * hiding, its readouts and the side rail. The same rows as Settings, Look.
 */
@Composable
internal fun BarSettingsSheet(theme: ThemeState, onDismiss: () -> Unit) {
    SettingsSheet(
        title = stringResource(R.string.dash_bar_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.dash_close)) { onDismiss() } }
    ) {
        Column {
            BarAutoHideSetting(theme)
            BarSizeSetting()
            BarItemsSetting()
            Spacer(Modifier.height(20.dp))
            NavigationSetting(theme)
        }
    }
}

/**
 * How the dashboards are reached: the side rail in the bottom bar's place
 * (what the Canvas theme always did), and tabs in that rail instead of
 * swiping, which a map app under the dashboards brings on by itself.
 */
@Composable
private fun NavigationSetting(theme: ThemeState) {
    SettingsSection(stringResource(R.string.settings_navigation_title))
    SettingsToggle(
        icon = Icons.Filled.ViewSidebar,
        title = stringResource(R.string.settings_side_rail),
        detail = stringResource(R.string.settings_side_rail_detail),
        checked = theme.rail,
        onChange = theme.onRail
    )
    SettingsToggle(
        icon = Icons.Filled.Tab,
        title = stringResource(R.string.settings_tabs_nav),
        detail = stringResource(R.string.settings_tabs_nav_detail),
        checked = theme.rail && theme.tabs,
        enabled = theme.rail,
        onChange = theme.onTabs
    )
}

/**
 * Whether the head unit's own top bar goes while an app window sits on the
 * dashboard ([FreeformBar]). Kept by default: the firmware's switch that hides
 * it draws the windows lower than their tiles on some units.
 */
@Composable
private fun UnitBarSetting() {
    val context = LocalContext.current
    val keep by FreeformBar.keepsBar.collectAsState()
    SettingsSection(stringResource(R.string.settings_unit_bar_title))
    SettingsToggle(
        Icons.Filled.VerticalAlignTop,
        stringResource(R.string.settings_unit_bar_hide),
        stringResource(R.string.settings_unit_bar_hide_detail),
        !keep
    ) { FreeformBar.keepBar(context, !it) }
}

/** A language by its own name; the system's says which one that is. */
@Composable
internal fun languageName(language: AppLanguage): String {
    if (language != AppLanguage.SYSTEM) return language.nativeName
    val system = remember { AppLanguage.systemLocale() }
    return stringResource(R.string.language_system, system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) })
}

@Composable
private fun LanguageSheet(onDismiss: () -> Unit) {
    SettingsSheet(
        title = stringResource(R.string.language_title),
        onDismiss = onDismiss,
        actions = {}
    ) {
        LanguageChoices()
    }
}

/**
 * Which way the dashboard stands ([ScreenShape]). Auto reads it from the
 * screen and says what it found; the others turn the launcher, which rebuilds
 * the dashboard for that shape with its own layout. A turn is tried out: it
 * goes back by itself unless it is kept, so a screen turned the wrong way
 * never has to be read sideways to be put right.
 */
@Composable
private fun ScreenOrientationSetting() {
    val context = LocalContext.current
    val chosen by ScreenShape.choice.collectAsState()
    val keepLeft by ScreenShape.keepSecondsLeft.collectAsState()
    SettingsSection(stringResource(R.string.settings_screen_title))
    keepLeft?.let { left ->
        // Asked here: the strip over the dashboard is not needed as well.
        DisposableEffect(Unit) {
            KeepDirectionAsk.inPane++
            onDispose { KeepDirectionAsk.inPane-- }
        }
        KeepDirection(left, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    }
    SegmentedSwitch(
        options = ScreenOrientation.entries,
        chosen = chosen,
        icon = { option ->
            when (option) {
                ScreenOrientation.AUTO -> Icons.Filled.ScreenRotation
                ScreenOrientation.HORIZONTAL -> Icons.Filled.StayCurrentLandscape
                ScreenOrientation.VERTICAL -> Icons.Filled.StayCurrentPortrait
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = { option -> context.findActivity()?.let { ScreenShape.tryOut(it, option) } }
    )
    SwitchHint(
        stringResource(
            when (chosen) {
                ScreenOrientation.AUTO ->
                    if (ScreenShape.vertical) R.string.settings_screen_auto_hint_tall else R.string.settings_screen_auto_hint_wide
                ScreenOrientation.HORIZONTAL -> R.string.settings_screen_horizontal_hint
                ScreenOrientation.VERTICAL -> R.string.settings_screen_vertical_hint
            }
        )
    )
}

/** Where the keep-or-go-back question is being asked, so it shows once. */
private object KeepDirectionAsk {
    /** Display panes showing it (two for a moment, while a turn rebuilds the dashboard). */
    var inPane by mutableIntStateOf(0)
}

/**
 * Asked once the screen has turned: keep it, or go back, which it does by
 * itself when [left] seconds run out ([ScreenShape] counts them, whatever is
 * open). [solid] over the dashboard, where it must read on any page.
 */
@Composable
private fun KeepDirection(left: Int, modifier: Modifier = Modifier, solid: Boolean = false) {
    val shape = DashShape.Medium
    Row(
        modifier = modifier
            .clip(shape)
            .then(if (solid) Modifier.background(DashColors.Card.copy(alpha = 1f)) else Modifier)
            .background(DashColors.Warning.copy(alpha = 0.12f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_screen_keep_title), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_screen_keep_detail, left), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        CompositionLocalProvider(LocalSheetInPane provides true) {
            SheetButton(stringResource(R.string.settings_screen_revert), primary = false) { ScreenShape.revert() }
            SheetButton(stringResource(R.string.settings_screen_keep)) { ScreenShape.keep() }
        }
    }
}

/**
 * The same question as a strip over the dashboard, for as long as a screen
 * direction is on trial and the Display settings are not there asking it:
 * Settings closed, or on another category. The wait runs on either way.
 */
@Composable
internal fun KeepDirectionStrip(modifier: Modifier = Modifier) {
    val left = ScreenShape.keepSecondsLeft.collectAsState().value ?: return
    if (KeepDirectionAsk.inPane > 0) return
    KeepDirection(
        left,
        modifier
            .widthIn(max = 640.dp)
            .keepClearOfWindows()
            // A tap beside its buttons stays here rather than landing on a tile underneath.
            .pointerInput(Unit) { detectTapGestures { } },
        solid = true
    )
}

/** How the car is driven: what waits until parked, the reverse view, the places, every physical button. */
@Composable
private fun DrivingPane(m: TopBarModel, open: (Deep) -> Unit) {
    val wheelButtons by SteeringWheelStore.buttons.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_driving))
    SettingsToggle(
        Icons.Filled.DirectionsCar, stringResource(R.string.settings_drive_lock),
        stringResource(R.string.settings_drive_lock_detail), m.lockWhileMoving, onChange = m.onLockWhileMoving
    )
    SettingsRow(Icons.Filled.Videocam, stringResource(R.string.reverse_title), stringResource(R.string.reverse_settings_detail)) { open(Deep.REVERSE) }
    PlacesRow { open(Deep.PLACES) }
    KeptCamerasRow { open(Deep.KEPT_CAMERAS) }
    KeptLimitsRow { open(Deep.KEPT_LIMITS) }
    WazeWakeSetting()
    // Every physical button together: what the wheel's do, then what the unit's own keys open.
    val keys = remember { Edition.full && KeyTargets.available() }
    val keysLocked = keys && !shellAccess().shell
    if (!SteeringWheelStore.AVAILABLE && (!keys || keysLocked)) {
        if (keysLocked) RootLockCard(listOf(stringResource(R.string.keys_section)), shellEnough = true)
        return
    }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_buttons))
    if (SteeringWheelStore.AVAILABLE) SettingsRow(
        Icons.Filled.SettingsRemote, stringResource(R.string.wheel_title),
        wheelButtons.count { it.assignment != null }.let { assigned ->
            when {
                assigned > 0 -> pluralStringResource(R.plurals.wheel_settings_detail_count, assigned, assigned)
                wheelButtons.isNotEmpty() -> pluralStringResource(R.plurals.wheel_settings_detail_mapped, wheelButtons.size, wheelButtons.size)
                else -> stringResource(R.string.wheel_settings_detail_empty)
            }
        }
    ) { open(Deep.WHEEL) }
    KeyTargetRows()
    if (keysLocked) RootLockCard(listOf(stringResource(R.string.keys_section)), shellEnough = true)
}

/**
 * Waze opened afresh a while after the unit wakes ([WazeWake]): only where
 * Waze is installed, never in the Play edition (it closes another app through
 * the privileged shell); greyed, with the reason, without a shell.
 */
@Composable
private fun WazeWakeSetting() {
    if (Edition.play) return
    val context = LocalContext.current
    val installed = remember { WazeWake.installed(context) }
    if (!installed) return
    LaunchedEffect(Unit) { WazeWake.load(context) }
    val setting by WazeWake.setting.collectAsState()
    val shell = shellAccess().shell
    SettingsToggle(
        Icons.Filled.Autorenew, stringResource(R.string.settings_waze_wake),
        stringResource(if (shell) R.string.settings_waze_wake_detail else R.string.settings_waze_wake_locked),
        setting.on && shell, enabled = shell
    ) { WazeWake.save(context, setting.copy(on = it)) }
    if (!setting.on || !shell) return
    StepperRow(
        Icons.Filled.Timer, stringResource(R.string.settings_waze_wake_delay), stringResource(R.string.settings_waze_wake_delay_detail),
        value = stringResource(R.string.settings_waze_wake_seconds, setting.delayS),
        less = stringResource(R.string.speed_volume_less), more = stringResource(R.string.speed_volume_more),
        canLess = setting.delayS > WazeWake.DELAY_MIN_S,
        canMore = setting.delayS < WazeWake.DELAY_MAX_S,
        onLess = { WazeWake.save(context, setting.copy(delayS = setting.delayS - WazeWake.DELAY_STEP_S)) },
        onMore = { WazeWake.save(context, setting.copy(delayS = setting.delayS + WazeWake.DELAY_STEP_S)) }
    )
}

/**
 * How the volume is changed (see [MediaVolume]): for units whose sound
 * ignores Android's volume. The keys are pressed through the privileged
 * shell, so that way is only offered where there is one ([PrivilegedShell]).
 */
@Composable
private fun VolumeWaySetting() {
    val context = LocalContext.current
    val saved by MediaVolume.way.collectAsState()
    val shell = shellAccess().shell
    // The keys way stays in sight without a shell, greyed, with the reason under the switch;
    // the Play edition, which can never have a shell, leaves it out.
    val way = if (shell || saved != VolumeWay.KEYS) saved else VolumeWay.AUTO
    SettingsSection(stringResource(R.string.volume_way_title))
    SegmentedSwitch(
        options = if (Edition.play) VolumeWay.entries - VolumeWay.KEYS else VolumeWay.entries,
        chosen = way,
        icon = { option ->
            when (option) {
                VolumeWay.AUTO -> Icons.Filled.AutoAwesome
                VolumeWay.ANDROID -> Icons.Filled.Android
                VolumeWay.KEYS -> Icons.Filled.Adjust
            }
        },
        title = { stringResource(it.titleRes) },
        enabled = { shell || it != VolumeWay.KEYS },
        onChoose = { MediaVolume.saveWay(context, it) }
    )
    SwitchHint(stringResource(if (!shell && Edition.full) R.string.volume_way_keys_locked else way.hintRes))
}

/**
 * Volume follows speed (see [SpeedVolume]): on or off, and once on, the speed
 * it starts at, how many volume steps it adds at most and the speed they are
 * all there at. A single step has no second speed: it comes on at the start.
 */
@Composable
private fun SpeedVolumeSetting() {
    val context = LocalContext.current
    val on by SpeedVolume.on.collectAsState()
    val curve by SpeedVolume.curve.collectAsState()
    val less = stringResource(R.string.speed_volume_less)
    val more = stringResource(R.string.speed_volume_more)
    val step = SpeedVolumeCurve.SPEED_STEP_KMH
    SettingsToggle(
        Icons.Filled.VolumeUp, stringResource(R.string.speed_volume_title),
        stringResource(R.string.speed_volume_detail), on
    ) { SpeedVolume.save(context, it) }
    if (!on) return
    StepperRow(
        Icons.Filled.Speed, stringResource(R.string.speed_volume_start), stringResource(R.string.speed_volume_start_detail),
        value = LocalUnits.current.speedText(curve.startKmh),
        less = less, more = more,
        canLess = curve.startKmh > SpeedVolumeCurve.START_MIN_KMH,
        canMore = curve.startKmh < SpeedVolumeCurve.START_MAX_KMH,
        onLess = { SpeedVolume.save(context, curve.copy(startKmh = curve.startKmh - step)) },
        // Caught up, the top speed moves up with it (SpeedVolumeCurve.fixed).
        onMore = { SpeedVolume.save(context, curve.copy(startKmh = curve.startKmh + step)) }
    )
    StepperRow(
        Icons.Filled.Tune, stringResource(R.string.speed_volume_steps), stringResource(R.string.speed_volume_steps_detail),
        value = "+${curve.maxSteps}",
        less = less, more = more,
        canLess = curve.maxSteps > 1,
        canMore = curve.maxSteps < SpeedVolumeCurve.STEPS_MAX,
        onLess = { SpeedVolume.save(context, curve.copy(maxSteps = curve.maxSteps - 1)) },
        onMore = { SpeedVolume.save(context, curve.copy(maxSteps = curve.maxSteps + 1)) }
    )
    if (curve.maxSteps > 1) {
        StepperRow(
            Icons.Filled.VerticalAlignTop, stringResource(R.string.speed_volume_full), stringResource(R.string.speed_volume_full_detail),
            value = LocalUnits.current.speedText(curve.fullKmh),
            less = less, more = more,
            canLess = curve.fullKmh > curve.startKmh + step,
            canMore = curve.fullKmh < SpeedVolumeCurve.FULL_MAX_KMH,
            onLess = { SpeedVolume.save(context, curve.copy(fullKmh = curve.fullKmh - step)) },
            onMore = { SpeedVolume.save(context, curve.copy(fullKmh = curve.fullKmh + step)) }
        )
    }
}

/**
 * The head unit itself: its screen and the firmware's bar, which firmware it
 * is and what Dashwheel may use on it, then what root brings: Unit tools
 * (what root writes to /system) and Shizuku.
 */
@Composable
private fun HeadUnitPane(open: (Deep) -> Unit) {
    val context = LocalContext.current
    ScreenOrientationSetting()
    // The unit's bar is moved through the shell: not in the Play edition.
    if (FreeformBar.supported && Edition.full) {
        Spacer(Modifier.height(20.dp))
        UnitBarSetting()
    }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_access))
    // Which head unit firmware this is, and what it lets Dashwheel do (HeadUnitCard.kt).
    HeadUnitRow { open(Deep.HEAD_UNIT) }
    SettingsRow(Icons.Filled.VerifiedUser, stringResource(R.string.setup_access_title), accessSummary()) { open(Deep.ACCESS) }
    HomeScreenToggle()
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_more_tools))
    // The boot logo and the system-app install write to /system: only with a
    // privileged shell (root or the unit's ADB, see PrivilegedShell) can they
    // do anything. Without one they are not listed, the root card counts them.
    // The Play edition can never have root: one row says where they are instead.
    val locked = mutableListOf<String>()
    if (Edition.play) {
        MoreWithGithubRow()
    } else {
        // Shizuku's shell user cannot write /system: these three want root or the unit's ADB.
        val system = shellAccess().system
        val embed = remember { EmbeddedApp.allowed(context) }
        if (system || embed) {
            SettingsRow(Icons.Filled.Build, stringResource(R.string.settings_unit_tools), stringResource(R.string.settings_unit_tools_detail)) { open(Deep.UNIT_TOOLS) }
        } else {
            if (BootLogoSupport.available) locked += stringResource(R.string.boot_menu)
            locked += stringResource(R.string.settings_system_perms)
            locked += stringResource(R.string.dash_system_app_title)
        }
        // The shell without root: where it stands, and the one step that brings it (ShizukuSettings.kt).
        ShizukuRow()
    }
    if (locked.isNotEmpty()) RootLockCard(locked)
}

/**
 * Off: the unit's own launcher is Home again and Dashwheel stops taking the
 * screen ([HomeHandBack]), for someone else at the wheel. Asked first: the
 * screen goes to the other launcher at once.
 */
@Composable
private fun HomeScreenToggle() {
    val context = LocalContext.current
    val released by HomeHandBack.releasedFlow.collectAsState()
    val handedBack = released ?: remember { HomeHandBack.released(context) }
    var asking by remember { mutableStateOf(false) }
    SettingsToggle(
        Icons.Filled.Home, stringResource(R.string.home_hand_back_title),
        stringResource(if (handedBack) R.string.home_hand_back_off_detail else R.string.home_hand_back_detail), !handedBack
    ) { on -> if (on) HomeHandBack.takeBack(context) else asking = true }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.home_hand_back_confirm_title),
            body = stringResource(R.string.home_hand_back_confirm),
            action = stringResource(R.string.home_hand_back_confirm_ok),
            onConfirm = { asking = false; HomeHandBack.handBack(context) },
            onDismiss = { asking = false }
        )
    }
}

/** The usual app settings: language and units, the setup kept or done again, the demo, About. */
@Composable
private fun GeneralPane(m: TopBarModel, open: (Deep) -> Unit) {
    val context = LocalContext.current
    val units by Units.current.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_region))
    SettingsRow(Icons.Filled.Language, stringResource(R.string.language_title), languageName(AppLanguage.current(context))) { open(Deep.LANGUAGE) }
    SettingsRow(Icons.Filled.Straighten, stringResource(R.string.units_title), unitsSummary(units)) { open(Deep.UNITS) }
    Spacer(Modifier.height(20.dp))
    SetupBackupRows()
    SettingsRow(Icons.Filled.Checklist, stringResource(R.string.setup_again), stringResource(R.string.setup_again_detail)) { m.onSetup(true) }
    // The demo stays here once turned on: the dashboard's badge stops it, and so does this switch.
    SettingsToggle(
        Icons.Filled.PlayCircle, stringResource(R.string.demo_menu_start),
        stringResource(R.string.demo_settings_detail), m.demo
    ) { m.onDemo() }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_about))
    // Updates are in About only, beside the version (the overview's card shows one waiting).
    SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_section_about), stringResource(R.string.settings_about_detail)) { open(Deep.ABOUT) }
    Spacer(Modifier.height(20.dp))
    ExpertRows(open)
}

/** For those who dig, at the end of General: Lab, the driver's own API keys, the map and route servers. */
@Composable
private fun ExpertRows(open: (Deep) -> Unit) {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.settings_section_expert))
    SettingsRow(Icons.Filled.Science, stringResource(R.string.settings_lab), stringResource(R.string.settings_lab_detail)) { open(Deep.LAB) }
    // The keys the driver brings for the services asked on their behalf (ApiKeys.kt).
    val keysVersion by ApiKeys.version.collectAsState()
    val keysSet = remember(keysVersion) { ApiKeys.countSet(context) }
    SettingsRow(
        Icons.Filled.Key, stringResource(R.string.api_keys_title),
        if (keysSet == 0) stringResource(R.string.api_keys_none) else pluralStringResource(R.plurals.api_keys_set_count, keysSet, keysSet)
    ) { open(Deep.API_KEYS) }
    val servers by MapServers.current.collectAsState()
    SettingsRow(
        Icons.Filled.Dns, stringResource(R.string.map_servers_title),
        stringResource(if (servers.isDefault) R.string.map_servers_default else R.string.map_servers_custom)
    ) { open(Deep.MAP_SERVERS) }
}

/**
 * What root writes to the unit: the boot logo, Google Maps inside a tile and
 * the system app. The boot logo needs the QF firmware as well; on another
 * one its row stays, greyed, with that reason.
 */
@Composable
private fun UnitToolsSheet(m: TopBarModel, onBootLogo: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Root or the unit's root ADB: Shizuku's shell user cannot write /system or the logo partition.
    val shell = shellAccess().system
    val needsRoot = stringResource(R.string.settings_rom_needs_root)
    SettingsSheet(title = stringResource(R.string.settings_unit_tools), onDismiss = onDismiss, help = WikiPage.ROOT_PMPATCH, actions = {}) {
        Column {
            when {
                !BootLogoSupport.available -> GatedRow(Icons.Filled.PowerSettingsNew, stringResource(R.string.boot_menu), stringResource(R.string.unit_needs_firmware, "QF"))
                !shell -> GatedRow(Icons.Filled.PowerSettingsNew, stringResource(R.string.boot_menu), needsRoot)
                else -> SettingsRow(Icons.Filled.PowerSettingsNew, stringResource(R.string.boot_menu), null, onBootLogo)
            }
            Spacer(Modifier.height(20.dp))
            // Apps running inside tiles: Google Maps needs permissions only the
            // firmware's apps get (EmbeddedApp), through PMPatch3; Android widgets
            // need Dashwheel installed as a system app.
            SettingsSection(stringResource(R.string.settings_section_apps_in_tiles))
            SystemPermissionsRow(remember { EmbeddedApp.allowed(context) })
            if (remember { SystemInstaller.isSystemApp(context) }) GatedRow(Icons.Filled.Build, stringResource(R.string.dash_system_app_title), stringResource(R.string.settings_system_app_on))
            else if (shell) SettingsRow(Icons.Filled.Build, stringResource(R.string.dash_system_app_title), stringResource(R.string.settings_system_detail), m.onSystem)
            else GatedRow(Icons.Filled.Build, stringResource(R.string.dash_system_app_title), needsRoot)
        }
    }
}

/**
 * Tools still being tried, and what is for a bug report rather than a
 * setting: the readings found by AI, deep sleep and the log.
 */
@Composable
private fun LabSheet(open: (Deep) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    SettingsSheet(title = stringResource(R.string.settings_lab), onDismiss = onDismiss, actions = {}) {
        Column {
            SettingsRow(Icons.Filled.Science, stringResource(R.string.explore_title), stringResource(R.string.explore_settings_detail)) { open(Deep.EXPLORER) }
            DeadStripRows()
            Spacer(Modifier.height(20.dp))
            SettingsSection(stringResource(R.string.settings_section_diagnostics))
            if (CarPower.available) {
                val lost = remember { CarPower.sleepLost(context) }
                var expanded by remember { mutableStateOf(false) }
                ExpandRow(
                    Icons.Filled.DirectionsCar, stringResource(R.string.settings_sleep_title),
                    if (lost.isEmpty()) stringResource(R.string.settings_sleep_none)
                    else stringResource(R.string.settings_sleep_lost, lost.size, if (expanded) lost.joinToString("\n") else lost.first()),
                    open = expanded,
                    canOpen = lost.size > 1
                ) { expanded = !expanded }
            }
            SendLogRow()
            SaveLogRow()
        }
    }
}

/**
 * A touchscreen dead in one upright strip ([DeadTouchStrip]): the switch, and
 * measuring the strip, which switching on asks for the first time.
 */
@Composable
private fun DeadStripRows() {
    val context = LocalContext.current
    DeadTouchStrip.load(context)
    val on by DeadTouchStrip.on.collectAsState()
    val strip by DeadTouchStrip.measured.collectAsState()
    var measuring by remember { mutableStateOf(false) }
    SettingsToggle(
        Icons.Filled.TouchApp,
        stringResource(R.string.dead_strip_title),
        strip?.let { stringResource(R.string.dead_strip_found, it.startPx, it.endPx) } ?: stringResource(R.string.dead_strip_detail),
        on
    ) { value -> if (value && strip == null) measuring = true else DeadTouchStrip.setOn(context, value) }
    if (on) SettingsRow(Icons.Filled.Swipe, stringResource(R.string.dead_strip_measure), stringResource(R.string.dead_strip_measure_detail)) { measuring = true }
    if (measuring) DeadStripMeasure(onDismiss = { measuring = false })
}

/**
 * What this unit could have with root, said once per group instead of a
 * greyed row each: how many, which, and a way to the wiki page on root. With
 * [shellEnough], any shell does (Shizuku included), and How opens Shizuku's
 * own card instead ([ShizukuDialog]). Never in the Play edition, which can
 * have neither.
 */
@Composable
internal fun RootLockCard(features: List<String>, shellEnough: Boolean = false) {
    if (Edition.play || features.isEmpty()) return
    var help by remember { mutableStateOf(false) }
    val shape = DashShape.Medium
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, DashColors.Line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pluralStringResource(if (shellEnough) R.plurals.settings_shell_more else R.plurals.settings_root_more, features.size, features.size),
                color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge
            )
            Text(features.joinToString(" · "), color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalSheetInPane provides true) {
            SheetButton(stringResource(R.string.settings_root_how), primary = false) { help = true }
        }
    }
    if (help) {
        if (shellEnough) ShizukuDialog { help = false } else WikiHelpDialog(WikiPage.ROOT_PMPATCH) { help = false }
    }
}

/**
 * Play edition only: the one place that says what the GitHub edition has
 * more (Google Maps inside a tile, the unit's own pop-ups, the boot logo),
 * and why (the unit's root). Opens the wiki page on root and PMPatch3.
 */
@Composable
private fun MoreWithGithubRow() {
    var help by remember { mutableStateOf(false) }
    SettingsRow(Icons.Filled.Extension, stringResource(R.string.settings_more_github), stringResource(R.string.settings_more_github_detail)) { help = true }
    if (help) WikiHelpDialog(WikiPage.ROOT_PMPATCH) { help = false }
}

internal const val KOFI_URL = "https://ko-fi.com/deviloufr"
private const val PROJECT_URL = "https://github.com/deviloufr-ai/Dashwheel"
private const val TELEGRAM_URL = "https://t.me/Dashwheel"
/** A Google Play listing, the package name appended (the Play edition's own, and the companion's). */
internal const val PLAY_LISTING = "https://play.google.com/store/apps/details?id="

/** Who made the app, what it is, the update check, a Ko-fi link with its QR code, the tour, help and the project. */
@Composable
private fun AboutSheet(m: TopBarModel, onDismiss: () -> Unit) {
    SettingsSheet(title = stringResource(R.string.settings_section_about), onDismiss = onDismiss, actions = {}) {
        Column { AboutBody(m) }
    }
}

@Composable
private fun AboutBody(m: TopBarModel) {
    val context = LocalContext.current
    val tap = rememberTapFeedback()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
        Box(Modifier.size(64.dp).clip(DashShape.Medium)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.app_name), color = DashColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.settings_version, m.versionName), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.about_description),
        color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.about_author_section))
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.about_author_name), color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_author_role), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(20.dp))
    // Google Play allows no donation link in the app: tips go through Play's own billing there.
    if (Edition.play) {
        TipJar(Modifier.padding(horizontal = 12.dp))
    } else {
        SettingsSection(stringResource(R.string.about_support_section))
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_support_text), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                SheetButton(stringResource(R.string.about_kofi)) {
                    tap()
                    context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(KOFI_URL, Modifier.size(120.dp).clip(DashShape.Small))
                Text(stringResource(R.string.about_scan), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.settings_section_about))
    // Here too, where the version is read: "Update" belongs beside it.
    UpdateRow(m)
    var report by remember { mutableStateOf(false) }
    SettingsRow(Icons.Filled.BugReport, stringResource(R.string.report_title), stringResource(R.string.report_row_detail)) { report = true }
    if (report) CompatibilityReportSheet { report = false }
    // The log too, right under the report: the report holds no log, and drivers
    // asked for one kept sending the report instead (issues #57, #59, #63).
    SendLogRow()
    SaveLogRow()
    SettingsRow(Icons.Filled.School, stringResource(R.string.tour_settings_row), stringResource(R.string.tour_settings_row_detail), m.onTour)
    // The wiki, as a QR code for the phone and a button for this screen (WikiHelp.kt).
    var help by remember { mutableStateOf(false) }
    SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.help_title), stringResource(R.string.help_row_detail)) { help = true }
    if (help) WikiHelpDialog(WikiPage.HOME) { help = false }
    SettingsRow(Icons.Filled.Favorite, stringResource(R.string.about_project), stringResource(R.string.about_project_detail)) {
        context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
    }
    SettingsRow(Icons.AutoMirrored.Filled.Send, stringResource(R.string.about_telegram), stringResource(R.string.about_telegram_detail)) {
        context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)))
    }
}

/**
 * The updater's one row: what it knows (checking, up to date, a newer build,
 * downloading, downloaded) and the one action that fits. The only place an
 * update the driver put off can still be had.
 */
@Composable
private fun UpdateRow(m: TopBarModel) {
    // The Play edition has no updater of its own: Google Play updates it, and the row opens its listing.
    if (Edition.play) {
        val context = LocalContext.current
        SettingsRow(Icons.Filled.SystemUpdate, stringResource(R.string.about_updates_play), stringResource(R.string.settings_version, m.versionName)) {
            context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_LISTING + context.packageName)))
        }
        return
    }
    SettingsRow(Icons.Filled.SystemUpdate, updateTitle(m), updateDetail(m)) { updateTap(m) }
    val context = LocalContext.current
    var beta by remember { mutableStateOf(UpdateManager.beta(context)) }
    SettingsToggle(Icons.Filled.Science, stringResource(R.string.settings_beta_updates), stringResource(R.string.settings_beta_updates_detail), beta) {
        beta = it
        UpdateManager.setBeta(context, it)
        m.onCheckUpdates()
    }
    PreviousVersionRow()
}

/**
 * The way back after a bad build: the channel's last five builds older than
 * this one ([UpdateManager.previousReleases]). A shell installs the pick over
 * this version; without one the APK is saved in Download/Dashwheel and the
 * driver uninstalls first, settings and pairings with it (issue #76).
 */
@Composable
private fun PreviousVersionRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Its own updater: a build going back must never show as the update on offer in ⋮.
    val manager = remember { UpdateManager(context) }
    val status by manager.status.collectAsState()
    val shell = shellAccess().shell
    // Android 9 writes Download itself, which needs the storage permission (see SaveLogRow).
    val storage = rememberPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    var releases by remember { mutableStateOf<List<UpdateInfo>?>(null) }
    var picked by remember { mutableStateOf<UpdateInfo?>(null) }
    var uninstall by remember { mutableStateOf<UpdateStatus.Ready?>(null) }
    var busy by remember { mutableStateOf(false) }
    var none by remember { mutableStateOf(false) }
    val detail = when (val s = status) {
        is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, s.percent)
        is UpdateStatus.Installing -> stringResource(R.string.dash_update_installing)
        is UpdateStatus.Ready -> stringResource(R.string.update_previous_saved, s.file.name)
        is UpdateStatus.Error -> stringResource(s.messageRes)
        else -> stringResource(if (busy) R.string.dash_update_checking else if (none) R.string.update_previous_none else R.string.update_previous_detail)
    }
    SettingsRow(Icons.Filled.History, stringResource(R.string.update_previous), detail) {
        if (busy || status is UpdateStatus.Downloading || status is UpdateStatus.Installing) return@SettingsRow
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !storage.granted) { storage.request(); return@SettingsRow }
        busy = true
        scope.launch {
            val list = manager.previousReleases()
            busy = false
            none = list.isEmpty()
            if (list.isNotEmpty()) releases = list
        }
    }
    releases?.let { list ->
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { releases = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.update_previous), color = DashColors.TextPrimary) },
            text = {
                Column {
                    list.forEach { info ->
                        SettingsRow(Icons.Filled.History, info.versionName, info.date) {
                            releases = null
                            if (shell) picked = info else scope.launch { manager.installPrevious(info) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { releases = null }) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
            }
        )
    }
    picked?.let { info ->
        ConfirmDialog(
            stringResource(R.string.update_previous_title, info.versionName),
            stringResource(R.string.update_previous_shell_body, info.versionName),
            stringResource(R.string.update_previous_go),
            onConfirm = { picked = null; scope.launch { manager.installPrevious(info) } },
            onDismiss = { picked = null }
        )
    }
    // No shell: the file is saved, and Android takes it only once this version is gone.
    LaunchedEffect(status) { if (!shell) uninstall = status as? UpdateStatus.Ready }
    uninstall?.let { ready ->
        ConfirmDialog(
            stringResource(R.string.update_previous_title, ready.info.versionName),
            stringResource(R.string.update_previous_manual_body, ready.file.name),
            stringResource(R.string.update_previous_uninstall),
            onConfirm = { uninstall = null; context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))) },
            onDismiss = { uninstall = null }
        )
    }
}

/** "Update to 1.0.531", or "Check for updates" while none is known. */
@Composable
internal fun updateTitle(m: TopBarModel): String =
    m.update.updateInfo?.let { stringResource(R.string.dash_update_to, it.versionName) } ?: stringResource(R.string.dash_menu_check_updates)

/** What the updater knows now, in one line. */
@Composable
internal fun updateDetail(m: TopBarModel): String = when (val status = m.update) {
    is UpdateStatus.Checking -> stringResource(R.string.dash_update_checking)
    is UpdateStatus.UpToDate -> stringResource(R.string.dash_update_up_to_date, m.versionName)
    is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, status.percent)
    is UpdateStatus.Ready -> stringResource(R.string.dash_update_ready_detail)
    is UpdateStatus.Installing -> stringResource(R.string.dash_update_installing)
    is UpdateStatus.Error -> stringResource(status.messageRes)
    is UpdateStatus.Available, is UpdateStatus.Dismissed -> stringResource(R.string.dash_update_available_detail, m.versionName)
    UpdateStatus.Idle -> stringResource(R.string.settings_version, m.versionName)
}

/** The one action that fits: install what is there, or look again. */
internal fun updateTap(m: TopBarModel) {
    val status = m.update
    if (status.updateInfo != null && status !is UpdateStatus.Downloading && status !is UpdateStatus.Installing) m.onUpdate() else m.onCheckUpdates()
}

/** The one heading of the Settings panes: every group of settings starts with it. */
@Composable
internal fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.AccentInk,
        letterSpacing = 0.08.em,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.searchTarget(title).padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * Google Maps inside a tile: whether Android granted Dashwheel the system
 * permissions it takes. While it hasn't, a tap sets it up through PMPatch3
 * ([PmPatchDialog]); once it has, the row is done and reads dimmed.
 */
@Composable
private fun SystemPermissionsRow(granted: Boolean) {
    var setup by remember { mutableStateOf(false) }
    if (setup) PmPatchDialog { setup = false }
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(enabled = !granted) { tap(); setup = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.VerifiedUser, contentDescription = null,
            tint = if (granted) DashColors.Good.copy(alpha = 0.7f) else DashColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_system_perms),
                color = if (granted) DashColors.TextSecondary else DashColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(if (granted) R.string.settings_system_perms_on else R.string.settings_system_perms_off),
                color = if (granted) DashColors.Muted else DashColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!granted) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * A setting this unit cannot have yet, kept in sight: greyed, no chevron,
 * and [reason] in one line (needs root, needs the QF firmware). One policy
 * for every gated row, like the alerts' held-back note.
 */
@Composable
internal fun GatedRow(icon: ImageVector, title: String, reason: String) {
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.Muted, style = MaterialTheme.typography.bodyLarge)
            Text(reason, color = DashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * A row that opens in place rather than further in: an expand arrow instead
 * of the chevron, turned over while [open]. Without more to show ([canOpen]
 * false) the arrow is left out and the row is plain text.
 */
@Composable
private fun ExpandRow(icon: ImageVector, title: String, detail: String, open: Boolean, canOpen: Boolean, onToggle: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(enabled = canOpen) { tap(); onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (canOpen) {
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = DashColors.Muted)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** One setting: icon, name, what it is right now or what it does, and a chevron. */
@Composable
internal fun SettingsRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    color = DashColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * The speed correction (see [SpeedCorrection]): − and + by 1 km/h, the value
 * between, applied at once so the speed on screen can be matched to the car's
 * speedometer while driving along. Also in the telemetry tile's dialog, which
 * opens on the move for that reason.
 */
@Composable
internal fun SpeedCorrectionRow(detail: String = stringResource(R.string.vehicle_speed_fix_detail)) {
    val context = LocalContext.current
    val offset by SpeedCorrection.offsetKmh.collectAsState()
    StepperRow(
        Icons.Filled.Speed, stringResource(R.string.vehicle_speed_fix), detail,
        value = speedOffsetText(offset, LocalUnits.current),
        valueColor = if (offset == 0) DashColors.TextSecondary else DashColors.Accent,
        less = stringResource(R.string.vehicle_speed_fix_less), more = stringResource(R.string.vehicle_speed_fix_more),
        canLess = offset > -SpeedCorrection.MAX_OFFSET_KMH,
        canMore = offset < SpeedCorrection.MAX_OFFSET_KMH,
        onLess = { SpeedCorrection.save(context, offset - 1) },
        onMore = { SpeedCorrection.save(context, offset + 1) }
    )
}

/** One setting changed a step at a time: icon, name, what it does, then −, the value and +. */
@Composable
internal fun StepperRow(
    icon: ImageVector,
    title: String,
    detail: String,
    value: String,
    less: String,
    more: String,
    canLess: Boolean,
    canMore: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
    valueColor: Color = DashColors.TextPrimary
) {
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                color = DashColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        StepButton(Icons.Filled.Remove, less, enabled = canLess, onClick = onLess)
        Text(
            value,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(88.dp)
        )
        StepButton(Icons.Filled.Add, more, enabled = canMore, onClick = onMore)
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** − or +: a filled square a thumb lands on, since it is pressed on the move. */
@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DashSize.TouchPrimary)
            .clip(DashShape.Medium)
            .background(DashColors.CardHi)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) DashColors.TextPrimary else DashColors.Muted)
    }
}

/** The name of the setting a search opened the pane for; null when it was opened otherwise. */
internal val LocalSettingsTarget = compositionLocalOf<String?> { null }

/** How far below a searched setting's row is brought into view too, so it lands in the upper part of the pane. */
private val TARGET_ROOM_BELOW = 220.dp

/**
 * The row of the setting named [title], when a search was for it
 * ([LocalSettingsTarget]): scrolled into view and lit up a moment. Before,
 * the pane opened at its top and the setting could sit half hidden at the
 * bottom edge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.searchTarget(title: String): Modifier {
    if (LocalSettingsTarget.current != title) return this
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0.22f) }
    val room = with(LocalDensity.current) { TARGET_ROOM_BELOW.toPx() }
    LaunchedEffect(title) {
        withFrameNanos { } // laid out first
        requester.bringIntoView(Rect(0f, 0f, 1f, room))
        glow.animateTo(0f, tween(durationMillis = 1_600, delayMillis = 600))
    }
    return bringIntoViewRequester(requester).background(DashColors.Accent.copy(alpha = glow.value), DashShape.Medium)
}

/** One on/off setting: icon, name, what it does, and a switch; the whole row toggles it. */
@Composable
internal fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(enabled = enabled, role = Role.Switch) { tap(); onChange(!checked) }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DashColors.OnAccent,
                checkedTrackColor = DashColors.Accent,
                uncheckedThumbColor = DashColors.TextSecondary,
                uncheckedTrackColor = DashColors.CardHi,
                uncheckedBorderColor = DashColors.Line
            )
        )
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** Saves the unit's log on the unit itself, for a driver with no phone linked ([DebugLog.saveOnUnit]). */
@Composable
private fun SaveLogRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Android 9 writes Download itself, which needs the storage permission (DebugLog.saveOnUnit).
    val storage = rememberPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !storage.granted
    SettingsRow(
        Icons.Filled.SaveAlt, stringResource(R.string.settings_log_save),
        when {
            saving -> stringResource(R.string.settings_log_sending)
            saved != null -> stringResource(R.string.settings_log_saved, saved!!)
            failed -> stringResource(R.string.settings_log_save_failed)
            else -> stringResource(R.string.settings_log_save_detail)
        }
    ) {
        if (saving) return@SettingsRow
        if (needsStorage) { storage.request(); return@SettingsRow }
        saving = true
        scope.launch {
            val name = DebugLog.saveOnUnit(context)
            saving = false
            saved = name
            failed = name == null
        }
    }
}

/** Gives the unit's log to the phone, to share as a bug report ([DebugLog]). In About and in Lab. */
@Composable
private fun SendLogRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    val last = remember { DebugLog.lastSnapshot(context) }
    SettingsRow(
        Icons.Filled.BugReport, stringResource(R.string.settings_log_title),
        when {
            sending -> stringResource(R.string.settings_log_sending)
            status != null -> stringResource(status!!)
            last != null -> stringResource(R.string.settings_log_detail_last, DateFormat.getTimeFormat(context).format(last))
            else -> stringResource(R.string.settings_log_detail)
        }
    ) {
        if (sending) return@SettingsRow
        sending = true
        scope.launch {
            val ok = DebugLog.sendToPhone(context)
            sending = false
            status = when (ok) {
                true -> R.string.settings_log_sent
                false -> R.string.settings_log_failed
                null -> R.string.settings_log_no_phone
            }
        }
    }
}
