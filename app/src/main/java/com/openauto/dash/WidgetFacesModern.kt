package com.openauto.dash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor

/*
 * The newer generic layouts: Orb (everything inside one circle, a round
 * player on the media widget), Liquid (the tile fills up with the reading),
 * Dot matrix, Poster (giant editorial type), Duo (colour block split) and
 * Island (a floating black capsule). Same [WidgetFace] as the classic ones in
 * WidgetFaces.kt, so every widget can wear them.
 */

private fun String.upper() = uppercase(Locale.getDefault())

/** White type and glassy controls for drawing over a picture or a colour. */
private fun FaceLook.overImage() = copy(
    ink = Color.White, dim = Color.White.copy(alpha = 0.72f), accent = Color.White, accent2 = Color.White,
    onAccent = Color(0xFF111111), fill = Color.White.copy(alpha = 0.18f), glow = null, warn = Color(0xFFFF8A80)
)

// --- Orb ----------------------------------------------------------------------------------

@Composable
internal fun OrbLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics, discClick: (() -> Unit)? = null) {
    val side = m.w / m.h >= 1.8f && (f.stats.isNotEmpty() || f.rows.isNotEmpty())
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (side) Arrangement.spacedBy(m.dp(6f)) else Arrangement.Center
    ) {
        val d = min(m.h - m.pad * 2f, if (side) m.w * 0.5f else m.w - m.pad * 2f).coerceAtLeast(24f)
        Orb(f, look, d, discClick)
        if (side) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2.6f), Alignment.CenterVertically)) {
                FaceHeader(f, look, m)
                if (f.stats.isNotEmpty()) f.stats.take(3).forEach { FaceStatBlock(it, look, m) }
                else FaceRows(f, look, m, 3)
            }
        }
    }
}

/**
 * The whole reading inside a disc of diameter [d] dp: the cover (or a colour) behind, the progress round the rim.
 * [onClick], when given, is taken by the disc alone: the clip keeps the corners of its square from answering.
 */
@Composable
private fun Orb(f: WidgetFace, look: FaceLook, d: Float, onClick: (() -> Unit)? = null) {
    val spin by rememberSpin(7000, f.active)
    val over = look.overImage()
    val inner = d * 0.88f
    val ringBrush = remember(look, f.alert) {
        if (f.alert) SolidColor(look.warn) else Brush.sweepGradient(listOf(look.accent, look.accent2, look.accent))
    }
    val fillBrush = remember(look) { Brush.sweepGradient(listOf(look.accent, look.accent2, look.accent)) }
    val shade = remember { Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.72f))) }
    Box(
        modifier = Modifier.size(d.dp).then(onClick?.let { Modifier.clip(CircleShape).clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center
    ) {
        // Its own layer: the glint's steps redraw the ring, not the words inside.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            val stroke = size.minDimension * 0.03f
            val tl = Offset(stroke / 2f, stroke / 2f)
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(look.track, 0f, 360f, false, tl, sz, style = Stroke(stroke))
            val fr = fraction01(f.fraction)
            if (fr > 0f) drawArc(ringBrush, -90f, 360f * fr, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            // A glint that runs round the rim while something plays.
            if (f.active) drawArc(Color.White.copy(alpha = 0.55f), spin - 90f, 24f, false, tl, sz, style = Stroke(stroke * 0.55f, cap = StrokeCap.Round))
        }
        Box(modifier = Modifier.size(inner.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            val art = f.art
            if (art != null) {
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(fillBrush))
                Icon(f.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.16f), modifier = Modifier.size((inner * 0.62f).dp))
            }
            Box(Modifier.fillMaxSize().background(shade))
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = (inner * 0.08f).dp, vertical = (inner * 0.1f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((inner * 0.02f).dp, Alignment.CenterVertically)
            ) {
                FaceText(f.title.upper(), over, textSize(max(inner * 0.05f, 8f)), color = over.dim, weight = FontWeight.SemiBold, letterSpacing = 0.14.em)
                val valueK = if (f.actions.isEmpty()) 0.30f else 0.22f
                if (f.textValue) {
                    FaceText(f.value, over, textSize(inner * valueK * 0.46f), weight = FontWeight.Bold, align = TextAlign.Center)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        FaceText(f.value, over, textSize(inner * valueK), weight = FontWeight.Bold, color = if (f.alert) over.warn else over.ink, letterSpacing = (-0.02).em)
                        if (f.unit.isNotEmpty()) FaceText(" " + f.unit, over, textSize(inner * valueK * 0.32f), color = over.dim, modifier = Modifier.padding(bottom = (inner * valueK * 0.14f).dp))
                    }
                }
                if (f.caption.isNotEmpty()) FaceText(f.caption, over, textSize(max(inner * 0.06f, 9f)), color = over.dim, align = TextAlign.Center)
                if (f.actions.isNotEmpty()) {
                    Spacer(Modifier.height((inner * 0.03f).dp))
                    val small = (inner * 0.17f).coerceIn(30f, 52f)
                    val big = (inner * 0.23f).coerceIn(38f, 66f)
                    // A small disc can't take three finger-sized controls: drop the side ones (previous first), never the main one.
                    var acts = f.actions.take(3)
                    fun width(a: List<FaceAction>) = a.sumOf { (if (it.primary) big else small).toDouble() } + small * 0.16f * (a.size - 1)
                    while (acts.size > 1 && width(acts) > inner * 0.8f) {
                        val drop = acts.indexOfFirst { !it.primary }.takeIf { it >= 0 } ?: acts.lastIndex
                        acts = acts.filterIndexed { i, _ -> i != drop }
                    }
                    RoundActions(acts, over, small, big)
                }
            }
        }
    }
}

