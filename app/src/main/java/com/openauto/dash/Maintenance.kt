package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/*
 * Servicing planner: what the car needs done every N km or N months, when
 * each was last done, and the mileage, which the driver types once and the
 * drive monitor then advances from the OBD speed, or the GPS without OBD
 * (or the car's own odometer, read off its CAN box). The rules are pure; the AI
 * only fills in the maker's intervals, once per car, so the reminders work
 * offline and in every language.
 */

/** What gets serviced. Names are persisted, so never rename an entry. */
enum class UpkeepKind(@StringRes val labelRes: Int) {
    OIL(R.string.upkeep_oil),
    AIR_FILTER(R.string.upkeep_air_filter),
    FUEL_FILTER(R.string.upkeep_fuel_filter),
    CABIN_FILTER(R.string.upkeep_cabin_filter),
    BRAKE_FLUID(R.string.upkeep_brake_fluid),
    COOLANT(R.string.upkeep_coolant),
    TIMING_BELT(R.string.upkeep_timing_belt),
    ADDITIVE(R.string.upkeep_additive),
    GEARBOX_OIL(R.string.upkeep_gearbox_oil),
    SPARK_PLUGS(R.string.upkeep_spark_plugs),
    /** The roadworthiness test (contrôle technique, MOT, TÜV): by date only. */
    INSPECTION(R.string.upkeep_inspection)
}

/** Every two years in France and most of Europe once the car is four years old. */
private const val INSPECTION_MONTHS = 24

/** The first inspection, counted from the first registration (France, Italy, Spain...). */
private const val FIRST_INSPECTION_MONTHS = 48

/**
 * How often [kind] is due; null = not known (no reminder on that count).
 * [own]: the driver typed it, so the maker's intervals never replace it.
 */
data class UpkeepInterval(val kind: UpkeepKind, val everyKm: Int? = null, val everyMonths: Int? = null, val own: Boolean = false) {
    val known: Boolean get() = everyKm != null || everyMonths != null
}

/** When [UpkeepKind] was last done: the mileage and/or the date. */
data class UpkeepDone(val km: Int? = null, val at: Long? = null)

/**
 * The mileage: what the driver read off the dashboard at [readAt], plus the
 * kilometres the drive monitor has counted since.
 */
data class Odometer(val km: Int, val readAt: Long, val drivenSince: Double = 0.0) {
    val nowKm: Int get() = km + drivenSince.roundToInt()
}

enum class UpkeepStage { UNKNOWN, OK, SOON, DUE }

/** One item's standing: what's left on the km and day counts, and the stage that follows. */
data class UpkeepDue(val kind: UpkeepKind, val kmLeft: Int?, val daysLeft: Int?, val stage: UpkeepStage)

/** The pure rules: intervals, what's left, what to say. Unit-tested. */
object UpkeepRules {
    const val SOON_KM = 1_000
    const val SOON_DAYS = 30
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * The items this car has, with the intervals already in its profile (oil
     * from the service plan) and the rest unknown until the AI or the driver
     * fills them in.
     */
    fun defaultPlan(car: CarProfile): List<UpkeepInterval> = buildList {
        add(UpkeepInterval(UpkeepKind.OIL, car.serviceKm, car.serviceMonths))
        add(UpkeepInterval(UpkeepKind.AIR_FILTER))
        if (car.diesel) add(UpkeepInterval(UpkeepKind.FUEL_FILTER))
        add(UpkeepInterval(UpkeepKind.CABIN_FILTER))
        add(UpkeepInterval(UpkeepKind.BRAKE_FLUID, everyMonths = 24))
        add(UpkeepInterval(UpkeepKind.COOLANT))
        if (!car.timing.contains("chain", ignoreCase = true) && !car.timing.contains("chaîne", ignoreCase = true)) {
            add(UpkeepInterval(UpkeepKind.TIMING_BELT))
        }
        if (car.particleFilter && car.filterAdditive) add(UpkeepInterval(UpkeepKind.ADDITIVE))
        if (car.gearbox != GearboxType.MANUAL) add(UpkeepInterval(UpkeepKind.GEARBOX_OIL))
        if (!car.diesel) add(UpkeepInterval(UpkeepKind.SPARK_PLUGS))
        add(UpkeepInterval(UpkeepKind.INSPECTION, everyMonths = INSPECTION_MONTHS))
    }

