package com.openauto.dash

import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberReadings
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberTyres
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Canvas: the live map is the page (MapLibrePanel as a wallpaper, under the
 * whole dashboard) and everything else floats over it on smoked glass. Its
 * home is the trip on one line across the top (the next turn, then rain, a
 * break, sunset and the arrival where they fall), the speed on the left, the
 * car on the right and the music at the foot, with the middle left open for
 * the map. A rail at the driver's side takes the bottom bar's place.
 * Android 10 cannot blur what lies behind a panel, so the glass is a
 * translucent fill with a hairline rim, not frosted.
 */

/** Room the rail takes at the side of the pages: its width and its margins. */
internal val CANVAS_RAIL_SPACE = 104.dp
/** The rail itself (CanvasRail here, CanvasTabRail over an app), inside [CANVAS_RAIL_SPACE]. */
internal val CANVAS_RAIL_WIDTH = 88.dp
internal val CanvasRailShape = RoundedCornerShape(30.dp)
private val GlassShape = RoundedCornerShape(28.dp)
/** Digits of one width, so a changing number does not shuffle what stands beside it. */
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * Smoked glass: the theme's translucent card, a hairline rim and a catch of
 * light along the top edge. "Plain, for sunlight" (the effects setting)
 * promises opaque cards, and gets them here too: a map showing through the
 * glass is what it is there to switch off. Kept per palette and shape: the
 * draw cache would otherwise be thrown away at every recomposition of a tile.
 */
@Composable
internal fun canvasGlass(shape: Shape = GlassShape): Modifier {
    val plain = DashColors.Effects == DashEffects.NONE
    val fill = DashColors.Card.let { if (plain) it.copy(alpha = 1f) else it }
    val rim = DashColors.Line
    val catchAlpha = if (DashColors.Light) 0.9f else 0.16f
    return remember(shape, fill, rim, catchAlpha) {
        Modifier
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, rim, shape)
            .drawWithCache {
                val catch = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = catchAlpha), Color.Transparent),
                    startX = size.width * 0.12f, endX = size.width * 0.88f
                )
                onDrawWithContent {
                    drawContent()
                    drawLine(catch, Offset(size.width * 0.12f, 1f), Offset(size.width * 0.88f, 1f), 1.5f)
                }
            }
    }
}

// --- The page: the map ---------------------------------------------------------------------------

/**
 * The map under the whole dashboard, with the far distance fading into the
 * page at the top so the trip strip over it stays easy to read. On the
 * emulator, whose software GL the map crashes, a drawn street grid stands in.
 */
@Composable
internal fun CanvasBackdrop(modifier: Modifier) {
    val bg = DashColors.Background
    Box(modifier = modifier.background(bg)) {
        if (isEmulator) StreetGridStandIn(Modifier.fillMaxSize()) else MapLibrePanel(Modifier.fillMaxSize(), wallpaper = true)
        Spacer(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to bg, 0.2f to bg.copy(alpha = 0.8f), 0.45f to bg.copy(alpha = 0f))
            )
        )
    }
}

/** Streets running off to a horizon, for the emulator only. */
@Composable
private fun StreetGridStandIn(modifier: Modifier) {
    val road = DashColors.TextPrimary.copy(alpha = 0.07f)
    val block = DashColors.TextPrimary.copy(alpha = 0.035f)
    val route = DashColors.Accent
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val vp = Offset(w / 2f, h * 0.12f)
        for (i in -14..14) {
            val x = w / 2f + i * w * 0.11f
            drawLine(road, vp, Offset(x, h), strokeWidth = 3f)
        }
        var y = h * 0.2f
        var gap = 10f
        while (y < h) {
            drawLine(road, Offset(0f, y), Offset(w, y), strokeWidth = 3f)
            drawRect(block, Offset(0f, y + 4f), Size(w, gap * 0.5f))
            y += gap
            gap *= 1.35f
        }
        drawLine(route.copy(alpha = 0.25f), vp, Offset(w / 2f, h), strokeWidth = 26f)
        drawLine(route, vp, Offset(w / 2f, h), strokeWidth = 9f)
    }
}

// --- The rail ------------------------------------------------------------------------------------

/**
 * The rail in the bar's place, at the driver's side: home, apps, music,
 * phone, the OBD link (a dot in its colour), the setup when something is
 * still to allow, and ⋮. The time sits at its foot unless the head unit's
 * own bar already shows it.
 */
