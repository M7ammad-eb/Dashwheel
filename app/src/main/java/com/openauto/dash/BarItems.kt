package com.openauto.dash

import com.openauto.dash.vehicle.rememberGauge
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.K
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/*
 * The bottom bar's readouts, chosen by the driver (Settings, Display): any of
 * the car's readings, the time, the Wi-Fi and Bluetooth, the phone, in their
 * order, drawn the Mistral way: short segment gauges under small capitals.
 * Until something is chosen the bar stays as each theme draws it.
 */

/**
 * One readout of the bar. Names are persisted: append, never rename. A
 * [spacer] is empty room rather than a readout, and the only kind the bar
 * may hold more than once.
 */
enum class BarItem(@StringRes val label: Int, val icon: ImageVector, val spacer: Boolean = false) {
    FUEL(R.string.bar_item_fuel, Icons.Filled.LocalGasStation),
    SPEED(R.string.bar_item_speed, Icons.Filled.Speed),
    REVS(R.string.bar_item_revs, Icons.Filled.Tune),
    COOLANT(R.string.bar_item_coolant, Icons.Filled.Thermostat),
    BATTERY(R.string.bar_item_battery, Icons.Filled.BatteryChargingFull),
    RANGE(R.string.bar_item_range, Icons.Filled.Route),
    CLOCK(R.string.bar_item_clock, Icons.Filled.AccessTime),
    WIFI(R.string.bar_item_wifi, Icons.Filled.Wifi),
    BLUETOOTH(R.string.bar_item_bluetooth, Icons.Filled.Bluetooth),
    PHONE(R.string.bar_item_phone, Icons.Filled.PhoneAndroid),
    OBD(R.string.bar_item_obd, Icons.Filled.Cable),
    /** The car's outside temperature sensor, else the weather's. */
    OUTSIDE(R.string.bar_item_outside, Icons.Filled.DeviceThermostat),
    /** A fixed gap, about an icon wide. */
    SPACE(R.string.bar_item_space, Icons.Filled.SpaceBar, spacer = true),
    /**
     * A gap that takes all the room the readouts leave: the bar then fills
     * edge to edge instead of sitting in the middle. Several share it evenly.
     */
    STRETCH(R.string.bar_item_stretch, Icons.Filled.SettingsEthernet, spacer = true)
}

/**
 * How much the readouts say beside their pictures: every caption, captions
 * on the gauges only (Wi-Fi and Bluetooth as bare icons), or no caption at
 * all, a small icon in front of each gauge instead. Names are persisted.
 */
enum class BarLook(@StringRes val title: Int, @StringRes val hint: Int, val icon: ImageVector) {
    LABELS(R.string.bar_look_labels, R.string.bar_look_labels_hint, Icons.Filled.Title),
    COMPACT(R.string.bar_look_compact, R.string.bar_look_compact_hint, Icons.Filled.ViewCompact),
    MINIMAL(R.string.bar_look_minimal, R.string.bar_look_minimal_hint, Icons.Filled.CropFree)
}

private val LocalBarLook = staticCompositionLocalOf { BarLook.COMPACT }

/** The driver's choice, kept in preferences; null: never chosen, the theme's own bar. */
internal object BarItems {
    private const val PREFS = "bar_items"
    private const val KEY = "items"

    /** What the settings start from the first time: the Mistral bar as it is. */
    val STARTER = listOf(BarItem.FUEL, BarItem.SPEED, BarItem.COOLANT, BarItem.BATTERY, BarItem.CLOCK, BarItem.WIFI, BarItem.BLUETOOTH, BarItem.PHONE, BarItem.OBD)

    private val _items = MutableStateFlow<List<BarItem>?>(null)
    val items: StateFlow<List<BarItem>?> = _items.asStateFlow()
    private val _look = MutableStateFlow(BarLook.COMPACT)
    val look: StateFlow<BarLook> = _look.asStateFlow()
    private var appContext: Context? = null
    private const val KEY_LOOK = "look"

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _items.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(',')?.mapNotNull { n -> BarItem.entries.firstOrNull { it.name == n } }
        _look.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOOK, null)
            ?.let { n -> BarLook.entries.firstOrNull { it.name == n } } ?: BarLook.COMPACT
    }

    /**
     * How many of the chosen readouts the bar last found room for, with the
     * list it was drawn from: Settings marks the rest as left out. Null until
     * the bar is drawn with the driver's readouts.
     */
    private val _fit = MutableStateFlow<Pair<List<BarItem>, Int>?>(null)
    val fit: StateFlow<Pair<List<BarItem>, Int>?> = _fit.asStateFlow()

    /** From the bar's measuring: [shown] of [items] fit. */
    internal fun reportFit(items: List<BarItem>, shown: Int) {
        val now = items to shown
        if (_fit.value != now) _fit.value = now
    }

    fun setLook(look: BarLook) {
        _look.value = look
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_LOOK, look.name)?.apply()
    }

    /** [list] from now on; null goes back to the theme's own bar. */
    fun set(list: List<BarItem>?) {
        _items.value = list
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            if (list == null) remove(KEY) else putString(KEY, list.joinToString(",") { it.name })
        }?.apply()
    }
}

