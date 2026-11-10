package com.openauto.dash

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * "Now": one tile with the single most pressing thing at this moment, like a
 * phone's smart stack but for the driver. It changes by itself: a fault or a
 * warning of the car's own (a Peugeot or Citroën's, [PsaWarnings]), the
 * next turn, low fuel, the parking about to run out, a break, the weather, the
 * next appointment, the servicing, else a calm line with the time. While the
 * car moves it never pages on its own (a moving picture pulls the eye off the
 * road); parked, it pages through what is active. The ranking is pure
 * ([NowRules]), so it is unit-tested.
 */

/** What the Now card can be about. */
internal enum class NowKind { FAULT, CAR, TURN, FUEL, PARKING, BREAK, WEATHER, AGENDA, SERVICE, CALM }

/** One candidate card: what it is about and how pressing it is now (higher wins). */
internal data class NowCard(val kind: NowKind, val priority: Int)

/**
 * What the rules look at, read from the feeds by the tile: plain values, so
 * the ranking runs in a JVM test. Times are wall clock; 0 / null for none.
 */
internal data class NowInputs(
    val faultCodes: List<String> = emptyList(),
    /** The most serious of the car's own warnings ([PsaWarnings]); null when it has none worth a card. */
    val carWarning: PsaWarnings.Level? = null,
    /** The mechanic's verdict on [faultCodes], or the car's own rules' floor while there is none. */
    val faultSeverity: Severity? = null,
    /** When a scan last found a code not seen before ([AiMechanic.newFaultAt]). */
    val newFaultAt: Long = 0L,
    val guiding: Boolean = false,
    /** Distance to the next turn while guiding; null when the navigation gives none. */
    val turnMetres: Int? = null,
    val rangeKm: Int? = null,
    /** The fuel card was up at the last look, so it stays up until the range is clearly back ([NowRules.lowFuel]). */
    val fuelWasLow: Boolean = false,
    val parkingEndsAt: Long = 0L,
    /** Driving without a real break ([RestTimer.drivingMs]). */
    val drivingMs: Long = 0L,
    /** A weather warning in force where the car is. */
    val weatherWarning: Boolean = false,
    val rainFromMs: Long? = null,
    val rainUntilMs: Long? = null,
    /** Start of the next timed agenda event. */
    val nextEventAt: Long? = null,
    /** The servicing item due first. */
    val serviceStage: UpkeepStage? = null
)

/**
 * The card on screen since [since], and how pressing each card was at the
 * last look ([seen]), which tells a card that just arrived or grew more
 * pressing (it may cut in) from one that was there all along (it waits).
 */
internal data class NowShown(val kind: NowKind, val since: Long, val seen: Map<NowKind, Int> = emptyMap())

/** The ranking and the paging, pure. */
internal object NowRules {
    // Priorities: the order the review asked for, with room for the milder version of a card.
    const val FAULT_STOP = 100
    const val TURN_NEAR = 90
    const val FUEL_LOW = 80
    const val PARKING_ENDING = 70
    const val BREAK_DUE = 60
    const val WEATHER = 50
    const val AGENDA_SOON = 40
    /** A fault to see a garage about, but not new: below the agenda, beside the servicing. */
    const val FAULT_SOON = 32
    const val SERVICE_DUE = 30
    const val PARKING_RUNNING = 26
    const val FAULT_MINOR = 24
    /** Guiding with the next turn far off: still more use than the calm line. */
    const val TURN_FAR = 20
    const val CALM = 0

    /** A fault found this recently cuts in whatever its verdict: it is news. */
    const val NEW_FAULT_MS = 15 * 60_000L
    const val TURN_NEAR_M = 1_000
    /** Once up, the fuel card stays until the range is this far over the warning: a range read 79, 81, 79 would flicker. */
    const val FUEL_CLEAR_KM = LowFuel.WARN_KM + 20
    const val AGENDA_AHEAD_MS = 2 * 3_600_000L
    const val RAIN_AHEAD_MS = 3_600_000L