@Composable
internal fun CanvasRail(m: TopBarModel, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(8.dp)
            .width(CANVAS_RAIL_WIDTH)
            .then(canvasGlass(CanvasRailShape))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The buttons scroll on a short screen; the setup, ⋮ and the time stay
        // pinned under them. As one fixed column the rail cut off its foot, ⋮
        // with it, and a driver who picked this look had no way back to Settings.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RailButton(Icons.Filled.Home, stringResource(R.string.dash_page_home), active = m.page == DashboardStore.CENTER, onClick = onHome)
            RailButton(Icons.Filled.Apps, stringResource(R.string.dash_all_apps), onClick = m.onApps)
            RailButton(Icons.Filled.MusicNote, stringResource(R.string.apps_kind_media)) { openMusic(context, m.onApps) }
            RailButton(Icons.Filled.Phone, stringResource(R.string.settings_section_phone)) {
                // Dashwheel's own phone screen; where none can show, the unit's phone app, else the drawer.
                PhoneScreen.open(context, m.onApps)
            }
            RailButton(Icons.Filled.DirectionsCar, obdStatusLabel(m.obdConnection), dot = obdStatusColor(m.obdConnection), onClick = m.onConnectObd)
        }
        Spacer(Modifier.height(10.dp))
        if (m.setupPending) {
            RailButton(Icons.Filled.Info, stringResource(R.string.setup_pill), tint = DashColors.Warning) { m.onSetup(false) }
            Spacer(Modifier.height(10.dp))
        }
        MorePicker(m) { open -> RailButton(Icons.Filled.MoreVert, stringResource(R.string.dash_more), onClick = open) }
        if (!m.merged) {
            Spacer(Modifier.height(8.dp))
            Text(m.clock, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = fixedSp(17f), style = Tabular, maxLines = 1)
        }
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    tint: Color = DashColors.TextPrimary,
    dot: Color? = null,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (active) DashColors.Accent.copy(alpha = 0.18f) else DashColors.CardHi)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) DashColors.Accent else tint, modifier = Modifier.size(28.dp))
        if (dot != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(10.dp).clip(CircleShape).background(dot))
        }
    }
}

/** The last music app used, else the app drawer to pick one. */
private fun openMusic(context: Context, orApps: () -> Unit) {
    val pkg = CarMediaController.getLastMediaPackage(context)
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent == null || !context.launchSafely(intent)) orApps()
}

// --- Tiles ---------------------------------------------------------------------------------------

@Composable
internal fun CanvasTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVIGATION -> CanvasJourney(env)
            BuiltinKind.SPEED_HUD -> CanvasSpeed(env)
            BuiltinKind.TELEMETRY -> CanvasCar(env)
            BuiltinKind.RANGE -> CanvasRange(env)
            BuiltinKind.MEDIA -> CanvasMedia(env)
            BuiltinKind.CLOCK -> CanvasClock()
            BuiltinKind.WEATHER -> CanvasWeather()
            else -> StandardSkinnedTile(item, env)
        }
        // App shortcuts and launch bars sit on the theme's glass card as they are.
        else -> StandardSkinnedTile(item, env)
    }
}

/** A number is never drawn smaller than this (dp), whatever its room: past it, it clips. */
private const val MIN_NUM_DP = 14f

/**
 * A tile's number: tabular, semi-bold, never scaled by the system font size
 * past its box. Asked for at [sizeDp], and shrunk to the width it has when
 * that is too narrow for it ("10:45 PM" at 56 in a two-column clock lost its
 * "PM" to the clip). Measured on its digits as eights, so the size holds
 * while the value ticks.
 */
