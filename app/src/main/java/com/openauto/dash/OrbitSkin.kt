package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/*
 * Orbit skin: everything round. A navy page with a coral and a violet glow,
 * faint dashed orbit rings and two small dots circling them; a floating
 * "dynamic island" top bar; a ring speed dial with satellite bubbles, a vinyl
 * record with a tonearm, a coral next-turn bubble, a ring clock, weather and
 * liquid-fuel bubbles, and app icons as bubbles on a gentle arc. Tiles are
 * bare: each widget draws its own shapes straight on the page background.
 * By day the page turns pale lavender, rings and hairlines take an ink tint,
 * and bubbles become frosted white discs with a soft drop shadow; the record
 * stays black vinyl.
 */

// Orbit colours beyond the palette (DashColors carries coral, teal, violet, text and muted).
// By day pink and sun deepen to hold on the pale page, the ink on coral turns
// white, and the tonearm takes a darker chrome on a pale base.
private val OrbitPink: Color get() = if (DashColors.Light) Color(0xFFEC3F7C) else Color(0xFFFF4F8B)
private val OrbitSun: Color get() = if (DashColors.Light) Color(0xFFEB920C) else Color(0xFFFFC867)
private val OrbitInk: Color get() = if (DashColors.Light) Color.White else Color(0xFF2A0F07)
private val OrbitVinyl = Color(0xFF0C0C11)
private val OrbitArm: Color get() = if (DashColors.Light) Color(0xFFA9AFC6) else Color(0xFFC9CEE0)
private val OrbitArmBase: Color get() = if (DashColors.Light) Color(0xFFE7E9F1) else Color(0xFF252A40)

/** Day only: the frosted white of bubbles, pills and the island. */
private val OrbitFrost = Color.White.copy(alpha = 0.92f)

private const val DIAL_MAX_KMH = 200f
private const val DIAL_START = 135f
private const val DIAL_SWEEP = 270f
private const val TAU = (2 * PI).toFloat()

/** Pure white at [alpha], for sheen that stays white by day (the record stays black). */
private fun white(alpha: Float): Color = Color.White.copy(alpha = alpha)

/**
 * Rings, hairlines, tracks and faint discs over the page: white at [alpha] at
 * night; by day the ink, a little fainter since ink reads stronger on the pale page.
 */
private fun mist(alpha: Float): Color =
    if (DashColors.Light) DashColors.TextPrimary.copy(alpha = alpha * 0.8f) else white(alpha)

/** A bubble's fill: a faint white haze at [alpha] at night, frosted white by day. */
private fun frost(alpha: Float): Color = if (DashColors.Light) OrbitFrost else white(alpha)

/**
 * By day, a soft ink drop shadow under a bubble; put it before any clip. The
 * bubble is the circle inscribed in the bounds or, for one inside a clipped
 * item, a circle [diameter] dp across centred horizontally [top] dp below the
 * top edge. At night bubbles cast none and this adds nothing. Remembered, so
 * a recomposing bubble (a live gauge) keeps its cached shadow.
 */
@Composable
private fun Modifier.orbitShadow(diameter: Float = 0f, top: Float = 0f): Modifier = if (!DashColors.Light) this else cachedDraw(diameter, top) {
    val r = if (diameter > 0f) diameter.dp.toPx() / 2f else size.minDimension / 2f
    val cy = if (diameter > 0f) top.dp.toPx() + r else size.height / 2f
    val blur = min(r * 0.2f, 10.dp.toPx())
    val reach = r + blur
    val c = Offset(size.width / 2f, cy + blur * 0.4f)
    val ink = DashColors.TextPrimary
    // Full strength up to the disc's lower edge, then an eased fade over the blur.
    val edge = (r - blur * 0.4f) / reach
    val brush = Brush.radialGradient(
        0f to ink.copy(alpha = 0.14f),
        edge to ink.copy(alpha = 0.14f),
        (edge + 1f) / 2f to ink.copy(alpha = 0.05f),
        1f to ink.copy(alpha = 0f),
        center = c,
        radius = reach
    )
    onDrawBehind { drawCircle(brush, reach, c) }
}

/** Day only: a bubble's frosted white face [r] px round, with a faint [rimColor] hairline drawn in [rim]. */
private fun DrawScope.frostedFace(r: Float, rim: Stroke, rimColor: Color) {
    drawCircle(OrbitFrost, r)
    drawCircle(rimColor, r - rim.width / 2f, style = rim)
}

/**
 * A render layer of the tile's own. The page background animates on every ambient step;
 * without it the tile's drawing would be replayed along with the background.
 */
private fun Modifier.ownLayer(): Modifier = graphicsLayer { }

/** One line (or [maxLines]) of Orbit text in the system sans; [tight] gives big numerals their tight tracking. */
@Composable
private fun OrbitText(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    tight: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Center
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fixedSp(size),
        lineHeight = fixedSp(size * if (tight) 1.05f else 1.25f),
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        letterSpacing = if (tight) (-0.04).em else 0.em,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align
    )
}

// --- Page background -----------------------------------------------------------------

/**
 * Navy page (pale lavender by day) with a coral glow near the middle and a
 * violet one towards the right, faint dashed orbit rings, and a teal and a
 * coral dot circling two of them in opposite directions (26 s and 44 s a lap).
 * The still part is rendered once into an offscreen layer; each step of the
 * ambient ticker (about 20 a second, none with effects off) only composites it
 * and draws the two dots, whose angles are read inside the draw lambda.
 */
@Composable
internal fun orbitBackground(): Modifier {
    val bg = DashColors.Background
    val coral = DashColors.Accent
    val violet = DashColors.Accent2
    val teal = DashColors.Secondary
    // By day the glows and halos soften so the colour tints the pale page rather than stains it.
    val light = DashColors.Light
    val coralGlow = if (light) 0.12f else 0.14f
    val violetGlow = if (light) 0.13f else 0.15f
    val halo = if (light) 0.32f else 0.5f
    val inner = rememberLoop(26_000)
    val outer = rememberLoop(44_000)
    return Modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val hub = Offset(w * 0.57f, h * 0.53f)
        val innerR = h * 0.294f
        val outerR = h * 0.375f
        val tealDot = 5.dp.toPx()
        val coralDot = 4.dp.toPx()
        val tealHalo = Brush.radialGradient(listOf(teal.copy(alpha = halo), Color.Transparent), Offset.Zero, tealDot * 3.4f)
        val coralHalo = Brush.radialGradient(listOf(coral.copy(alpha = halo), Color.Transparent), Offset.Zero, coralDot * 3.4f)
        val still = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        still.record {
            drawRect(bg)
            // Radii follow the CSS mock: a share of the distance to the farthest corner.
            val coralR = hypot(hub.x, hub.y) * 0.32f
            drawCircle(Brush.radialGradient(listOf(coral.copy(alpha = coralGlow), Color.Transparent), hub, coralR), coralR, hub)
            val violetC = Offset(w * 0.84f, h * 0.44f)
            val violetR = hypot(violetC.x, h - violetC.y) * 0.28f
            drawCircle(Brush.radialGradient(listOf(violet.copy(alpha = violetGlow), Color.Transparent), violetC, violetR), violetR, violetC)
            val fine = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 8.dp.toPx()))
            val sparse = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 14.dp.toPx()))
            drawCircle(mist(0.09f), innerR, hub, style = Stroke(1.5.dp.toPx(), pathEffect = fine))
            drawCircle(mist(0.045f), outerR, hub, style = Stroke(1.dp.toPx()))
            drawCircle(mist(0.035f), h * 0.62f, hub, style = Stroke(1.dp.toPx(), pathEffect = sparse))
        }
        onDrawBehind {
            drawLayer(still)
            val a = inner.value * TAU - PI.toFloat() / 2f
            translate(hub.x + cos(a) * innerR, hub.y + sin(a) * innerR) {
                drawCircle(tealHalo, tealDot * 3.4f, Offset.Zero)
                drawCircle(teal, tealDot, Offset.Zero)
            }
            val b = -outer.value * TAU - PI.toFloat() / 2f
            translate(hub.x + cos(b) * outerR, hub.y + sin(b) * outerR) {
                drawCircle(coralHalo, coralDot * 3.4f, Offset.Zero)
                drawCircle(coral, coralDot, Offset.Zero)
            }
        }
    }
}

// --- Top bar -------------------------------------------------------------------------

