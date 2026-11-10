package com.openauto.dash

import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The Futuristic skin's tiles (its parts are in FuturisticSkin.kt, the car in
 * FuturisticSkinHologram.kt). Speed and a large telemetry tile sit bare on
 * the page like the mockup's ring and hologram; everything else is a
 * cut-corner HUD panel, cyan for driving and the clock, violet for music and
 * fuel. Every tile sizes its type from its own box and drops parts as it
 * shrinks, from a full screen down to a 3 x 2 cell.
 */

private const val TWO_PI = (2 * PI).toFloat()

/** The speed arc's full sweep, in km/h (the mockup's 87 lights 104 degrees of 270). */
private const val SPEED_SCALE_KMH = 220f
private const val RING_TICKS = 40

private const val EQ_LOOP_MS = 6_300
private const val EQ_BARS = 7
/** Each bar's cycles per loop (whole, so the loop is seamless): 0.7 to 1.1 s a beat, as in the mockup. */
private val EQ_CYCLES = intArrayOf(7, 9, 6, 8, 7, 9, 6)
private val EQ_PHASE = floatArrayOf(0f, 0.22f, 0.11f, 0.39f, 0.17f, 0.44f, 0.28f)
/** The bars' heights while nothing plays. */
private val EQ_REST = floatArrayOf(0.3f, 0.5f, 0.38f, 0.62f, 0.44f, 0.34f, 0.26f)

private const val CHEVRON_MS = 1_500
/** Far, middle and near chevron: their size and when each lights in the cycle (the light runs from near to far). */
private val CHEVRON_SCALE = floatArrayOf(0.62f, 0.8f, 1f)
private val CHEVRON_DELAY = floatArrayOf(0.4f, 0.2f, 0f)

private const val FUEL_SEGMENTS = 20

/** Assistant apps: their button in a launch bar or as a shortcut is the lit, pulsing one. */
private val VOICE_PACKAGES = setOf(
    GeminiLive.GEMINI_PACKAGE,
    "com.google.android.googlequicksearchbox",
    "com.google.android.apps.googleassistant",
    "com.amazon.dee.app"
)

/** Futuristic renderer for app shortcuts, launch bars and the main widgets; anything else keeps its standard renderer. */
@Composable
internal fun FuturisticTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> FutApp(item, env)
        is DashboardItem.LaunchBar -> FutLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> FutTelemetry(env)
            BuiltinKind.SPEED_HUD -> FutSpeed(env)
            BuiltinKind.MEDIA -> FutMedia(env)
            BuiltinKind.NAVIGATION -> FutNavigation(env)
            BuiltinKind.CLOCK -> FutClock(env)
            BuiltinKind.WEATHER -> FutWeather(env)
            BuiltinKind.RANGE -> FutRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/** The inner margin of a panel: tighter on a small tile. */
private fun panelPad(w: Dp, h: Dp): Dp = if (w < 260.dp || h < 150.dp) 10.dp else 16.dp

/** A bare tile (the ring, the hologram) framed by its corner brackets while the dashboard is arranged. */
@Composable
private fun Modifier.futEditFrame(editing: Boolean): Modifier =
    if (editing) futPanel(DashColors.Accent, editing = true, frame = FutFrame.BRACKETS) else this

// --- Shared parts ---------------------------------------------------------------------

/**
 * A round glowing button [size] across (48 dp at least): a hairline ring over
 * a dark glass disc with a soft halo, brighter while pressed. [filled] makes
 * it the lit cyan button of the mockup (a radial gradient from a white-hot
 * spot); [pulse] lets its halo breathe and a ring ripple out from it (the
 * assistant). [editing] dashes the ring and the button does nothing.
 */
@Composable
private fun FutRoundButton(
    description: String,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    filled: Boolean = false,
    pulse: Boolean = false,
    editing: Boolean = false,
    tone: Color = DashColors.Accent,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    val breath = if (pulse) rememberLoop(2_400) else null
    val light = DashColors.Light
    val glowK = futGlow
    Box(
        modifier = Modifier
            .size(size.coerceAtLeast(DashSize.Touch))
            .cachedDraw(tone, filled, editing, light, glowK, breath, pressed) {
                val r = this.size.minDimension / 2f - 2.dp.toPx()
                val c = this.size.center
                val disc = if (filled) {
                    Brush.radialGradient(
                        0f to (if (light) lerp(tone, Color.White, 0.35f) else Color(0xFFBFFBFF)),
                        0.35f to tone,
                        1f to (if (light) lerp(tone, Color.Black, 0.25f) else Color(0xFF0B4A66)),
                        center = Offset(c.x - r * 0.2f, c.y - r * 0.3f), radius = r * 1.3f
                    )
                } else {
                    Brush.radialGradient(
                        listOf(
                            if (light) Color.White.copy(alpha = 0.85f) else Color(0xFF0A1628).copy(alpha = 0.75f),
                            if (light) Color(0xFFEAF2F7).copy(alpha = 0.7f) else Color(0xFF050B16).copy(alpha = 0.6f)
                        ),
                        center = c, radius = r
                    )
                }
                val halo = Brush.radialGradient(listOf(tone.copy(alpha = 0.45f), Color.Transparent), c, r * 1.6f)
                val ring = Stroke(1.5.dp.toPx(), pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
                val ripple = Stroke(2.dp.toPx())
                val spread = 14.dp.toPx()
                val glowing = glowK > 0f && !light
                onDrawBehind {
                    val p = breath?.value ?: 0f
                    val down = pressed.value
                    if (glowing && (filled || down)) drawCircle(halo, r * 1.6f, c, alpha = glowK * (if (pulse) 0.55f + 0.45f * sin(p * PI.toFloat()) else 0.7f))
                    if (pulse && breath != null && glowK > 0f) {
                        drawCircle(tone.copy(alpha = 0.45f * (1f - p) * glowK), r + spread * p, c, style = ripple)
                    }
                    drawCircle(disc, r, c)
                    if (down && !filled) drawCircle(tone.copy(alpha = 0.18f), r, c)
                    drawCircle(tone.copy(alpha = if (filled || down) 1f else 0.6f), r, c, style = ring)
                }
            }
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** An app's own icon, or a grid glyph while apps load. */
@Composable
private fun FutAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(size * 0.75f))
}

/** A thin bar lit up to [fraction] in a cyan-to-violet light (or [from] alone), with a glow under the lit part. */
@Composable
private fun FutBar(fraction: Float, modifier: Modifier, from: Color = DashColors.Accent, to: Color = DashColors.Secondary, height: Dp = 6.dp) {
    val glowK = futGlow
    val light = DashColors.Light
    val f = fraction.coerceIn(0f, 1f)
    Spacer(
        modifier
            .height(height)
            // Its own layer: the bar's steps redraw the bar, not the glowing readout beside it.
            .graphicsLayer()
            .cachedDraw(f, from, to, glowK, light) {
                val track = from.copy(alpha = if (light) 0.16f else 0.12f)
                val fill = Brush.horizontalGradient(listOf(from, to), startX = 0f, endX = size.width)
                val fw = size.width * f
                val bloom = 3.dp.toPx()
                onDrawBehind {
                    drawRect(track)
                    if (fw > 0f) {
                        if (glowK > 0f && !light) {
                            drawRect(from.copy(alpha = 0.22f * glowK), Offset(0f, -bloom), Size(fw, size.height + bloom * 2f))
                        }
                        drawRect(fill, size = Size(fw, size.height))
                    }
                }
            }
    )
}

/** A small reading: its label in spaced caps over the value in wide numerals ([alert] in amber). */
@Composable
private fun FutReadout(
    label: String, value: String, alert: Boolean, labelDp: Float, valueDp: Float,
    modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier, horizontalAlignment = align) {
        FutText(label.uppercase(Locale.getDefault()), futCaps(labelDp))
        FutText(value, futNum(valueDp, if (alert) DashColors.Warning else DashColors.TextPrimary, glow = if (alert) DashColors.Warning else DashColors.Accent))
    }
}