@Composable
private fun Num(text: String, sizeDp: Float, color: Color = DashColors.TextPrimary, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    val wantedSp = maxOf(sizeDp, MIN_TEXT_DP) / density.fontScale
    BoxWithConstraints(modifier) {
        val roomPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val template = exTemplate(text)
        val fontSize = remember(template, wantedSp, roomPx, base, density) {
            val probe = measurer.measure(template, base.merge(Tabular).copy(fontSize = wantedSp.sp, fontWeight = FontWeight.SemiBold), softWrap = false, maxLines = 1)
            val width = probe.size.width
            if (width <= roomPx) wantedSp.sp
            else maxOf(wantedSp * roomPx / width.coerceAtLeast(1), MIN_NUM_DP / density.fontScale).sp
        }
        Text(text, color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
            style = Tabular, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun Label(text: String, sizeDp: Float = 16f, color: Color = DashColors.TextSecondary, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) {
    Text(text, modifier = modifier, color = color, fontSize = fixedSp(sizeDp), fontWeight = FontWeight.Medium,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)
}

/** The speed on a round glass dial, its arc filling to 180 km/h; amber when [speedOver]. */
@Composable
private fun CanvasSpeed(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val revs = rememberGaugeInt(K.RPM)
    val fast = speedOver(speed)
    val arc = if (fast) DashColors.Warning else DashColors.Accent
    val track = DashColors.CardHi
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = min(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .size(d)
                .then(canvasGlass(CircleShape))
                .drawBehind {
                    val stroke = 5.dp.toPx()
                    val inset = stroke / 2f + 6.dp.toPx()
                    val box = Size(size.width - inset * 2f, size.height - inset * 2f)
                    drawArc(track, 135f, 270f, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (speed != null && speed > 0) {
                        drawArc(arc, 135f, 270f * (speed / 180f).coerceIn(0f, 1f), false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val units = LocalUnits.current
                Num(speed?.let { units.speed(it).toString() } ?: "–", d.value * 0.32f, if (fast) DashColors.Warning else DashColors.TextPrimary)
                Label(units.speedUnit, (d.value * 0.075f).coerceAtLeast(13f))
                if (revs != null && d >= 200.dp) {
                    Spacer(Modifier.height(4.dp))
                    Label(String.format(Locale.getDefault(), "%,d rpm", revs), 14f, DashColors.Muted)
                }
            }
        }
    }
}

private class CarLine(val label: String, val value: String, val warn: Boolean)

/**
 * The car at a glance: range, coolant, battery, and a tyre that needs
 * looking at, each on its own line, amber when out of range. With nothing
 * to read yet, the OBD link's pill to connect it.
 */
@Composable
private fun CanvasCar(env: SkinTileEnv) {
    val d = rememberReadings(K.COOLANT_TEMP, K.BATTERY_VOLTS)
    val fuel = rememberFuel()
    val tyres = rememberTyres()
    val problems by Tyres.problems.collectAsState()
    val unit by Tyres.unit.collectAsState()
    val tyre = problems.keys.firstNotNullOfOrNull { pos -> tyres[pos]?.let { pos to it } }
    val units = LocalUnits.current
    val lines = buildList {
        fuel?.let { add(CarLine(stringResource(R.string.car_status_range), units.distanceText(it.rangeKm), it.percent <= 10)) }
        if (d.coolantTempC > 0) add(CarLine(stringResource(R.string.info_chip_coolant), units.tempText(d.coolantTempC), d.coolantTempC >= 105))
        if (d.voltage > 0.0) add(CarLine(stringResource(R.string.info_chip_battery), String.format(Locale.getDefault(), "%.1f V", d.voltage), d.voltage < 11.8))
        tyre?.let { (pos, t) -> add(CarLine(stringResource(pos.labelRes), formatPressure(t.kPa, unit), true)) }
    }
    Column(
        modifier = Modifier.fillMaxSize().then(canvasGlass()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = if (lines.isEmpty()) Arrangement.spacedBy(12.dp, Alignment.CenterVertically) else Arrangement.SpaceEvenly
    ) {
        if (lines.isEmpty()) {
            Label(obdStatusLabel(env.obdConnection), maxLines = 2)
            ObdPill(env.obdConnection, env.onConnectObd)
        } else {
            lines.take(4).forEach { line ->
                val color = if (line.warn) DashColors.Warning else DashColors.TextPrimary
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label(line.label, 16f, if (line.warn) DashColors.Warning else DashColors.TextSecondary, Modifier.weight(1f))
                    Num(line.value, 24f, color)
                }
            }
        }
    }
}

/** Range as one big number, the tank as a bar under it, the level in words. */
@Composable
private fun CanvasRange(env: SkinTileEnv) {
    val fuel = rememberFuel() ?: return StandardSkinnedTile(DashboardItem.BuiltinWidget(BuiltinKind.RANGE), env)
    val low = fuel.percent <= 10
    Column(modifier = Modifier.fillMaxSize().then(canvasGlass()).padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalGasStation, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Label(stringResource(R.string.car_status_range))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val units = LocalUnits.current
            // The unit is measured first: the number takes what is left, shrinking before it clips.
            Num(units.distance(fuel.rangeKm).toString(), 48f, if (low) DashColors.Warning else DashColors.TextPrimary, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Label(units.distanceUnit, 20f, modifier = Modifier.padding(bottom = 6.dp))
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(DashColors.CardHi)) {
            Box(Modifier.fillMaxWidth((fuel.percent / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(if (low) DashColors.Warning else DashColors.TextPrimary))
        }
        Label(String.format(Locale.getDefault(), "%s%d %% · %.1f L", if (fuel.percentEstimated) "≈ " else "", fuel.percent, fuel.liters))
    }
}

/**
 * The music island: the cover, the song and who sings it, and the controls,
 * with the track's progress as a thin line along its foot. Rounded into a
 * pill when it is low. Without media access it says so and opens the setting.
 */
@Composable
private fun CanvasMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val context = env.context
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val fraction = if (access) rememberMediaFraction(ms, env.mediaController) else 0f
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // An island, not a slab: at most [ISLAND_HEIGHT] tall, at the foot of its tile.
        val h = min(maxHeight, ISLAND_HEIGHT)
        val radius = min(h / 2f, 48.dp)
        val shape = RoundedCornerShape(radius)
        val art = (h - 28.dp).coerceIn(40.dp, 96.dp)
        val wide = maxWidth >= 440.dp
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(h)
                .then(canvasGlass(shape))
                .then(if (!access && !env.editing) Modifier.clickable { CarMediaController.openNotificationAccessSettings(context) } else Modifier)
                .padding(start = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cover = ms.artwork
            val bitmap = remember(cover) { cover?.asImageBitmap() }
            Box(
                modifier = Modifier.size(art).clip(RoundedCornerShape(art * 0.22f))
                    .background(Brush.linearGradient(listOf(DashColors.Accent.copy(alpha = 0.55f), DashColors.Accent2.copy(alpha = 0.25f)))),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(art * 0.4f))
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = when {
                    !access -> stringResource(R.string.info_media_access_needed)
                    hasTrack -> ms.title
                    else -> stringResource(R.string.info_nothing_playing)
                }
                Text(title, color = DashColors.TextPrimary, fontSize = fixedSp(22f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (!access) stringResource(R.string.info_media_grant_access) else ms.artist
                if (sub.isNotBlank()) Label(sub, 17f, if (!access) DashColors.Accent else DashColors.TextSecondary)
            }
            if (access) {
                if (wide) MediaKey(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous)) { env.mediaController.previous() }
                Spacer(Modifier.width(10.dp))
                MediaKey(if (ms.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(R.string.info_media_play_pause), primary = true) { env.mediaController.playPause() }
                Spacer(Modifier.width(10.dp))
                MediaKey(Icons.Filled.SkipNext, stringResource(R.string.info_media_next)) { env.mediaController.next() }
            }
        }
        if (access && ms.durationMs > 0L) {
            // The progress: a hairline along the foot, inside the rounded ends.
            Box(Modifier.align(Alignment.BottomStart).padding(start = radius, end = radius, bottom = 3.dp).fillMaxWidth().height(3.dp)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(DashColors.Accent))
            }
        }
    }
}

/** The music island's height at most: a pill the thumb finds without looking. */
private val ISLAND_HEIGHT = 112.dp

@Composable
private fun MediaKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, primary: Boolean = false, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val size = if (primary) 72.dp else 60.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) DashColors.TextPrimary else DashColors.CardHi)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) DashColors.Background else DashColors.TextPrimary, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun CanvasClock() {
    val context = LocalContext.current
    val now = rememberNow(1_000L)
    val time = rememberTimeFormat()
    val date = rememberDateFormat("EEEEdMMMM", best = true)
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier.fillMaxSize().then(canvasGlass())
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.horizon_open_clock)) { tap(); openClockApp(context) }
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Num(time.format(now), 56f)
        Label(date.format(now), 18f)
    }
}

