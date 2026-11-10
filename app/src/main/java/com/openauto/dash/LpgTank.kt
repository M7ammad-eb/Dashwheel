package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import com.openauto.dash.vehicle.Hold
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The second tank: an LPG (autogas) tank the car's own gauge knows nothing
 * about. Its level is worked out from what was burned on LPG since the last
 * full fill. "Full" after each fill puts it back to 100 %; the litres pumped,
 * when given, teach it the car's real figures.
 *
 * What was burned, two ways: the kilometres at an average L/100 km, or the
 * petrol the engine computer asks for (its fuel rate or air flow over OBD:
 * the LPG system feeds the engine what the petrol injection would have) times
 * a factor, LPG litres per petrol litre, which follows city and motorway.
 *
 * Which fuel burns: most LPG systems start on petrol and switch over once the
 * engine is warm, so in Auto LPG counts once the OBD's coolant reading passes
 * the switch temperature, or, without an adapter, after the first kilometre of
 * each drive. Always and the driver's own switch cover the rest, and the
 * driver can say LPG is off (switched off at the dash) in any mode.
 */

enum class LpgMode { AUTO, ALWAYS, MANUAL }

/** How the LPG burned is worked out: kilometres at an average, or the engine's petrol demand times a factor. */
enum class LpgMethod { AVERAGE, ENGINE }

/** What the tank counts right now. */
enum class LpgStatus { LPG, WARMING, PETROL, LPG_OFF }

/** The driver's settings. [tankL] is what a full fill takes (an LPG tank fills to about 80 %). */
internal data class LpgSettings(
    val enabled: Boolean = false,
    val tankL: Double = 40.0,
    /** L/100 km typed in; null: the car's own consumption, a fifth more. */
    val useL100: Double? = null,
    /** A litre of LPG; null: the nearest station's price at each fill. */
    val pricePerL: Double? = null,
    val mode: LpgMode = LpgMode.AUTO,
    /** Coolant temperature at which the system switches to LPG (Auto). */
    val switchC: Int = 40,
    val method: LpgMethod = LpgMethod.AVERAGE,
    /** LPG litres per petrol litre typed in; null: [LpgRules.PETROL_TO_LPG] until the fills teach it. */
    val factor: Double? = null
)

/**
 * One full fill: since the one before (all 0 for the first), [kmBefore] driven
 * on LPG, [petrolBefore] petrol litres the engine asked for meanwhile,
 * [kmNoFlowBefore] of those kilometres without that reading, and [usedBefore]
 * litres the estimate said went; the [liters] pumped where told.
 */
internal data class LpgFill(
    val at: Long,
    val kmBefore: Double,
    val liters: Double? = null,
    val pricePerL: Double? = null,
    val petrolBefore: Double = 0.0,
    val kmNoFlowBefore: Double = 0.0,
    val usedBefore: Double = 0.0
)

internal data class LpgState(
    /** The last full fill; 0 until the first. */
    val filledAt: Long = 0L,
    /** Kilometres on LPG since the fill. */
    val kmSinceFill: Double = 0.0,
    /** Petrol litres the engine asked for while on LPG since the fill. */
    val petrolSinceFill: Double = 0.0,
    /** Kilometres on LPG since the fill without the engine's reading (no adapter, or not served). */
    val kmNoFlow: Double = 0.0,
    /** The driver's switch, in [LpgMode.MANUAL]. */
    val onLpg: Boolean = true,
    /** The driver said LPG is off (Auto, Always), until said again or the next fill. */
    val lpgOff: Boolean = false,
    /** The low level was said since the last fill. */
    val warned: Boolean = false,
    /** Newest first. */
    val fills: List<LpgFill> = emptyList()
)

/** What the tile shows. */
internal data class LpgReading(
    val percent: Int,
    val liters: Double,
    val rangeKm: Double,
    /** What the car burns on LPG now, L/100 km. */
    val useL100: Double,
    /** [useL100] (or the factor) comes from the litres pumped, not from a setting. */
    val learned: Boolean,
    val pricePerL: Double?,
    val kmSinceFill: Double,
    val filledAt: Long
) {
    val low: Boolean get() = percent <= LpgRules.LOW_PCT
    val costPer100: Double? get() = pricePerL?.let { it * useL100 }
}

