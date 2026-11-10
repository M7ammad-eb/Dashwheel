package com.openauto.dash

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Modern skin's tiles: every widget on its own glass card (ModernSkin.kt),
 * laid out from the card's size so the same tile reads as a 3x2 cell and as
 * the whole screen, landscape or upright.
 */

private fun Dp.atMost(other: Dp): Dp = if (this < other) this else other
private fun Dp.atLeast(other: Dp): Dp = if (this > other) this else other
private fun Dp.within(lo: Dp, hi: Dp): Dp = atLeast(lo).atMost(hi)

/**
 * A tile's glass card, the whole tile: tappable when [onClick] is given and
 * the dashboard is not being arranged (then its rim turns blue instead). The
 * content gets the room inside the card's padding, which grows with the card.
 */
@Composable
private fun ModernCard(
    env: SkinTileEnv,
    tone: GlassTone = GlassTone.PLAIN,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    description: String? = null,
    content: @Composable (w: Dp, h: Dp) -> Unit
) {
    val tap = rememberTapFeedback()
    val rim = if (env.editing) DashColors.Accent.copy(alpha = 0.7f) else null
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .modernGlass(tone = tone, rim = rim)
            .then(
                if (onClick != null && !env.editing) {
                    Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { tap(); onClick() }
                } else Modifier
            )
            .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier)
    ) {
        val pad = (if (maxWidth < maxHeight) maxWidth else maxHeight).times(0.08f).within(10.dp, 22.dp)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        Box(Modifier.fillMaxSize().padding(pad)) { content(innerW, innerH) }
    }
}

/** Small letter-spaced caps over a reading ("SPEED"). */
@Composable
private fun Caps(text: String, size: Float, modifier: Modifier = Modifier, color: Color = DashColors.Muted) {
    MText(text.uppercase(Locale.getDefault()), size, color, modifier, weight = FontWeight.Bold, face = Face.CAPS)
}

/** A rounded-square tile holding an icon: the blue gradient (with its glow) when [lit], else a quiet glass well. */
@Composable
private fun IconTile(size: Dp, lit: Boolean, brush: Brush? = null, glowColor: Color = DashColors.Accent, content: @Composable (ink: Color) -> Unit) {
    val light = DashColors.Light
    val glow = if (lit) modernGlow else 0f
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = Modifier
            .size(size)
            .then(if (glow > 0f) Modifier.shadow(size * 0.13f * glow, shape, ambientColor = glowColor, spotColor = glowColor) else Modifier)
            .clip(shape)
            .then(
                if (lit) Modifier.background(brush ?: modernBlueBrush(light))
                else Modifier.background(DashColors.haze(0.06f)).border(1.dp, DashColors.Line, shape)
            ),
        contentAlignment = Alignment.Center
    ) {
        content(if (lit) (if (brush != null) AmberInk else ModernOnBlue) else DashColors.Muted)
    }
}

/** Dark ink on amber, the mockup's #1E1A12; amber is deep enough by day for it too. */
private val AmberInk = Color(0xFF1E1A12)

/** Amber gradient (the fuel suggestion, a sunny sky), from the warning amber to a pale gold. */
private val AmberBrush: Brush get() = Brush.linearGradient(listOf(DashColors.Warning, Color(0xFFFFD27A)))

// --- Next turn ---------------------------------------------------------------------------

/**
 * Next turn from Google Maps or Waze: the manoeuvre on the blue gradient tile
 * with the distance big beside it and the instruction under it. Taller cards
 * add the arrival timeline at the foot and, with room to spare, the city with
 * the car's puck on the route between them. Without a route the tile is a
 * quiet glass well saying so (and the city shows the car parked); a tap opens
 * the navigation app, or the access settings when turns cannot be read yet.
 */
@Composable
internal fun ModernNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val access = env.hasMediaAccess
    val active = access && nav.active
    val open = { if (access) openNavigationApp(context, nav) else CarMediaController.openNotificationAccessSettings(context) }
    ModernCard(
        env,
        onClick = open,
        clickLabel = stringResource(if (access) R.string.skin_modern_open_navigation else R.string.skin_modern_allow_access),
        description = if (active) listOf(nav.distance, nav.instruction).filter { it.isNotBlank() }.joinToString(", ") else null
    ) { w, h ->
        val stripH = 56.dp
        val gap = 12.dp
        val showStrip = active && h >= 200.dp && w >= 260.dp
        val showCity = h >= 330.dp && w >= 240.dp
        val headH = when {
            showCity -> (h * 0.3f).within(76.dp, 140.dp)
            showStrip -> h - stripH - gap
            else -> h
        }
        Column(Modifier.fillMaxSize()) {
            NavHead(nav, access, active, w, headH)
            if (showCity) {
                Spacer(Modifier.height(gap))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        // Its own layer: the flowing dashes redraw it alone, not the whole card.
                        .graphicsLayer()
                        .modernCity(route = active, carShare = 0.22f)
                )
            }
            if (showStrip) {
                Spacer(Modifier.height(gap))
                ArrivalStrip(nav, Modifier.fillMaxWidth().height(stripH))
            } else if (showCity) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** The turn tile, the distance and the instruction (or the quiet state) in a [w]×[h] band. */
@Composable
private fun NavHead(nav: NavState, access: Boolean, active: Boolean, w: Dp, h: Dp) {
    val stacked = w < 200.dp || (h > w * 0.9f && h >= 220.dp)
    val tile = if (stacked) (h * 0.4f).within(44.dp, 120.dp).atMost(w * 0.5f) else h.atMost(w * 0.3f).within(44.dp, 120.dp)
    val textW = if (stacked) w else w - tile - 16.dp
    val textH = if (stacked) h - tile - 10.dp else h
    val glyph = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val turnTile: @Composable () -> Unit = {
        IconTile(tile, lit = active) { ink ->
            if (active && glyph != null) {
                Image(glyph, contentDescription = null, colorFilter = ColorFilter.tint(ink, BlendMode.SrcIn), modifier = Modifier.size(tile * 0.6f))
            } else {
                Icon(if (active) Icons.Filled.Directions else Icons.Filled.Navigation, contentDescription = null, tint = ink, modifier = Modifier.size(tile * 0.5f))
            }
        }
    }
    val words: @Composable () -> Unit = {
        if (active) {
            val (value, unit) = nav.distanceParts
            if (value.isNotEmpty()) {
                val numSize = fitText(digitsTemplate(value) + if (unit.isNotEmpty()) " $unit" else "", Face.NUMBER, FontWeight.ExtraBold, textW, textH * 0.55f, 20f, 96f)
                val instrSize = (numSize * 0.4f).coerceIn(13f, 22f)
                val lines = if (textH.value >= numSize * 1.1f + instrSize * 2.6f) 2 else 1
                Row {
                    MText(value, numSize, modifier = Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width((numSize * 0.12f).dp))
                        MText(unit, numSize * 0.45f, DashColors.TextSecondary, Modifier.alignByBaseline(), weight = FontWeight.Bold)
                    }
                }
                if (textH.value >= numSize * 1.1f + instrSize * 1.3f) {
                    Spacer(Modifier.height(4.dp))
                    MText(nav.instruction, instrSize, DashColors.TextSecondary, maxLines = lines)
                }
            } else {
                // No distance in the notification: the instruction is the headline.
                val size = (textH.value * 0.22f).coerceIn(15f, 30f)
                MText(nav.instruction, size, weight = FontWeight.ExtraBold, maxLines = 3)
            }
        } else {
            val title = stringResource(if (access) R.string.info_directions_no_route else R.string.info_directions_access_title)
            val hint = stringResource(if (access) R.string.skin_modern_tap_open_navigation else R.string.skin_modern_tap_allow_access)
            val size = fitText(title, Face.TEXT, FontWeight.ExtraBold, textW, textH * 0.4f, 15f, 34f)
            MText(title, size, weight = FontWeight.ExtraBold, maxLines = 2)
            if (textH.value >= size * 1.3f + 18f) {
                Spacer(Modifier.height(4.dp))
                MText(hint, (size * 0.55f).coerceIn(13f, 18f), DashColors.AccentInk, maxLines = 2)
            }
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().height(h), verticalArrangement = Arrangement.Center) {
            turnTile()
            Spacer(Modifier.height(10.dp))
            Column { words() }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(h), verticalAlignment = Alignment.CenterVertically) {
            turnTile()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { words() }
        }
    }
}