/** A quiet state on a panel: a title and one hint, the hint in the accent when a tap does something. */
@Composable
private fun FutEmpty(title: String, hint: String, tone: Color, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(tone, editing)
            .futTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val titleDp = min(maxHeight.value * 0.22f, maxWidth.value / (title.length * 0.55f + 1f)).coerceIn(15f, 34f)
        val hintDp = (titleDp * 0.55f).coerceIn(12f, 16f)
        Column(Modifier.padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
            FutText(title, futText(titleDp, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            FutText(hint, futText(hintDp, if (onTap != null) tone else DashColors.TextSecondary), maxLines = 2, align = TextAlign.Center)
        }
    }
}

// --- Speed ----------------------------------------------------------------------------

/**
 * Speed: the ticked ring with its lit arc on the page, as in the mockup. A
 * tall tile adds the revs bar and the gear and economy boxes under it, a wide
 * one beside it; a big wide tile floats the car's hologram beside the ring.
 * With no speed, a tap connects OBD.
 */
@Composable
private fun FutSpeed(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle && !env.editing
    val noSignal = stringResource(R.string.info_speed_no_signal)
    val source = speedSource(noSignal).uppercase(Locale.getDefault())
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .futEditFrame(env.editing)
            .futTap(canConnect, stringResource(R.string.skin_futuristic_connect_obd), onClick = env.onConnectObd)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        val caption = if (speed == null && idle) stringResource(R.string.skin_futuristic_tap_connect_obd).uppercase(Locale.getDefault()) else source
        val captionColor = if (speed != null) DashColors.Good else if (idle) DashColors.Accent else DashColors.Muted
        when {
            w >= h * 1.7f && h >= 240.dp && w >= 520.dp -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val ring = min(h.value, w.value * 0.42f).dp
                FutSpeedRing(speed, caption, captionColor, ring)
                FutHologram(env, callouts = w - ring >= 460.dp, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            h >= w * 1.2f && h >= 330.dp -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val ring = min(w.value, h.value - 130f).dp
                FutSpeedRing(speed, caption, captionColor, ring)
                Spacer(Modifier.height(10.dp))
                FutDriveStats(env, Modifier.width(min(ring.value, w.value).dp))
            }
            w >= h * 1.55f && h >= 170.dp && w - h >= 200.dp -> Row(verticalAlignment = Alignment.CenterVertically) {
                FutSpeedRing(speed, caption, captionColor, h)
                Spacer(Modifier.width(16.dp))
                FutDriveStats(env, Modifier.width(min(w.value - h.value - 16f, 320f).dp))
            }
            else -> FutSpeedRing(speed, caption, captionColor, min(w.value, h.value).dp)
        }
    }
}

/**
 * The ring [size] across: forty ticks all round (those under the arc lit),
 * a 270 degree track from bottom left to bottom right with the speed's arc
 * glowing on it and a hot dot at its tip, a light pool inside; the number in
 * glowing wide numerals with the unit under it and [caption] (where the speed
 * comes from) below. Amber from the speed warning up.
 */
@Composable
private fun FutSpeedRing(speedKmh: Int?, caption: String, captionColor: Color, size: Dp) {
    val units = LocalUnits.current
    val over = speedOver(speedKmh)
    val tone = if (over) DashColors.Warning else DashColors.Accent
    val frac = animateFloatAsState(((speedKmh ?: 0) / SPEED_SCALE_KMH).coerceIn(0f, 1f), tween(450), label = "speed arc")
    val light = DashColors.Light
    val glowK = futGlow
    val core = if (light) tone else lerp(tone, Color.White, 0.6f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .fillMaxSize()
                .cachedDraw(tone, light, glowK, core, frac) {
                    val d = this.size.minDimension
                    val c = this.size.center
                    val s = d / 260f
                    val rOuter = d / 2f - 1.dp.toPx()
                    val tickLen = 11f * s
                    val tickW = max(1.dp.toPx(), rOuter * 0.021f)
                    val ix = FloatArray(RING_TICKS)
                    val iy = FloatArray(RING_TICKS)
                    val ox = FloatArray(RING_TICKS)
                    val oy = FloatArray(RING_TICKS)
                    val rel = FloatArray(RING_TICKS)
                    val ticks = Path()
                    for (k in 0 until RING_TICKS) {
                        val deg = k * 360f / RING_TICKS
                        val a = deg * PI.toFloat() / 180f
                        ix[k] = c.x + cos(a) * (rOuter - tickLen)
                        iy[k] = c.y + sin(a) * (rOuter - tickLen)
                        ox[k] = c.x + cos(a) * rOuter
                        oy[k] = c.y + sin(a) * rOuter
                        rel[k] = ((deg - 135f) % 360f + 360f) % 360f
                        ticks.moveTo(ix[k], iy[k])
                        ticks.lineTo(ox[k], oy[k])
                    }
                    val tickStroke = Stroke(tickW)
                    val arcW = 10f * s
                    val arcR = rOuter - 18f * s - arcW / 2f
                    val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
                    val arcSize = Size(arcR * 2f, arcR * 2f)
                    val track = Stroke(arcW)
                    val glowWide = Stroke(arcW + 16f * s)
                    val glowMid = Stroke(arcW + 7f * s)
                    val glowing = glowK > 0f && !light
                    val pool = Brush.radialGradient(listOf(tone.copy(alpha = 0.12f * glowK), Color.Transparent), c, arcR)
                    val dim = tone.copy(alpha = if (light) 0.35f else 0.45f)
                    val trackInk = tone.copy(alpha = if (light) 0.16f else 0.12f)
                    val tipR = max(2.dp.toPx(), 4f * s)
                    onDrawBehind {
                        val f = frac.value
                        val lit = f * 270f
                        if (glowing) drawCircle(pool, arcR, c)
                        drawPath(ticks, dim, style = tickStroke)
                        if (f > 0.002f) {
                            for (k in 0 until RING_TICKS) {
                                if (rel[k] <= lit) drawLine(tone, Offset(ix[k], iy[k]), Offset(ox[k], oy[k]), tickW)
                            }
                        }
                        drawArc(trackInk, 135f, 270f, false, arcTopLeft, arcSize, style = track)
                        if (f > 0.002f) {
                            if (glowing) {
                                drawArc(tone.copy(alpha = 0.10f * glowK), 135f, lit, false, arcTopLeft, arcSize, style = glowWide)
                                drawArc(tone.copy(alpha = 0.22f * glowK), 135f, lit, false, arcTopLeft, arcSize, style = glowMid)
                            }
                            drawArc(tone, 135f, lit, false, arcTopLeft, arcSize, style = track)
                            val a = (135f + lit) * PI.toFloat() / 180f
                            drawCircle(core, tipR, Offset(c.x + cos(a) * arcR, c.y + sin(a) * arcR))
                        }
                    }
                }
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val numDp = futFitSp("188", futNum(100f), size * 0.56f, size * 0.32f, 16f, 400f)
            if (speedKmh != null) {
                FutText(
                    units.speed(speedKmh).toString(),
                    futNum(numDp, if (over) DashColors.Warning else DashColors.TextPrimary, glow = tone),
                    overflow = TextOverflow.Clip
                )
            } else {
                FutText("–", futNum(numDp * 0.6f, DashColors.Muted), overflow = TextOverflow.Clip)
            }
            FutText(units.speedUnit.uppercase(Locale.getDefault()), futCaps((size.value * 0.055f).coerceIn(11f, 22f), tone))
            if (size >= 150.dp && caption.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                FutText(caption, futCaps((size.value * 0.042f).coerceIn(10f, 15f), captionColor), Modifier.widthIn(max = size * 0.66f), align = TextAlign.Center)
            }
        }
    }
}