@Composable
private fun CanvasWeather() {
    val weather = rememberWeather()
    val time = rememberTimeFormat()
    val units = LocalUnits.current
    // A clock that ticks: "rain from" turns into "rain until" when the rain starts, not at the next refresh.
    val now = rememberWallClock(60_000L).longValue
    Column(modifier = Modifier.fillMaxSize().then(canvasGlass()).padding(20.dp), verticalArrangement = Arrangement.Center) {
        if (weather == null) {
            // Say why, as the other looks do: "--°" alone reads as broken.
            val wait = rememberWeatherWait()
            Num("--°", 48f, DashColors.Muted)
            Label(
                stringResource(
                    when (wait) {
                        WeatherWait.LOADING -> R.string.horizon_loading
                        WeatherWait.NO_GPS -> R.string.info_waiting_gps
                        WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
                    }
                ),
                16f,
                DashColors.Muted
            )
        } else {
            Num(String.format(Locale.getDefault(), "%.0f°", units.tempExact(weather.tempC)), 52f)
            Label(weather.condition, 18f)
            val from = weather.rainFromMs
            val until = weather.rainUntilMs
            when {
                from != null && until != null && from <= now -> Label(stringResource(R.string.canvas_rain_until, time.format(Date(until))), 16f, DashColors.AccentInk)
                from != null -> Label(stringResource(R.string.canvas_rain_from, time.format(Date(from))), 16f, DashColors.AccentInk)
            }
        }
    }
}

