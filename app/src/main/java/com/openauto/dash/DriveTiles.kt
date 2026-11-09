@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.openauto.dash.link.FuelFills
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberSpeedSourceKind
import com.openauto.dash.vehicle.rememberVehicle
import com.openauto.dash.vehicle.speedSourceWord
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/*
 * Driving widgets: digital speed HUD, compass, trip computer, G-force meter
 * and the parking-spot finder. All fed by LiveFeeds (GPS, accelerometer).
 */

/** Small uppercase tile title with an optional trailing element ([TitleWithTrailing]: below it when both don't fit). */
@Composable
internal fun TileHeader(title: String, trailing: @Composable () -> Unit = {}) {
    TitleWithTrailing(
        Modifier.fillMaxWidth(),
        title = {
            Text(
                // Upper case here, so a title passed in sentence case (a widget's name) matches the others.
                title.uppercase(Locale.getDefault()),
                color = DashColors.AccentInk,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelMedium
            )
        },
        trailing = trailing
    )
}

/**
 * A tile's title and what goes at its end (a status chip, a source, a
 * button) on one line, the end piece at the right edge, when the two fit side
 * by side; else the end piece drops to its own line under the title. Squeezed
 * into one row, the title came out cut to a few letters or the chip broken
 * over three lines. With [dropWhenTight] (a tile with no line to spare) the
 * end piece is left out instead.
 */
@Composable
internal fun TitleWithTrailing(
    modifier: Modifier,
    title: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    dropWhenTight: Boolean = false
) {
    Layout(contents = listOf(title, trailing), modifier = modifier) { (titles, ends), c ->
        val gap = 8.dp.roundToPx()
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val endPlaceables = ends.map { it.measure(loose) }
        val endW = endPlaceables.maxOfOrNull { it.width } ?: 0
        val endH = endPlaceables.maxOfOrNull { it.height } ?: 0
        val titleWanted = titles.maxOfOrNull { it.maxIntrinsicWidth(c.maxHeight) } ?: 0
        val oneRow = endW == 0 || titleWanted + gap + endW <= c.maxWidth
        val dropped = !oneRow && dropWhenTight
        val titlePlaceables = titles.map { it.measure(loose.copy(maxWidth = if (oneRow && endW > 0) c.maxWidth - endW - gap else c.maxWidth)) }
        val titleH = titlePlaceables.maxOfOrNull { it.height } ?: 0
        val width = if (c.hasBoundedWidth) c.maxWidth else (titlePlaceables.maxOfOrNull { it.width } ?: 0) + gap + endW
        val height = when {
            oneRow -> maxOf(titleH, endH)
            dropped -> titleH
            else -> titleH + 4.dp.roundToPx() + endH
        }
        layout(width, height.coerceIn(c.minHeight, c.maxHeight)) {
            titlePlaceables.forEach { it.place(0, if (oneRow) (height - it.height) / 2 else 0) }
            if (!dropped) endPlaceables.forEach {
                if (oneRow) it.place(width - it.width, (height - it.height) / 2)
                else it.place(width - it.width, titleH + 4.dp.roundToPx())
            }
        }
    }
}

/** Keeps the GPS feed alive while this composable is on screen. */
@Composable
internal fun UseLocationFeed() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val acquired = LocationFeed.acquire(context)
        onDispose { if (acquired) LocationFeed.release() }
    }
}

/** Big glowing numerals shared by the speed HUD and clock. */
@Composable
internal fun HeroNumber(text: String, size: Int, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    val lit = glow > 0f && !dimmed
    Text(
        text = text,
        modifier = modifier,
        color = if (lit) Color.Unspecified else if (dimmed) DashColors.Muted else DashColors.TextPrimary,
        fontSize = size.sp,
        lineHeight = size.sp,
        fontFamily = DashColors.heroFamily(),
        fontWeight = DashColors.HeroWeight,
        letterSpacing = (-0.06).em,
        maxLines = 1,
        style = TextStyle(
            brush = if (lit) Brush.verticalGradient(listOf(DashColors.Bright, lerp(DashColors.Bright, accent, 0.45f))) else null,
            shadow = if (lit) Shadow(accent.copy(alpha = 0.8f * glow), blurRadius = size * 0.6f) else null
        )
    )
}

// --- Speed HUD ------------------------------------------------------------------