/**
 * Under or beside the ring: RPM with its cyan-to-violet bar, then the gear
 * (worked out from revs and speed) and the instant consumption in two small
 * HUD boxes, cyan and violet.
 */
@Composable
private fun FutDriveStats(env: SkinTileEnv, modifier: Modifier) {
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.FUEL_FLOW)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val gear by rememberGear()
    val flow = g.fuelFlowLh
    val kmh = g.speedKmh
    val eco = if (flow != null && kmh != null && kmh >= 5) {
        units.economy(flow / kmh * 100.0)?.let { String.format(locale, "%.1f", it) } ?: "--"
    } else "--"
    BoxWithConstraints(modifier) {
        val labelDp = (maxWidth.value / 20f).coerceIn(11f, 14f)
        val valueDp = (maxWidth.value / 9f).coerceIn(18f, 30f)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FutText(stringResource(R.string.skin_futuristic_rpm).uppercase(locale), futCaps(labelDp))
                Spacer(Modifier.weight(1f))
                FutText(g.rpm?.let { groupThousands(it) } ?: "--", futNum(labelDp * 1.3f))
            }
            Spacer(Modifier.height(8.dp))
            FutBar(rememberRevScale().fraction(g.rpm), Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).futPanel(DashColors.Accent).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    FutReadout(stringResource(R.string.skin_futuristic_gear), gearGlyph(gear.gear), false, labelDp * 0.85f, valueDp)
                }
                Box(Modifier.weight(1f).futPanel(DashColors.Secondary).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column {
                        FutText(units.economyUnit.uppercase(locale), futCaps(labelDp * 0.85f))
                        FutText(eco, futNum(valueDp, lerp(DashColors.Secondary, DashColors.TextPrimary, 0.45f), glow = DashColors.Secondary))
                    }
                }
            }
        }
    }
}

// --- Telemetry ------------------------------------------------------------------------

/**
 * Telemetry. A big tile shows the car as a hologram with its readings pointed
 * out on it, the OBD speed and revs above it; a smaller one is a cyan panel:
 * the speed, the revs bar and coolant, battery and load. A tap connects OBD
 * while the link is idle; on the link's state, picks another adapter.
 */
@Composable
private fun FutTelemetry(env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        if (maxWidth >= 420.dp && maxHeight >= 250.dp) TelemetryHologram(env) else TelemetryPanel(env)
    }
}

@Composable
private fun TelemetryHologram(env: SkinTileEnv) {
    val idle = env.obdConnection.isIdle
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .futEditFrame(env.editing)
            .futTap(idle && !env.editing, stringResource(R.string.skin_futuristic_connect_obd), onClick = env.onConnectObd)
            .padding(8.dp)
    ) {
        val headH = (maxHeight * 0.2f).coerceIn(44.dp, 92.dp)
        val capsDp = (headH.value * 0.2f).coerceIn(11f, 15f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(headH), verticalAlignment = Alignment.CenterVertically) {
                TelemetrySpeed(env, headH, capsDp, Modifier.weight(1f))
                TelemetryStatus(env, capsDp)
            }
            FutHologram(env, callouts = true, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        PickAdapterTarget(env, Modifier.align(Alignment.TopEnd))
    }
}

/** The OBD speed in wide numerals with its unit, and the revs after it, all on one line. */
@Composable
private fun TelemetrySpeed(env: SkinTileEnv, height: Dp, capsDp: Float, modifier: Modifier) {
    val g = rememberPanelGauges(K.SPEED, K.RPM)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val over = speedOver(g.speedKmh)
    val numDp = futFitSp("188", futNum(100f), 200.dp, height * 0.9f, 18f, 80f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FutText(
            g.speedKmh?.let { units.speed(it).toString() } ?: "--",
            futNum(numDp, if (over) DashColors.Warning else DashColors.TextPrimary),
            overflow = TextOverflow.Clip
        )
        Spacer(Modifier.width(8.dp))
        Column {
            FutText(units.speedUnit.uppercase(locale), futCaps(capsDp, DashColors.AccentInk))
            g.rpm?.let { FutText("${groupThousands(it)} ${stringResource(R.string.skin_futuristic_rpm).uppercase(locale)}", futCaps(capsDp)) }
        }
    }
}

/** The link's state: a dot and LIVE, LINKING (flashing), the error, or what a tap does. */
@Composable
private fun TelemetryStatus(env: SkinTileEnv, capsDp: Float) {
    val state = env.obdConnection
    val color = when (state) {
        ObdConnectionState.CONNECTED -> DashColors.Good
        ObdConnectionState.CONNECTING -> DashColors.Accent
        ObdConnectionState.ERROR -> DashColors.Critical
        ObdConnectionState.DISCONNECTED -> DashColors.Accent
    }
    val text = stringResource(
        when (state) {
            ObdConnectionState.CONNECTED -> R.string.skin_futuristic_live
            ObdConnectionState.CONNECTING -> R.string.skin_futuristic_link_linking
            ObdConnectionState.ERROR -> R.string.skin_futuristic_link_error
            ObdConnectionState.DISCONNECTED -> R.string.skin_futuristic_tap_connect_obd
        }
    ).uppercase(Locale.getDefault())
    val flash = if (state == ObdConnectionState.CONNECTING) rememberLoop(700, reverse = true, status = true) else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .drawWithCache {
                    val r = size.minDimension / 2f
                    onDrawBehind {
                        if (state == ObdConnectionState.DISCONNECTED) drawCircle(color, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        else drawCircle(color, r, alpha = flash?.let { 0.35f + 0.65f * it.value } ?: 1f)
                    }
                }
        )
        Spacer(Modifier.width(8.dp))
        FutText(text, futCaps(capsDp, color, FontWeight.SemiBold), Modifier.widthIn(max = 220.dp))
    }
}