/**
 * The same bar as every other look, in Orbit's frosted glass: an island on
 * the left with Apps and Layout (their words beside the icons where the bar
 * is wide enough), the clock with a short date and the dashboard's position
 * centred, and an island on the right with the OBD pill and the ⋮ menu, ⋮
 * nearest the screen edge. The setup pill, the vehicle alert chips and the
 * phone sit between the clock and the right island, only when there is
 * something to show and only where they fit ([BarEnd]). The buttons are 56 dp
 * targets with 26 dp icons, 8 dp apart. By day the islands are frosted
 * white, lifted off the page by a soft ink shadow.
 */
@Composable
internal fun OrbitTopBar(m: TopBarModel) {
    val now = rememberNow(60_000L)
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    val shape = RoundedCornerShape(32.dp)
    val light = DashColors.Light
    val ink = DashColors.TextPrimary
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(ORBIT_BAR)
            .ownLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val shared = barIsShared()
        // A narrow screen drops the date; a tight one gives its room to a chip or a pill.
        val showDate = maxWidth >= 640.dp && !(narrow && (shared || m.setupPending))
        val island: @Composable (content: @Composable RowScope.() -> Unit) -> Unit = { content ->
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(if (light) Modifier.shadow(6.dp, shape, ambientColor = ink, spotColor = ink) else Modifier)
                    .clip(shape)
                    .background(frost(0.06f))
                    .border(1.dp, mist(0.10f), shape)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Each side gets half of what the clock leaves, so the clock stays
            // centred; an upright screen, or half of a split one, has no half wide
            // enough for that, so the islands take only what they need.
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                island {
                    OrbitBarButton(
                        onClick = m.onApps, filled = true,
                        label = if (labels) stringResource(R.string.dash_apps) else null,
                        description = stringResource(R.string.orbit_all_apps)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                    }
                    LayoutPicker(m) { open ->
                        OrbitBarButton(
                            onClick = open,
                            label = if (labels) stringResource(R.string.dash_layout_button) else null,
                            description = stringResource(R.string.orbit_screen_layout_desc, m.layout.title)
                        ) {
                            LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(26.dp))
                        }
                    }
                }
            }
            // The head unit's status bar shows the time while it is up.
            Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) {
                    Text(
                        text = m.clock,
                        modifier = Modifier.alignByBaseline(),
                        color = DashColors.TextPrimary,
                        fontSize = fixedSp(24f),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.02).em,
                        maxLines = 1
                    )
                    if (showDate) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = dateFmt.format(now),
                            modifier = Modifier.alignByBaseline(),
                            color = DashColors.Muted,
                            fontSize = fixedSp(14f),
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            // The right island gets its room first; the badges before it only where they fit.
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 10.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).fillMaxHeight()) {
                    island {
                        ObdPill(m.obdConnection, m.onConnectObd)
                        MorePicker(m) { open ->
                            OrbitBarButton(onClick = open, description = stringResource(R.string.orbit_more)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The island's height: its buttons and 4 dp around them. */
private val ORBIT_BAR = 64.dp

/**
 * 56 dp round button inside an island, a pill once it carries a [label];
 * [filled] gives it the faint disc of the Apps button (white at night, ink by day).
 */
@Composable
private fun OrbitBarButton(
    onClick: () -> Unit,
    description: String,
    filled: Boolean = false,
    label: String? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .height(DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clip(CircleShape)
            .background(if (filled) mist(0.08f) else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (label != null) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = DashColors.TextPrimary,
                fontSize = fixedSp(16f),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

// --- Tiles ---------------------------------------------------------------------------

/**
 * Orbit renderer for app shortcuts, launch bars and the main widgets; anything
 * else, and fuel before it is learned, keeps the standard renderer.
 */
@Composable
internal fun OrbitTile(item: DashboardItem, env: SkinTileEnv) {
    Box(modifier = Modifier.fillMaxSize().ownLayer()) {
        when (item) {
            is DashboardItem.AppShortcut -> OrbitAppBubble(item, env)
            is DashboardItem.LaunchBar -> OrbitLaunchArc(item, env)
            is DashboardItem.BuiltinWidget -> when (item.kind) {
                BuiltinKind.TELEMETRY -> OrbitTelemetry(env)
                BuiltinKind.SPEED_HUD -> OrbitSpeedHud(env)
                BuiltinKind.MEDIA -> OrbitMedia(env)
                BuiltinKind.NAVIGATION -> OrbitNavigation(env)
                BuiltinKind.CLOCK -> OrbitClock(env)
                BuiltinKind.WEATHER -> OrbitWeather()
                BuiltinKind.RANGE -> OrbitRange(item, env)
                else -> StandardSkinnedTile(item, env)
            }
            else -> StandardSkinnedTile(item, env)
        }
    }
}

// --- Speed dial ----------------------------------------------------------------------

/**
 * Where a dial and its satellite bubbles sit in a tile, in dp: the dial's
 * diameter and centre, the bubbles' diameter and centres, and the radius of
 * the orbit through them (0 when they stand in a straight row).
 */
private class DialLayout(
    val dial: Float,
    val center: Offset,
    val bubble: Float = 0f,
    val satellites: List<Offset> = emptyList(),
    val orbit: Float = 0f
)

/**
 * Fits a dial and [count] satellites into a [w]×[h] dp tile: on an arc
 * hugging the dial (beside it on wide tiles, below it on tall ones), else in a
 * straight row, else the dial alone.
 */
private fun dialLayout(w: Float, h: Float, count: Int): DialLayout {
    val pad = 6f
    val cw = w - 2 * pad
    val ch = h - 2 * pad
    val beside = cw >= ch
    // "main" runs from the dial towards its satellites, "cross" is the other axis.
    val main = if (beside) cw else ch
    val cross = if (beside) ch else cw
    val at: (Float, Float) -> Offset = { m, c -> if (beside) Offset(pad + m, pad + c) else Offset(pad + c, pad + m) }
    if (count > 0) {
        var dial = min(cross, main * 0.64f)
        var gap = max(8f, dial * 0.04f)
        var bubble = minOf(dial * 0.32f, (cross - (count - 1) * gap) / count, main - dial - gap, 132f)
        if (bubble >= 60f) {
            // Every satellite sits on one circle round the dial, so none can touch it.
            val orbit = dial / 2 + gap + bubble / 2
            val dm = (main - (dial + gap + bubble)) / 2 + dial / 2
            val dc = cross / 2
            val sats = (0 until count).map { i ->
                val dcI = (i - (count - 1) / 2f) * (bubble + gap)
                at(dm + sqrt(orbit * orbit - dcI * dcI), dc + dcI)
            }
            return DialLayout(dial, at(dm, dc), bubble, sats, orbit)
        }
        dial = min(cross, main * 0.5f)
        gap = 10f
        bubble = minOf(dial * 0.8f, (main - dial - count * gap) / count, cross, 120f)
        if (bubble >= 56f) {
            val dm = (main - (dial + count * (gap + bubble))) / 2 + dial / 2
            val dc = cross / 2
            val sats = (0 until count).map { i -> at(dm + dial / 2 + gap + bubble / 2 + i * (gap + bubble), dc) }
            return DialLayout(dial, at(dm, dc), bubble, sats)
        }
    }
    return DialLayout(min(cw, ch), Offset(w / 2, h / 2))
}

/** One satellite reading: ring [fraction], its colour, the value and the label under it. */
private class Satellite(val fraction: Float, val color: Color, val value: String, val label: String)

/**
 * Speed dial with the RPM arc; when the tile has room, coolant, battery and
 * engine-load bubbles orbit it. Disconnected, it rests at zero showing "--"
 * and a tap connects OBD.
 */
@Composable
private fun OrbitTelemetry(env: SkinTileEnv) {
    val idle = env.obdConnection.isIdle
    // Whatever the car answers is shown, adapter or not; the rest reads "--".
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val connected = env.obdConnection == ObdConnectionState.CONNECTED || g.any
    val warn = speedOver(g.speedKmh)
    val teal = DashColors.Secondary
    val units = LocalUnits.current
    val coolantC = g.coolantTempC ?: 0
    val volts = g.voltage ?: 0.0
    val load = g.engineLoadPct ?: 0
    val satellites = listOf(
        Satellite(
            (coolantC - 40) / 90f,
            if (coolantC >= 105) DashColors.Warning else teal,
            g.coolantTempC?.let { "${units.temp(it)}°" } ?: "--",
            stringResource(R.string.orbit_sat_coolant)
        ),
        Satellite(
            batteryFraction(volts),
            batteryColor(volts),
            if (volts > 0.0) "%.1fV".format(volts) else "--",
            stringResource(R.string.orbit_sat_battery)
        ),
        Satellite(
            load / 100f, DashColors.Accent2, g.engineLoadPct?.let { "$it%" } ?: "--",
            stringResource(R.string.orbit_sat_load)
        )
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Kept across OBD samples, so the orbit path's cached drawing is too.
        val layout = remember(maxWidth, maxHeight, satellites.size) { dialLayout(maxWidth.value, maxHeight.value, satellites.size) }
        if (layout.orbit > 0f) OrbitPathThrough(layout)
        val d = layout.dial
        OrbitDial(
            diameter = d,
            speedFraction = g.speedKmh?.let { it / DIAL_MAX_KMH } ?: 0f,
            // Null with no revs: the dial then shows its plain dashed orbit, not an empty rev track.
            outerFraction = g.rpm?.let { it / SKIN_RPM_MAX },
            active = connected,
            warn = warn,
            onClick = if (idle && !env.editing) env.onConnectObd else null,
            modifier = Modifier.offset((layout.center.x - d / 2).dp, (layout.center.y - d / 2).dp)
        ) {
            OrbitText(
                g.speedKmh?.let { "${units.speed(it)}" } ?: "--",
                d * 0.30f,
                when {
                    warn -> DashColors.Warning
                    g.speedKmh != null -> DashColors.TextPrimary
                    else -> DashColors.Muted
                },
                weight = FontWeight.SemiBold,
                tight = true
            )
            OrbitText(units.speedUnit, max(14f, d * 0.044f), DashColors.Muted)
            Spacer(Modifier.height((d * 0.025f).dp))
            OrbitText(
                when {
                    g.rpm != null -> stringResource(R.string.orbit_rpm_value, groupThousands(g.rpm))
                    connected -> ""
                    idle -> stringResource(R.string.orbit_tap_to_connect)
                    else -> stringResource(R.string.orbit_connecting)
                },
                max(14f, d * 0.042f),
                when {
                    connected -> teal
                    idle -> DashColors.Accent
                    else -> DashColors.Muted
                },
                weight = FontWeight.SemiBold
            )
        }
        val b = layout.bubble
        layout.satellites.forEachIndexed { i, c ->
            val s = satellites[i]
            OrbitGaugeBubble(
                size = b,
                fraction = s.fraction,
                color = s.color,
                value = s.value,
                label = s.label,
                dimmed = s.value == "--",
                modifier = Modifier.offset((c.x - b / 2).dp, (c.y - b / 2).dp)
            )
        }
    }
}

/** Faint dashed orbit arc through the satellites of [layout], with a small dot riding its far end. */
@Composable
private fun OrbitPathThrough(layout: DialLayout) {
    val teal = DashColors.Secondary
    val line = mist(0.10f)
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val c = Offset(layout.center.x.dp.toPx(), layout.center.y.dp.toPx())
                val r = layout.orbit.dp.toPx()
                val angles = layout.satellites.map {
                    Math.toDegrees(atan2(it.y - layout.center.y, it.x - layout.center.x).toDouble()).toFloat()
                }
                val start = (angles.minOrNull() ?: 0f) - 12f
                val sweep = (angles.maxOrNull() ?: 0f) + 12f - start
                val dash = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx())))
                val end = Math.toRadians((start + sweep).toDouble())
                val dot = Offset(c.x + (cos(end) * r).toFloat(), c.y + (sin(end) * r).toFloat())
                // The orbit stops at each bubble's rim instead of showing through its fill.
                val holes = Path().apply {
                    layout.satellites.forEach { addOval(Rect(Offset(it.x.dp.toPx(), it.y.dp.toPx()), (layout.bubble / 2f).dp.toPx())) }
                }
                onDrawBehind {
                    clipPath(holes, ClipOp.Difference) {
                        drawArc(line, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = dash)
                    }
                    drawCircle(teal.copy(alpha = 0.25f), 6.dp.toPx(), dot)
                    drawCircle(teal, 2.5.dp.toPx(), dot)
                }
            }
    )
}

/**
 * The Orbit speed dial, [diameter] dp across: a thick 270° track with a
 * coral→pink value arc and a soft glow, fine ticks inside, a knob at the arc
 * end, and a thin outer arc (RPM in teal, or a plain dashed orbit when
 * [outerFraction] is null). By day it sits on a frosted white face with a
 * light ink track and a white knob. [content] is centred on it.
 */
@Composable
private fun OrbitDial(
    diameter: Float,
    speedFraction: Float,
    outerFraction: Float?,
    active: Boolean,
    warn: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val coral = DashColors.Accent
    val warning = DashColors.Warning
    val teal = DashColors.Secondary
    val light = DashColors.Light
    val knobFill = if (light) Color.White else DashColors.TextPrimary
    val muted = DashColors.Muted
    // Tweened only on the page shown: the revs come several times a second, and a neighbouring
    // page the pager keeps composed would redraw its dial at frame rate for nobody.
    val onPage = LocalPageActive.current
    val value = if (onPage) animateFloatAsState(speedFraction.coerceIn(0f, 1f), tween(500), label = "dial speed") else rememberUpdatedState(speedFraction.coerceIn(0f, 1f))
    val outer = if (onPage) animateFloatAsState((outerFraction ?: 0f).coerceIn(0f, 1f), tween(500), label = "dial rpm") else rememberUpdatedState((outerFraction ?: 0f).coerceIn(0f, 1f))
    val showOuter = outerFraction != null
    val connectLabel = stringResource(R.string.orbit_connect_obd)
    Box(
        modifier = modifier
            .size(diameter.dp)
            .orbitShadow()
            .then(
                if (onClick != null) {
                    Modifier.clip(CircleShape).clickable(onClickLabel = connectLabel, role = Role.Button, onClick = onClick)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Its own layer under the numerals: the needle's swing redraws the dial alone.
        Spacer(
            Modifier.matchParentSize().graphicsLayer().drawWithCache {
                // Geometry from the 340-unit mock, scaled to the dial.
                val s = size.minDimension / 340f
                val c = size.center
                val rOut = 160f * s
                val rTrack = 138f * s
                val outTopLeft = Offset(c.x - rOut, c.y - rOut)
                val outSize = Size(rOut * 2, rOut * 2)
                val trackTopLeft = Offset(c.x - rTrack, c.y - rTrack)
                val trackSize = Size(rTrack * 2, rTrack * 2)
                val thin = Stroke(6f * s, cap = StrokeCap.Round)
                val thick = Stroke(18f * s, cap = StrokeCap.Round)
                val haloWide = Stroke(34f * s, cap = StrokeCap.Round)
                val haloNarrow = Stroke(26f * s, cap = StrokeCap.Round)
                val knobRing = Stroke(4f * s)
                val orbitDash = Stroke(1.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * s, 9f * s)))
                // Coral → pink along the arc (drawn rotated so 0° is the arc start); the wrap back
                // to coral keeps the start cap coral.
                val sweepBrush = Brush.sweepGradient(0f to coral, 0.75f to OrbitPink, 1f to coral, center = c)
                val ticks = FloatArray(41 * 4)
                for (i in 0..40) {
                    val a = Math.toRadians((DIAL_START + DIAL_SWEEP * i / 40f).toDouble())
                    val r1 = 120f * s
                    val r2 = r1 - (if (i % 5 == 0) 9f else 5f) * s
                    ticks[i * 4] = c.x + (cos(a) * r1).toFloat()
                    ticks[i * 4 + 1] = c.y + (sin(a) * r1).toFloat()
                    ticks[i * 4 + 2] = c.x + (cos(a) * r2).toFloat()
                    ticks[i * 4 + 3] = c.y + (sin(a) * r2).toFloat()
                }
                val rim = Stroke(1.dp.toPx())
                val hairline = mist(0.10f)
                val outerTrack = mist(0.07f)
                val track = mist(0.08f)
                val majorTick = mist(0.35f)
                val minorTick = mist(0.15f)
                onDrawBehind {
                    if (light) frostedFace(size.minDimension / 2f, rim, hairline)
                    if (showOuter) {
                        drawArc(outerTrack, DIAL_START, DIAL_SWEEP, false, outTopLeft, outSize, style = thin)
                        val o = outer.value
                        if (active && o > 0.003f) drawArc(teal, DIAL_START, DIAL_SWEEP * o, false, outTopLeft, outSize, style = thin)
                    } else {
                        drawCircle(hairline, rOut, c, style = orbitDash)
                    }
                    drawArc(track, DIAL_START, DIAL_SWEEP, false, trackTopLeft, trackSize, style = thick)
                    val f = if (active) value.value else 0f
                    val arcColor = if (warn) warning else coral
                    if (f > 0.003f) {
                        val sweep = DIAL_SWEEP * f
                        // Soft glow: two wider, fainter passes under the value arc.
                        drawArc(arcColor.copy(alpha = 0.09f), DIAL_START, sweep, false, trackTopLeft, trackSize, style = haloWide)
                        drawArc(arcColor.copy(alpha = 0.16f), DIAL_START, sweep, false, trackTopLeft, trackSize, style = haloNarrow)
                        if (warn) {
                            drawArc(warning, DIAL_START, sweep, false, trackTopLeft, trackSize, style = thick)
                        } else {
                            rotate(DIAL_START, c) {
                                drawArc(sweepBrush, 0f, sweep, false, trackTopLeft, trackSize, style = thick)
                            }
                        }
                    }
                    for (i in 0..40) {
                        val major = i % 5 == 0
                        drawLine(
                            color = if (major) majorTick else minorTick,
                            start = Offset(ticks[i * 4], ticks[i * 4 + 1]),
                            end = Offset(ticks[i * 4 + 2], ticks[i * 4 + 3]),
                            strokeWidth = (if (major) 2f else 1.5f) * s,
                            cap = StrokeCap.Round
                        )
                    }
                    val ka = Math.toRadians((DIAL_START + DIAL_SWEEP * f).toDouble())
                    val knob = Offset(c.x + (cos(ka) * rTrack).toFloat(), c.y + (sin(ka) * rTrack).toFloat())
                    if (active) {
                        drawCircle(arcColor.copy(alpha = 0.25f), 17f * s, knob)
                        drawCircle(knobFill, 11f * s, knob)
                        drawCircle(arcColor, 11f * s, knob, style = knobRing)
                    } else {
                        drawCircle(muted.copy(alpha = 0.55f), 9f * s, knob)
                    }
                }
            }
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/**
 * Small ring gauge: a faint disc (frosted white by day), a [fraction] arc from
 * the top in [color], the value and a label inside.
 */
@Composable
private fun OrbitGaugeBubble(
    size: Float,
    fraction: Float,
    color: Color,
    value: String,
    label: String,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val level = if (LocalPageActive.current) animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "satellite") else rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val light = DashColors.Light
    Box(
        modifier = modifier
            .size(size.dp)
            .orbitShadow()
            .drawWithCache {
                val d = this.size.minDimension
                val stroke = d * 4f / 84f
                val r = d * 37f / 84f
                val topLeft = this.size.center.let { Offset(it.x - r, it.y - r) }
                val arcSize = Size(r * 2, r * 2)
                val ring = Stroke(stroke, cap = StrokeCap.Round)
                val rim = Stroke(1.dp.toPx())
                val hairline = mist(0.10f)
                val track = mist(0.08f)
                onDrawBehind {
                    if (light) frostedFace(d / 2f, rim, hairline) else drawCircle(white(0.03f), d / 2f)
                    drawCircle(track, r, style = ring)
                    val f = level.value
                    if (!dimmed && f > 0.003f) drawArc(color, -90f, 360f * f, false, topLeft, arcSize, style = ring)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val ink = if (dimmed) DashColors.Muted else DashColors.TextPrimary
            OrbitText(value, max(14f, size * 0.226f), ink, weight = FontWeight.SemiBold, tight = true)
            OrbitText(label, max(14f, size * 0.143f), DashColors.Muted)
        }
    }
}

/** The dial with speed only (OBD, else GPS) and its source under it; with no signal a tap connects OBD. */
@Composable
private fun OrbitSpeedHud(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val idle = env.obdConnection.isIdle
    val warn = speedOver(speed)
    val units = LocalUnits.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = min(maxWidth.value, maxHeight.value) - 8f
        OrbitDial(
            diameter = d,
            speedFraction = (speed ?: 0) / DIAL_MAX_KMH,
            outerFraction = null,
            active = speed != null,
            warn = warn,
            onClick = if (speed == null && idle && !env.editing) env.onConnectObd else null
        ) {
            OrbitText(
                speed?.let { units.speed(it).toString() } ?: "--",
                d * 0.30f,
                when {
                    speed == null -> DashColors.Muted
                    warn -> DashColors.Warning
                    else -> DashColors.TextPrimary
                },
                weight = FontWeight.SemiBold,
                tight = true
            )
            OrbitText(units.speedUnit, max(14f, d * 0.044f), DashColors.Muted)
            Spacer(Modifier.height((d * 0.025f).dp))
            OrbitText(
                speedSource(stringResource(R.string.info_speed_no_signal)),
                max(14f, d * 0.042f),
                if (speed != null) DashColors.Secondary else DashColors.Muted,
                weight = FontWeight.SemiBold
            )
        }
    }
}

// --- Media ---------------------------------------------------------------------------

/**
 * Vinyl deck beside (wide tiles) or above (tall tiles) the track info and
 * round controls. The record hides when the tile is too small for it. Without
 * notification access a tap anywhere opens the access settings.
 */
@Composable
private fun OrbitMedia(env: SkinTileEnv) {
    val access = env.hasMediaAccess
    val context = env.context
    val grant = { CarMediaController.openNotificationAccessSettings(context) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !access && !env.editing, onClick = grant),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val k = (min(w, h) / 260f).coerceIn(1f, 1.45f)
        if (w >= h * 1.2f) {
            val deck = minOf(h - 8f, w * 0.55f, w - 196f * k - 12f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (deck >= 90f) {
                    Spacer(Modifier.width(4.dp))
                    OrbitRecordDeck(deck, env.mediaState, env.mediaController)
                    Spacer(Modifier.width(8.dp))
                }
                OrbitMediaInfo(env, k, grant, Modifier.weight(1f))
            }
        } else {
            val deck = min(w - 8f, h - 150f * k - 8f)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (deck >= 90f) {
                    OrbitRecordDeck(deck, env.mediaState, env.mediaController)
                    Spacer(Modifier.height(4.dp))
                }
                OrbitMediaInfo(env, k, grant, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The record deck in a [size] dp square: a near-black disc with grooves and
 * two sheen wedges that spins while playing (album art as its label), a static
 * coral progress ring hugging it, and a tonearm that rests on the record while
 * playing and swings off it when paused. By day the record stays black and the
 * tonearm turns on a pale base.
 */
@Composable
private fun OrbitRecordDeck(size: Float, state: MediaState, controller: CarMediaController) {
    val spin = rememberSpin(7_000, state.isPlaying)
    val arm = animateFloatAsState(if (state.isPlaying) 0f else -18f, tween(700, easing = FastOutSlowInEasing), label = "tonearm")
    // Record radius and centre inside the square; the tonearm pivot takes the top-right corner.
    val r = size * 0.42f
    val cx = size * 0.47f
    val cy = size * 0.53f
    val art = remember(state.artwork) { state.artwork?.asImageBitmap() }
    val labelBrush = Brush.linearGradient(listOf(DashColors.Accent, DashColors.Accent2))
    val base = OrbitArmBase
    val baseRimColor = mist(0.18f)
    val chrome = OrbitArm
    Box(Modifier.size(size.dp)) {
        OrbitProgressRing(state, controller, Offset(cx, cy), r, Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .offset((cx - r).dp, (cy - r).dp)
                .size((2 * r).dp)
                .graphicsLayer { rotationZ = spin.value }
                .drawWithCache {
                    val rr = this.size.minDimension / 2f
                    val hairline = Stroke(1f)
                    val grooves = FloatArray(13) { rr * (128f - it * 6f) / 135f }
                    val wedge = rr * 132f / 135f
                    val wedgeTopLeft = this.size.center.let { Offset(it.x - wedge, it.y - wedge) }
                    val wedgeSize = Size(wedge * 2, wedge * 2)
                    onDrawBehind {
                        drawCircle(OrbitVinyl, rr)
                        for (g in grooves) drawCircle(white(0.05f), g, style = hairline)
                        drawArc(white(0.05f), -72f, 32f, true, wedgeTopLeft, wedgeSize)
                        drawArc(white(0.05f), 108f, 32f, true, wedgeTopLeft, wedgeSize)
                        drawCircle(white(0.08f), rr - 0.5f, style = hairline)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((r * 0.68f).dp)
                    .clip(CircleShape)
                    .background(labelBrush),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = OrbitInk, modifier = Modifier.size((r * 0.3f).dp))
                }
            }
            Box(
                Modifier
                    .size(max(3f, r * 0.074f).dp)
                    .clip(CircleShape)
                    .background(if (DashColors.Light) Color.White else DashColors.TextPrimary)
            )
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    // Tonearm from the 135-unit record of the mock: pivot up and right of the record.
                    val u = r.dp.toPx() / 135f
                    val pivot = Offset(cx.dp.toPx() + 155f * u, cy.dp.toPx() - 140f * u)
                    val armPath = Path().apply {
                        moveTo(pivot.x, pivot.y)
                        lineTo(pivot.x - 10f * u, pivot.y + 98f * u)
                        lineTo(pivot.x - 38f * u, pivot.y + 138f * u)
                    }
                    val head = Path().apply {
                        moveTo(pivot.x - 47f * u, pivot.y + 130f * u)
                        lineTo(pivot.x - 31f * u, pivot.y + 144f * u)
                        lineTo(pivot.x - 39f * u, pivot.y + 156f * u)
                        lineTo(pivot.x - 55f * u, pivot.y + 142f * u)
                        close()
                    }
                    val armStroke = Stroke(5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val baseRim = Stroke(2f * u)
                    onDrawBehind {
                        drawCircle(base, 15f * u, pivot)
                        drawCircle(baseRimColor, 15f * u, pivot, style = baseRim)
                        rotate(arm.value, pivot) {
                            drawPath(armPath, chrome, style = armStroke)
                            drawPath(head, chrome)
                        }
                        drawCircle(chrome, 5f * u, pivot)
                    }
                }
        )
    }
}

/**
 * Soft shadow under the record (ink and lighter by day) and the static coral
 * progress ring around it ([center] and [r] in dp). Kept apart so only it
 * follows the playback position.
 */
@Composable
private fun OrbitProgressRing(state: MediaState, controller: CarMediaController, center: Offset, r: Float, modifier: Modifier) {
    // Read while drawing, so a new position only redraws the cached ring.
    val fraction = rememberUpdatedState(rememberMediaFraction(state, controller))
    val coral = DashColors.Accent
    val shadow = if (DashColors.Light) DashColors.TextPrimary.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.55f)
    val track = mist(0.06f)
    Spacer(
        modifier.cachedDraw(center, r, coral, shadow, track) {
            val c = Offset(center.x.dp.toPx(), center.y.dp.toPx())
            val rec = r.dp.toPx()
            val ring = rec * 146f / 135f
            val width = max(2.dp.toPx(), rec * 0.028f)
            val shadowCenter = Offset(c.x, c.y + rec * 0.12f)
            val shadowBrush = Brush.radialGradient(listOf(shadow, Color.Transparent), shadowCenter, rec * 1.2f)
            val trackStroke = Stroke(width)
            val arcStroke = Stroke(width, cap = StrokeCap.Round)
            val arcTopLeft = Offset(c.x - ring, c.y - ring)
            val arcSize = Size(ring * 2, ring * 2)
            onDrawBehind {
                drawCircle(shadowBrush, rec * 1.2f, shadowCenter)
                drawCircle(track, ring, c, style = trackStroke)
                val f = fraction.value
                if (f > 0f) drawArc(coral, -90f, 360f * f, false, arcTopLeft, arcSize, style = arcStroke)
            }
        }
    )
}

/** Title, artist and "2:14 / 4:03" over the round transport controls ([k] scales them on big tiles). */
@Composable
private fun OrbitMediaInfo(env: SkinTileEnv, k: Float, onGrant: () -> Unit, modifier: Modifier) {
    val state = env.mediaState
    val access = env.hasMediaAccess
    val controller = env.mediaController
    val title = when {
        !access -> stringResource(R.string.info_media_access_needed)
        state.hasMedia && state.title.isNotBlank() -> state.title
        else -> stringResource(R.string.info_nothing_playing)
    }
    val subtitle = when {
        !access -> stringResource(R.string.orbit_tap_to_enable)
        state.hasMedia -> state.artist
        else -> stringResource(R.string.orbit_start_music)
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OrbitText(title, 19f * k, DashColors.TextPrimary, Modifier.padding(horizontal = 6.dp), weight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            OrbitText(subtitle, max(14f, 13f * k), DashColors.Muted, Modifier.padding(horizontal = 6.dp))
        }
        if (access && state.durationMs > 0L) {
            Spacer(Modifier.height(2.dp))
            OrbitTrackTime(state, controller, max(14f, 12.5f * k))
        }
        Spacer(Modifier.height((12f * k).dp))
        if (access) {
            val playing = state.isPlaying
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((12f * k).dp)
            ) {
                OrbitRoundButton(
                    Icons.Filled.SkipPrevious, stringResource(R.string.orbit_previous_track), 48f * k, 22f * k,
                    frost(0.07f), DashColors.TextPrimary, !env.editing, bubble = true
                ) { controller.previous() }
                OrbitRoundButton(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.orbit_pause else R.string.orbit_play),
                    62f * k, 30f * k, DashColors.Accent, OrbitInk, !env.editing, glow = DashColors.Accent
                ) { controller.playPause() }
                OrbitRoundButton(
                    Icons.Filled.SkipNext, stringResource(R.string.orbit_next_track), 48f * k, 22f * k,
                    frost(0.07f), DashColors.TextPrimary, !env.editing, bubble = true
                ) { controller.next() }
            }
        } else {
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(shape)
                    .background(DashColors.Accent)
                    .clickable(enabled = !env.editing, role = Role.Button, onClick = onGrant)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                OrbitText(stringResource(R.string.orbit_grant_access), 15f, OrbitInk, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** "2:14 / 4:03", in its own scope so only this line follows the playback position. */
@Composable
private fun OrbitTrackTime(state: MediaState, controller: CarMediaController, size: Float) {
    val positionMs = rememberMediaPosition(state, controller)
    OrbitText("${formatTrackTime(positionMs)} / ${formatTrackTime(state.durationMs)}", size, DashColors.Muted)
}

/**
 * Round control, at least 48 dp: [fill] disc, centred icon, and an optional
 * soft [glow] around it (fainter by day). A neutral [bubble] gains an ink
 * hairline and a drop shadow by day.
 */
@Composable
private fun OrbitRoundButton(
    icon: ImageVector,
    description: String,
    size: Float,
    iconSize: Float,
    fill: Color,
    tint: Color,
    enabled: Boolean,
    glow: Color? = null,
    bubble: Boolean = false,
    onClick: () -> Unit
) {
    val light = DashColors.Light
    val glowAlpha = if (light) 0.32f else 0.5f
    Box(
        modifier = Modifier
            .size(max(size, 48f).dp)
            .then(
                if (glow != null) {
                    Modifier.drawWithCache {
                        val r = this.size.minDimension * 0.85f
                        val halo = Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent), this.size.center, r)
                        onDrawBehind { drawCircle(halo, r) }
                    }
                } else Modifier
            )
            .then(if (bubble) Modifier.orbitShadow() else Modifier)
            .clip(CircleShape)
            .background(fill)
            .then(if (bubble && light) Modifier.border(1.dp, mist(0.10f), CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

// --- Navigation ----------------------------------------------------------------------

/**
 * Next turn from Google Maps / Waze: a coral bubble with the manoeuvre glyph
 * and distance, the instruction and ETA pills beside (or under) it. With no
 * route a dim dashed circle says so; a tap opens the navigation app, or the
 * notification access settings when directions cannot be read yet.
 */
@Composable
private fun OrbitNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val access = env.hasMediaAccess
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !env.editing) {
                if (access) openNavigationApp(context, nav) else CarMediaController.openNotificationAccessSettings(context)
            },
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val wide = w >= h * 1.3f
        val bubble = if (wide) minOf(h * 0.66f, w * 0.36f, 260f) else minOf(w * 0.62f, h * 0.5f, 260f)
        if (access && nav.active) OrbitRoute(nav, wide, bubble, w) else OrbitNoRoute(access, wide, bubble)
    }
}

/** Active route in a [width] dp tile: turn bubble plus instruction and ETA pills, side by side or stacked. */
@Composable
private fun OrbitRoute(nav: NavState, wide: Boolean, bubble: Float, width: Float) {
    val instructionSize = (bubble * 0.14f).coerceIn(15f, 28f)
    val pillSize = (bubble * 0.1f).coerceIn(14f, 20f)
    val textWidth = if (wide) width - bubble * 1.44f - 18f else width - 16f
    val chips = fittingChips(nav.etaParts.take(3), pillSize, textWidth)
    if (wide) {
        // Padded by the glow's reach so it never meets the tile edge; the group is centred.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = (bubble * 0.22f).dp, end = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OrbitTurnBubble(nav, bubble)
            Spacer(Modifier.width((bubble * 0.22f + 10f).dp))
            Column(Modifier.weight(1f, fill = false)) {
                OrbitText(
                    nav.instruction, instructionSize, DashColors.TextPrimary,
                    weight = FontWeight.SemiBold, maxLines = 3, align = TextAlign.Start
                )
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { OrbitPill(it, pillSize, Modifier.weight(1f, fill = false)) }
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OrbitTurnBubble(nav, bubble)
            Spacer(Modifier.height((bubble * 0.14f + 6f).dp))
            OrbitText(
                nav.instruction, instructionSize, DashColors.TextPrimary, Modifier.padding(horizontal = 8.dp),
                weight = FontWeight.SemiBold, maxLines = 2
            )
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    chips.forEach { OrbitPill(it, pillSize, Modifier.weight(1f, fill = false)) }
                }
            }
        }
    }
}

/**
 * Coral bubble [d] dp across with a halo and glow (softer by day): the
 * manoeuvre glyph over the distance, in dark ink at night and white by day.
 */
@Composable
private fun OrbitTurnBubble(nav: NavState, d: Float) {
    val coral = DashColors.Accent
    val glowAlpha = if (DashColors.Light) 0.3f else 0.42f
    val glyph = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val hasDistance = nav.distance.isNotEmpty()
    val glyphSize = d * if (hasDistance) 0.3f else 0.46f
    Box(
        modifier = Modifier
            .size(d.dp)
            .drawWithCache {
                val r = size.minDimension / 2f
                val glow = Brush.radialGradient(listOf(coral.copy(alpha = glowAlpha), Color.Transparent), size.center, r * 1.45f)
                onDrawBehind {
                    drawCircle(glow, r * 1.45f)
                    drawCircle(coral.copy(alpha = 0.14f), r * 1.13f)
                }
            }
            .clip(CircleShape)
            .background(coral),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (glyph != null) {
                Image(
                    bitmap = glyph,
                    contentDescription = nav.instruction,
                    modifier = Modifier.size(glyphSize.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(OrbitInk, BlendMode.SrcIn)
                )
            } else {
                Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = OrbitInk, modifier = Modifier.size(glyphSize.dp))
            }
            if (hasDistance) OrbitText(nav.distance, d * 0.19f, OrbitInk, weight = FontWeight.Bold, tight = true)
        }
    }
}

/**
 * The leading ETA segments whose pills fit side by side in [width] dp at text
 * [size] (a rough estimate), so narrow tiles drop a pill instead of squeezing
 * all of them into ellipses. Always keeps the first.
 */
private fun fittingChips(chips: List<String>, size: Float, width: Float): List<String> {
    var used = 0f
    return chips.takeWhile { chip ->
        used += chip.length * size * 0.56f + size * 1.7f + 6f
        used <= width
    }.ifEmpty { chips.take(1) }
}

/** Round-ended ETA pill ("12 min", "6.4 km", "20:58"); frosted white with an ink hairline by day. */
@Composable
private fun OrbitPill(text: String, size: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(frost(0.06f))
            .border(1.dp, mist(0.10f), shape)
            .padding(horizontal = (size * 0.85f).dp, vertical = (size * 0.4f).dp)
    ) {
        OrbitText(text, size, DashColors.TextPrimary, weight = FontWeight.Medium)
    }
}

/**
 * Calm idle state: a dim dashed circle (a faint frosted disc by day, with no
 * shadow) with a navigation icon, and what a tap will do.
 */
@Composable
private fun OrbitNoRoute(access: Boolean, wide: Boolean, bubble: Float) {
    val muted = DashColors.Muted
    val ink = DashColors.TextPrimary.copy(alpha = 0.8f)
    val disc = if (DashColors.Light) OrbitFrost.copy(alpha = 0.5f) else white(0.03f)
    val dashColor = mist(0.18f)
    val title = stringResource(if (access) R.string.info_directions_no_route else R.string.info_directions_access_title)
    val hint = stringResource(if (access) R.string.orbit_tap_open_maps else R.string.orbit_tap_allow_notifications)
    val d = bubble * 0.8f
    val titleSize = (d * 0.16f).coerceIn(14f, 26f)
    val hintSize = max(14f, titleSize * 0.72f)
    val ring: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(d.dp)
                .drawWithCache {
                    val r = size.minDimension / 2f - 1.dp.toPx()
                    val dash = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())))
                    val dot = size.center.let { Offset(it.x + r * 0.7071f, it.y - r * 0.7071f) }
                    onDrawBehind {
                        drawCircle(disc, r)
                        drawCircle(dashColor, r, style = dash)
                        drawCircle(muted, 3.dp.toPx(), dot)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (access) Icons.Filled.Navigation else Icons.Filled.Directions,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size((d * 0.34f).dp)
            )
        }
    }
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            ring()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                OrbitText(title, titleSize, ink, weight = FontWeight.SemiBold, maxLines = 2, align = TextAlign.Start)
                OrbitText(hint, hintSize, muted, maxLines = 2, align = TextAlign.Start)
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            ring()
            Spacer(Modifier.height(8.dp))
            OrbitText(title, titleSize, ink, weight = FontWeight.SemiBold, maxLines = 2)
            OrbitText(hint, hintSize, muted, maxLines = 2)
        }
    }
}

