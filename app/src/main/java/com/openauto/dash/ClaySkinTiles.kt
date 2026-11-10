package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Clay skin, the tiles (the look itself is in ClaySkin.kt): each main widget
 * on its own pastel clay card, its big figure in the pastel's deep ink. The
 * next turn is the lilac card with the puffy 3D arrow (ClaySkinArrow.kt),
 * the speed a round coral disc, the weather pale blue, the music butter with
 * a puffy orange play button. The arrow, its balls and the play button bob
 * gently only while the car stands, on the page in sight, with effects on.
 */

private const val DOT = " · "

/** Room around a card's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.075f).coerceIn(14f, 30f).dp

/**
 * Clay renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer (on a lilac card, [clayFallbackPanel]).
 */
@Composable
internal fun ClayTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> ClayApp(item, env)
        is DashboardItem.LaunchBar -> ClayLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> ClayTelemetry(env)
            BuiltinKind.SPEED_HUD -> ClaySpeed(env)
            BuiltinKind.MEDIA -> ClayMedia(env)
            BuiltinKind.NAVIGATION -> ClayDirections(env)
            BuiltinKind.CLOCK -> ClayClock(env)
            BuiltinKind.WEATHER -> ClayWeather(env)
            BuiltinKind.RANGE -> ClayRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -----------------------------------------------------------------

/** A whole card as one tap target with the tap feedback, or nothing when [enabled] is false. */
@Composable
private fun Modifier.clayTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/** A round clay ball in a pale version of [hue] with an icon in the hue's ink: the cards' little emblems. */
@Composable
private fun ClayIconBall(icon: ImageVector, hue: ClayHue, size: Dp) {
    val light = DashColors.Light
    val base = hue.tint
    val tint = remember(base, light) { base.copy(fill = lerp(base.fill, Color.White, if (light) 0.75f else 0.14f)) }
    Box(Modifier.size(size).clay(tint, CLAY_ROUND, depth = 0.45f), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = base.ink, modifier = Modifier.size(size * 0.52f))
    }
}

/**
 * A quiet state on a clay card: an emblem (the puffy arrow on the
 * navigation card), a title and one hint, the hint in the card's ink when a
 * tap does something; the whole card is the target.
 */
@Composable
private fun ClayEmpty(
    hue: ClayHue,
    icon: ImageVector,
    title: String,
    hint: String,
    editing: Boolean,
    onTap: (() -> Unit)?,
    art: (@Composable (Dp) -> Unit)? = null
) {
    val tint = hue.tint
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .clay(tint, editing = editing)
            .clayTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val roomy = maxHeight >= 200.dp
        val emblem = if (art != null) min(maxHeight.value * 0.42f, maxWidth.value * (if (wide) 0.36f else 0.6f)).coerceIn(48f, 180f).dp
        else min(maxHeight.value * 0.34f, maxWidth.value * 0.22f).coerceIn(44f, 84f).dp
        val textW = if (wide) maxWidth - pad * 2 - emblem - 18.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.12f, textW.value / (title.length * 0.5f + 1f)).coerceIn(18f, 32f)
        val hintSize = (titleSize * 0.7f).coerceIn(15f, 22f)
        val drawEmblem: @Composable () -> Unit = {
            if (art != null) art(emblem) else ClayIconBall(icon, hue, emblem)
        }
        val words: @Composable () -> Unit = {
            Column {
                ClayLine(title, clayText(titleSize, DashColors.TextPrimary, FontWeight.Black), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                ClayLine(hint, clayText(hintSize, if (onTap != null) tint.ink else DashColors.TextSecondary, FontWeight.SemiBold), maxLines = 2)
            }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                drawEmblem()
                Spacer(Modifier.width(18.dp))
                words()
            }
        } else {
            Column(Modifier.padding(pad)) {
                if (roomy || art != null) {
                    drawEmblem()
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

/** Children in a row, in order, each only if it fits whole in the room left (dropped, never cut), [gap] apart. */
@Composable
private fun ClayFitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val placed = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        var x = 0
        var height = 0
        measurables.forEachIndexed { i, m ->
            val room = constraints.maxWidth - x
            if (room <= 0 || m.maxIntrinsicWidth(constraints.maxHeight) > room) return@forEachIndexed
            val p = m.measure(Constraints(maxWidth = room, maxHeight = constraints.maxHeight))
            placed[i] = p
            xs[i] = x
            x += p.width + gapPx
            height = max(height, p.height)
        }
        val width = (x - gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], (height - p.height) / 2) }
        }
    }
}