/** While the link is live, an invisible 48 dp target over its state picks another adapter, as the standard tile does. */
@Composable
private fun PickAdapterTarget(env: SkinTileEnv, modifier: Modifier) {
    if (env.obdConnection != ObdConnectionState.CONNECTED) return
    val label = stringResource(R.string.skin_futuristic_choose_adapter)
    Box(
        modifier
            .size(width = 120.dp, height = 48.dp)
            .clickable(enabled = !env.editing, role = Role.Button, onClickLabel = label, onClick = env.onPickDevice)
            .semantics { contentDescription = label }
    )
}

@Composable
private fun TelemetryPanel(env: SkinTileEnv) {
    val state = env.obdConnection
    val idle = state.isIdle
    // Whatever the car answers is shown, adapter or not; the rest reads "--".
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .futPanel(DashColors.Accent, env.editing)
            .futTap(idle && !env.editing, stringResource(R.string.skin_futuristic_connect_obd), onClick = env.onConnectObd)
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val boxH = maxHeight
        val tiny = maxHeight < 120.dp
        val wide = maxWidth >= maxHeight * 2.1f
        val capsDp = (maxHeight.value * 0.07f).coerceIn(11f, 15f)
        val volts = g.voltage
        val readouts = listOf(
            Triple(stringResource(R.string.vehicle_coolant), g.coolantTempC?.let { units.tempTight(it) } ?: "--", (g.coolantTempC ?: 0) >= COOLANT_WARNING_C),
            Triple(stringResource(R.string.info_chip_battery), volts?.let { String.format(locale, "%.1f V", it) } ?: "--", volts != null && volts < 11.8),
            Triple(stringResource(R.string.skin_futuristic_load), g.engineLoadPct?.let { "$it%" } ?: "--", false)
        )
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!tiny) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FutText(stringResource(R.string.skin_futuristic_telemetry).uppercase(locale), futCaps(capsDp, DashColors.AccentInk), Modifier.weight(1f))
                    TelemetryStatus(env, capsDp)
                }
                Spacer(Modifier.height(6.dp))
            }
            val speedBlock: @Composable (Modifier) -> Unit = { m ->
                BoxWithConstraints(m, contentAlignment = Alignment.CenterStart) {
                    val numDp = futFitSp("188", futNum(100f), maxWidth * 0.66f, maxHeight, 16f, 220f)
                    Row(verticalAlignment = Alignment.Bottom) {
                        FutText(
                            g.speedKmh?.let { units.speed(it).toString() } ?: "--",
                            futNum(numDp, if (speedOver(g.speedKmh)) DashColors.Warning else DashColors.TextPrimary),
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.width(6.dp))
                        FutText(units.speedUnit.uppercase(locale), futCaps((numDp * 0.2f).coerceIn(11f, 20f), DashColors.AccentInk), Modifier.padding(bottom = (numDp * 0.14f).dp))
                    }
                }
            }
            val readoutDp = (boxH.value * 0.09f).coerceIn(14f, 26f)
            val readoutRow: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    readouts.forEach { (label, value, alert) -> FutReadout(label, value, alert, capsDp * 0.9f, readoutDp, Modifier.weight(1f)) }
                }
            }
            if (wide && !tiny) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    speedBlock(Modifier.weight(0.9f).fillMaxHeight())
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1.1f)) {
                        RpmLine(g.rpm, capsDp)
                        Spacer(Modifier.height(10.dp))
                        readoutRow()
                    }
                }
            } else {
                speedBlock(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                RpmLine(g.rpm, capsDp)
                if (boxH >= 210.dp) {
                    Spacer(Modifier.height(10.dp))
                    readoutRow()
                }
            }
        }
        PickAdapterTarget(env, Modifier.align(Alignment.TopEnd))
    }
}

/** "RPM 2 100" over the revs bar. */
@Composable
private fun RpmLine(rpm: Int?, capsDp: Float) {
    val locale = Locale.getDefault()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FutText(stringResource(R.string.skin_futuristic_rpm).uppercase(locale), futCaps(capsDp))
            Spacer(Modifier.weight(1f))
            FutText(rpm?.let { groupThousands(it) } ?: "--", futNum(capsDp * 1.3f))
        }
        Spacer(Modifier.height(4.dp))
        FutBar(rememberRevScale().fraction(rpm), Modifier.fillMaxWidth())
    }
}

// --- Media ----------------------------------------------------------------------------

/**
 * Music on a violet panel: an equalizer that dances while a track plays, the
 * title, the artist with the time, a thin cyan-to-violet progress line and
 * round controls (play / pause the lit one). Small tiles put it all on one
 * row; wide ones set the equalizer, the text and the controls side by side.
 * Without media access the whole tile opens the access settings.
 */
