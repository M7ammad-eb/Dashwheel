package com.openauto.dash.companion

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.openauto.dash.link.DriveSummary
import com.openauto.dash.link.FuelFill
import com.openauto.dash.link.FuelFills
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Past drives listed under the latest one; older ones stay in [DriveJournal]. */
internal const val PAST_DRIVES_SHOWN = 30

private const val DAY_MS = 24 * 3600_000L

/** Midnight of the day [at] falls in, local time. */
private fun dayStart(at: Long): Long = Calendar.getInstance().apply {
    timeInMillis = at
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The last seven days at a glance: distance, drives, the eco score weighted
 * by distance, fuel spent, and one bar per day (today on the right).
 */
@Composable
internal fun WeekCard(drives: List<DriveSummary>, now: Long = rememberWallClock(DateUtils.HOUR_IN_MILLIS).longValue) {
    val today = dayStart(now)
    val from = today - 6 * DAY_MS
    val week = drives.filter { it.startedAt >= from }
    val km = week.sumOf { it.distanceKm }
    val scored = week.filter { it.ecoScore != null && it.distanceKm > 0 }
    val eco = scored.sumOf { it.distanceKm }.takeIf { it > 0 }?.let { total -> (scored.sumOf { it.ecoScore!! * it.distanceKm } / total).roundToInt() }
    val cost = week.mapNotNull { it.fuelCost }.takeIf { it.isNotEmpty() }?.sum()
    val currency = week.firstNotNullOfOrNull { it.currency }.orEmpty()
    val perDay = (0 until 7).map { i -> val start = from + i * DAY_MS; week.filter { it.startedAt in start until start + DAY_MS }.sumOf { it.distanceKm } }
    val dayLetter = SimpleDateFormat("EEEEE", Locale.getDefault())

    Panel {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.week_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(kmText(km), stringResource(R.string.drive_km))
                Stat(week.size.toString(), stringResource(R.string.week_drives))
                Stat(eco?.toString() ?: "--", stringResource(R.string.week_eco), eco?.let { ecoTint(it) })
                Stat(cost?.let { decimal(it, 0) + " " + currency } ?: "--", stringResource(R.string.week_fuel))
            }
            val max = perDay.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val blue = CompanionColors.Blue
            val teal = CompanionColors.Teal
            val empty = CompanionColors.Line
            Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = 18.dp)) {
                val slot = size.width / 7f
                val barW = slot * 0.46f
                perDay.forEachIndexed { i, v ->
                    val h = if (v <= 0) 4.dp.toPx() else (size.height * (v / max).toFloat()).coerceAtLeast(6.dp.toPx())
                    drawRoundRect(
                        color = when { v <= 0 -> empty; i == 6 -> teal; else -> blue },
                        topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                (0 until 7).forEach { i ->
                    Text(
                        dayLetter.format(Date(from + i * DAY_MS)).uppercase(),
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == 6) CompanionColors.Teal else CompanionColors.Muted
                    )
                }
            }
        }
    }
}

/**
 * A month of motoring, this one or an earlier one: distance, drives, time at
 * the wheel, the eco score, and what the fuel cost (the refuels' own prices
 * where known, else the drives' estimate) with the car's real consumption.
 * Shared as plain text, for an expense claim or the garage.
 */