// --- Clock ---------------------------------------------------------------------------

/**
 * Ring clock: a thin ring with a teal seconds arc sweeping round it, the time
 * big in the middle and the date under it. Very wide tiles move the weekday
 * and date beside the ring. A tap opens the clock app.
 */
@Composable
private fun OrbitClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    // Digits in the ring; a 12-hour clock's AM / PM goes on the small line under them.
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val amPm = units.amPm(now)
    val shortDate = rememberDateFormat("EEEdMMM", best = true)
    val dayFmt = rememberDateFormat("EEEE")
    val longDate = rememberDateFormat("dMMMM", best = true)
    // Progress through the current minute, read only while drawing: four steps a
    // second with full effects (1.5° each, still a sweep), one a second otherwise.
    val wall = rememberWallClock(if (DashColors.Effects == DashEffects.FULL) 250L else 1_000L)
    val seconds = remember(wall) { { (wall.longValue % 60_000L) / 60_000f } }
    val context = env.context
    val open = { openClockApp(context) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth.value
        val h = maxHeight.value
        if (w >= h * 2f) {
            val d = min(h - 8f, w * 0.45f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitClockRing(d, timeFmt.format(now), amPm, seconds, !env.editing, open)
                Spacer(Modifier.width((16f + d * 0.08f).dp))
                Column {
                    OrbitText(
                        dayFmt.format(now).replaceFirstChar { it.uppercase() },
                        (d * 0.17f).coerceAtMost(40f), DashColors.TextPrimary, weight = FontWeight.SemiBold, align = TextAlign.Start
                    )
                    OrbitText(longDate.format(now), (d * 0.12f).coerceIn(14f, 28f), DashColors.Muted, align = TextAlign.Start)
                }
            }
        } else {
            OrbitClockRing(
                min(w, h) - 8f, timeFmt.format(now), listOfNotNull(amPm, shortDate.format(now)).joinToString(" · "),
                seconds, !env.editing, open
            )
        }
    }
}