@Composable
private fun FutMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val context = env.context
    val violet = DashColors.Secondary
    val locale = Locale.getDefault()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(violet, env.editing)
            .futTap(!access && !env.editing, stringResource(R.string.skin_futuristic_allow_media)) {
                CarMediaController.openNotificationAccessSettings(context)
            }
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val boxW = maxWidth
        val boxH = maxHeight
        val compact = maxHeight < 150.dp
        val wide = !compact && maxWidth >= maxHeight * 2.2f && maxWidth >= 560.dp
        val titleDp = min(maxHeight.value * (if (compact) 0.2f else 0.12f), maxWidth.value / 12f).coerceIn(15f, 40f)
        val subDp = (titleDp * 0.66f).coerceIn(12f, 22f)
        val capsDp = (titleDp * 0.5f).coerceIn(11f, 14f)
        val ctrl = min((maxHeight.value * if (compact || wide) 0.42f else 0.2f), 64f).coerceAtLeast(48f).dp
        val title = when {
            !access -> stringResource(R.string.info_media_access_needed)
            hasTrack -> ms.title
            else -> stringResource(R.string.info_nothing_playing)
        }
        val status = stringResource(
            when {
                access && ms.isPlaying -> R.string.skin_futuristic_now_playing
                access && hasTrack -> R.string.skin_futuristic_paused
                else -> R.string.skin_futuristic_music
            }
        ).uppercase(locale)
        val text: @Composable (Int) -> Unit = { titleLines ->
            Column {
                FutText(title, futText(titleDp, DashColors.TextPrimary, FontWeight.Bold), maxLines = titleLines)
                if (!access || !hasTrack) {
                    FutText(
                        stringResource(if (!access) R.string.skin_futuristic_tap_allow_access else R.string.skin_futuristic_play_something),
                        futText(subDp, if (!access) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FutText(
                            ms.artist.ifBlank { stringResource(R.string.skin_futuristic_unknown_artist) },
                            futText(subDp, lerp(violet, DashColors.TextPrimary, 0.35f)), Modifier.weight(1f, fill = false)
                        )
                        if (ms.durationMs > 0L && !compact) FutTrackTime(ms, env.mediaController, futText(subDp, DashColors.TextSecondary))
                    }
                }
            }
        }
        val eqH = (maxHeight * if (compact) 0.34f else 0.2f).coerceIn(18.dp, 60.dp)
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                compact -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    FutEqualizer(ms.isPlaying, Modifier.size(eqH * 1.3f, eqH))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { text(1) }
                    if (access) {
                        Spacer(Modifier.width(8.dp))
                        FutMediaControls(env, ctrl, all = boxW >= 380.dp)
                    }
                }
                wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    FutEqualizer(ms.isPlaying, Modifier.size(boxH * 0.5f, boxH * 0.45f))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        FutText(status, futCaps(capsDp, violet))
                        Spacer(Modifier.height(4.dp))
                        text(1)
                        if (access && ms.durationMs > 0L) {
                            Spacer(Modifier.height(10.dp))
                            FutMediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                        }
                    }
                    if (access) {
                        Spacer(Modifier.width(18.dp))
                        FutMediaControls(env, ctrl, all = true)
                    }
                }
                // The equalizer and the progress line go first as the tile gets shorter, so the controls always fit.
                else -> Column(Modifier.fillMaxSize()) {
                    if (boxH >= 220.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            FutEqualizer(ms.isPlaying, Modifier.size(eqH * 1.6f, eqH))
                            Spacer(Modifier.weight(1f))
                            FutText(status, futCaps(capsDp, violet))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    text(if (boxH >= 300.dp) 2 else 1)
                    if (access && ms.durationMs > 0L && boxH >= 190.dp) {
                        Spacer(Modifier.height(10.dp))
                        FutMediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    }
                    if (access) {
                        Spacer(Modifier.height(if (boxH > 220.dp) 14.dp else 8.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            FutMediaControls(env, ctrl, all = boxW >= 220.dp)
                        }
                    }
                }
            }
        }
    }
}

/** " · 1:35 / 4:03", in its own scope so only it follows the playback position. */
@Composable
private fun FutTrackTime(ms: MediaState, controller: CarMediaController, style: androidx.compose.ui.text.TextStyle) {
    val pos = rememberMediaPosition(ms, controller)
    FutText(" · ${formatTrackTime(pos)} / ${formatTrackTime(ms.durationMs)}", style.copy(fontFeatureSettings = "tnum"))
}

/** The progress line, in its own scope so only it follows the playback position. */
@Composable
private fun FutMediaProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    FutBar(rememberMediaFraction(ms, controller), modifier, height = 3.dp)
}

/** Previous, the lit play / pause, next; only play / pause when [all] is false. */
@Composable
private fun FutMediaControls(env: SkinTileEnv, size: Dp, all: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val controller = env.mediaController
    val ink = DashColors.TextPrimary
    val iconSize = size * 0.46f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.22f)) {
        if (all) {
            FutRoundButton(stringResource(R.string.skin_futuristic_cd_previous), size * 0.86f, enabled, { controller.previous() }, editing = env.editing) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = ink, modifier = Modifier.size(iconSize))
            }
        }
        FutRoundButton(
            stringResource(if (playing) R.string.skin_futuristic_cd_pause else R.string.skin_futuristic_cd_play),
            size, enabled, { controller.playPause() }, filled = true, editing = env.editing
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = FutOnFill, modifier = Modifier.size(iconSize))
        }
        if (all) {
            FutRoundButton(stringResource(R.string.skin_futuristic_cd_next), size * 0.86f, enabled, { controller.next() }, editing = env.editing) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = ink, modifier = Modifier.size(iconSize))
            }
        }
    }
}

/**
 * Seven bars from cyan to violet, each bouncing on its own beat while
 * [playing] (on its own layer, so only the bars redraw), resting at varied
 * heights otherwise; with effects off they rest too.
 */
@Composable
private fun FutEqualizer(playing: Boolean, modifier: Modifier) {
    val cyan = DashColors.Accent
    val violet = DashColors.Secondary
    val glowK = futGlow
    val light = DashColors.Light
    val loop = if (playing && DashColors.Effects != DashEffects.NONE) rememberLoop(EQ_LOOP_MS) else null
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(cyan, violet, glowK, light, loop) {
                val gap = size.width * 0.06f
                val barW = (size.width - gap * (EQ_BARS - 1)) / EQ_BARS
                val colors = Array(EQ_BARS) { lerp(cyan, violet, it / (EQ_BARS - 1f)) }
                val halos = Array(EQ_BARS) { colors[it].copy(alpha = 0.22f * glowK) }
                val halo = 2.dp.toPx()
                val glowing = glowK > 0f && !light
                onDrawBehind {
                    val t = loop?.value
                    for (i in 0 until EQ_BARS) {
                        val level = if (t == null) EQ_REST[i] else 0.35f + 0.65f * (0.5f - 0.5f * cos(TWO_PI * (EQ_CYCLES[i] * t + EQ_PHASE[i])))
                        val bh = size.height * level
                        val x = i * (barW + gap)
                        if (glowing) drawRect(halos[i], Offset(x - halo, size.height - bh - halo), Size(barW + halo * 2f, bh + halo))
                        drawRect(colors[i], Offset(x, size.height - bh), Size(barW, bh))
                    }
                }
            }
    )
}

// --- Navigation -----------------------------------------------------------------------

/**
 * Directions on a cyan panel: perspective chevrons lighting one after the
 * other toward the horizon, the turn's glyph with the distance in big
 * numerals, the instruction and the ETA. A tap opens the navigation app;
 * without a route the chevrons rest dim under "No route".
 */