/** A purple clay pill button, at least 56 dp tall, for the one thing to do on a card (connect the adapter). */
@Composable
private fun ClayPillButton(text: String, enabled: Boolean, filled: Boolean = true, onClick: () -> Unit) {
    val light = DashColors.Light
    val purple = clayPurple
    val tint = remember(purple, light, filled) {
        if (filled) ClayTint(purple, Color.White, if (light) Color(0xFF5B3FD1) else Color(0xFF0B0716))
        else clayTint(ClayHue.LILAC, light)
    }
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .clayPress(enabled, text, onClick)
            .clay(tint, CLAY_ROUND, depth = 0.55f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        ClayLine(text, clayText(18f, tint.ink, FontWeight.Black))
    }
}

/** A small reading in an inset well: its name over its value, the value in amber when out of range. */
@Composable
private fun ClayReadout(card: ClayTint, label: String, value: String, warn: Boolean, size: Float) {
    Column(Modifier.clayWell(card, 22.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        ClayLine(label, clayText((size * 0.75f).coerceAtLeast(14f), DashColors.TextSecondary, FontWeight.SemiBold))
        ClayLine(value, clayText(size * 1.1f, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.Black))
    }
}

/**
 * An inset groove with a puffy capsule of [ink] filling [fraction] of it: the
 * fuel and the song's progress. Drawn once per value.
 */
@Composable
private fun ClayGroove(card: ClayTint, ink: Color, fraction: Float, modifier: Modifier) {
    val plain = DashColors.Effects == DashEffects.NONE
    Box(modifier.clayWell(card)) {
        Spacer(
            Modifier.fillMaxSize().cachedDraw(ink, fraction, plain) {
                val inset = (size.height * 0.16f).coerceAtLeast(2.dp.toPx())
                val h = size.height - inset * 2f
                val fw = ((size.width - inset * 2f) * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) h else 0f)
                val body = if (plain) Brush.linearGradient(listOf(ink, ink)) else Brush.verticalGradient(
                    listOf(lerp(ink, Color.White, 0.4f), ink, lerp(ink, Color.Black, 0.12f)), inset, inset + h
                )
                val shine = Color.White.copy(alpha = 0.55f)
                onDrawBehind {
                    if (fw <= 0f) return@onDrawBehind
                    drawRoundRect(body, Offset(inset, inset), Size(fw, h), CornerRadius(h / 2f))
                    if (!plain && fw > h) drawLine(shine, Offset(inset + h * 0.5f, inset + h * 0.3f), Offset(inset + fw - h * 0.5f, inset + h * 0.3f), h * 0.18f, StrokeCap.Round)
                }
            }
        )
    }
}

// --- Directions: the puffy arrow ---------------------------------------------------

/**
 * The puffy 3D arrow for [side] in a square [size] box, leaning toward the
 * turn, with three clay balls floating around it. It bobs gently on the
 * ambient loop: still while the car moves, on a hidden page or with effects off.
 */
@Composable
private fun ClayTurnArt(side: TurnSide, size: Dp, description: String) {
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val purple = clayPurple
    val orange = clayOrange
    val loop = rememberLoop(3_200)
    Spacer(
        Modifier
            .size(size)
            .graphicsLayer()
            .semantics { contentDescription = description }
            .cachedDraw(side, light, plain, purple, orange, loop) {
                val s = min(this.size.width, this.size.height)
                val topLeft = Offset((this.size.width - s) / 2f, (this.size.height - s) / 2f)
                val art = ClayArrowArt(side, s, purple, light, plain)
                val balls = arrayOf(
                    ClayBall(orange, s * 0.075f, light, plain),
                    ClayBall(Color(0xFFFF8FB0), s * 0.05f, light, plain),
                    ClayBall(Color(0xFF7FD3FF), s * 0.04f, light, plain)
                )
                val spots = arrayOf(
                    topLeft + Offset(s * 0.08f, s * 0.86f),
                    topLeft + Offset(s * 0.9f, s * 0.1f),
                    topLeft + Offset(s * 0.95f, s * 0.68f)
                )
                onDrawBehind {
                    val p = loop.value
                    val lift = (0.5f + 0.5f * clayWave(p, 0f)) * s * 0.035f
                    drawTurn(art, topLeft, lift, 2.5f * clayWave(p, 0.25f))
                    for (i in balls.indices) {
                        drawBall(balls[i], spots[i], clayWave(p, 0.33f * i + 0.1f) * s * 0.025f, 1f + 0.06f * clayWave(p, 0.33f * i + 0.35f))
                    }
                }
            }
    )
}

/**
 * The navigation app's own glyph (a roundabout, a fork) white on a puffy
 * purple ball, for a turn the arrow cannot tell; it bobs like the arrow.
 */