/**
 * The arrival timeline: the arrival time, the trip as a bar glowing blue up
 * to a dot where the car is, an amber dot where the fuel would run out when
 * it does before the end, and the time and distance left.
 */
@Composable
private fun ArrivalStrip(nav: NavState, modifier: Modifier) {
    val clock = rememberWallClock(30_000L).longValue
    val timeFmt = rememberTimeFormat()
    val units = LocalUnits.current
    val remaining = etaMinutes(nav.eta)
    val arrival = arrivalMs(nav.eta, clock)
    val km = CareRules.remainingKm(nav.eta)
    // The longest time left seen for this route, so the timeline tells how much of the trip is
    // behind: a longer time (a detour) grows it, a destination handed off starts it again, and it
    // goes with the strip rather than living in a process global that outlasted the route.
    val handed by NavHandoff.handedOff.collectAsState()
    val longest = remember(nav.packageName, handed?.at) { mutableIntStateOf(0) }
    val start = max(longest.intValue, remaining ?: 0)
    SideEffect { if (start > longest.intValue) longest.intValue = start }
    val progress = if (remaining != null && start > 0) (1f - remaining.toFloat() / start).coerceIn(0f, 1f) else 0f
    val fuel = rememberFuelToDest()
    val toGo = fuel?.toGoKm
    val dry = if (fuel != null && toGo != null && toGo > 0.0 && fuel.rangeKm < toGo) {
        progress + (1f - progress) * (fuel.rangeKm / toGo).toFloat().coerceIn(0f, 1f)
    } else null
    val left = when {
        remaining == null -> nav.etaParts.firstOrNull { !ARRIVAL_CLOCK.containsMatchIn(it) }.orEmpty()
        remaining >= 60 -> stringResource(R.string.info_map_duration_hm, remaining / 60, remaining % 60)
        else -> stringResource(R.string.info_map_duration_min, remaining)
    }
    val distance = km?.let {
        val d = units.distance(it)
        if (d < 10.0) String.format(Locale.getDefault(), "%.1f %s", d, units.distanceUnit) else "${d.toInt()} ${units.distanceUnit}"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column {
            MText(arrival?.let { timeFmt.format(Date(it)) } ?: "--:--", 24f, weight = FontWeight.ExtraBold, face = Face.NUMBER)
            MText(stringResource(R.string.skin_modern_arrival), 12f, DashColors.Muted, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(18.dp))
        TripBar(progress, dry, Modifier.weight(1f).height(26.dp))
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.End) {
            MText(left, 18f, weight = FontWeight.ExtraBold, face = Face.NUMBER)
            if (distance != null) MText(distance, 14f, DashColors.Muted, weight = FontWeight.SemiBold)
        }
    }
}

/** The trip bar: track, the done part in the blue gradient with its glow, the car's dot and the amber dot where fuel ends. */
@Composable
private fun TripBar(progress: Float, dry: Float?, modifier: Modifier) {
    val light = DashColors.Light
    val glow = modernGlow
    val track = DashColors.haze(0.1f)
    val from = if (light) Color(0xFF1F55C0) else Color(0xFF4E86F2)
    val to = if (light) Color(0xFF4F8DF5) else Color(0xFF8CBBFF)
    val accent = DashColors.Accent
    val dot = if (light) accent else Color(0xFFECEFF3)
    val warn = DashColors.Warning
    val card = DashColors.Card
    Canvas(modifier) {
        val y = size.height / 2f
        val t = 8.dp.toPx()
        val r = 11.dp.toPx()
        val x0 = r
        val x1 = size.width - r
        val x = x0 + (x1 - x0) * progress
        drawLine(track, Offset(x0, y), Offset(x1, y), t, StrokeCap.Round)
        val fill = Brush.horizontalGradient(listOf(from, to), startX = x0, endX = x.coerceAtLeast(x0 + 1f))
        if (glow > 0f) drawLine(to.copy(alpha = 0.32f * glow), Offset(x0, y), Offset(x, y), t * 2.4f, StrokeCap.Round)
        drawLine(fill, Offset(x0, y), Offset(x, y), t, StrokeCap.Round)
        if (dry != null) {
            val dx = x0 + (x1 - x0) * dry
            drawCircle(card, 9.dp.toPx() + 3.dp.toPx(), Offset(dx, y))
            drawCircle(warn, 9.dp.toPx(), Offset(dx, y))
        }
        drawCircle(accent.copy(alpha = 0.4f), r + 5.dp.toPx(), Offset(x, y))
        drawCircle(dot, r, Offset(x, y))
    }
}