// --- The journey strip ---------------------------------------------------------------------------

/** Something on the line: at [atMs], a [title] (a time) over a [caption]. */
private class Stop(val atMs: Long, val title: String, val caption: String, val lead: Boolean = false)

/** Under this width the strip drops its line: the labels along it would not fit. */
private val NARROW_STRIP = 560.dp

/** How much of the day the line covers without a route. */
private const val DAY_LINE_HOURS = 6

/**
 * The trip across the top. Navigating (Google Maps or Waze): the next turn
 * on the left, then one line from now to the arrival, with rain, a break
 * and sunset placed where they fall and the fuel left on arrival at its
 * end. Without a route: the time and date with a way to Maps, and the next
 * hours as the line: appointments, rain and sunset.
 */
@Composable
private fun CanvasJourney(env: SkinTileEnv) {
    val context = LocalContext.current
    val nav by NavDirections.state.collectAsState()
    val clock = rememberWallClock(30_000L).longValue
    val timeFmt = rememberTimeFormat()
    val units = LocalUnits.current
    fun hm(ms: Long) = timeFmt.format(Date(ms))

    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val weather = rememberWeather()
    val care by CarCare.state.collectAsState()
    val fuel = rememberFuelToDest()
    val agenda = rememberAgenda(context, enabled = !nav.active)

    val rainWord = stringResource(R.string.canvas_rain)
    val rainStops = stringResource(R.string.canvas_rain_stops)
    val breakWord = stringResource(R.string.canvas_break)
    val sunsetWord = stringResource(R.string.canvas_sunset)
    val noTitle = stringResource(R.string.info_agenda_no_title)

    val arrival = if (nav.active) arrivalMs(nav.eta, clock) else null
    val start = clock
    val end = arrival ?: (clock + DAY_LINE_HOURS * 3_600_000L)
    val stops = buildList {
        val from = weather?.rainFromMs
        val until = weather?.rainUntilMs
        if (from != null && until != null) {
            if (from > start) add(Stop(from, hm(from), rainWord)) else if (until < end) add(Stop(until, hm(until), rainStops))
        }
        if (nav.active) {
            val due = CareRules.FIRST_BREAK_MIN * 60_000L
            val driving = care.rest.drivingMs
            if (driving in 1 until due) (start + due - driving).let { add(Stop(it, hm(it), breakWord)) }
        } else {
            agenda.filter { !it.allDay && it.begin > start }.take(2).forEach {
                add(Stop(it.begin, hm(it.begin), it.title.ifBlank { noTitle }, lead = true))
            }
        }
        val l = location
        if (l != null) nextSunset(start, end, l.latitude, l.longitude)?.let { add(Stop(it, hm(it), sunsetWord)) }
    }.filter { it.atMs in (start + 1)..(end - 1) }.sortedBy { it.atMs }

    val endStop = if (nav.active) {
        val spare = fuel?.takeIf { it.toGoKm != null }?.spareKm
        // The time left and the distance, without the clock time the title already shows.
        val rest = nav.etaParts.filterNot { ARRIVAL_CLOCK.containsMatchIn(it) }
        Stop(end, if (arrival != null) hm(arrival) else rest.firstOrNull() ?: nav.etaParts.firstOrNull().orEmpty(),
            spare?.let { stringResource(if (units.imperial) R.string.units_canvas_fuel_left_mi else R.string.canvas_fuel_left, units.distance(it)) }
                ?: (if (arrival != null) rest.firstOrNull() else rest.getOrNull(1)).orEmpty())
    } else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(canvasGlass())) {
        // Too narrow for a line worth reading (a phone upright, a small tile): the
        // turn or the time, and the arrival beside it.
        if (maxWidth < NARROW_STRIP) {
            val stripW = maxWidth
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (nav.active) NextTurn(nav, onOpen = { openNavApp(context, nav.packageName) })
                    else Today(clock, timeFmt, onMaps = { openNavApp(context, null) })
                }
                if (endStop != null) {
                    // At most a third of the strip, so the turn keeps the room to read.
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp).widthIn(max = stripW * 0.32f)) {
                        Num(endStop.title, 22f, DashColors.Good)
                        Label(endStop.caption, 13f, align = TextAlign.End, maxLines = 2)
                    }
                }
            }
        } else {
            val leftW = min(maxWidth * 0.32f, 320.dp)
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(leftW)) {
                    if (nav.active) NextTurn(nav, onOpen = { openNavApp(context, nav.packageName) })
                    else Today(clock, timeFmt, onMaps = { openNavApp(context, null) })
                }
                Box(Modifier.padding(horizontal = 20.dp).width(1.dp).fillMaxHeight(0.7f).background(DashColors.Line))
                JourneyLine(
                    start = start, end = end, stops = stops, endStop = endStop,
                    header = if (nav.active) nav.etaParts.filterNot { ARRIVAL_CLOCK.containsMatchIn(it) }.joinToString(" · ") else null,
                    hourTicks = !nav.active,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun NextTurn(nav: NavState, onOpen: () -> Unit) {
    val icon = nav.icon
    val bitmap = remember(icon) { icon?.asImageBitmap() }
    Row(modifier = Modifier.clickable(onClick = onOpen).heightIn(min = DashSize.Touch), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, colorFilter = ColorFilter.tint(DashColors.TextPrimary), modifier = Modifier.size(60.dp))
        } else {
            Icon(Icons.Filled.Navigation, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            val (value, unit) = nav.distanceParts
            Row(verticalAlignment = Alignment.Bottom) {
                Num(value.ifBlank { "–" }, 44f, modifier = Modifier.weight(1f, fill = false))
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Label(unit, 20f, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Label(nav.instruction, 16f, DashColors.TextPrimary, maxLines = 2)
        }
    }
}

@Composable
private fun Today(clock: Long, timeFmt: java.text.SimpleDateFormat, onMaps: () -> Unit) {
    val date = rememberDateFormat("EEEEdMMMM", best = true)
    val tap = rememberTapFeedback()
    Column {
        Num(timeFmt.format(Date(clock)), 44f)
        Label(date.format(Date(clock)), 16f)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.heightIn(min = DashSize.Touch).clip(CircleShape).clickable(role = Role.Button) { tap(); onMaps() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Navigation, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Label(stringResource(R.string.info_map_where_to), 17f, DashColors.Accent)
        }
    }
}

/**
 * The line itself, from [start] (the car, or "now") to [end], with [stops]
 * as rings and their labels under them. Labels too close to the one before
 * give way, the arrival never does. Navigating, the line is the route in the
 * accent colour; otherwise a quiet track with the hours marked.
 */
@Composable
private fun JourneyLine(
    start: Long, end: Long, stops: List<Stop>, endStop: Stop?, header: String?, hourTicks: Boolean, modifier: Modifier
) {
    val accent = DashColors.Accent
    val ink = DashColors.TextPrimary
    val track = DashColors.CardHi
    val good = DashColors.Good
    val bg = DashColors.Background
    val faint = DashColors.Muted
    // The hours as the dashboard's clock writes them: "15:00", or "3 PM" on a 12-hour clock.
    val clock24 = LocalUnits.current.clock24
    val zone = rememberZone()
    val hourFmt = remember(clock24, zone) { java.text.SimpleDateFormat(if (clock24) "H:mm" else "h a", Locale.getDefault()).inZone(zone) }
    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        val lineY = maxHeight * if (header != null) 0.40f else 0.34f
        val inset = 16.dp
        val labelW = 130.dp
        fun xOf(ms: Long): Dp = inset + (w - inset * 2) * ((ms - start).toFloat() / (end - start).coerceAtLeast(1L)).coerceIn(0f, 1f)
        // Stops that leave room for their labels, the arrival's first.
        val shown = mutableListOf<Stop>()
        var lastX = (-1000).dp
        stops.forEach { s ->
            val x = xOf(s.atMs)
            val clearOfEnd = endStop == null || xOf(endStop.atMs) - x >= labelW * 0.9f
            if (x - lastX >= labelW * 0.9f && clearOfEnd) { shown += s; lastX = x }
        }
        val ticks = if (hourTicks) {
            val cal = Calendar.getInstance().apply { timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.HOUR_OF_DAY, 1) }
            generateSequence(cal.timeInMillis) { it + 3_600_000L }.takeWhile { it < end }.toList()
        } else emptyList()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = lineY.toPx()
            val x0 = inset.toPx()
            val x1 = size.width - inset.toPx()
            val thick = 8.dp.toPx()
            if (endStop != null) {
                drawLine(accent.copy(alpha = 0.25f), Offset(x0, y), Offset(x1, y), thick * 2.2f, StrokeCap.Round)
                drawLine(accent, Offset(x0, y), Offset(x1, y), thick, StrokeCap.Round)
            } else {
                drawLine(track, Offset(x0, y), Offset(x1, y), thick, StrokeCap.Round)
                ticks.forEach { t ->
                    val x = xOf(t).toPx()
                    drawLine(faint, Offset(x, y - thick * 0.9f), Offset(x, y + thick * 0.9f), 1.5f)
                }
            }
            shown.forEach { s ->
                val x = xOf(s.atMs).toPx()
                drawCircle(bg, 7.dp.toPx(), Offset(x, y))
                drawCircle(if (s.lead) accent else ink, 7.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
            }
            if (endStop != null) drawCircle(good, 10.dp.toPx(), Offset(x1, y))
            // Where the car is: a chevron heading along the line, or the "now" mark.
            val r = 13.dp.toPx()
            drawCircle(if (endStop != null) accent.copy(alpha = 0.22f) else ink.copy(alpha = 0.12f), r * 1.4f, Offset(x0, y))
            val chevron = Path().apply {
                moveTo(x0 + r * 0.75f, y); lineTo(x0 - r * 0.55f, y - r * 0.6f); lineTo(x0 - r * 0.2f, y); lineTo(x0 - r * 0.55f, y + r * 0.6f); close()
            }
            drawPath(chevron, ink)
        }
        if (header != null && header.isNotBlank()) {
            Label(header, 15f, DashColors.Muted, Modifier.offset(x = inset + 30.dp, y = lineY - 34.dp))
        }
        if (hourTicks) {
            ticks.forEach { t ->
                val x = xOf(t)
                // Clear of the car's mark at the start and of any stop's label.
                if (x - inset > 44.dp && shown.none { kotlin.math.abs((xOf(it.atMs) - x).value) < 34f }) {
                    Label(hourFmt.format(Date(t)), 12f, DashColors.Muted, Modifier.offset(x = x - 30.dp, y = lineY - 30.dp).width(60.dp), align = TextAlign.Center)
                }
            }
        }
        val labelTop = lineY + 16.dp
        shown.forEach { s -> StopLabel(s, xOf(s.atMs), labelTop, labelW, w, if (s.lead) DashColors.Accent else DashColors.TextPrimary) }
        endStop?.let { StopLabel(it, xOf(it.atMs), labelTop, labelW, w, DashColors.Good, alignEnd = true) }
    }
}