@Composable
private fun ClayGlyphBall(bitmap: ImageBitmap, size: Dp, description: String) {
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val purple = clayPurple
    val loop = rememberLoop(3_200)
    Box(
        Modifier
            .size(size)
            .graphicsLayer { translationY = -(0.5f + 0.5f * clayWave(loop.value, 0f)) * this.size.minDimension * 0.035f }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            Modifier.fillMaxSize().cachedDraw(purple, light, plain) {
                val r = this.size.minDimension * 0.42f
                val ball = ClayBall(purple, r, light, plain)
                onDrawBehind { drawBall(ball, center, 0f, 1f) }
            }
        )
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.fillMaxSize(0.46f))
    }
}

/** The arrival time and the rest of the arrival line ("12 min · 6.4 km"), as the pill shows them. */
private fun etaSplit(nav: NavState): Pair<String?, String> {
    val parts = nav.etaParts
    val clock = parts.firstOrNull { CLAY_CLOCK.matches(it) }
    return clock to parts.filter { it != clock }.joinToString(DOT)
}

private val CLAY_CLOCK = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/**
 * The next turn on the lilac card, as in the design: the puffy arrow on the
 * left (above on an upright card), the distance big in purple, the street
 * under it, and an inset pill along the foot with the arrival time and
 * what is left. Tap opens the navigation app. Without a route the arrow
 * points ahead and the card says how to start.
 */