/** Font size from a dp height, the way [FaceMetrics.sp] does it but without needing the metrics. */
@Composable
private fun textSize(dpValue: Float) = with(LocalDensity.current) { dpValue.dp.toSp() }

/** Circular controls: the primary one bigger and filled, the rest glassy. */
@Composable
private fun RoundActions(actions: List<FaceAction>, look: FaceLook, small: Float, big: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy((small * 0.16f).dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { a ->
            val s = if (a.primary) big else small
            Box(
                modifier = Modifier
                    .size(s.dp)
                    .clip(CircleShape)
                    .background(if (a.primary) look.accent else look.fill)
                    .clickable(enabled = a.enabled, role = Role.Button, onClick = a.onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    a.icon, contentDescription = a.label,
                    tint = (if (a.primary) look.onAccent else look.ink).copy(alpha = if (a.enabled) 1f else 0.4f),
                    modifier = Modifier.size((s * 0.55f).dp)
                )
            }
        }
    }
}

// --- Liquid -------------------------------------------------------------------------------

@Composable
internal fun LiquidLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val t by rememberLoop(6000)
    val level by animateFloatAsState((f.fraction ?: 0.35f).coerceIn(0.04f, 0.96f), tween(900), label = "liquid")
    val c1 = if (f.alert) look.warn else look.accent
    val c2 = if (f.alert) look.warn else look.accent2
    // The two wave outlines are rebuilt every step (20 a second) into the same
    // two paths, instead of a new hundred-point path each.
    val backWave = remember { Path() }
    val frontWave = remember { Path() }
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(look.radius))) {
        // Its own layer: the waves' steps redraw the water, not the words over it.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            val w = size.width
            val h = size.height
            val top = h * (1f - level)
            val amp = size.minDimension * 0.035f
            fun wave(into: Path, k: Float, phase: Float, lift: Float): Path = into.apply {
                rewind()
                moveTo(0f, h)
                var x = 0f
                val step = 4.dp.toPx()
                while (x <= w + step) {
                    lineTo(x, top + lift + amp * sin(2f * PI.toFloat() * (k * x / w + phase)))
                    x += step
                }
                lineTo(w, h)
                close()
            }
            drawPath(wave(backWave, 1.1f, t, -amp * 0.6f), Brush.verticalGradient(listOf(c2.copy(alpha = 0.30f), c2.copy(alpha = 0.12f)), top, h))
            drawPath(wave(frontWave, 1.6f, -t + 0.25f, 0f), Brush.verticalGradient(listOf(c1.copy(alpha = 0.55f), c1.copy(alpha = 0.22f)), top, h))
        }
        Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.8f))) {
            FaceHeader(f, look, m)
            Spacer(Modifier.weight(1f))
            FaceValue(f, look, m, min(m.h * 0.34f, m.w * 0.25f))
            if (f.caption.isNotEmpty() || f.actions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FaceCaption(f, look, m, Modifier.weight(1f))
                    FaceActions(f, look.copy(fill = look.ink.copy(alpha = 0.10f)), m, small = true)
                }
            }
        }
    }
}

// --- Dot matrix ---------------------------------------------------------------------------