/**
 * The ring [d] dp across with hour dots, the seconds arc and its glowing head,
 * time (and [date]) inside. By day it sits on a frosted white face filling the
 * bubble, with ink hour dots.
 */
@Composable
private fun OrbitClockRing(
    d: Float,
    time: String,
    date: String?,
    progress: () -> Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val teal = DashColors.Secondary
    val light = DashColors.Light
    val openLabel = stringResource(R.string.orbit_open_alarms)
    Box(
        modifier = Modifier
            .size(d.dp)
            .orbitShadow()
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .drawWithCache {
                val mid = size.center
                val dotR = max(3.dp.toPx(), size.minDimension * 0.016f)
                val r = size.minDimension / 2f - dotR * 2.6f
                val thin = Stroke(max(1.5.dp.toPx(), size.minDimension * 0.006f))
                val arc = Stroke(thin.width * 2f, cap = StrokeCap.Round)
                val topLeft = Offset(mid.x - r, mid.y - r)
                val arcSize = Size(r * 2, r * 2)
                val marks = FloatArray(24)
                for (i in 0 until 12) {
                    val a = i * TAU / 12f
                    marks[i * 2] = mid.x + sin(a) * r * 0.87f
                    marks[i * 2 + 1] = mid.y - cos(a) * r * 0.87f
                }
                val markR = thin.width * 0.9f
                val halo = Brush.radialGradient(
                    listOf(teal.copy(alpha = if (light) 0.35f else 0.55f), Color.Transparent), Offset.Zero, dotR * 2.6f
                )
                val rim = Stroke(1.dp.toPx())
                val hairline = mist(0.10f)
                val quarterMark = mist(0.35f)
                val hourMark = mist(0.18f)
                onDrawBehind {
                    if (light) frostedFace(size.minDimension / 2f, rim, hairline) else drawCircle(white(0.03f), r)
                    drawCircle(hairline, r, style = thin)
                    for (i in 0 until 12) {
                        val quarter = i % 3 == 0
                        val at = Offset(marks[i * 2], marks[i * 2 + 1])
                        drawCircle(if (quarter) quarterMark else hourMark, markR * if (quarter) 1.4f else 1f, at)
                    }
                    val f = progress()
                    drawArc(teal, -90f, 360f * f, false, topLeft, arcSize, style = arc)
                    val a = f * TAU - PI.toFloat() / 2f
                    translate(center.x + cos(a) * r, center.y + sin(a) * r) {
                        drawCircle(halo, dotR * 2.6f, Offset.Zero)
                        drawCircle(teal, dotR, Offset.Zero)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitText(time, d * 0.25f, DashColors.TextPrimary, weight = FontWeight.SemiBold, tight = true)
            if (date != null) {
                Spacer(Modifier.height((d * 0.02f).dp))
                OrbitText(date, max(14f, d * 0.075f), DashColors.Muted, Modifier.widthIn(max = (d * 0.7f).dp))
            }
        }
    }
}

// --- Weather -------------------------------------------------------------------------

/** Weather bubble: warm icon, big temperature and condition; wide tiles list feels / wind / range beside it. */
@Composable
private fun OrbitWeather() {
    val weather = rememberWeather()
    val error by WeatherRepo.error.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth.value
        val h = maxHeight.value
        if (weather != null && w >= h * 1.7f) {
            val d = min(h - 8f, w * 0.5f)
            val detail = (d * 0.1f).coerceIn(14f, 22f)
            val units = LocalUnits.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitWeatherBubble(d, weather, offline = false, feels = false)
                Spacer(Modifier.width((14f + d * 0.06f).dp))
                Column {
                    OrbitText(
                        stringResource(R.string.orbit_feels, units.temp(weather.feelsC)), detail, DashColors.Muted,
                        align = TextAlign.Start
                    )
                    OrbitText(
                        stringResource(if (units.imperial) R.string.units_orbit_wind_mph else R.string.orbit_wind, units.speed(weather.windKmh)),
                        detail, DashColors.Muted,
                        align = TextAlign.Start
                    )
                    if (!weather.hiC.isNaN() && !weather.loC.isNaN()) {
                        OrbitText(
                            "${units.temp(weather.loC)}° / ${units.temp(weather.hiC)}°", detail, DashColors.Muted,
                            align = TextAlign.Start
                        )
                    }
                }
            }
        } else {
            val d = min(w, h) - 8f
            OrbitWeatherBubble(d, weather, offline = weather == null && error != null, feels = d >= 170f)
        }
    }
}

