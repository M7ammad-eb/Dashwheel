@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberGauges
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawWithCache

/*
 * The bottom bar, the edit bar that takes its place while arranging, and the page dots.
 */

/**
 * Everything a top bar shows and can do; each skin's bar arranges the same model.
 * The link's state is a [State], read only where it is drawn ([obdConnection]),
 * so a dial of the adapter does not recompose the whole bar. The readings are
 * not in the model: the parts of the bar that show one read its key.
 *
 * A data class: the dashboard builds a fresh model on each of its own
 * recompositions, and only one that differs in a value (the callbacks are
 * memoised) makes the bar recompose.
 */
@Stable
internal data class TopBarModel(
    val clock: String,
    val versionName: String,
    val obdConnectionState: State<ObdConnectionState>,
    val editing: Boolean,
    val layout: DashLayout,
    val onLayout: (DashLayout) -> Unit,
    val onApps: () -> Unit,
    val onConnectObd: () -> Unit,
    /** The link is up: opens the car's fault codes and live readings (issue #121). */
    val onObd: () -> Unit = {},
    val onSplit: () -> Unit,
    val onToggleEdit: () -> Unit,
    val onTemplates: () -> Unit,
    val onSystem: () -> Unit,
    val onCheckUpdates: () -> Unit,
    /** Where the updater stands: a dot on ⋮ and a menu row while a newer build is on offer. */
    val update: UpdateStatus,
    /** Installs the update on offer (downloading it first if need be). */
    val onUpdate: () -> Unit,
    /** "Later": stops the update asking until a newer one. */
    val onDismissUpdate: () -> Unit,
    /** Something the launcher can use is still not allowed: the bar shows a pill that opens the setup. */
    val setupPending: Boolean,
    /** Opens the setup on its access step (the pill), or from the start (Settings). */
    val onSetup: (fromStart: Boolean) -> Unit,
    /** Demo mode is running: the menu offers to stop it. */
    val demo: Boolean,
    val onDemo: () -> Unit,
    /** The head unit's status bar is up (an app window is docked) and already shows the time. */
    val merged: Boolean = false,
    val page: Int = 0,
    /** The car is moving and the drive lock is on: arranging and settings wait (DriveLock.kt). */
    val moving: Boolean = false,
    val lockWhileMoving: Boolean = true,
    val onLockWhileMoving: (Boolean) -> Unit = {},
    /** Opens the Settings screen (SettingsScreen.kt). */
    val onSettings: () -> Unit = {},
    /** Starts the tour of the basics (TourScreen.kt). */
    val onTour: () -> Unit = {},
    /** Opens Moments (MomentsScreen.kt). */
    val onMoments: () -> Unit = {}
) {
    val obdConnection: ObdConnectionState get() = obdConnectionState.value
}

/** The bar from a model built by the caller (shared with the Settings screen). */
@Composable
internal fun TopBar(m: TopBarModel) {
    if (DashColors.Skin == DashSkin.STANDARD) StandardTopBar(m) else SkinTopBar(m)
}

/**
 * Minimal bar: Apps and the layout picker on the left, the clock centred, the
 * OBD link pill and a ⋮ menu on the right. Set-and-forget controls (theme,
 * system install, edit) live in the menu; battery and coolant only appear, as
 * warning pills, when a reading is out of range. The buttons are
 * [DashSize.Bar] tall and reach the screen's bottom edge.
 */