/** The rules, pure so they're unit-tested. */
internal object LpgRules {
    /** Without a coolant reading, the first kilometre of a drive is on petrol. */
    const val WARMUP_KM = 1.0
    const val LOW_PCT = 15
    /**
     * Fills with their litres over this many kilometres before the car's own
     * figure replaces the setting: one ordinary refuel is enough, so the use
     * follows each fill by itself instead of waiting for several.
     */
    const val MIN_LEARN_KM = 50.0
    /** And over this much petrol asked for, before the factor is the car's own. */
    const val MIN_LEARN_PETROL_L = 5.0
    /** LPG burns about a fifth more than petrol, litre for litre. */
    const val PETROL_TO_LPG = 1.2
    /** More than this between two readings is the GPS jumping, not driving. */
    const val MAX_STEP_KM = 1.0
    /** Kilometres since the fill before the engine method's own L/100 km is shown rather than the average. */
    private const val MIN_SHOWN_KM = 10.0
    private const val LEARN_FILLS = 5
    private const val MAX_FILLS = 20

    /** What counts now: [coolantC] null without an adapter, [tripKm] into the drive. */
    fun status(s: LpgSettings, st: LpgState, coolantC: Int?, tripKm: Double): LpgStatus = when {
        s.mode == LpgMode.MANUAL -> if (st.onLpg) LpgStatus.LPG else LpgStatus.PETROL
        st.lpgOff -> LpgStatus.LPG_OFF
        s.mode == LpgMode.ALWAYS -> LpgStatus.LPG
        (if (coolantC != null) coolantC >= s.switchC else tripKm >= WARMUP_KM) -> LpgStatus.LPG
        else -> LpgStatus.WARMING
    }

    /** The driver's tap (tile or wheel button): LPG or petrol in Manual, LPG off or back on otherwise. */
    fun toggle(s: LpgSettings, st: LpgState): LpgState =
        if (s.mode == LpgMode.MANUAL) st.copy(onLpg = !st.onLpg) else st.copy(lpgOff = !st.lpgOff)

    /** The car's real use from the last full fills whose litres are known: each one put back what the kilometres before it burned. */
    fun learnedUse(fills: List<LpgFill>): Double? {
        val known = fills.filter { it.liters != null && it.kmBefore > 0 }.take(LEARN_FILLS)
        val km = known.sumOf { it.kmBefore }
        if (km < MIN_LEARN_KM) return null
        return (known.sumOf { it.liters ?: 0.0 } / km * 100).takeIf { it in 3.0..30.0 }
    }

    /** L/100 km and whether it was learned. */
    fun use(s: LpgSettings, fills: List<LpgFill>, petrolUse: Double): Pair<Double, Boolean> =
        learnedUse(fills)?.let { it to true } ?: ((s.useL100?.takeIf { it > 0 } ?: petrolUse * PETROL_TO_LPG) to false)

    /**
     * The car's own factor from the last full fills whose litres are known:
     * what was pumped, less what the kilometres without the engine's reading
     * took at [use], over the petrol the engine asked for.
     */
    fun learnedFactor(fills: List<LpgFill>, use: Double): Double? {
        val known = fills.filter { it.liters != null && it.petrolBefore > 0 }.take(LEARN_FILLS)
        val petrol = known.sumOf { it.petrolBefore }
        if (petrol < MIN_LEARN_PETROL_L) return null
        val lpg = known.sumOf { (it.liters ?: 0.0) - it.kmNoFlowBefore * use / 100 }
        return (lpg / petrol).takeIf { it in 0.8..2.0 }
    }

    /** LPG litres per petrol litre, and whether it was learned. */
    fun factor(s: LpgSettings, fills: List<LpgFill>, use: Double): Pair<Double, Boolean> =
        learnedFactor(fills, use)?.let { it to true } ?: ((s.factor?.takeIf { it > 0 } ?: PETROL_TO_LPG) to false)