@Composable
private fun StopLabel(s: Stop, x: Dp, top: Dp, width: Dp, maxW: Dp, titleColor: Color, alignEnd: Boolean = false) {
    // A line narrower than one label keeps its labels at the start rather than failing to place them.
    val room = (maxW - width).coerceAtLeast(0.dp)
    val left = if (alignEnd) room else (x - width / 2).coerceIn(0.dp, room)
    Column(
        modifier = Modifier.offset(x = left, y = top).width(width),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.CenterHorizontally
    ) {
        Num(s.title, 20f, titleColor)
        Label(s.caption, 13f, align = if (alignEnd) TextAlign.End else TextAlign.Center)
    }
}

/**
 * Today's coming appointments: the phone's when linked, as on the Agenda tile
 * ([phoneOrUnit]), else the unit's calendar when it may be read, re-read every five minutes.
 */
@Composable
private fun rememberAgenda(context: Context, enabled: Boolean): List<AgendaEvent> {
    var events by remember { mutableStateOf<List<AgendaEvent>>(emptyList()) }
    val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(enabled, allowed) {
        if (!enabled || !allowed) return@LaunchedEffect
        while (true) {
            events = withContext(Dispatchers.IO) { runCatching { loadAgenda(context, hours = DAY_LINE_HOURS) }.getOrDefault(emptyList()) }
            delay(5 * 60_000L)
        }
    }
    val phone = PhoneLink.lists.collectAsState().value.agenda
    return phoneOrUnit(phone, events)
}