@Composable
internal fun StandardTopBar(m: TopBarModel) {
    // Glass themes float the bar as its own panel over the gradient background;
    // solid themes keep the flat full-width strip.
    val glass = DashColors.Glass
    Surface(color = if (glass) Color.Transparent else DashColors.Bar, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass) Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).then(glassPanel(DashShape.Large))
                    else Modifier
                )
                .padding(horizontal = 6.dp)
        ) {
            // An upright screen, or half of a split one: no room to keep the
            // clock centred, so the left side takes only what its buttons need.
            val narrow = maxWidth < NARROW_BAR
            // The same two words under every look: Apps and Layout, wherever the bar is wide enough.
            val labels = maxWidth >= LABELLED_BAR
            // The driver's own readouts (Settings, Display) replace the theme's middle and end.
            val chosen by BarItems.items.collectAsState()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = if (narrow || chosen != null) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(
                        onClick = m.onApps,
                        label = if (labels) stringResource(R.string.dash_apps) else null,
                        description = stringResource(R.string.dash_all_apps)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
                    }
                    LayoutPicker(m) { open ->
                        BarButton(
                            onClick = open,
                            label = if (labels) stringResource(R.string.dash_layout_button) else null,
                            description = stringResource(R.string.dash_screen_layout, m.layout.title)
                        ) {
                            LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(28.dp))
                        }
                    }
                    GeminiBarButton()
                }

                chosen?.let { items ->
                    // Where the dashboards stand, then the readouts, which take all the
                    // room the end leaves them; what doesn't fit is left out.
                    PagePositionChip(Modifier.padding(start = 8.dp))
                    BarReadouts(items, m, Modifier.weight(1f).padding(horizontal = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.demo) Box(Modifier.padding(end = 6.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                        VehicleAlerts()
                        if (m.setupPending) Box(Modifier.padding(end = 6.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = true) }
                        MorePicker(m, geminiInBar = true) { open ->
                            BarButton(onClick = open, description = stringResource(R.string.dash_more)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    return@Row
                }

                // A cluster bar (Mistral) puts the speed in the middle, like the car's
                // own central display, and moves the clock to the right; without a
                // speed source it is the plain bar again.
                // Whether there is a speed, not the speed: read here, every reading of
                // the car recomposed the whole bar for the length of the drive.
                val cluster = DashColors.BarStyle == DashBarStyle.CLUSTER && rememberHasSpeed()
                Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        cluster -> ClusterReadout(m)
                        // The head unit's status bar shows the time while it is up.
                        // A clock tile in sight already tells the time.
                        !m.merged && !ClockInSight.on.value -> BarClock(m.clock)
                    }
                    // Which dashboard is on screen, beside the time: the one cue the fading cross leaves behind.
                    PagePositionChip(Modifier.padding(start = 10.dp))
                }

                // ⋮ and the OBD pill get their room first, whatever else is on
                // the bar; the badges go short when they share it.
                BarEnd(modifier = Modifier.weight(1f)) {
                    if (cluster && !ClockInSight.on.value) {
                        Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 10.dp)) {
                            BarClock(m.clock, MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (m.demo) {
                        Box(Modifier.layoutId(BarRank.DEMO).padding(end = 6.dp)) {
                            DemoBadge(onStop = m.onDemo, compact = narrow || m.setupPending)
                        }
                    }
                    Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                        VehicleAlerts()
                    }
                    Box(Modifier.layoutId(BarRank.PHONE).padding(end = 6.dp)) { PhonePill() }
                    if (m.setupPending) {
                        Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                            SetupPill(onClick = { m.onSetup(false) }, compact = narrow || m.demo)
                        }
                    }
                    Box(Modifier.layoutId(BarRank.OBD)) { ObdPill(m.obdConnection, m.onConnectObd, onOpen = m.onObd) }
                    Box(Modifier.layoutId(BarRank.MORE)) {
                        MorePicker(m, geminiInBar = true) { open ->
                            BarButton(onClick = open, description = stringResource(R.string.dash_more)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gemini Live from the bar ([GeminiLive]): its sparkle, and while the
 * conversation is open a red end button that breathes, so the driver sees
 * Gemini is listening. Only with Gemini on the unit.
 */
@Composable
private fun GeminiBarButton() {
    val context = LocalContext.current
    val installed = GeminiLive.rememberAvailable(context)
    if (!installed) return
    val active by GeminiLive.active.collectAsState()
    val label = stringResource(if (active) R.string.ai_gemini_live_end else R.string.ai_gemini_live)
    BarButton(onClick = { GeminiLive.toggle(context) }, description = label) {
        if (active) {
            // A status pulse at the status rate (20 fps), read while drawing: an
            // infinite transition would rebuild the bar at the display's rate.
            val pulse = rememberLoop(900, reverse = true, status = true)
            Box(
                modifier = Modifier.size(36.dp).graphicsLayer { alpha = 0.6f + 0.4f * pulse.value }.clip(CircleShape).background(DashColors.Critical),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        } else {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(28.dp))
        }
    }
}

/** Under this width a bar stops centring its clock and shortens its badges. */
internal val NARROW_BAR = 900.dp

/** From this width every look's bar writes "Apps" and "Layout" beside their icons. */
internal val LABELLED_BAR = 720.dp

/** Who gets room first at the bar's end ([BarEnd]); lowest first. */
internal object BarRank {
    const val MORE = 0
    const val OBD = 1
    const val CLOCK = 2
    const val ALERTS = 3
    const val DEMO = 4
    const val SETUP = 5
    const val PHONE = 6

    /** A skin's outside temperature: the weather tile says the same, so it goes first. */
    const val TEMP = 7
}

/**
 * The bar's end, right-aligned in the order written. Room is handed out by
 * [BarRank] (the child's layoutId), so a crowded bar never squeezes ⋮ or the
 * OBD pill: what comes last in rank and no longer fits is left out whole.
 * [alignEnd] false starts from the left instead, for a cluster at the bar's
 * start (Orbit's island on a left-hand-drive car).
 */
@Composable
internal fun BarEnd(modifier: Modifier = Modifier, alignEnd: Boolean = true, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val maxHeight = constraints.maxHeight
        var left = constraints.maxWidth
        measurables.indices
            .sortedBy { (measurables[it].layoutId as? Int) ?: Int.MAX_VALUE }
            .forEach { i ->
                val rank = (measurables[i].layoutId as? Int) ?: Int.MAX_VALUE
                // The menu and the link pill always show; the rest only where it fits.
                if (rank > BarRank.OBD && (left <= 0 || measurables[i].minIntrinsicWidth(maxHeight) > left)) return@forEach
                val p = measurables[i].measure(Constraints(maxWidth = left.coerceAtLeast(0), maxHeight = maxHeight))
                placeables[i] = p
                left -= p.width
            }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.maxWidth - left
        val height = (placeables.maxOfOrNull { it?.height ?: 0 } ?: 0).coerceIn(constraints.minHeight, maxHeight)
        layout(width, height) {
            var x = if (alignEnd) left.coerceAtLeast(0) else 0
            placeables.forEach { p ->
                if (p != null) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width
                }
            }
        }
    }
}

/**
 * A button of the standard bar: [DashSize.Bar] each way at least, so it can
 * be hit without aiming, with [label] beside the icon where there is room.
 */
@Composable
private fun BarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
    }
}

/** Whether the dashboard page in sight has a clock tile: the bar then leaves the time out. */
internal object ClockInSight {
    val on = androidx.compose.runtime.mutableStateOf(false)
}

@Composable
private fun BarClock(clock: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(
        text = clock,
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em,
        style = style
    )
}

/**
 * The centre of a cluster bar: the rev counter as a row of segments (the
 * last ones amber, then red), the speed in the hero face under it, and fuel
 * and coolant as short segment bars on either side. Segments only, no
 * needles: the reading is the count of lit cells.
 */
@Composable
private fun ClusterReadout(m: TopBarModel) {
    // The readings are read here, so they recompose the readout alone.
    ClusterReadout(rememberSpeedKmh() ?: 0)
}

@Composable
private fun ClusterReadout(speedKmh: Int) {
    val g = rememberGauges(K.RPM, K.COOLANT_TEMP)
    // The same fuel as the Fuel & Range tile: the CANbox's level first (many
    // cars, the C4 Picasso among them, don't answer the OBD fuel PID), then OBD.
    val fuel = rememberFuel()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        FuelSegmentBar(label = stringResource(R.string.vehicle_fuel), percent = fuel?.percent)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // On the car's own scale: a diesel's 4 500 red line lights the last segments, a petrol's 7 500 fits.
                val scale = rememberRevScale()
                val lit = g.rpm?.let { (scale.fraction(it) * TACHO_SEGMENTS).toInt().coerceIn(0, TACHO_SEGMENTS) } ?: 0
                val redFrom = (scale.redFrom * TACHO_SEGMENTS).roundToInt().coerceIn(1, TACHO_SEGMENTS)
                repeat(TACHO_SEGMENTS) { i ->
                    val colour = when {
                        i >= lit -> DashColors.CardHi
                        i >= redFrom -> DashColors.Critical
                        i >= redFrom - 2 -> DashColors.Tacho
                        else -> DashColors.Accent
                    }
                    Box(Modifier.size(width = 12.dp, height = 5.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                }
            }
            val u = LocalUnits.current
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    u.speed(speedKmh).toString(),
                    color = DashColors.AccentInk,
                    fontFamily = DashColors.heroFamily(),
                    fontWeight = DashColors.HeroWeight,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.02).em,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    u.speedUnit.uppercase(),
                    color = DashColors.TextSecondary,
                    letterSpacing = 0.1.em,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        SegmentBar(
            label = stringResource(R.string.vehicle_coolant),
            fraction = (g.coolantTempC ?: 0) / 120f,
            hot = (g.coolantTempC ?: 0) >= COOLANT_WARNING_C,
            value = g.coolantTempC?.let { "${LocalUnits.current.temp(it)}°" }
        )
    }
}

private const val TACHO_SEGMENTS = 14

private const val FUEL_SEGMENTS = 10

/**
 * Fuel as ten segments, one per 10 % (a started tenth lights its segment, so
 * the last drops still show). The lit segments turn amber at 20 % and red at 10 %.
 */
@Composable
private fun FuelSegmentBar(label: String, percent: Int?) {
    val level = percent?.coerceIn(0, 100)
    val lit = if (level == null) 0 else (level * FUEL_SEGMENTS + 99) / 100
    val litColour = when {
        level == null -> DashColors.Accent
        level <= 10 -> DashColors.Critical
        level <= 20 -> DashColors.Tacho
        else -> DashColors.Accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(FUEL_SEGMENTS) { i ->
                val colour = if (i < lit) litColour else DashColors.CardHi
                Box(Modifier.size(width = 5.dp, height = 14.dp).clip(RoundedCornerShape(1.dp)).background(colour))
            }
            if (level != null) SegmentValue("$level %")
        }
        Text(label.uppercase(), color = DashColors.TextSecondary, letterSpacing = 0.12.em, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** The reading next to a segment bar, for a glance that doesn't count cells. */
@Composable
private fun SegmentValue(text: String) {
    Text(
        text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 4.dp)
    )
}

/** Six segments and a caption; the top segment reads red when [hot]. */
@Composable
private fun SegmentBar(label: String, fraction: Float, hot: Boolean, value: String? = null) {
    val lit = (fraction * 6f).toInt().coerceIn(0, 6)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(6) { i ->
                val colour = when {
                    i >= lit -> DashColors.CardHi
                    hot && i == 5 -> DashColors.Critical
                    else -> DashColors.Accent
                }
                Box(Modifier.size(width = 8.dp, height = 14.dp).clip(RoundedCornerShape(1.dp)).background(colour))
            }
            if (value != null) SegmentValue(value)
        }
        Text(label.uppercase(), color = DashColors.TextSecondary, letterSpacing = 0.12.em, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * The Layout menu around any [anchor] a skin draws: the anchor gets an `open`
 * callback, the menu offers the three ways of sharing the screen with Google
 * Maps, the current one checked, and under them the system's split screen
 * with any other app. One door for both: the bar's button is the only place
 * the screen is divided from.
 */
@Composable
internal fun LayoutPicker(m: TopBarModel, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Switching layout reloads every page: parked only, like arranging.
    val lock = LocalDriveLock.current
    Box {
        anchor { lock.whenParked { open = true } }
        DashMenu(open, onDismiss = { open = false }) {
            // The Maps dock is placed through the shell: the Play edition offers the dashboards alone.
            // It is a floating window too: a unit that cannot float Maps opened it full
            // screen and Home kept landing on it, so the dock is offered only where it can
            // float (DockSupport). The layout in use stays listed, ticked.
            val context = LocalContext.current
            val dockOffered = remember(open) { !Edition.play && DockSupport.offered(context) }
            val layouts = DashLayout.entries.filter { it == DashLayout.GRID || it == m.layout || dockOffered }
            layouts.forEach { l ->
                DashMenuItem(
                    text = l.title,
                    leading = { LayoutIcon(l, null, if (l == m.layout) DashColors.Accent else DashColors.TextSecondary) },
                    selected = l == m.layout,
                    onClick = {
                        open = false
                        m.onLayout(l)
                    }
                )
            }
            HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
            DashMenuItem(
                text = stringResource(R.string.dash_layout_another_app),
                leading = { MenuIcon(Icons.Filled.Splitscreen) },
                onClick = {
                    open = false
                    m.onSplit()
                }
            )
        }
    }
}

/** Icon for a layout: a dashboard, or a split whose solid pane (the map) sits on the docked side. */
@Composable
internal fun LayoutIcon(layout: DashLayout, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    val vertical = ScreenShape.vertical
    Icon(
        imageVector = when {
            layout == DashLayout.GRID -> Icons.Filled.SpaceDashboard
            vertical -> Icons.Filled.HorizontalSplit
            else -> Icons.Filled.VerticalSplit
        },
        contentDescription = contentDescription,
        tint = tint,
        // VerticalSplit draws its solid pane (the map) on the right, HorizontalSplit at the
        // bottom; mirror them for the left dock (on top, upright).
        modifier = modifier.then(
            when {
                layout != DashLayout.MAPS_LEFT -> Modifier
                vertical -> Modifier.scale(scaleX = 1f, scaleY = -1f)
                else -> Modifier.scale(scaleX = -1f, scaleY = 1f)
            }
        )
    )
}

internal fun obdStatusColor(state: ObdConnectionState): Color = when (state) {
    ObdConnectionState.CONNECTED -> DashColors.Good
    ObdConnectionState.CONNECTING -> DashColors.Accent
    ObdConnectionState.ERROR -> DashColors.Critical
    ObdConnectionState.DISCONNECTED -> DashColors.Muted
}

@StringRes
internal fun obdStatusLabelRes(state: ObdConnectionState): Int = when (state) {
    ObdConnectionState.CONNECTED -> R.string.dash_obd_connected
    ObdConnectionState.CONNECTING -> R.string.dash_obd_connecting
    ObdConnectionState.ERROR -> R.string.dash_obd_error
    ObdConnectionState.DISCONNECTED -> R.string.dash_obd_off
}

/** Spoken OBD link state; outside composition use [obdStatusLabelRes]. */
@Composable
internal fun obdStatusLabel(state: ObdConnectionState): String = stringResource(obdStatusLabelRes(state))

/** Whether a tap on the OBD mark opens the steps for the phone, not another try that would fail the same way. */
internal fun obdTapOpensHelp(state: ObdConnectionState, phoneBlocking: Boolean): Boolean =
    state == ObdConnectionState.ERROR && phoneBlocking

/**
 * What the OBD mark of every bar does, whatever a skin draws as [content]: a
 * [DashSize.TouchPrimary] target with the tap feedback that connects while
 * the link is idle, the steps for the phone when its Android Auto holds the
 * adapter, the spot the tour lights up, and with [onOpen] a tap while linked
 * that opens the car's codes and readings.
 */
@Composable
internal fun ObdMark(
    state: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = DashShape.Pill,
    clickLabel: String? = null,
    onOpen: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val label = obdStatusLabel(state)
    val tap = rememberTapFeedback()
    // The phone's Android Auto holding the radio: another try would fail the same way, the steps help.
    val phoneBlocking by ObdBluetoothManager.phoneBlocking.collectAsState()
    var help by remember { mutableStateOf(false) }
    if (help) PhoneAaHelpDialog(onConnect = { help = false; onConnect() }, onDismiss = { help = false })
    Box(
        modifier = modifier
            .tourTarget(TourTarget.OBD)
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clip(shape)
            .clickable(enabled = state.isIdle || (onOpen != null && state == ObdConnectionState.CONNECTED), onClickLabel = clickLabel, role = Role.Button) {
                tap()
                when {
                    state == ObdConnectionState.CONNECTED -> onOpen?.invoke()
                    obdTapOpensHelp(state, phoneBlocking) -> help = true
                    else -> onConnect()
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * OBD link as a pill that reads without colour: a dot (hollow when off, lit
 * with a halo when live, pulsing while connecting, with a "!" on error) and
 * the letters OBD. What a tap does is [ObdMark]'s.
 */
@Composable
internal fun ObdPill(state: ObdConnectionState, onConnect: () -> Unit, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val color = obdStatusColor(state)
    val off = state == ObdConnectionState.DISCONNECTED
    val connecting = state == ObdConnectionState.CONNECTING
    val pulse = if (connecting) rememberLoop(900, reverse = true, status = true) else null
    val halo = DashColors.Glow
    val ink = if (off) DashColors.Muted else color
    val shape = DashShape.Pill
    ObdMark(state, onConnect, modifier, onOpen = onOpen) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .height(40.dp)
                .clip(shape)
                .background(if (off) Color.Transparent else color.copy(alpha = 0.14f))
                .border(1.dp, if (off) DashColors.Line else color.copy(alpha = 0.5f), shape)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    // Its own layer: the pulse redraws the dot, not the whole bar.
                    .graphicsLayer()
                    .drawWithCache {
                        val r = size.minDimension / 2f
                        val haloBrush = if (state == ObdConnectionState.CONNECTED && halo > 0f) {
                            Brush.radialGradient(listOf(color.copy(alpha = 0.6f * halo), Color.Transparent), Offset(size.width / 2f, size.height / 2f), r * 2.4f)
                        } else null
                        val ring = Stroke(1.5.dp.toPx())
                        val ringR = r - 1.dp.toPx()
                        onDrawBehind {
                            val a = pulse?.let { 0.35f + 0.65f * it.value } ?: 1f
                            if (haloBrush != null) drawCircle(haloBrush, radius = r * 2.4f)
                            if (off) drawCircle(ink, radius = ringR, style = ring)
                            else drawCircle(color.copy(alpha = a), radius = r)
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.dash_obd_short),
                color = ink,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(4.dp))
                Text("!", color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Whether the phone's pill or an alert chip is on the bar: a skin's setup
 * pill then goes short, so they all fit beside its clock.
 */
@Composable
internal fun barIsShared(): Boolean {
    val phone by UnitSignals.phone.collectAsState()
    val link by PhoneLink.state.collectAsState()
    return phone != null || link is PhoneLinkState.Connected ||
        AlertCenter.critical.isNotEmpty() || AlertCenter.warnings.isNotEmpty()
}

/** Why there is no weather to show yet. */
internal enum class WeatherWait { LOADING, NO_GPS, UNAVAILABLE }

/**
 * What a weather readout says while it has nothing, as the standard tile
 * tells it: no position to ask about, an answer that failed ([error], the
 * repo's, null while none did), or simply not there yet.
 */
internal fun weatherWait(hasLocation: Boolean, error: String?): WeatherWait = when {
    !hasLocation -> WeatherWait.NO_GPS
    error != null -> WeatherWait.UNAVAILABLE
    else -> WeatherWait.LOADING
}

/**
 * The phone beside the car's alerts in every skin's bar: its name and its
 * battery. Exact, with the charging, from the companion when it is linked
 * ([PhoneLink.battery]); else as the head unit's Bluetooth gives it, five
 * bars ([UnitSignals]). Nothing while no phone is connected either way.
 */
@Composable
internal fun PhonePill(modifier: Modifier = Modifier) {
    val phone by UnitSignals.phone.collectAsState()
    val link by PhoneLink.state.collectAsState()
    val exact by PhoneLink.battery.collectAsState()
    val name = phone?.name ?: (link as? PhoneLinkState.Connected)?.phoneName ?: return
    val shape = DashShape.Pill
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, if (exact?.charging == true) DashColors.Good.copy(alpha = 0.6f) else DashColors.Line, shape)
            .semantics(mergeDescendants = true) {}
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(18.dp))
        val b = exact
        when {
            b != null -> {
                Spacer(Modifier.width(4.dp))
                if (b.charging) ChargingBattery(b.percent) else {
                    Icon(
                        batteryIcon(b.percent), contentDescription = null,
                        tint = if (b.percent <= LOW_PERCENT) DashColors.Critical else DashColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    stringResource(R.string.phone_battery_percent, b.percent),
                    color = when {
                        b.charging -> DashColors.Good
                        b.percent <= LOW_PERCENT -> DashColors.Critical
                        else -> DashColors.TextSecondary
                    },
                    style = MaterialTheme.typography.labelSmall, maxLines = 1
                )
            }
            phone?.battery != null -> {
                val level = phone!!.battery!!
                Spacer(Modifier.width(4.dp))
                // A battery that fills with the phone's level (0..5); red when nearly empty.
                Icon(
                    when (level) {
                        0 -> Icons.Filled.BatteryAlert
                        1 -> Icons.Filled.Battery1Bar
                        2 -> Icons.Filled.Battery3Bar
                        3 -> Icons.Filled.Battery4Bar
                        4 -> Icons.Filled.Battery5Bar
                        else -> Icons.Filled.BatteryFull
                    },
                    contentDescription = null,
                    tint = if (level <= 1) DashColors.Critical else DashColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 120.dp)
        )
    }
}

/** Under this the phone's battery shows red. */
private const val LOW_PERCENT = 15

private fun batteryIcon(percent: Int) = when {
    percent <= 5 -> Icons.Filled.BatteryAlert
    percent <= 20 -> Icons.Filled.Battery1Bar
    percent <= 40 -> Icons.Filled.Battery3Bar
    percent <= 60 -> Icons.Filled.Battery4Bar
    percent <= 85 -> Icons.Filled.Battery5Bar
    else -> Icons.Filled.BatteryFull
}

/**
 * A green battery with a bolt whose fill rises from the phone's level to
 * full and starts again, the way phones show charging. Drawn here: the icon
 * set has one charging battery only.
 */
@Composable
private fun ChargingBattery(percent: Int) {
    val from = (percent / 100f).coerceIn(0f, 1f)
    // Stepped at the status rate (20 fps) and read while drawing: it sits in every
    // look's bar for as long as the phone charges, which can be the whole drive.
    val loop = rememberLoop(CHARGE_MS, status = true)
    val green = DashColors.Good
    Box(Modifier.size(width = 12.dp, height = 18.dp), contentAlignment = Alignment.Center) {
        // Its own layer: the fill's steps redraw the battery, not the whole bar.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val stroke = 1.5.dp.toPx()
            val cap = Size(size.width * 0.4f, 2.dp.toPx())
            drawRect(green, topLeft = Offset((size.width - cap.width) / 2f, 0f), size = cap)
            val top = cap.height
            val body = Size(size.width, size.height - top)
            drawRoundRect(green, topLeft = Offset(0f, top), size = body, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(stroke))
            val inner = body.height - stroke * 2
            val filled = inner * (if (percent >= 100) 1f else from + (1f - from) * loop.value)
            drawRect(green.copy(alpha = 0.55f), topLeft = Offset(stroke, top + stroke + inner - filled), size = Size(body.width - stroke * 2, filled))
        }
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(11.dp))
    }
}

/** One rise of the fill, from the level to full. */
private const val CHARGE_MS = 1_600

/**
 * Menu around any [anchor] a skin draws: the things done often (edit,
 * templates, split screen), one door to everything set once, and a way out
 * of the demo while it runs (it starts from Settings → Advanced).
 */
@Composable
internal fun MorePicker(m: TopBarModel, geminiInBar: Boolean = false, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    val offered = m.update is UpdateStatus.Available || m.update is UpdateStatus.Ready
    val context = LocalContext.current
    Box(Modifier.tourTarget(TourTarget.MENU)) {
        anchor {
            // Opening the menu shows what the dot meant: the hint has done its job.
            if (offered) BarHints.updateDotExplained(context)
            open = true
        }
        // A newer build waits behind the menu: a dot on its corner says so, and
        // the first time, a line over the button says what the dot means.
        if (offered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(DashColors.Accent)
                    .semantics { contentDescription = "" }
            )
            UpdateDotHint()
        }
        DashMenu(open, onDismiss = { open = false }, prominent = true) {
            if (m.moving) DriveLockRow()
            val parked = !m.moving
            UpdateMenuRow(m.update, parked, onUpdate = pick(m.onUpdate))
            DashMenuItem(
                text = stringResource(if (m.editing) R.string.dash_menu_done_editing else R.string.dash_menu_edit_dashboards),
                leading = { MenuChip(if (m.editing) Icons.Filled.Done else Icons.Filled.Edit, DashColors.Accent, parked) },
                enabled = parked || m.editing,
                onClick = pick(m.onToggleEdit)
            )
            DashMenuItem(stringResource(R.string.templates_button), leading = { MenuChip(Icons.Filled.Dashboard, DashColors.Accent, parked) }, enabled = parked, onClick = pick(m.onTemplates))
            // Splitting the screen with an app is in the bar's Layout menu, with Google Maps' dock.
            // Spoken, so offered while driving too; here only when the look's bar has no button for it.
            if (!geminiInBar && GeminiLive.available(context)) {
                DashMenuItem(stringResource(R.string.ai_gemini_live), leading = { MenuChip(Icons.Filled.AutoAwesome, DashColors.Accent2) }, onClick = pick { GeminiLive.toggle(context) })
            }
            // What the car says by itself, off for this drive and back: the same switch as the wheel's Quiet.
            val quiet by CarVoice.quietState.collectAsState()
            DashMenuItem(
                text = stringResource(if (quiet) R.string.voice_menu_back else R.string.voice_menu_quiet),
                leading = { MenuChip(if (quiet) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, DashColors.Secondary) },
                onClick = pick {
                    CarVoice.quiet = !quiet
                    if (quiet) HandsFree.say(context, R.string.voice_quiet_off)
                }
            )
            // Settings, set once, stands apart from the things done often.
            HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            DashMenuItem(stringResource(R.string.settings_menu), leading = { MenuChip(Icons.Filled.Settings, DashColors.TextPrimary, parked) }, enabled = parked, onClick = pick(m.onSettings))
            if (m.demo) {
                HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
                DashMenuItem(
                    text = stringResource(R.string.demo_menu_stop),
                    leading = { MenuChip(Icons.Filled.Stop, DashColors.Critical) },
                    onClick = pick(m.onDemo)
                )
            }
        }
    }
}

/** One-time hints of the bar, remembered across starts. */
internal object BarHints {
    private const val PREFS = "bar_hints"
    private const val KEY_UPDATE_DOT = "update_dot_explained"

    /** Whether the line over ⋮ saying what its dot means has been shown (or the menu opened with the dot on). */
    fun isUpdateDotExplained(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_UPDATE_DOT, false)

    fun updateDotExplained(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_UPDATE_DOT, true).apply()
    }
}

/** How long the line over ⋮ explaining its dot stays, the first time the dot shows. */
internal const val UPDATE_DOT_HINT_MS = 8_000L

/**
 * Over the ⋮ button, the first time its dot appears: one line saying what
 * it means (an update waits in the menu). Goes by itself after
 * [UPDATE_DOT_HINT_MS], and never comes back once seen or once the menu
 * has been opened with the dot on ([BarHints]).
 */
@Composable
private fun UpdateDotHint() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(!BarHints.isUpdateDotExplained(context)) }
    if (!show) return
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(UPDATE_DOT_HINT_MS)
        BarHints.updateDotExplained(context)
        show = false
    }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    // Above the button, its end on the button's end, kept inside the window.
    val above = remember(gap) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize
            ): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = above) {
        val shape = DashShape.Pill
        Row(
            modifier = Modifier
                .clip(shape)
                .background(DashColors.Card.copy(alpha = 1f))
                .border(1.dp, DashColors.Accent.copy(alpha = 0.6f), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(DashColors.Accent))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.dash_update_dot_hint),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

/** First row of the menu while the car moves: why the entries under it are greyed out. */
@Composable
private fun DriveLockRow() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.dash_drive_lock_notice),
            color = DashColors.Warning,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(bottom = 4.dp))
}