/**
 * The bubble itself, [d] dp across (frosted white by day, with a warmer sun
 * glow); a muted cloud says "Loading…" (or "Offline") until the first fetch.
 */
@Composable
private fun OrbitWeatherBubble(d: Float, weather: Weather?, offline: Boolean, feels: Boolean) {
    val warm = if (weather != null) OrbitSun.copy(alpha = if (DashColors.Light) 0.16f else 0.10f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(d.dp)
            .orbitShadow()
            .clip(CircleShape)
            .background(frost(0.05f))
            .drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f - r * 0.35f)
                val glow = Brush.radialGradient(listOf(warm, Color.Transparent), c, r * 0.8f)
                onDrawBehind { drawCircle(glow, r * 0.8f, c) }
            }
            .border(1.dp, mist(0.10f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (weather != null) {
                val units = LocalUnits.current
                Icon(weatherIcon(weather.code), contentDescription = null, tint = OrbitSun, modifier = Modifier.size((d * 0.22f).dp))
                OrbitText("${units.temp(weather.tempC)}°", d * 0.22f, DashColors.TextPrimary, weight = FontWeight.SemiBold, tight = true)
                OrbitText(weather.condition, max(14f, d * 0.105f), DashColors.Muted, Modifier.widthIn(max = (d * 0.76f).dp))
                if (feels) {
                    OrbitText(
                        stringResource(
                            if (units.imperial) R.string.units_orbit_feels_wind_mph else R.string.orbit_feels_wind,
                            units.temp(weather.feelsC), units.speed(weather.windKmh)
                        ),
                        max(14f, d * 0.068f),
                        DashColors.Muted.copy(alpha = 0.8f),
                        Modifier.widthIn(max = (d * 0.72f).dp)
                    )
                }
            } else {
                Icon(
                    if (offline) Icons.Filled.CloudOff else Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = DashColors.Muted,
                    modifier = Modifier.size((d * 0.2f).dp)
                )
                OrbitText(
                    stringResource(if (offline) R.string.orbit_offline else R.string.orbit_loading),
                    max(14f, d * 0.1f), DashColors.Muted
                )
            }
        }
    }
}