// --- Speed ----------------------------------------------------------------------------

/**
 * Speed: "SPEED" in small caps with the road's limit sign beside it, the
 * number heavy and big with its unit, where it comes from and, on taller
 * cards, a bar filling with the speed up to the limit's tick. Amber over the
 * limit or from [SPEED_WARNING_KMH]. With no speed a tap connects OBD.
 */
@Composable
internal fun ModernSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val limit by SpeedLimit.limit.collectAsState()
    val speed = rememberSpeedKmh()
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle
    val units = LocalUnits.current
    ModernCard(
        env,
        onClick = if (canConnect) env.onConnectObd else null,
        clickLabel = stringResource(R.string.skin_modern_connect_obd)
    ) { w, h ->
        val lim = limit
        val over = speed != null && lim != null && speed > lim + SpeedLimit.TOLERANCE_KMH
        val color = when {
            speed == null -> DashColors.Muted
            over || (lim == null && speed >= SPEED_WARNING_KMH) -> DashColors.Warning
            else -> DashColors.TextPrimary
        }
        val capsSize = (min(w.value, h.value) * 0.07f).coerceIn(12f, 16f)
        val sign = (min(h.value * 0.24f, w.value * 0.28f)).coerceIn(34f, 84f).dp
        val head = if (lim != null) sign else (capsSize * 1.4f).dp
        val showBar = h >= 210.dp
        val showSource = h >= 140.dp
        val sourceSize = (capsSize * 0.95f).coerceIn(12f, 15f)
        val below = (if (showSource) sourceSize * 1.4f + 4f else 0f) + (if (showBar) 26f else 0f)
        val numSize = fitText("188", Face.NUMBER, FontWeight.ExtraBold, w * 0.72f, h - head - below.dp - 6.dp, 28f, 240f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(head), verticalAlignment = Alignment.CenterVertically) {
                Caps(stringResource(R.string.info_speed_title), capsSize, Modifier.weight(1f))
                if (lim != null) LimitSign(units.speed(lim), sign)
            }
            Spacer(Modifier.weight(1f))
            Row {
                // A lone dash at full size reads as a bar, so the placeholder is smaller.
                if (speed != null) {
                    MText(units.speed(speed).toString(), numSize, color, Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                } else {
                    MText("–", numSize * 0.5f, color, Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER)
                }
                Spacer(Modifier.width((numSize * 0.08f).dp))
                MText(units.speedUnit, (numSize * 0.22f).coerceIn(13f, 30f), DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.SemiBold)
            }
            if (showSource) {
                Spacer(Modifier.height(4.dp))
                val none = if (idle) stringResource(R.string.skin_modern_tap_connect) else stringResource(R.string.info_speed_no_signal)
                MText(
                    speedSource(none), sourceSize,
                    when {
                        speed != null -> DashColors.Good
                        idle -> DashColors.Accent
                        else -> DashColors.Muted
                    },
                    weight = FontWeight.Bold
                )
            }
            if (showBar) {
                Spacer(Modifier.height(10.dp))
                SpeedBar(speed, lim, color, Modifier.fillMaxWidth().height(16.dp))
            }
        }
    }
}

/** The road sign: a white disc in a red ring with the limit in black. */
@Composable
private fun LimitSign(value: Int, size: Dp) {
    val red = Color(0xFFE5484D)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(size * 0.11f, red, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val text = value.toString()
        MText(text, size.value * if (text.length >= 3) 0.32f else 0.4f, Color(0xFF0B0E14), weight = FontWeight.ExtraBold, face = Face.NUMBER)
    }
}