    /** LPG litres burned since the fill. */
    fun used(s: LpgSettings, st: LpgState, petrolUse: Double): Double {
        val (use, _) = use(s, st.fills, petrolUse)
        return when (s.method) {
            LpgMethod.AVERAGE -> st.kmSinceFill * use / 100
            LpgMethod.ENGINE -> st.petrolSinceFill * factor(s, st.fills, use).first + st.kmNoFlow * use / 100
        }
    }

    /** The price to count with: the driver's, else the last one paid. */
    fun price(s: LpgSettings, fills: List<LpgFill>): Double? = s.pricePerL?.takeIf { it > 0 } ?: fills.firstNotNullOfOrNull { it.pricePerL }

    /** The tank now; null until the first full fill. */
    fun reading(s: LpgSettings, st: LpgState, petrolUse: Double): LpgReading? {
        if (st.filledAt == 0L) return null
        val (average, useLearned) = use(s, st.fills, petrolUse)
        val tank = s.tankL.coerceAtLeast(1.0)
        val used = used(s, st, petrolUse)
        val liters = (tank - used).coerceIn(0.0, tank)
        // With the engine's reading, the car's own L/100 km since the fill, once it has gone far enough to mean something.
        val engineUse = if (s.method == LpgMethod.ENGINE && st.kmSinceFill >= MIN_SHOWN_KM) (used / st.kmSinceFill * 100).takeIf { it in 3.0..30.0 } else null
        val use = engineUse ?: average
        return LpgReading(
            percent = (liters / tank * 100).roundToInt(),
            liters = liters,
            rangeKm = liters / use * 100,
            useL100 = use,
            learned = if (s.method == LpgMethod.ENGINE) factor(s, st.fills, average).second else useLearned,
            pricePerL = price(s, st.fills),
            kmSinceFill = st.kmSinceFill,
            filledAt = st.filledAt
        )
    }

    /** [km] more on LPG, with [petrolL] petrol litres the engine asked for, or null without that reading. */
    fun drove(st: LpgState, km: Double, petrolL: Double? = null): LpgState = st.copy(
        kmSinceFill = st.kmSinceFill + km,
        petrolSinceFill = st.petrolSinceFill + (petrolL ?: 0.0),
        kmNoFlow = st.kmNoFlow + if (petrolL == null) km else 0.0
    )

    /** A full fill at [now]: back to 100 %, LPG on again, and what went since the last one kept with it. */
    fun fill(s: LpgSettings, st: LpgState, now: Long, pricePerL: Double?, petrolUse: Double): LpgState {
        val first = st.filledAt == 0L
        val fill = LpgFill(
            at = now,
            kmBefore = if (first) 0.0 else st.kmSinceFill,
            pricePerL = pricePerL,
            petrolBefore = if (first) 0.0 else st.petrolSinceFill,
            kmNoFlowBefore = if (first) 0.0 else st.kmNoFlow,
            usedBefore = if (first) 0.0 else used(s, st, petrolUse).coerceAtMost(s.tankL)
        )
        return st.copy(
            filledAt = now, kmSinceFill = 0.0, petrolSinceFill = 0.0, kmNoFlow = 0.0,
            lpgOff = false, warned = false,
            fills = (listOf(fill) + st.fills).take(MAX_FILLS)
        )
    }

    /** The litres pumped at the last fill, told after the tap. */
    fun pumped(st: LpgState, liters: Double): LpgState =
        st.fills.firstOrNull()?.let { last -> st.copy(fills = listOf(last.copy(liters = liters.takeIf { it > 0 })) + st.fills.drop(1)) } ?: st

    /** What the estimate says the last fill put in: the starting suggestion for the litres pumped. */
    fun suggestedLiters(st: LpgState): Double = st.fills.firstOrNull()?.usedBefore ?: 0.0

    fun settingsJson(s: LpgSettings): JSONObject = JSONObject()
        .put("enabled", s.enabled).put("tank", s.tankL).putOpt("use", s.useL100).putOpt("price", s.pricePerL)
        .put("mode", s.mode.name).put("switch_c", s.switchC).put("method", s.method.name).putOpt("factor", s.factor)