@Composable
private fun FutNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    val locale = Locale.getDefault()
    if (!env.hasMediaAccess) {
        FutEmpty(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.skin_futuristic_tap_allow_access),
            DashColors.Accent, env.editing, if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(DashColors.Accent, env.editing)
            .futTap(canTap, stringResource(if (nav.active) R.string.skin_futuristic_open_navigation else R.string.skin_futuristic_start_navigation)) {
                openNavigationApp(context, nav)
            }
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val small = maxHeight < 170.dp || maxWidth < 230.dp
        val wide = !small && maxWidth >= maxHeight * 1.5f
        val capsDp = (innerH.value * 0.06f).coerceIn(11f, 14f)
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (!nav.active) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (!small) {
                        FutChevrons(false, Modifier.size(min(innerW.value * 0.5f, innerH.value * 0.6f).dp, (innerH * 0.32f).coerceAtMost(120.dp)))
                        Spacer(Modifier.height(10.dp))
                    }
                    val titleDp = min(innerH.value * 0.16f, innerW.value / 8f).coerceIn(15f, 32f)
                    FutText(stringResource(R.string.info_directions_no_route), futText(titleDp, DashColors.TextPrimary, FontWeight.SemiBold))
                    FutText(
                        stringResource(R.string.skin_futuristic_start_navigation),
                        futText((titleDp * 0.55f).coerceIn(12f, 16f), DashColors.AccentInk), maxLines = 2, align = TextAlign.Center
                    )
                }
                return@Box
            }
            val (value, unit) = nav.distanceParts
            val eta = nav.etaParts.joinToString(" · ")
            val instrDp = (innerW.value / (if (wide) 26f else 16f)).coerceIn(14f, 26f)
            val etaDp = (instrDp * 0.75f).coerceIn(12f, 18f)
            val details: @Composable (Dp, Dp, Int) -> Unit = { w, h, lines ->
                val below = (instrDp * 1.25f * lines + (if (eta.isNotEmpty()) etaDp * 1.25f else 0f) + 8f).dp
                val distDp = futFitSp(futTemplate(value.ifEmpty { "888" }), futNum(100f), w * 0.5f, (h - below).coerceAtLeast(20.dp), 18f, 120f)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FutManeuverGlyph(nav, (distDp * 0.9f).coerceAtLeast(24f).dp)
                        Spacer(Modifier.width((distDp * 0.18f).dp))
                        if (value.isNotEmpty()) {
                            Row {
                                FutText(value, futNum(distDp), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                                if (unit.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    FutText(unit.uppercase(locale), futCaps((distDp * 0.4f).coerceIn(12f, 26f)), Modifier.alignByBaseline())
                                }
                            }
                        }
                    }
                    FutText(nav.instruction, futText(instrDp, DashColors.TextPrimary, FontWeight.Bold), maxLines = lines)
                    if (eta.isNotEmpty()) FutText(stringResource(R.string.skin_futuristic_eta, eta), futText(etaDp, DashColors.TextSecondary))
                }
            }
            when {
                small -> details(innerW, innerH, 1)
                wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    val chevW = min(innerW.value * 0.3f, innerH.value)
                    FutChevrons(true, Modifier.size(chevW.dp, (innerH * 0.8f)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        FutText(stringResource(R.string.skin_futuristic_next_manoeuvre).uppercase(locale), futCaps(capsDp, DashColors.AccentInk))
                        Spacer(Modifier.height(4.dp))
                        details(innerW - chevW.dp - 16.dp, innerH - (capsDp * 1.25f + 4f).dp, if (innerH > 200.dp) 2 else 1)
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    FutText(stringResource(R.string.skin_futuristic_next_manoeuvre).uppercase(locale), futCaps(capsDp, DashColors.AccentInk))
                    val chevH = (innerH * 0.3f).coerceIn(40.dp, 140.dp)
                    FutChevrons(true, Modifier.fillMaxWidth().height(chevH))
                    Spacer(Modifier.weight(1f))
                    details(innerW, innerH - chevH - (capsDp * 1.25f).dp, if (innerH > 300.dp) 2 else 1)
                }
            }
        }
    }
}

/**
 * Three chevrons on the floor in perspective, the far one small and flat, the
 * near one wide, each with a soft glow; while [active] they light one after
 * the other from near to far (steady and bright with effects off), otherwise
 * they rest dim in the muted ink.
 */
@Composable
private fun FutChevrons(active: Boolean, modifier: Modifier) {
    val still = DashColors.Effects == DashEffects.NONE
    val loop = if (active && !still) rememberLoop(CHEVRON_MS) else null
    val ink = if (active) DashColors.Accent else DashColors.Muted
    val glowK = futGlow
    val light = DashColors.Light
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(ink, glowK, light, loop, active) {
                val w = size.width
                val h = size.height
                val total = CHEVRON_SCALE.sum()
                var top = 0f
                val paths = Array(3) { i ->
                    val k = CHEVRON_SCALE[i]
                    val rowH = h * k / total
                    val cw = min(w * 0.9f * k, rowH * 3.2f)
                    val sw = cw * 0.075f
                    val apexY = top + sw
                    val legY = top + rowH - sw * 0.8f
                    top += rowH
                    Path().apply {
                        moveTo(w / 2f - cw / 2f, legY)
                        lineTo(w / 2f, apexY)
                        lineTo(w / 2f + cw / 2f, legY)
                    } to sw
                }
                val strokes = Array(3) { Stroke(paths[it].second, cap = StrokeCap.Round, join = StrokeJoin.Round) }
                val blooms = Array(3) { Stroke(paths[it].second * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
                val glowing = glowK > 0f && !light && active
                onDrawBehind {
                    val t = loop?.value
                    for (i in 0 until 3) {
                        val a = when {
                            !active -> 0.3f
                            t == null -> 1f
                            else -> {
                                val p = ((t - CHEVRON_DELAY[i]) % 1f + 1f) % 1f
                                if (p < 0.4f) {
                                    val e = p / 0.4f
                                    0.25f + 0.75f * e * e * (3f - 2f * e)
                                } else {
                                    val e = (p - 0.4f) / 0.6f
                                    1f - 0.75f * e * e * (3f - 2f * e)
                                }
                            }
                        }
                        if (glowing) drawPath(paths[i].first, ink.copy(alpha = 0.18f * a * glowK), style = blooms[i])
                        drawPath(paths[i].first, ink.copy(alpha = a), style = strokes[i])
                    }
                }
            }
    )
}

/** The turn arrow from the navigation app's notification in the accent, a generic sign without one. */
@Composable
private fun FutManeuverGlyph(nav: NavState, size: Dp) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(Modifier.size(size).glowHalo(DashColors.Accent.copy(alpha = 0.3f), 0.7f), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = nav.instruction,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(DashColors.Accent),
                modifier = Modifier.size(size * 0.9f)
            )
        } else {
            Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = DashColors.Accent, modifier = Modifier.size(size * 0.85f))
        }
    }
}

// --- Clock ----------------------------------------------------------------------------

/**
 * The clock in corner brackets: the time in big glowing wide numerals, a
 * row of sixty ticks lighting up to the current second and the date in
 * spaced caps. A tap opens the alarms.
 */