/** Floats over the pages for a moment after a tap the drive lock held back. */
@Composable
internal fun DriveLockChip(modifier: Modifier = Modifier) {
    val shape = DashShape.Pill
    Row(
        modifier = modifier
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.dash_drive_lock_notice),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
    }
}

/** How long [DriveLockChip] stays up. */
internal const val LOCK_NOTICE_MS = 2_500L

/** Whether the [DashMenu] around a row is the bar's More menu, drawn to stand out ([MorePicker]). */
private val LocalProminentMenu = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A menu of the bar. [prominent] (the More menu) lifts it off the dashboard
 * it opens over: a lighter panel between [DashColors.Card] and
 * [DashColors.CardHi], an accent edge, a deeper shadow and its labels a step
 * bolder, so it no longer melts into dark tiles.
 */
@Composable
private fun DashMenu(open: Boolean, onDismiss: () -> Unit, prominent: Boolean = false, content: @Composable () -> Unit) {
    // An auto-hiding bar stays up while its menu is open (BarAutoHide.kt).
    if (open) {
        DisposableEffect(Unit) {
            BarAutoHide.openMenus++
            onDispose { BarAutoHide.openMenus-- }
        }
    }
    // Docked windows are drawn above the bar's pop-ups; one the menu overlaps steps aside meanwhile.
    // Kept inside the dashboard's window, clear of bars the head unit draws itself (WindowMenu.kt).
    WindowMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier.keepClearOfWindows(),
        shape = if (prominent) DashShape.Large else MaterialTheme.shapes.medium,
        containerColor = if (prominent) {
            androidx.compose.ui.graphics.lerp(DashColors.Card.copy(alpha = 1f), DashColors.CardHi.copy(alpha = 1f), 0.5f)
        } else DashColors.Card.copy(alpha = 1f),
        border = if (prominent) BorderStroke(1.5.dp, DashColors.Accent.copy(alpha = 0.55f)) else BorderStroke(1.dp, DashColors.Line),
        shadow = if (prominent) 16.dp else 3.dp
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalProminentMenu provides prominent) {
            content()
        }
    }
}