@Composable
private fun ClayDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    val tint = ClayHue.LILAC.tint
    when {
        !env.hasMediaAccess -> ClayEmpty(
            ClayHue.LILAC, Icons.Rounded.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> {
            val noRoute = stringResource(R.string.info_directions_no_route)
            ClayEmpty(
                ClayHue.LILAC, Icons.Rounded.DirectionsCar,
                noRoute, stringResource(R.string.horizon_start_navigation), env.editing,
                if (canTap) ({ openNavigationApp(context, nav) }) else null,
                art = { d -> ClayTurnArt(TurnSide.AHEAD, d, noRoute) }
            )
        }
        else -> {
            val (arrive, rest) = remember(nav.eta) { etaSplit(nav) }
            val arrival = arrive?.let { stringResource(R.string.clay_arrival, it) }
            val side = nav.turnSide()
            val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
            val glyph = if (side == TurnSide.AHEAD && nav.maneuver != "STRAIGHT") bitmap else null
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .clay(tint, editing = env.editing)
                    .clayTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = padFor(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val pill = (arrival != null || rest.isNotEmpty()) && innerH >= 170.dp
                val pillH = if (pill) (innerH * 0.16f).coerceIn(52.dp, 74.dp) else 0.dp
                val areaH = (innerH - pillH - if (pill) 14.dp else 0.dp).coerceAtLeast(0.dp)
                val wide = innerW >= areaH * 1.02f
                val (value, unit) = nav.distanceParts
                val sample = clayTemplate(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit")
                val art: @Composable (Dp) -> Unit = { d ->
                    if (glyph != null) ClayGlyphBall(glyph, d, nav.instruction) else ClayTurnArt(side, d, nav.instruction)
                }
                Column(Modifier.fillMaxSize().padding(pad)) {
                    if (wide) {
                        val artS = min(areaH.value, innerW.value * 0.5f).dp
                        val wordsW = innerW - artS - 16.dp
                        val streetSize = min(wordsW.value / 11f, areaH.value * 0.1f).coerceIn(17f, 32f)
                        val distSize = clayFit(sample, FontWeight.Black, wordsW * 0.96f, areaH * 0.42f, 28f, 130f)
                        Row(Modifier.fillMaxWidth().height(areaH), verticalAlignment = Alignment.CenterVertically) {
                            art(artS)
                            Spacer(Modifier.width(16.dp))
                            ClayTurnWords(nav, value, unit, distSize, streetSize, tint.ink, Modifier.weight(1f), maxLines = if (areaH > 220.dp) 2 else 1)
                        }
                    } else {
                        val streetSize = min(innerW.value / 13f, areaH.value * 0.08f).coerceIn(17f, 30f)
                        val artS = min(innerW.value * 0.72f, areaH.value * 0.5f).dp
                        val wordsH = areaH - artS - (streetSize * 2.6f).dp - 12.dp
                        val distSize = clayFit(sample, FontWeight.Black, innerW * 0.92f, wordsH.coerceAtLeast(30.dp), 26f, 110f)
                        Column(Modifier.fillMaxWidth().height(areaH), verticalArrangement = Arrangement.Center) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { art(artS) }
                            Spacer(Modifier.height(12.dp))
                            ClayTurnWords(nav, value, unit, distSize, streetSize, tint.ink, Modifier.fillMaxWidth(), maxLines = 2)
                        }
                    }
                    if (pill) {
                        Spacer(Modifier.height(14.dp))
                        val etaSize = (pillH.value * 0.34f).coerceIn(16f, 24f)
                        Row(
                            Modifier.fillMaxWidth().height(pillH).clayWell(tint).padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ClayLine(arrival ?: rest, clayText(etaSize, DashColors.TextPrimary, FontWeight.Black), Modifier.weight(1f, fill = false))
                            if (arrival != null && rest.isNotEmpty()) {
                                Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                                ClayLine(rest, clayText(etaSize, DashColors.TextPrimary, FontWeight.Black), align = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The distance big in the card's purple ink with its unit, and the street under it. */
@Composable
private fun ClayTurnWords(nav: NavState, value: String, unit: String, distSize: Float, streetSize: Float, ink: Color, modifier: Modifier, maxLines: Int) {
    Column(modifier) {
        if (value.isNotEmpty()) {
            Row {
                ClayLine(value, clayText(distSize, ink, FontWeight.Black, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.08f).dp))
                    ClayLine(unit, clayText((distSize * 0.36f).coerceAtLeast(16f), ink, FontWeight.Bold), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(8.dp))
            ClayLine(nav.instruction, clayText(streetSize, DashColors.TextPrimary, FontWeight.Bold), maxLines = maxLines)
        } else {
            ClayLine(nav.instruction, clayText((streetSize * 1.35f).coerceAtMost(40f), DashColors.TextPrimary, FontWeight.Black), maxLines = maxLines + 1)
        }
    }
}

// --- Speed: the coral disc ------------------------------------------------------------

/**
 * Speed on a round coral clay disc, as in the design: the numeral big in
 * coral, the unit under it, the road's limit on a small white clay sign at
 * the disc's shoulder. Over the limit the numeral turns red with a red ring
 * inside the disc. No speed: a dash, and a tap connects the adapter.
 */
@Composable
private fun ClaySpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val tint = ClayHue.CORAL.tint
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Critical
        else -> tint.ink
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val critical = DashColors.Critical
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer(), contentAlignment = Alignment.Center) {
        val margin = (min(maxWidth.value, maxHeight.value) * 0.04f).coerceIn(2f, 14f).dp
        val d = min(maxWidth.value, maxHeight.value).dp - margin * 2
        val wide = maxWidth >= d * 1.7f + 40.dp
        Box(Modifier.size(d)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clay(tint, CLAY_ROUND, editing = env.editing)
                    .cachedDraw(over, critical) {
                        val ring = Stroke(4.dp.toPx())
                        onDrawBehind { if (over) drawCircle(critical, size.minDimension / 2f - 10.dp.toPx(), style = ring) }
                    }
                    .clayTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
                    .semantics(mergeDescendants = true) { contentDescription = said },
                contentAlignment = Alignment.Center
            ) {
                val numSize = clayFit("188", FontWeight.Black, d * 0.66f, d * 0.42f, 28f, 170f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ClayLine(
                        speed?.let { units.speed(it).toString() } ?: "–",
                        clayText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.Black, figure = true),
                        overflow = TextOverflow.Clip
                    )
                    Spacer(Modifier.height((d.value * 0.02f).dp))
                    ClayLine(units.speedUnit, clayText((d.value * 0.075f).coerceIn(15f, 24f), DashColors.TextPrimary, FontWeight.Bold))
                }
            }
            val sign = (d * 0.27f).coerceIn(40.dp, 76.dp)
            if (!wide) limit?.let {
                Box(Modifier.align(Alignment.TopEnd).offset(x = sign * 0.12f, y = -sign * 0.08f)) { ClayLimitSign(units.speed(it), sign) }
            }
        }
        if (wide) limit?.let {
            val sign = (d * 0.3f).coerceIn(44.dp, 84.dp)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = margin)) { ClayLimitSign(units.speed(it), sign) }
        }
    }
}

/** The road's limit as its sign on a little clay disc: white, a red ring, the number in black (a sign keeps its colours day and night). */
@Composable
private fun ClayLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.clay_speed_limit_cd, limit)
    val tint = remember { ClayTint(Color.White, Color(0xFF111111), Color(0xFF7A3A3A)) }
    Box(
        Modifier
            .size(d)
            .clay(tint, CLAY_ROUND, depth = 0.4f)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.11f
                onDrawBehind { drawCircle(Color(0xFFD8232A), size.minDimension / 2f - ring / 2f - 2.dp.toPx(), style = Stroke(ring)) }
            },
        contentAlignment = Alignment.Center
    ) {
        ClayLine(limit.toString(), clayText((d.value * if (limit >= 100) 0.3f else 0.38f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.Black), overflow = TextOverflow.Clip)
    }
}

// --- Weather ----------------------------------------------------------------------

/**
 * Weather on a pale blue clay card: the temperature big in blue, the sky's
 * icon on a little clay ball, then rain to come (or the sky in words) and a
 * second line. While there is nothing yet, it says why.
 */
@Composable
private fun ClayWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val tint = ClayHue.SKY.tint
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().clay(tint, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val lineSize = min(innerW.value / 12f, innerH.value * 0.12f).coerceIn(15f, 26f)
        val ball = min(innerW.value * 0.28f, innerH.value * 0.34f).coerceIn(40f, 88f).dp
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.fillMaxSize().padding(pad)) {
                ClayLine("--°", clayText(clayFit("88°", FontWeight.Black, innerW * 0.6f, innerH * 0.45f, 28f, 120f), DashColors.Muted, FontWeight.Black, figure = true))
                Spacer(Modifier.weight(1f))
                ClayLine(stringResource(title), clayText(lineSize, DashColors.TextSecondary, FontWeight.SemiBold), maxLines = 2)
            }
            return@BoxWithConstraints
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val temp = "${units.temp(w.tempC)}°"
        val lines = if (innerH >= 130.dp) 2 else 1
        val tempSize = clayFit(clayTemplate(temp), FontWeight.Black, innerW - ball - 10.dp, innerH - (lineSize * 1.25f * lines + 8f).dp, 28f, 150f)
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(verticalAlignment = Alignment.Top) {
                ClayLine(temp, clayText(tempSize, tint.ink, FontWeight.Black, figure = true), Modifier.weight(1f), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(10.dp))
                ClayIconBall(weatherIcon(w.code), ClayHue.SKY, ball)
            }
            Spacer(Modifier.weight(1f))
            ClayLine(rain ?: w.condition, clayText(lineSize, DashColors.TextPrimary, FontWeight.Bold))
            if (lines > 1) ClayLine(if (rain != null) w.condition else feels, clayText(lineSize, DashColors.TextSecondary, FontWeight.SemiBold))
        }
    }
}

