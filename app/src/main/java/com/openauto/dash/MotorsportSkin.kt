package com.openauto.dash

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.K
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Motorsport skin: a race car's steering-wheel display and the pit wall. The
 * page is carbon-fibre weave with a red glow under the top bar, and a frame
 * round the whole screen glowing in the flag colour: green while all is well,
 * yellow while a car alert or a door is open, red for a critical alert. The
 * top bar carries a glossy housing of fifteen shift lights lit from the revs,
 * the clock and a skewed flag tag. The tiles are pit-wall screens: an LCD gear
 * and big italic speed, the car from above with its tyres, the route as a race
 * track in sectors, the music as team radio, a pit board for the fuel, and
 * glossy round buttons for the apps, all in heavy condensed italic numerals.
 * By day the carbon turns light grey, the ink dark and the flag frame stays.
 *
 * Performance on the head unit: the weave is one small tile bitmap repeated by
 * a shader, the still page is recorded once into a layer, panels and buttons
 * build their gradients once per size (cachedDraw), and live values are read
 * inside draw lambdas where they only move a light or a dot.
 */

internal val MotorsportPalette = DashPalette(
    Background = Color(0xFF0A0A0B), Bar = Color.Transparent, Card = Color(0xFF151517), CardHi = Color(0xFF26262A),
    Accent = Color(0xFFFFD400), Secondary = Color(0xFF2E7BFF), Critical = Color(0xFFFF2E2E), Good = Color(0xFF2BD96B),
    Muted = Color(0xFF8A8A92), TextPrimary = Color(0xFFF5F5F5), TextSecondary = Color(0xFFB0B0B8), Accent2 = Color(0xFFB467FF),
    Line = Color(0x1FFFFFFF), Glow = 0.7f, Warning = Color(0xFFFFB020), Font = DashFont.CONDENSED, HeroWeight = FontWeight.Black,
    Bare = true, Skin = DashSkin.MOTORSPORT
)

internal val MotorsportLightPalette = DashPalette(
    Background = Color(0xFFE4E4E8), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0xFFDADADF),
    Accent = Color(0xFF8F7300), Secondary = Color(0xFF1C5FD6), Critical = Color(0xFFD01B1B), Good = Color(0xFF13914A),
    Muted = Color(0xFF5E5E66), TextPrimary = Color(0xFF111114), TextSecondary = Color(0xFF3A3A42), Accent2 = Color(0xFF7D3BD1),
    Line = Color(0x24000000), Glow = 0.25f, Light = true, Warning = AmberDay, Font = DashFont.CONDENSED, HeroWeight = FontWeight.Black,
    Bare = true, Skin = DashSkin.MOTORSPORT
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val MotorsportShapes = skinShapes(18, 16, 6)

// --- Materials ----------------------------------------------------------------------
//
// Getters pick the night or the day version, so anything that bakes one in
// must read it inside a cache block (which then rebuilds on a day / night switch).

/** The night or the day version of a material. */
internal fun msTone(night: Color, day: Color): Color = if (DashColors.Light) day else night

internal val MsPanelTop get() = msTone(Color(0xFF17171A), Color(0xFFFFFFFF))
internal val MsPanelBottom get() = msTone(Color(0xFF070708), Color(0xFFECECF0))
internal val MsPanelRim get() = msTone(Color(0xFF2A2A2E), Color(0xFFC3C3CB))
internal val MsSpecular get() = msTone(Color.White.copy(alpha = 0.07f), Color.White)
internal val MsWell get() = msTone(Color(0xFF0B0B0D), Color(0xFFF7F7F9))
internal val MsTrackInk get() = msTone(Color(0xFF26262A), Color(0xFFD3D3DA))
internal val MsSegOff get() = msTone(Color(0xFF1C1C20), Color(0xFFDADAE0))
internal val MsSegOffRed get() = msTone(Color(0xFF2A1414), Color(0xFFF1D3D3))
internal val MsLcdBack get() = msTone(Color(0xFF0B0E05), Color(0xFFC6C9AC))
internal val MsLcdRim get() = msTone(Color(0xFF3A3410), Color(0xFF8C8A6C))
internal val MsLcdInk get() = msTone(Color(0xFFFFD400), Color(0xFF1E2410))
internal val MsLcdLabel get() = msTone(Color(0xFF8A7A20), Color(0xFF4C4E36))
internal val MsRadioInk get() = msTone(Color(0xFFFF7070), Color(0xFFB81616))
internal val MsPitBlue get() = msTone(Color(0xFF6FA4FF), Color(0xFF1C5FD6))
internal val MsSilver get() = msTone(Color(0xFFCFCFD6), Color(0xFFB9B9C2))

/** Ink on a glossy coloured dome or the flag tag: near black, whatever the time of day. */
internal val MsDomeInk = Color(0xFF0A0A0B)

/** A shadow that is half as dark on the pale day page. */
internal fun msShade(alpha: Float): Color = Color.Black.copy(alpha = if (DashColors.Light) alpha * 0.4f else alpha)

