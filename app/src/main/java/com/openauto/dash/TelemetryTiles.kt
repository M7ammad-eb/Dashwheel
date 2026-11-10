@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import com.openauto.dash.vehicle.rememberDisplaySpeedKmh
import com.openauto.dash.vehicle.rememberFuelInfo
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberGauges
import com.openauto.dash.vehicle.K
import android.Manifest
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate

/*
 * OBD tiles: hero speed gauge, rev bar, meter chips, fault codes, all-data, fuel & range.
 */

internal const val SPEED_WARNING_KMH = 110

/**
 * Whether [kmh] reads as too fast, the same in every look: over the road's
 * limit (by more than [SpeedLimit.TOLERANCE_KMH]) while a speed-limit reader
 * runs and knows it, else from [SPEED_WARNING_KMH].
 */
@Composable
internal fun speedOver(kmh: Int?): Boolean {
    val limit by SpeedLimit.limit.collectAsState()
    return isSpeedOver(kmh, limit)
}

internal fun isSpeedOver(kmh: Int?, limit: Int?): Boolean = when {
    kmh == null -> false
    limit != null -> kmh > limit + SpeedLimit.TOLERANCE_KMH
    else -> kmh >= SPEED_WARNING_KMH
}

/** Battery bar: 11 V empty to 15 V full; healthy between 12 and 15 V, critical under 11.5 V or over 15.5 V. */
internal fun batteryFraction(voltage: Double): Float = ((voltage - 11.0) / 4.0).toFloat()
internal fun batteryColor(voltage: Double): Color = when {
    voltage in BATTERY_OK_V -> DashColors.Good
    voltage in BATTERY_WARNING_V -> DashColors.Warning
    else -> DashColors.Critical
}
internal val BATTERY_OK_V = 12.0..15.0
internal val BATTERY_WARNING_V = 11.5..15.5

/** The "connect first" body shared by the OBD cards. */
@Composable
internal fun ObdNotConnected(connection: ObdConnectionState, onConnect: () -> Unit, onPickDevice: (() -> Unit)? = null) {
    val connecting = connection == ObdConnectionState.CONNECTING
    val lastError by ObdBluetoothManager.lastError.collectAsState()
    val connectStep by ObdBluetoothManager.connectStep.collectAsState()
    // Looked up again on every state change: the name appears once the adapter is paired.
    val offered by PhoneObd.offer.collectAsState()
    val info by ObdBluetoothManager.adapter.collectAsState()
    // Through the phone: the adapter is the one its companion holds. Looked up off the main thread (AdapterInfo).
    val adapter = if (DeepObdSource.owns()) "Deep OBD" else if (info.phone) offered else info.name
    if (connecting) {
        Text(stringResource(R.string.vehicle_obd_connecting_to, adapter ?: "OBD"), color = DashColors.AccentInk)
        // A long step (Android's PIN dialog, a Bluetooth restart): say what is going on.
        connectStep?.let { Text(stringResource(it), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
    } else {
        Text(stringResource(R.string.vehicle_obd_not_connected), color = DashColors.Muted)
        // What the last attempt ran into, so a silent adapter is not a mystery.
        lastError?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
        val phoneBlocking by ObdBluetoothManager.phoneBlocking.collectAsState()
        if (phoneBlocking) {
            var help by remember { mutableStateOf(false) }
            TextButton(onClick = { help = true }) {
                Text(stringResource(R.string.vehicle_phone_aa_fix), color = DashColors.AccentInk)
            }
            if (help) PhoneAaHelpDialog(onConnect = { help = false; onConnect() }, onDismiss = { help = false })
        }
        adapter?.let { Text(stringResource(R.string.vehicle_obd_adapter, it), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall) }
    }
    Spacer(Modifier.height(10.dp))
    // Only the page's first vehicle tile carries the button; the others point at the bar.
    if (!LocalObdPrompt.current) {
        if (!connecting) Text(stringResource(R.string.vehicle_obd_connect_from_bar), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onConnect,
            enabled = !connecting,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent)
        ) { Text(if (connecting) "\u2026" else stringResource(R.string.vehicle_connect)) }
        if (onPickDevice != null) {
            TextButton(onClick = onPickDevice, enabled = !connecting) {
                Text(stringResource(R.string.vehicle_choose_adapter), color = DashColors.AccentInk)
            }
        }
    }
}

/**
 * How to stop the phone's Android Auto from holding the unit's Bluetooth
 * ([ObdBluetoothManager.phoneBlocking]): the steps on the phone, then a
 * fresh start of the unit's Bluetooth, which stays stuck until then.
 */
@Composable
internal fun PhoneAaHelpDialog(onConnect: () -> Unit, onDismiss: () -> Unit) {
    val steps = listOf(R.string.vehicle_phone_aa_step1, R.string.vehicle_phone_aa_step2, R.string.vehicle_phone_aa_step3)
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.vehicle_phone_aa_title), color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.vehicle_phone_aa_body), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(step), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.vehicle_phone_aa_note), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect) { Text(stringResource(R.string.vehicle_connect), color = DashColors.AccentInk) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
        }
    )
}