// --- Media ------------------------------------------------------------------------

/**
 * Music on a butter clay card, as in the design: the cover as a squircle
 * with its own inner light (or a pink-to-violet clay sweet while the player
 * gives none), the song big and who sings it, a groove of progress on a tall
 * card, and the puffy orange play button with smaller clay keys beside it.
 * A tall narrow card stacks them.
 */
@Composable
private fun ClayMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        ClayEmpty(
            ClayHue.BUTTER, Icons.Rounded.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val tint = ClayHue.BUTTER.tint
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().clay(tint, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val stacked = maxWidth < maxHeight * 1.25f
        if (!stacked) {
            val play = min(innerH.value * 0.62f, 96f).coerceAtLeast(56f).dp
            val small = (play * 0.68f).coerceAtLeast(48.dp)
            val art = min(innerH.value, innerW.value * 0.24f).coerceIn(48f, 150f).dp
            val titleSize = (innerH.value * 0.2f).coerceIn(18f, 34f)
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 360.dp) {
                    ClayCover(ms, art)
                    Spacer(Modifier.width(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    ClayLine(title, clayText(titleSize, DashColors.TextPrimary, FontWeight.Black), maxLines = if (innerH >= 160.dp) 2 else 1)
                    ClayLine(sub, clayText((titleSize * 0.66f).coerceIn(15f, 22f), DashColors.TextSecondary, FontWeight.SemiBold))
                    if (innerH >= 150.dp && ms.durationMs > 0L) {
                        Spacer(Modifier.height(12.dp))
                        ClayProgress(ms, env.mediaController, tint, Modifier.fillMaxWidth().height(16.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                ClayMediaKeys(env, play, small, prev = innerW >= 560.dp, next = innerW >= 420.dp)
            }
        } else {
            val play = (innerW.value * 0.3f).coerceIn(56f, 92f).dp
            val small = (play * 0.68f).coerceAtLeast(48.dp)
            val titleSize = (innerW.value / 12f).coerceIn(18f, 30f)
            val textH = (titleSize * 1.2f + titleSize * 0.66f * 1.2f + 30f).dp
            val coverH = innerH - textH - play - 16.dp
            Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                if (coverH >= 64.dp) {
                    ClayCover(ms, min(coverH.value, innerW.value).dp)
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.weight(1f))
                ClayLine(title, clayText(titleSize, DashColors.TextPrimary, FontWeight.Black), Modifier.fillMaxWidth(), align = TextAlign.Center)
                ClayLine(sub, clayText((titleSize * 0.66f).coerceIn(15f, 22f), DashColors.TextSecondary, FontWeight.SemiBold), Modifier.fillMaxWidth(), align = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                ClayMediaKeys(env, play, small, prev = innerW >= 240.dp, next = true)
            }
        }
    }
}

/**
 * The song's cover as a squircle with the clay's inner light over it (a
 * catch of light top left, a shade bottom right), or while the player gives
 * none the design's pink-to-violet sweet with a note on it.
 */
@Composable
private fun ClayCover(ms: MediaState, size: Dp) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    val plain = DashColors.Effects == DashEffects.NONE
    val corner = size * 0.3f
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .cachedDraw(bitmap == null, plain, size) {
                val w = this.size.width
                val h = this.size.height
                val r = corner.toPx()
                val sweet = Brush.linearGradient(listOf(Color(0xFFFF9A8B), Color(0xFFFF6A88), Color(0xFFA77BFF)), Offset.Zero, Offset(w, h))
                val light = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.55f), 0.4f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f),
                    start = Offset.Zero, end = clayLightEnd(w, h)
                )
                val band = Stroke(min(w, h) * 0.08f)
                val half = band.width / 2f
                onDrawWithContent {
                    if (bitmap == null) drawRect(sweet)
                    drawContent()
                    if (!plain) drawRoundRect(light, Offset(half, half), Size(w - band.width, h - band.width), CornerRadius(r - half), style = band)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(size * 0.42f))
    }
}