    fun settingsFrom(o: JSONObject): LpgSettings = LpgSettings(
        enabled = o.optBoolean("enabled"),
        tankL = o.optDouble("tank", 40.0),
        useL100 = o.optNumber("use"),
        pricePerL = o.optNumber("price"),
        mode = runCatching { LpgMode.valueOf(o.optString("mode")) }.getOrDefault(LpgMode.AUTO),
        switchC = o.optInt("switch_c", 40),
        method = runCatching { LpgMethod.valueOf(o.optString("method")) }.getOrDefault(LpgMethod.AVERAGE),
        factor = o.optNumber("factor")
    )

    fun stateJson(st: LpgState): JSONObject = JSONObject()
        .put("filled_at", st.filledAt).put("km", st.kmSinceFill).put("petrol", st.petrolSinceFill).put("km_no_flow", st.kmNoFlow)
        .put("on_lpg", st.onLpg).put("lpg_off", st.lpgOff).put("warned", st.warned)
        .put("fills", JSONArray().apply {
            st.fills.forEach { f ->
                put(
                    JSONObject().put("at", f.at).put("km", f.kmBefore).putOpt("l", f.liters).putOpt("price", f.pricePerL)
                        .put("petrol", f.petrolBefore).put("km_no_flow", f.kmNoFlowBefore).put("used", f.usedBefore)
                )
            }
        })

    fun stateFrom(o: JSONObject): LpgState = LpgState(
        filledAt = o.optLong("filled_at"),
        kmSinceFill = o.optDouble("km", 0.0),
        petrolSinceFill = o.optDouble("petrol", 0.0),
        // Saved before the engine method: every kilometre was counted without it.
        kmNoFlow = o.optDouble("km_no_flow", o.optDouble("km", 0.0)),
        onLpg = o.optBoolean("on_lpg", true),
        lpgOff = o.optBoolean("lpg_off"),
        warned = o.optBoolean("warned"),
        fills = o.optJSONArray("fills")?.let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                LpgFill(
                    at = f.optLong("at"),
                    kmBefore = f.optDouble("km", 0.0),
                    liters = f.optNumber("l"),
                    pricePerL = f.optNumber("price"),
                    petrolBefore = f.optDouble("petrol", 0.0),
                    kmNoFlowBefore = f.optDouble("km_no_flow", f.optDouble("km", 0.0)),
                    usedBefore = f.optDouble("used", 0.0)
                )
            }
        }.orEmpty()
    )

    private fun JSONObject.optNumber(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
}

/** The live side: counts what burns on LPG, keeps the fills, says when the tank runs low. */
internal object LpgTank {
    private const val PREFS = "lpg_tank"
    private const val TICK_MS = 2_000L
    /** A longer gap between ticks (the process paused) adds no fuel. */
    private const val MAX_FLOW_STEP_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null

    private val _settings = MutableStateFlow(LpgSettings())
    val settings: StateFlow<LpgSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(LpgState())
    val state: StateFlow<LpgState> = _state.asStateFlow()

    private val _reading = MutableStateFlow<LpgReading?>(null)
    /** The tank now; null until the first full fill (or with the tank off). */
    val reading: StateFlow<LpgReading?> = _reading.asStateFlow()

    private val _status = MutableStateFlow(LpgStatus.WARMING)
    /** What counts right now, for the tile's LPG / petrol mark. */
    val status: StateFlow<LpgStatus> = _status.asStateFlow()

    private val _askPumped = MutableStateFlow(false)
    /**
     * The litres pumped at the last fill are still to be asked: set by every
     * Full (tile, Settings, wheel button) but the first, so the tile asks the
     * next time it's seen, until they're told or skipped.
     */
    val askPumped: StateFlow<Boolean> = _askPumped.asStateFlow()

    // The GPS trip last read: its distance grows as the car moves.
    private var tripStart = 0L
    private var tripM = 0.0
    private var savedTripStart = 0L
    private var savedTripM = 0.0
    private var unsavedKm = 0.0
    private var lastTickAt = 0L