/** 5x7 glyphs, one row per entry, bit 4 the leftmost dot. */
private val GLYPHS: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
    '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
    '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
    '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
    '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
    '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
    '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
    '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
    '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
    '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
    ':' to intArrayOf(0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00),
    '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C),
    ',' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x0C, 0x04, 0x08),
    '-' to intArrayOf(0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
    '+' to intArrayOf(0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00),
    '%' to intArrayOf(0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03),
    '°' to intArrayOf(0x0C, 0x12, 0x12, 0x0C, 0x00, 0x00, 0x00),
    '/' to intArrayOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x00),
    ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
    'A' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11),
    'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
    'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
    'D' to intArrayOf(0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
    'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
    'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
    'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
    'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
    'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
    'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
    'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
    'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
    'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
    'N' to intArrayOf(0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11),
    'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
    'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
    'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
    'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
    'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
    'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
    'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A),
    'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
    'Y' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04),
    'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F)
)

@Composable
internal fun DotsLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val text = f.value.upper()
    val matrix = !f.textValue && text.isNotEmpty() && text.length <= 8 && text.all { it in GLYPHS }
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.2f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(m.dp(3.2f).coerceIn(6.dp, 10.dp)).clip(CircleShape).background(look.accent))
            Spacer(Modifier.width(m.dp(2.4f)))
            FaceText(f.title.upper(), look, m.label, Modifier.weight(1f), color = look.dim, weight = look.labelWeight, letterSpacing = 0.16.em)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (matrix) {
                val cols = text.length * 6 - 1
                val unitCols = if (f.unit.isEmpty()) 0f else f.unit.length * 1.5f + 1.5f
                val pitch = min(maxWidth.value / (cols + unitCols), maxHeight.value * 0.92f / 7f).coerceIn(1f, m.u * 8f)
                Row(verticalAlignment = Alignment.Bottom) {
                    Canvas(modifier = Modifier.width((cols * pitch).dp).height((7 * pitch).dp)) {
                        val p = pitch.dp.toPx()
                        val lit = if (f.alert) look.warn else look.ink
                        text.forEachIndexed { i, ch ->
                            val g = GLYPHS.getValue(ch)
                            for (row in 0 until 7) for (col in 0 until 5) {
                                val on = g[row] shr (4 - col) and 1 == 1
                                val c = Offset((i * 6 + col + 0.5f) * p, (row + 0.5f) * p)
                                if (on) drawCircle(lit, p * 0.40f, c) else drawCircle(look.ink.copy(alpha = 0.08f), p * 0.18f, c)
                            }
                        }
                    }
                    if (f.unit.isNotEmpty()) {
                        Spacer(Modifier.width((pitch * 1.2f).dp))
                        FaceText(f.unit.upper(), look, m.sp(pitch * 2.2f), color = look.dim, family = look.numFont, modifier = Modifier.padding(bottom = (pitch * 0.2f).dp))
                    }
                }
            } else {
                FaceText(f.value, look, m.sp(min(m.h * 0.15f, m.w * 0.09f)), weight = FontWeight.Medium, family = look.numFont, maxLines = 2)
            }
        }
        FaceCaption(f, look, m)
        val fraction = f.fraction
        if (fraction != null) {
            Canvas(modifier = Modifier.fillMaxWidth().height(m.dp(2.6f).coerceAtLeast(6.dp))) {
                val step = size.height * 1.6f
                val n = max(1, (size.width / step).toInt())
                val on = (fraction01(fraction) * n).toInt()
                for (i in 0 until n) {
                    val c = Offset(i * step + size.height / 2f, size.height / 2f)
                    when {
                        i < on - 1 -> drawCircle(look.ink, size.height * 0.42f, c)
                        i == on - 1 -> drawCircle(look.accent, size.height * 0.5f, c)
                        else -> drawCircle(look.ink.copy(alpha = 0.14f), size.height * 0.3f, c)
                    }
                }
            }
        }
        if (f.actions.isNotEmpty()) FaceActions(f, look.copy(fill = Color.Transparent), m, small = true)
    }
}

// --- Poster -------------------------------------------------------------------------------

/** Roboto Condensed Bold: [CondensedFamily] is fixed at the regular weight. */
private val PosterFamily = androidx.compose.ui.text.font.FontFamily(
    android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
)