/** The navigation app that is guiding (else the one the driver uses, [navAppToOpen]), brought to the front. */
private fun openNavApp(context: Context, packageName: String?) {
    // The dashboard's own guidance runs on the map under this page: the tap offers to stop it.
    if (packageName == context.packageName) return InAppNav.askToStop()
    val pkg = packageName?.takeIf { it.isNotBlank() } ?: navAppToOpen(context)
    context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.launchSafely(it) }
}

// --- Reading the route ---------------------------------------------------------------------------

/** A clock time in an ETA line: "14:58", "9h05", "2:58 PM". */
internal val ARRIVAL_CLOCK = Regex("""\b(\d{1,2})[:h.](\d{2})\s*([AaPp]\.?\s?[Mm]\.?)?""")
private val HOURS = Regex("""(\d+)\s*(?:h|hr|hrs|hour|hours|std|u|godz)\b""", RegexOption.IGNORE_CASE)
private val MINUTES = Regex("""(\d+)\s*(?:min|mins|minute|minutes|minuten|minuti|minutos|minutter)\b""", RegexOption.IGNORE_CASE)
/** A duration written short, "1h05" (Waze, and Maps in French): hours, then two digits of minutes. */
private val COMPACT_HOURS = Regex("""\b(\d{1,2})\s?h\s?(\d{2})\b""", RegexOption.IGNORE_CASE)