/** Just the speed, as large as the tile allows, with where it comes from (the adapter, the car, the GPS) as the bus resolved it. */
@Composable
internal fun SpeedHudCard(modifier: Modifier = Modifier) {
    val kmh = rememberSpeedKmh()
    val source = speedSourceWord(rememberSpeedSourceKind()) ?: stringResource(R.string.info_speed_no_signal)
    val over = speedOver(kmh)
    val u = LocalUnits.current
    val speed = kmh?.let { u.speed(it) }
    // The source (OBD, CAR or GPS) is said for two seconds when it changes, not worn
    // as a badge; "No signal" stays while there is no speed to show.
    var saidSource by remember { mutableStateOf<String?>(null) }
    var lastSource by remember { mutableStateOf(source) }
    LaunchedEffect(source) {
        if (source == lastSource) return@LaunchedEffect
        lastSource = source
        saidSource = source
        delay(SOURCE_CAPTION_MS)
        saidSource = null
    }
    val caption = saidSource ?: source.takeIf { speed == null }

    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            val numSize = (min(maxWidth.value * 0.42f, maxHeight.value * 0.62f)).coerceIn(40f, 150f).roundToInt()
            // Two rows high or less the number is everything: no header, as on the Gear card.
            val roomy = maxHeight >= 150.dp
            Column(modifier = Modifier.fillMaxSize()) {
                if (roomy) TileHeader(stringResource(R.string.info_speed_title))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (over) {
                            Text(
                                text = speed.toString(),
                                color = DashColors.Critical,
                                fontSize = numSize.sp,
                                lineHeight = numSize.sp,
                                fontFamily = DashColors.heroFamily(),
                                fontWeight = DashColors.HeroWeight,
                                letterSpacing = (-0.06).em,
                                maxLines = 1,
                                // A blurred shadow only with effects at full (softTextShadow): it is costly on the head unit.
                                style = TextStyle(shadow = softTextShadow(DashColors.Critical.copy(alpha = 0.7f * DashColors.Glow), numSize * 0.5f))
                            )
                        } else {
                            HeroNumber(text = speed?.toString() ?: "--", size = numSize, dimmed = speed == null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            u.speedUnit.uppercase(),
                            color = DashColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.25.em,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = (numSize * 0.16f).dp)
                        )
                    }
                }
                if (caption != null) {
                    Text(
                        caption,
                        color = if (speed != null) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

/** How long the speed tile says where its reading comes from after that changes. */
private const val SOURCE_CAPTION_MS = 2_000L

// --- Compass --------------------------------------------------------------------

/** Heading dial from GPS bearing, with altitude and GPS speed underneath. */
@Composable
internal fun CompassCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    // Both stay States, read by the dial's draw, the header and the footer in
    // their own scopes: a GPS fix a second must not recompose the whole card.
    val locationState = LocationFeed.location.collectAsState()
    val headingState = LocationFeed.headingDeg.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val muted = DashColors.TextSecondary
    val glow = DashColors.Glow
    val labelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = muted)
    val northStyle = labelStyle.copy(color = DashColors.Critical)
    // Dial letters in the UI language (e.g. O for Ouest / Osten); North is always first.
    val dialLabels = listOf(
        stringResource(R.string.info_dir_n) to 0, stringResource(R.string.info_dir_e) to 90,
        stringResource(R.string.info_dir_s) to 180, stringResource(R.string.info_dir_w) to 270
    )

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_compass_title)) {
                val heading = headingState.value
                Text(
                    heading?.let { "${it.roundToInt()}° ${stringResource(cardinalRes(it))}" }
                        ?: stringResource(R.string.info_compass_no_heading),
                    color = if (heading != null) DashColors.TextPrimary else DashColors.Muted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            // Dial ink follows the theme: white-on-dark was invisible on light palettes.
            val ink = DashColors.TextPrimary
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The dial's geometry (72 ticks as three paths, the four measured
                // letters, the marker's brushes) is built once per size; each
                // fix only turns it, reading the heading in the draw.
                Spacer(modifier = Modifier.fillMaxSize().drawWithCache {
                    val r = size.minDimension / 2f - 6.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val majorTicks = Path()
                    val midTicks = Path()
                    val minorTicks = Path()
                    for (i in 0 until 72) {
                        val major = i % 18 == 0
                        val mid = i % 6 == 0
                        val a = Math.toRadians((i * 5 - 90).toDouble())
                        val inner = r - if (major) 14.dp.toPx() else if (mid) 9.dp.toPx() else 5.dp.toPx()
                        val path = if (major) majorTicks else if (mid) midTicks else minorTicks
                        path.moveTo(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner)
                        path.lineTo(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r)
                    }
                    val majorStroke = Stroke(width = 3f, cap = StrokeCap.Round)
                    val minorStroke = Stroke(width = 1.5f, cap = StrokeCap.Round)
                    val lr = r - 26.dp.toPx()
                    val letters = dialLabels.mapIndexed { i, (l, deg) ->
                        val a = Math.toRadians((deg - 90).toDouble())
                        val layout = textMeasurer.measure(l, if (i == 0) northStyle else labelStyle)
                        layout to Offset(
                            c.x + cos(a).toFloat() * lr - layout.size.width / 2f,
                            c.y + sin(a).toFloat() * lr - layout.size.height / 2f
                        )
                    }
                    // Fixed lubber line + glowing heading marker at the top.
                    val tip = Offset(c.x, c.y - r)
                    val tipRadius = 16.dp.toPx()
                    val tipGlow = if (glow > 0f) Brush.radialGradient(listOf(accent.copy(alpha = 0.6f * glow), Color.Transparent), center = tip, radius = tipRadius) else null
                    val lubber = Brush.verticalGradient(listOf(accent, accent2), startY = tip.y, endY = c.y)
                    val dotRadius = 4.dp.toPx()
                    onDrawBehind {
                        // Dial rotates so the current heading sits at the top.
                        rotate(degrees = -(headingState.value ?: 0f), pivot = c) {
                            drawCircle(color = ink.copy(alpha = 0.10f), radius = r, center = c)
                            drawPath(majorTicks, color = accent, style = majorStroke)
                            drawPath(midTicks, color = ink.copy(alpha = 0.55f), style = minorStroke)
                            drawPath(minorTicks, color = ink.copy(alpha = 0.25f), style = minorStroke)
                            letters.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                        }
                        if (tipGlow != null) drawCircle(brush = tipGlow, radius = tipRadius, center = tip)
                        drawLine(brush = lubber, start = tip, end = Offset(c.x, c.y - r * 0.55f), strokeWidth = 4f, cap = StrokeCap.Round)
                        drawCircle(color = Color.White, radius = dotRadius, center = tip)
                    }
                })
            }
            CompassFooter(locationState)
        }
    }
}

