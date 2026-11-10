package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.rememberReadings
import com.openauto.dash.vehicle.K
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Cockpit skin: a real instrument cluster on stitched leather. Chrome-ringed
 * analog dials with orange backlit numerals and red-orange needles, amber
 * dot-matrix LCDs, aircraft toggle switches for apps and a round instrument
 * screen framing a docked Google Maps window. By day (DashColors.Light) it is
 * a tan leather interior: ivory dial faces printed in black, reflective
 * grey-green LCDs with dark segments and pale switch plates; the chrome, the
 * red zones and the red-orange needles stay.
 *
 * Performance on the Mali head unit: textures are small tiled bitmaps drawn
 * with one shader call, static artwork is cached (drawWithCache on its own
 * layer), and moving parts (needles, clock hands, VU meters) only change a
 * layer's rotation, so animation never re-records the artwork.
 */

// --- Materials ------------------------------------------------------------------

// Getters pick the night or day version of a material, so anything that bakes
// one in must read it inside its cache block or key on DashColors.Light.
// Chrome, the red zone and the engraving on chrome are the same day and night.

private val Leather get() = if (DashColors.Light) Color(0xFFD2B792) else Color(0xFF231C16)
private val CowlTop get() = if (DashColors.Light) Color(0xFFEADFCD) else Color(0xFF1D1915)
private val CowlBottom get() = if (DashColors.Light) Color(0xFFD6C3A7) else Color(0xFF0B0A09)
private val StitchBrown get() = if (DashColors.Light) Color(0xFF6B4527) else Color(0xFF8A6040)
private val Chrome0 = Color(0xFFF7F6F2)
private val Chrome1 = Color(0xFF8E8B86)
private val Chrome2 = Color(0xFFEFEDE8)
private val Chrome3 = Color(0xFF46433F)
private val ChromeShade = Color(0xFF6E6B66)
private val BezelBlack get() = if (DashColors.Light) Color(0xFF3A2E24) else Color(0xFF050404)
private val Ledge get() = if (DashColors.Light) Color(0xFF4B3C2F) else Color(0xFF0A0908)
private val FaceCentre get() = if (DashColors.Light) Color(0xFFF4EFE4) else Color(0xFF1C1A18)
private val FaceEdge get() = if (DashColors.Light) Color(0xFFDCD4C4) else Color(0xFF070606)
private val SubFace get() = if (DashColors.Light) Color(0xFFE2DBCC) else Color(0xFF121110)
private val PodFace get() = if (DashColors.Light) Color(0xFFF2EDE2) else Color(0xFF0B0A09)
private val TickInk get() = if (DashColors.Light) Color(0xFF1A1714) else Color(0xFFEDE6DA)
private val SubPin get() = if (DashColors.Light) Color(0xFF2E2A26) else Color(0xFFD9D6D0)
private val RedZone = Color(0xFFD8261C)
private val LcdBack get() = if (DashColors.Light) Color(0xFFBAC0A2) else Color(0xFF1C1206)
private val LcdRim get() = if (DashColors.Light) Color(0xFF6E6150) else Color(0xFF3A2A18)
private val EngraveInk = Color(0xFF1E1B18)
private val Plate get() = if (DashColors.Light) Color(0xFFE6E2D9) else Color(0xFF0E0C0A)
private val Bushing = Color(0xFF2A2723)
private val HubPin = Color(0xFF1A1816)
private val LampOff get() = if (DashColors.Light) Color(0xFFAE9E87) else Color(0xFF3A332C)

/** LCD segment ink: backlit amber at night, dark segments on the reflective day LCD. */
private val LcdInk get() = if (DashColors.Light) Color(0xFF1F251A) else DashColors.Secondary

/** Halo strength of lamps and LEDs: full at night, half by day, where a glow reads as a stain. */
private val Halo get() = if (DashColors.Light) 0.5f else 1f

/** Black for a cast shadow; a bit softer by day on the light leather. */
private fun castShadow(alpha: Float): Color = Color.Black.copy(alpha = if (DashColors.Light) alpha * 0.6f else alpha)

/** Turned chrome: bright, dark band, bright again, dark edge (also an alert's bezel, AlertChrome.kt). */
internal val CockpitChromeStops: Array<Pair<Float, Color>> = arrayOf(0f to Chrome0, 0.38f to Chrome1, 0.56f to Chrome2, 1f to Chrome3)

/** Diagonal turned-chrome. */
private fun chromeBrush(from: Offset, to: Offset): Brush = Brush.linearGradient(*CockpitChromeStops, start = from, end = to)

/** Vertical chrome for the pill buttons. */
private fun pillBrush(height: Float): Brush = Brush.verticalGradient(
    0f to Color(0xFFF6F5F1), 0.46f to Color(0xFFC4C1BA), 0.54f to Color(0xFF8F8C86), 1f to Color(0xFFDAD7D0),
    startY = 0f, endY = height
)

/** The middle of the area being drawn, for cache blocks (DrawScope has its own). */
private val CacheDrawScope.center: Offset get() = Offset(size.width / 2f, size.height / 2f)

/** Point at [r] from [c] on a dial where 0° is 12 o'clock and angles run clockwise. */
private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
}

/**
 * Leather grain: soft light and dark specks on a 64 px tile, wrapped so it
 * repeats without seams. By [day] the dark specks turn a fainter brown and the
 * light ones brighter, so tan leather reads as grain rather than dirt.
 */
private fun grainTile(day: Boolean): ShaderBrush {
    val n = 64
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val rnd = java.util.Random(11)
    fun speck(argb: Int) {
        paint.color = argb
        val x = rnd.nextFloat() * n
        val y = rnd.nextFloat() * n
        val r = 0.6f + rnd.nextFloat() * 0.9f
        for (dx in intArrayOf(-n, 0, n)) for (dy in intArrayOf(-n, 0, n)) canvas.drawCircle(x + dx, y + dy, r, paint)
    }
    repeat(170) {
        speck(
            if (day) android.graphics.Color.argb(16 + rnd.nextInt(24), 70, 40, 16)
            else android.graphics.Color.argb(40 + rnd.nextInt(45), 0, 0, 0)
        )
    }
    repeat(120) {
        speck(android.graphics.Color.argb(if (day) 28 + rnd.nextInt(30) else 8 + rnd.nextInt(10), 255, 255, 255))
    }
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private val nightGrain by lazy { grainTile(day = false) }
private val dayGrain by lazy { grainTile(day = true) }

/** The grain tile for the current appearance; each is built once, on first use. */
private val grainBrush: ShaderBrush get() = if (DashColors.Light) dayGrain else nightGrain

/** One repeating tile: a dark dot per LCD pixel, or a dark line per scanline; fainter by [day]. */
private fun patternTile(pitch: Int, scanline: Boolean, day: Boolean): ImageBitmap {
    val bmp = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    if (scanline) {
        paint.color = android.graphics.Color.argb(if (day) 26 else 52, 0, 0, 0)
        canvas.drawRect(0f, 0f, pitch.toFloat(), 1f, paint)
    } else {
        paint.color = android.graphics.Color.argb(if (day) 34 else 90, 0, 0, 0)
        canvas.drawCircle(pitch / 2f, pitch / 2f, pitch * 0.3f, paint)
    }
    return bmp.asImageBitmap()
}

/** Dot-matrix overlay for LCDs (3 dp pitch), one tile per density and appearance. */
@Composable
private fun rememberDotMatrix(): ShaderBrush {
    val density = LocalDensity.current.density
    val day = DashColors.Light
    return remember(density, day) {
        val pitch = (3f * density).roundToInt().coerceAtLeast(2)
        ShaderBrush(ImageShader(patternTile(pitch, scanline = false, day = day), TileMode.Repeated, TileMode.Repeated))
    }
}

/** Scanlines for the instrument screen: a 1 px dark line every 3 dp, one tile per density and appearance. */
@Composable
private fun rememberScanlines(): ShaderBrush {
    val density = LocalDensity.current.density
    val day = DashColors.Light
    return remember(density, day) {
        val pitch = (3f * density).roundToInt().coerceAtLeast(2)
        ShaderBrush(ImageShader(patternTile(pitch, scanline = true, day = day), TileMode.Repeated, TileMode.Repeated))
    }
}

// --- Type -------------------------------------------------------------------------

/** Engraved caps: condensed, letter-spaced, sunk into the dash (a dark lip at night, a lit one by day). */
private fun engraved(size: TextUnit, color: Color = DashColors.TextSecondary): TextStyle = TextStyle(
    color = color,
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = readableCaps(size),
    letterSpacing = 0.18.em,
    shadow = if (DashColors.Light) {
        Shadow(Color.White.copy(alpha = 0.55f), Offset(0f, 1.5f), 1f)
    } else {
        Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1.5f), 1f)
    }
)

/**
 * LCD text in monospace: backlit with a soft glow of its own colour at night;
 * by day dark segments casting a faint shadow on the reflector behind them.
 * Blurred only with effects at full (see [softTextShadow]).
 */
private fun lcd(size: TextUnit, color: Color): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.Monospace,
    fontSize = size,
    shadow = if (DashColors.Light) {
        val d = (size.value * 0.06f).coerceAtLeast(1f)
        softTextShadow(color.copy(alpha = 0.22f * color.alpha), d, Offset(d, d * 1.3f))
    } else {
        softTextShadow(color.copy(alpha = 0.6f * color.alpha), (size.value * 0.45f).coerceAtLeast(3f))
    }
)

// --- Clocks & motion ----------------------------------------------------------------

/**
 * The small idle tremble of a live needle, in degrees: ambient motion, so it
 * steps with the ambient ticker. Callers skip it with effects off.
 */
@Composable
private fun rememberWobble(): State<Float> {
    val loop = rememberLoop(1800, reverse = true)
    return remember(loop) { derivedStateOf { -1.2f + 2.6f * FastOutSlowInEasing.transform(loop.value) } }
}

/** Step of a clock's second hand: four a second with full effects (a smooth sweep), one a second otherwise. */
private val secondHandStepMs: Long get() = if (DashColors.Effects == DashEffects.FULL) 250L else 1_000L

/** Hours and minutes out of an "HH:mm" or "h:mm a" string (the dial is 12 hours either way), or the current time if it does not parse. */
private fun parseClock(clock: String): Pair<Int, Int> {
    val parts = clock.trim().split(':')
    val h = parts.getOrNull(0)?.toIntOrNull()
    val m = parts.getOrNull(1)?.take(2)?.toIntOrNull()
    if (h != null && m != null) return h to m
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
}

// --- Page background ----------------------------------------------------------------

/**
 * Cached geometry and materials of the leather page for one screen size and
 * appearance: the cowl hood, its edge and stitching. Build it inside a cache
 * block, which then rebuilds when day/night switches.
 */
private class Backdrop(val size: Size, private val density: Float) {
    private fun dp(v: Float) = v * density
    private fun curve(dy: Float): Path {
        val sx = size.width / 1280f
        return Path().apply {
            moveTo(0f, dp(150f + dy))
            cubicTo(200f * sx, dp(60f + dy), 420f * sx, dp(70f + dy), 640f * sx, dp(70f + dy))
            cubicTo(860f * sx, dp(70f + dy), 1080f * sx, dp(60f + dy), size.width, dp(150f + dy))
        }
    }