@Composable
internal fun ObdCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (DashColors.Original) {
        OriginalObdCard(connection, onConnect, onPickDevice, modifier)
        return
    }
    val connected = connection == ObdConnectionState.CONNECTED
    var speedFix by remember { mutableStateOf(false) }
    // What the tile shows: the speed as the bar shows it, and the engine's gauges; null = not answered ("--").
    val speed = rememberDisplaySpeedKmh()
    val g = rememberGauges(K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            // Short tiles drop the secondary chips; tall tiles stack the RPM bar
            // and chips under the gauge instead of beside it.
            val compact = maxHeight < 250.dp
            val stacked = maxWidth < maxHeight * 1.15f

            Column(modifier = Modifier.fillMaxSize()) {
                // Header: title + live connection status / connect button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.vehicle_telemetry_title),
                        color = DashColors.AccentInk,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // The speed correction, to match the car's speedometer: it
                    // can only be compared while the car moves, so it opens then too.
                    IconButton(onClick = { speedFix = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.vehicle_speed_fix), tint = DashColors.Muted, modifier = Modifier.size(20.dp))
                    }
                    if (connected) {
                        TextButton(
                            onClick = onPickDevice,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DashColors.Good)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.vehicle_live), color = DashColors.Good, style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (LocalObdPrompt.current) {
                        Button(
                            onClick = onConnect,
                            enabled = connection != ObdConnectionState.CONNECTING,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DashColors.Accent,
                                contentColor = DashColors.OnAccent
                            ),
                            shape = DashShape.Medium,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (connection == ObdConnectionState.CONNECTING) "\u2026" else stringResource(R.string.vehicle_connect))
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                val gauge: @Composable (Modifier) -> Unit = { m ->
                    val u = LocalUnits.current
                    // 0-220 km/h labelled every 20, or 0-140 mph the same way.
                    val top = u.speedScale(220)
                    AnalogGauge(
                        value = speed?.let { u.speed(it).toFloat() } ?: 0f,
                        maxValue = top.toFloat(),
                        valueText = speed?.let { u.speed(it).toString() } ?: "--",
                        label = stringResource(R.string.vehicle_speed_caps),
                        unit = u.speedUnit,
                        accent = DashColors.Accent,
                        redlineAccent = DashColors.Critical,
                        redlineFraction = u.speed(SPEED_WARNING_KMH) / top.toFloat(),
                        dimmed = speed == null,
                        majorTicks = top / 20 + 1,
                        hero = true,
                        modifier = m
                    )
                }
                val rpm: @Composable (Modifier) -> Unit = { m ->
                    RpmBar(rpm = g.rpm ?: 0, maxRpm = 7000f, dimmed = g.rpm == null, modifier = m)
                }
                val chips: @Composable (Modifier) -> Unit = { m ->
                    MeterChip(
                        label = stringResource(R.string.vehicle_coolant),
                        valueText = g.coolantTempC?.let { "${LocalUnits.current.temp(it)}°" } ?: "--",
                        fraction = ((g.coolantTempC ?: 0) / 120f),
                        color = coolantColor(g.coolantTempC ?: 0),
                        dimmed = g.coolantTempC == null,
                        modifier = m
                    )
                    MeterChip(
                        label = stringResource(R.string.vehicle_load),
                        valueText = g.engineLoadPct?.let { "$it%" } ?: "--",
                        fraction = (g.engineLoadPct ?: 0) / 100f,
                        color = DashColors.Accent,
                        dimmed = g.engineLoadPct == null,
                        modifier = m
                    )
                    MeterChip(
                        label = stringResource(R.string.vehicle_battery),
                        valueText = g.voltage?.let { "%.1fV".format(it) } ?: "--",
                        fraction = batteryFraction(g.voltage ?: 0.0),
                        color = batteryColor(g.voltage ?: 0.0),
                        dimmed = g.voltage == null,
                        modifier = m
                    )
                }

                if (stacked) {
                    gauge(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    rpm(Modifier.fillMaxWidth())
                    if (!compact) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { chips(Modifier.weight(1f)) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        gauge(Modifier.weight(1.25f).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        ) {
                            rpm(Modifier.fillMaxWidth())
                            if (!compact) chips(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    if (speedFix) SpeedCorrectionDialog(speed, onDismiss = { speedFix = false })
}

/**
 * The speed correction from the telemetry tile, with the corrected speed live
 * above it: nudge it until it reads what the car's speedometer says. The
 * one dialog the drive lock lets through: two large buttons and a number, and
 * the only time the two speeds can be compared is on the move.
 */
@Composable
internal fun SpeedCorrectionDialog(speedKmh: Int?, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.vehicle_speed_fix), color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    speedKmh?.let {
                        val u = LocalUnits.current
                        stringResource(if (u.imperial) R.string.units_speed_fix_now_mph else R.string.vehicle_speed_fix_now, u.speed(it))
                    } ?: stringResource(R.string.vehicle_speed_fix_not_connected),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                SpeedCorrectionRow()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.AccentInk) } }
    )
}