/** Previous and next as small clay keys (when [prev] / [next]), the puffy orange play / pause between them. */
@Composable
private fun ClayMediaKeys(env: SkinTileEnv, play: Dp, small: Dp, prev: Boolean, next: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (prev) ClaySmallKey(Icons.Rounded.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), small, enabled) { c.previous() }
        ClayPlay(playing, play, enabled) { c.playPause() }
        if (next) ClaySmallKey(Icons.Rounded.SkipNext, stringResource(R.string.horizon_cd_next_track), small, enabled) { c.next() }
    }
}

/**
 * The puffy orange play / pause, bobbing gently like the design's (only
 * while the car stands, with effects on); it squashes when pressed.
 */
@Composable
private fun ClayPlay(playing: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play)
    val light = DashColors.Light
    val orange = clayOrange
    val tint = remember(orange, light) { ClayTint(orange, Color.White, if (light) Color(0xFFC87814) else Color(0xFF0B0716)) }
    val loop = rememberLoop(2_400)
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val wv = clayWave(loop.value, 0f)
                translationY = -(0.5f + 0.5f * wv) * 6f * density
                val s = 1f + 0.015f * (1f + wv)
                scaleX = s
                scaleY = s
            }
            .clayPress(enabled, label, onClick)
            .clay(tint, CLAY_ROUND, depth = 0.6f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.48f))
    }
}

/** A small round clay key in pale butter, the icon in the text's ink. */
@Composable
private fun ClaySmallKey(icon: ImageVector, label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val light = DashColors.Light
    val base = ClayHue.BUTTER.tint
    val tint = remember(base, light) { base.copy(fill = lerp(base.fill, Color.White, if (light) 0.6f else 0.1f)) }
    Box(
        Modifier
            .size(size)
            .clayPress(enabled, label, onClick)
            .clay(tint, CLAY_ROUND, depth = 0.45f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(size * 0.5f))
    }
}

/** The song's progress as an orange capsule in a groove; in its own scope, so only it follows the position. */
@Composable
private fun ClayProgress(ms: MediaState, controller: CarMediaController, card: ClayTint, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    // A step per percent: the groove redraws a hundred times a song, not every second.
    val stepped = (fraction * 100f).roundToInt() / 100f
    ClayGroove(card, clayOrange, stepped, modifier)
}

// --- Clock ------------------------------------------------------------------------