@Composable
internal fun MonthCard(drives: List<DriveSummary>, fills: List<FuelFill>) {
    val context = LocalContext.current
    var back by rememberSaveable { mutableIntStateOf(0) }
    val (from, to) = monthBounds(back)
    val month = drives.filter { it.startedAt in from until to }
    val refuels = fills.filter { it.at in from until to }
    val km = month.sumOf { it.distanceKm }
    val movingMs = month.sumOf { it.movingMs }
    val scored = month.filter { it.ecoScore != null && it.distanceKm > 0 }
    val eco = scored.sumOf { it.distanceKm }.takeIf { it > 0 }?.let { t -> (scored.sumOf { it.ecoScore!! * it.distanceKm } / t).roundToInt() }
    val paid = refuels.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
    val estimated = paid == null
    val spent = paid ?: month.mapNotNull { it.fuelCost }.takeIf { it.isNotEmpty() }?.sum()
    val currency = (refuels.firstNotNullOfOrNull { it.currency } ?: month.firstNotNullOfOrNull { it.currency }).orEmpty()
    val per100 = FuelFills.litersPer100(fills)
    val title = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(Date(from)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val drivingTime = durationText(movingMs)
    val labels = ReportLabels(
        km = stringResource(R.string.drive_km), drives = stringResource(R.string.week_drives),
        time = stringResource(R.string.report_time), eco = stringResource(R.string.week_eco),
        fuel = stringResource(R.string.week_fuel), per100 = stringResource(R.string.report_per100)
    )
    val shareSubject = stringResource(R.string.report_share_subject, title)
    val estimatedNote = stringResource(R.string.report_estimated)
    var pricing by remember { mutableStateOf<FuelFill?>(null) }
    pricing?.let { f ->
        FuelPriceDialog(f, onDismiss = { pricing = null }) { price ->
            FuelJournal.setPrice(context, f.at, price)
            pricing = null
        }
    }

    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back++ }, enabled = back < 11) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.report_earlier))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { back-- }, enabled = back > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.report_later))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(kmText(km), labels.km)
                Stat(month.size.toString(), labels.drives)
                Stat(drivingTime, labels.time)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(eco?.toString() ?: "--", labels.eco, eco?.let { ecoTint(it) })
                Stat(spent?.let { decimal(it, 0) + " " + currency } ?: "--", labels.fuel)
                Stat(per100?.let { decimal(it, 1) } ?: "--", labels.per100)
            }
            if (estimated && spent != null) {
                Text(estimatedNote, style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(top = 10.dp))
            }
            fills.firstOrNull()?.let { f ->
                val price = f.pricePerL?.let { decimal(it, 3) + " " + f.currency.orEmpty() + "/L" + if (f.priceGuessed) "?" else "" }
                Text(
                    stringResource(R.string.report_last_fill, decimal(f.liters, 0), listOfNotNull(price, f.station, whenShort(f.at)).joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(top = 6.dp)
                )
                // Prices change by the day: a refuel away from a known pump asks what was paid.
                if (f.priceGuessed) TextButton(onClick = { pricing = f }) { Text(stringResource(R.string.fuel_set_price)) }
            }
            TextButton(
                onClick = {
                    val text = buildString {
                        appendLine(shareSubject)
                        appendLine("${labels.km}: ${kmText(km)}")
                        appendLine("${labels.drives}: ${month.size}")
                        appendLine("${labels.time}: $drivingTime")
                        eco?.let { appendLine("${labels.eco}: $it/100") }
                        spent?.let { appendLine("${labels.fuel}: ${decimal(it, 2)} $currency" + if (estimated) " ($estimatedNote)" else "") }
                        per100?.let { appendLine("${labels.per100}: ${decimal(it, 1)}") }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, shareSubject).putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, shareSubject)) }
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.report_share))
            }
        }
    }
}

/** What was paid a litre at [fill], typed; the guess to start from. */
@Composable
private fun FuelPriceDialog(fill: FuelFill, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(fill.pricePerL?.let { decimal(it, 3) }.orEmpty()) }
    // A comma or a point, whichever the keyboard gives.
    val price = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CompanionColors.SurfaceHigh,
        title = { Text(stringResource(R.string.fuel_set_price)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(stringResource(R.string.fuel_price_per_litre, fill.currency.orEmpty())) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = { Button(onClick = { price?.let(onSave) }, enabled = price != null) { Text(stringResource(R.string.fuel_price_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private class ReportLabels(val km: String, val drives: String, val time: String, val eco: String, val fuel: String, val per100: String)

/** The first and the end of the month [back] months before this one, local time. */
private fun monthBounds(back: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, -back)
    }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}

@Composable
private fun whenShort(at: Long): String = DateUtils.formatDateTime(LocalContext.current, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)

@Composable
private fun Stat(value: String, label: String, tint: Color? = null) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CompanionColors.Muted)
    }
}

/** Home's word on the last drive while parked: distance, time and eco score; a tap opens the Drives tab. */
@Composable
internal fun LastDriveGlance(d: DriveSummary, onAll: () -> Unit) {
    Panel(onClick = onAll) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.drive_last), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.drives_all), style = MaterialTheme.typography.labelLarge, color = CompanionColors.Blue)
            }
            Text(dateText(d.startedAt), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(kmText(d.distanceKm), stringResource(R.string.drive_km))
                Stat(durationText(d.elapsedMs), stringResource(R.string.drive_time))
                Stat(d.ecoScore?.toString() ?: "--", stringResource(R.string.week_eco), d.ecoScore?.let { ecoTint(it) })
            }
        }
    }
}