@Composable
private fun FutClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val context = env.context
    val amPm = units.amPm(now, locale)?.uppercase(locale)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(DashColors.Accent, env.editing, FutFrame.BRACKETS)
            .futTap(!env.editing, stringResource(R.string.skin_futuristic_open_clock)) { openClockApp(context) }
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateDp = (innerW.value / 22f).coerceIn(11f, 18f)
        val ticks = innerH >= 120.dp
        val below = (dateDp * 1.25f + (if (ticks) 18f else 0f) + 8f).dp
        val timeDp = futFitSp("00:00", futNum(100f), innerW * (if (amPm != null) 0.84f else 0.96f), innerH - below, 18f, 360f)
        Column(
            Modifier.fillMaxSize().padding(pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row {
                FutText(timeFmt.format(now), futNum(timeDp), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeDp * 0.05f).dp))
                    FutText(amPm, futCaps((timeDp * 0.2f).coerceAtLeast(12f), DashColors.AccentInk), Modifier.alignByBaseline())
                }
            }
            if (ticks) {
                Spacer(Modifier.height(6.dp))
                FutSecondTicks(Modifier.width(innerW * 0.8f).height(10.dp))
            }
            Spacer(Modifier.height(4.dp))
            FutText(dateFmt.format(now).uppercase(locale), futCaps(dateDp))
        }
    }
}

/** Sixty ticks, those up to the current second lit and that one brightest; only this redraws each second. */
@Composable
private fun FutSecondTicks(modifier: Modifier) {
    val clock = rememberWallClock(1_000L)
    val ink = DashColors.Accent
    val glowK = futGlow
    val light = DashColors.Light
    Spacer(
        modifier.cachedDraw(ink, glowK, light, clock) {
            val step = size.width / 60f
            val w = max(1.dp.toPx(), step * 0.4f)
            val dim = ink.copy(alpha = if (light) 0.2f else 0.18f)
            val lit = ink.copy(alpha = 0.7f)
            val halo = ink.copy(alpha = 0.3f * glowK)
            val glowing = glowK > 0f && !light
            onDrawBehind {
                val sec = ((clock.longValue / 1000L) % 60L).toInt()
                for (i in 0 until 60) {
                    val x = i * step + step / 2f
                    val tall = i % 5 == 0
                    val top = if (tall) 0f else size.height * 0.35f
                    val c = when {
                        i == sec -> ink
                        i < sec -> lit
                        else -> dim
                    }
                    if (i == sec && glowing) drawLine(halo, Offset(x, -2f), Offset(x, size.height + 2f), w * 3f)
                    drawLine(c, Offset(x, top), Offset(x, size.height), w)
                }
            }
        }
    )
}

// --- Weather --------------------------------------------------------------------------

/**
 * Weather on a cyan panel: the condition's icon in a glowing ring, the
 * temperature in big numerals, the condition in caps and a feels / wind /
 * low-high line, side by side on a wide tile.
 */
@Composable
private fun FutWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        val title = when (wait) {
            WeatherWait.LOADING -> R.string.skin_futuristic_loading
            WeatherWait.NO_GPS -> R.string.info_waiting_gps
            WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
        }
        FutEmpty(stringResource(title), stringResource(R.string.skin_futuristic_weather_at_car), DashColors.Accent, env.editing, null)
        return
    }
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(DashColors.Accent, env.editing)
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val wide = innerW >= innerH * 1.7f && innerW >= 360.dp
        val condDp = (min(innerW.value, innerH.value * 2f) / 18f).coerceIn(12f, 22f)
        val lineDp = (condDp * 0.8f).coerceIn(12f, 18f)
        val temp = "${units.temp(w.tempC)}°"
        val feels = stringResource(R.string.skin_futuristic_feels, units.temp(w.feelsC))
        val wind = stringResource(R.string.skin_futuristic_wind, units.speed(w.windKmh), units.speedUnit)
        val range = if (!w.hiC.isNaN() && !w.loC.isNaN()) "${units.temp(w.loC)}° / ${units.temp(w.hiC)}°" else null
        val lineStyle = futText(lineDp, DashColors.TextSecondary)
        val condition = w.condition.uppercase(locale)
        // The condition shrinks to its room on one line, and only below 11 dp wraps to a second: never cut short.
        val details: @Composable () -> Unit = {
            BoxWithConstraints {
                val room = maxWidth
                val fitDp = futFitSp(condition, futCaps(condDp, DashColors.TextPrimary, FontWeight.SemiBold), room, 1000.dp, 11f, condDp)
                Column {
                    FutText(condition, futCaps(fitDp, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                    FutText(futFirstFitting(listOfNotNull(range?.let { "$feels · $wind · $it" }, "$feels · $wind", feels), lineStyle, room), lineStyle)
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            if (wide) {
                val ring = (innerH * 0.7f).coerceAtMost(innerW * 0.22f)
                val tempDp = futFitSp(futTemplate(temp), futNum(100f), innerW * 0.3f, innerH * 0.8f, 18f, 200f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FutIconRing(weatherIcon(w.code), ring)
                    Spacer(Modifier.width(14.dp))
                    FutText(temp, futNum(tempDp), overflow = TextOverflow.Clip)
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f)) { details() }
                }
            } else {
                val below = (condDp * 1.25f + lineDp * 1.25f + 8f).dp
                val ring = ((innerH - below) * 0.7f).coerceIn(28.dp, 96.dp)
                val tempDp = futFitSp(futTemplate(temp), futNum(100f), innerW - ring - 12.dp, innerH - below, 18f, 200f)
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FutIconRing(weatherIcon(w.code), ring)
                        Spacer(Modifier.width(12.dp))
                        FutText(temp, futNum(tempDp), overflow = TextOverflow.Clip)
                    }
                    Spacer(Modifier.height(4.dp))
                    details()
                }
            }
        }
    }
}

/** An icon in the accent inside a hairline ring with a dashed inner arc and a soft halo. */
@Composable
private fun FutIconRing(icon: ImageVector, size: Dp) {
    val ink = DashColors.Accent
    val glowK = futGlow
    val light = DashColors.Light
    Box(
        Modifier
            .size(size)
            .cachedDraw(ink, glowK, light) {
                val r = this.size.minDimension / 2f - 1.dp.toPx()
                val halo = Brush.radialGradient(listOf(ink.copy(alpha = 0.3f * glowK), Color.Transparent), this.size.center, r * 1.3f)
                val ring = Stroke(1.dp.toPx())
                val dash = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                val inner = r * 0.82f
                onDrawBehind {
                    if (glowK > 0f && !light) drawCircle(halo, r * 1.3f)
                    drawCircle(ink.copy(alpha = 0.7f), r, style = ring)
                    drawArc(ink.copy(alpha = 0.5f), 200f, 140f, false, Offset(center.x - inner, center.y - inner), Size(inner * 2f, inner * 2f), style = dash)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.52f))
    }
}

// --- Range ----------------------------------------------------------------------------

/**
 * Fuel and range on a violet panel: what is left to drive in big numerals,
 * the tank as a row of twenty lit cells (amber in the reserve) with its share
 * beside it and where the level comes from. A tap opens the fuel finder to
 * recalibrate; until fuel is known the standard tile explains how to learn it.
 */