/** Altitude, GPS speed and accuracy under the dial; its own scope, recomposed per fix instead of the card. */
@Composable
private fun CompassFooter(locationState: State<Location?>) {
    val location = locationState.value
    // From a fresh fix only: in a tunnel the footer reads "--", not the speed the car went in with.
    val speedKmh = rememberVehicle(K.GPS_SPEED)?.roundToInt()
    val u = LocalUnits.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // In the driver's units, as the compass face shows them (feet for an imperial driver).
        StatBlock(stringResource(R.string.info_compass_altitude), location?.takeIf { it.hasAltitude() }?.let { u.heightText(it.altitude) } ?: "--")
        StatBlock(stringResource(R.string.info_compass_gps_speed), speedKmh?.let { u.speedText(it) } ?: "--")
        StatBlock(stringResource(R.string.info_compass_accuracy), location?.let { "±${u.heightText(it.accuracy.toDouble())}" } ?: "--")
    }
}

/** Eight-point compass direction for a bearing, as a localized abbreviation. */
@StringRes
internal fun cardinalRes(deg: Float): Int {
    val dirs = listOf(
        R.string.info_dir_n, R.string.info_dir_ne, R.string.info_dir_e, R.string.info_dir_se,
        R.string.info_dir_s, R.string.info_dir_sw, R.string.info_dir_w, R.string.info_dir_nw
    )
    return dirs[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
}

@Composable
internal fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = DashColors.TextPrimary) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, color = DashColors.Muted, letterSpacing = 1.sp, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// --- Trip computer --------------------------------------------------------------

/**
 * This drive: distance, time, average and top speed since the last reset,
 * with the fuel it took and what it cost. On a tile big enough the distance
 * since the last fill-up comes beside it, counted on by itself ([SinceFill]).
 * Reset starts this drive again, as it always did; nothing else needs a tap.
 */