/** The Wi-Fi and the Bluetooth for the bar: on or off, and how strong the Wi-Fi is (0..4). */
internal object Radios {
    data class Wifi(val connected: Boolean, val bars: Int)

    private val _wifi = MutableStateFlow(Wifi(false, 0))
    val wifi: StateFlow<Wifi> = _wifi.asStateFlow()
    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()
    private var started = false

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = readWifi(app, caps)
                override fun onLost(network: Network) {
                    _wifi.value = Wifi(false, 0)
                }
            })
        }
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let { readWifi(app, it) }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        _bluetoothOn.value = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                _bluetoothOn.value = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    @Suppress("DEPRECATION")
    private fun readWifi(context: Context, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            _wifi.value = Wifi(false, 0)
            return
        }
        val rssi = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo.rssi }.getOrDefault(-60)
        _wifi.value = Wifi(true, WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4))
    }
}

/**
 * The chosen readouts, left to right. When they don't all fit in the chosen
 * [BarLook], the bar tries the tighter looks first (captions on the gauges
 * only, then none), so a 1280-wide unit still shows all it was asked for;
 * only then are those that no longer fit at the end left out whole, never
 * squeezed, and Settings says which ([BarItems.fit]). Centred, unless a
 * [BarItem.STRETCH] takes the room left over ([barPositions]).
 */