/** An ETA line's parts, as [NavState.etaParts] splits them: "26 min · 18 km · 14:58" gives three. */
private fun etaSegments(eta: String): List<String> = eta.split('·', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The clock time [part] is, when it is one: a time that ends the part, with
 * no other figure in it ("14:58", "Arrivée : 13:51", "ETA 1:51 PM"). "1h05
 * min" is a duration, and was read as five past one in the morning: the
 * line then ran to tomorrow, with the rain and sunset lost along it.
 */
private fun clockOf(part: String): MatchResult? {
    val clock = ARRIVAL_CLOCK.find(part)?.takeIf { it.range.last == part.lastIndex } ?: return null
    return clock.takeIf { part.removeRange(it.range).none(Char::isDigit) }
}

/** Minutes left in an ETA line ("26 min", "1 h 5 min", "1h05"); null when it gives none. */
internal fun etaMinutes(eta: String): Int? {
    val duration = etaSegments(eta).filter { clockOf(it) == null }.joinToString(" ")
    COMPACT_HOURS.find(duration)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    val h = HOURS.find(duration)?.groupValues?.get(1)?.toIntOrNull()
    val m = MINUTES.find(duration)?.groupValues?.get(1)?.toIntOrNull()
    if (h == null && m == null) return null
    return (h ?: 0) * 60 + (m ?: 0)
}

/**
 * A clock time this far behind the wall clock is a notification not yet
 * refreshed (Maps rounds its arrival and posts it late), not tomorrow's.
 */
private const val STALE_CLOCK_MS = 12 * 3_600_000L

/**
 * When the car arrives, from an ETA line at [nowMs]: its clock time (today,
 * or tomorrow once past midnight), else now plus the minutes left; null when
 * neither. A clock time a little past already means the car is about there:
 * the minutes left if the line gives them, else now, never a day on.
 */
internal fun arrivalMs(eta: String, nowMs: Long): Long? {
    val byMinutes = etaMinutes(eta)?.let { nowMs + it * 60_000L }
    val m = etaSegments(eta).firstNotNullOfOrNull(::clockOf) ?: return byMinutes
    var hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].toInt()
    val ampm = m.groupValues[3].lowercase(Locale.ROOT)
    if (ampm.startsWith("p") && hour < 12) hour += 12
    if (ampm.startsWith("a") && hour == 12) hour = 0
    if (hour !in 0..23 || minute !in 0..59) return byMinutes
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val at = cal.timeInMillis
    return when {
        at >= nowMs - 60_000L -> at
        nowMs - at <= STALE_CLOCK_MS -> maxOf(byMinutes ?: nowMs, nowMs)
        else -> cal.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    }
}

/** Sunset (the sun's centre 0.833° under the horizon) between [fromMs] and [toMs] at [lat], [lon], to five minutes; null if none. */
internal fun nextSunset(fromMs: Long, toMs: Long, lat: Double, lon: Double): Long? {
    val step = 5 * 60_000L
    var t = fromMs
    var up = sunAltitudeDeg(t, lat, lon) > -0.833
    while (t < toMs) {
        val next = t + step
        val nowUp = sunAltitudeDeg(next, lat, lon) > -0.833
        if (up && !nowUp) return next
        up = nowUp
        t = next
    }
    return null
}