// --- Fuel & range --------------------------------------------------------------------

/**
 * Liquid-fill bubble: the fluid level is the fuel share, its surface a slow
 * sine wave; range and fuel % on top. A tap opens the fuel finder to
 * recalibrate. Until fuel is known the standard tile explains how to learn it.
 */
@Composable
private fun OrbitRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    var finder by remember { mutableStateOf(false) }
    val open = { finder = true }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth.value
        val h = maxHeight.value
        if (w >= h * 1.7f) {
            val d = min(h - 8f, w * 0.5f)
            val detail = (d * 0.1f).coerceIn(14f, 22f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitFuelBubble(d, fuel, !env.editing, open)
                Spacer(Modifier.width((14f + d * 0.06f).dp))
                Column {
                    val liters = fuel.liters
                    OrbitText(
                        stringResource(R.string.orbit_to_empty), (d * 0.12f).coerceIn(14f, 26f), DashColors.TextPrimary,
                        weight = FontWeight.SemiBold, align = TextAlign.Start
                    )
                    Spacer(Modifier.height(4.dp))
                    OrbitText(stringResource(R.string.orbit_liters_in_tank, liters), detail, DashColors.Muted, align = TextAlign.Start)
                    OrbitText(stringResource(R.string.orbit_via_source, fuel.source), detail, DashColors.Muted, align = TextAlign.Start)
                    OrbitText(
                        stringResource(R.string.orbit_tap_to_recalibrate), max(14f, detail * 0.8f),
                        DashColors.Muted.copy(alpha = 0.7f), align = TextAlign.Start
                    )
                }
            }
        } else {
            OrbitFuelBubble(min(w, h) - 8f, fuel, !env.editing, open)
        }
    }
    if (finder) FuelFinderDialog(onDismiss = { finder = false })
}