@Composable
internal fun TripCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    val trip by LocationFeed.trip.collectAsState()
    val u = LocalUnits.current
    val rate = rememberFuelRate()
    val since = remember(trip.startedAt, u) { formatClock(trip.startedAt, u) }
    val a = TripMath.figures(trip.distanceM / 1000.0, trip.movingMs, rate.use, rate.price)

    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            // Both trips once there is room for two blocks of rows: one above the other, or side by side.
            val stacked = maxHeight >= 300.dp
            val sideBySide = !stacked && maxWidth >= 600.dp
            Column(modifier = Modifier.fillMaxSize()) {
                TileHeader(stringResource(R.string.info_trip_title)) {
                    TextButton(onClick = { LocationFeed.resetTrip() }, modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.info_reset), color = DashColors.AccentInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (stacked || sideBySide) {
                    val b = rememberSinceFill(trip)
                    val drive: @Composable (Modifier) -> Unit = { m ->
                        TripSection(stringResource(R.string.info_trip_this_drive), stringResource(R.string.info_trip_since, since), a, rate.currency, m) {
                            TripElapsedRow(stringResource(R.string.info_trip_time), trip)
                        }
                    }
                    val fill: @Composable (Modifier) -> Unit = { m -> SinceFillSection(b, rate, m) }
                    if (stacked) {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                            drive(Modifier.weight(1f).fillMaxWidth())
                            fill(Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Lg)) {
                            drive(Modifier.weight(1f).fillMaxHeight())
                            fill(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            TripDistance(u.distance(a.km), u.distanceUnit, 48)
                            Text(stringResource(R.string.info_trip_since, since), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                            // What it took, under the distance: the rows on the right stay as they were.
                            Text(
                                listOfNotNull(TripMath.litersText(a.liters), a.cost?.let { money(it, rate.currency) }).joinToString(", "),
                                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TripElapsedRow(stringResource(R.string.info_trip_time), trip)
                            TripRow(stringResource(R.string.info_trip_moving), formatDuration(trip.movingMs))
                            TripRow(stringResource(R.string.info_trip_average), u.speedText(trip.avgSpeedKmh))
                            TripRow(stringResource(R.string.info_trip_top), u.speedText(trip.maxSpeedKmh))
                        }
                    }
                }
            }
        }
    }
}

/** The consumption and price a trip's fuel is counted at, and the money's currency. */
internal class FuelRate(val use: Double, val price: Double?, val currency: String)

/** The car's measured consumption and last price paid ([FuelLog]) where known, else its profile's. */
@Composable
internal fun rememberFuelRate(): FuelRate {
    val fills by FuelLog.fills.collectAsState()
    val car by CarProfileStore.profile.collectAsState()
    return remember(fills, car) {
        FuelRate(TripMath.use(FuelFills.litersPer100(fills), car), TripMath.price(fills.firstNotNullOfOrNull { it.pricePerL }, car), car.currency)
    }
}

/** The count since the last fill-up; the demo's own during the demo. */
@Composable
internal fun rememberSinceFill(trip: TripState): SinceFillState {
    val real by SinceFill.state.collectAsState()
    val demo by DemoMode.active.collectAsState()
    return if (demo) DemoMode.sinceFill(trip) else real
}

/** The headline distance, already in the driver's units. */
@Composable
private fun TripDistance(distance: Double, unit: String, size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        HeroNumber(text = TripMath.figure(distance), size = size)
        Spacer(Modifier.width(6.dp))
        Text(unit.uppercase(), color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.em,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = (size / 6).dp))
    }
}

/** One trip of the big tile: its name and start, the distance, then [time], the average, the fuel and its cost. */
@Composable
private fun TripSection(title: String, note: String, f: TripFigures, currency: String, modifier: Modifier, time: @Composable () -> Unit) {
    val u = LocalUnits.current
    Column(modifier = modifier) {
        TripSectionTitle(title, note)
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { TripDistance(u.distance(f.km), u.distanceUnit, 40) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                time()
                TripRow(stringResource(R.string.info_trip_average), u.speedText(f.avgKmh))
                TripRow(stringResource(R.string.info_trip_fuel), TripMath.litersText(f.liters))
                f.cost?.let { TripRow(stringResource(R.string.info_trip_cost), money(it, currency)) }
            }
        }
    }
}