    /** Starts once for the process, after [DriveLog] (which brings back the trip under way); main thread. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        _settings.value = p.getString("settings", null)?.let { runCatching { LpgRules.settingsFrom(JSONObject(it)) }.getOrNull() } ?: LpgSettings()
        _state.value = p.getString("state", null)?.let { runCatching { LpgRules.stateFrom(JSONObject(it)) }.getOrNull() } ?: LpgState()
        _askPumped.value = p.getBoolean("ask_pumped", false)
        // Where the counting stopped: a trip carried on after a restart isn't counted twice.
        tripStart = p.getLong("trip_start", 0L)
        tripM = p.getFloat("trip_m", 0f).toDouble()
        savedTripStart = tripStart
        savedTripM = tripM
        askFlow()
        refresh()
        scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        // A clock GPS cannot set mid-drive: the flow step is a difference between ticks.
        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceIn(0L, MAX_FLOW_STEP_MS)
        lastTickAt = now
        val trip = LocationFeed.trip.value
        val movedM = if (trip.startedAt == tripStart) trip.distanceM - tripM else trip.distanceM
        tripStart = trip.startedAt
        tripM = trip.distanceM
        if (DemoMode.isOn) {
            _reading.value = demo()
            _status.value = LpgStatus.LPG
            return
        }
        val s = _settings.value
        val st = _state.value
        val km = (movedM / 1000).takeIf { it > 0 && it <= LpgRules.MAX_STEP_KM } ?: 0.0
        val status = LpgRules.status(s, st, coolant(), trip.distanceM / 1000)
        _status.value = status
        if (s.enabled && st.filledAt != 0L && status == LpgStatus.LPG) {
            val flow = if (s.method == LpgMethod.ENGINE) flowLh() else null
            val petrol = flow?.let { it * dtMs / 3_600_000.0 }
            if (km > 0 || (petrol ?: 0.0) > 0) {
                _state.value = LpgRules.drove(st, km, petrol)
                // A litre of petrol stands for about 10 km here: saved as often.
                unsavedKm += km + (petrol ?: 0.0) * 10
            }
        }
        when {
            unsavedKm >= 0.1 -> save(saveState = true)
            tripStart != savedTripStart || abs(tripM - savedTripM) >= 100 -> save(saveState = false)
        }
        refresh()
        warnIfLow()
    }

    /** The coolant temperature while somebody reports one ([askFlow] keeps it asked for); null without. */
    private fun coolant(): Int? = Vehicle.bus.now(K.COOLANT_TEMP)?.roundToInt()

    /** The petrol the engine asks for while the adapter answers; null without that reading. */
    private fun flowLh(): Double? = Vehicle.bus.now(K.FUEL_FLOW)

    private var coolantHold: Hold? = null
    private var flowHold: Hold? = null

    /**
     * What the tank reads from the car, kept wanted on the vehicle bus for as long as it counts: the
     * coolant (its warm-up check) while it is on, and the fuel flow while it counts with the engine's.
     */
    private fun askFlow() {
        val s = _settings.value
        val coolant = s.enabled
        val flow = s.enabled && s.method == LpgMethod.ENGINE
        if (coolant && coolantHold == null) coolantHold = Vehicle.bus.hold(K.COOLANT_TEMP)
        else if (!coolant) coolantHold = coolantHold?.let { it.release(); null }
        if (flow && flowHold == null) flowHold = Vehicle.bus.hold(K.FUEL_FLOW)
        else if (!flow) flowHold = flowHold?.let { it.release(); null }
    }

    private fun refresh() {
        if (DemoMode.isOn) return
        val s = _settings.value
        _reading.value = if (s.enabled) LpgRules.reading(s, _state.value, CarProfileStore.current.typicalUse) else null
    }

    private fun warnIfLow() {
        val r = _reading.value ?: return
        val st = _state.value
        if (!r.low || st.warned) return
        _state.value = st.copy(warned = true)
        save(saveState = true)
        val app = appContext ?: return
        if (!SpokenEvents.isOn(app, SpokenEvent.LPG)) return
        val units = Units.current.value
        // Said aloud, so the unit is a full word in the right number, never "km".
        val left = units.distance(r.rangeKm).roundToInt()
        val text = AppLanguage.wrap(app).resources.getQuantityString(
            if (units.imperial) R.plurals.units_lpg_voice_low_mi else R.plurals.lpg_voice_low, left, left
        )
        CarVoice.announce(text, InAppNav.locale(app))
    }