/** How strong halos and blooms are: the effects setting, and softer by day so nothing smears in the sun. */
internal val msGlow: Float get() = DashColors.Effects.scale * (if (DashColors.Light) 0.55f else 1f)

// --- Type ---------------------------------------------------------------------------

private val TightLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The heavy condensed italic of every numeral (speed, gear, times), with a faint glow of its colour at night. */
internal fun msHero(size: TextUnit, color: Color, glow: Boolean = false): TextStyle = TextStyle(
    color = color,
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = size,
    lineHeight = 1.0.em,
    lineHeightStyle = TightLine,
    letterSpacing = (-0.01).em,
    shadow = if (glow && !DashColors.Light) softTextShadow(color.copy(alpha = 0.55f), size.value * 0.35f) else null
)

/** Letter-spaced condensed caps ("SPEED", "PIT BOARD"). */
internal fun msCaps(size: TextUnit, color: Color = DashColors.Muted, weight: FontWeight = FontWeight.Bold): TextStyle = TextStyle(
    color = color,
    fontFamily = CondensedFamily,
    fontWeight = weight,
    fontSize = readableCaps(size),
    lineHeight = 1.15.em,
    letterSpacing = 0.16.em
)

/** Plain sans for titles and lines of text (a song, a street). */
internal fun msBody(size: TextUnit, color: Color = DashColors.TextSecondary, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = readable(size),
    lineHeight = 1.2.em
)

/** One line of text that ends in an ellipsis rather than wrapping or spilling. */
@Composable
internal fun MsText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, clip: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = if (clip) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

/**
 * Largest size in sp (within [minSp]..[maxSp]) at which [sample] in [style]
 * fits [maxW] on one line with its line box no taller than [maxH]. Measured
 * once at 100 sp and scaled: type grows linearly. Italic leans out, so a
 * little room is kept on the right.
 */
@Composable
internal fun msFitSp(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minSp: Float, maxSp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, maxW, maxH, minSp, maxSp, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = 100.sp, shadow = null), softWrap = false, maxLines = 1)
        val w = probe.size.width.coerceAtLeast(1) * 1.06f
        val h = probe.size.height.coerceAtLeast(1)
        val byW = with(density) { maxW.toPx() } * 100f / w / density.fontScale
        val byH = with(density) { maxH.toPx() } * 100f / h / density.fontScale
        min(byW, byH).coerceIn(minSp, maxSp)
    }
}

/** Digits replaced by '8', so a readout keeps one size while its value changes. */
internal fun msTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

// --- Carbon fibre -------------------------------------------------------------------

/**
 * One repeating tile of 2x2 twill weave: 4 x 4 tows, each lit across its
 * fibres (dark edges, a bright middle), horizontal and vertical ones stepping
 * one place per row so the weave runs diagonally. Light grey by [day].
 */