    /** Least time a card stays before a less pressing one replaces it; a more pressing one cuts in at once. */
    const val HOLD_MS = 10_000L
    /** Parked, the next active card comes after this long. */
    const val PAGE_MS = 8_000L

    /** Low on fuel: under [LowFuel.WARN_KM], and still until [FUEL_CLEAR_KM] once it was. A range of 0 is the car not saying. */
    fun lowFuel(rangeKm: Int?, wasLow: Boolean): Boolean = when {
        rangeKm == null || rangeKm <= 0 -> false
        rangeKm <= LowFuel.WARN_KM -> true
        else -> wasLow && rangeKm < FUEL_CLEAR_KM
    }

    /** "300 m", "1,2 km", "0.4 mi" in metres; null when there's no distance in it. */
    fun metres(distance: String): Int? {
        val m = NavState.DISTANCE.find(distance) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val factor = when (m.groupValues[2]) {
            "km" -> 1_000.0
            "mi" -> 1_609.344
            "ft" -> 0.3048
            "yd" -> 0.9144
            else -> 1.0
        }
        return (value * factor).roundToInt()
    }

    /** Every card that has something to say [now], the most pressing first; the calm line is always last. */
    fun cards(i: NowInputs, now: Long): List<NowCard> = buildList {
        if (i.faultCodes.isNotEmpty()) {
            val fresh = i.newFaultAt > 0L && now - i.newFaultAt in 0 until NEW_FAULT_MS
            add(NowCard(NowKind.FAULT, when {
                i.faultSeverity == Severity.STOP -> FAULT_STOP
                fresh && i.faultSeverity != Severity.OK -> FAULT_STOP
                i.faultSeverity == Severity.OK -> FAULT_MINOR
                else -> FAULT_SOON
            }))
        }
        // The car's own warning: stop now as a fault would be, a garage visit beside the servicing.
        when (i.carWarning) {
            PsaWarnings.Level.STOP -> add(NowCard(NowKind.CAR, FAULT_STOP))
            PsaWarnings.Level.SERVICE -> add(NowCard(NowKind.CAR, FAULT_SOON))
            else -> {}
        }
        if (i.guiding) add(NowCard(NowKind.TURN, if (i.turnMetres != null && i.turnMetres <= TURN_NEAR_M) TURN_NEAR else TURN_FAR))
        if (lowFuel(i.rangeKm, i.fuelWasLow)) add(NowCard(NowKind.FUEL, FUEL_LOW))
        if (i.parkingEndsAt > now) {
            add(NowCard(NowKind.PARKING, if (i.parkingEndsAt - now <= ParkingClock.REMIND_BEFORE_MS) PARKING_ENDING else PARKING_RUNNING))
        }
        if (i.drivingMs >= CareRules.FIRST_BREAK_MIN * 60_000L) add(NowCard(NowKind.BREAK, BREAK_DUE))
        val rainSoon = i.rainFromMs != null && i.rainFromMs - now <= RAIN_AHEAD_MS && (i.rainUntilMs ?: Long.MAX_VALUE) > now
        if (i.weatherWarning || rainSoon) add(NowCard(NowKind.WEATHER, WEATHER))
        if (i.nextEventAt != null && i.nextEventAt > now && i.nextEventAt - now <= AGENDA_AHEAD_MS) add(NowCard(NowKind.AGENDA, AGENDA_SOON))
        if (i.serviceStage == UpkeepStage.SOON || i.serviceStage == UpkeepStage.DUE) add(NowCard(NowKind.SERVICE, SERVICE_DUE))
        add(NowCard(NowKind.CALM, CALM))
    }.sortedByDescending { it.priority }

    /** The most pressing card [now]. */
    fun pick(i: NowInputs, now: Long): NowCard = cards(i, now).first()

    /**
     * The card to show after [shown], given the [cards] active [now]
     * (as [cards] ranks them). In order:
     *  - a card that just arrived (or grew more pressing) above the one shown cuts in;
     *  - the one shown gone, the most pressing takes its place at once;
     *  - [moving]: the most pressing card, changing only once the one shown
     *    has had [HOLD_MS]: no paging, by time or by hand;
     *  - parked: [page] steps by hand (+1 next, -1 back), and every [PAGE_MS]
     *    the next active card comes; the calm line only when nothing else is.
     */
    fun step(shown: NowShown?, cards: List<NowCard>, now: Long, moving: Boolean, page: Int = 0): NowShown {
        val ranked = cards.sortedByDescending { it.priority }.ifEmpty { listOf(NowCard(NowKind.CALM, CALM)) }
        val seen = ranked.associate { it.kind to it.priority }
        val top = ranked.first()
        if (shown == null) return NowShown(top.kind, now, seen)
        fun show(kind: NowKind) = if (kind == shown.kind) shown.copy(seen = seen) else NowShown(kind, now, seen)
        val current = seen[shown.kind] ?: return show(top.kind)
        ranked.firstOrNull { it.priority > current && it.priority > (shown.seen[it.kind] ?: -1) }?.let { return show(it.kind) }
        val held = now - shown.since
        if (moving) return show(if (shown.kind != top.kind && held >= HOLD_MS) top.kind else shown.kind)
        val rotation = ranked.filter { it.priority > CALM }.ifEmpty { listOf(top) }
        if (rotation.size > 1) {
            if (page != 0) return show(stepIn(rotation, shown.kind, page))
            if (held >= PAGE_MS) return show(stepIn(rotation, shown.kind, 1))
            return show(shown.kind)
        }
        return show(if (shown.kind != top.kind && held >= HOLD_MS) top.kind else shown.kind)
    }

    private fun stepIn(rotation: List<NowCard>, kind: NowKind, by: Int): NowKind {
        val at = rotation.indexOfFirst { it.kind == kind }
        return if (at < 0) rotation.first().kind else rotation[(at + by).mod(rotation.size)].kind
    }
}

/** The first of [barPages] holding each kind of built-in tile, from the dashboards' [pages]. */
internal fun widgetPagesOf(barPages: List<Int>, pages: List<List<DashboardItem>>): Map<BuiltinKind, Int> {
    val out = HashMap<BuiltinKind, Int>()
    barPages.forEach { page ->
        pages.getOrNull(page)?.forEach { if (it is DashboardItem.BuiltinWidget) out.putIfAbsent(it.kind, page) }
    }
    return out
}

/** Brings the dashboard holding a [kind] tile on screen; false when none of the bar's dashboards has one. */
private fun showWidgetPage(kind: BuiltinKind): Boolean {
    val page = LiveFacts.widgetPages[kind] ?: return false
    MainActivity.dashboardGoTo.value = System.currentTimeMillis() to page
    return true
}

// --- The live side -------------------------------------------------------------

/** One card as drawn: [label] names where it comes from, [title] says it, [detail] adds the figures. [onTap] is null where a tap only pages. */
internal class NowItem(
    val kind: NowKind,
    val priority: Int,
    val icon: ImageVector,
    val label: String,
    val title: String,
    val detail: String,
    val onTap: (() -> Unit)?
)

/** The tile's cards, the one shown, and paging by hand (parked only). */
internal class NowView(val items: List<NowItem>, val shown: NowItem, val parked: Boolean, val page: (Int) -> Unit)

/** How the card's colour says how pressing it is. */
private fun nowTint(priority: Int): Color = when {
    priority >= NowRules.FAULT_STOP -> DashColors.Critical
    priority >= NowRules.WEATHER -> DashColors.Warning
    else -> DashColors.AccentInk
}

private fun verdictText(severity: Severity?): Int = when (severity) {
    Severity.STOP -> R.string.ai_verdict_stop
    Severity.OK -> R.string.ai_verdict_ok
    Severity.SOON, null -> R.string.ai_verdict_soon
}

/** "45 min", "1h 20m". */
@Composable
private fun minutesText(min: Long): String =
    if (min < 60) stringResource(R.string.info_map_duration_min, min.toInt()) else stringResource(R.string.info_duration_hm, min / 60, min % 60)

/**
 * Reads every feed the cards come from, ranks them and keeps the one shown;
 * [pending] holds a tap that starts guidance (three seconds to cancel).
 */
@Composable
internal fun rememberNowView(pending: PendingActionState): NowView {
    val context = LocalContext.current
    val units = LocalUnits.current
    val lock = LocalDriveLock.current
    val moving = LocalCarMoving.current || lock.moving
    val clock = rememberWallClock(30_000L).longValue
    val demo by DemoMode.active.collectAsState()

    // The car: faults, fuel, driving time.
    val ai by AiMechanic.state.collectAsState()
    val newFaultAt by AiMechanic.newFaultAt.collectAsState()
    val fuel = rememberFuel()
    val care = CarCare.state.collectAsState()
    // The rest timer moves every second: only its minute is read.
    val drivingMin by remember { derivedStateOf { care.value.rest.drivingMs / 60_000L } }
    val car by CarProfileStore.profile.collectAsState()
    // The road: guidance, weather.
    val nav by NavDirections.state.collectAsState()
    DisposableEffect(Unit) { WeatherAlerts.start(context); onDispose { WeatherAlerts.stop() } }
    val warnings by WeatherAlerts.here.collectAsState()
    val weather = rememberWeather()
    // The driver: parking, agenda, servicing.
    val parkingEnds by remember { ParkingClock.load(context) }.collectAsState()
    val agenda = rememberAgendaSource()
    val upkeep by Maintenance.state.collectAsState()

    // The car's own warnings, the most serious first; the ones only good to know stay on the Car status tile.
    val trip by PsaTripComputer.state.collectAsState()
    val carWarnings = remember(trip?.warnings) {
        trip?.warnings.orEmpty().mapNotNull { PsaWarnings.of(it) }.filter { it.level != PsaWarnings.Level.INFO }
            .sortedBy { it.level.ordinal }.distinctBy { it.textRes }
    }
    val now = clock
    val codes = ai.codes.orEmpty()
    val severity = if (codes.isEmpty()) null else ai.diagnosis?.severity ?: SeverityFloor.of(codes, 0, car.hotC)
    val event = agenda.events.firstOrNull { !it.allDay && it.begin > now }
    // The demo has no servicing plan: an oil change coming up, shown here only.
    val dues = if (demo) listOf(DemoMode.upkeepSoon()) else remember(upkeep, now / 3_600_000L) { upkeep.statuses(now) }
    val service = dues.firstOrNull { it.stage == UpkeepStage.SOON || it.stage == UpkeepStage.DUE }
    // What comes after it, under the headline: the card was a lone line with empty space around it.
    val serviceNext = dues.firstOrNull { it !== service && it.stage != UpkeepStage.UNKNOWN }
    val warning = warnings.maxByOrNull { it.level }
    var fuelWasLow by remember { mutableStateOf(false) }
    val inputs = NowInputs(
        faultCodes = codes, faultSeverity = severity, newFaultAt = newFaultAt,
        carWarning = carWarnings.firstOrNull()?.level,
        guiding = nav.active, turnMetres = NowRules.metres(nav.distance),
        rangeKm = fuel?.rangeKm, fuelWasLow = fuelWasLow,
        parkingEndsAt = parkingEnds, drivingMs = drivingMin * 60_000L,
        weatherWarning = warning != null, rainFromMs = weather?.rainFromMs, rainUntilMs = weather?.rainUntilMs,
        nextEventAt = event?.begin, serviceStage = service?.stage
    )
    val cards = NowRules.cards(inputs, now)
    val fuelLow = cards.any { it.kind == NowKind.FUEL }
    SideEffect { fuelWasLow = fuelLow }

    // The cheapest station, asked for only while the fuel is low.
    val stations by FuelPriceRepo.stations.collectAsState()
    val grades = remember(car) { FuelPrices.gradesFor(car) }
    LaunchedEffect(fuelLow, grades.first()) {
        if (!fuelLow) return@LaunchedEffect
        while (true) {
            LocationFeed.location.value?.let { FuelPriceRepo.refresh(it.latitude, it.longitude, grades.first()) }
            delay(60_000L)
        }
    }
    val best = remember(stations, fuelLow, now) {
        val here = LocationFeed.location.value
        val list = stations
        if (!fuelLow || here == null || list == null) null else {
            val grade = grades.firstOrNull { g -> list.any { it.prices.containsKey(g) } } ?: grades.first()
            FuelPrices.rank(list, grade, here.latitude, here.longitude).firstOrNull()
        }
    }

    // Which card is shown: stepped once a second, and by hand. The whole step is
    // kept out of the snapshot; only the kind is state, so the tile redraws when the card changes.
    val stepper = remember { NowStepper() }
    var shownKind by remember { mutableStateOf<NowKind?>(null) }
    val latestCards by rememberUpdatedState(cards)
    val latestMoving by rememberUpdatedState(moving)
    val active = LocalPageActive.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            shownKind = stepper.step(latestCards, latestMoving, 0)
            delay(1_000L)
        }
    }
    val page: (Int) -> Unit = { by -> shownKind = stepper.step(latestCards, latestMoving, by) }

    val turnIcon = when (nav.turnSide()) {
        TurnSide.LEFT -> Icons.Filled.TurnLeft
        TurnSide.RIGHT -> Icons.Filled.TurnRight
        TurnSide.AHEAD -> Icons.Filled.Straight
    }
    val items = cards.map { c ->
        when (c.kind) {
            NowKind.FAULT -> {
                val first = codes.first()
                NowItem(
                    c.kind, c.priority, if (severity == Severity.STOP) Icons.Filled.Error else Icons.Filled.Warning,
                    BuiltinKind.OBD_DTC.label + ": " + codes.joinToString(" "),
                    stringResource(verdictText(severity)),
                    ai.diagnosis?.summary?.takeIf { it.isNotBlank() } ?: (first + ": " + stringResource(ObdCodes.describe(first).titleRes)),
                    onTap = { lock.whenParked { if (!showWidgetPage(BuiltinKind.OBD_DTC)) page(1) } }
                )
            }
            NowKind.CAR -> {
                val first = carWarnings.first()
                NowItem(
                    c.kind, c.priority, if (first.level == PsaWarnings.Level.STOP) Icons.Filled.Error else Icons.Filled.Warning,
                    stringResource(R.string.car_status_message),
                    stringResource(first.textRes),
                    when (carWarnings.size) {
                        1 -> ""
                        2 -> stringResource(carWarnings[1].textRes)
                        else -> stringResource(carWarnings[1].textRes) + ", " + stringResource(R.string.car_warn_more, carWarnings.size - 2)
                    },
                    onTap = { lock.whenParked { if (!showWidgetPage(BuiltinKind.OBD_DTC)) page(1) } }
                )
            }
            // The distance is the headline: it is what changes, and what the eye looks for.
            NowKind.TURN -> NowItem(
                c.kind, c.priority, turnIcon, BuiltinKind.NAVIGATION.label,
                nav.distance.ifBlank { nav.etaParts.firstOrNull().orEmpty() }.ifBlank { nav.instruction },
                if (nav.distance.isBlank()) "" else nav.instruction,
                onTap = { openNavigationApp(context, nav) }
            )
            NowKind.FUEL -> {
                val range = fuel?.rangeKm ?: 0
                val name = best?.station?.name?.ifBlank { best.station.town }
                val go = name?.let { context.getString(R.string.phone_guidance_to, it) } ?: context.getString(R.string.wheel_action_navigate_fuel)
                NowItem(
                    c.kind, c.priority, Icons.Filled.LocalGasStation, BuiltinKind.RANGE.label,
                    stringResource(R.string.widgets_now_fuel_low, units.distanceText(range)),
                    if (best != null && name != null) stringResource(
                        R.string.widgets_now_fuel_station, name, FuelPrices.formatPriceWithCurrency(best.price), FuelPrices.formatDistance(best.distanceKm, units)
                    ) else stringResource(R.string.wheel_action_navigate_fuel),
                    // Guidance replaces any route: said first, three seconds to cancel, as on the fuel tile.
                    onTap = {
                        pending.arm(go) {
                            if (best != null) navigateTo(context, best.station.lat, best.station.lng, best.station.label) else HandsFree.navigateToFuel(context)
                        }
                    }
                )
            }
            NowKind.PARKING -> NowItem(
                c.kind, c.priority, Icons.Filled.LocalParking, BuiltinKind.PARKING_TIMER.label,
                stringResource(R.string.widgets_now_park_title, units.time(Date(parkingEnds))),
                // To the minute as the parking tile counts it, not the half-minute clock.
                stringResource(R.string.widgets_now_left_min, ((parkingEnds - System.currentTimeMillis() + 59_999) / 60_000L).toInt().coerceAtLeast(1)),
                onTap = { lock.whenParked { if (!showWidgetPage(BuiltinKind.PARKING_TIMER)) page(1) } }
            )
            NowKind.BREAK -> NowItem(
                c.kind, c.priority, Icons.Filled.Coffee, BuiltinKind.BREAK_TIMER.label,
                stringResource(R.string.car_break_due),
                stringResource(R.string.widgets_now_driving, minutesText(drivingMin)),
                onTap = null
            )
            NowKind.WEATHER -> if (warning != null) NowItem(
                c.kind, c.priority, Icons.Filled.Warning, BuiltinKind.WEATHER_ALERTS.label,
                hazardName(context, warning), warning.area,
                onTap = { lock.whenParked { if (!showWidgetPage(BuiltinKind.WEATHER_ALERTS)) page(1) } }
            ) else {
                val from = weather?.rainFromMs ?: now
                NowItem(
                    c.kind, c.priority, Icons.Filled.Umbrella, BuiltinKind.WEATHER.label,
                    if (from <= now) weather?.rainUntilMs?.let { stringResource(R.string.canvas_rain_until, units.time(Date(it))) } ?: stringResource(R.string.info_wx_rain)
                    else stringResource(R.string.canvas_rain_from, units.time(Date(from))),
                    weather?.let { units.tempText(it.tempC) + " · " + stringResource(it.conditionRes) }.orEmpty(),
                    onTap = null
                )
            }
            NowKind.AGENDA -> {
                val e = event!!
                val title = e.title.ifBlank { stringResource(R.string.info_agenda_no_title) }
                NowItem(
                    c.kind, c.priority, Icons.Filled.Event, BuiltinKind.CALENDAR.label, title,
                    stringResource(R.string.widgets_now_event_at, units.time(Date(e.begin)), minutesText(((e.begin - now) / 60_000L).coerceAtLeast(1))) +
                        (if (e.location.isNotBlank()) " · " + e.location else ""),
                    onTap = if (e.location.isBlank()) null else {
                        { pending.arm(context.getString(R.string.phone_guidance_to, title)) { guideToEvent(context, e) } }
                    }
                )
            }
            NowKind.SERVICE -> NowItem(
                c.kind, c.priority, Icons.Filled.Build, BuiltinKind.SERVICE.label,
                upkeepLine(service!!), serviceNext?.let { upkeepLine(it) }.orEmpty(),
                onTap = { lock.whenParked { if (!showWidgetPage(BuiltinKind.SERVICE)) page(1) } }
            )
            // Nothing pressing: the time, the weather and the range, calmly.
            NowKind.CALM -> NowItem(
                c.kind, c.priority, Icons.Filled.Schedule, BuiltinKind.NOW.label,
                units.time(Date(now)),
                listOfNotNull(
                    weather?.let { units.tempText(it.tempC) + " · " + stringResource(it.conditionRes) },
                    fuel?.rangeKm?.takeIf { it > 0 }?.let { units.distanceText(it) }
                ).joinToString(" · "),
                onTap = null
            )
        }
    }
    return NowView(items, items.firstOrNull { it.kind == shownKind } ?: items.first(), parked = !moving, page = page)
}

/** [NowRules.step] with its last answer kept. */
private class NowStepper {
    private var shown: NowShown? = null

    // A clock GPS cannot set: the hold and paging times are differences, and the unit's
    // time jumps by months when a cold-started unit gets its fix.
    fun step(cards: List<NowCard>, moving: Boolean, page: Int): NowKind =
        NowRules.step(shown, cards, SystemClock.elapsedRealtime(), moving, page).also { shown = it }.kind
}

/**
 * The Now tile: the card shown, its source above it, page dots while parked.
 * A tap does the card's action (guidance, its tile's dashboard), else pages;
 * a swipe pages, parked only.
 */
@Composable
internal fun NowCard(modifier: Modifier = Modifier) {
    val pending = rememberPendingAction()
    val view = rememberNowView(pending)
    val swipe = with(LocalDensity.current) { 48.dp.toPx() }
    val latest by rememberUpdatedState(view)
    Card(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (abs(dx) > swipe) latest.page(if (dx < 0) 1 else -1) }
                    ) { _, amount -> dx += amount }
                }
                .clickable { (latest.shown.onTap ?: { latest.page(1) })() }
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
                val compact = maxHeight < 140.dp
                val wide = maxWidth > 420.dp
                Crossfade(targetState = view.shown.kind, animationSpec = tween(250), label = "now") { kind ->
                    val item = view.items.firstOrNull { it.kind == kind } ?: view.shown
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DashSpace.Xs)) {
                        val tint = nowTint(item.priority)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
                            Spacer(Modifier.width(DashSpace.Sm))
                            Text(
                                item.label.uppercase(Locale.getDefault()), color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            if (view.parked && view.items.count { it.priority > NowRules.CALM } > 1) PageDots(view)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            item.title, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 22.sp else if (wide) 34.sp else 28.sp,
                            lineHeight = if (compact) 26.sp else if (wide) 38.sp else 32.sp,
                            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis
                        )
                        if (item.detail.isNotEmpty()) Text(
                            item.detail, color = DashColors.TextSecondary, fontSize = if (compact) 15.sp else 18.sp,
                            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
        }
    }
}

/** One dot per active card, the shown one lit. */
@Composable
private fun PageDots(view: NowView) {
    Row(horizontalArrangement = Arrangement.spacedBy(DashSpace.Xs), verticalAlignment = Alignment.CenterVertically) {
        view.items.filter { it.priority > NowRules.CALM }.forEach {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (it.kind == view.shown.kind) DashColors.AccentInk else DashColors.Muted.copy(alpha = 0.4f))
            )
        }
    }
}

/** The Now card for the designs: the shown card as the headline, every active card as a row. */
@Composable
internal fun nowFace(): WidgetFace {
    val view = rememberNowView(LocalPendingAction.current)
    val s = view.shown
    return WidgetFace(
        icon = s.icon,
        title = s.label,
        value = s.title,
        textValue = true,
        caption = s.detail,
        alert = s.priority >= NowRules.WEATHER,
        severity = when {
            s.priority >= NowRules.FAULT_STOP -> 2
            s.priority >= NowRules.WEATHER -> 1
            else -> 0
        },
        // The shown card first, the rest in their order: a card stack keeps it on top.
        rows = (listOf(s) + view.items.filter { it !== s && it.priority > NowRules.CALM }).map {
            FaceRow(it.title, it.detail, alert = it.priority >= NowRules.WEATHER, onClick = it.onTap, key = it.kind)
        },
        onClick = s.onTap ?: { view.page(1) }
    )
}