/** Clock on a pink clay card: the time big and heavy in pink ink, the date under it. Tap opens the alarms. */
@Composable
private fun ClayClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val context = env.context
    val tint = ClayHue.PINK.tint
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .clay(tint, editing = env.editing)
            .clayTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 13f, innerH.value * 0.14f).coerceIn(15f, 26f)
        val date = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = clayFit(clayTemplate(timeText), FontWeight.Black, if (amPm != null) innerW * 0.8f else innerW, innerH - (dateSize * 1.3f + 8f).dp, 28f, 260f)
        Column(Modifier.padding(pad)) {
            Row {
                ClayLine(timeText, clayText(timeSize, tint.ink, FontWeight.Black, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    ClayLine(amPm, clayText((timeSize * 0.24f).coerceAtLeast(15f), tint.ink, FontWeight.Bold), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(6.dp))
            ClayLine(date, clayText(dateSize, DashColors.TextPrimary, FontWeight.Bold))
        }
    }
}

// --- Telemetry --------------------------------------------------------------------

/** The gear's mark: R, 1 to 6, N while free-wheeling, a dash when it can't be told. */
private fun gearMark(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    is Gear.Forward -> gear.n.toString()
    Gear.Free -> "N"
    Gear.Stopped, Gear.Unknown -> "–"
}

/**
 * Telemetry on an aqua clay card: the revs big in teal with the gear in an
 * inset pill, then the engine's readings in little wells where they fit.
 * Without the link: the card says so with a purple clay button to connect.
 */
@Composable
private fun ClayTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val tint = ClayHue.AQUA.tint
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val gear by rememberGear()
    val live = state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().clay(tint, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val readSize = min(innerW.value / 18f, innerH.value * 0.12f).coerceIn(15f, 22f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            when {
                live -> {
                    val rpm = gear.rpm
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClayLine(stringResource(R.string.clay_rpm), clayText(readSize, DashColors.TextSecondary, FontWeight.Bold), Modifier.weight(1f))
                        Box(Modifier.clayWell(tint).padding(horizontal = 14.dp, vertical = 6.dp)) {
                            ClayLine(stringResource(R.string.clay_gear, gearMark(gear.gear)), clayText(readSize, tint.ink, FontWeight.Black))
                        }
                    }
                    val wellsH = if (innerH >= 150.dp) (readSize * 2.6f + 20f).dp else 0.dp
                    val numSize = clayFit("8 888", FontWeight.Black, innerW * 0.9f, innerH - wellsH - (readSize * 2.2f).dp, 26f, 140f)
                    ClayLine(rpm?.let { groupThousands(it) } ?: "--", clayText(numSize, tint.ink, FontWeight.Black, figure = true), overflow = TextOverflow.Clip)
                    if (wellsH > 0.dp) {
                        ClayFitRow(10.dp) {
                            g.coolantTempC?.let { ClayReadout(tint, stringResource(R.string.clay_coolant), "${units.temp(it)}°", it >= COOLANT_WARNING_C, readSize) }
                            g.voltage?.let { ClayReadout(tint, stringResource(R.string.clay_battery), String.format(Locale.getDefault(), "%.1f V", it), it !in BATTERY_OK_V, readSize) }
                            g.engineLoadPct?.let { ClayReadout(tint, stringResource(R.string.clay_load), "$it %", false, readSize) }
                        }
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (innerH >= 140.dp) {
                            ClayIconBall(Icons.Rounded.DirectionsCar, ClayHue.AQUA, (innerH.value * 0.3f).coerceIn(40f, 72f).dp)
                            Spacer(Modifier.width(14.dp))
                        }
                        ClayLine(obdStatusLabel(state), clayText((readSize * 1.3f).coerceAtMost(28f), DashColors.TextPrimary, FontWeight.Black), maxLines = 2)
                    }
                    when (state) {
                        ObdConnectionState.CONNECTING -> ClayLine(stringResource(R.string.horizon_obd_connecting), clayText(readSize, tint.ink, FontWeight.Bold))
                        else -> ClayFitRow(10.dp) {
                            ClayPillButton(stringResource(R.string.horizon_connect_obd), enabled = !env.editing, onClick = env.onConnectObd)
                            if (state == ObdConnectionState.ERROR) {
                                ClayPillButton(stringResource(R.string.tape_cd_choose_adapter), enabled = !env.editing, filled = false, onClick = env.onPickDevice)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Range ------------------------------------------------------------------------

/**
 * Fuel and range on a mint clay card (peach on the reserve): FUEL and the
 * level, the range big in green, a groove with a puffy capsule of fuel, the
 * litres under it on a tall card. Fuel not learned yet: the standard tile on
 * the clay, which explains how to learn it.
 */
@Composable
private fun ClayRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        Box(Modifier.fillMaxSize().clay(ClayHue.MINT.tint, editing = env.editing)) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val tint = (if (low) ClayHue.PEACH else ClayHue.MINT).tint
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().clay(tint, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight) + 2.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val grooveH = (innerH * 0.12f).coerceIn(16.dp, 30.dp)
        val lineSize = (innerH.value * 0.09f).coerceIn(15f, 20f)
        val showLine = innerH >= 160.dp
        val numH = innerH - 22.dp - grooveH - 24.dp - if (showLine) (lineSize * 1.3f).dp else 0.dp
        val numSize = clayFit("888", FontWeight.Black, innerW * 0.62f, numH, 26f, 150f)
        val ink = if (low) DashColors.Warning else tint.ink
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClayLine(stringResource(R.string.clay_fuel), clayText(17f, DashColors.TextSecondary, FontWeight.Bold), Modifier.weight(1f))
                ClayLine("${fuel.percent} %", clayText(17f, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Black))
            }
            Row {
                ClayLine(units.distance(fuel.rangeKm).toString(), clayText(numSize, ink, FontWeight.Black, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width((numSize * 0.08f).dp))
                ClayLine(units.distanceUnit, clayText((numSize * 0.3f).coerceAtLeast(16f), ink, FontWeight.Bold), Modifier.alignByBaseline())
            }
            ClayGroove(tint, if (low) DashColors.Warning else tint.ink, fuel.percent / 100f, Modifier.fillMaxWidth().height(grooveH))
            if (showLine) {
                ClayLine(
                    (if (fuel.percentEstimated) "≈ " else "") + LocalUnits.current.volumeText(fuel.liters),
                    clayText(lineSize, DashColors.TextSecondary, FontWeight.SemiBold)
                )
            }
        }
    }
}

// --- Apps -------------------------------------------------------------------------

/** The pastels the app keys take in turn, so a row of them reads as the design's dock. */
private val APP_HUES = arrayOf(ClayHue.SKY, ClayHue.PINK, ClayHue.MINT, ClayHue.BUTTER, ClayHue.LILAC, ClayHue.PEACH, ClayHue.AQUA)

/** An app's own pastel, the same wherever it sits. */
private fun appHue(packageName: String): ClayHue = APP_HUES[abs(packageName.hashCode() % APP_HUES.size)]

@Composable
private fun ClayAppGlyph(app: AppEntry?, size: Dp, ink: Color) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Rounded.Apps, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.8f))
}

/** App shortcut: a clay key in the app's pastel, its icon and name side by side on a wide tile, stacked on a square one. It squashes when pressed. */
@Composable
private fun ClayApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val tint = appHue(item.packageName).tint
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val mw = maxWidth
        val mh = maxHeight
        val row = mw >= mh * 1.5f && mw >= 180.dp
        Box(
            Modifier
                .fillMaxSize()
                .clayPress(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
                .clay(tint, (min(mw.value, mh.value) * 0.3f).coerceAtMost(CLAY_CARD.value).dp, editing = env.editing, depth = 0.7f)
                .semantics(mergeDescendants = true) { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            if (row) {
                val icon = (mh.value * 0.5f).coerceIn(30f, 72f).dp
                Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClayAppGlyph(app, icon, tint.ink)
                    Spacer(Modifier.width(14.dp))
                    ClayLine(label, clayText((icon.value * 0.4f).coerceIn(16f, 26f), DashColors.TextPrimary, FontWeight.Black), Modifier.weight(1f, fill = false))
                }
            } else {
                val icon = (min(mw.value, mh.value) * 0.42f).coerceIn(30f, 96f).dp
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ClayAppGlyph(app, icon, tint.ink)
                    if (mh >= 96.dp) {
                        Spacer(Modifier.height(8.dp))
                        ClayLine(label, clayText((icon.value * 0.3f).coerceIn(14f, 22f), DashColors.TextPrimary, FontWeight.Bold), align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/**
 * Launch bar: a row of clay keys, each in its app's pastel (icon, and the
 * name where there is room), and a lilac pencil key at the end. Apps that
 * would get a key under 64 dp are left out.
 */
@Composable
private fun ClayLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 14.dp
        val edit = min(maxHeight.value, 64f).coerceAtLeast(56f).dp
        val room = maxWidth - edit - gap
        val tall = maxHeight >= 150.dp
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = if (tall) per >= 90f else per >= 150f
        val icon = if (tall) (maxHeight.value * 0.34f).coerceIn(28f, 64f).dp else (maxHeight.value * 0.46f).coerceIn(24f, 48f).dp
        val labelSize = if (tall) (icon.value * 0.32f).coerceIn(14f, 20f) else (maxHeight.value * 0.2f).coerceIn(14f, 20f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clay(ClayHue.LILAC.tint, 28.dp, editing = env.editing).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ClayLine(stringResource(R.string.clay_launch_bar_empty), clayText(labelSize, DashColors.TextSecondary, FontWeight.SemiBold), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    val tint = appHue(pkg).tint
                    val base = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .clayPress(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                        .clay(tint, 26.dp, editing = env.editing, depth = 0.6f)
                        .semantics(mergeDescendants = true) { contentDescription = name }
                        .padding(horizontal = 10.dp)
                    if (tall) {
                        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            ClayAppGlyph(app, icon, tint.ink)
                            if (labels) {
                                Spacer(Modifier.height(8.dp))
                                ClayLine(name, clayText(labelSize, DashColors.TextPrimary, FontWeight.Bold), align = TextAlign.Center)
                            }
                        }
                    } else {
                        Row(base, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            ClayAppGlyph(app, icon, tint.ink)
                            if (labels) {
                                Spacer(Modifier.width(12.dp))
                                ClayLine(name, clayText(labelSize, DashColors.TextPrimary, FontWeight.Bold), Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                }
            }
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            val lilac = ClayHue.LILAC.tint
            Box(
                Modifier
                    .size(edit)
                    .clayPress(true, editLabel, env.onEditLaunchBar)
                    .clay(lilac, edit * 0.34f, depth = 0.5f)
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = lilac.ink, modifier = Modifier.size(24.dp))
            }
        }
    }
}