    /** A plan saved before the inspection was an item gets it too. */
    fun withInspection(plan: List<UpkeepInterval>): List<UpkeepInterval> =
        if (plan.isEmpty() || plan.any { it.kind == UpkeepKind.INSPECTION }) plan
        else plan + UpkeepInterval(UpkeepKind.INSPECTION, everyMonths = INSPECTION_MONTHS)

    /** [ai]'s intervals over [base]: a known figure replaces, an unknown one keeps the base's. */
    fun merge(base: List<UpkeepInterval>, ai: List<UpkeepInterval>): List<UpkeepInterval> {
        val byKind = ai.associateBy { it.kind }
        val merged = base.map { b ->
            byKind[b.kind]?.let { a -> UpkeepInterval(b.kind, a.everyKm ?: b.everyKm, a.everyMonths ?: b.everyMonths) } ?: b
        }
        // Items the AI knows about that the defaults left out (a chain car with a belt-driven pump...).
        return merged + ai.filter { a -> a.known && merged.none { it.kind == a.kind } }
    }

    /** [fresh] (the defaults, or the maker's intervals) with the driver's own figures in [current] kept. */
    fun keepOwn(fresh: List<UpkeepInterval>, current: List<UpkeepInterval>): List<UpkeepInterval> {
        val own = current.filter { it.own }.associateBy { it.kind }
        return fresh.map { own[it.kind] ?: it } + own.values.filter { o -> fresh.none { it.kind == o.kind } }
    }

    /**
     * The inspection to log for a deadline printed on the registration
     * certificate (X.1): one interval of [everyMonths] before [deadline], so
     * the reminder counts down to it. Null when [logged] is already that
     * recent, or when the deadline is past: it says nothing about tests done since.
     */
    fun inspectionBefore(deadline: Long, everyMonths: Int, logged: UpkeepDone?, now: Long): UpkeepDone? {
        if (deadline < now) return null
        val at = Calendar.getInstance().apply { timeInMillis = deadline; add(Calendar.MONTH, -everyMonths) }.timeInMillis
        if ((logged?.at ?: Long.MIN_VALUE) >= at) return null
        return UpkeepDone(at = at)
    }

    /** Where [interval] stands given the last time it was done and the mileage. */
    fun status(interval: UpkeepInterval, done: UpkeepDone?, odometerKm: Int?, now: Long): UpkeepDue {
        val kmLeft = if (interval.everyKm != null && done?.km != null && odometerKm != null) done.km + interval.everyKm - odometerKm else null
        val daysLeft = if (interval.everyMonths != null && done?.at != null) {
            val due = Calendar.getInstance().apply { timeInMillis = done.at; add(Calendar.MONTH, interval.everyMonths) }.timeInMillis
            ((due - now) / DAY_MS).toInt()
        } else null
        val stage = when {
            kmLeft == null && daysLeft == null -> UpkeepStage.UNKNOWN
            (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> UpkeepStage.DUE
            (kmLeft != null && kmLeft <= SOON_KM) || (daysLeft != null && daysLeft <= SOON_DAYS) -> UpkeepStage.SOON
            else -> UpkeepStage.OK
        }
        return UpkeepDue(interval.kind, kmLeft, daysLeft, stage)
    }

    /**
     * [registeredAt] (the first registration) dates the first inspection when
     * none was logged and it is still to come; after it, only the driver's
     * log knows when the last one was.
     */
    fun statuses(plan: List<UpkeepInterval>, done: Map<UpkeepKind, UpkeepDone>, odometerKm: Int?, now: Long, registeredAt: Long? = null): List<UpkeepDue> =
        plan.map {
            val last = done[it.kind] ?: if (it.kind == UpkeepKind.INSPECTION) firstInspection(registeredAt, now) else null
            status(it, last, odometerKm, now)
        }.sortedWith(compareBy({ order(it.stage) }, { soonest(it) }))

    /**
     * The first inspection as if an inspection had been done one interval
     * before it, so [status] counts down to it; null once it is past.
     */
    internal fun firstInspection(registeredAt: Long?, now: Long): UpkeepDone? {
        registeredAt ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = registeredAt; add(Calendar.MONTH, FIRST_INSPECTION_MONTHS) }
        if (cal.timeInMillis < now) return null
        cal.add(Calendar.MONTH, -INSPECTION_MONTHS)
        return UpkeepDone(at = cal.timeInMillis)
    }

    private fun order(stage: UpkeepStage) = when (stage) {
        UpkeepStage.DUE -> 0
        UpkeepStage.SOON -> 1
        UpkeepStage.OK -> 2
        UpkeepStage.UNKNOWN -> 3
    }

    /**
     * [dues] in the order of [kinds], the kinds not listed last in their own
     * order. The planner's sheet keeps the order it opened with: every digit
     * typed into a mileage changes an item's stage, and a list re-sorted under
     * the finger moved the field being typed in onto another item.
     */
    fun inOrder(dues: List<UpkeepDue>, kinds: List<UpkeepKind>): List<UpkeepDue> =
        dues.sortedBy { kinds.indexOf(it.kind).let { i -> if (i < 0) kinds.size else i } }

    /** A GPS step bigger than this is a restored or demo trip, not driving. */
    const val MAX_GPS_STEP_KM = 1.0
    /** An OBD drive heard from within this long is the one counting the kilometres. */
    const val OBD_FRESH_MS = 15_000L

    /**
     * The kilometres the GPS trip moved from [before] to [after], or 0 while the
     * OBD drive is counting them ([obdLastAt] fresh) or the step is no real driving.
     */
    fun gpsStepKm(before: TripState?, after: TripState, obdLastAt: Long?, now: Long): Double {
        if (before == null || (obdLastAt != null && now - obdLastAt < OBD_FRESH_MS)) return 0.0
        val m = if (after.startedAt == before.startedAt) after.distanceM - before.distanceM else after.distanceM
        return (m / 1000).takeIf { it > 0 && it <= MAX_GPS_STEP_KM } ?: 0.0
    }

    /** A rough "how soon" for sorting: days, with 50 km counting as a day. */
    private fun soonest(d: UpkeepDue): Int = listOfNotNull(d.daysLeft, d.kmLeft?.let { it / 50 }).minOrNull() ?: Int.MAX_VALUE

    /**
     * The items worth saying at start-up: due or nearly, and not yet said at
     * that stage ([spoken] is the stage last announced per item).
     */
    fun toSpeak(dues: List<UpkeepDue>, spoken: Map<UpkeepKind, UpkeepStage>): List<UpkeepDue> =
        dues.filter { (it.stage == UpkeepStage.SOON || it.stage == UpkeepStage.DUE) && spoken[it.kind] != it.stage }

    /** The sentence for one due item; the km count wins over the date when both say something. */
    internal fun line(d: UpkeepDue, units: UnitSystem = UnitSystem()): SpokenLine {
        val name = SpokenLine(d.kind.labelRes, emptyList())
        val km = d.kmLeft
        val days = d.daysLeft
        val mi = units.imperial
        return when {
            d.stage == UpkeepStage.DUE && km != null && km < 0 -> units.distance(-km).let { over ->
                SpokenLine(if (mi) R.plurals.units_upkeep_say_overdue_mi else R.plurals.upkeep_say_overdue_km, listOf(name, over), quantity = over)
            }
            d.stage == UpkeepStage.DUE && days != null && days < 0 ->
                SpokenLine(R.plurals.upkeep_say_overdue_days, listOf(name, -days), quantity = -days)
            // Due with nothing to count yet: "overdue by 0 days" reads wrong.
            d.stage == UpkeepStage.DUE -> SpokenLine(R.string.upkeep_say_due_today, listOf(name))
            km != null && km <= SOON_KM -> units.distance(km).let { left ->
                SpokenLine(if (mi) R.plurals.units_upkeep_say_soon_mi else R.plurals.upkeep_say_soon_km, listOf(name, left), quantity = left)
            }
            else -> (days ?: 0).let { SpokenLine(R.plurals.upkeep_say_soon_days, listOf(name, it), quantity = it) }
        }
    }
}

/** Asks Gemini for the maker's service intervals and reads the answer. Pure prompt and reading, unit-tested. */
object UpkeepPlan {
    private const val BUDGET_MS = 60_000L

    val SCHEMA: JSONObject
        get() {
            val item = JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("kind", JSONObject().put("type", "STRING").put("enum", JSONArray(UpkeepKind.entries.map { it.name })))
                    .put("every_km", JSONObject().put("type", "NUMBER").put("nullable", true))
                    .put("every_months", JSONObject().put("type", "NUMBER").put("nullable", true)))
                .put("required", JSONArray(listOf("kind", "every_km", "every_months")))
            return JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("items", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray(listOf("items")))
        }

    fun prompt(car: String): String = buildString {
        appendLine("You are an automotive technical reference. Give the manufacturer's service schedule for this exact car, as sold in Europe: \"$car\".")
        appendLine("One item per kind, using these kinds: ${UpkeepKind.entries.joinToString(", ") { it.name }}.")
        appendLine("OIL = engine oil and filter; ADDITIVE = the particulate filter's fuel additive (e.g. Eolys) refill; GEARBOX_OIL = gearbox oil or, on a robotised gearbox, the clutch actuator check; TIMING_BELT = timing belt replacement (leave out on a chain engine).")
        appendLine("every_km and every_months: the maker's interval for normal use, whichever apply; null when not applicable or not sure. Leave out kinds this car doesn't have (no spark plugs on a diesel). Never guess wildly.")
    }

    /** The intervals in Gemini's [raw] answer; unknown kinds are skipped. */
    fun read(raw: String): List<UpkeepInterval> {
        val o = aiJson(raw)
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val kind = UpkeepKind.entries.firstOrNull { k -> k.name == it.optString("kind") } ?: return@mapNotNull null
            fun num(k: String) = if (it.isNull(k)) null else it.optDouble(k).takeIf { v -> !v.isNaN() && v > 0 }?.roundToInt()
            UpkeepInterval(kind, num("every_km"), num("every_months"))
        }.distinctBy { it.kind }
    }

    suspend fun fetch(context: Context, car: CarProfile): Result<List<UpkeepInterval>> {
        val config = AiSettings.load(context)
        if (config.apiKey.isBlank()) return Result.failure(IllegalStateException(context.getString(R.string.car_fetch_no_key)))
        GeminiClient.reachGoogle().exceptionOrNull()?.let {
            return Result.failure(IllegalStateException(AiMechanic.describe(context, it)))
        }
        return GeminiClient.generate(config.apiKey, prompt(car.promptDescription()), SCHEMA, budgetMs = BUDGET_MS)
            .mapCatching { runCatching { read(it.text) }.getOrElse { throw UnreadableAnswerException() } }
            .recoverCatching { throw IllegalStateException(AiMechanic.describe(context, it)) }
    }
}

/** Everything the planner knows, for the tile and the dialog. */
data class UpkeepState(
    val plan: List<UpkeepInterval> = emptyList(),
    val done: Map<UpkeepKind, UpkeepDone> = emptyMap(),
    val odometer: Odometer? = null,
    /** The plan came from the AI for this car (else defaults / the driver's own figures). */
    val planFromAi: Boolean = false,
    val fetching: Boolean = false,
    /** Why the last automatic plan fetch failed, to show in the dialog. */
    val fetchError: String? = null,
    /** The car's first registration, which dates its first inspection. */
    val registeredAt: Long? = null
) {
    fun statuses(now: Long): List<UpkeepDue> = UpkeepRules.statuses(plan, done, odometer?.nowKm, now, registeredAt)

    /** The date [kind] is next due by, or null while its last date or its interval isn't known. */
    fun dueAt(kind: UpkeepKind, now: Long): Long? {
        val months = plan.firstOrNull { it.kind == kind }?.everyMonths ?: return null
        val last = done[kind] ?: if (kind == UpkeepKind.INSPECTION) UpkeepRules.firstInspection(registeredAt, now) else null
        val at = last?.at ?: return null
        return Calendar.getInstance().apply { timeInMillis = at; add(Calendar.MONTH, months) }.timeInMillis
    }
}

/**
 * The live side: keeps the plan, the log and the mileage, counts the
 * kilometres driven, fetches the maker's intervals by itself once a key and a
 * car are known, and tells the briefing what to say.
 */
object Maintenance {
    private const val PREFS = "upkeep"

    private var appContext: Context? = null
    private val _state = MutableStateFlow(UpkeepState())
    val state: StateFlow<UpkeepState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fetchMutex = Mutex()
    /** Which car's description the saved plan was fetched for. */
    private var planFor: Int = 0
    /** The saved plan predates [UpkeepInterval.own] and never came from the AI: [onCar] works out the driver's figures. */
    private var ownUnmarked = false
    private var spoken: Map<UpkeepKind, UpkeepStage> = emptyMap()
    private var drivenUnsaved = 0.0

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
        scope.launch { CarProfileStore.profile.collect { onCar(it) } }
        scope.launch { followGps() }
        // The car's own odometer, from whichever source has it ([K.ODOMETER]; the car app's today).
        scope.launch { Vehicle.bus.value(K.ODOMETER).filterNotNull().collect { carOdometer(it.toInt()) } }
    }

    /** Without OBD, the GPS trip's kilometres advance the mileage instead. */
    private suspend fun followGps() {
        var before: TripState? = null
        LocationFeed.trip.collect { trip ->
            val now = System.currentTimeMillis()
            drove(UpkeepRules.gpsStepKm(before, trip, CarCare.state.value.drive?.lastAt, now))
            before = trip
        }
    }

    /** The plan follows the car: a new car gets the defaults and, given a key, the maker's intervals. */
    private fun onCar(car: CarProfile) {
        val registeredAt = car.registeredOn?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        if (_state.value.registeredAt != registeredAt) _state.update { it.copy(registeredAt = registeredAt) }
        val hash = car.promptDescription().hashCode()
        if (ownUnmarked) {
            ownUnmarked = false
            // Saved before the driver's figures were marked: on a plan the AI never filled, what differs from the defaults is theirs.
            val defaults = UpkeepRules.defaultPlan(car).associateBy { it.kind }
            _state.update { s -> s.copy(plan = s.plan.map { it.copy(own = it.known && it != defaults[it.kind]) }) }
            save()
        }
        val s = _state.value
        if (s.plan.isEmpty() || (planFor != hash && s.planFromAi)) {
            // What the driver typed stays, for the items the car still has.
            val fresh = UpkeepRules.defaultPlan(car)
            val plan = UpkeepRules.keepOwn(fresh, s.plan).filter { kept -> fresh.any { it.kind == kept.kind } }
            _state.update { it.copy(plan = plan, planFromAi = false) }
            planFor = hash
            save()
        }
        if (!_state.value.planFromAi) autoFetch(car, hash)
    }

    /**
     * Once per car, then again only after [AUTO_RETRY_MS]: every save of the
     * car profile lands here, and asking again each time while offline would
     * spend the free AI quota for nothing.
     */
    private fun autoFetch(car: CarProfile, hash: Int) {
        val context = appContext ?: return
        if (AiSettings.load(context).apiKey.isBlank() || DemoMode.isOn) return
        val now = System.currentTimeMillis()
        if (hash == autoTriedFor && now - autoTriedAt < AUTO_RETRY_MS) return
        autoTriedFor = hash
        autoTriedAt = now
        scope.launch { fetchPlan(car, hash) }
    }

    /** The car the plan was last fetched for by itself, and when; in memory, so a restart tries again. */
    private var autoTriedFor = 0
    private var autoTriedAt = 0L
    private const val AUTO_RETRY_MS = 6 * 60 * 60_000L

    /** Fetches the maker's intervals for [car] (the current one by default); the failure text is ready to show. */
    suspend fun fetchPlan(car: CarProfile = CarProfileStore.current, hash: Int = car.promptDescription().hashCode()): Result<Unit> =
        fetchMutex.withLock {
            val context = appContext ?: return Result.failure(IllegalStateException("no context"))
            _state.update { it.copy(fetching = true, fetchError = null) }
            val result = UpkeepPlan.fetch(context, car)
            result.onSuccess { ai ->
                val plan = UpkeepRules.keepOwn(UpkeepRules.merge(UpkeepRules.defaultPlan(car), ai), _state.value.plan)
                _state.update { it.copy(plan = plan, planFromAi = true, fetching = false) }
                planFor = hash
                save()
            }.onFailure {
                _state.update { s -> s.copy(fetching = false, fetchError = it.message) }
            }
            result.map { }
        }

    /** The driver read the dashboard: [km] as of now. */
    @Synchronized
    fun setOdometer(km: Int) {
        _state.update { it.copy(odometer = Odometer(km, System.currentTimeMillis())) }
        drivenUnsaved = 0.0
        drivenUnpublished = 0.0
        save()
    }

    /**
     * The car's own odometer, from the bus ([K.ODOMETER]): the mileage keeps
     * itself up to date, nothing to type. Written when it moved a whole
     * kilometre from what the planner has (typed or counted).
     */
    fun carOdometer(km: Int) {
        if (DemoMode.isOn || km !in 1..MAX_ODOMETER_KM) return
        val known = _state.value.odometer?.nowKm
        if (known != null && kotlin.math.abs(known - km) < 1) return
        // A misread frame under the ceiling (1 234 567 on a 150 000 km car) must not become the
        // saved mileage, with every km item overdue: a leap from what is known is taken only
        // when the car repeats it. The bus then takes nothing lower until the process restarts.
        if (known != null && kotlin.math.abs(known - km) > ODOMETER_LEAP_KM && kotlin.math.abs(pendingKm - km) > 1) {
            pendingKm = km
            return
        }
        pendingKm = 0
        setOdometer(km)
    }

    /** An odometer reading far from the known one, waiting to be seen again. */
    private var pendingKm = 0
    private const val ODOMETER_LEAP_KM = 500

    /** The driver's own interval for its kind (added when the plan had none); emptied, the maker's may fill it in again. */
    fun setInterval(interval: UpkeepInterval) {
        val typed = interval.copy(own = interval.known)
        _state.update { s ->
            val plan = s.plan
            s.copy(plan = if (plan.any { it.kind == typed.kind }) plan.map { if (it.kind == typed.kind) typed else it } else plan + typed)
        }
        save()
    }

    fun setDone(kind: UpkeepKind, done: UpkeepDone?) {
        val before = _state.value.done[kind]?.at
        // Set atomically: the drive monitor advances the odometer on another
        // thread, and its copy of the state could carry the old list back.
        val map = _state.updateAndGet { s ->
            val all = s.done.toMutableMap()
            if (done == null || (done.km == null && done.at == null)) all.remove(kind) else all[kind] = done
            s.copy(done = all)
        }.done
        // Done again: the next reminder for it is news again.
        spoken = spoken - kind
        save()
        // A date the driver set was gone after an update: what was set, and later loaded, goes in the event log.
        if (map[kind]?.at != before) appContext?.let { DebugLog.note(it, "servicing: ${kind.name} set to ${doneText(map[kind])}") }
    }

    /**
     * The registration certificate says the next roadworthiness test is due by
     * [deadline] (local midnight): logged as the inspection before it.
     */
    fun inspectionDueBy(deadline: Long) {
        val every = _state.value.plan.firstOrNull { it.kind == UpkeepKind.INSPECTION }?.everyMonths ?: INSPECTION_MONTHS
        val done = UpkeepRules.inspectionBefore(deadline, every, _state.value.done[UpkeepKind.INSPECTION], System.currentTimeMillis()) ?: return
        setDone(UpkeepKind.INSPECTION, done)
    }

    /** The drive monitor counted [km] more kilometres. From the monitor's thread: the counters are kept under the same lock as [setOdometer]. */
    @Synchronized
    fun drove(km: Double) {
        if (km <= 0.0 || DemoMode.isOn) return
        if (_state.value.odometer == null) return
        drivenUnpublished += km
        drivenUnsaved += km
        // Published every 100 m: at every reading, each upkeep tile redrew for a few metres.
        if (drivenUnpublished < 0.1 && drivenUnsaved < 1.0) return
        val add = drivenUnpublished
        _state.update { s -> s.odometer?.let { odo -> s.copy(odometer = odo.copy(drivenSince = odo.drivenSince + add)) } ?: s }
        drivenUnpublished = 0.0
        if (drivenUnsaved >= 1.0) {
            drivenUnsaved = 0.0
            save()
        }
    }

    /** Kilometres driven since the state last published them. */
    private var drivenUnpublished = 0.0

    /** What the briefing should say now; call [markSpoken] once it has. */
    fun dueForBriefing(now: Long): List<UpkeepDue> = UpkeepRules.toSpeak(_state.value.statuses(now), spoken)

    fun markSpoken(dues: List<UpkeepDue>) {
        spoken = spoken + dues.associate { it.kind to it.stage }
        save()
    }

    private fun load() {
        val context = appContext ?: return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString("state", null)
        if (raw == null) {
            DebugLog.note(context, "servicing: nothing saved yet")
            return
        }
        runCatching {
            val o = JSONObject(raw)
            planFor = o.optInt("plan_for")
            val plan = o.optJSONArray("plan")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val it = a.getJSONObject(i)
                    val kind = UpkeepKind.entries.firstOrNull { k -> k.name == it.optString("kind") } ?: return@mapNotNull null
                    UpkeepInterval(kind, it.optInt("km").takeIf { v -> v > 0 }, it.optInt("months").takeIf { v -> v > 0 }, it.optBoolean("own"))
                }
            }.orEmpty()
            ownUnmarked = !o.optBoolean("plan_ai") && o.optJSONArray("plan")?.optJSONObject(0)?.has("own") == false
            val done = o.optJSONObject("done")?.let { d ->
                d.keys().asSequence().mapNotNull { key ->
                    val kind = UpkeepKind.entries.firstOrNull { k -> k.name == key } ?: return@mapNotNull null
                    val it = d.getJSONObject(key)
                    kind to UpkeepDone(it.optInt("km").takeIf { v -> v > 0 }, it.optLong("at").takeIf { v -> v > 0 })
                }.toMap()
            }.orEmpty()
            val odo = o.optJSONObject("odometer")?.let { Odometer(it.getInt("km"), it.getLong("at"), it.optDouble("driven", 0.0)) }
            spoken = o.optJSONObject("spoken")?.let { s ->
                s.keys().asSequence().mapNotNull { key ->
                    val kind = UpkeepKind.entries.firstOrNull { k -> k.name == key } ?: return@mapNotNull null
                    val stage = UpkeepStage.entries.firstOrNull { st -> st.name == s.optString(key) } ?: return@mapNotNull null
                    kind to stage
                }.toMap()
            }.orEmpty()
            _state.value = UpkeepState(UpkeepRules.withInspection(plan), done, odo, planFromAi = o.optBoolean("plan_ai"))
            DebugLog.note(context, "servicing: loaded ${done.entries.joinToString { "${it.key.name} ${doneText(it.value)}" }.ifEmpty { "nothing done" }}")
        }.onFailure { DebugLog.note(context, "servicing: saved state not read (${it.message}), ${raw.length} chars") }
    }

    private fun doneText(done: UpkeepDone?): String = when {
        done == null -> "nothing"
        else -> listOfNotNull(
            done.at?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(it) },
            done.km?.let { "$it km" }
        ).joinToString(" ")
    }

    private fun save() {
        val s = _state.value
        val o = JSONObject()
            .put("plan_for", planFor)
            .put("plan_ai", s.planFromAi)
            .put("plan", JSONArray(s.plan.map { JSONObject().put("kind", it.kind.name).putOpt("km", it.everyKm).putOpt("months", it.everyMonths).put("own", it.own) }))
            .put("done", JSONObject().apply { s.done.forEach { (k, d) -> put(k.name, JSONObject().putOpt("km", d.km).putOpt("at", d.at)) } })
            .putOpt("odometer", s.odometer?.let { JSONObject().put("km", it.km).put("at", it.readAt).put("driven", it.drivenSince) })
            .put("spoken", JSONObject().apply { spoken.forEach { (k, st) -> put(k.name, st.name) } })
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("state", o.toString())?.apply()
    }
}

/** Above this an odometer reading is garbage, not a car. */
/** An odometer reading above this is garbage (a CANbox frame misread); none is published or kept. */
internal const val MAX_ODOMETER_KM = 2_000_000