@Composable
private fun TripSectionTitle(title: String, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(note, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Since the last fill-up: the time is the time spent moving, the hours parked in between don't count. */
@Composable
private fun SinceFillSection(b: SinceFillState, rate: FuelRate, modifier: Modifier) {
    val fillAt = b.fillAt
    if (fillAt == null) {
        Column(modifier = modifier) {
            TripSectionTitle(stringResource(R.string.info_trip_since_fill), "")
            Text(stringResource(R.string.info_trip_no_fill), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val f = TripMath.figures(b.km, b.movingMs, rate.use, rate.price)
    val date = remember(fillAt) { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(fillAt)) }
    TripSection(stringResource(R.string.info_trip_since_fill), stringResource(R.string.info_trip_since, date), f, rate.currency, modifier) {
        TripRow(stringResource(R.string.info_trip_moving), formatDuration(f.movingMs))
    }
}

/** The trip's running time, ticking each second on its own so it moves while parked without redrawing the card. */
@Composable
private fun TripElapsedRow(label: String, trip: TripState) {
    rememberWallClock(1_000L).longValue
    TripRow(label, formatDuration(trip.elapsedMs))
}

@Composable
private fun TripRow(label: String, value: String) {
    // The value keeps one line on a narrow tile ("48 km/h", not "48 km/" over "h"); the label gives way first.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
}

/** "1h 05m" / "12m 30s", in the UI language's abbreviations. */
@Composable
internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) stringResource(R.string.info_duration_hm, h, m)
    else stringResource(R.string.info_duration_ms, m, s % 60)
}

internal fun formatClock(epochMs: Long, units: UnitSystem = Units.current.value): String =
    units.time(java.util.Date(epochMs))

// --- G-force --------------------------------------------------------------------

/** The g-meter's rings, in g; built once, not on each of the ~15 draws a second. */
private val G_RINGS = floatArrayOf(0.4f, 0.8f, 1.2f)
private val RingStroke = Stroke(width = 1.5f)

/** Friction-circle style meter: a dot for the current lateral / longitudinal g. */
@Composable
internal fun GForceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        GForceFeed.acquire(context)
        onDispose { GForceFeed.release() }
    }
    // Read only by the dial's draw and the readouts' own scope, so ~15 samples a
    // second don't recompose the whole card.
    val feed = GForceFeed.g.collectAsState()
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val warning = DashColors.Warning
    val glow = DashColors.Glow

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_gforce_title)) {
                TextButton(onClick = { GForceFeed.resetPeaks() }, modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.info_gforce_reset_peaks), color = DashColors.AccentInk, style = MaterialTheme.typography.labelMedium)
                }
            }
            val ink = DashColors.TextPrimary
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val g = feed.value
                    val r = size.minDimension / 2f - 4.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val scale = r / 1.2f   // 1.2 g at the rim
                    for (ring in G_RINGS) {
                        drawCircle(
                            color = if (ring >= 1.2f) warning.copy(alpha = 0.5f) else ink.copy(alpha = 0.2f),
                            radius = ring * scale, center = c, style = RingStroke
                        )
                    }
                    drawLine(ink.copy(alpha = 0.18f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                    drawLine(ink.copy(alpha = 0.18f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                    val px = c.x + g.lateral.coerceIn(-1.2f, 1.2f) * scale
                    val py = c.y - g.longitudinal.coerceIn(-1.2f, 1.2f) * scale
                    val p = Offset(px, py)
                    val mag = min(1f, (abs(g.lateral) + abs(g.longitudinal)) / 1.2f)
                    val dotColor = lerp(accent, warning, mag)
                    drawLine(brush = Brush.linearGradient(listOf(accent2.copy(alpha = 0.2f), dotColor), start = c, end = p), start = c, end = p, strokeWidth = 3f, cap = StrokeCap.Round)
                    if (glow > 0f) {
                        drawCircle(brush = Brush.radialGradient(listOf(dotColor.copy(alpha = 0.55f * glow), Color.Transparent), center = p, radius = 18.dp.toPx()), radius = 18.dp.toPx(), center = p)
                    }
                    drawCircle(color = dotColor, radius = 7.dp.toPx(), center = p)
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = p)
                }
                Spacer(Modifier.width(10.dp))
                BoxWithConstraints(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
                    // Two-row tiles only have room for the live values; peaks need a taller tile.
                    val showPeaks = maxHeight >= 150.dp
                    val g = feed.value
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        StatBlock(stringResource(R.string.info_gforce_lateral), String.format(Locale.getDefault(), "%+.2f g", g.lateral), Modifier.fillMaxWidth())
                        StatBlock(stringResource(R.string.info_gforce_accel_brake), String.format(Locale.getDefault(), "%+.2f g", g.longitudinal), Modifier.fillMaxWidth())
                        if (showPeaks) {
                            StatBlock(
                                stringResource(R.string.info_gforce_peaks),
                                String.format(Locale.getDefault(), "%.2f / %.2f g", g.peakLateral, g.peakLongitudinal),
                                Modifier.fillMaxWidth(), valueColor = DashColors.Warning
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Parking --------------------------------------------------------------------

/** Save where the car is parked; later shows distance and direction back to it. */
@Composable
internal fun ParkingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    UseLocationFeed()
    LaunchedEffect(Unit) { ParkingStore.load(context) }
    val spot by ParkingStore.spot.collectAsState()
    // The position is read only once a spot is saved (the distance to it): with
    // none, whether there is a fix is all the card shows, and it doesn't follow each fix.
    val location by LocationFeed.location.collectAsState()
    val hasFix by LocationFeed.hasFix.collectAsState()
    // "Parked 5 min ago" moves by the minute, and only once a spot is saved.
    if (spot != null) rememberWallClock(60_000L).longValue

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_parking_title)) {
                if (spot != null) {
                    TextButton(onClick = { ParkingStore.clear(context) }, modifier = Modifier.height(DashSize.Touch), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.info_clear), color = DashColors.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val s = spot
            if (s == null) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.LocalParking, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(if (hasFix) R.string.info_parking_prompt else R.string.info_waiting_gps),
                        color = DashColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { LocationFeed.location.value?.let { ParkingStore.save(context, it) } },
                        enabled = hasFix,
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                        shape = DashShape.Medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text(stringResource(R.string.info_parking_save)) }
                }
            } else {
                val here = location
                val (dist, bearing) = remember(here, s) {
                    if (here == null) null to null else {
                        val results = FloatArray(2)
                        Location.distanceBetween(here.latitude, here.longitude, s.lat, s.lng, results)
                        results[0] to results[1]
                    }
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DashColors.AccentBrush)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Arrow points from the car's heading towards the spot.
                        val heading = LocationFeed.headingDeg.collectAsState().value ?: 0f
                        val relative = bearing?.let { (it - heading + 360f) % 360f }
                        val arrowDescription = when {
                            relative == null -> null
                            relative <= 180f -> stringResource(R.string.info_parking_dir_right, relative.roundToInt())
                            else -> stringResource(R.string.info_parking_dir_left, (360f - relative).roundToInt())
                        }
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = arrowDescription,
                            tint = DashColors.OnAccent,
                            modifier = Modifier.size(34.dp).rotate(((bearing ?: 0f) - heading + 360f) % 360f)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            dist?.let { formatDistance(it) } ?: stringResource(R.string.info_parking_distance_unknown),
                            color = DashColors.TextPrimary, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        val ago = formatAgo(s.savedAt)
                        Text(
                            if (bearing != null) stringResource(R.string.info_parking_parked_dir, ago, stringResource(cardinalRes(bearing)))
                            else stringResource(R.string.info_parking_parked, ago),
                            color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Button(
                        onClick = { walkTo(context, s) },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                        shape = DashShape.Medium,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text(stringResource(R.string.info_parking_walk)) }
                }
            }
        }
    }
}

internal fun walkTo(context: Context, spot: ParkingSpot) {
    val uri = Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", spot.lat, spot.lng))
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Coordinates stay Locale.US (machine-read); only the pin label is localized.
        val label = Uri.encode(context.getString(R.string.info_parking_map_label))
        val geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", spot.lat, spot.lng, spot.lat, spot.lng, label))
        context.launchSafely(Intent(Intent.ACTION_VIEW, geo))
    }
}

internal fun formatDistance(m: Float, units: UnitSystem = Units.current.value): String = when {
    units.imperial -> units.shortDistance(m.toDouble())
    m < 1000f -> "${m.roundToInt()} m"
    else -> String.format(Locale.getDefault(), "%.1f km", m / 1000f)
}

@Composable
internal fun formatAgo(epochMs: Long): String {
    val mins = ((System.currentTimeMillis() - epochMs) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> stringResource(R.string.info_ago_just_now)
        mins < 60 -> stringResource(R.string.info_ago_minutes, mins)
        mins < 24 * 60 -> stringResource(R.string.info_ago_hours, mins / 60, mins % 60)
        else -> stringResource(R.string.info_ago_days, mins / (24 * 60))
    }
}