/**
 * Horizontal rev bar: green through the accent into red at the redline, with a
 * glow underlay on glowing themes and a marker at the redline.
 */
@Composable
internal fun RpmBar(
    rpm: Int,
    maxRpm: Float,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    redlineFraction: Float = 0.82f
) {
    // The animated fill is read only while drawing, so the 400 ms slide redraws
    // the bar without recomposing the tile each frame; the readout's colour only
    // follows it across the redline.
    val frac = animateFloatAsState(
        targetValue = if (dimmed) 0f else (rpm / maxRpm).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "rpmBar"
    )
    val good = DashColors.Good
    val accent = DashColors.Accent
    val warning = DashColors.Critical
    val glow = DashColors.Glow
    // Bare themes have no card behind the bar, so a background-coloured track would vanish.
    val track = if (DashColors.Glass) DashColors.well(0.35f) else if (DashColors.Bare) DashColors.CardHi else DashColors.Background
    val overRedline by remember(redlineFraction) { derivedStateOf { frac.value >= redlineFraction } }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.vehicle_rpm_caps), color = DashColors.TextSecondary, letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall)
            Text(
                text = if (dimmed) "--" else rpm.toString(),
                color = if (dimmed) DashColors.Muted else if (overRedline) warning else DashColors.Tacho,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawWithCache {
                    val h = size.height
                    val corner = CornerRadius(h / 2f)
                    val fill = Brush.horizontalGradient(listOf(good, accent, warning), endX = size.width)
                    val rx = size.width * redlineFraction
                    onDrawBehind {
                        drawRoundRect(color = track, size = size, cornerRadius = corner)
                        val w = size.width * frac.value
                        if (w > 0f) {
                            if (glow > 0f) {
                                drawRoundRect(
                                    brush = fill,
                                    topLeft = Offset(0f, -h * 0.6f),
                                    size = Size(w, h * 2.2f),
                                    cornerRadius = CornerRadius(h),
                                    alpha = 0.30f * glow
                                )
                            }
                            drawRoundRect(brush = fill, size = Size(w, h), cornerRadius = corner)
                        }
                        // Redline marker.
                        drawLine(
                            color = warning.copy(alpha = 0.7f),
                            start = Offset(rx, -2f),
                            end = Offset(rx, h + 2f),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
        )
    }
}

/** Coolant: cold is accent (still warming up), then good, amber and red at the bar's limits for this engine. */
internal fun coolantColor(tempC: Int): Color = when {
    tempC >= COOLANT_CRITICAL_C -> DashColors.Critical
    tempC >= COOLANT_WARNING_C -> DashColors.Warning
    tempC >= 75 -> DashColors.Good
    else -> DashColors.Accent
}
/** The bar's coolant limits for the driver's engine ([coolantWarningC]): 105 and 110 °C for one that runs at 90. */
internal val COOLANT_WARNING_C: Int get() = coolantWarningC(CarProfileStore.current.hotC)
internal val COOLANT_CRITICAL_C: Int get() = coolantCriticalC(CarProfileStore.current.hotC)

/**
 * A racing-style analog gauge: a 270° dark dial with tick marks, a coloured
 * sweep arc (turning red past [redlineFraction]), an animated needle and a big
 * digital readout in the middle. Scales to whatever size the tile gives it.
 */
@Composable
internal fun AnalogGauge(
    value: Float,
    maxValue: Float,
    valueText: String,
    label: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    redlineAccent: Color = DashColors.Critical,
    redlineFraction: Float = 0.8f,
    dimmed: Boolean = false,
    majorTicks: Int = 9,
    // Hero style (telemetry speed): tick labels, glowing tip dot instead of a
    // needle, and large gradient numerals - the mockup's instrument cluster.
    hero: Boolean = false
) {
    if (DashColors.Original) {
        OriginalAnalogGauge(
            value, maxValue, valueText, label, unit, accent, modifier,
            redlineAccent, redlineFraction, dimmed, majorTicks
        )
        return
    }
    val target = (value / maxValue).coerceIn(0f, 1f)
    // Read only inside the draw below: the 500 ms needle swing redraws the dial
    // without recomposing the gauge (and its numerals) every frame.
    val sweep = animateFloatAsState(
        targetValue = if (dimmed) 0f else target,
        animationSpec = tween(durationMillis = 500),
        label = "gauge"
    )
    val startAngle = 135f      // 7:30 position (Compose: 0° = 3 o'clock, CW positive)
    val sweepTotal = 270f
    val muted = DashColors.Muted
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent2 = DashColors.Accent2
    val trackColor = if (glass) DashColors.haze(0.07f) else DashColors.CardHi
    val tickColor = DashColors.TextSecondary
    val hubColor = DashColors.Card

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugePx = min(maxWidth.value, maxHeight.value)
        val valueSize = if (hero) (gaugePx * 0.30f).coerceIn(22f, 76f).sp else (gaugePx * 0.20f).coerceIn(16f, 46f).sp
        val unitSize = (gaugePx * 0.075f).coerceIn(MIN_TEXT_DP, 16f).sp
        val labelSize = (gaugePx * 0.085f).coerceIn(MIN_TEXT_DP, 17f).sp
        val tickLabelSize = (gaugePx * 0.055f).coerceIn(11f, 14f).sp
        val textMeasurer = rememberTextMeasurer()
        val tickLabelColor = DashColors.TextSecondary.copy(alpha = 0.85f)
        // The hero's tick labels ("0" ... "220"), measured once per size and style
        // rather than a dozen times on every frame of the needle's swing.
        val tickCount = (majorTicks - 1) * (if (hero) 4 else 1)
        val tickLabels = remember(textMeasurer, hero, maxValue, tickCount, tickLabelSize, tickLabelColor) {
            if (!hero) emptyMap() else (0..tickCount step 4).associateWith { i ->
                textMeasurer.measure(
                    (maxValue * i / tickCount).roundToInt().toString(),
                    style = TextStyle(fontSize = tickLabelSize, fontWeight = FontWeight.SemiBold, color = tickLabelColor)
                )
            }
        }

        // Its own layer: the needle's swing redraws the dial, not the numerals beside it.
        Spacer(modifier = Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val stroke = size.minDimension * 0.085f
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val line = Stroke(width = stroke, cap = StrokeCap.Round)
            // The sweep's three weights, built with the geometry rather than on every draw.
            val halo = Stroke(width = stroke * 3.2f, cap = StrokeCap.Round)
            val wide = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
            val core = Stroke(width = stroke * 0.22f, cap = StrokeCap.Round)
            val gradient = gaugeSweepBrush(center, accent, accent2)
            val redline = SolidColor(redlineAccent)
            val tipGlow = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * glow), Color.Transparent), center = Offset.Zero, radius = stroke * 1.6f)
            onDrawBehind {
                val frac = sweep.value
                val sweepColor = if (frac >= redlineFraction) redlineAccent else accent
                val needleColor = if (dimmed) muted else sweepColor

                // Base track.
                drawArc(
                    color = trackColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = line
                )
                // Dim redline zone on the track.
                drawArc(
                    color = redlineAccent.copy(alpha = 0.35f),
                    startAngle = startAngle + sweepTotal * redlineFraction,
                    sweepAngle = sweepTotal * (1f - redlineFraction),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = line
                )
                // Active sweep: accent->accent2 gradient along the arc, a wide soft
                // halo underneath (scaled by the theme's glow) and a bright core line.
                if (frac > 0f) {
                    val sweepBrush: Brush = if (frac >= redlineFraction) redline else gradient
                    if (glow > 0f) {
                        drawArc(
                            brush = sweepBrush,
                            startAngle = startAngle,
                            sweepAngle = sweepTotal * frac,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            alpha = 0.14f * glow,
                            style = halo
                        )
                    }
                    drawArc(
                        brush = sweepBrush,
                        startAngle = startAngle,
                        sweepAngle = sweepTotal * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        alpha = 0.25f + 0.15f * glow,
                        style = wide
                    )
                    drawArc(
                        brush = sweepBrush,
                        startAngle = startAngle,
                        sweepAngle = sweepTotal * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = line
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.35f + 0.3f * glow),
                        startAngle = startAngle,
                        sweepAngle = sweepTotal * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = core
                    )
                }
                // Tick marks (hero adds minor ticks between the majors, plus labels).
                val tickOuter = radius - stroke * 0.6f
                val tickInner = radius - stroke * 1.5f
                val minorPerMajor = if (hero) 4 else 1
                for (i in 0..tickCount) {
                    val major = i % minorPerMajor == 0
                    val a = Math.toRadians((startAngle + sweepTotal * i / tickCount).toDouble())
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    val inner = if (major) tickInner else tickInner + (tickOuter - tickInner) * 0.45f
                    drawLine(
                        color = tickColor.copy(alpha = if (major) 0.6f else 0.25f),
                        start = Offset(center.x + ca * inner, center.y + sa * inner),
                        end = Offset(center.x + ca * tickOuter, center.y + sa * tickOuter),
                        strokeWidth = stroke * if (major) 0.16f else 0.09f,
                        cap = StrokeCap.Round
                    )
                    // Tile-sized gauges label every other major and skip the two end
                    // labels, which would collide with the unit text below the numerals.
                    val majorIndex = i / minorPerMajor
                    val showLabel = hero && major && (
                        gaugePx >= 300f || (majorIndex % 2 == 0 && i != 0 && i != tickCount)
                    )
                    val layout = if (showLabel) tickLabels[i] else null
                    if (layout != null) {
                        val lr = tickInner - stroke * 0.55f - maxOf(layout.size.width, layout.size.height) * 0.5f
                        drawText(
                            layout,
                            topLeft = Offset(
                                center.x + ca * lr - layout.size.width / 2f,
                                center.y + sa * lr - layout.size.height / 2f
                            )
                        )
                    }
                }
                if (hero) {
                    // Glowing tip dot at the end of the sweep.
                    if (!dimmed) {
                        val tipA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                        val tip = Offset(center.x + cos(tipA).toFloat() * radius, center.y + sin(tipA).toFloat() * radius)
                        if (glow > 0f) translate(tip.x, tip.y) { drawCircle(brush = tipGlow, radius = stroke * 1.6f, center = Offset.Zero) }
                        drawCircle(color = Color.White, radius = stroke * 0.42f, center = tip)
                    }
                } else {
                    // Needle + hub.
                    val needleA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                    val nx = cos(needleA).toFloat()
                    val ny = sin(needleA).toFloat()
                    val needleLen = radius - stroke * 0.4f
                    drawLine(
                        color = needleColor,
                        start = Offset(center.x - nx * radius * 0.12f, center.y - ny * radius * 0.12f),
                        end = Offset(center.x + nx * needleLen, center.y + ny * needleLen),
                        strokeWidth = stroke * 0.35f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = hubColor, radius = stroke * 0.9f, center = center)
                    drawCircle(color = needleColor, radius = stroke * 0.5f, center = center)
                }
            }
        })

        // Digital readout in the middle.
        val lit = glow > 0f && !dimmed
        // Hero numerals fade from white into the accent, like the mockup.
        val numeralBrush: Brush? = if (hero && lit) {
            Brush.verticalGradient(listOf(DashColors.Bright, lerp(DashColors.Bright, accent, 0.45f)))
        } else null
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = gaugePx.times(if (hero) 0.02f else 0.10f).dp)
        ) {
            Text(
                text = valueText,
                color = if (numeralBrush != null) Color.Unspecified else if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontSize = valueSize,
                fontWeight = if (hero) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = if (hero) (-0.06).em else (-0.04).em,
                maxLines = 1,
                // Numerals glow in the accent colour on glowing themes.
                style = TextStyle(
                    brush = numeralBrush,
                    shadow = if (lit) {
                        Shadow(color = accent.copy(alpha = 0.85f * glow), blurRadius = valueSize.value * 0.8f)
                    } else null
                )
            )
            // Inside the dial's opening, one line each: a small gauge drops its unit line
            // (the source, "approx.") rather than spill it over the arc.
            val inner = Modifier.widthIn(max = (gaugePx * 0.66f).dp)
            if (hero || gaugePx >= 150f) {
                Text(
                    text = if (hero) unit.uppercase() else unit,
                    color = if (hero) DashColors.TextSecondary else DashColors.Muted,
                    fontSize = unitSize,
                    fontWeight = if (hero) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = if (hero) 0.3.em else 0.15.em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = inner
                )
            }
            if (!hero) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label, color = accent, fontSize = labelSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = inner
                )
            }
        }
    }
}