/** Lays [this] out turned a quarter to the left, reading bottom to top. */
private fun Modifier.readingUp(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(
        Constraints(maxWidth = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity, maxHeight = constraints.maxWidth)
    )
    layout(p.height, p.width) {
        p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

@Composable
internal fun PosterLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val block = (m.u * 24f).coerceIn(34f, 72f)
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(look.radius)).clipToBounds()) {
        // The accent block with the icon, and the title running up the left edge under it.
        Column(modifier = Modifier.fillMaxHeight().width(block.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(block.dp).background(
                    Brush.linearGradient(listOf(look.accent, look.accent2)),
                    RoundedCornerShape(bottomEnd = (block * 0.4f).dp)
                ),
                contentAlignment = Alignment.Center
            ) { Icon(f.icon, contentDescription = null, tint = look.onAccent, modifier = Modifier.size((block * 0.48f).dp)) }
            if (m.h >= 110f) {
                Box(modifier = Modifier.weight(1f).padding(vertical = m.dp(3f)), contentAlignment = Alignment.TopCenter) {
                    FaceText(
                        f.title.upper(), look, m.label, Modifier.readingUp(),
                        color = look.dim, weight = FontWeight.Bold, letterSpacing = 0.22.em
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(start = (block + 8f).dp, top = m.pad.dp * 0.8f, end = m.pad.dp * 0.8f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(3f), Alignment.End)
        ) {
            if (f.caption.isNotEmpty()) FaceCaption(f, look, m, Modifier.weight(1f, fill = false), align = TextAlign.End)
            FaceActions(f, look, m, small = true)
        }
        // The reading, as big as the tile allows, spilling off the bottom edge.
        val room = m.w - block - m.pad * 0.5f
        val color = if (f.alert) look.warn else look.ink
        if (f.textValue) {
            FaceText(
                f.value, look, m.sp(min(m.h * 0.2f, m.w * 0.11f)),
                Modifier.align(Alignment.BottomStart).padding(start = (block + 6f).dp, end = m.pad.dp, bottom = m.pad.dp),
                color = color, weight = FontWeight.Bold, family = PosterFamily, maxLines = 2, letterSpacing = (-0.02).em
            )
        } else {
            val top = if (f.actions.isEmpty() && f.caption.isEmpty()) 0.74f else 0.58f
            val size = min(m.h * top, room / (f.value.length * 0.5f + f.unit.length * 0.2f + 0.2f)).coerceAtLeast(10f)
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = (size * 0.12f).dp).padding(end = (m.pad * 0.5f).dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    f.value,
                    color = color,
                    fontSize = m.sp(size),
                    lineHeight = m.sp(size * 0.86f),
                    fontFamily = PosterFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.04).em,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
                // After the number, as it is read: "20 °C", not "°C 20".
                if (f.unit.isNotEmpty()) {
                    FaceText(f.unit.upper(), look, m.sp(size * 0.18f), color = look.accent, weight = FontWeight.Bold,
                        letterSpacing = 0.08.em, modifier = Modifier.padding(bottom = (size * 0.24f).dp, start = (size * 0.04f).dp))
                }
            }
        }
    }
}

// --- Duo ----------------------------------------------------------------------------------

@Composable
internal fun DuoLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val stacked = m.h > m.w * 1.1f
    val on = look.copy(ink = look.onAccent, dim = look.onAccent.copy(alpha = 0.72f), warn = look.onAccent, glow = null, ghost = null)
    val panel: @Composable (Modifier) -> Unit = { mod ->
        Column(
            modifier = mod
                .background(Brush.linearGradient(listOf(if (f.alert) look.warn else look.accent, if (f.alert) look.warn else look.accent2)))
                .padding(m.pad.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(f.icon, contentDescription = null, tint = on.ink, modifier = Modifier.size(m.dp(9f).coerceIn(16.dp, 26.dp)))
                Spacer(Modifier.width(6.dp))
                FaceText(f.title.upper(), on, m.label, Modifier.weight(1f), color = on.dim, weight = FontWeight.Bold, letterSpacing = 0.12.em)
            }
            val big = if (stacked) min(m.w * 0.3f, m.h * 0.2f) else min(m.h * 0.34f, m.w * 0.13f)
            if (f.textValue) FaceText(f.value, on, m.sp(big * 0.5f), weight = FontWeight.Bold, maxLines = 2)
            else FaceValue(f, on, m, big)
        }
    }
    val details: @Composable (Modifier) -> Unit = { mod ->
        Column(modifier = mod.padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.6f), Alignment.CenterVertically)) {
            FaceCaption(f, look, m)
            when {
                f.stats.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
                    f.stats.take(2).forEach { FaceStatBlock(it, look, m, Modifier.weight(1f, fill = false)) }
                }
                f.rows.isNotEmpty() -> FaceRows(f, look, m, 2)
            }
            f.fraction?.let { FaceProgress(it, look, m, Modifier.fillMaxWidth(), f.alert) }
            FaceActions(f, look, m, small = true)
        }
    }
    val shape = RoundedCornerShape(look.radius)
    if (stacked) {
        Column(modifier = Modifier.fillMaxSize().clip(shape)) {
            panel(Modifier.fillMaxWidth().weight(0.45f))
            details(Modifier.fillMaxWidth().weight(0.55f))
        }
    } else {
        Row(modifier = Modifier.fillMaxSize().clip(shape)) {
            panel(Modifier.fillMaxHeight().weight(0.42f))
            details(Modifier.fillMaxHeight().weight(0.58f))
        }
    }
}