/** The latest drive in full (see [DriveJournal]): the trip computer's figures around the eco-driving ring. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LatestDriveCard(d: DriveSummary, underWay: Boolean) {
    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Route, contentDescription = null, tint = CompanionColors.Blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (underWay) R.string.drive_under_way else R.string.drive_last),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (underWay) CompanionColors.Teal else CompanionColors.Blue
                        )
                    }
                    Text(dateText(d.startedAt), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(top = 2.dp))
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                        Text(kmText(d.distanceKm), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(" " + stringResource(R.string.drive_km), style = MaterialTheme.typography.titleMedium, color = CompanionColors.Muted, modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
                EcoRing(d.ecoScore)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(durationText(d.elapsedMs), stringResource(R.string.drive_time))
                Stat(d.avgSpeedKmh.toString(), stringResource(R.string.drive_average))
                Stat(d.maxSpeedKmh.toString(), stringResource(R.string.drive_top))
            }
            val score = d.ecoScore
            if (score == null) {
                Text(stringResource(R.string.drive_eco_none), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(top = 14.dp))
            } else {
                Text(
                    stringResource(ecoCall(score)),
                    style = MaterialTheme.typography.titleSmall, color = ecoTint(score), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (score != null) {
                    Chip(stringResource(R.string.drive_hard, d.hardAccel, d.hardBrake))
                    d.sweetPercent?.let { Chip(stringResource(R.string.drive_band, it)) }
                    if (d.clutchHolds > 0) Chip(stringResource(R.string.drive_clutch, d.clutchHolds))
                }
                val liters = d.fuelLiters
                val cost = d.fuelCost
                if (liters != null && cost != null) Chip(stringResource(R.string.drive_fuel, decimal(liters, 1), decimal(cost, 2), d.currency.orEmpty()))
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = CompanionColors.SurfaceHigh) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** The eco-driving score as a ring filled to it, in its verdict's colour; "--" without one. */
@Composable
private fun EcoRing(score: Int?, diameter: Int = 76) {
    val tint = score?.let { ecoTint(it) } ?: CompanionColors.Muted
    val track = CompanionColors.Line
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(diameter.dp)) {
        Canvas(Modifier.size(diameter.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (score != null) drawArc(tint, 135f, 270f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "--", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
            Text(stringResource(R.string.week_eco), style = MaterialTheme.typography.labelSmall, color = CompanionColors.Muted)
        }
    }
}

/** The drives before the latest, under one heading per day ("Today", "Yesterday", then the date). */
internal fun LazyListScope.driveHistory(drives: List<DriveSummary>) {
    drives.groupBy { dayStart(it.startedAt) }.forEach { (day, list) ->
        item(key = "day-$day") { DayHeading(day) }
        items(list, key = { it.startedAt }) { DriveRow(it) }
    }
}

@Composable
private fun DayHeading(day: Long) {
    // "Today" becomes "Yesterday" at midnight on a list left open; read once, it never did.
    val now = rememberWallClock(DateUtils.HOUR_IN_MILLIS).longValue
    val text = if (now - day < 2 * DAY_MS) {
        DateUtils.getRelativeTimeSpanString(day, now, DateUtils.DAY_IN_MILLIS).toString()
    } else {
        DateUtils.formatDateTime(LocalContext.current, day, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = CompanionColors.Muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** One past drive on a line: the time, how far, how long and how fast, and its score. */
@Composable
private fun DriveRow(d: DriveSummary) {
    Panel {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(kmText(d.distanceKm) + " " + stringResource(R.string.drive_km), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("  " + timeText(d.startedAt), style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted, modifier = Modifier.padding(bottom = 2.dp))
                }
                Text(
                    stringResource(R.string.drive_row_short, durationText(d.elapsedMs), d.avgSpeedKmh),
                    style = MaterialTheme.typography.bodySmall, color = CompanionColors.Muted
                )
            }
            Spacer(Modifier.width(8.dp))
            EcoBadge(d.ecoScore)
        }
    }
}

/** The eco-driving score as a small tinted chip; "--" without one. */
@Composable
private fun EcoBadge(score: Int?) {
    val tint = score?.let { ecoTint(it) } ?: CompanionColors.Muted
    Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = 0.16f)) {
        Text(
            score?.toString() ?: "--", color = tint, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** The same verdict as the head unit's eco-driving card. */
private fun ecoCall(score: Int): Int = when {
    score >= 80 -> R.string.drive_eco_smooth
    score >= 60 -> R.string.drive_eco_fair
    else -> R.string.drive_eco_harsh
}

private fun ecoTint(score: Int): Color = when {
    score >= 80 -> CompanionColors.Teal
    score >= 60 -> CompanionColors.Amber
    else -> CompanionColors.Red
}

private fun kmText(km: Double): String =
    if (km < 100) String.format(Locale.getDefault(), "%.1f", km) else km.roundToInt().toString()

private fun decimal(value: Double, digits: Int): String = String.format(Locale.getDefault(), "%.${digits}f", value)

/** "1 h 05" / "25 min". */
@Composable
private fun durationText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return if (minutes >= 60) stringResource(R.string.drive_duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.drive_duration_m, minutes.coerceAtLeast(1))
}

/** Weekday, date and time, abbreviated: "Tue, 24 Sep, 08:12". */
@Composable
private fun dateText(at: Long): String = DateUtils.formatDateTime(
    LocalContext.current, at,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
)

@Composable
private fun timeText(at: Long): String = DateUtils.formatDateTime(LocalContext.current, at, DateUtils.FORMAT_SHOW_TIME)