/**
 * The liquid bubble [d] dp across: teal fluid (amber-red in reserve) with two
 * drifting wave layers; by day in a frosted white disc, the fuel line in a
 * deeper shade of the fluid so it holds over the pale liquid.
 */
@Composable
private fun OrbitFuelBubble(d: Float, fuel: FuelInfo, enabled: Boolean, onClick: () -> Unit) {
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val fluid = if (low) DashColors.Warning else DashColors.Secondary
    val light = DashColors.Light
    val fluidInk = if (light) lerp(fluid, DashColors.TextPrimary, 0.3f) else fluid
    val level = animateFloatAsState(fuel.percent.coerceIn(0, 100) / 100f, tween(900), label = "fuel level")
    val wave = rememberLoop(3_000)
    val recalibrateLabel = stringResource(R.string.orbit_recalibrate_fuel)
    Box(
        modifier = Modifier
            .size(d.dp)
            .orbitShadow()
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = recalibrateLabel, role = Role.Button, onClick = onClick)
            .drawWithCache {
                val dd = size.minDimension
                val amp = dd * 0.028f
                // Two wavelengths of surface (one per bubble width) closed down past the bottom,
                // so sliding it left by up to one wavelength always covers the bubble.
                val surface = Path().apply {
                    moveTo(0f, 0f)
                    val half = dd / 2f
                    var x = 0f
                    var crest = true
                    while (x < dd * 2f) {
                        quadraticTo(x + half / 2f, if (crest) -2f * amp else 2f * amp, x + half, 0f)
                        x += half
                        crest = !crest
                    }
                    lineTo(x, dd + 2f * amp)
                    lineTo(0f, dd + 2f * amp)
                    close()
                }
                onDrawBehind {
                    if (light) drawRect(OrbitFrost)
                    drawRect(fluid.copy(alpha = 0.05f))
                    val top = dd * (1f - level.value)
                    val p = wave.value
                    translate(left = -((p + 0.5f) % 1f) * dd, top = top + amp * 0.8f) { drawPath(surface, fluid.copy(alpha = 0.10f)) }
                    translate(left = -p * dd, top = top) { drawPath(surface, fluid.copy(alpha = 0.24f)) }
                }
            }
            .border(1.dp, fluid.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitText(LocalUnits.current.distanceText(fuel.rangeKm), d * 0.17f, DashColors.TextPrimary, weight = FontWeight.SemiBold, tight = true)
            OrbitText(
                stringResource(R.string.orbit_fuel_percent, fuel.percent), max(14f, d * 0.1f), fluidInk,
                weight = FontWeight.Medium
            )
        }
    }
}