    fun saveSettings(context: Context, s: LpgSettings) {
        appContext = appContext ?: context.applicationContext
        _settings.value = s
        prefs(context).edit().putString("settings", LpgRules.settingsJson(s).toString()).apply()
        askFlow()
        refreshStatus()
        refresh()
    }

    /** The tank was just filled to the brim: 100 %, at the nearest station's LPG price where it's known. */
    fun fillUp(context: Context) {
        val app = context.applicationContext
        val here = LocationFeed.location.value
        val station = here?.let { h -> FuelPriceRepo.stations.value?.let { FuelLogRules.stationAt(it, h.latitude, h.longitude, listOf(FuelGrade.GPLC)) } }
        val s = _settings.value
        // The first fill only starts the count: its litres teach nothing.
        val first = _state.value.filledAt == 0L
        _state.value = LpgRules.fill(s, _state.value, System.currentTimeMillis(), station?.second ?: s.pricePerL, CarProfileStore.current.typicalUse)
        save(app, saveState = true)
        setAskPumped(app, !first)
        refreshStatus()
        refresh()
    }

    /** The litres the pump showed for the last fill. */
    fun setPumped(context: Context, liters: Double) {
        _state.value = LpgRules.pumped(_state.value, liters)
        save(context.applicationContext, saveState = true)
        setAskPumped(context, false)
        refresh()
    }

    /** The litres pumped won't be told for this fill. */
    fun skipPumped(context: Context) = setAskPumped(context, false)

    private fun setAskPumped(context: Context, ask: Boolean) {
        _askPumped.value = ask
        prefs(context).edit().putBoolean("ask_pumped", ask).apply()
    }

    fun suggestedLiters(): Double = LpgRules.suggestedLiters(_state.value)

    /** The driver's tap on the LPG / petrol mark: returns what counts now. */
    fun toggle(context: Context): LpgStatus {
        _state.value = LpgRules.toggle(_settings.value, _state.value)
        save(context.applicationContext, saveState = true)
        return refreshStatus()
    }

    private fun refreshStatus(): LpgStatus {
        val status = LpgRules.status(_settings.value, _state.value, coolant(), LocationFeed.trip.value.distanceM / 1000)
        _status.value = status
        return status
    }

    /** "Tank full" from a steering wheel button: done and said. */
    fun fillUpHandsFree(context: Context) {
        if (!_settings.value.enabled) {
            HandsFree.say(context, R.string.lpg_voice_off)
            return
        }
        fillUp(context)
        HandsFree.say(context, R.string.lpg_voice_full)
    }

    /** LPG or petrol from a steering wheel button: the same as a tap on the tile's mark, said. */
    fun switchHandsFree(context: Context) {
        if (!_settings.value.enabled) {
            HandsFree.say(context, R.string.lpg_voice_off)
            return
        }
        HandsFree.say(
            context,
            when (toggle(context)) {
                LpgStatus.LPG -> R.string.lpg_voice_on_lpg
                LpgStatus.WARMING -> R.string.lpg_voice_auto
                LpgStatus.PETROL -> R.string.lpg_voice_on_petrol
                LpgStatus.LPG_OFF -> R.string.lpg_voice_lpg_off
            }
        )
    }

    /** A tank half used on a made-up drive; nothing saved. */
    private fun demo(): LpgReading {
        val minute = (System.currentTimeMillis() / 60_000 % 30).toInt()
        val liters = 24.0 - minute * 0.1
        return LpgReading(
            percent = (liters / 40 * 100).roundToInt(), liters = liters, rangeKm = liters / 9.5 * 100, useL100 = 9.5, learned = true,
            pricePerL = 0.99, kmSinceFill = 170.0 + minute, filledAt = System.currentTimeMillis() - 3 * 86_400_000L
        )
    }

    private fun save(context: Context? = appContext, saveState: Boolean) {
        val app = context ?: return
        val edit = prefs(app).edit().putLong("trip_start", tripStart).putFloat("trip_m", tripM.toFloat())
        savedTripStart = tripStart
        savedTripM = tripM
        if (saveState) {
            edit.putString("state", LpgRules.stateJson(_state.value).toString())
            unsavedKm = 0.0
        }
        edit.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