@Composable
internal fun BarReadouts(items: List<BarItem>, m: TopBarModel, modifier: Modifier = Modifier) {
    val look by BarItems.look.collectAsState()
    SubcomposeLayout(modifier) { constraints ->
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth else null
        val looks = barLooksToTry(look)
        var placed: List<Placeable> = emptyList()
        var xs: List<Int> = emptyList()
        for ((n, l) in looks.withIndex()) {
            val measurables = subcompose(l) {
                CompositionLocalProvider(LocalBarLook provides l) {
                    items.forEach { item ->
                        when (item) {
                            BarItem.SPACE -> Spacer(Modifier.width(SPACE_WIDTH))
                            BarItem.STRETCH -> Spacer(Modifier.layoutId(STRETCH_ID))
                            else -> Box(Modifier.padding(horizontal = if (l == BarLook.LABELS) 10.dp else 8.dp)) { BarReadout(item, m) }
                        }
                    }
                }
            }
            val all = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            xs = barPositions(all.map { it.width }, measurables.map { it.layoutId == STRETCH_ID }, room)
            placed = all.take(xs.size)
            if (xs.size == all.size || n == looks.lastIndex) break
        }
        BarItems.reportFit(items, placed.size)
        val height = (placed.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = room ?: placed.sumOf { it.width }
        layout(width, height) {
            placed.forEachIndexed { i, p -> p.placeRelative(xs[i], (height - p.height) / 2) }
        }
    }
}

/** The looks the bar tries, in order, until its readouts all fit: the driver's [look], then each tighter one. */
internal fun barLooksToTry(look: BarLook): List<BarLook> = BarLook.entries.drop(look.ordinal)

/** A fixed [BarItem.SPACE]: about an icon's width, on the app's spacing scale. */
private val SPACE_WIDTH = DashSpace.Xl
private const val STRETCH_ID = "bar_stretch"

/**
 * Where each of the bar's pieces starts, for as many as fit: [widths] in
 * order, [stretch] marking the [BarItem.STRETCH]es (measured empty). The
 * first piece that doesn't fit ends the bar, as before. In [room]: the
 * stretches split what is left evenly, the odd pixels to the first ones; with
 * none, the lot is centred, like the Mistral cluster in the middle of its bar.
 * Unbounded ([room] null): packed from the left, the stretches empty.
 */
internal fun barPositions(widths: List<Int>, stretch: List<Boolean>, room: Int?): List<Int> {
    var left = room ?: Int.MAX_VALUE
    var fit = 0
    for (w in widths) {
        if (w > left) break
        left -= w
        fit++
    }
    val stretches = (0 until fit).count { stretch[it] }
    val extra = if (room == null) 0 else left
    var x = if (stretches == 0) extra / 2 else 0
    var seen = 0
    return (0 until fit).map { i ->
        val at = x
        x += widths[i]
        if (stretch[i]) {
            x += extra / stretches + if (seen < extra % stretches) 1 else 0
            seen++
        }
        at
    }
}

@Composable
private fun BarReadout(item: BarItem, m: TopBarModel) {
    val units = LocalUnits.current
    when (item) {
        BarItem.FUEL -> {
            // The fuel, coolant and battery readouts follow their own value, not every OBD sample.
            val fuel = rememberFuel()
            MistralSegments(stringResource(item.label), item.icon, fuel?.percent?.let { it / 100f }, fuel?.percent?.let { "$it %" }, cells = 10,
                warnBelow = 0.2f, alarmBelow = 0.1f)
        }
        BarItem.SPEED -> {
            val kmh = rememberSpeedKmh()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    kmh?.let { units.speed(it).toString() } ?: "–", color = DashColors.Accent,
                    fontFamily = DashColors.heroFamily(), fontWeight = DashColors.HeroWeight,
                    fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em, maxLines = 1
                )
                Spacer(Modifier.width(5.dp))
                Caption(units.speedUnit, Modifier.padding(bottom = 5.dp))
            }
        }
        BarItem.REVS -> {
            // Read where a readout uses it: the clock or the phone's battery doesn't follow the car's readings.
            val rpm = rememberGaugeInt(K.RPM)
            val scale = rememberRevScale()
            MistralSegments(
                stringResource(item.label), item.icon, rpm?.let { scale.fraction(it) },
                rpm?.let { String.format(Locale.getDefault(), "%,d", it) }, cells = 10, hotAbove = scale.redFrom
            )
        }
        BarItem.COOLANT -> {
            val coolantC = rememberGaugeInt(K.COOLANT_TEMP)
            MistralSegments(
                stringResource(item.label), item.icon, coolantC?.let { it / 120f },
                coolantC?.let { "${units.temp(it)}°" }, cells = 6,
                hotAbove = COOLANT_WARNING_C / 120f
            )
        }
        BarItem.BATTERY -> {
            val v = rememberGauge(K.BATTERY_VOLTS)
            MistralSegments(
                stringResource(item.label), item.icon, v?.let { ((it - 11.0) / 4.0).toFloat() },
                v?.let { String.format(Locale.getDefault(), "%.1f V", it) }, cells = 6,
                alarmBelow = (11.8f - 11f) / 4f, hotAbove = (15.0f - 11f) / 4f
            )
        }
        BarItem.RANGE -> {
            val fuel = rememberFuel()
            ValueReadout(item, fuel?.let { units.distanceText(it.rangeKm) } ?: "–")
        }
        BarItem.CLOCK -> if (!m.merged) {
            Text(m.clock, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, style = MaterialTheme.typography.titleLarge)
        }
        BarItem.WIFI -> {
            val wifi by Radios.wifi.collectAsState()
            RadioReadout(
                if (wifi.connected) Icons.Filled.Wifi else Icons.Filled.WifiOff, stringResource(item.label),
                on = wifi.connected, strength = if (wifi.connected) wifi.bars else null
            )
        }
        BarItem.BLUETOOTH -> {
            val on by Radios.bluetoothOn.collectAsState()
            val phone by UnitSignals.phone.collectAsState()
            val viaPhone by ObdBluetoothManager.viaPhone.collectAsState()
            val viaUsb by ObdBluetoothManager.viaUsb.collectAsState()
            // The adapter counts only when it is on this unit's Bluetooth.
            val linked = phone != null || (m.obdConnection == ObdConnectionState.CONNECTED && !viaPhone && !viaUsb)
            RadioReadout(
                when {
                    !on -> Icons.Filled.BluetoothDisabled
                    linked -> Icons.Filled.BluetoothConnected
                    else -> Icons.Filled.Bluetooth
                },
                stringResource(item.label), on = on, strength = null, active = linked
            )
        }
        BarItem.PHONE -> PhonePill()
        BarItem.OBD -> ObdDot(m.obdConnection, m.onConnectObd, m.onObd)
        BarItem.OUTSIDE -> ValueReadout(item, rememberOutsideTempC()?.let { units.tempTight(it) } ?: "–")
        // Empty room, drawn by BarReadouts itself.
        BarItem.SPACE, BarItem.STRETCH -> Unit
    }
}