    val edge = curve(0f)
    val stitch = curve(15f)
    val cowl = curve(0f).apply {
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    private val light = DashColors.Light
    val leather = Leather
    val grain = grainBrush
    val cowlBrush = Brush.verticalGradient(listOf(CowlTop, CowlBottom), startY = dp(60f), endY = size.height)
    val edgeStroke = Stroke(dp(2f))
    val edgeLight = Color.White.copy(alpha = if (light) 0.3f else 0.08f)
    val stitchStroke = Stroke(dp(2f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(9f), dp(7f))))
    val stitchColor = StitchBrown
    val stitchShade = castShadow(0.5f)
    val shadeOffset = dp(1.2f)
    val vignette = Brush.radialGradient(
        0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = if (light) 0.14f else 0.38f),
        center = Offset(size.width / 2f, size.height * 0.45f),
        radius = max(size.width, size.height) * 0.72f
    )
}

/** Leather, the cowl (darker at night, lighter by day) with its highlight and stitching, grain over all, a soft vignette. */
private fun DrawScope.drawBackdrop(b: Backdrop) {
    drawRect(b.leather, size = b.size)
    drawPath(b.cowl, b.cowlBrush)
    drawRect(b.grain, size = b.size)
    drawPath(b.edge, b.edgeLight, style = b.edgeStroke)
    translate(top = b.shadeOffset) { drawPath(b.stitch, b.stitchShade, style = b.stitchStroke) }
    drawPath(b.stitch, b.stitchColor, style = b.stitchStroke)
    drawRect(b.vignette, size = b.size)
}

/** The whole-screen page: stitched leather with the instrument cowl. Static and cached per size and appearance. */
@Composable
internal fun cockpitBackground(): Modifier = Modifier.drawWithCache {
    val backdrop = Backdrop(size, density)
    onDrawBehind { drawBackdrop(backdrop) }
}

// --- Top bar ------------------------------------------------------------------------

/**
 * The dash top: chrome APPS / LAYOUT pills on the left, a chrome clock pod
 * with the time in LCD digits beside it and the dashboard's position in the middle, and on the right the setup pill, warning pills, the
 * outside-temperature LCD, the OBD lamp and a chrome ⋮ button. The same order as every other look's bar.
 */
@Composable
internal fun CockpitTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val narrow = maxWidth < NARROW_BAR
        // The same two words as every other look's bar, engraved on the pills where there is room.
        val labels = maxWidth >= LABELLED_BAR
        val shared = barIsShared()
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Each side gets half of what the clock leaves, so the clock stays
            // centred and nothing prints over it. An upright screen, or half of a
            // split one, has no room for that: the pills go down to their icons
            // and take only what they need, like the standard bar's.
            Row(
                modifier = if (narrow) Modifier else Modifier.weight(1f).wrapContentWidth(Alignment.Start, unbounded = true),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChromePill(onClick = m.onApps, description = if (labels) null else stringResource(R.string.dash_all_apps)) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = EngraveInk, modifier = Modifier.size(18.dp))
                    if (labels) {
                        Spacer(Modifier.width(8.dp))
                        PillLabel(stringResource(R.string.cockpit_apps))
                    }
                }
                LayoutPicker(m) { open ->
                    ChromePill(onClick = open, description = stringResource(R.string.cockpit_screen_layout_desc, m.layout.title)) {
                        LayoutIcon(m.layout, null, EngraveInk, Modifier.size(18.dp))
                        if (labels) {
                            Spacer(Modifier.width(8.dp))
                            PillLabel(stringResource(R.string.cockpit_layout))
                        }
                    }
                }
            }

            // The head unit's status bar shows the time while it is up. The pod is
            // the dash's ornament; the digits are what is read at a glance.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) {
                    if (!narrow) {
                        ClockPod(m.clock)
                        Spacer(Modifier.width(10.dp))
                    }
                    LcdPanel(Modifier.height(44.dp), corner = 8.dp) {
                        val (digits, amPm) = splitClock(m.clock)
                        Row(
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(digits, style = lcd(26.sp, LcdInk), maxLines = 1)
                            if (amPm != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(amPm, style = lcd(13.sp, LcdInk.copy(alpha = 0.7f)), maxLines = 1)
                            }
                        }
                    }
                }
                // Which dashboard is on screen, beside the time, as in every bar.
                PagePositionChip(Modifier.padding(start = 10.dp))
            }

            // ⋮ and the OBD lamp get their room first; what ranks last and no
            // longer fits beside the clock is left out (the outside temperature
            // first: the page's weather tile says the same).
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared)
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE)) { PhonePill() }
                if (!narrow) Box(Modifier.layoutId(BarRank.TEMP)) { OutsideTempLcd() }
                Box(Modifier.layoutId(BarRank.OBD).padding(start = 8.dp)) { ObdLamp(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE).padding(start = 6.dp)) {
                    MorePicker(m) { open ->
                        ChromePill(onClick = open, description = stringResource(R.string.cockpit_more), modifier = Modifier.width(60.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = EngraveInk, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Dark engraved caps on a chrome pill. */
@Composable
private fun PillLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        style = TextStyle(
            color = EngraveInk,
            fontFamily = CondensedFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 2.sp,
            shadow = Shadow(Color.White.copy(alpha = 0.6f), Offset(0f, 1f), 0f)
        )
    )
}

/** Chrome push button: a pill on a dark ledge that sinks a little while pressed; 56 dp tall. */
@Composable
private fun ChromePill(
    onClick: () -> Unit,
    description: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .drawWithCache {
                val drop = 3.dp.toPx()
                val bodyH = size.height - drop
                val corner = CornerRadius(12.dp.toPx())
                val body = pillBrush(bodyH)
                val line = 1.dp.toPx()
                val ledge = Ledge
                onDrawBehind {
                    val sink = if (pressed) drop * 0.7f else 0f
                    drawRoundRect(ledge, Offset(0f, drop), Size(size.width, bodyH), corner)
                    drawRoundRect(body, Offset(0f, sink), Size(size.width, bodyH), corner)
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.35f), Offset(line / 2f, sink + line / 2f),
                        Size(size.width - line, bodyH - line), corner, style = Stroke(line)
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.9f),
                        Offset(corner.x, sink + line * 1.5f), Offset(size.width - corner.x, sink + line * 1.5f), line
                    )
                    if (pressed) drawRoundRect(Color.Black.copy(alpha = 0.12f), Offset(0f, sink), Size(size.width, bodyH), corner)
                }
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .graphicsLayer { translationY = if (pressed) 2.dp.toPx() else 0f }
            .padding(start = 14.dp, end = 14.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) { content() }
}

/**
 * Chrome clock pod: hour and minute hands from [clock] ("HH:mm") and a
 * sweeping orange second hand, on a black face at night and an ivory one by day.
 */
@Composable
private fun ClockPod(clock: String, modifier: Modifier = Modifier) {
    val (hh, mm) = remember(clock) { parseClock(clock) }
    val wall = rememberWallClock(secondHandStepMs)
    val accent = DashColors.Accent
    val cream = DashColors.TextPrimary
    val timeLabel = stringResource(R.string.cockpit_time_desc, clock)
    Box(
        modifier = modifier
            .size(58.dp)
            .semantics { contentDescription = timeLabel }
            .drawWithCache {
                val r = size.minDimension / 2f
                val c = center
                val ring = chromeBrush(Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
                val faceR = r - 4.dp.toPx()
                val face = PodFace
                val shadow = Brush.radialGradient(
                    listOf(castShadow(0.6f), Color.Transparent),
                    center = c + Offset(0f, 3.dp.toPx()), radius = r + 7.dp.toPx()
                )
                val hourDeg = (hh % 12 + mm / 60f) * 30f
                val minuteDeg = mm * 6f
                fun hand(len: Float, halfW: Float) = Path().apply {
                    moveTo(c.x - halfW, c.y + halfW * 2f)
                    lineTo(c.x, c.y - len)
                    lineTo(c.x + halfW, c.y + halfW * 2f)
                    close()
                }
                val hourHand = hand(faceR * 0.52f, 1.6.dp.toPx())
                val minuteHand = hand(faceR * 0.82f, 1.1.dp.toPx())
                onDrawBehind {
                    drawCircle(shadow, r + 7.dp.toPx(), c + Offset(0f, 3.dp.toPx()))
                    drawCircle(ring, r, c)
                    drawCircle(face, faceR, c)
                    for (i in 0 until 60) {
                        val major = i % 5 == 0
                        val quarter = i % 15 == 0
                        val len = if (major) 4.5.dp.toPx() else 2.dp.toPx()
                        drawLine(
                            if (quarter) accent else if (major) cream else cream.copy(alpha = 0.4f),
                            polar(c, faceR - 1.dp.toPx(), i * 6f), polar(c, faceR - 1.dp.toPx() - len, i * 6f),
                            if (major) 1.6.dp.toPx() else 0.8.dp.toPx()
                        )
                    }
                    rotate(hourDeg, c) { drawPath(hourHand, cream) }
                    rotate(minuteDeg, c) { drawPath(minuteHand, cream) }
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = (wall.longValue % 60_000L) / 1000f * 6f }
                .drawWithCache {
                    val c = center
                    val r = size.minDimension / 2f - 4.dp.toPx()
                    onDrawBehind {
                        drawLine(accent, Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y - r * 0.9f), 1.2.dp.toPx(), StrokeCap.Round)
                        drawCircle(accent, 2.4.dp.toPx(), c)
                        drawCircle(Color(0xFF0B0A09), 1.dp.toPx(), c)
                    }
                }
        )
    }
}

/**
 * "OUT 20°C" LCD from the car's sensor or the weather feed; the value stays
 * dashed until the first one. Without a GPS fix or an answer it says so in a
 * word, so the dashes are not taken for a fetch still on its way.
 */
@Composable
private fun OutsideTempLcd() {
    val temp = rememberOutsideTempC()
    val wait = rememberWeatherWait()
    val ink = LcdInk
    LcdPanel(Modifier.height(40.dp).widthIn(min = 124.dp), corner = 8.dp) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (temp == null && wait != WeatherWait.LOADING) {
                Text(
                    stringResource(if (wait == WeatherWait.NO_GPS) R.string.info_no_gps else R.string.orbit_offline).uppercase(),
                    style = lcd(14.sp, ink.copy(alpha = 0.7f)),
                    maxLines = 1
                )
                return@Row
            }
            Text(stringResource(R.string.cockpit_out), style = lcd(14.sp, ink.copy(alpha = 0.7f)), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                temp?.let { LocalUnits.current.tempTight(it) } ?: "--",
                style = lcd(20.sp, if (temp == null) ink.copy(alpha = 0.4f) else ink),
                maxLines = 1
            )
        }
    }
}

/** OBD tell-tale: a glossy lamp, green when linked, dim when off; what a tap does is the shared [ObdMark]'s. */
@Composable
private fun ObdLamp(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = when (state) {
        ObdConnectionState.CONNECTED -> DashColors.Good
        ObdConnectionState.CONNECTING -> DashColors.Secondary
        ObdConnectionState.ERROR -> DashColors.Warning
        ObdConnectionState.DISCONNECTED -> LampOff
    }
    val lit = state != ObdConnectionState.DISCONNECTED
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    ObdMark(state, onConnect, shape = RoundedCornerShape(12.dp), clickLabel = stringResource(R.string.cockpit_connect_obd)) {
        // 10 dp each side: the lamp's halo stays inside the mark's clip.
        Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(15.dp)
                    .cachedDraw(color, lit, pulse) {
                        val r = size.minDimension / 2f
                        // The pulse only fades both gradients, so they are built once and drawn at its alpha.
                        val halo = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * Halo), Color.Transparent), center, r * 2.2f)
                        val glass = Brush.radialGradient(
                            listOf(lerp(color, Color.White, if (lit) 0.65f else 0.25f), color, lerp(color, Color.Black, 0.5f)),
                            center = center + Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.3f
                        )
                        val rim = Stroke(1.dp.toPx())
                        onDrawBehind {
                            val a = pulse?.let { 0.35f + 0.65f * it.value } ?: 1f
                            if (lit) drawCircle(halo, r * 2.2f, alpha = a)
                            drawCircle(glass, r, alpha = if (lit) a else 1f)
                            drawCircle(Color.Black.copy(alpha = 0.6f), r, style = rim)
                        }
                    }
            )
            Spacer(Modifier.width(7.dp))
            Text("OBD", style = engraved(15.sp), maxLines = 1)
        }
    }
}

// --- Shared parts -------------------------------------------------------------------

/**
 * LCD window sunk into the dash, with a dot-matrix grid over whatever
 * [content] draws and a faint reflection. At night dark brown glass with a
 * warm amber backlight; by day a reflective grey-green positive LCD, lit only
 * by the daylight on it.
 */
@Composable
private fun LcdPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val dots = rememberDotMatrix()
    val amber = DashColors.Secondary
    val light = DashColors.Light
    Box(
        modifier = modifier.drawWithCache {
            val r = CornerRadius(corner.toPx())
            val rim = 2.dp.toPx()
            val back = LcdBack
            val rimColor = LcdRim
            val inset = Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = if (light) 0.22f else 0.6f), Color.Transparent), startY = 0f, endY = 12.dp.toPx()
            )
            val backlight = Brush.radialGradient(
                listOf(if (light) Color.White.copy(alpha = 0.16f) else amber.copy(alpha = 0.08f), Color.Transparent),
                center = center, radius = max(size.width, size.height) * 0.6f
            )
            val sheen = Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (light) 0.22f else 0.05f), Color.Transparent),
                start = Offset.Zero, end = Offset(size.width * 0.3f, size.height)
            )
            onDrawWithContent {
                drawRoundRect(back, cornerRadius = r)
                drawRoundRect(backlight, cornerRadius = r)
                drawRoundRect(inset, cornerRadius = r)
                drawContent()
                drawRoundRect(dots, cornerRadius = r)
                drawRoundRect(sheen, cornerRadius = r)
                drawRoundRect(
                    rimColor, Offset(rim / 2f, rim / 2f), Size(size.width - rim, size.height - rim), r, style = Stroke(rim)
                )
            }
        },
        content = content
    )
}

/** Width in dp of [text] on an LCD at [size] dp: monospace, about 0.62 em a glyph. */
private fun lcdWidth(text: String, size: Dp): Dp = size * (text.length * 0.62f)

/**
 * Two dim lines centred on an LCD: a state ("NO ROUTE") and what a tap does.
 * The title shrinks to fit the [width] (never under 14 sp) and may take two lines.
 */
@Composable
private fun BoxScope.LcdMessage(title: String, hint: String, width: Dp, height: Dp) {
    val ink = LcdInk
    val room = width - 24.dp
    val titleSize = min(height * 0.2f, room / (title.length * 0.62f)).coerceIn(14.dp, 40.dp)
    Column(
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = lcd(titleSize.fixedSp(), ink.copy(alpha = 0.8f)),
            textAlign = TextAlign.Center,
            maxLines = if (lcdWidth(title, titleSize) <= room) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        if (height >= 70.dp) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = lcd((height * 0.1f).coerceIn(14.dp, 18.dp).fixedSp(), ink.copy(alpha = 0.75f)),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Round chrome push button with a concave centre; at least 48 dp. */
@Composable
private fun ChromeRoundButton(
    icon: ImageVector,
    description: String,
    diameter: Dp,
    enabled: Boolean,
    disc: Float = 0.92f,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val touch = if (diameter < 48.dp) 48.dp else diameter
    Box(
        modifier = Modifier
            .size(touch)
            .clickable(
                interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick
            )
            .semantics { contentDescription = description }
            .drawWithCache {
                val r = diameter.toPx() / 2f * disc
                val c = center
                val outer = chromeBrush(Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
                val innerR = r * 0.78f
                val inner = Brush.linearGradient(
                    0f to Chrome3, 0.5f to Color(0xFFBDBAB3), 1f to Chrome0,
                    start = Offset(c.x - innerR, c.y - innerR), end = Offset(c.x + innerR, c.y + innerR)
                )
                val shadowC = c + Offset(0f, r * 0.14f)
                val shadow = Brush.radialGradient(
                    listOf(castShadow(0.6f), Color.Transparent), center = shadowC, radius = r * 1.22f
                )
                val ledge = Ledge
                onDrawBehind {
                    drawCircle(shadow, r * 1.22f, shadowC)
                    drawCircle(ledge, r + 1.5.dp.toPx(), c)
                    drawCircle(outer, r, c)
                    drawCircle(inner, innerR, c)
                    if (pressed) drawCircle(Color.Black.copy(alpha = 0.22f), innerR, c)
                    if (!enabled) drawCircle(Color.Black.copy(alpha = 0.35f), r, c)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = EngraveInk.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier
                .size(diameter * 0.42f)
                .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
        )
    }
}

/** A warning-lamp icon: lit in [lit] with a soft halo, or unlit dark. */
@Composable
private fun TellTale(icon: ImageVector, lit: Color?, description: String, iconSize: Dp = 22.dp) {
    Icon(
        icon,
        contentDescription = description,
        tint = lit ?: LampOff,
        modifier = Modifier
            .size(iconSize)
            .then(if (lit != null) Modifier.glowHalo(lit.copy(alpha = 0.35f * Halo), 1f) else Modifier)
    )
}

// --- Dials --------------------------------------------------------------------------

/** A small gauge set into a dial face, in the dial's 380-unit design space. */
private class SubDial(val cx: Float, val cy: Float, val lo: String, val hi: String, val caption: String, val r: Float = 30f)

/** An LCD window on a dial face, in design units. */
private class LcdBox(val x: Float, val y: Float, val w: Float, val h: Float)

/** Static artwork of a chrome-ringed dial, in the 380-unit design space of the mockup; [lcd] null draws no readout window. */
private class DialFace(
    val labels: List<String>,
    val minor: Int,
    val numeral: Float,
    val title: String,
    val lcd: LcdBox?,
    val start: Float = -135f,
    val sweep: Float = 270f,
    val red: ClosedFloatingPointRange<Float>? = null,
    val subs: List<SubDial> = emptyList(),
    val titleY: Float = 140f,
    val titleSize: Float = 15f
)

/** The printed words on the dial faces, resolved in composition so faces can be built in plain code. */
private data class DialWords(
    val rpmTitle: String,
    val temp: String,
    val fuel: String,
    val volt: String,
    val load: String,
    val cold: String,
    val hot: String,
    val empty: String,
    val full: String,
    val fuelTitle: String
)

@Composable
private fun dialWords(): DialWords = DialWords(
    rpmTitle = stringResource(R.string.cockpit_dial_rpm_title),
    temp = stringResource(R.string.cockpit_dial_temp),
    fuel = stringResource(R.string.cockpit_dial_fuel),
    volt = stringResource(R.string.cockpit_dial_volt),
    load = stringResource(R.string.cockpit_dial_load),
    cold = stringResource(R.string.cockpit_dial_cold),
    hot = stringResource(R.string.cockpit_dial_hot),
    empty = stringResource(R.string.cockpit_dial_empty),
    full = stringResource(R.string.cockpit_dial_full),
    fuelTitle = stringResource(R.string.cockpit_dial_fuel_title)
)

private val LcdNormal = LcdBox(140f, 306f, 100f, 36f)
private val LcdTwoLine = LcdBox(132f, 294f, 116f, 52f)
private val LcdCompact = LcdBox(125f, 294f, 130f, 50f)

/** Tachometer, 0–7 ×1000 r/min, red from 6; coolant and (when known) fuel sub-dials. */
private fun tachFace(fuel: Boolean, compact: Boolean, w: DialWords, scale: RevScale) = DialFace(
    // Numbered per thousand up to the car's own top; red from its red line.
    labels = (0..(scale.top / 1000f).roundToInt()).map { it.toString() },
    minor = if (compact) 2 else 4,
    numeral = if (compact) 36f else 32f,
    title = w.rpmTitle,
    lcd = if (compact) LcdCompact else LcdNormal,
    red = scale.redFrom..1f,
    subs = when {
        compact -> emptyList()
        fuel -> listOf(SubDial(147f, 247f, w.cold, w.hot, w.temp), SubDial(233f, 247f, w.empty, w.full, w.fuel))
        else -> listOf(SubDial(190f, 250f, w.cold, w.hot, w.temp))
    },
    titleSize = if (compact) 20f else 15f
)

/**
 * Speedometer, 0–240 km/h or 0–150 mph: battery and engine-load sub-dials
 * when [subs], and an LCD [window] where the dial carries a readout (the revs
 * when it stands in for the tachometer, the speed's digits when it is the
 * whole speed tile); without one it is read by its needle alone.
 * Numbered every 20 km/h or every 10 mph (40 and 30 when compact), ticked
 * every 10 or 5.
 */
private fun speedFace(subs: Boolean, compact: Boolean, window: Boolean, w: DialWords, units: UnitSystem) = DialFace(
    labels = (0..speedoTop(units) step if (units.imperial) (if (compact) 30 else 10) else (if (compact) 40 else 20)).map { it.toString() },
    minor = if (units.imperial) (if (compact) 3 else 2) else (if (compact) 4 else 2),
    numeral = if (compact) 30f else 23f,
    title = units.speedUnit,
    lcd = if (window) LcdCompact else null,
    subs = if (subs && !compact) {
        listOf(SubDial(147f, 247f, "8", "16", w.volt), SubDial(233f, 247f, "0", "100", w.load))
    } else emptyList(),
    titleSize = if (compact) 20f else 15f
)

/** The speedometer's top figure in the shown unit. */
private fun speedoTop(units: UnitSystem): Int = units.speedScale(240)

/** Where [kmh] puts the speedometer's needle, 0 to 1. */
private fun speedoFraction(kmh: Int, units: UnitSystem): Float = units.speed(kmh) / speedoTop(units).toFloat()

/** Fuel gauge: a 120° arc over the hub, E to F, red below 12 %. */
private fun fuelFace(w: DialWords) = DialFace(
    labels = listOf(w.empty, "½", w.full),
    minor = 2,
    numeral = 36f,
    title = w.fuelTitle,
    lcd = LcdNormal,
    start = -60f,
    sweep = 120f,
    red = 0f..0.12f,
    titleY = 284f
)

/**
 * The dial's static face: bezel, face, red zone, ticks, numerals, title,
 * sub-dials, LCD window. At night a black face with glowing orange numerals;
 * by day an ivory face printed in black, like a classic white-face instrument.
 * Remembered on its inputs: the dial recomposes on every OBD sample, and a new
 * cache would measure all its lettering again.
 */
@Composable
private fun Modifier.dialFace(face: DialFace, measurer: TextMeasurer, accent: Color, cream: Color, lcdInk: Color): Modifier =
    cachedDraw(face, measurer, accent, cream, lcdInk) {
        val light = DashColors.Light
        val u = size.minDimension / 380f
        val c = center
        val bezel = chromeBrush(Offset(c.x - 183f * u, c.y - 183f * u), Offset(c.x + 183f * u, c.y + 183f * u))
        val faceBrush = Brush.radialGradient(listOf(FaceCentre, FaceEdge), center = Offset(c.x, c.y - 30f * u), radius = 205f * u)
        val shadow = Brush.radialGradient(
            listOf(castShadow(0.7f), Color.Transparent), center = c + Offset(0f, 10f * u), radius = 205f * u
        )
        val seam = BezelBlack
        val rimShade = castShadow(0.7f)
        val tick = TickInk
        val subFace = SubFace
        val subRing = if (light) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.10f)
        val lcdBack = LcdBack
        val numeral = if (light) tick else accent
        val intervals = (face.labels.size - 1) * face.minor
        // Lettering follows the dial's size, but never drops under 14 sp.
        val floor = 14.sp.toPx()
        val numStyle = TextStyle(
            color = numeral, fontFamily = CondensedFamily, fontWeight = FontWeight.SemiBold,
            fontSize = max(face.numeral * u, floor).toSp(), shadow = if (light) null else softTextShadow(accent.copy(alpha = 0.7f), 6f * u)
        )
        val numerals = face.labels.mapIndexed { i, label ->
            measurer.measure(label, numStyle) to polar(c, 117f * u, face.start + face.sweep * i / (face.labels.size - 1))
        }
        val title = measurer.measure(
            face.title,
            TextStyle(
                color = cream.copy(alpha = 0.55f), fontFamily = CondensedFamily, fontWeight = FontWeight.Medium,
                fontSize = max(face.titleSize * u, floor).toSp(), letterSpacing = (2f * u).toSp()
            )
        )
        val subLabelStyle = TextStyle(
            color = numeral, fontFamily = CondensedFamily, fontWeight = FontWeight.SemiBold, fontSize = max(10f * u, floor).toSp()
        )
        val captionStyle = TextStyle(
            color = cream.copy(alpha = 0.6f), fontFamily = CondensedFamily,
            fontSize = max(9.5f * u, floor).toSp(), letterSpacing = (1f * u).toSp()
        )
        val subs = face.subs.map { s ->
            Triple(measurer.measure(s.lo, subLabelStyle), measurer.measure(s.hi, subLabelStyle), measurer.measure(s.caption, captionStyle))
        }
        val lcdBox = face.lcd
        val lcdTop = lcdBox?.let { Offset(c.x - (190f - it.x) * u, c.y - (190f - it.y) * u) }
        onDrawBehind {
            fun text(layout: TextLayoutResult, at: Offset) =
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
            drawCircle(shadow, 205f * u, c + Offset(0f, 10f * u))
            drawCircle(seam, 188f * u, c)
            drawCircle(bezel, 183f * u, c)
            drawCircle(rimShade, 172.5f * u, c, style = Stroke(2f * u))
            drawCircle(faceBrush, 171f * u, c)
            face.red?.let { red ->
                val r = 153f * u
                drawArc(
                    RedZone, face.start + face.sweep * red.start - 90f, face.sweep * (red.endInclusive - red.start), false,
                    Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = Stroke(10f * u)
                )
            }
            for (i in 0..intervals) {
                val a = face.start + face.sweep * i / intervals
                val major = i % face.minor == 0
                drawLine(
                    if (major) tick else tick.copy(alpha = 0.55f),
                    polar(c, 162f * u, a), polar(c, (if (major) 142f else 152f) * u, a),
                    (if (major) 3.5f else 2f) * u
                )
            }
            numerals.forEach { (layout, at) -> text(layout, at) }
            text(title, Offset(c.x, c.y - (190f - face.titleY) * u))
            face.subs.forEachIndexed { i, s ->
                val sc = Offset(c.x + (s.cx - 190f) * u, c.y + (s.cy - 190f) * u)
                val sr = s.r * u
                drawCircle(subFace, sr, sc)
                drawCircle(subRing, sr, sc, style = Stroke(1.5f * u))
                for (k in 0..4) {
                    val a = -60f + 30f * k
                    drawLine(cream.copy(alpha = 0.7f), polar(sc, sr * 0.89f, a), polar(sc, sr * 0.71f, a), 1.5f * u)
                }
                val (lo, hi, caption) = subs[i]
                text(lo, polar(sc, sr * 0.72f, -102f))
                text(hi, polar(sc, sr * 0.72f, 102f))
                text(caption, Offset(sc.x, sc.y + sr * 0.56f))
            }
            if (lcdBox != null && lcdTop != null) {
                drawRoundRect(
                    lcdBack, lcdTop, Size(lcdBox.w * u, lcdBox.h * u), CornerRadius(5f * u)
                )
                drawRoundRect(
                    lcdInk.copy(alpha = 0.3f), lcdTop, Size(lcdBox.w * u, lcdBox.h * u), CornerRadius(5f * u),
                    style = Stroke(1f * u)
                )
            }
        }
    }

/**
 * A chrome-ringed analog dial [side] wide: the cached face on its own layer,
 * sub-dial needles, the main needle turned only by layer rotation (with an
 * idle wobble while [live]), a chrome hub under glass, and the LCD [readout]
 * when the face has a window for it, with [caption] under it where the
 * window is big enough. [fraction] 0..1 places the needle; null parks it at
 * rest.
 */
@Composable
private fun ChromeDial(
    face: DialFace,
    fraction: Float?,
    subFractions: List<Float?>,
    live: Boolean,
    readout: String?,
    unit: String,
    lcdColor: Color,
    side: Dp,
    caption: String? = null,
    extra: @Composable BoxScope.(unit: Dp) -> Unit = {}
) {
    val measurer = rememberTextMeasurer()
    val accent = DashColors.Accent
    val cream = DashColors.TextPrimary
    val ink = LcdInk
    val needleColor = DashColors.Warning
    val light = DashColors.Light
    val target = face.start + face.sweep * (fraction ?: 0f).coerceIn(0f, 1f)
    val angle = animateFloatAsState(target, spring(dampingRatio = 0.72f, stiffness = 90f), label = "needle")
    val wobble = if (live && DashColors.Effects != DashEffects.NONE) rememberWobble() else null
    val du = side / 380f

    Box(Modifier.size(side)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .dialFace(face, measurer, accent, cream, ink)
        )
        extra(du)
        if (face.subs.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val u = size.minDimension / 380f
                        face.subs.forEachIndexed { i, s ->
                            val sc = Offset(center.x + (s.cx - 190f) * u, center.y + (s.cy - 190f) * u)
                            val f = subFractions.getOrNull(i)?.coerceIn(0f, 1f) ?: 0f
                            drawLine(needleColor, sc, polar(sc, s.r * u * 0.79f, -60f + 120f * f), 2.5f * u, StrokeCap.Round)
                            drawCircle(SubPin, 4f * u, sc)
                        }
                    }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle.value + (wobble?.value ?: 0f) }
                .drawWithCache {
                    val u = size.minDimension / 380f
                    val c = center
                    val needle = Path().apply {
                        moveTo(c.x - 4f * u, c.y + 14f * u)
                        lineTo(c.x, c.y - 140f * u)
                        lineTo(c.x + 4f * u, c.y + 14f * u)
                        close()
                    }
                    val tail = Path().apply {
                        moveTo(c.x - 3f * u, c.y + 14f * u)
                        lineTo(c.x + 3f * u, c.y + 14f * u)
                        lineTo(c.x + 2f * u, c.y + 34f * u)
                        lineTo(c.x - 2f * u, c.y + 34f * u)
                        close()
                    }
                    val from = Offset(c.x, c.y + 10f * u)
                    val to = Offset(c.x, c.y - 132f * u)
                    onDrawBehind {
                        // A glow off the needle at night; by day only a soft shadow under it on the ivory.
                        if (light) {
                            drawLine(Color.Black.copy(alpha = 0.10f), from, to, 7f * u, StrokeCap.Round)
                        } else {
                            drawLine(needleColor.copy(alpha = 0.10f), from, to, 14f * u, StrokeCap.Round)
                            drawLine(needleColor.copy(alpha = 0.18f), from, to, 7f * u, StrokeCap.Round)
                        }
                        drawPath(needle, needleColor)
                        drawPath(tail, needleColor)
                        drawLine(Color.White.copy(alpha = 0.25f), Offset(c.x - 1f * u, c.y), Offset(c.x, c.y - 130f * u), 0.8f * u)
                    }
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val u = size.minDimension / 380f
                    val c = center
                    val hub = chromeBrush(Offset(c.x - 17f * u, c.y - 17f * u), Offset(c.x + 17f * u, c.y + 17f * u))
                    val glass = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (light) 0.2f else 0.07f), Color.Transparent),
                        start = Offset(c.x - 120f * u, c.y - 150f * u), end = Offset(c.x, c.y - 20f * u)
                    )
                    val hubShadow = castShadow(0.4f)
                    onDrawBehind {
                        drawCircle(hubShadow, 19f * u, c + Offset(0f, 2f * u))
                        drawCircle(hub, 17f * u, c)
                        drawCircle(HubPin, 6f * u, c)
                        drawCircle(glass, 171f * u, c)
                    }
                }
        )
        val window = face.lcd
        if (window != null && readout != null) {
            // The readout follows the dial's size but never drops under 14 sp; the
            // unit comes along only while the window has room for it (the dial's
            // title names the unit anyway).
            val mainSize = max(du.value * if (window == LcdCompact) 30f else 20f, 14f)
            val unitSize = max(mainSize * 0.55f, 14f)
            // A caption goes under the readout, both at the small size, only where
            // the window holds two such lines; a smaller dial keeps the readout alone.
            val twoLines = caption != null &&
                unitSize * 2.4f <= (du * window.h).value && lcdWidth(caption, unitSize.dp) <= du * window.w
            val showUnit = !twoLines && unit.isNotEmpty() &&
                lcdWidth(readout, mainSize.dp) + lcdWidth(" $unit", unitSize.dp) <= du * window.w
            Column(
                modifier = Modifier
                    .offset(du * window.x, du * window.y)
                    .size(du * window.w, du * window.h),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    buildAnnotatedString {
                        append(readout)
                        if (showUnit) {
                            withStyle(SpanStyle(fontSize = unitSize.dp.fixedSp(), color = lcdColor.copy(alpha = 0.7f))) {
                                append(" $unit")
                            }
                        }
                    },
                    style = lcd((if (twoLines) unitSize else mainSize).dp.fixedSp(), lcdColor),
                    maxLines = 1,
                    softWrap = false
                )
                if (twoLines && caption != null) {
                    Text(caption, style = lcd(unitSize.dp.fixedSp(), ink.copy(alpha = 0.75f)), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Big LCD speed readout for when a dial would be too small, or beside a dial in a wide tile; [speed] in km/h. */
@Composable
private fun SpeedLcd(speed: Int?, caption: String, modifier: Modifier) {
    val ink = LcdInk
    val units = LocalUnits.current
    val over = speedOver(speed)
    val color = when {
        speed == null -> ink.copy(alpha = 0.4f)
        over -> DashColors.Warning
        else -> ink
    }
    BoxWithConstraints(modifier) {
        val h = maxHeight
        val w = maxWidth
        LcdPanel(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    speed?.let { units.speed(it).toString() } ?: "--",
                    style = lcd(min(h * 0.46f, w * 0.32f).fixedSp(), color),
                    maxLines = 1
                )
                Text(
                    caption,
                    style = lcd((h * 0.11f).coerceIn(14.dp, 18.dp).fixedSp(), ink.copy(alpha = 0.75f)),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

// --- Telemetry & speed --------------------------------------------------------------

/**
 * Twin dials: tachometer (coolant, fuel) and speedometer (battery, load) side by
 * side in a wide tile, stacked in a tall one, or the speedometer alone with the
 * revs in its LCD. With OBD off the needles rest, the LCDs read "--" and a tap
 * anywhere connects.
 */
@Composable
private fun CockpitTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val idle = state.isIdle
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val live = state == ObdConnectionState.CONNECTED || g.any
    val fuel = rememberFuel()
    val speed = g.speedKmh
    val rpm = g.rpm
    val ink = LcdInk
    val speedColor = if (speedOver(speed)) DashColors.Warning else ink
    val coolant = g.coolantTempC?.let { (it - 50f) / 80f }
    val fuelFrac = fuel?.let { it.percent / 100f }
    val volts = g.voltage?.let { ((it - 8.0) / 8.0).toFloat() }
    val load = g.engineLoadPct?.let { it / 100f }
    val words = dialWords()
    val rpmUnit = stringResource(R.string.cockpit_unit_rpm)
    val units = LocalUnits.current
    val revScale = rememberRevScale()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = idle && !env.editing, onClickLabel = stringResource(R.string.cockpit_connect_obd),
                role = Role.Button, onClick = env.onConnectObd
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        val wide = w >= h * 1.6f
        val tall = h >= w * 1.6f

        @Composable
        fun tach(side: Dp) {
            val compact = side < 230.dp
            val face = remember(fuel != null, compact, words, revScale) { tachFace(fuel != null, compact, words, revScale) }
            ChromeDial(
                face = face,
                fraction = rpm?.let { it / revScale.top },
                subFractions = if (fuel != null) listOf(coolant, fuelFrac) else listOf(coolant),
                live = live,
                readout = rpm?.toString() ?: "--",
                unit = rpmUnit,
                lcdColor = ink,
                side = side
            )
        }

        // The speedometer is read by its needle alone; standing in for the
        // tachometer on a small tile, its LCD carries the revs instead.
        @Composable
        fun speedo(side: Dp, withRevs: Boolean) {
            val compact = side < 230.dp
            val revs = withRevs && !compact
            val face = remember(compact, revs, words, units) { speedFace(subs = true, compact = compact, window = revs, w = words, units = units) }
            ChromeDial(
                face = face,
                fraction = speed?.let { speedoFraction(it, units) },
                subFractions = listOf(volts, load),
                live = live,
                readout = if (revs) rpm?.toString() ?: "--" else null,
                unit = rpmUnit,
                lcdColor = speedColor,
                side = side
            )
        }

        when {
            min(w, h) < 120.dp -> SpeedLcd(
                speed,
                if (live) stringResource(R.string.cockpit_speed_rpm_caption, rpm ?: 0) else stringResource(R.string.cockpit_obd_off),
                Modifier.fillMaxSize()
            )
            wide -> {
                val side = min(h, (w - 12.dp) / 2f)
                val gap = w - side * 2f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    tach(side)
                    if (gap >= 64.dp) {
                        TelemetryTellTales(env, speed, idle, Modifier.width(gap).height(side))
                    } else {
                        Spacer(Modifier.width(gap))
                    }
                    speedo(side, withRevs = false)
                }
            }
            tall -> {
                val side = min(w, (h - 8.dp) / 2f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tach(side)
                    speedo(side, withRevs = false)
                }
            }
            else -> speedo(min(w, h), withRevs = true)
        }
    }
}

/** The warning-lamp column between the two dials; says how to connect while OBD is off. */
@Composable
private fun TelemetryTellTales(env: SkinTileEnv, speed: Int?, idle: Boolean, modifier: Modifier) {
    val d = rememberReadings(K.COOLANT_TEMP, K.BATTERY_VOLTS)
    val warn = DashColors.Warning
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        TellTale(
            Icons.Filled.Bluetooth,
            when (env.obdConnection) {
                ObdConnectionState.CONNECTED -> DashColors.Good
                ObdConnectionState.CONNECTING -> DashColors.Secondary
                ObdConnectionState.ERROR -> warn
                ObdConnectionState.DISCONNECTED -> null
            },
            obdStatusLabel(env.obdConnection)
        )
        TellTale(
            Icons.Filled.BatteryAlert,
            if (d.voltage > 0.0 && d.voltage !in 12.0..15.0) warn else null,
            stringResource(R.string.cockpit_battery)
        )
        TellTale(
            Icons.Filled.Thermostat, if (d.coolantTempC >= 105) warn else null,
            stringResource(R.string.cockpit_coolant)
        )
        TellTale(
            Icons.Filled.Speed, if (speedOver(speed)) warn else null,
            stringResource(R.string.cockpit_speed_warning)
        )
        if (idle) {
            Text(
                stringResource(R.string.cockpit_tap_to_connect),
                style = engraved(14.sp),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A single speedometer fed by OBD or GPS, the digits in its LCD window; wide tiles put them on a big LCD beside it. */
@Composable
private fun CockpitSpeedHud(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val source = speedSource(stringResource(R.string.info_speed_no_signal).uppercase()
    )
    val color = if (speedOver(speed)) DashColors.Warning else LcdInk
    val words = dialWords()
    val units = LocalUnits.current
    val unitCaps = units.speedUnit.uppercase()
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = maxHeight
        if (min(w, h) < 120.dp) {
            SpeedLcd(speed, if (speed == null) source else "$unitCaps · $source", Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        // Alone, the dial carries the digits in its own LCD window, and "--"
        // with the source while there is no speed: a resting needle alone would
        // read as standing still. In a wide tile they are on the LCD beside it.
        @Composable
        fun dial(side: Dp, window: Boolean) {
            val compact = side < 230.dp
            val face = remember(compact, window, words, units) { speedFace(subs = false, compact = compact, window = window, w = words, units = units) }
            ChromeDial(
                face = face,
                fraction = speed?.let { speedoFraction(it, units) },
                subFractions = emptyList(),
                live = speed != null,
                readout = if (window) speed?.let { units.speed(it).toString() } ?: "--" else null,
                unit = if (speed != null) source else "",
                lcdColor = if (speed == null) LcdInk.copy(alpha = 0.4f) else color,
                side = side,
                caption = if (speed == null) source else null
            )
        }
        if (w >= h * 1.7f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dial(h, window = false)
                Spacer(Modifier.width(12.dp))
                SpeedLcd(
                    speed, if (speed == null) source else "$unitCaps · $source",
                    Modifier
                        .width(min(w - h - 12.dp, h * 1.4f))
                        .height(h * 0.62f)
                )
            }
        } else {
            dial(min(w, h), window = true)
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Dot-matrix LCD strip with the scrolling "ARTIST · TITLE" and elapsed time,
 * chrome push buttons, and two analog VU meters whose needles bounce while
 * playing when the tile has room.
 */
@Composable
private fun CockpitMedia(env: SkinTileEnv) {
    val state = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = access && state.hasMedia && state.title.isNotBlank()
    val playing = hasTrack && state.isPlaying
    val text = when {
        !access -> "${stringResource(R.string.info_media_access_needed)} · ${stringResource(R.string.cockpit_tap_to_enable)}".uppercase()
        !hasTrack -> stringResource(R.string.info_nothing_playing).uppercase()
        state.artist.isNotBlank() -> "${state.artist} · ${state.title}".uppercase()
        else -> state.title.uppercase()
    }
    // Elapsed time for the LCD, read in its own scope so only that line follows the position.
    val timeOf = if (hasTrack) state else null
    val glyph = when {
        playing -> Icons.Filled.PlayArrow
        hasTrack -> Icons.Filled.Pause
        else -> Icons.Filled.MusicNote
    }

    // Two VU needles bounce on one shared loop; when playback stops they settle to
    // rest from where they were (the loop's last phase), not from its start.
    val loop = if (playing) rememberLoop(4800) else null
    val settle = animateFloatAsState(if (playing) 1f else 0f, tween(700), label = "vu")
    val lastPhase = remember { floatArrayOf(0f) }
    fun level(phase: Float): Float {
        val t = loop?.value?.also { lastPhase[0] = it } ?: lastPhase[0]
        val x = t * 2f * Math.PI.toFloat()
        val v = 0.4f + 0.26f * abs(sin(3f * x + phase)) + 0.2f * abs(sin(7f * x + 2f * phase)) + 0.1f * sin(17f * x + phase)
        return (v * settle.value).coerceIn(0f, 1f)
    }

    val grantLabel = stringResource(R.string.cockpit_grant_media_access)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!access) {
                    Modifier.clickable(enabled = !env.editing, onClickLabel = grantLabel, role = Role.Button) {
                        CarMediaController.openNotificationAccessSettings(env.context)
                    }
                } else Modifier
            )
            .padding(4.dp)
    ) {
        val h = maxHeight
        if (h < 128.dp) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediaLcd(text, timeOf, env.mediaController, glyph, !hasTrack, Modifier.weight(1f).height(min(h, 72.dp)))
                if (access) MediaButtons(env, playing, h.coerceIn(48.dp, 64.dp))
            }
        } else {
            val lcdH = (h * 0.3f).coerceIn(52.dp, 80.dp)
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaLcd(text, timeOf, env.mediaController, glyph, !hasTrack, Modifier.fillMaxWidth().height(lcdH))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val rowH = maxHeight
                    val btn = (rowH * 0.6f).coerceIn(48.dp, 76.dp)
                    val buttonsW = btn * 3.4f + 24.dp
                    val vuW = min(rowH * 1.5f, 170.dp)
                    val showVu = rowH >= 64.dp && maxWidth - buttonsW >= vuW * 2f + 24.dp
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (showVu) Arrangement.SpaceBetween else Arrangement.Center
                    ) {
                        if (showVu) VuMeter({ level(0f) }, stringResource(R.string.cockpit_vu_left), Modifier.width(vuW).height(vuW / 1.5f))
                        if (access) {
                            MediaButtons(env, playing, btn)
                        } else {
                            Text(stringResource(R.string.cockpit_tap_to_enable), style = engraved(14.sp), maxLines = 1)
                        }
                        if (showVu) VuMeter({ level(1.7f) }, stringResource(R.string.cockpit_vu_right), Modifier.width(vuW).height(vuW / 1.5f))
                    }
                }
            }
        }
    }
}

/** The media LCD strip: status glyph, marquee text, and the elapsed time of [timeOf]'s track when there is one. */
@Composable
private fun MediaLcd(
    text: String,
    timeOf: MediaState?,
    controller: CarMediaController,
    glyph: ImageVector,
    dim: Boolean,
    modifier: Modifier
) {
    val ink = if (dim) LcdInk.copy(alpha = 0.5f) else LcdInk
    LcdPanel(modifier, corner = 12.dp) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            val fs = (maxHeight * 0.36f).coerceIn(14.dp, 26.dp)
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph, contentDescription = null, tint = ink, modifier = Modifier.size(fs))
                Spacer(Modifier.width(10.dp))
                // A long title scrolls a few times when it changes, then rests: a
                // marquee redraws every frame for as long as it runs. None with effects off.
                val scroll = LocalPageActive.current && DashColors.Effects != DashEffects.NONE
                key(text) {
                    Text(
                        text,
                        style = lcd(fs.fixedSp(), ink),
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (scroll) Modifier.basicMarquee(iterations = 3, repeatDelayMillis = 1500, velocity = 40.dp) else Modifier)
                    )
                }
                if (timeOf != null) {
                    Spacer(Modifier.width(12.dp))
                    LcdTrackTime(timeOf, controller, lcd((fs * 0.85f).fixedSp(), ink.copy(alpha = 0.85f)))
                }
            }
        }
    }
}

/** Elapsed track time on the LCD, in its own scope so only it follows the playback position. */
@Composable
private fun LcdTrackTime(state: MediaState, controller: CarMediaController, style: TextStyle) {
    val positionMs = rememberMediaPosition(state, controller)
    Text(formatTrackTime(positionMs), style = style, maxLines = 1)
}

/** Previous / play-pause / next as chrome push buttons; inert while arranging. */
@Composable
private fun MediaButtons(env: SkinTileEnv, playing: Boolean, diameter: Dp) {
    val c = env.mediaController
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(diameter * 0.16f)) {
        ChromeRoundButton(Icons.Filled.SkipPrevious, stringResource(R.string.cockpit_previous), diameter, !env.editing) { c.previous() }
        ChromeRoundButton(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.cockpit_pause else R.string.cockpit_play),
            diameter * 1.18f,
            !env.editing
        ) { c.playPause() }
        ChromeRoundButton(Icons.Filled.SkipNext, stringResource(R.string.cockpit_next), diameter, !env.editing) { c.next() }
    }
}

/** Small analog VU meter; [level] (0..1) is read in the needle's layer, so bouncing only turns a layer. */
@Composable
private fun VuMeter(level: () -> Float, channel: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val cream = DashColors.TextPrimary
    val accent = DashColors.Accent
    val needleColor = DashColors.Warning
    val light = DashColors.Light
    val pivotY = 0.9f
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .drawWithCache {
                    val corner = CornerRadius(8.dp.toPx())
                    val rim = 3.dp.toPx()
                    val bezel = chromeBrush(Offset.Zero, Offset(size.width, size.height))
                    val face = Brush.radialGradient(
                        listOf(FaceCentre, FaceEdge), center = Offset(size.width / 2f, size.height * 0.6f),
                        radius = size.width * 0.7f
                    )
                    val pivot = Offset(size.width / 2f, size.height * pivotY)
                    val r = min(size.height * 0.72f, size.width * 0.6f)
                    val vu = measurer.measure(
                        "VU", TextStyle(color = cream.copy(alpha = 0.55f), fontFamily = CondensedFamily, fontSize = max(r * 0.16f, 14.sp.toPx()).toSp())
                    )
                    val ch = measurer.measure(
                        channel, TextStyle(color = accent, fontFamily = CondensedFamily, fontSize = max(r * 0.15f, 14.sp.toPx()).toSp())
                    )
                    onDrawBehind {
                        drawRoundRect(bezel, cornerRadius = corner)
                        drawRoundRect(
                            face, Offset(rim, rim), Size(size.width - rim * 2f, size.height - rim * 2f),
                            CornerRadius(corner.x - rim / 2f)
                        )
                        for (i in 0..10) {
                            val a = -45f + 9f * i
                            val red = i >= 8
                            drawLine(
                                if (red) RedZone else cream.copy(alpha = 0.75f),
                                polar(pivot, r, a), polar(pivot, r * (if (i % 2 == 0) 0.84f else 0.9f), a),
                                (if (i % 2 == 0) 2f else 1.2f) * density
                            )
                        }
                        drawArc(
                            RedZone, -45f + 72f - 90f, 18f, false,
                            Offset(pivot.x - r * 1.02f, pivot.y - r * 1.02f), Size(r * 2.04f, r * 2.04f),
                            style = Stroke(2.5f * density)
                        )
                        drawText(vu, topLeft = Offset(pivot.x - vu.size.width / 2f, pivot.y - r * 0.55f))
                        drawText(ch, topLeft = Offset(rim * 3f, size.height - rim * 2f - ch.size.height))
                    }
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = -45f + 90f * level()
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                }
                .drawWithCache {
                    val pivot = Offset(size.width / 2f, size.height * pivotY)
                    val r = min(size.height * 0.72f, size.width * 0.6f)
                    val halo = if (light) Color.Black.copy(alpha = 0.10f) else needleColor.copy(alpha = 0.2f)
                    onDrawBehind {
                        drawLine(halo, pivot, Offset(pivot.x, pivot.y - r * 0.98f), 5f * density, StrokeCap.Round)
                        drawLine(needleColor, pivot, Offset(pivot.x, pivot.y - r * 0.98f), 1.8f * density, StrokeCap.Round)
                    }
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val pivot = Offset(size.width / 2f, size.height * pivotY)
                    drawCircle(HubPin, size.height * 0.1f, pivot)
                    drawCircle(Chrome1, size.height * 0.1f, pivot, style = Stroke(1.dp.toPx()))
                }
        )
    }
}

// --- Navigation ---------------------------------------------------------------------

/** Distance to the manoeuvre in metres, when the notification gave one. */
private fun metresTo(nav: NavState): Float? {
    val (value, unit) = nav.distanceParts
    val v = value.replace(',', '.').toFloatOrNull() ?: return null
    return when (unit.lowercase()) {
        "km" -> v * 1000f
        "m" -> v
        "mi" -> v * 1609.34f
        "ft" -> v * 0.3048f
        "yd" -> v * 0.9144f
        else -> null
    }
}

/**
 * Next turn on an LCD: the manoeuvre glyph, the distance in big digits,
 * the street in caps and the ETA, plus a green turn-signal tell-tale that
 * blinks inside 300 m. No route: a calm "NO ROUTE" that opens Maps on tap.
 */
@Composable
private fun CockpitNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val openLabel = stringResource(R.string.cockpit_open_navigation)
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val h = maxHeight
        val w = maxWidth
        LcdPanel(
            Modifier
                .fillMaxSize()
                .clickable(enabled = !env.editing, onClickLabel = openLabel, role = Role.Button) {
                    if (env.hasMediaAccess) openNavigationApp(context, nav)
                    else CarMediaController.openNotificationAccessSettings(context)
                },
            corner = 14.dp
        ) {
            when {
                !env.hasMediaAccess -> LcdMessage(
                    stringResource(R.string.info_directions_access_title).uppercase(), stringResource(R.string.cockpit_tap_to_enable), w, h
                )
                !nav.active -> LcdMessage(
                    stringResource(R.string.info_directions_no_route).uppercase(), stringResource(R.string.cockpit_tap_for_maps), w, h
                )
                else -> NavReadout(nav, w, h)
            }
        }
    }
}

/** The active-route layout inside the navigation LCD. */
@Composable
private fun NavReadout(nav: NavState, w: Dp, h: Dp) {
    val ink = LcdInk
    val backlit = !DashColors.Light
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val metres = metresTo(nav)
    val (value, unit) = nav.distanceParts
    val glyph = min(h * 0.52f, w * 0.2f).coerceIn(28.dp, 110.dp)
    val distSize = min(h * 0.3f, w * 0.13f).coerceIn(18.dp, 72.dp)
    val lineSize = 18.dp
    val etaSize = 14.dp
    val signal = (glyph * 0.5f).coerceIn(26.dp, 56.dp)
    // Room for the text column: the tile less its padding, the glyph, the signal and the gaps between them.
    val textW = w - 28.dp - glyph - 14.dp - 10.dp - signal
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(glyph)
                .then(if (backlit) Modifier.glowHalo(ink.copy(alpha = 0.22f), 0.7f) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = nav.instruction,
                    colorFilter = ColorFilter.tint(ink),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Directions, contentDescription = nav.instruction, tint = ink, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            if (value.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        append(value)
                        if (unit.isNotEmpty()) {
                            withStyle(SpanStyle(fontSize = (distSize * 0.5f).fixedSp())) { append(" ${unit.uppercase()}") }
                        }
                    },
                    style = lcd(distSize.fixedSp(), ink),
                    maxLines = 1
                )
            }
            Text(
                nav.instruction.uppercase(),
                style = lcd(lineSize.fixedSp(), ink.copy(alpha = 0.9f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (nav.eta.isNotEmpty() && h >= 110.dp) {
                // "14 MIN · 7.5 KM · 10:32": the arrival time (last) is never cut. When
                // the line is too long it wraps on a tall tile, else it drops the
                // distance, then the duration.
                val parts = nav.etaParts.map { it.uppercase() }
                val fits: (List<String>) -> Boolean = { lcdWidth(it.joinToString(" · "), etaSize) <= textW }
                val wrap = !fits(parts) && h >= 150.dp
                var shown = parts
                if (!wrap) {
                    while (shown.size > 1 && !fits(shown)) shown = shown.filterIndexed { i, _ -> i != shown.size - 2 }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    shown.joinToString(" · "),
                    style = lcd(etaSize.fixedSp(), ink.copy(alpha = 0.7f)),
                    maxLines = if (wrap) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        TurnSignal(nav.turnSide().sign, metres != null && metres < 300f, signal)
    }
}

/** A green turn-signal tell-tale ([side]: -1 left, 1 right, 0 ahead), blinking when [blinking]. */
@Composable
private fun TurnSignal(side: Int, blinking: Boolean, diameter: Dp) {
    val green = DashColors.Good
    // A real signal: on 450 ms, off 450 ms, whatever the effects setting.
    val blink = if (blinking) rememberBlink(450L) else null
    Box(
        Modifier
            .size(diameter)
            .graphicsLayer { alpha = if (blink == null || blink.value) 1f else 0.15f }
            .drawWithCache {
                val s = size.minDimension
                val arrow = Path().apply {
                    moveTo(0.08f * s, 0.38f * s)
                    lineTo(0.52f * s, 0.38f * s)
                    lineTo(0.52f * s, 0.14f * s)
                    lineTo(0.94f * s, 0.5f * s)
                    lineTo(0.52f * s, 0.86f * s)
                    lineTo(0.52f * s, 0.62f * s)
                    lineTo(0.08f * s, 0.62f * s)
                    close()
                }
                val glow = Brush.radialGradient(listOf(green.copy(alpha = 0.35f * Halo), Color.Transparent), center, s * 0.6f)
                val turn = when (side) {
                    -1 -> 180f
                    1 -> 0f
                    else -> -90f
                }
                onDrawBehind {
                    rotate(turn) {
                        if (blinking) drawCircle(glow, s * 0.6f)
                        drawPath(arrow, if (blinking) green else green.copy(alpha = 0.18f))
                    }
                }
            }
    )
}

// --- Clock --------------------------------------------------------------------------

/** Analog clock with a date window; a wide tile adds the time and date on an LCD. Tap opens alarms. */
@Composable
private fun CockpitClock(env: SkinTileEnv) {
    val openLabel = stringResource(R.string.cockpit_open_alarms)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !env.editing, onClickLabel = openLabel, role = Role.Button) { openClockApp(env.context) }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        if (w >= h * 1.9f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnalogClock(h)
                Spacer(Modifier.width(16.dp))
                DateLcd(Modifier.width(min(w - h - 16.dp, h * 1.6f).coerceAtLeast(0.dp)).height(h * 0.6f))
            }
        } else {
            AnalogClock(min(w, h))
        }
    }
}

/**
 * Chrome-bezel clock with an orange sweeping second hand: a black face with
 * cream hands and orange 12/3/6/9 at night, an ivory face printed and handed
 * in black by day.
 */
@Composable
private fun AnalogClock(side: Dp) {
    val wall = rememberWallClock(secondHandStepMs)
    val zone = rememberZone()
    val today = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val dateFmt = rememberDateFormat("EEEd", best = true)
    val dateText = remember(today, dateFmt) { dateFmt.format(today).uppercase(locale) }
    val measurer = rememberTextMeasurer()
    val accent = DashColors.Accent
    val cream = DashColors.TextPrimary
    val light = DashColors.Light
    val clockLabel = stringResource(R.string.cockpit_clock)
    fun local(): Long {
        val t = wall.longValue
        return t + zone.getOffset(t)
    }
    Box(
        Modifier
            .size(side)
            .semantics { contentDescription = clockLabel }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val c = center
                    val bezel = chromeBrush(Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
                    val faceR = r * 0.9f
                    val face = Brush.radialGradient(listOf(FaceCentre, FaceEdge), center = Offset(c.x, c.y - r * 0.15f), radius = r * 1.1f)
                    val shadow = Brush.radialGradient(
                        listOf(castShadow(0.65f), Color.Transparent), center = c + Offset(0f, r * 0.05f), radius = r * 1.08f
                    )
                    val seam = BezelBlack
                    val rimShade = castShadow(0.7f)
                    val paper = if (light) Color.White else cream
                    val numStyle = TextStyle(
                        color = if (light) TickInk else accent, fontFamily = CondensedFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = (r * 0.2f).toSp(), shadow = if (light) null else softTextShadow(accent.copy(alpha = 0.7f), r * 0.04f)
                    )
                    val numerals = listOf("12" to 0f, "3" to 90f, "6" to 180f, "9" to 270f).map { (n, a) ->
                        measurer.measure(n, numStyle) to polar(c, faceR * 0.72f, a)
                    }
                    val date = measurer.measure(
                        dateText,
                        TextStyle(
                            color = EngraveInk, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = (r * 0.09f).toSp()
                        )
                    )
                    val winW = max(date.size.width + r * 0.08f, r * 0.34f)
                    val winH = date.size.height + r * 0.03f
                    val winTop = Offset(c.x - winW / 2f, c.y + faceR * 0.38f)
                    onDrawBehind {
                        drawCircle(shadow, r * 1.08f, c + Offset(0f, r * 0.05f))
                        drawCircle(seam, r, c)
                        drawCircle(bezel, r * 0.97f, c)
                        drawCircle(rimShade, faceR + r * 0.006f, c, style = Stroke(r * 0.012f))
                        drawCircle(face, faceR, c)
                        for (i in 0 until 60) {
                            val major = i % 5 == 0
                            drawLine(
                                if (major) cream else cream.copy(alpha = 0.4f),
                                polar(c, faceR * 0.95f, i * 6f), polar(c, faceR * (if (major) 0.84f else 0.9f), i * 6f),
                                if (major) r * 0.022f else r * 0.009f
                            )
                        }
                        numerals.forEach { (layout, at) ->
                            drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
                        }
                        drawRoundRect(
                            Color.Black, winTop - Offset(r * 0.012f, r * 0.012f),
                            Size(winW + r * 0.024f, winH + r * 0.024f), CornerRadius(r * 0.02f)
                        )
                        drawRoundRect(paper, winTop, Size(winW, winH), CornerRadius(r * 0.015f))
                        drawText(date, topLeft = Offset(c.x - date.size.width / 2f, winTop.y + (winH - date.size.height) / 2f))
                    }
                }
        )
        ClockHand(rotation = { (local() % 43_200_000L) / 43_200_000f * 360f }, length = 0.5f, width = 0.075f, color = cream)
        ClockHand(rotation = { (local() % 3_600_000L) / 3_600_000f * 360f }, length = 0.78f, width = 0.05f, color = cream)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = (wall.longValue % 60_000L) / 60_000f * 360f }
                .drawWithCache {
                    val r = size.minDimension / 2f * 0.9f
                    val c = center
                    onDrawBehind {
                        drawLine(accent, Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y - r * 0.88f), r * 0.018f, StrokeCap.Round)
                        drawCircle(accent, r * 0.05f, Offset(c.x, c.y + r * 0.16f))
                        drawCircle(accent, r * 0.045f, c)
                    }
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val c = center
                    val cap = chromeBrush(Offset(c.x - r * 0.04f, c.y - r * 0.04f), Offset(c.x + r * 0.04f, c.y + r * 0.04f))
                    val glass = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (light) 0.2f else 0.07f), Color.Transparent),
                        start = Offset(c.x - r * 0.6f, c.y - r * 0.8f), end = Offset(c.x, c.y - r * 0.1f)
                    )
                    onDrawBehind {
                        drawCircle(cap, r * 0.028f, c)
                        drawCircle(glass, r * 0.9f, c)
                    }
                }
        )
    }
}

/** One tapered hand in [color] turned by layer rotation; [length] and [width] are fractions of the face radius. */
@Composable
private fun ClockHand(rotation: () -> Float, length: Float, width: Float, color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = rotation() }
            .drawWithCache {
                val r = size.minDimension / 2f * 0.9f
                val c = center
                val hw = r * width / 2f
                val hand = Path().apply {
                    moveTo(c.x - hw, c.y + r * 0.1f)
                    lineTo(c.x - hw * 0.8f, c.y - r * length * 0.85f)
                    lineTo(c.x, c.y - r * length)
                    lineTo(c.x + hw * 0.8f, c.y - r * length * 0.85f)
                    lineTo(c.x + hw, c.y + r * 0.1f)
                    close()
                }
                val shade = castShadow(0.45f)
                onDrawBehind {
                    translate(r * 0.012f, r * 0.02f) { drawPath(hand, shade) }
                    drawPath(hand, color)
                    drawPath(hand, Color.Black.copy(alpha = 0.35f), style = Stroke(r * 0.006f))
                }
            }
    )
}

/** Digital time and the full date on an LCD, beside the analog clock in wide tiles. */
@Composable
private fun DateLcd(modifier: Modifier) {
    // Minutes only, so one tick a minute, on the minute.
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val time = remember(now, timeFmt) { timeFmt.format(now) }
    val amPm = remember(now, units) { units.amPm(now, locale) }
    val date = remember(now, dateFmt) { dateFmt.format(now).uppercase(locale) }
    val ink = LcdInk
    BoxWithConstraints(modifier) {
        val h = maxHeight
        val w = maxWidth
        LcdPanel(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                val digits = min(h * 0.44f, w * 0.22f)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time, style = lcd(digits.fixedSp(), ink), maxLines = 1)
                    // A 12-hour clock's AM / PM, small beside the digits like a clock radio's.
                    if (amPm != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(amPm, style = lcd((digits * 0.3f).coerceAtLeast(14.dp).fixedSp(), ink.copy(alpha = 0.75f)), maxLines = 1,
                            modifier = Modifier.padding(bottom = digits * 0.12f))
                    }
                }
                Text(
                    date,
                    style = lcd((h * 0.12f).coerceIn(14.dp, 20.dp).fixedSp(), ink.copy(alpha = 0.75f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
        }
    }
}

// --- Weather ------------------------------------------------------------------------

/** "OUT 20°C" on an LCD with the condition and feels-like / wind line. */
@Composable
private fun CockpitWeather() {
    val weather = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val ink = LcdInk
    val backlit = !DashColors.Light
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val h = maxHeight
        val w = maxWidth
        val big = min(h * 0.36f, w * 0.16f).coerceIn(18.dp, 80.dp)
        val line = (big * 0.3f).coerceIn(14.dp, 22.dp)
        LcdPanel(Modifier.fillMaxSize(), corner = 14.dp) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (weather != null && w >= 220.dp) {
                    Icon(
                        weatherIcon(weather.code),
                        contentDescription = weather.condition,
                        tint = ink,
                        modifier = Modifier
                            .size(big * 1.1f)
                            .then(if (backlit) Modifier.glowHalo(ink.copy(alpha = 0.22f), 0.7f) else Modifier)
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.cockpit_out), style = lcd((big * 0.36f).fixedSp(), ink.copy(alpha = 0.7f)), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            weather?.let { units.tempTight(it.tempC) } ?: "--${units.tempUnit}",
                            style = lcd(big.fixedSp(), if (weather == null) ink.copy(alpha = 0.4f) else ink),
                            maxLines = 1
                        )
                    }
                    Text(
                        weather?.condition?.uppercase() ?: when (wait) {
                            WeatherWait.LOADING -> stringResource(R.string.cockpit_loading)
                            WeatherWait.NO_GPS -> stringResource(R.string.info_waiting_gps).uppercase()
                            WeatherWait.UNAVAILABLE -> stringResource(R.string.info_weather_unavailable).uppercase()
                        },
                        style = lcd(line.fixedSp(), ink.copy(alpha = 0.9f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (weather != null && h >= 110.dp) {
                        Text(
                            stringResource(
                                if (units.imperial) R.string.units_cockpit_feels_wind_mph else R.string.cockpit_feels_wind,
                                units.temp(weather.feelsC), units.speed(weather.windKmh)
                            ),
                            style = lcd((line * 0.85f).fixedSp(), ink.copy(alpha = 0.7f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// --- Fuel & range -------------------------------------------------------------------

/**
 * Analog fuel gauge (E–F, red reserve zone) with the range on its LCD; wide
 * tiles add an LCD with range, level and litres. Tap to recalibrate the fuel
 * signal. Unknown fuel falls back to the standard tile and its learning flow.
 */
@Composable
private fun CockpitRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    var finder by remember { mutableStateOf(false) }
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val segment = LcdInk
    val ink = if (low) DashColors.Warning else segment
    val cream = DashColors.TextPrimary
    val words = dialWords()
    val face = remember(words) { fuelFace(words) }
    val settingsLabel = stringResource(R.string.cockpit_fuel_settings)
    val units = LocalUnits.current
    val range = units.distance(fuel.rangeKm)
    val rangeUnit = units.distanceUnit.uppercase()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !env.editing, onClickLabel = settingsLabel, role = Role.Button) { finder = true }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight

        @Composable
        fun gauge(side: Dp, readout: String, unit: String) {
            ChromeDial(
                face = face,
                fraction = fuel.percent / 100f,
                subFractions = emptyList(),
                live = false,
                readout = readout,
                unit = unit,
                lcdColor = ink,
                side = side
            ) { du ->
                Icon(
                    Icons.Filled.LocalGasStation,
                    contentDescription = null,
                    tint = if (low) DashColors.Warning else cream.copy(alpha = 0.55f),
                    modifier = Modifier
                        .offset(du * 173f, du * 226f)
                        .size(du * 34f)
                )
            }
        }
        if (w >= h * 1.5f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                gauge(h, "${fuel.percent}", "%")
                Spacer(Modifier.width(12.dp))
                BoxWithConstraints(Modifier.width(min(w - h - 12.dp, h * 1.4f).coerceAtLeast(0.dp)).height(h * 0.62f)) {
                    val ph = maxHeight
                    val pw = maxWidth
                    LcdPanel(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.cockpit_range),
                                style = lcd((ph * 0.11f).coerceIn(14.dp, 18.dp).fixedSp(), segment.copy(alpha = 0.7f)),
                                maxLines = 1
                            )
                            Text(
                                "$range $rangeUnit",
                                style = lcd(min(ph * 0.34f, pw * 0.16f).fixedSp(), ink),
                                maxLines = 1
                            )
                            // Three 14 sp lines need the room; a short panel keeps the two that matter.
                            if (ph >= 56.dp) {
                                Text(
                                    "${LocalUnits.current.volumeText(fuel.liters)} · ${fuel.source.uppercase()}",
                                    style = lcd((ph * 0.1f).coerceIn(14.dp, 16.dp).fixedSp(), segment.copy(alpha = 0.7f)),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        } else {
            gauge(min(w, h), "$range", rangeUnit)
        }
    }
    if (finder) FuelFinderDialog(onDismiss = { finder = false })
}

// --- App toggles --------------------------------------------------------------------

/** An app shortcut as an aircraft toggle on its own plate. */
@Composable
private fun CockpitToggleTile(packageName: String, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = maxHeight
        val plate = if (w >= h * 1.25f) Modifier.width(min(w, h * 2.2f)).height(h) else Modifier.width(min(w, h * 0.82f)).height(h)
        ToggleSwitch(env.appsByPackage[packageName], packageName, env, onPlate = true, modifier = plate)
    }
}

/**
 * Aircraft-style toggle for an app: its icon, a chrome lever on a hex nut and
 * an engraved caps label. A tap flips the lever up, launches the app, and the
 * lever springs back. Inert while arranging so long-press drags the tile.
 */
@Composable
private fun ToggleSwitch(app: AppEntry?, packageName: String, env: SkinTileEnv, onPlate: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val lever = remember { Animatable(-1f) }
    var busy by remember { mutableStateOf(false) }
    val label = appLabel(app, packageName)
    val media = env.mediaState
    val lastMediaPackage = remember(media.title, media.isPlaying) { CarMediaController.getLastMediaPackage(env.context) }
    val playingHere = media.isPlaying && lastMediaPackage == packageName
    val openLabel = stringResource(R.string.cockpit_open_app, label)
    BoxWithConstraints(
        modifier = modifier
            .then(if (onPlate) TogglePlate else Modifier)
            .clickable(enabled = !env.editing, onClickLabel = openLabel, role = Role.Button) {
                if (busy) return@clickable
                busy = true
                scope.launch {
                    try {
                        lever.animateTo(1f, tween(110, easing = FastOutSlowInEasing))
                        env.onLaunchApp(packageName)
                        delay(420)
                        lever.animateTo(-1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMediumLow))
                    } finally {
                        busy = false
                    }
                }
            }
    ) {
        val w = maxWidth
        val h = maxHeight
        val ledOn = playingHere || busy
        if (w >= h * 1.25f) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToggleLever({ lever.value }, Modifier.fillMaxHeight().width(h * 0.5f))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ToggleIcon(app, (h * 0.34f).coerceIn(18.dp, 48.dp))
                    Spacer(Modifier.height(4.dp))
                    ToggleLabel(label, ledOn, (h * 0.13f).coerceIn(14.dp, 15.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = (h * 0.05f).coerceAtMost(10.dp), horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ToggleIcon(app, (h * 0.2f).coerceIn(16.dp, 44.dp))
                ToggleLever({ lever.value }, Modifier.weight(1f).fillMaxWidth())
                ToggleLabel(label, ledOn, (h * 0.1f).coerceIn(14.dp, 15.dp))
            }
        }
    }
}

/**
 * The toggle's plate: a rounded panel with a faint bevel and two screws;
 * black at night, pale aluminium by day with a hairline dark edge. One shared
 * modifier, so the cache survives recomposition.
 */
private val TogglePlate = Modifier.drawWithCache {
    val light = DashColors.Light
    val corner = CornerRadius(10.dp.toPx())
    val bevel = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (light) 0.5f else 0.05f), Color.Transparent), 0f, size.height * 0.4f
    )
    val drop = castShadow(0.45f)
    val plate = Plate
    val edge = if (light) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f)
    val line = 1.dp.toPx()
    val screwR = 2.5.dp.toPx()
    val inset = 7.dp.toPx()
    onDrawBehind {
        drawRoundRect(drop, Offset(0f, 2.dp.toPx()), size, corner)
        drawRoundRect(plate, cornerRadius = corner)
        drawRoundRect(bevel, cornerRadius = corner)
        drawRoundRect(
            edge, Offset(line / 2f, line / 2f),
            Size(size.width - line, size.height - line), corner, style = Stroke(line)
        )
        if (size.width > 60.dp.toPx() && size.height > 60.dp.toPx()) {
            for (x in listOf(inset, size.width - inset)) {
                drawCircle(Chrome1, screwR, Offset(x, inset))
                drawLine(Bushing, Offset(x - screwR * 0.7f, inset + screwR * 0.4f), Offset(x + screwR * 0.7f, inset - screwR * 0.4f), line)
            }
        }
    }
}

/** The app's icon, small, above the lever. */
@Composable
private fun ToggleIcon(app: AppEntry?, iconSize: Dp) {
    if (app != null) {
        AppIcon(icon = app.icon, size = iconSize)
    } else {
        Icon(Icons.Filled.Apps, contentDescription = null, tint = LampOff, modifier = Modifier.size(iconSize))
    }
}

/** Engraved caps label with a small amber LED that lights while the app plays or launches. */
@Composable
private fun ToggleLabel(label: String, ledOn: Boolean, labelSize: Dp) {
    val amber = DashColors.Secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .then(if (ledOn) Modifier.glowHalo(amber.copy(alpha = 0.6f * Halo), 1.6f) else Modifier)
                .drawBehind { drawCircle(if (ledOn) amber else LampOff) }
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label.uppercase(),
            style = engraved(labelSize.fixedSp()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Chrome lever on a hex nut; [position] runs from -1 (down, off) to 1 (up, on). */
@Composable
private fun ToggleLever(position: () -> Float, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val u = min(size.width / 40f, size.height / 86f)
            val c = center
            val hex = Path().apply {
                for (i in 0 until 6) {
                    val p = polar(c, 17f * u, i * 60f)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            val nut = Brush.radialGradient(listOf(Chrome0, ChromeShade), center = c + Offset(-5f * u, -6f * u), radius = 22f * u)
            val rod = Brush.horizontalGradient(
                0f to Color(0xFF8F8C86), 0.45f to Chrome0, 1f to ChromeShade,
                startX = c.x - 5f * u, endX = c.x + 5f * u
            )
            val corner = CornerRadius(5f * u)
            val nutShadow = castShadow(0.4f)
            val leverShadow = castShadow(0.3f)
            onDrawBehind {
                val p = position().coerceIn(-1.2f, 1.2f)
                val tipY = c.y - p * 32f * u
                val top = min(c.y, tipY)
                val len = abs(tipY - c.y)
                val ball = 8f * u * (1f + 0.22f * (1f - abs(p).coerceAtMost(1f)))
                val shade = Offset(3f * u, 4f * u)
                drawCircle(nutShadow, 18f * u, c + shade)
                drawPath(hex, nut)
                drawPath(hex, Color.Black.copy(alpha = 0.5f), style = Stroke(1f * u))
                drawCircle(Bushing, 9f * u, c)
                drawRoundRect(leverShadow, Offset(c.x - 5f * u, top) + shade, Size(10f * u, len), corner)
                drawCircle(leverShadow, ball, Offset(c.x, tipY) + shade)
                drawRoundRect(rod, Offset(c.x - 5f * u, top), Size(10f * u, len), corner)
                drawCircle(Chrome2, ball, Offset(c.x, tipY))
                drawCircle(Color.White, ball * 0.38f, Offset(c.x - ball * 0.3f, tipY - ball * 0.32f), alpha = 0.9f)
                drawCircle(Chrome3.copy(alpha = 0.6f), ball, Offset(c.x, tipY), style = Stroke(1f * u))
            }
        }
    )
}

/** A rail of app toggles on one switch plate, with a small chrome pencil at the end that edits the bar. */
@Composable
private fun CockpitLaunchRail(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .then(TogglePlate)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.packages.isEmpty()) {
            Text(
                stringResource(R.string.cockpit_launch_bar_empty),
                style = engraved(14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                item.packages.forEach { pkg ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        ToggleSwitch(
                            env.appsByPackage[pkg], pkg, env, onPlate = false,
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 170.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
        ChromeRoundButton(
            Icons.Filled.Edit, stringResource(R.string.cockpit_edit_launch_bar), 48.dp,
            enabled = true, disc = 0.72f, onClick = env.onEditLaunchBar
        )
    }
}

// --- Tiles --------------------------------------------------------------------------

/**
 * Cockpit renderer for the skinned tiles: app toggles, the toggle rail and
 * the instrument widgets. Anything else keeps its standard renderer.
 */
@Composable
internal fun CockpitTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> CockpitToggleTile(item.packageName, env)
        is DashboardItem.LaunchBar -> CockpitLaunchRail(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> CockpitTelemetry(env)
            BuiltinKind.SPEED_HUD -> CockpitSpeedHud(env)
            BuiltinKind.MEDIA -> CockpitMedia(env)
            BuiltinKind.NAVIGATION -> CockpitNavigation(env)
            BuiltinKind.CLOCK -> CockpitClock(env)
            BuiltinKind.WEATHER -> CockpitWeather()
            BuiltinKind.RANGE -> CockpitRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Maps window frame --------------------------------------------------------------

/**
 * A round instrument screen over the docked Maps window: outside the largest
 * centred circle the page itself (leather, cowl and stitching, lined up with
 * the real background), a thick chrome bezel, and inside faint scanlines, a
 * vignette and a glass sheen (softer by day, over a daylight map). The middle
 * stays clear; static, drawn once per size and appearance.
 */
@Composable
internal fun CockpitWindowFrame(modifier: Modifier) {
    val view = LocalView.current
    val metrics = view.resources.displayMetrics
    val screen = Size(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    val scan = rememberScanlines()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val loc = IntArray(2)
                view.rootView.getLocationOnScreen(loc)
                val p = coords.positionInWindow()
                val o = Offset(loc[0] + p.x, loc[1] + p.y)
                if (o != origin) origin = o
            }
            .drawWithCache {
                val o = origin
                val light = DashColors.Light
                val backdrop = Backdrop(screen, density)
                val r = size.minDimension / 2f
                val c = center
                val bezelW = (r * 0.06f).coerceIn(10.dp.toPx(), 24.dp.toPx())
                val inner = r - bezelW
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(c, r - 0.5f))
                }
                val chrome = chromeBrush(Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
                val ringW = bezelW - 4.dp.toPx()
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = if (light) 0.4f else 0.7f),
                    center = c, radius = inner
                )
                val sheen = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (light) 0.14f else 0.06f), Color.Transparent),
                    start = Offset(c.x - inner, c.y - inner), end = Offset(c.x, c.y)
                )
                val shadowW = 16.dp.toPx()
                val shadow = Brush.radialGradient(
                    0f to Color.Transparent, r / (r + shadowW) to castShadow(0.55f), 1f to Color.Transparent,
                    center = c + Offset(0f, 4.dp.toPx()), radius = r + shadowW
                )
                val seam = BezelBlack
                val screwShadow = castShadow(0.5f)
                val screwR = 6.dp.toPx()
                val screwInset = 18.dp.toPx()
                val screws = listOf(
                    Offset(screwInset, screwInset), Offset(size.width - screwInset, screwInset),
                    Offset(screwInset, size.height - screwInset), Offset(size.width - screwInset, size.height - screwInset)
                ).filter { (it - c).getDistance() > r + screwR * 2.5f }
                onDrawBehind {
                    drawCircle(scan, inner, c)
                    drawCircle(vignette, inner, c)
                    drawCircle(sheen, inner, c)
                    clipPath(mask) {
                        translate(-o.x, -o.y) { drawBackdrop(backdrop) }
                        drawCircle(shadow, r + shadowW, c + Offset(0f, 4.dp.toPx()))
                        screws.forEach { s ->
                            drawCircle(screwShadow, screwR, s + Offset(1.dp.toPx(), 2.dp.toPx()))
                            drawCircle(chromeBrush(s - Offset(screwR, screwR), s + Offset(screwR, screwR)), screwR, s)
                            drawLine(
                                Bushing, s + Offset(-screwR * 0.7f, screwR * 0.35f), s + Offset(screwR * 0.7f, -screwR * 0.35f),
                                1.5.dp.toPx()
                            )
                        }
                    }
                    drawCircle(seam, r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    drawCircle(chrome, r - 2.dp.toPx() - ringW / 2f, c, style = Stroke(ringW))
                    drawCircle(Color.White.copy(alpha = 0.35f), inner + 2.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.85f), inner + 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
            }
    )
}