// --- Island -------------------------------------------------------------------------------

private val IslandBlack = Color(0xFF050506)

@Composable
internal fun IslandLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val isl = look.overImage().copy(accent = look.accent, accent2 = look.accent2, onAccent = look.onAccent, fill = Color.White.copy(alpha = 0.12f))
    val chips = f.stats.isNotEmpty() && m.h >= 150f
    val fraction = f.fraction
    val extras = (if (fraction != null) 12f else 0f) + (if (chips) 40f else 0f)
    val pillH = min(m.h - m.pad * 2f - extras, m.w * 0.34f).coerceIn(36f, 120f)
    // Three where the island is wide enough (a 3-column tile is), so the
    // volume keeps both of its keys; squeezed, media keeps play and next and
    // everything else its first two.
    val room = if (m.w >= 220f) 3 else 2
    val acts = f.actions.let { all ->
        if (all.size <= room) all
        else { val i = all.indexOfFirst { it.primary }.coerceAtLeast(0); listOfNotNull(all.getOrNull(i), all.getOrNull(i + 1)).ifEmpty { all.take(2) } }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOval(
                Brush.radialGradient(listOf(look.accent.copy(alpha = 0.28f), Color.Transparent), center, size.width * 0.5f),
                Offset(size.width * 0.05f, size.height * 0.15f), Size(size.width * 0.9f, size.height * 0.7f)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(m.pad.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val pill = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillH.dp)
                    .clip(pill)
                    .background(IslandBlack)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), pill)
                    .padding(horizontal = (pillH * 0.14f).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val d = pillH * 0.72f
                Box(modifier = Modifier.size(d.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    val art = f.art
                    if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(look.accent, look.accent2))))
                        Icon(f.icon, contentDescription = null, tint = look.onAccent, modifier = Modifier.size((d * 0.52f).dp))
                    }
                }
                Spacer(Modifier.width((pillH * 0.16f).dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    FaceText(f.title.upper(), isl, textSize(max(pillH * 0.12f, 8f)), color = isl.dim, weight = FontWeight.SemiBold, letterSpacing = 0.12.em)
                    if (f.textValue) {
                        FaceText(f.value, isl, textSize(pillH * 0.2f), weight = FontWeight.Bold)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            FaceText(f.value, isl, textSize(pillH * 0.32f), weight = FontWeight.SemiBold, color = if (f.alert) isl.warn else isl.ink)
                            if (f.unit.isNotEmpty()) FaceText(" " + f.unit, isl, textSize(pillH * 0.13f), color = isl.dim, modifier = Modifier.padding(bottom = (pillH * 0.04f).dp))
                        }
                    }
                    if (f.caption.isNotEmpty() && pillH >= 56f) FaceText(f.caption, isl, textSize(max(pillH * 0.13f, 9f)), color = isl.dim)
                }
                if (acts.isNotEmpty()) {
                    Spacer(Modifier.width((pillH * 0.1f).dp))
                    val s = (pillH * 0.5f).coerceIn(28f, 50f)
                    RoundActions(acts, isl.copy(accent = Color.White, onAccent = IslandBlack), s, s)
                }
            }
            if (fraction != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.84f).height(3.dp).clip(CircleShape).background(look.track)
                ) {
                    Box(
                        // fraction01: coerceIn lets NaN through, and fillMaxWidth(NaN) throws on the dashboard.
                        Modifier.fillMaxHeight().fillMaxWidth(fraction01(fraction)).clip(CircleShape)
                            .background(if (f.alert) Brush.horizontalGradient(listOf(look.warn, look.warn)) else Brush.horizontalGradient(listOf(look.accent, look.accent2)))
                    )
                }
            }
            if (chips) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.stats.take(3).forEach { s ->
                        Row(
                            modifier = Modifier.clip(pill).background(IslandBlack).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaceText(s.label.upper(), isl, m.sp(max(m.u * 4.6f, 9f)), color = isl.dim, letterSpacing = 0.08.em)
                            Spacer(Modifier.width(6.dp))
                            FaceText(s.value, isl, m.sp(max(m.u * 5.4f, 10f)), weight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