private fun carbonTile(cell: Int, day: Boolean): ImageBitmap {
    val n = cell * 4
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint()
    val edge = if (day) 0xFFC9C9CF.toInt() else 0xFF080809.toInt()
    val mid = if (day) 0xFFEDEDF0.toInt() else 0xFF19191C.toInt()
    val colors = intArrayOf(edge, mid, edge)
    val stops = floatArrayOf(0f, 0.5f, 1f)
    for (i in 0 until 4) for (j in 0 until 4) {
        val x = (i * cell).toFloat()
        val y = (j * cell).toFloat()
        val c = cell.toFloat()
        paint.shader = if ((i + j) % 4 < 2) {
            android.graphics.LinearGradient(0f, y, 0f, y + c, colors, stops, android.graphics.Shader.TileMode.CLAMP)
        } else {
            android.graphics.LinearGradient(x, 0f, x + c, 0f, colors, stops, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(x, y, x + c, y + c, paint)
    }
    return bmp.asImageBitmap()
}

/** The weave as a repeating brush, one tile per density and appearance. */
private fun carbonBrush(density: Float, day: Boolean): ShaderBrush {
    val cell = (4f * density).roundToInt().coerceAtLeast(3)
    return ShaderBrush(ImageShader(carbonTile(cell, day), TileMode.Repeated, TileMode.Repeated))
}

// --- Flag ---------------------------------------------------------------------------

/** The race flag the car's state calls for. */
internal enum class MsFlag { GREEN, YELLOW, RED }

/**
 * The flag, read from what the app already knows and never changed here: red
 * for a critical alert still on the bar ([AlertCenter.critical]) or a tyre
 * losing air, yellow while a warning is up, a door is open or a tyre needs a
 * look, green otherwise.
 */
@Composable
internal fun rememberMsFlag(): State<MsFlag> {
    val doors = remember { Vehicle.bus.value(K.DOORS) }.collectAsState(null)
    val tyres = Tyres.problems.collectAsState()
    return remember(doors, tyres) {
        derivedStateOf {
            val problems = tyres.value
            when {
                AlertCenter.critical.isNotEmpty() || TyreProblem.LEAK in problems.values -> MsFlag.RED
                AlertCenter.warnings.isNotEmpty() || doors.value?.anyOpen == true || problems.isNotEmpty() -> MsFlag.YELLOW
                else -> MsFlag.GREEN
            }
        }
    }
}

internal fun msFlagColor(flag: MsFlag): Color = when (flag) {
    MsFlag.GREEN -> DashColors.Good
    MsFlag.YELLOW -> DashColors.Warning
    MsFlag.RED -> DashColors.Critical
}

/** One glow cycle of the frame: calm on green, quicker on a yellow or red flag. */
private fun breatheMs(flag: MsFlag): Int = if (flag == MsFlag.GREEN) 3_600 else 1_400

// --- Page background ----------------------------------------------------------------

/**
 * The whole screen: carbon weave, a red glow under the top bar, a soft sheen
 * across and a vignette, recorded once per size and appearance; then the flag
 * frame, a line round the screen with a glow inside it that breathes slowly
 * (the only thing that moves, and not at all with effects off).
 */
@Composable
internal fun motorsportBackground(): Modifier {
    val light = DashColors.Light
    val density = LocalDensity.current.density
    val carbon = remember(density, light) { carbonBrush(density, light) }
    val flag = rememberMsFlag()
    val breathe = rememberLoop(breatheMs(flag.value), reverse = true, rest = 0.6f)
    return remember(carbon, flag, breathe) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val day = DashColors.Light
            val red = DashColors.Critical
            val topGlowR = max(w, h) * 0.55f
            val topGlow = Brush.radialGradient(
                listOf(red.copy(alpha = if (day) 0.10f else 0.18f), red.copy(alpha = 0f)),
                center = Offset(w / 2f, 0f), radius = topGlowR
            )
            val sheen = Brush.linearGradient(
                0f to Color.Transparent, 0.45f to Color.White.copy(alpha = if (day) 0.22f else 0.025f), 0.55f to Color.Transparent,
                start = Offset(0f, 0f), end = Offset(w, h)
            )
            val vignette = Brush.radialGradient(
                0f to Color.Transparent, 0.62f to Color.Transparent, 1f to msShade(0.45f),
                center = Offset(w / 2f, h * 0.55f), radius = max(w, h) * 0.75f
            )
            val flat = DashColors.Effects == DashEffects.NONE
            val page = DashColors.Background
            val still = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            still.record {
                // Effects off: the plain page colour, for the clearest read in full sun.
                if (flat) {
                    drawRect(page)
                    return@record
                }
                drawRect(carbon)
                // An ellipse 2.3 times wider than tall, as the mockup's light hangs under the bar.
                scale(1f, 0.43f, Offset(w / 2f, 0f)) { drawCircle(topGlow, topGlowR, Offset(w / 2f, 0f)) }
                drawRect(sheen)
                drawRect(vignette)
            }

            val line = 4.dp.toPx()
            val reach = 36.dp.toPx()
            val glow = msGlow
            // Each flag's glow along the four edges, built once; the frame only picks one.
            val edges = MsFlag.entries.associateWith { f ->
                val c = msFlagColor(f)
                val a = c.copy(alpha = if (day) 0.30f else 0.42f)
                val z = c.copy(alpha = 0f)
                arrayOf(
                    Brush.verticalGradient(listOf(a, z), startY = 0f, endY = reach),
                    Brush.verticalGradient(listOf(z, a), startY = h - reach, endY = h),
                    Brush.horizontalGradient(listOf(a, z), startX = 0f, endX = reach),
                    Brush.horizontalGradient(listOf(z, a), startX = w - reach, endX = w)
                )
            }
            val frame = Stroke(line)
            onDrawBehind {
                drawLayer(still)
                val f = flag.value
                val g = edges.getValue(f)
                if (glow > 0f) {
                    val a = glow * (0.55f + 0.45f * breathe.value)
                    drawRect(g[0], size = Size(w, reach), alpha = a)
                    drawRect(g[1], topLeft = Offset(0f, h - reach), size = Size(w, reach), alpha = a)
                    drawRect(g[2], size = Size(reach, h), alpha = a)
                    drawRect(g[3], topLeft = Offset(w - reach, 0f), size = Size(reach, h), alpha = a)
                }
                drawRect(msFlagColor(f), topLeft = Offset(line / 2f, line / 2f), size = Size(w - line, h - line), style = frame)
            }
        }
    }
}

// --- Top bar ------------------------------------------------------------------------

private val BAR_H = 72.dp

/**
 * In the same order as every other look's bar: glossy round APPS and LAYOUT
 * buttons, the clock and the dashboard's position on the left, the
 * shift-light housing in the middle, and on the right the setup pill, alerts,
 * the phone, the flag tag, the OBD lamp and ⋮. The housing shares what the
 * left side leaves with the right side's badges, so the buttons' words show
 * only on a wide screen, and where fifteen lights would be too small to read
 * the housing steps aside.
 */