/** A bar filling with the speed (to 180 km/h, or a little past the limit), the limit marked by a tick. */
@Composable
private fun SpeedBar(speed: Int?, limit: Int?, color: Color, modifier: Modifier) {
    val track = DashColors.haze(0.1f)
    val glow = modernGlow
    val tick = DashColors.TextPrimary
    val light = DashColors.Light
    val from = if (light) Color(0xFF1F55C0) else Color(0xFF4E86F2)
    val warn = color == DashColors.Warning
    Canvas(modifier) {
        val y = size.height / 2f
        val t = 6.dp.toPx()
        val top = if (limit != null) max(180f, limit * 1.25f) else 180f
        val f = ((speed ?: 0) / top).coerceIn(0f, 1f)
        val x0 = t / 2f
        val x1 = size.width - t / 2f
        val x = x0 + (x1 - x0) * f
        drawLine(track, Offset(x0, y), Offset(x1, y), t, StrokeCap.Round)
        if (f > 0f) {
            val end = if (warn) color else DashColors.Accent
            if (glow > 0f) drawLine(end.copy(alpha = 0.3f * glow), Offset(x0, y), Offset(x, y), t * 2.4f, StrokeCap.Round)
            drawLine(Brush.horizontalGradient(listOf(from, end), startX = x0, endX = x.coerceAtLeast(x0 + 1f)), Offset(x0, y), Offset(x, y), t, StrokeCap.Round)
        }
        if (limit != null) {
            val lx = x0 + (x1 - x0) * (limit / top).coerceIn(0f, 1f)
            drawLine(tick, Offset(lx, 0f), Offset(lx, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

// --- Engine ---------------------------------------------------------------------------

private class EngineChip(val label: String, val value: String, val warn: Boolean)

/**
 * Engine readings: "ENGINE" caps with a live dot, the revs heavy and big over
 * a bar that glows blue and warms to amber near the red line, then chips for
 * speed, coolant, battery and load (as many as the width holds, two by two on
 * a tall card). Not connected, it says the link's state and a tap connects.
 */
@Composable
internal fun ModernTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    if (state != ObdConnectionState.CONNECTED && !g.any) {
        ModernCard(
            env,
            onClick = if (state.isIdle) env.onConnectObd else null,
            clickLabel = stringResource(R.string.skin_modern_connect_obd)
        ) { w, h -> EngineIdle(state, w, h) }
        return
    }
    val units = LocalUnits.current
    val chips = listOf(
        EngineChip(stringResource(R.string.info_speed_title), g.speedKmh?.let { "${units.speed(it)}" } ?: "--", speedOver(g.speedKmh)),
        EngineChip(stringResource(R.string.info_chip_coolant), g.coolantTempC?.let { "${units.temp(it)}°" } ?: "--", (g.coolantTempC ?: 0) >= 105),
        EngineChip(
            stringResource(R.string.info_chip_battery),
            g.voltage?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "--",
            g.voltage != null && g.voltage < 11.8
        ),
        EngineChip(stringResource(R.string.info_chip_load), g.engineLoadPct?.let { "$it %" } ?: "--", false)
    )
    ModernCard(env) { w, h ->
        val capsSize = (min(w.value, h.value) * 0.07f).coerceIn(12f, 16f)
        val grid = h >= 300.dp && w < 460.dp
        val perRow = if (grid) 2 else ((w.value + 8f) / 92f).toInt().coerceIn(1, 4)
        val rows = if (grid) 2 else 1
        val chipH = (h.value * if (grid) 0.17f else 0.27f).coerceIn(48f, 84f).dp
        val chipsH = chipH * rows + 8.dp * (rows - 1)
        val heroH = h - (capsSize * 1.4f).dp - chipsH - 8.dp - 22.dp
        val rpm = g.rpm?.let { groupThousands(it) } ?: "--"
        val rpmUnit = stringResource(R.string.skin_modern_rpm)
        val heroSize = fitText(digitsTemplate(rpm) + " " + rpmUnit, Face.NUMBER, FontWeight.ExtraBold, w, heroH, 22f, 180f)
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caps(stringResource(R.string.skin_modern_engine), capsSize, Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(DashColors.Good))
            }
            Spacer(Modifier.weight(1f))
            Row {
                MText(rpm, heroSize, modifier = Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                Spacer(Modifier.width((heroSize * 0.08f).dp))
                MText(rpmUnit, (heroSize * 0.3f).coerceIn(12f, 28f), DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            RevBar((g.rpm ?: 0) / SKIN_RPM_MAX, Modifier.fillMaxWidth().height(14.dp))
            Spacer(Modifier.height(10.dp))
            val shown = chips.take(perRow * rows)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { EngineChipView(it, chipH, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Not connected: a quiet glass well with the gauge icon, "ENGINE" and the link's state in words. */
@Composable
private fun EngineIdle(state: ObdConnectionState, w: Dp, h: Dp) {
    val tile = (min(w.value * 0.3f, h.value * 0.5f)).coerceIn(44f, 96f).dp
    val text = obdStatusLabel(state)
    val textSize = (h.value * 0.11f).coerceIn(14f, 24f)
    val wide = w >= h * 1.2f
    val body: @Composable () -> Unit = {
        Caps(stringResource(R.string.skin_modern_engine), (textSize * 0.62f).coerceIn(11f, 15f))
        Spacer(Modifier.height(4.dp))
        MText(
            text, textSize,
            if (state == ObdConnectionState.ERROR) DashColors.Critical else DashColors.TextPrimary,
            weight = FontWeight.Bold, maxLines = 3
        )
    }
    val icon: @Composable () -> Unit = {
        IconTile(tile, lit = false) { ink -> Icon(Icons.Filled.Speed, contentDescription = null, tint = ink, modifier = Modifier.size(tile * 0.5f)) }
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { body() }
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            icon()
            Spacer(Modifier.height(12.dp))
            body()
        }
    }
}

/** The revs bar: blue gradient with its glow, amber past 4 500 r/min. */
@Composable
private fun RevBar(fraction: Float, modifier: Modifier) {
    val track = DashColors.haze(0.1f)
    val glow = modernGlow
    val light = DashColors.Light
    val from = if (light) Color(0xFF1F55C0) else Color(0xFF4E86F2)
    val to = DashColors.Accent
    val hot = DashColors.Tacho
    Canvas(modifier) {
        val y = size.height / 2f
        val t = 6.dp.toPx()
        val x0 = t / 2f
        val x1 = size.width - t / 2f
        val f = fraction.coerceIn(0f, 1f)
        val x = x0 + (x1 - x0) * f
        drawLine(track, Offset(x0, y), Offset(x1, y), t, StrokeCap.Round)
        if (f <= 0f) return@Canvas
        val end = if (f * SKIN_RPM_MAX > 4_500f) hot else to
        if (glow > 0f) drawLine(end.copy(alpha = 0.3f * glow), Offset(x0, y), Offset(x, y), t * 2.4f, StrokeCap.Round)
        drawLine(Brush.horizontalGradient(listOf(from, end), startX = x0, endX = x.coerceAtLeast(x0 + 1f)), Offset(x0, y), Offset(x, y), t, StrokeCap.Round)
    }
}

@Composable
private fun EngineChipView(chip: EngineChip, height: Dp, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (chip.warn) DashColors.Warning.copy(alpha = 0.14f) else DashColors.haze(0.05f))
            .border(1.dp, if (chip.warn) DashColors.Warning.copy(alpha = 0.5f) else DashColors.Line, shape)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Caps(chip.label, (height.value * 0.17f).coerceIn(10f, 13f))
        MText(
            chip.value, (height.value * 0.36f).coerceIn(15f, 28f),
            if (chip.warn) DashColors.Warning else DashColors.TextPrimary,
            weight = FontWeight.ExtraBold, face = Face.NUMBER
        )
    }
}

// --- Fuel and range -------------------------------------------------------------------

/**
 * Fuel: a gradient ring filled to the tank's level (blue, warming to amber
 * under a quarter) with the share in the middle and the range under it; wide
 * cards put "RANGE" and the range big beside it. A tap recalibrates the fuel
 * reading. In reserve the card turns amber with the cheapest fuel nearby
 * ([ModernFuelSuggestion]); before fuel is known the standard tile explains
 * how to learn it.
 */
@Composable
internal fun ModernRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    if (fuel.percent <= SKIN_LOW_FUEL_PCT) {
        ModernFuelSuggestion(fuel, env)
        return
    }
    var finder by remember { mutableStateOf(false) }
    val units = LocalUnits.current
    ModernCard(env, onClick = { finder = true }, clickLabel = stringResource(R.string.skin_modern_recalibrate)) { w, h ->
        val warm = fuel.percent <= 25
        val range = units.distance(fuel.rangeKm).toString()
        val tank = stringResource(R.string.skin_modern_in_tank, fuel.liters.toInt())
        if (w >= h * 1.3f) {
            val d = h.atMost(w * 0.45f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                FuelRing(fuel.percent, warm, d, showRange = false, range = range)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val textW = w - d - 16.dp
                    val capsSize = (h.value * 0.08f).coerceIn(12f, 16f)
                    val numSize = fitText(digitsTemplate(range) + " " + units.distanceUnit, Face.NUMBER, FontWeight.ExtraBold, textW, h * 0.42f, 22f, 110f)
                    Caps(stringResource(R.string.car_status_range), capsSize)
                    Row {
                        MText(range, numSize, modifier = Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                        Spacer(Modifier.width((numSize * 0.1f).dp))
                        MText(units.distanceUnit, (numSize * 0.36f).coerceIn(13f, 30f), DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.SemiBold)
                    }
                    if (h >= 120.dp) MText(tank, (capsSize * 1.05f).coerceIn(13f, 17f), DashColors.Muted, weight = FontWeight.SemiBold)
                }
            }
        } else {
            val caption = h - w >= 40.dp
            val d = (if (caption) h - 34.dp else h).atMost(w)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FuelRing(fuel.percent, warm, d, showRange = true, range = "$range ${units.distanceUnit}")
                if (caption) {
                    Spacer(Modifier.height(8.dp))
                    MText(tank, 15f, DashColors.Muted, weight = FontWeight.SemiBold, align = TextAlign.Center)
                }
            }
        }
    }
    if (finder) FuelFinderDialog(onDismiss = { finder = false })
}

/** The fuel ring [d] across: a gradient arc from the mockup's angle with a soft glow, the share inside and, when [showRange], the range under it. */
@Composable
private fun FuelRing(percent: Int, warm: Boolean, d: Dp, showRange: Boolean, range: String) {
    val track = DashColors.haze(0.08f)
    val glow = modernGlow
    val light = DashColors.Light
    val from = if (warm) DashColors.Warning else if (light) Color(0xFF1F55C0) else Color(0xFF4E86F2)
    val to = if (warm) Color(0xFFFFD27A) else DashColors.Accent
    val f = (percent / 100f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(d)
            .cachedDraw(f, from, to, track, glow) {
                val stroke = size.minDimension * 0.1f
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - stroke * (if (glow > 0f) 1.1f else 0.6f)
                val tl = Offset(center.x - r, center.y - r)
                val box = Size(r * 2f, r * 2f)
                val sweep = 360f * f
                // The gradient runs from the arc's start (rotated to 0°) to its end, so it never wraps.
                val brush = Brush.sweepGradient(0f to from, (f * 0.98f).coerceAtLeast(0.01f) to to, 1f to to, center = center)
                onDrawBehind {
                    drawCircle(track, r, center, style = Stroke(stroke))
                    if (sweep <= 0f) return@onDrawBehind
                    rotate(RING_START - 3f, center) {
                        if (glow > 0f) drawArc(to.copy(alpha = 0.24f * glow), 3f, sweep, false, tl, box, style = Stroke(stroke * 2f, cap = StrokeCap.Round))
                        drawArc(brush, 3f, sweep, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MText("$percent%", (d.value * 0.22f).coerceAtLeast(14f), weight = FontWeight.ExtraBold, face = Face.NUMBER)
            if (showRange) MText(range, (d.value * 0.1f).coerceAtLeast(12f), DashColors.Muted, weight = FontWeight.SemiBold)
        }
    }
}

/** Where the fuel arc starts, in degrees from three o'clock (the mockup's conic gradient starts at 200° from twelve). */
private const val RING_START = 110f

/**
 * Fuel in reserve, the mockup's amber "suggested" card: an amber pump tile,
 * the cheapest fuel nearby and its price ("Gazole at 1.719 €") with how far
 * and which station, and a Go key that sets the route there after a short
 * held step (it replaces the route). Without prices (no position, no
 * network, outside France) it says the fuel is low and the range left.
 */
@Composable
private fun ModernFuelSuggestion(fuel: FuelInfo, env: SkinTileEnv) {
    val nearby = rememberFuelNearby()
    val best = nearby?.ranked?.firstOrNull()
    val pending = rememberPendingAction()
    val context = env.context
    val units = LocalUnits.current
    val title = if (nearby != null && best != null) {
        stringResource(R.string.skin_modern_fuel_at, nearby.grade.label, FuelPrices.formatPriceWithCurrency(best.price))
    } else stringResource(R.string.skin_modern_fuel_low)
    val sub = if (best != null) {
        listOf(stringResource(R.string.skin_modern_away, FuelPrices.formatDistance(best.distanceKm, units)), best.station.name.ifBlank { best.station.town })
            .filter { it.isNotBlank() }.joinToString(" · ")
    } else stringResource(R.string.skin_modern_range_left, units.distanceText(fuel.rangeKm))
    val guidance = best?.let { stringResource(R.string.phone_guidance_to, it.station.name.ifBlank { it.station.town }) }
    val go: (() -> Unit)? = if (best != null && guidance != null) {
        { pending.arm(guidance) { navigateTo(context, best.station.lat, best.station.lng, best.station.label) } }
    } else null
    Box(Modifier.fillMaxSize()) {
        ModernCard(env, tone = GlassTone.AMBER, description = "$title, $sub") { w, h ->
            val tall = h >= 220.dp && w < h * 1.4f
            val tile = (if (tall) h * 0.24f else h.atMost(w * 0.22f)).within(44.dp, 72.dp)
            val titleSize = (min(w.value / 16f, h.value / 6f)).coerceIn(15f, 26f)
            val pump: @Composable () -> Unit = {
                IconTile(tile, lit = true, brush = AmberBrush, glowColor = DashColors.Warning) { ink ->
                    Icon(Icons.Filled.LocalGasStation, contentDescription = null, tint = ink, modifier = Modifier.size(tile * 0.52f))
                }
            }
            val words: @Composable () -> Unit = {
                MText(title, titleSize, weight = FontWeight.ExtraBold, maxLines = 2)
                MText(sub, (titleSize * 0.72f).coerceIn(13f, 18f), DashColors.TextSecondary, weight = FontWeight.SemiBold, maxLines = 2)
            }
            if (tall) {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pump()
                        Spacer(Modifier.weight(1f))
                        MText("${fuel.percent}%", (tile.value * 0.6f), DashColors.Warning, weight = FontWeight.ExtraBold, face = Face.NUMBER)
                    }
                    Spacer(Modifier.height(12.dp))
                    words()
                    Spacer(Modifier.weight(1f))
                    if (go != null) AmberKey(stringResource(R.string.skin_modern_go), env.editing, go, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    pump()
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { words() }
                    if (go != null && w >= 300.dp) {
                        Spacer(Modifier.width(12.dp))
                        AmberKey(stringResource(R.string.skin_modern_go), env.editing, go)
                    }
                }
            }
        }
        PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

/** The amber key of a suggestion (the mockup's "Add stop"). */
@Composable
private fun AmberKey(label: String, editing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 72.dp)
            .clip(shape)
            .background(DashColors.Warning)
            .clickable(enabled = !editing, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        MText(label, 16f, AmberInk, weight = FontWeight.ExtraBold)
    }
}

// --- Music ----------------------------------------------------------------------------

/** Two colour blobs and the dark ground under them: one set per song (picked from its name), the mockup's first. */
private val CoverInks = listOf(
    listOf(Color(0xFFFF8FB1), Color(0xFF7A5CFF), Color(0xFF2B1E5C), Color(0xFF0F1A3A)),
    listOf(Color(0xFFFFB86B), Color(0xFFFF5F8F), Color(0xFF3A1E2C), Color(0xFF1A0F22)),
    listOf(Color(0xFF6EE7F9), Color(0xFF4E86F2), Color(0xFF0E2A44), Color(0xFF0B1428)),
    listOf(Color(0xFF9BF3B0), Color(0xFF3EC6A8), Color(0xFF0F2E2A), Color(0xFF0A1A1C)),
    listOf(Color(0xFFFFD27A), Color(0xFFFF7A59), Color(0xFF3A2412), Color(0xFF1C120B)),
    listOf(Color(0xFFC79BFF), Color(0xFF6EA8FE), Color(0xFF221B48), Color(0xFF0E1230))
)

/**
 * Music: the cover (the song's own art, else art made from its name: two
 * colour blobs on a dark ground, with a glow of its colour under it), the
 * title heavy, the artist under it, a thin progress line and pill keys, play
 * filled. Cards wide enough put the cover beside the rest; upright ones put a
 * big cover on top; small ones a small cover beside the title.
 */
@Composable
internal fun ModernMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val context = env.context
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = when {
        !access -> stringResource(R.string.info_media_access_needed)
        hasTrack -> ms.title
        else -> stringResource(R.string.info_nothing_playing)
    }
    val sub = when {
        !access -> stringResource(R.string.info_media_tap_to_enable)
        hasTrack -> ms.artist.ifBlank { stringResource(R.string.skin_modern_unknown_artist) }
        else -> stringResource(R.string.skin_modern_play_something)
    }
    ModernCard(
        env,
        onClick = if (!access) ({ CarMediaController.openNotificationAccessSettings(context) }) else null,
        clickLabel = stringResource(R.string.info_media_grant_access)
    ) { w, h ->
        val key = (h.value * 0.24f).coerceIn(48f, 64f).dp
        val keysW = key * 3 + 16.dp
        val upright = h >= w * 1.25f && h >= 360.dp
        val sideCover = (h).atMost(w * 0.42f)
        val beside = !upright && w - sideCover - 18.dp >= keysW && h >= 140.dp
        val showProgress = access && hasTrack && ms.durationMs > 0L
        when {
            upright -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                val cover = w.atMost(h * 0.48f)
                CoverArt(ms, hasTrack && access, cover)
                Spacer(Modifier.height(16.dp))
                MediaWords(title, sub, w, (w.value / 12f).coerceIn(18f, 30f), !access, Alignment.CenterHorizontally)
                Spacer(Modifier.weight(1f))
                if (showProgress) MediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                if (access) {
                    Spacer(Modifier.height(14.dp))
                    MediaKeys(env, key)
                }
            }
            beside -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(ms, hasTrack && access, sideCover)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    MediaWords(title, sub, w - sideCover - 18.dp, (h.value / 7.5f).coerceIn(16f, 30f), !access, Alignment.Start)
                    if (showProgress) MediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    if (access) MediaKeys(env, key) else Spacer(Modifier.height(1.dp))
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                val small = (h * 0.34f).within(40.dp, 76.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(ms, hasTrack && access, small)
                    Spacer(Modifier.width(12.dp))
                    MediaWords(title, sub, w - small - 12.dp, (h.value / 9f).coerceIn(15f, 24f), !access, Alignment.Start)
                }
                if (showProgress && h >= 150.dp) MediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                if (access) MediaKeys(env, key)
            }
        }
    }
}

@Composable
private fun MediaWords(title: String, sub: String, w: Dp, size: Float, hint: Boolean, align: Alignment.Horizontal) {
    Column(Modifier.widthIn(max = w), horizontalAlignment = align) {
        val textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
        MText(title, size, weight = FontWeight.ExtraBold, align = textAlign)
        MText(sub, (size * 0.66f).coerceIn(13f, 20f), if (hint) DashColors.AccentInk else DashColors.Muted, weight = FontWeight.SemiBold, align = textAlign)
    }
}

/** The cover [size] across: the song's art, or art made from its name; a quiet well with a note when nothing plays. */
@Composable
private fun CoverArt(ms: MediaState, playing: Boolean, side: Dp) {
    val shape = RoundedCornerShape(side * 0.17f)
    val art = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    val seed = "${ms.title}|${ms.artist}".hashCode()
    val ink = CoverInks[abs(seed % CoverInks.size)]
    val glow = if (playing) modernGlow else 0f
    Box(
        modifier = Modifier
            .size(side)
            .then(if (glow > 0f) Modifier.shadow(side * 0.1f * glow, shape, ambientColor = ink[1], spotColor = ink[1]) else Modifier)
            .clip(shape)
            .then(
                if (playing) Modifier.cachedDraw(seed) {
                    val ground = Brush.linearGradient(listOf(ink[2], ink[3]), start = Offset.Zero, end = Offset(size.width, size.height))
                    // The blobs drift a little with the song, so two songs of one colour set still differ.
                    val jx = ((seed ushr 8) and 0xFF) / 255f * 0.2f - 0.1f
                    val jy = ((seed ushr 16) and 0xFF) / 255f * 0.2f - 0.1f
                    val a = Offset(size.width * (0.3f + jx), size.height * (0.3f + jy))
                    val b = Offset(size.width * (0.75f - jx), size.height * (0.7f - jy))
                    val ra = size.minDimension * 0.55f
                    val rb = size.minDimension * 0.6f
                    val blobA = Brush.radialGradient(listOf(ink[0], ink[0].copy(alpha = 0f)), a, ra)
                    val blobB = Brush.radialGradient(listOf(ink[1], ink[1].copy(alpha = 0f)), b, rb)
                    onDrawBehind {
                        drawRect(ground)
                        drawCircle(blobB, rb, b)
                        drawCircle(blobA, ra, a)
                    }
                } else Modifier.background(DashColors.haze(0.06f)).border(1.dp, DashColors.Line, shape)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (art != null && playing) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!playing) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(side * 0.38f))
        }
    }
}

/** The progress line, in its own scope so only it follows the playback position. */
@Composable
private fun MediaProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val fraction = rememberMediaFraction(ms, controller)
    val track = DashColors.haze(0.12f)
    val fill = DashColors.TextPrimary
    Canvas(modifier.height(10.dp)) {
        val y = size.height / 2f
        val t = 5.dp.toPx()
        drawLine(track, Offset(t / 2f, y), Offset(size.width - t / 2f, y), t, StrokeCap.Round)
        if (fraction > 0f) drawLine(fill, Offset(t / 2f, y), Offset(t / 2f + (size.width - t) * fraction, y), t, StrokeCap.Round)
    }
}

/** Previous, play / pause and next as pill keys, play filled in the text colour. */
@Composable
private fun MediaKeys(env: SkinTileEnv, size: Dp) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillKey(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous), size, primary = false, enabled = enabled) { env.mediaController.previous() }
        PillKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.skin_modern_pause else R.string.skin_modern_play),
            size, primary = true, enabled = enabled
        ) { env.mediaController.playPause() }
        PillKey(Icons.Filled.SkipNext, stringResource(R.string.info_media_next), size, primary = false, enabled = enabled) { env.mediaController.next() }
    }
}