@Composable
private fun DashMenuItem(
    text: String,
    leading: @Composable () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** In the accent colour without being a choice (no check mark): the update on offer. */
    highlighted: Boolean = false,
    /** A second, smaller line under [text]. */
    detail: String? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        // Rows a finger finds in a moving car, not a phone menu's.
        modifier = Modifier.heightIn(min = DashSize.MenuRow),
        text = {
            Column {
                Text(
                    text,
                    color = when {
                        !enabled -> DashColors.Muted
                        selected || highlighted -> DashColors.Accent
                        else -> DashColors.TextPrimary
                    },
                    fontWeight = when {
                        selected || highlighted -> FontWeight.SemiBold
                        LocalProminentMenu.current -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                if (detail != null) {
                    Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        },
        leadingIcon = leading,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent) }
        } else null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun MenuIcon(icon: ImageVector, enabled: Boolean = true) {
    Icon(icon, contentDescription = null, tint = if (enabled) DashColors.TextSecondary else DashColors.Muted)
}

/**
 * The More menu's icon: [icon] in [hue] on a round chip of the same hue, so
 * each entry is told apart at a glance. Greyed like the row when it is off.
 */
@Composable
private fun MenuChip(icon: ImageVector, hue: Color, enabled: Boolean = true) {
    val ink = if (enabled) hue else DashColors.Muted
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = if (enabled) 0.2f else 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * Alert pills on the bar (VehicleAlerts.kt): amber ones while a reading is
 * out of range, red ones for a critical reading, kept until tapped. Nothing
 * shows while everything is normal or OBD is off.
 */
@Composable
internal fun VehicleAlerts() {
    // Judged for the whole process (AlertCenter.evaluate, from VehicleMonitor),
    // so an alert raised behind a full-screen app is here when the bar comes back.
    val held = AlertCenter.critical
    held.values.sortedBy { it.key }.forEach { alert ->
        AlertChip(alert, onAcknowledge = { AlertCenter.acknowledge(alert.key) })
    }
    AlertCenter.warnings.filter { !held.containsKey(it.key) }.forEach { alert ->
        AlertChip(alert, onAcknowledge = null)
    }
}

/** One alert pill: amber for a warning, red for a critical one, which also takes a tap to dismiss. */
@Composable
private fun AlertChip(alert: VehicleAlert, onAcknowledge: (() -> Unit)?) {
    val colour = if (alert.level == AlertLevel.CRITICAL) DashColors.Critical else DashColors.Warning
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .padding(end = 6.dp)
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .background(colour.copy(alpha = if (alert.level == AlertLevel.CRITICAL) 0.22f else 0.14f))
            .border(1.dp, colour.copy(alpha = if (alert.level == AlertLevel.CRITICAL) 0.7f else 0.45f), shape)
            .then(
                if (onAcknowledge != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_dismiss)) { tap(); onAcknowledge() }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(alert.icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            alert.text,
            color = colour,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Shortens first when the bar is tight; the icons keep their size.
            modifier = Modifier.weight(1f, fill = false)
        )
        if (onAcknowledge != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Close, contentDescription = null, tint = colour, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Floats over the dashboard while [DemoMode] runs, so made-up readings are
 * never taken for the car's; tap to stop. [compact] where the bar is shared
 * with other badges: the dot and "Stop demo".
 */
@Composable
internal fun DemoBadge(onStop: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Accent.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button) { tap(); onStop() }
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(DashColors.Accent))
        Spacer(Modifier.width(8.dp))
        if (!compact) {
            Text(
                stringResource(R.string.demo_badge),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(12.dp))
        }
        Icon(Icons.Filled.Close, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(if (compact) R.string.demo_menu_stop else R.string.demo_stop),
            color = DashColors.AccentInk,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/**
 * The bar while arranging, in the launcher bar's place: what to do, plus
 * Add / Undo / Templates / Text size / Reset / New dashboard / Done, each
 * [DashSize.TouchPrimary] tall. Changes save as they happen; Undo walks back
 * through the last edits. The page's name opens its dashboard (name, place,
 * remove). An upright screen keeps the page's name and shows the actions as icons.
 */
@Composable
internal fun EditBar(
    page: Int,
    canUndo: Boolean,
    /** The page's text size (the zoom its tiles share, or the commonest one). */
    pageZoom: Float,
    onAdd: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onTemplates: () -> Unit,
    /** Sets every tile on the page to this zoom. */
    onPageZoom: (Float) -> Unit,
    /** Opens the page's dashboard sheet. */
    onDashboard: () -> Unit,
    /** Starts a new dashboard; null when every spot of the cross has one. */
    onNewDashboard: (() -> Unit)?,
    /** Shows every dashboard, to add and remove them; null over an app (the rail does it). */
    onDashboards: (() -> Unit)?,
    /** Shares this dashboard, or puts a shared one in its place ([DashShareSheet]). */
    onShare: () -> Unit,
    onDone: () -> Unit
) {
    val glass = DashColors.Glass
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val narrow = maxWidth < NARROW_BAR
        val actionLabels = listOfNotNull(
            stringResource(R.string.dash_add), stringResource(R.string.dash_undo), stringResource(R.string.templates_button),
            stringResource(R.string.zoom_page), stringResource(R.string.dash_reset_page),
            onNewDashboard?.let { stringResource(R.string.canvas_tab_new) }, onDashboards?.let { stringResource(R.string.dash_dashboards) },
            stringResource(R.string.share_button)
        )
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val labelsWidth = with(LocalDensity.current) { actionLabels.sumOf { measurer.measure(it, labelStyle).size.width }.toDp() }
        // The actions keep their names only where the page's name still reads beside
        // them: in French they left it "Organisatio…" on a 1280 px screen.
        val iconsOnly = narrow || labelsWidth + EDIT_ACTION_CHROME * actionLabels.size + EDIT_BAR_REST > maxWidth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .then(if (glass) glassPanel(shape) else Modifier.clip(shape).background(DashColors.Bar))
                .heightIn(min = DashSize.Bar)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 2.dp else 6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(DashColors.Accent)
            )
            Spacer(Modifier.width(4.dp))
            val dashboardLabel = stringResource(R.string.dash_dashboard_options)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DashSize.TouchPrimary)
                    .clip(DashShape.Small)
                    .clickable(role = Role.Button, onClickLabel = dashboardLabel) { tap(); onDashboard() }
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.dash_arranging_dashboard, pageName(page)),
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Edit, contentDescription = dashboardLabel, tint = DashColors.Accent, modifier = Modifier.size(16.dp))
                }
                if (!narrow) {
                    Text(
                        stringResource(R.string.dash_arranging_hint),
                        color = DashColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Box(Modifier.tourTarget(TourTarget.ADD)) { EditAction(Icons.Filled.Add, stringResource(R.string.dash_add), iconsOnly, onClick = onAdd) }
            EditAction(Icons.Filled.Undo, stringResource(R.string.dash_undo), iconsOnly, enabled = canUndo, onClick = onUndo)
            EditAction(Icons.Filled.Dashboard, stringResource(R.string.templates_button), iconsOnly, onClick = onTemplates)
            PageZoomButton(pageZoom, iconsOnly, onPageZoom)
            EditAction(Icons.Outlined.DeleteOutline, stringResource(R.string.dash_reset_page), iconsOnly, onClick = onReset)
            if (onNewDashboard != null) EditAction(Icons.Filled.LibraryAdd, stringResource(R.string.canvas_tab_new), iconsOnly, onClick = onNewDashboard)
            if (onDashboards != null) EditAction(Icons.Filled.LibraryAdd, stringResource(R.string.dash_dashboards), iconsOnly, onClick = onDashboards)
            EditAction(Icons.Filled.Share, stringResource(R.string.share_button), iconsOnly, onClick = onShare)
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { tap(); onDone() },
                modifier = Modifier.heightIn(min = DashSize.TouchPrimary),
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                shape = DashShape.Small,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dash_done), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** An edit bar action's room besides its name: padding, icon, gap, and the space to the next. */
private val EDIT_ACTION_CHROME = 54.dp

/** The edit bar's room besides its actions: margins, the dot, Done, and a page name that still reads. */
private val EDIT_BAR_REST = 460.dp

/** One action of the edit bar: icon and name, or the icon alone where the bar is [compact]. */
@Composable
private fun EditAction(
    icon: ImageVector,
    label: String,
    compact: Boolean,
    enabled: Boolean = true,
    ink: Color = DashColors.TextPrimary,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    val tint = if (enabled) ink else DashColors.Muted
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clip(DashShape.Small)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled && ink == DashColors.TextPrimary) DashColors.Accent else tint, modifier = Modifier.size(22.dp))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = tint, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Floats over the pages for a few seconds after a page change, then fades:
 * the dashboards as the cross they form, the one on screen filled with
 * the accent, the rest hollow, and its name under it. With [wayHome] (a
 * sideways swipe above or below Home, where there is nothing beside the
 * page) Home is lit too, with an arrow towards it and the swipe that gets
 * there. It takes no room in the layout and no touches.
 */
@Composable
internal fun PageIndicator(current: Int, cross: ShownCross, modifier: Modifier = Modifier, wayHome: Boolean = false) {
    val cell = 12.dp
    val gap = 3.dp
    val accent = DashColors.Accent
    val ink = DashColors.TextSecondary
    // Above Home, the swipe up brings the page below (Home); below it, the swipe down.
    val homeBelow = DashboardStore.COLUMN.indexOf(current).let { it in 0 until DashboardStore.COLUMN_HOME }
    Column(
        modifier = modifier
            .clip(DashShape.Medium)
            .background(DashColors.Card.copy(alpha = 0.92f))
            .border(1.dp, DashColors.Line, DashShape.Medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(width = cell * 3 + gap * 2, height = cell * 5 + gap * 4)) {
            val c = cell.toPx()
            val step = (cell + gap).toPx()
            val r = CornerRadius(3.dp.toPx())
            fun draw(page: Int, col: Int, row: Int) {
                val topLeft = Offset(col * step, row * step)
                when {
                    page == current -> drawRoundRect(accent, topLeft, Size(c, c), r)
                    wayHome && page == DashboardStore.CENTER -> {
                        drawRoundRect(accent.copy(alpha = 0.3f), topLeft, Size(c, c), r)
                        drawRoundRect(accent, topLeft, Size(c, c), r, style = Stroke(2.dp.toPx()))
                        // The arrow points at Home from the page on screen.
                        val cx = topLeft.x + c / 2
                        val cy = topLeft.y + c / 2
                        val tip = if (homeBelow) c * 0.28f else -c * 0.28f
                        val arrow = Path().apply {
                            moveTo(cx - c * 0.3f, cy - tip * 0.5f)
                            lineTo(cx + c * 0.3f, cy - tip * 0.5f)
                            lineTo(cx, cy + tip)
                            close()
                        }
                        drawPath(arrow, accent)
                    }
                    else -> drawRoundRect(ink, topLeft, Size(c, c), r, style = Stroke(1.5.dp.toPx()))
                }
            }
            // Each dashboard at its spot of the full cross; the removed ones leave theirs empty.
            val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
            DashboardStore.COLUMN.forEachIndexed { row, page -> if (page in cross) draw(page, centreCol, row) }
            DashboardStore.ROW.forEachIndexed { col, page -> if (page != DashboardStore.CENTER && page in cross) draw(page, col, DashboardStore.COLUMN_HOME) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            pageName(current),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
        if (wayHome) {
            Text(
                stringResource(if (homeBelow) R.string.dash_way_home_up else R.string.dash_way_home_down),
                color = DashColors.AccentInk,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** How long [PageIndicator] stays after a page change. */
internal const val PAGE_INDICATOR_MS = 2_000L


/**
 * The update's row at the top of the ⋮ menu: only while a newer build is on
 * offer, downloading or downloaded. Installing restarts the launcher, so the
 * row waits for the car to be parked.
 */
@Composable
private fun UpdateMenuRow(status: UpdateStatus, parked: Boolean, onUpdate: () -> Unit) {
    val text = when (status) {
        is UpdateStatus.Available -> stringResource(R.string.dash_update_to, status.info.versionName)
        is UpdateStatus.Ready -> stringResource(R.string.dash_update_to, status.info.versionName)
        is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, status.percent)
        else -> return
    }
    val enabled = parked && status !is UpdateStatus.Downloading
    DashMenuItem(
        text = text,
        leading = { MenuChip(Icons.Filled.SystemUpdate, DashColors.Accent, enabled) },
        highlighted = status !is UpdateStatus.Downloading,
        enabled = enabled,
        // The same line as the hint over ⋮: the dot on the button is this row.
        detail = stringResource(R.string.dash_update_dot_menu),
        onClick = onUpdate
    )
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * On the bar while something the launcher could use is still not allowed
 * (notifications, location, contacts, calendar, the OBD adapter): one pill
 * for all of them, in place of a button on every tile. Opens the setup's
 * access step. [compact] where the bar is tight: the mark alone.
 */
@Composable
internal fun SetupPill(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    val label = stringResource(R.string.setup_pill)
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .widthIn(min = DashSize.Touch)
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.7f), shape)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(20.dp))
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The whole page's text size: a menu with smaller, the percentage, bigger and back to 100 %. */
@Composable
private fun PageZoomButton(zoom: Float, compact: Boolean, onZoom: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditAction(Icons.Filled.FormatSize, stringResource(R.string.zoom_page), compact) { open = true }
        DashMenu(open, onDismiss = { open = false }) {
            ZoomStepper(zoom, onZoom)
        }
    }
}

/** Smaller, the percentage, bigger, and back to 100 % when it is not there. */
@Composable
internal fun ZoomStepper(zoom: Float, onZoom: (Float) -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onZoom(zoomStep(zoom, -1)) }, enabled = zoom > ZOOM_MIN, modifier = Modifier.size(DashSize.Touch)) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.zoom_out), tint = DashColors.TextPrimary)
        }
        Text(
            stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()),
            color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(72.dp)
        )
        IconButton(onClick = { onZoom(zoomStep(zoom, 1)) }, enabled = zoom < ZOOM_MAX, modifier = Modifier.size(DashSize.Touch)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zoom_in), tint = DashColors.TextPrimary)
        }
        if (zoom != 1f) {
            TextButton(onClick = { onZoom(1f) }) {
                Text(stringResource(R.string.zoom_reset), color = DashColors.AccentInk)
            }
        }
    }
}