@Composable
internal fun MotorsportTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_H)
            // Own layer: the breathing frame behind does not re-record the bar.
            .graphicsLayer()
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        val labels = maxWidth >= 1_100.dp
        val narrow = maxWidth < NARROW_BAR
        val shared = barIsShared()
        // What the left side takes, roughly: two buttons (with their words when wide), the clock and the position chip.
        val leftGuess = (if (labels) 420.dp else 270.dp) - (if (m.merged) 80.dp else 0.dp)
        val showLights = (maxWidth - leftGuess) * (HOUSING_SHARE / (HOUSING_SHARE + 1f)) >= 170.dp
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DomeBarButton(
                    color = DashColors.Secondary,
                    onClick = m.onApps,
                    description = stringResource(R.string.dash_all_apps),
                    label = if (labels) stringResource(R.string.dash_apps).uppercase() else null
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                LayoutPicker(m) { open ->
                    DomeBarButton(
                        color = MsSilver,
                        onClick = open,
                        description = stringResource(R.string.dash_screen_layout, m.layout.title),
                        label = if (labels) stringResource(R.string.dash_layout_button).uppercase() else null
                    ) {
                        LayoutIcon(m.layout, null, MsDomeInk, Modifier.size(18.dp))
                    }
                }
                // The head unit's status bar shows the time while it is up.
                if (!m.merged) {
                    Spacer(Modifier.width(8.dp))
                    BarClock(m.clock)
                }
                PagePositionChip(Modifier.padding(start = 8.dp))
            }
            if (showLights) {
                Box(Modifier.weight(HOUSING_SHARE).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    ShiftLights(m, Modifier.widthIn(max = 600.dp).fillMaxWidth().height(46.dp))
                }
            }
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
                // The frame says the same, so the tag is the first to step aside.
                Box(Modifier.layoutId(BarRank.TEMP).padding(horizontal = 6.dp)) { FlagTag() }
                Box(Modifier.layoutId(BarRank.OBD)) { ObdLamp(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m) { open ->
                        DomeBarButton(color = MsSilver, onClick = open, description = stringResource(R.string.dash_more)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MsDomeInk, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The housing's share of the room the left side leaves, against 1 for the right side's badges. */
private const val HOUSING_SHARE = 1.4f

/** "09:24" in the heavy italic, AM / PM small beside it. */
@Composable
private fun BarClock(clock: String) {
    val (digits, amPm) = splitClock(clock)
    Row(verticalAlignment = Alignment.Bottom) {
        MsText(digits, msHero(30.sp, DashColors.TextPrimary), Modifier.alignByBaseline(), clip = true)
        if (amPm != null) {
            Spacer(Modifier.width(3.dp))
            MsText(amPm, msCaps(14.sp, DashColors.TextSecondary), Modifier.alignByBaseline())
        }
    }
}

/**
 * A button of the bar: a glossy round dome of [color] with the icon on it, in
 * a 56 dp target, and [label] in condensed caps beside it where there is room.
 * It sinks a little while pressed.
 */
@Composable
private fun DomeBarButton(
    color: Color,
    onClick: () -> Unit,
    description: String,
    label: String? = null,
    icon: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clip(RoundedCornerShape(28.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    val s = if (pressed) 0.92f else 1f
                    scaleX = s
                    scaleY = s
                }
                .glossyDome(color),
            contentAlignment = Alignment.Center
        ) { icon() }
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            MsText(label, msCaps(15.sp, DashColors.TextPrimary, FontWeight.ExtraBold))
        }
    }
}

/**
 * A glossy dome of [color]: a drop shadow, a coloured halo (with effects on),
 * the body lit from the upper left and a white glint, the way the mockup's
 * wheel buttons catch the light.
 */
@Composable
internal fun Modifier.glossyDome(color: Color, halo: Boolean = true): Modifier = cachedDraw(color, halo) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val glow = msGlow
    val body = Brush.radialGradient(
        0f to lerp(color, Color.White, 0.62f), 0.45f to color, 1f to lerp(color, Color.Black, 0.6f),
        center = c + Offset(-r * 0.3f, -r * 0.4f), radius = r * 1.45f
    )
    val haloBrush = Brush.radialGradient(listOf(color.copy(alpha = 0.45f * glow), Color.Transparent), c, r * 1.6f)
    val shadow = Brush.radialGradient(listOf(msShade(0.6f), Color.Transparent), c + Offset(0f, r * 0.25f), r * 1.25f)
    val glint = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), startY = c.y - r * 0.92f, endY = c.y - r * 0.05f
    )
    val glintSize = Size(r * 1.1f, r * 0.6f)
    val glintAt = Offset(c.x - glintSize.width / 2f, c.y - r * 0.86f)
    val rim = Stroke(1.dp.toPx())
    onDrawBehind {
        if (glow > 0f) {
            drawCircle(shadow, r * 1.25f, c + Offset(0f, r * 0.25f))
            if (halo) drawCircle(haloBrush, r * 1.6f, c)
        }
        if (DashColors.Effects == DashEffects.NONE) drawCircle(color, r, c) else drawCircle(body, r, c)
        drawCircle(Color.Black.copy(alpha = 0.35f), r - rim.width / 2f, c, style = rim)
        if (DashColors.Effects != DashEffects.NONE) drawOval(glint, glintAt, glintSize)
    }
}