@Composable
private fun FutRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    var finder by remember { mutableStateOf(false) }
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            .futPanel(DashColors.Secondary, env.editing)
            .futTap(!env.editing, stringResource(R.string.skin_futuristic_recalibrate_fuel)) { finder = true }
    ) {
        val pad = panelPad(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val capsDp = (innerH.value * 0.07f).coerceIn(11f, 14f)
        val showHead = innerH >= 100.dp
        val cellsH = (innerH * 0.1f).coerceIn(10.dp, 26.dp)
        val pctDp = (cellsH.value * 1.1f).coerceIn(14f, 28f)
        val head = if (showHead) (capsDp * 1.25f + 6f).dp else 0.dp
        val numH = innerH - head - cellsH * 1.3f - 14.dp
        val rangeText = "${units.distance(fuel.rangeKm)}"
        val numDp = futFitSp(futTemplate(rangeText).padStart(3, '8'), futNum(100f), innerW * 0.62f, numH.coerceAtLeast(20.dp), 18f, 220f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
            if (showHead) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FutText(stringResource(R.string.skin_futuristic_to_empty).uppercase(locale), futCaps(capsDp, DashColors.Secondary), Modifier.weight(1f))
                    FutText(fuel.source.uppercase(locale), futCaps(capsDp))
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FutText(rangeText, futNum(numDp, if (low) DashColors.Warning else DashColors.TextPrimary, glow = if (low) DashColors.Warning else DashColors.Secondary), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(8.dp))
                FutText(units.distanceUnit.uppercase(locale), futCaps((numDp * 0.22f).coerceIn(12f, 24f), DashColors.Secondary), Modifier.padding(bottom = (numDp * 0.14f).dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FutFuelCells(fuel.percent, low, Modifier.weight(1f).height(cellsH))
                Spacer(Modifier.width(10.dp))
                FutText("${fuel.percent}%", futNum(pctDp, if (low) DashColors.Warning else DashColors.TextPrimary))
            }
        }
    }
    if (finder) FuelFinderDialog(onDismiss = { finder = false })
}

/** Twenty cells from empty to full, those within [percent] lit from violet to cyan (all amber when [low]), with a glow. */
@Composable
private fun FutFuelCells(percent: Int, low: Boolean, modifier: Modifier) {
    val violet = DashColors.Secondary
    val cyan = DashColors.Accent
    val warn = DashColors.Warning
    val glowK = futGlow
    val light = DashColors.Light
    val lit = ((percent.coerceIn(0, 100) * FUEL_SEGMENTS + 99) / 100)
    Spacer(
        modifier.cachedDraw(lit, low, violet, cyan, warn, glowK, light) {
            val gap = max(2.dp.toPx(), size.width * 0.008f)
            val cell = (size.width - gap * (FUEL_SEGMENTS - 1)) / FUEL_SEGMENTS
            val skew = size.height * 0.25f
            val shapes = Array(FUEL_SEGMENTS) { i ->
                val x = i * (cell + gap)
                Path().apply {
                    moveTo(x + skew, 0f); lineTo(x + cell, 0f); lineTo(x + cell - skew, size.height); lineTo(x, size.height); close()
                }
            }
            val inks = Array(FUEL_SEGMENTS) { i -> if (low) warn else lerp(violet, cyan, i / (FUEL_SEGMENTS - 1f)) }
            val off = (if (low) warn else violet).copy(alpha = if (light) 0.14f else 0.12f)
            val halo = Stroke(4.dp.toPx())
            val glowing = glowK > 0f && !light
            onDrawBehind {
                for (i in 0 until FUEL_SEGMENTS) {
                    if (i < lit) {
                        if (glowing) drawPath(shapes[i], inks[i].copy(alpha = 0.2f * glowK), style = halo)
                        drawPath(shapes[i], inks[i])
                    } else {
                        drawPath(shapes[i], off)
                    }
                }
            }
        }
    )
}

// --- Apps -----------------------------------------------------------------------------

/** An app shortcut as a round glowing button with its icon, its name in caps under it; an assistant's is the lit, pulsing one. */
@Composable
private fun FutApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val voice = item.packageName in VOICE_PACKAGES
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer(), contentAlignment = Alignment.Center) {
        val boxW = maxWidth
        val showLabel = maxHeight >= 96.dp
        val labelDp = (min(maxWidth.value, maxHeight.value) * 0.1f).coerceIn(11f, 14f)
        val room = maxHeight.value - (if (showLabel) labelDp * 1.25f + 10f else 0f)
        val d = (min(maxWidth.value, room) * 0.8f).coerceIn(48f, 132f).dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FutRoundButton(
                stringResource(R.string.skin_futuristic_open_app, label), d, !env.editing, { env.onLaunchApp(item.packageName) },
                filled = voice, pulse = voice, editing = env.editing
            ) {
                FutAppGlyph(app, d * 0.56f)
            }
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                FutText(
                    label.uppercase(Locale.getDefault()), futCaps(labelDp, DashColors.TextPrimary),
                    Modifier.widthIn(max = boxW - 8.dp), align = TextAlign.Center
                )
            }
        }
    }
}

/**
 * A launch bar as the mockup's dock, on a cut-corner HUD plate like every panel, with
 * round buttons spread along it (the names under them on a tall bar), an
 * assistant's button bigger, lit and pulsing, and a small pencil at the end.
 */
@Composable
private fun FutLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val ink = DashColors.Accent
    val editing = env.editing
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .padding(4.dp)
            // The same cut-corner HUD plate as every other panel.
            .futPanel(ink, editing)
    ) {
        val pencil = 56.dp
        val room = maxWidth - pencil - 24.dp
        val pkgs = item.packages.take((room.value / 60f).toInt().coerceAtLeast(1))
        val per = room / pkgs.size.coerceAtLeast(1)
        val tall = maxHeight >= 130.dp
        val labels = tall && per >= 80.dp
        val labelDp = 11f
        val btn = min(maxHeight.value * (if (tall) 0.5f else 0.72f), per.value * 0.78f).coerceIn(44f, 72f).dp
        val voiceBtn = (btn * 1.22f).coerceAtMost(maxHeight * 0.92f)
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                FutText(
                    stringResource(R.string.skin_futuristic_launch_bar_empty), futText(14f, DashColors.TextSecondary),
                    Modifier.weight(1f).padding(start = 12.dp), maxLines = 2
                )
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    val voice = pkg in VOICE_PACKAGES
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val size = if (voice) voiceBtn else btn
                            FutRoundButton(
                                stringResource(R.string.skin_futuristic_open_app, name), size, !editing, { env.onLaunchApp(pkg) },
                                filled = voice, pulse = voice, editing = editing
                            ) {
                                FutAppGlyph(app, size * 0.56f)
                            }
                            if (labels) {
                                Spacer(Modifier.height(4.dp))
                                FutText(name.uppercase(Locale.getDefault()), futCaps(labelDp, DashColors.TextPrimary), Modifier.widthIn(max = per - 8.dp), align = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(pencil), contentAlignment = Alignment.Center) {
                FutRoundButton(stringResource(R.string.skin_futuristic_cd_edit_launch_bar), 48.dp, true, env.onEditLaunchBar, tone = DashColors.TextSecondary) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