/** Small labelled meter: value on top, a rounded progress track below. */
@Composable
internal fun MeterChip(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    if (DashColors.Original) {
        OriginalMeterChip(label, valueText, fraction, color, dimmed, modifier)
        return
    }
    val glass = DashColors.Glass
    val chipShape = DashShape.Small
    Column(
        modifier = modifier
            .clip(chipShape)
            .itemFill(if (glass) DashColors.haze(0.06f) else DashColors.CardHi, chipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(if (glass) DashColors.well(0.35f) else if (DashColors.Bare) DashColors.CardHi else DashColors.Background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (dimmed) 0f else fraction01(fraction))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.3f))))
            )
        }
    }
}

/** Shows every OBD value currently available. */
@Composable
internal fun ObdAllCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onPickDevice: (() -> Unit)? = null
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.vehicle_obd_data_title), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            if (!connected) {
                ObdNotConnected(connection, onConnect, onPickDevice)
            } else {
                val u = LocalUnits.current
                // Every reading the car has answered; the ones it hasn't are left out, not shown as 0.
                val g = rememberGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.INTAKE_TEMP, K.THROTTLE, K.ENGINE_LOAD, K.FUEL_LEVEL, K.BATTERY_VOLTS)
                g.speedKmh?.let {
                    MeterChip(stringResource(R.string.vehicle_speed), u.speedText(it), it / 220f, DashColors.Accent, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.rpm?.let {
                    MeterChip(stringResource(R.string.vehicle_rpm), "$it", rememberRevScale().fraction(it), DashColors.Tacho, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.coolantTempC?.let {
                    MeterChip(stringResource(R.string.vehicle_coolant), u.tempText(it), it / 120f, coolantColor(it), false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.intakeTempC?.let {
                    MeterChip(stringResource(R.string.vehicle_intake_air), u.tempText(it), it / 80f, DashColors.Accent, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.throttlePct?.let {
                    MeterChip(stringResource(R.string.vehicle_throttle), "$it %", it / 100f, DashColors.Accent, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.engineLoadPct?.let {
                    MeterChip(stringResource(R.string.vehicle_engine_load), "$it %", it / 100f, DashColors.Tacho, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.fuelLevelPct?.let {
                    MeterChip(stringResource(R.string.vehicle_fuel_level), "$it %", it / 100f, DashColors.Good, false, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                g.voltage?.let {
                    MeterChip(stringResource(R.string.vehicle_battery), "%.1f V".format(it), batteryFraction(it), batteryColor(it), false, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Fuel & range as a radial gauge. Fuel level comes from the **CANbox** (learned
 * via the finder) when available — the C4 Picasso's OBD doesn't report it — and
 * falls back to the OBD fuel PID if that ever works. Range is the car's own
 * trip-computer figure once its CANbox word is learned, else an estimate from
 * the tank size and average consumption.
 */
@Composable
internal fun RangeCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The CANbox stream (needs root) is the real fuel source; keep it running
    // while this card is on screen so the learned signals decode live.
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val canFuel by McuReader.fuelPercent.collectAsState()
    val canRange by McuReader.rangeKm.collectAsState()
    val obdConnected = connection == ObdConnectionState.CONNECTED
    val obdFuel = rememberGaugeInt(K.FUEL_LEVEL) ?: 0
    val fuel = rememberFuelInfo()
    val fromCan = canFuel != null || canRange != null
    val source = if (fromCan) stringResource(R.string.vehicle_via_canbox)
        else if (obdFuel > 0) stringResource(R.string.vehicle_via_obd) else null

    var showFinder by remember { mutableStateOf(false) }
    var showRangeFinder by remember { mutableStateOf(false) }
    val lock = LocalDriveLock.current
    // The finders read the CANbox stream through root: without it they have
    // nothing to list, so only the OBD is offered (see PrivilegedShell).
    val canbox = shellAccess().root

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(DashSpace.Lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TitleWithTrailing(
                Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.vehicle_fuel_range_title), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) },
                trailing = {
                    source?.let {
                        Text(it, color = if (fromCan) DashColors.Good else DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                },
                dropWhenTight = true
            )

            if (fuel == null) {
                // Nothing yet: explain and offer the finders / OBD connect.
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Muted, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (!canbox) R.string.vehicle_fuel_none else if (McuReader.learningNeedsRoot) R.string.vehicle_fuel_no_obd else R.string.vehicle_fuel_no_obd_bus),
                    color = DashColors.Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                if (canbox) {
                    // Reading the range is instant (the trip computer shows it); the
                    // fuel byte takes days of driving to learn, so range comes first.
                    Button(
                        onClick = { lock.whenParked { showRangeFinder = true } },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent)
                    ) {
                        Icon(Icons.Filled.Speed, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vehicle_find_range_signal))
                    }
                    TextButton(onClick = { lock.whenParked { showFinder = true } }) {
                        Text(stringResource(R.string.vehicle_find_fuel_signal), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!obdConnected && LocalObdPrompt.current) {
                    TextButton(onClick = onConnect) {
                        Text(stringResource(R.string.vehicle_try_obd_fuel_pid), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                val fuelPct = fuel.percent
                val approx = if (fuel.percentEstimated) "≈ " else ""
                val u = LocalUnits.current

                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnalogGauge(
                        value = fuelPct.toFloat(),
                        maxValue = 100f,
                        valueText = "${u.distance(fuel.rangeKm)}",
                        label = stringResource(if (u.imperial) R.string.units_mi_to_empty else R.string.vehicle_km_to_empty),
                        unit = stringResource(if (fuel.rangeFromCar) R.string.vehicle_range_from_car else R.string.vehicle_range_approx),
                        accent = if (fuelPct <= 12) DashColors.Warning else DashColors.Good,
                        // No redline band on fuel (more fill = more fuel); the whole
                        // sweep just turns amber when the tank drops into reserve.
                        redlineFraction = 1f,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeterChip(stringResource(R.string.vehicle_fuel), "$approx$fuelPct%", fuelPct / 100f, if (fuelPct <= 12) DashColors.Warning else DashColors.Good, false, Modifier.weight(1f))
                    MeterChip(stringResource(R.string.vehicle_in_tank), approx + u.volumeText(fuel.liters), (fuel.liters / fuel.tankL).toFloat(), DashColors.Accent, false, Modifier.weight(1f))
                    MeterChip(stringResource(R.string.vehicle_avg_use), u.economy(fuel.avgUse)?.let { "%.1f".format(it) } ?: "--", (fuel.avgUse / (2 * CarProfileStore.current.typicalUse)).toFloat().coerceIn(0f, 1f), DashColors.Accent, false, Modifier.weight(1f))
                }
                // Always reachable under root, so a learned signal can be recalibrated or forgotten.
                if (canbox) Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { lock.whenParked { showFinder = true } }) {
                        Text(
                            stringResource(if (canFuel == null) R.string.vehicle_learn_fuel else R.string.vehicle_recalibrate_fuel),
                            color = DashColors.Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = { lock.whenParked { showRangeFinder = true } }) {
                        Text(
                            stringResource(if (canRange == null) R.string.vehicle_learn_range else R.string.vehicle_change_range),
                            color = DashColors.Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    if (showFinder) {
        FuelFinderDialog(onDismiss = { showFinder = false })
    }
    if (showRangeFinder) {
        RangeFinderDialog(onDismiss = { showRangeFinder = false })
    }
}

/**
 * Learns which CANbox/MCU word is the car's distance to empty. The head unit's
 * trip computer already shows it, so the user just types that number and we
 * list every 16-bit word holding it, each with its live value — the right one
 * keeps matching the trip computer as the range changes.
 */
@Composable
internal fun RangeFinderDialog(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    // The MCU stream, or the values of another family's bus (FYT).
    val entries by McuReader.monitorEntries.collectAsState()
    var typed by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<RangeMapping>?>(null) }

    fun search() {
        val km = typed.trim().substringBefore('.').substringBefore(',').toIntOrNull() ?: return
        candidates = RangeMapping.candidates(entries.associate { it.key to it.bytes }, km)
    }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.vehicle_find_range_signal), color = DashColors.TextPrimary) },
        text = {
            Column {
                Text(
                    stringResource(R.string.vehicle_range_finder_help),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { v -> typed = v.filter { it.isDigit() || it == '.' || it == ',' }.take(7); candidates = null },
                        label = { Text(stringResource(R.string.vehicle_range_car_shows)) },
                        suffix = { Text("km") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DashColors.TextPrimary,
                            unfocusedTextColor = DashColors.TextPrimary,
                            focusedBorderColor = DashColors.Accent,
                            unfocusedBorderColor = DashColors.Line,
                            focusedLabelColor = DashColors.Accent,
                            unfocusedLabelColor = DashColors.Muted,
                            cursorColor = DashColors.Accent
                        )
                    )
                    Button(
                        onClick = ::search,
                        enabled = entries.isNotEmpty() && typed.any { it.isDigit() },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent)
                    ) { Text(stringResource(R.string.vehicle_range_search)) }
                }
                Spacer(Modifier.height(8.dp))
                val found = candidates
                when {
                    entries.isEmpty() ->
                        Text(stringResource(McuReader.waitingRes), color = DashColors.Muted)
                    found == null -> Unit
                    found.isEmpty() ->
                        Text(stringResource(R.string.vehicle_range_none), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(stringResource(R.string.vehicle_range_candidates), color = DashColors.AccentInk, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val live = entries.associate { it.key to it.bytes }
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(210.dp)) {
                            lazyColumnItems(found, key = { "${it.key}#${it.index}#${it.bigEndian}#${it.scale}" }) { c ->
                                val now = live[c.key]?.let { c.decode(it) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DashColors.CardHi)
                                        .clickable {
                                            McuReader.saveRangeMapping(c)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(stringResource(R.string.vehicle_range_frame, c.key, c.index, c.index + 1), color = DashColors.TextPrimary, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            (if (c.bigEndian) "BE" else "LE") + (if (c.scale == 10) " · 0.1 km" else ""),
                                            color = DashColors.Muted,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        now?.let { "$it km" } ?: "—",
                                        color = DashColors.AccentInk,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (McuReader.rangeConfigured) {
                TextButton(onClick = { McuReader.clearRangeMapping(); onDismiss() }) {
                    Text(stringResource(R.string.vehicle_forget_current), color = DashColors.Warning)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.vehicle_close), color = DashColors.Muted) }
        }
    )
}

/**
 * Learns which CANbox/MCU byte is the fuel level with a **two-point capture**
 * (like the door A/B differential). A single snapshot is unreliable — many bytes
 * happen to read ~60% at one moment, including static ones — so instead the user
 * captures the frames at one fuel level, waits until the gauge has visibly
 * changed, captures again, and we only offer bytes that actually **moved in the
 * same direction** as the fuel and map consistently to the same full tank. That
 * excludes the static byte that got picked before and stayed at 60%.
 */
@Composable
internal fun FuelFinderDialog(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    // The MCU stream, or the values of another family's bus (FYT).
    val entries by McuReader.monitorEntries.collectAsState()
    // Capture A is restored from storage, so you can tap Capture A, close this,
    // drive for days, then reopen and Capture B against the same A.
    val savedA = remember { McuReader.loadFuelCaptureA() }
    var currentPct by remember { mutableIntStateOf(savedA?.first ?: 60) }
    var capA by remember { mutableStateOf(savedA?.second) }
    var pctA by remember { mutableIntStateOf(savedA?.first ?: 0) }
    var capB by remember { mutableStateOf<Map<String, List<Int>>?>(null) }
    var pctB by remember { mutableIntStateOf(0) }

    fun snapshot(): Map<String, List<Int>> = entries.associate { it.key to it.bytes }

    // Bytes that changed in the same direction as the fuel and map to a
    // consistent full-tank raw across both captures. err = disagreement between
    // the two implied full-tank values (lower = better fit).
    data class Cand(val key: String, val index: Int, val rawA: Int, val rawB: Int, val fullRaw: Int, val err: Int)
    val candidates = remember(capA, capB, pctA, pctB) {
        val a = capA; val b = capB
        if (a == null || b == null || pctA == pctB || pctA == 0 || pctB == 0) {
            emptyList()
        } else {
            val fuelDir = if (pctB > pctA) 1 else -1
            val out = ArrayList<Cand>()
            for ((key, av) in a) {
                val bv = b[key] ?: continue
                val n = minOf(av.size, bv.size)
                for (i in 0 until n) {
                    val ra = av[i]; val rb = bv[i]
                    if (ra !in 1..255 || rb !in 1..255 || ra == rb) continue
                    val byteDir = if (rb > ra) 1 else -1
                    if (byteDir != fuelDir) continue                       // must track fuel
                    val fullA = ra * 100f / pctA
                    val fullB = rb * 100f / pctB
                    val fullRaw = ((fullA + fullB) / 2f).roundToInt().coerceIn(1, 255)
                    if (fullRaw < maxOf(ra, rb)) continue                  // full tank ≥ current
                    out.add(Cand(key, i, ra, rb, fullRaw, kotlin.math.abs(fullA - fullB).roundToInt()))
                }
            }
            out.sortedWith(compareBy({ it.err }, { -kotlin.math.abs(it.rawB - it.rawA) })).take(12)
        }
    }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.vehicle_find_fuel_signal), color = DashColors.TextPrimary) },
        text = {
            Column {
                Text(
                    stringResource(R.string.vehicle_finder_help),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vehicle_dash_reads), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct - 5).coerceAtLeast(5) },
                        modifier = Modifier.size(DashSize.Touch),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("−") }
                    Text("$currentPct%", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct + 5).coerceAtMost(100) },
                        modifier = Modifier.size(DashSize.Touch),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("+") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val snap = snapshot(); capA = snap; pctA = currentPct
                            McuReader.saveFuelCaptureA(currentPct, snap)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capA == null) stringResource(R.string.vehicle_capture_a) else "A ✓ $pctA%") }
                    TextButton(
                        onClick = { capB = snapshot(); pctB = currentPct },
                        enabled = capA != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capB == null) stringResource(R.string.vehicle_capture_b) else "B ✓ $pctB%") }
                    if (capA != null || capB != null) {
                        TextButton(
                            onClick = {
                                capA = null; capB = null; pctA = 0; pctB = 0
                                McuReader.clearFuelCaptureA()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Muted)
                        ) { Text(stringResource(R.string.vehicle_reset)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    entries.isEmpty() ->
                        Text(stringResource(McuReader.waitingRes), color = DashColors.Muted)
                    capA == null ->
                        Text(stringResource(R.string.vehicle_finder_step_a), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    capB == null ->
                        Text(stringResource(R.string.vehicle_finder_step_b, pctA), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    pctA == pctB ->
                        Text(stringResource(R.string.vehicle_finder_same), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    candidates.isEmpty() ->
                        Text(stringResource(R.string.vehicle_finder_none), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(stringResource(R.string.vehicle_finder_candidates), color = DashColors.AccentInk, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(210.dp)) {
                            lazyColumnItems(candidates, key = { "${it.key}#${it.index}" }) { c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DashColors.CardHi)
                                        .clickable {
                                            McuReader.saveFuelMapping(c.key, c.index, c.fullRaw)
                                            McuReader.clearFuelCaptureA()
                                            onDismiss()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(stringResource(R.string.vehicle_finder_frame, c.key, c.index), color = DashColors.TextPrimary, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.vehicle_finder_raw, c.rawA, c.rawB, c.fullRaw), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (McuReader.fuelConfigured) {
                TextButton(onClick = { McuReader.clearFuelMapping(); onDismiss() }) {
                    Text(stringResource(R.string.vehicle_forget_current), color = DashColors.Warning)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.vehicle_close), color = DashColors.Muted) }
        }
    )
}

/**
 * Gradient that follows the gauge arc (135deg -> 405deg). Compose sweep gradients
 * start at 3 o'clock, so the stops are placed in that frame: the arc start
 * (135deg = 0.375) is [start], the arc end (45deg = 0.125, wrapped) is [end].
 */
internal fun gaugeSweepBrush(center: Offset, start: Color, end: Color): Brush {
    val mid = lerp(start, end, 0.35f)
    val atZero = lerp(mid, end, 0.65f)
    return Brush.sweepGradient(
        0f to atZero,
        0.125f to end,
        0.375f to start,
        0.7875f to mid,
        1f to atZero,
        center = center
    )
}

/** Lets the user pick which paired Bluetooth device, or which USB device plugged in, is the OBD adapter. */
@Composable
internal fun DevicePickerDialog(
    devices: List<Pair<String, String>>,
    usbAdapters: List<UsbObdAdapter>,
    onPick: (String) -> Unit,
    onPickUsb: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { TitleWithHelp(stringResource(R.string.vehicle_select_adapter), WikiPage.OBD_ADAPTER) },
        text = {
            Column {
                // A cable first: plugged in, it is the one meant.
                usbAdapters.forEach { adapter ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickUsb(adapter.key) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(adapter.label, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.vehicle_usb_cable), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (devices.isEmpty()) {
                    if (usbAdapters.isEmpty()) {
                        Text(
                            stringResource(R.string.vehicle_no_paired),
                            color = DashColors.TextSecondary
                        )
                    }
                } else {
                    devices.forEach { (name, mac) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(mac) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(name, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
                            Text(mac, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.vehicle_bt_settings), color = DashColors.AccentInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vehicle_cancel), color = DashColors.Muted)
            }
        }
    )
}

// Only BLUETOOTH_CONNECT is needed (and declared) to talk to a bonded adapter.
internal fun requiredBluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }
