package com.openauto.dash

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The servicing tile (mileage, what's due next, every item's count-down) and
 * the dialog behind it: the mileage, the maker's intervals fetched by AI or
 * typed, and when each item was last done.
 */

/** One servicing row (labelMedium line) and the gap under it, for counting how many fit. */
private val UPKEEP_ROW_GAP = 5.dp
private val UPKEEP_ROW_HEIGHT = 20.dp + UPKEEP_ROW_GAP

internal fun formatKm(km: Int): String = String.format(Locale.getDefault(), "%,d", km)

private val UpkeepStage.color: Color
    @Composable get() = when (this) {
        UpkeepStage.DUE -> DashColors.Critical
        UpkeepStage.SOON -> DashColors.Warning
        UpkeepStage.OK -> DashColors.Good
        UpkeepStage.UNKNOWN -> DashColors.Muted
    }

/** "Oil change in 800 km" / "Brake fluid overdue by 12 days" / "Coolant due today" / the item's name when nothing is known. */
@Composable
internal fun upkeepLine(d: UpkeepDue): String {
    val name = stringResource(d.kind.labelRes)
    val km = d.kmLeft
    val days = d.daysLeft
    val u = LocalUnits.current
    return when {
        d.stage == UpkeepStage.DUE && km != null && km < 0 ->
            stringResource(if (u.imperial) R.string.units_upkeep_overdue_mi else R.string.upkeep_overdue_km, name, formatKm(u.distance(-km)))
        d.stage == UpkeepStage.DUE && days != null && days < 0 -> pluralStringResource(R.plurals.upkeep_overdue_days, -days, name, -days)
        // Due with nothing to count yet: "overdue by 0 days" reads wrong.
        d.stage == UpkeepStage.DUE -> stringResource(R.string.upkeep_due_today, name)
        d.stage == UpkeepStage.UNKNOWN -> name
        km != null && (days == null || km / 50 <= days) ->
            stringResource(if (u.imperial) R.string.units_upkeep_next_mi else R.string.upkeep_next_km, name, formatKm(u.distance(km)))
        else -> (days ?: 0).let { pluralStringResource(R.plurals.upkeep_next_days, it, name, it) }
    }
}

/** What's left on one item, short, for a list: "800 km", "12 d", "Not set". */
@Composable
internal fun upkeepLeft(d: UpkeepDue): String {
    val km = d.kmLeft
    val days = d.daysLeft
    val u = LocalUnits.current
    return when {
        d.stage == UpkeepStage.UNKNOWN -> stringResource(R.string.upkeep_not_set)
        km != null && (days == null || km / 50 <= days) ->
            stringResource(if (u.imperial) R.string.units_upkeep_row_mi else R.string.upkeep_row_km, formatKm(u.distance(km)))
        else -> stringResource(R.string.upkeep_row_days, days ?: 0)
    }
}

/** How long the servicing tile's edit button stays up after a tap. */
private const val EDIT_SHOWN_MS = 4_000L

/**
 * The servicing tile's two-step tap: a first tap only puts up an edit
 * button ([shown]), the button opens the dialog, and a button left alone
 * goes away after [EDIT_SHOWN_MS]. A brush of the tile no longer throws a
 * full sheet over the dashboard.
 */
internal class EditReveal {
    var shown by mutableStateOf(false)
}

@Composable
internal fun rememberEditReveal(): EditReveal {
    val reveal = remember { EditReveal() }
    LaunchedEffect(reveal.shown) {
        if (reveal.shown) {
            delay(EDIT_SHOWN_MS)
            reveal.shown = false
        }
    }
    return reveal
}

/** The round edit button over a tile's top end corner, while [reveal] is up. */
@Composable
internal fun EditCornerButton(reveal: EditReveal, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(reveal.shown, modifier = modifier, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        Box(
            modifier = Modifier.padding(DashSpace.Sm).size(48.dp).clip(CircleShape).background(DashColors.Accent)
                .clickable { reveal.shown = false; onEdit() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.upkeep_dialog_title), tint = Color.White,
                modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
internal fun ServiceCard(modifier: Modifier = Modifier) {
    val state by Maintenance.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val reveal = rememberEditReveal()
    // Once an hour: a unit left on overnight otherwise keeps yesterday's "due in N days".
    val now = rememberWallClock(3_600_000L).longValue
    val dues = remember(state, now) { state.statuses(now) }
    val first = dues.firstOrNull { it.stage != UpkeepStage.UNKNOWN }
    val lock = LocalDriveLock.current
    Card(modifier = modifier) { Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().clickable { reveal.shown = !reveal.shown }.padding(DashSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TileHeader(stringResource(R.string.upkeep_title))
            val odo = state.odometer
            val u = LocalUnits.current
            Row(verticalAlignment = Alignment.Bottom) {
                HeroNumber(text = odo?.let { formatKm(u.distance(it.nowKm)) } ?: "--", size = 34, dimmed = odo == null)
                Spacer(Modifier.width(6.dp))
                Text(u.distanceUnit, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
            val (status, color) = when {
                odo == null -> stringResource(R.string.upkeep_no_odometer) to DashColors.Accent
                first == null -> stringResource(R.string.upkeep_needs_dates) to DashColors.Muted
                first.stage == UpkeepStage.OK -> stringResource(R.string.upkeep_all_good) to DashColors.Good
                else -> upkeepLine(first) to first.stage.color
            }
            Text(status, color = color, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            // As many items as the tile has room for, so a tall tile has no empty bottom.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val fits = (maxHeight / UPKEEP_ROW_HEIGHT).toInt().coerceAtLeast(0)
                Column(verticalArrangement = Arrangement.spacedBy(UPKEEP_ROW_GAP)) {
                    dues.take(fits).forEach { d ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(d.kind.labelRes), color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text(upkeepLeft(d), color = if (d.stage == UpkeepStage.UNKNOWN) DashColors.Muted else d.stage.color,
                                fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        EditCornerButton(reveal, onEdit = { lock.whenParked { editing = true } }, Modifier.align(Alignment.TopEnd))
    } }
    if (editing) UpkeepDialog(onDismiss = { editing = false })
}

/** Mileage, the plan's intervals (AI or typed) and the last time each item was done. */
@Composable
internal fun UpkeepDialog(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Maintenance.state.collectAsState()
    // Typed and shown in the driver's unit, kept in km.
    val u = LocalUnits.current
    var odoText by remember { mutableStateOf(state.odometer?.nowKm?.let { u.distance(it) }?.toString().orEmpty()) }
    var fetchResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val now = System.currentTimeMillis()
    // The items keep the order the sheet opened with: each digit typed changes an
    // item's stage, and a list re-sorted under the finger moved the field being
    // typed in onto another item (the air filter's mileage ended up in the oil's).
    val order = remember { state.statuses(now).map { it.kind } }
    val dues = remember(state) { UpkeepRules.inOrder(state.statuses(now), order) }
    val zone = rememberZone()
    val monthFmt = remember(zone) { SimpleDateFormat("yyyy-MM", Locale.US).inZone(zone) }
    val toKm = { shown: Int? -> shown?.let { u.km(it.toDouble()).roundToInt() } }

    fun commitOdometer() {
        val typed = odoText.trim().replace(" ", "").replace(",", "").replace(".", "").toIntOrNull() ?: return
        if (typed > 0 && typed != state.odometer?.nowKm?.let { u.distance(it) }) Maintenance.setOdometer(u.km(typed.toDouble()).roundToInt())
    }

    val leave = { commitOdometer(); onDismiss() }
    SettingsSheet(
        title = stringResource(R.string.upkeep_dialog_title),
        onDismiss = leave,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = leave) }
    ) {
        Text(stringResource(R.string.upkeep_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Label(stringResource(if (u.imperial) R.string.units_upkeep_odometer_mi else R.string.upkeep_odometer))
        OutlinedTextField(
            value = odoText,
            onValueChange = { odoText = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
        state.odometer?.let { odo ->
            Text(
                stringResource(
                    if (u.imperial) R.string.units_upkeep_odometer_as_of_mi else R.string.upkeep_odometer_as_of,
                    DateUtils.getRelativeTimeSpanString(odo.readAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                    u.distance(odo.drivenSince).toInt()
                ),
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !state.fetching,
                onClick = {
                    fetchResult = null
                    scope.launch {
                        Maintenance.fetchPlan()
                            .onSuccess { fetchResult = true to context.getString(R.string.upkeep_fetch_ok) }
                            .onFailure { fetchResult = false to context.getString(R.string.upkeep_fetch_failed, it.message.orEmpty()) }
                    }
                },
                colors = buttonColors()
            ) { Text(stringResource(R.string.upkeep_fetch)) }
            Spacer(Modifier.size(12.dp))
            if (state.fetching) {
                CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
            } else {
                // Why the fetch the car made by itself failed, until one works.
                val (ok, message) = fetchResult
                    ?: state.fetchError?.takeIf { !state.planFromAi }?.let { false to stringResource(R.string.upkeep_fetch_failed, it) }
                    ?: (state.planFromAi to stringResource(if (state.planFromAi) R.string.upkeep_plan_ai else R.string.upkeep_plan_default))
                Text(message, color = if (ok) DashColors.Good else DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Label(stringResource(R.string.upkeep_section_items))
        // Keyed by item: its fields' text and focus stay with it, whatever its place in the list.
        dues.forEach { d -> key(d.kind) {
            val interval = state.plan.first { it.kind == d.kind }
            val done = state.done[d.kind]
            HorizontalDivider(color = DashColors.Line)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(d.kind.labelRes), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(upkeepLeft(d), color = d.stage.color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(
                    stringResource(if (u.imperial) R.string.units_upkeep_every_mi else R.string.upkeep_every_km),
                    interval.everyKm?.let { u.distance(it) }, Modifier.weight(1f)
                ) {
                    Maintenance.setInterval(interval.copy(everyKm = toKm(it)))
                }
                NumField(stringResource(R.string.upkeep_every_months), interval.everyMonths, Modifier.weight(1f)) {
                    Maintenance.setInterval(interval.copy(everyMonths = it))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField(
                    stringResource(if (u.imperial) R.string.units_upkeep_last_mi else R.string.upkeep_last_km),
                    done?.km?.let { u.distance(it) }, Modifier.weight(1f)
                ) {
                    Maintenance.setDone(d.kind, UpkeepDone(km = toKm(it), at = done?.at))
                }
                MonthField(stringResource(R.string.upkeep_last_date), done?.at?.let { monthFmt.format(it) }.orEmpty(), Modifier.weight(1f)) { text ->
                    val at = runCatching { monthFmt.parse(text)?.time }.getOrNull()
                    if (text.isBlank() || at != null) Maintenance.setDone(d.kind, UpkeepDone(km = done?.km, at = at))
                }
                TextButton(onClick = {
                    commitOdometer()
                    // The unit's clock may still read 1 January 2025 just after a start.
                    Maintenance.setDone(d.kind, UpkeepDone(km = Maintenance.state.value.odometer?.nowKm, at = UnitClock.now(context)))
                }) { Text(stringResource(R.string.upkeep_done_today), color = DashColors.AccentInk, maxLines = 2) }
            }
        } }
    }
}

/**
 * A whole-number field that reports on every valid change; blank = not known.
 * What is typed stays as typed while the field has the focus: the value it
 * reports comes back changed (rounded through the driver's unit, or by "Done
 * today" on another item) and rewriting the text under the finger lost digits.
 * Once the focus leaves, the field shows what is kept.
 */
@Composable
private fun NumField(label: String, value: Int?, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value?.toString().orEmpty() }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            val cleaned = typed.trim().replace(" ", "")
            if (cleaned.isEmpty()) onChange(null) else cleaned.toIntOrNull()?.let { if (it > 0) onChange(it) }
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = fieldColors()
    )
}

/** A year-month field; like [NumField], what is typed stays while it has the focus. */
@Composable
private fun MonthField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank() || Regex("\\d{4}-\\d{2}").matches(typed.trim())) onChange(typed.trim())
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = fieldColors()
    )
}