/** A reading in words with its caption under it, or its icon in front on the minimal bar. */
@Composable
private fun ValueReadout(item: BarItem, value: String) {
    if (LocalBarLook.current == BarLook.MINIMAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LookIcon(item.icon)
            Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Caption(stringResource(item.label))
        }
    }
}

/** Segments that light up to [fraction] (null: unknown, all dark), the reading beside, a caption under. */
@Composable
private fun MistralSegments(
    label: String, icon: ImageVector, fraction: Float?, value: String?, cells: Int,
    warnBelow: Float? = null, alarmBelow: Float? = null, hotAbove: Float? = null
) {
    val f = fraction?.coerceIn(0f, 1f)
    val lit = if (f == null) 0 else kotlin.math.ceil(f * cells).toInt().coerceIn(0, cells)
    val colour = when {
        f == null -> DashColors.Accent
        alarmBelow != null && f <= alarmBelow -> DashColors.Critical
        hotAbove != null && f >= hotAbove -> DashColors.Critical
        warnBelow != null && f <= warnBelow -> DashColors.Tacho
        else -> DashColors.Accent
    }
    val cellWidth = if (cells > 6) 5.dp else 8.dp
    val minimal = LocalBarLook.current == BarLook.MINIMAL
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // No caption to say what it is: a small icon in front says it.
            if (minimal) LookIcon(icon)
            repeat(cells) { i ->
                Box(Modifier.size(width = cellWidth, height = 14.dp).clip(RoundedCornerShape(1.dp)).background(if (i < lit) colour else DashColors.CardHi))
            }
            if (value != null) {
                Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (!minimal) Caption(label)
    }
}

/** A gauge's own small icon, standing in for its caption in the minimal look. */
@Composable
private fun LookIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.padding(end = 4.dp).size(16.dp))
}

/**
 * A radio as its icon, lit when on. The Wi-Fi's strength, when known
 * ([strength], 0..4), is the icon itself: its dot and three arcs light up
 * with it ([WifiStrength]), rather than bars beside it.
 */
@Composable
private fun RadioReadout(icon: ImageVector, label: String, on: Boolean, strength: Int?, active: Boolean = on) {
    // Only the labelled look names a radio: its icon says what it is.
    val labelled = LocalBarLook.current == BarLook.LABELS
    val size = if (labelled) 18.dp else 22.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        if (strength != null) {
            WifiStrength(wifiLit(strength), DashColors.Accent, Modifier.size(size))
        } else {
            Icon(icon, contentDescription = null, tint = if (active) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(size).alpha(if (on) 1f else 0.6f))
        }
        if (labelled) Caption(label)
    }
}

/** How many of the Wi-Fi symbol's four parts light up for [bars] of 4: the dot at least, while connected. */
internal fun wifiLit(bars: Int): Int = bars.coerceIn(1, 4)

/**
 * The Wi-Fi symbol with its dot and three arcs lit in [color] up to [lit] of
 * them, from the dot out; the rest stays dim, so the shape still reads.
 */
@Composable
private fun WifiStrength(lit: Int, color: Color, modifier: Modifier = Modifier) {
    val dim = DashColors.CardHi
    Canvas(modifier) {
        val s = size.minDimension
        // The arcs' common centre, low in the box: the dot sits on it.
        val centre = Offset(size.width / 2f, size.height * 0.84f)
        drawCircle(if (lit >= 1) color else dim, s * 0.08f, centre)
        for (i in 0..2) {
            val r = s * (0.24f + 0.18f * i)
            drawArc(
                if (lit >= i + 2) color else dim, 225f, 90f, useCenter = false,
                topLeft = Offset(centre.x - r, centre.y - r), size = Size(2 * r, 2 * r),
                style = Stroke(width = s * 0.11f, cap = StrokeCap.Round)
            )
        }
    }
}

/** The OBD link as a small dot and three letters: the bar's own pill takes less room. Tapped while idle, it connects; while linked, it opens the car's codes and readings. */
@Composable
private fun ObdDot(state: ObdConnectionState, onConnect: () -> Unit, onOpen: () -> Unit) {
    val color = obdStatusColor(state)
    val label = obdStatusLabel(state)
    val tap = rememberTapFeedback()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .tourTarget(TourTarget.OBD)
            .heightIn(min = DashSize.Touch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = state.isIdle || state == ObdConnectionState.CONNECTED) { tap(); if (state == ObdConnectionState.CONNECTED) onOpen() else onConnect() }
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = label }
    ) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (state == ObdConnectionState.DISCONNECTED) DashColors.CardHi else color))
        Spacer(Modifier.height(3.dp))
        Caption(stringResource(R.string.dash_obd_short), color = if (state == ObdConnectionState.DISCONNECTED) DashColors.Muted else color)
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, color: Color = DashColors.TextSecondary) {
    Text(text.uppercase(), color = color, letterSpacing = 0.12.em, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = modifier)
}

/**
 * Settings, Display, under the one "Bottom bar" heading (with its auto-hide,
 * ThemePane.kt): the bar's readouts. Off: the theme's own bar. On: a list
 * of every readout, ticked or not, moved up and down into order. A skin draws
 * its own bar and shows none of them: under one the switch is greyed out and
 * says why, so nobody orders readouts that never appear.
 */
@Composable
internal fun BarItemsSetting() {
    val chosen by BarItems.items.collectAsState()
    if (DashColors.Skin != DashSkin.STANDARD) {
        SettingsToggle(
            Icons.Filled.Tune, stringResource(R.string.bar_items_custom), stringResource(R.string.bar_items_skin),
            checked = chosen != null, enabled = false
        ) {}
        return
    }
    // The same row as every other setting of the pane, icon and all.
    SettingsToggle(
        Icons.Filled.Tune, stringResource(R.string.bar_items_custom),
        stringResource(if (chosen == null) R.string.bar_items_theme else R.string.bar_items_custom_detail),
        checked = chosen != null
    ) { on -> BarItems.set(if (on) BarItems.STARTER else null) }
    val list = chosen ?: return
    val look by BarItems.look.collectAsState()
    SegmentedSwitch(
        options = BarLook.entries,
        chosen = look,
        icon = { it.icon },
        title = { stringResource(it.title) },
        onChoose = { BarItems.setLook(it) }
    )
    SwitchHint(stringResource(look.hint))
    Spacer(Modifier.height(8.dp))
    // Chosen ones first, in their order, then the rest. The spacers are
    // always among the rest as well: each tap there adds one more.
    val all = list + BarItem.entries.filter { it !in list || it.spacer }
    // The readouts the bar had no room for, even without captions: from this index on.
    val fit by BarItems.fit.collectAsState()
    val shown = fit?.takeIf { it.first == list }?.second ?: list.size
    if (shown < list.size) SwitchHint(stringResource(R.string.bar_items_no_room))
    all.forEachIndexed { i, item ->
        val on = i < list.size
        val leftOut = on && i >= shown && !item.spacer
        val tap = rememberTapFeedback()
        // The whole row is the switch: a tap anywhere on the name puts it in the bar or takes it out
        // (this one: a spacer that is in more than once leaves only here).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .heightIn(min = DashSize.Touch)
                .clip(DashShape.Medium)
                .background(if (on) DashColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = Role.Switch) { tap(); BarItems.set(if (on) list.filterIndexed { j, _ -> j != i } else list + item) }
                .semantics { selected = on }
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null, tint = if (on) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(item.label), color = if (on) DashColors.TextPrimary else DashColors.Muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (leftOut) {
                    Text(stringResource(R.string.bar_items_left_out), color = DashColors.Warning, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!on && item.spacer) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.padding(end = 14.dp).size(22.dp))
            }
            if (on) {
                IconButton(onClick = { BarItems.set(list.moved(i, -1)) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.bar_items_left), tint = DashColors.TextSecondary)
                }
                IconButton(onClick = { BarItems.set(list.moved(i, 1)) }, enabled = i < list.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.bar_items_right), tint = DashColors.TextSecondary)
                }
            }
        }
    }
    TextButton(onClick = { BarItems.set(BarItems.STARTER) }) {
        Text(stringResource(R.string.bar_items_reset), color = DashColors.AccentInk)
    }
}

/** [this] with the item at [i] moved [by] places. */
private fun List<BarItem>.moved(i: Int, by: Int): List<BarItem> {
    val j = (i + by).coerceIn(0, size - 1)
    if (i == j) return this
    return toMutableList().also { val x = it.removeAt(i); it.add(j, x) }
}