@Composable
private fun PillKey(icon: ImageVector, label: String, size: Dp, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (primary) DashColors.TextPrimary else DashColors.haze(0.08f))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) DashColors.Background else DashColors.TextPrimary, modifier = Modifier.size(size * 0.42f))
    }
}

// --- Clock and weather ----------------------------------------------------------------

/** Clock: the time heavy and tight, the date under it and, on taller cards, the weather in a line. A tap opens alarms. */
@Composable
internal fun ModernClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val weather by WeatherRepo.weather.collectAsState()
    val context = env.context
    ModernCard(env, onClick = { openClockApp(context) }, clickLabel = stringResource(R.string.skin_modern_open_clock)) { w, h ->
        val dateSize = (w.value / 18f).coerceIn(13f, 22f)
        val wx = weather
        val showWeather = wx != null && h >= 190.dp
        val below = (dateSize * 1.3f * (if (showWeather) 2.4f else 1f) + 6f).dp
        val amPm = units.amPm(now, locale)
        val timeSize = fitText("00:00", Face.NUMBER, FontWeight.ExtraBold, w * if (amPm != null) 0.8f else 1f, h - below, 28f, 300f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row {
                MText(timeFmt.format(now), timeSize, modifier = Modifier.alignByBaseline(), weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    MText(amPm, (timeSize * 0.22f).coerceAtLeast(13f), DashColors.Muted, Modifier.alignByBaseline(), weight = FontWeight.Bold)
                }
            }
            MText(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, dateSize, DashColors.TextSecondary, weight = FontWeight.SemiBold)
            if (wx != null && showWeather) {
                Spacer(Modifier.height((dateSize * 0.5f).dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(wx.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size((dateSize * 1.2f).dp))
                    Spacer(Modifier.width(8.dp))
                    MText("${units.temp(wx.tempC)}° · ${wx.condition}", dateSize, DashColors.Muted, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Weather: the condition's icon on a gradient tile (amber for sun, blue
 * otherwise), the temperature heavy and big, the condition and a feels / wind
 * / low-high line. Says why there is none yet (no GPS, offline, loading).
 */
@Composable
internal fun ModernWeather(env: SkinTileEnv) {
    val wx = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    ModernCard(env) { w, h ->
        val tile = (min(h.value * 0.36f, w.value * 0.26f)).coerceIn(44f, 88f).dp
        if (wx == null) {
            val title = stringResource(
                when (wait) {
                    WeatherWait.LOADING -> R.string.info_weather_loading
                    WeatherWait.NO_GPS -> R.string.info_waiting_gps
                    WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
                }
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconTile(tile, lit = false) { ink ->
                    Icon(if (wait == WeatherWait.UNAVAILABLE) Icons.Filled.CloudOff else Icons.Filled.Cloud, contentDescription = null, tint = ink, modifier = Modifier.size(tile * 0.5f))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    MText(title, (h.value * 0.11f).coerceIn(15f, 24f), weight = FontWeight.Bold, maxLines = 2)
                    MText(stringResource(R.string.skin_modern_weather_here), 13f, DashColors.Muted, maxLines = 2)
                }
            }
            return@ModernCard
        }
        val sunny = wx.code <= 1
        val temp = "${units.temp(wx.tempC)}°"
        val feels = stringResource(R.string.skin_modern_feels, units.temp(wx.feelsC))
        val wind = stringResource(R.string.skin_modern_wind, units.speed(wx.windKmh), units.speedUnit)
        val hiLo = if (!wx.hiC.isNaN() && !wx.loC.isNaN()) "${units.temp(wx.loC)}° / ${units.temp(wx.hiC)}°" else null
        val lineSize = (min(w.value / 22f, h.value / 9f)).coerceIn(12f, 18f)
        val wide = w >= h * 1.5f
        val iconTile: @Composable () -> Unit = {
            IconTile(tile, lit = true, brush = if (sunny) AmberBrush else null, glowColor = if (sunny) DashColors.Warning else DashColors.Accent) { ink ->
                Icon(weatherIcon(wx.code), contentDescription = null, tint = ink, modifier = Modifier.size(tile * 0.55f))
            }
        }
        if (wide) {
            val textW = w - tile - 16.dp
            val tempSize = fitText(digitsTemplate(temp), Face.NUMBER, FontWeight.ExtraBold, textW * 0.45f, h * 0.7f, 26f, 160f)
            val line = firstThatFits(listOfNotNull(hiLo?.let { "$feels · $wind · $it" }, "$feels · $wind", feels), lineSize, FontWeight.SemiBold, Face.TEXT, textW - (tempSize * 1.6f).dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                iconTile()
                Spacer(Modifier.width(16.dp))
                MText(temp, tempSize, weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    MText(wx.condition, (lineSize * 1.25f).coerceIn(14f, 24f), weight = FontWeight.Bold)
                    MText(line, lineSize, DashColors.Muted, weight = FontWeight.SemiBold)
                }
            }
        } else {
            val below = (lineSize * 1.4f * 2f + 8f).dp
            val tempSize = fitText(digitsTemplate(temp), Face.NUMBER, FontWeight.ExtraBold, w - tile - 14.dp, h - below, 26f, 200f)
            val line = firstThatFits(listOfNotNull(hiLo?.let { "$feels · $wind · $it" }, "$feels · $wind", feels), lineSize, FontWeight.SemiBold, Face.TEXT, w)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    iconTile()
                    Spacer(Modifier.width(14.dp))
                    MText(temp, tempSize, weight = FontWeight.ExtraBold, face = Face.NUMBER, overflow = TextOverflow.Clip)
                }
                Column {
                    MText(wx.condition, (lineSize * 1.2f).coerceIn(14f, 22f), weight = FontWeight.Bold)
                    MText(line, lineSize, DashColors.Muted, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// --- Apps -----------------------------------------------------------------------------

/** An app's own icon, or a neutral grid glyph while apps load. */
@Composable
private fun AppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(size * 0.8f))
}

/** App shortcut: a glass card with the app's icon and, where there is room, its name under it. */
@Composable
internal fun ModernApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    ModernCard(
        env,
        onClick = { env.onLaunchApp(item.packageName) },
        clickLabel = stringResource(R.string.skin_modern_open_app, label),
        description = label
    ) { w, h ->
        val showLabel = h >= 84.dp
        val icon = (min(w.value, h.value) * if (showLabel) 0.52f else 0.72f).coerceIn(28f, 80f).dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppGlyph(app, icon)
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                MText(label, (icon.value * 0.24f).coerceIn(12f, 16f), DashColors.TextSecondary, weight = FontWeight.SemiBold, align = TextAlign.Center)
            }
        }
    }
}

/**
 * Launch bar: one glass card with the apps as rounded keys in a row (labels
 * beside the icons on a wide bar, under them on a tall one) and the edit
 * pencil at the end, live even while arranging, as on the standard bar.
 */
@Composable
internal fun ModernLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    ModernCard(env) { w, h ->
        val tall = h >= 120.dp
        val room = (w - 56.dp).value
        // Never squeeze an app below a 52 dp target.
        val pkgs = item.packages.take((room / 60f).toInt().coerceAtLeast(1))
        val per = room / pkgs.size.coerceAtLeast(1)
        val labels = if (tall) per >= 76f else per >= 130f
        val icon = (if (tall) h.value * 0.36f else h.value * 0.5f).coerceIn(24f, 56f).dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                MText(stringResource(R.string.skin_modern_launch_bar_empty), 15f, DashColors.Muted, Modifier.weight(1f), maxLines = 2)
            } else {
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    pkgs.forEach { pkg ->
                        LaunchKey(env.appsByPackage[pkg], pkg, icon, labels, tall, !env.editing, Modifier.weight(1f).fillMaxHeight()) {
                            env.onLaunchApp(pkg)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DashColors.haze(0.06f))
                    .clickable(role = Role.Button, onClick = env.onEditLaunchBar),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.skin_modern_edit_launch_bar), tint = DashColors.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LaunchKey(app: AppEntry?, pkg: String, icon: Dp, label: Boolean, tall: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = appLabel(app, pkg)
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .clip(shape)
        .background(DashColors.haze(0.05f))
        .clickable(enabled = enabled, onClickLabel = stringResource(R.string.skin_modern_open_app, name), role = Role.Button) { tap(); onClick() }
        .semantics { contentDescription = name }
        .padding(horizontal = 6.dp)
    if (tall) {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppGlyph(app, icon)
            if (label) {
                Spacer(Modifier.height(6.dp))
                MText(name, 14f, DashColors.TextSecondary, weight = FontWeight.SemiBold, align = TextAlign.Center)
            }
        }
    } else {
        Row(base, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(app, icon)
            if (label) {
                Spacer(Modifier.width(8.dp))
                MText(name, 14f, DashColors.TextPrimary, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold)
            }
        }
    }
}