/** The colours of the fifteen shift lights: five green, five red, five blue. */
private fun ledColor(i: Int): Color = when {
    i < 5 -> DashColors.Good
    i < 10 -> DashColors.Critical
    else -> DashColors.Secondary
}

private const val LEDS = 15

/**
 * The shift-light housing: a glossy black pill holding fifteen round lights
 * that come on one by one as the revs climb towards the shift point (about
 * 3 500 r/min on the 1.6 HDi, from the car's red line when its profile has
 * one), each with a bloom of its colour; at the shift point all fifteen
 * flash blue. With no revs to show, a faint light sweeps along them, so the
 * housing never reads as a reading. Only the housing redraws as the revs move.
 */
@Composable
private fun ShiftLights(m: TopBarModel, modifier: Modifier) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { CarBox.start(context) }
    // A State, read only in the draw below: the revs move many times a second.
    val revs = remember { Vehicle.bus.value(K.RPM).map { it?.roundToInt() }.distinctUntilChanged() }.collectAsState(null)
    val profile by CarProfileStore.profile.collectAsState()
    val redline = profile.redlineRpm?.takeIf { it > 0 } ?: DEFAULT_REDLINE_RPM
    val shift = (redline * 0.78f).coerceIn(2_500f, 7_000f)
    val start = shift * 0.36f
    val rpm = remember(revs) { { revs.value } }
    val atLimit by remember(rpm, shift) { derivedStateOf { (rpm() ?: 0) >= shift } }
    val idle by remember(rpm) { derivedStateOf { rpm() == null } }
    val flash = if (atLimit) rememberBlink(80L) else null
    val sweep = if (idle) rememberLoop(2_600, reverse = true, rest = -1f) else null
    val description = stringResource(R.string.skin_motorsport_cd_shift_lights)
    Box(
        modifier
            .semantics { contentDescription = description }
            .cachedDraw(shift, start, rpm, flash, sweep) {
                val w = size.width
                val h = size.height
                val day = DashColors.Light
                val glow = msGlow
                val corner = CornerRadius(h / 2f)
                val pad = h * 0.32f
                val d = min(h * 0.6f, (w - pad * 2f) / (LEDS + 0.38f * (LEDS - 1)))
                val gap = if (LEDS > 1) (w - pad * 2f - d * LEDS) / (LEDS - 1) else 0f
                val r = d / 2f
                val first = pad + r
                val cy = h / 2f
                val housing = Brush.verticalGradient(
                    listOf(msTone(Color(0xFF2A2A2E), Color(0xFFFAFAFC)), msTone(Color(0xFF0E0E10), Color(0xFFD9D9DF)))
                )
                val rimInk = msTone(Color(0xFF3A3A40), Color(0xFFB4B4BD))
                val socket = msTone(Color(0xFF050506), Color(0xFF9E9EA8))
                val colors = Array(LEDS) { ledColor(it) }
                // Brushes centred on the origin, moved to each light while drawing (no allocation per frame).
                fun lit(c: Color) = Brush.radialGradient(
                    0f to lerp(c, Color.White, 0.78f), 0.45f to c, 1f to lerp(c, Color.Black, 0.55f),
                    center = Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.35f
                )
                fun off(c: Color) = if (day) {
                    Brush.radialGradient(listOf(lerp(c, Color.White, 0.72f), lerp(c, Color.White, 0.45f)), Offset(-r * 0.3f, -r * 0.3f), r * 1.35f)
                } else {
                    Brush.radialGradient(listOf(lerp(c, Color.Black, 0.62f), lerp(c, Color.Black, 0.86f)), Offset(-r * 0.3f, -r * 0.3f), r * 1.35f)
                }
                fun bloom(c: Color) = Brush.radialGradient(listOf(c.copy(alpha = 0.7f * glow), c.copy(alpha = 0f)), Offset.Zero, r * 2.5f)
                val litB = colors.map { lit(it) }
                val offB = colors.map { off(it) }
                val bloomB = colors.map { bloom(it) }
                val blue = DashColors.Secondary
                val blueLit = lit(blue)
                val blueBloom = bloom(blue)
                val shadow = msShade(0.55f)
                val spec = MsSpecular
                val rimStroke = Stroke(1.dp.toPx())
                val sweepDot = Color.White.copy(alpha = if (day) 0.55f else 0.22f)
                onDrawBehind {
                    drawRoundRect(shadow, Offset(0f, 5.dp.toPx()), size, corner, alpha = if (glow > 0f) 1f else 0f)
                    drawRoundRect(housing, cornerRadius = corner)
                    drawRoundRect(rimInk, cornerRadius = corner, style = rimStroke)
                    drawLine(spec, Offset(corner.x, 1.5.dp.toPx()), Offset(w - corner.x, 1.5.dp.toPx()), 1.dp.toPx())
                    val now = rpm()
                    val lights = if (now == null) 0 else ((now - start) / (shift - start) * LEDS).toInt().coerceIn(0, LEDS)
                    val blinkOn = flash?.value
                    val sweepAt = sweep?.value?.let { it * (LEDS - 1) }
                    for (i in 0 until LEDS) {
                        val x = first + i * (d + gap)
                        translate(x, cy) {
                            drawCircle(socket, r * 1.2f, Offset.Zero)
                            when {
                                blinkOn != null -> if (blinkOn) {
                                    if (glow > 0f) drawCircle(blueBloom, r * 2.5f, Offset.Zero)
                                    drawCircle(blueLit, r, Offset.Zero)
                                } else drawCircle(offB[i], r, Offset.Zero)
                                i < lights -> {
                                    if (glow > 0f) drawCircle(bloomB[i], r * 2.5f, Offset.Zero)
                                    drawCircle(litB[i], r, Offset.Zero)
                                }
                                else -> {
                                    drawCircle(offB[i], r, Offset.Zero)
                                    if (sweepAt != null && sweepAt >= 0f) {
                                        val a = (1f - abs(i - sweepAt) / 1.6f).coerceIn(0f, 1f)
                                        if (a > 0f) drawCircle(sweepDot, r * 0.8f, Offset.Zero, alpha = a)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    )
}

/** The flag as a skewed tag in its colour: GREEN FLAG, YELLOW FLAG, RED FLAG. */
@Composable
private fun FlagTag() {
    val flag by rememberMsFlag()
    val text = stringResource(
        when (flag) {
            MsFlag.GREEN -> R.string.skin_motorsport_flag_green
            MsFlag.YELLOW -> R.string.skin_motorsport_flag_yellow
            MsFlag.RED -> R.string.skin_motorsport_flag_red
        }
    )
    val color = msFlagColor(flag)
    Box(
        Modifier
            .height(30.dp)
            .cachedDraw(color) {
                val skew = size.height * 0.18f
                val tag = Path().apply {
                    moveTo(skew, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width - skew, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                val glow = msGlow
                val halo = Stroke(5.dp.toPx())
                onDrawBehind {
                    if (glow > 0f) drawPath(tag, color.copy(alpha = 0.3f * glow), style = halo)
                    drawPath(tag, color)
                }
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        MsText(text, msCaps(16.sp, MsDomeInk, FontWeight.ExtraBold).copy(fontStyle = FontStyle.Italic))
    }
}

/**
 * The OBD link as a pit-wall lamp: a glossy light in the link's colour (green
 * linked, yellow pulsing while connecting, red on error, a dark glass when
 * off, so its shape tells too: a ring when off, a "!" on error) and the
 * letters OBD. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun ObdLamp(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val off = state == ObdConnectionState.DISCONNECTED
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    ObdMark(state, onConnect, shape = RoundedCornerShape(14.dp), clickLabel = stringResource(R.string.skin_motorsport_connect_obd)) {
        Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(16.dp)
                    .cachedDraw(color, off, pulse) {
                        val r = size.minDimension / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val glow = msGlow
                        val halo = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), c, r * 2.3f)
                        val glass = Brush.radialGradient(
                            listOf(lerp(color, Color.White, 0.7f), color, lerp(color, Color.Black, 0.5f)),
                            center = c + Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.3f
                        )
                        val ring = Stroke(1.5.dp.toPx())
                        onDrawBehind {
                            if (off) {
                                drawCircle(MsWell, r, c)
                                drawCircle(color, r - ring.width / 2f, c, style = ring)
                            } else {
                                val a = pulse?.let { 0.35f + 0.65f * it.value } ?: 1f
                                if (glow > 0f) drawCircle(halo, r * 2.3f, c, alpha = a)
                                drawCircle(glass, r, c, alpha = a)
                            }
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            MsText(stringResource(R.string.dash_obd_short), msCaps(15.sp, if (off) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.ExtraBold))
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(3.dp))
                MsText("!", msCaps(15.sp, color, FontWeight.Black))
            }
        }
    }
}

// --- Panels -------------------------------------------------------------------------

/** Room between a tile's edge and its panel, and the panel's corners. */
internal val MS_INSET = 4.dp
internal val MS_RADIUS = 16.dp
internal val MsPanelShape = RoundedCornerShape(MS_RADIUS)

/** The panel faces of the pit wall. */
internal enum class MsPanel {
    /** A display: dark glass shading down (white to pale grey by day). */
    SCREEN,
    /** The car's pad: a radial light in the middle. */
    PAD,
    /** Team radio: a red wash from the upper left, a red rim. */
    RADIO,
    /** The wheel's button field: a light from the top edge. */
    WHEEL
}

/**
 * A pit-wall panel inset in its tile: a stepped drop shadow (no blur on this
 * GPU), the face, a hairline rim and a specular top edge. With effects off
 * the face is the plain card colour, no shadow and no sheen. Built once per
 * size and appearance.
 */
@Composable
internal fun Modifier.msPanel(kind: MsPanel = MsPanel.SCREEN, rim: Color? = null): Modifier = this
    .padding(MS_INSET)
    .cachedDraw(kind, rim) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(MS_RADIUS.toPx())
        val effects = DashColors.Effects
        val plain = effects == DashEffects.NONE
        val day = DashColors.Light
        val face: Brush = when {
            plain -> Brush.verticalGradient(listOf(DashColors.Card, DashColors.Card))
            kind == MsPanel.PAD -> Brush.radialGradient(
                0f to msTone(Color(0xFF1E1E25), Color.White), 0.75f to msTone(Color(0xFF0B0B0D), Color(0xFFE9E9EE)),
                center = Offset(w / 2f, h / 2f), radius = max(w, h) * 0.62f
            )
            kind == MsPanel.WHEEL -> Brush.radialGradient(
                0f to msTone(Color(0xFF202024), Color.White), 0.8f to msTone(Color(0xFF0B0B0D), Color(0xFFE6E6EB)),
                center = Offset(w / 2f, 0f), radius = max(w, h) * 0.9f
            )
            kind == MsPanel.RADIO -> Brush.verticalGradient(listOf(msTone(Color(0xFF131315), Color.White), msTone(Color(0xFF0C0C0E), Color(0xFFF2EEEE))))
            else -> Brush.verticalGradient(listOf(MsPanelTop, MsPanelBottom))
        }
        val red = DashColors.Critical
        val wash = if (kind == MsPanel.RADIO && !plain) Brush.linearGradient(
            0f to red.copy(alpha = if (day) 0.16f else 0.28f), 0.55f to red.copy(alpha = 0f),
            start = Offset.Zero, end = Offset(w, h)
        ) else null
        val rimInk = rim ?: if (kind == MsPanel.RADIO) red.copy(alpha = 0.5f) else MsPanelRim
        val rimStroke = Stroke(1.dp.toPx())
        val half = rimStroke.width / 2f
        val spec = MsSpecular
        val s1 = msShade(0.32f)
        val s2 = msShade(0.16f)
        val d1 = 3.dp.toPx()
        val d2 = 9.dp.toPx()
        onDrawBehind {
            if (!plain) {
                drawRoundRect(s2, Offset(-d1, d2 * 0.6f), Size(w + d1 * 2f, h + d2 * 0.6f), CornerRadius(r.x + d1))
                drawRoundRect(s1, Offset(0f, d1), size, r)
            }
            drawRoundRect(face, cornerRadius = r)
            if (wash != null) drawRoundRect(wash, cornerRadius = r)
            drawRoundRect(rimInk, Offset(half, half), Size(w - half * 2f, h - half * 2f), CornerRadius(r.x - half), style = rimStroke)
            if (!plain) drawLine(spec, Offset(r.x, half + 1f), Offset(w - r.x, half + 1f), 1.dp.toPx())
        }
    }
    .clip(MsPanelShape)

/** The whole panel as one button (with the tap sound and buzz), or nothing when [enabled] is false. */
@Composable
internal fun Modifier.msTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/**
 * A segmented bar of [count] blocks with a gap between them, the first [lit]
 * on: green ones up to [redFrom], red ones from it; unlit blocks keep a dim
 * tint of their zone. [lit] is read while drawing, so a rev change only
 * redraws the bar.
 */
@Composable
internal fun MsSegments(count: Int, redFrom: Int, lit: () -> Int, modifier: Modifier) {
    Box(
        modifier.cachedDraw(count, redFrom, lit) {
            val gap = 3.dp.toPx()
            val bw = (size.width - gap * (count - 1)) / count
            val green = DashColors.Good
            val red = DashColors.Critical
            val offG = MsSegOff
            val offR = MsSegOffRed
            val corner = CornerRadius(2.dp.toPx())
            val glow = msGlow
            val bloomH = size.height * 0.5f
            onDrawBehind {
                val n = lit()
                for (i in 0 until count) {
                    val x = i * (bw + gap)
                    val on = i < n
                    val c = if (i >= redFrom) red else green
                    if (on && glow > 0f) {
                        drawRect(c.copy(alpha = 0.22f * glow), Offset(x - 1.5f, -bloomH * 0.25f), Size(bw + 3f, size.height + bloomH * 0.5f))
                    }
                    drawRoundRect(if (on) c else if (i >= redFrom) offR else offG, Offset(x, 0f), Size(bw, size.height), corner)
                }
            }
        }
    )
}

/** An icon on a glossy dome, for media keys and the launch bar's pencil. */
@Composable
internal fun MsIcon(icon: ImageVector, tint: Color, size: Dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

// --- Tiles --------------------------------------------------------------------------

/**
 * Motorsport renderer for app shortcuts, launch bars and the main widgets;
 * anything else keeps its standard renderer on the skin's palette.
 */
@Composable
internal fun MotorsportTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> MsAppButton(item, env)
        is DashboardItem.LaunchBar -> MsWheelButtons(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> MsTelemetry(env)
            BuiltinKind.SPEED_HUD -> MsDriverDisplay(env)
            BuiltinKind.MEDIA -> MsTeamRadio(env)
            BuiltinKind.NAVIGATION -> MsTrackMap(env)
            BuiltinKind.CLOCK -> MsClock(env)
            BuiltinKind.WEATHER -> MsWeather()
            BuiltinKind.RANGE -> MsPitBoard(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Maps window frame --------------------------------------------------------------

/**
 * A pit-wall monitor round a docked app window: the corners rounded off in
 * the page colour, a dark bezel, the flag colour as a thin inner rim with a
 * glow down from the top edge, so the page's frame runs on round the map.
 * Takes no touches and leaves the middle clear.
 */
@Composable
internal fun MotorsportWindowFrame(modifier: Modifier) {
    val flag = rememberMsFlag()
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = 16.dp.toPx()
                val bezel = 4.dp.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val page = DashColors.Background
                val bezelInk = Brush.verticalGradient(listOf(msTone(Color(0xFF2A2A2E), Color(0xFFF4F4F7)), msTone(Color(0xFF0B0B0D), Color(0xFFCFCFD6))))
                val bezelStroke = Stroke(bezel)
                val rimStroke = Stroke(2.dp.toPx())
                val glowH = 28.dp.toPx()
                val glow = msGlow
                val tops = MsFlag.entries.associateWith { f ->
                    val c = msFlagColor(f)
                    Brush.verticalGradient(listOf(c.copy(alpha = 0.35f * glow), c.copy(alpha = 0f)), startY = bezel, endY = bezel + glowH)
                }
                onDrawBehind {
                    val c = msFlagColor(flag.value)
                    drawPath(mask, page)
                    if (glow > 0f) drawRect(tops.getValue(flag.value), Offset(bezel, bezel), Size(w - bezel * 2f, glowH))
                    drawRoundRect(bezelInk, Offset(bezel / 2f, bezel / 2f), Size(w - bezel, h - bezel), CornerRadius(r - bezel / 2f), style = bezelStroke)
                    val inset = bezel + rimStroke.width / 2f
                    drawRoundRect(c, Offset(inset, inset), Size(w - inset * 2f, h - inset * 2f), CornerRadius(max(0f, r - inset)), style = rimStroke)
                }
            }
    )
}

// --- Theme stamp --------------------------------------------------------------------

/** The skin as a stamp in the theme picker: the shift lights, an LCD gear, italic speed and the green flag frame. */
internal fun DrawScope.motorsportStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    // Carbon hint: a few diagonal tows.
    val tow = if (p.Light) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.04f)
    var x = -h
    while (x < w) {
        drawLine(tow, Offset(x, h), Offset(x + h, 0f), 2f)
        x += 6f
    }
    drawRect(p.Critical.copy(alpha = if (p.Light) 0.08f else 0.14f), size = Size(w, h * 0.3f))
    // The shift lights in their housing.
    val hw = w * 0.62f
    val hx = (w - hw) / 2f
    drawRoundRect(if (p.Light) Color(0xFFF4F4F6) else Color(0xFF1C1C20), Offset(hx, h * 0.08f), Size(hw, h * 0.16f), CornerRadius(h * 0.08f))
    val r = h * 0.035f
    for (i in 0 until LEDS) {
        val c = when {
            i < 5 -> p.Good
            i < 10 -> p.Critical
            else -> p.Secondary
        }
        val lit = i < 6
        val cx = hx + hw * (0.08f + 0.84f * i / (LEDS - 1))
        drawCircle(if (lit) c else c.copy(alpha = 0.25f), r, Offset(cx, h * 0.16f))
    }
    // LCD gear on the left, italic speed on the right.
    drawRoundRect(if (p.Light) Color(0xFFC6C9AC) else Color(0xFF0B0E05), Offset(w * 0.1f, h * 0.34f), Size(w * 0.24f, h * 0.52f), CornerRadius(4f))
    number("4", w * 0.22f, h * 0.6f, 16f, if (p.Light) Color(0xFF1E2410) else p.Accent)
    number("87", w * 0.62f, h * 0.56f, 18f, p.TextPrimary)
    drawRoundRect(p.Good, Offset(w * 0.46f, h * 0.78f), Size(w * 0.34f, h * 0.05f), CornerRadius(2f))
    // The flag frame.
    drawRect(p.Good, style = Stroke(3f))
}