// --- Apps ----------------------------------------------------------------------------

/** App shortcut as a round bubble (about 64 dp, faint fill and rim) holding the icon, label below. */
@Composable
private fun OrbitAppBubble(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth.value
        val h = maxHeight.value
        val showLabel = h >= 72f
        val bubble = min(w - 8f, h - 8f - if (showLabel) 22f else 0f).coerceIn(32f, 88f)
        Column(
            modifier = Modifier
                .orbitShadow(bubble, top = 4f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = !env.editing, role = Role.Button) { env.onLaunchApp(item.packageName) }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OrbitAppIcon(app, bubble)
            if (showLabel) {
                Spacer(Modifier.height(4.dp))
                OrbitText(appLabel(app, item.packageName), 14f, DashColors.TextSecondary, Modifier.widthIn(max = (w - 8f).dp))
            }
        }
    }
}

/**
 * The bubble behind an app icon: white 6 % fill with a white 10 % hairline ring
 * at night; by day frosted white with an ink hairline. Its drop shadow is drawn
 * by the caller, outside the item's clip.
 */
@Composable
private fun OrbitAppIcon(app: AppEntry?, bubble: Float) {
    Box(
        modifier = Modifier
            .size(bubble.dp)
            .clip(CircleShape)
            .background(frost(0.06f))
            .border(1.dp, mist(0.10f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (app != null) AppIcon(icon = app.icon, size = (bubble * 0.72f).dp)
        else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size((bubble * 0.45f).dp))
    }
}

/**
 * Launch bar as bubbles on a gentle arc (the middle ones higher) strung on a
 * faint dashed orbit, labels when the tile is tall enough, and the edit pencil
 * at the end (always live, as on the standard bar).
 */
@Composable
private fun OrbitLaunchArc(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val line = mist(0.10f)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (item.packages.isEmpty()) {
            OrbitText(
                stringResource(R.string.orbit_launch_bar_empty), 14f, DashColors.Muted,
                Modifier.weight(1f).padding(start = 8.dp), align = TextAlign.Start
            )
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val n = item.packages.size
                val w = maxWidth.value
                val h = maxHeight.value
                val slot = w / n
                val showLabels = h >= 110f
                val labelH = if (showLabels) 21f else 0f
                val arcShare = if (n >= 3) 0.4f else 0f
                val bubble = minOf(slot * 0.8f, (h - labelH - 8f) / (1f + arcShare), 72f).coerceAtLeast(28f)
                val depth = (bubble * arcShare).coerceAtMost(h - bubble - labelH - 8f).coerceAtLeast(0f)
                val top = (h - (bubble + depth + labelH)) / 2f
                val rowY = top + bubble / 2f
                if (n >= 2) {
                    Spacer(
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                // Quadratic through the bubble centres: y = rowY + depth·u², u = −1…1 across the row.
                                val x0 = (slot * 0.5f).dp.toPx()
                                val x1 = (slot * (n - 0.5f)).dp.toPx()
                                val edgeY = (rowY + depth).dp.toPx()
                                val path = Path().apply {
                                    moveTo(x0, edgeY)
                                    quadraticTo((x0 + x1) / 2f, (rowY - depth).dp.toPx(), x1, edgeY)
                                }
                                // The line stops at each bubble's rim instead of showing through its fill.
                                val holes = Path().apply {
                                    for (i in 0 until n) {
                                        val u = (2f * i + 1f - n) / (n - 1f)
                                        val c = Offset((slot * (i + 0.5f)).dp.toPx(), (rowY + depth * u * u).dp.toPx())
                                        addOval(Rect(c, (bubble / 2f).dp.toPx()))
                                    }
                                }
                                val dashes = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))
                                val dash = Stroke(1.5.dp.toPx(), pathEffect = dashes)
                                onDrawBehind {
                                    clipPath(holes, ClipOp.Difference) { drawPath(path, line, style = dash) }
                                }
                            }
                    )
                }
                item.packages.forEachIndexed { i, pkg ->
                    val app = env.appsByPackage[pkg]
                    val u = if (n == 1) 0f else (2f * i + 1f - n) / (n - 1f)
                    Column(
                        modifier = Modifier
                            .offset(x = (slot * i).dp, y = (top + depth * u * u).dp)
                            .width(slot.dp)
                            .orbitShadow(bubble)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !env.editing, role = Role.Button) { env.onLaunchApp(pkg) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OrbitAppIcon(app, bubble)
                        if (showLabels) {
                            Spacer(Modifier.height(3.dp))
                            OrbitText(appLabel(app, pkg), 14f, DashColors.TextSecondary, Modifier.padding(horizontal = 2.dp))
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, mist(0.08f), CircleShape)
                .clickable(role = Role.Button, onClick = env.onEditLaunchBar),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.orbit_edit_launch_bar), tint = DashColors.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

// --- Docked window frame -------------------------------------------------------------

/**
 * Porthole over a docked Maps window: everything outside the largest centred
 * circle is masked in the page colour, a soft vignette darkens the inside of
 * the rim, and a hairline ring with a faint halo and a dashed outer orbit
 * finish the edge. The middle stays clear so the map shows through. By day the
 * mask and vignette take the lavender page and the rings an ink tint.
 */
@Composable
internal fun OrbitWindowFrame(modifier: Modifier) {
    val bg = DashColors.Background
    val teal = DashColors.Secondary
    val haloColor = mist(0.025f)
    val orbitColor = mist(0.06f)
    val rimColor = mist(0.10f)
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val c = size.center
                val r = size.minDimension / 2f
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(c, r))
                }
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to bg.copy(alpha = 0.6f),
                    center = c,
                    radius = r
                )
                val ring = Stroke(2.dp.toPx())
                val halo = Stroke(12.dp.toPx())
                val orbitR = r + 24.dp.toPx()
                val orbitDash = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx())))
                val dot = Offset(c.x + orbitR * 0.94f, c.y - orbitR * 0.342f)
                onDrawBehind {
                    drawCircle(vignette, r, c)
                    drawPath(mask, bg)
                    drawCircle(haloColor, r + 6.dp.toPx(), c, style = halo)
                    drawCircle(orbitColor, orbitR, c, style = orbitDash)
                    drawCircle(teal.copy(alpha = 0.25f), 6.dp.toPx(), dot)
                    drawCircle(teal, 2.5.dp.toPx(), dot)
                    drawCircle(rimColor, r - 1.dp.toPx(), c, style = ring)
                }
            }
    )
}
