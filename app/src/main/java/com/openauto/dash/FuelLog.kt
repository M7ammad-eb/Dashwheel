package com.openauto.dash

import android.os.SystemClock
import com.openauto.dash.vehicle.fuelInfoNow
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.K
import android.content.Context
import com.openauto.dash.link.FuelFill
import com.openauto.dash.link.FuelFills
import com.openauto.dash.link.FuelReport
import com.openauto.dash.link.FuelSync
import com.openauto.dash.link.LinkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The fuel log: each refuel, noticed by the fuel level jumping up, with the
 * litres it took, the price at the pump where the fuel-price feed knows the
 * station, and the mileage then. From them come the car's real consumption
 * (which the drive log then uses instead of the profile's guess) and what the
 * fuel costs; the companion shows both. Nothing to type: the level is read
 * from the CANbox or the OBD as the fuel gauge tiles read it.
 */

/** The rules, pure so they're unit-tested. */
internal object FuelLogRules {
    /** A rise smaller than this is the gauge moving on a slope, not a refuel. */
    const val MIN_RISE_PCT = 12
    const val MIN_LITERS = 5.0
    /** Readings this close count as the same level. */
    private const val SAME_PCT = 2
    /** A level is trusted once read this many times, over [SETTLE_MS]: the gauge settles after the engine starts. */
    const val SETTLE_READS = 3
    const val SETTLE_MS = 20_000L

    /**
     * [baseline]: the last settled level; [candidate]: the level being read
     * now, since [since], [reads] times.
     */
    data class Gauge(val baseline: Int? = null, val candidate: Int? = null, val since: Long = 0, val reads: Int = 0)

    /** A refuel: the level went from [from] to [to] percent. */
    data class Rise(val from: Int, val to: Int)

    /** One reading of the level ([percent]) at [now]: the gauge after it, and a refuel when this settled level is one. */
    fun step(g: Gauge, percent: Int, now: Long): Pair<Gauge, Rise?> {
        val same = g.candidate != null && abs(percent - g.candidate) <= SAME_PCT
        val next = if (same) g.copy(reads = g.reads + 1) else g.copy(candidate = percent, since = now, reads = 1)
        val settled = next.candidate ?: return next to null
        if (next.reads < SETTLE_READS || now - next.since < SETTLE_MS) return next to null
        val base = next.baseline ?: return next.copy(baseline = settled) to null
        if (settled == base) return next to null
        val rise = if (settled - base >= MIN_RISE_PCT) Rise(base, settled) else null
        return next.copy(baseline = settled) to rise
    }

    fun liters(rise: Rise, tankL: Double): Double = (rise.to - rise.from) / 100.0 * tankL

    /** The pump price where the car stands: the nearest station within [RADIUS_KM] that sells [grade]. */
    fun stationAt(stations: List<FuelStation>, lat: Double, lng: Double, grades: List<FuelGrade>): Pair<FuelStation, Double>? =
        stations.asSequence()
            .mapNotNull { s -> grades.firstNotNullOfOrNull { s.prices[it] }?.let { s to it } }
            .map { it to it.first.distanceKm(lat, lng) }
            .filter { it.second <= RADIUS_KM }
            .minByOrNull { it.second }?.first

    private const val RADIUS_KM = 0.3
}

/** The live side: reads the level, logs each refuel, and reports to the phone. */
internal object FuelLog {
    private const val PREFS = "fuel_log"
    private const val TICK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var gauge = FuelLogRules.Gauge()

    private val _fills = MutableStateFlow<List<FuelFill>>(emptyList())
    /** The refuels logged, newest first. */
    val fills: StateFlow<List<FuelFill>> = _fills.asStateFlow()

    private val reports = MutableSharedFlow<FuelFill>(extraBufferCapacity = 8)

    /** Starts once for the process; main thread. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        _fills.value = p.getString("fills", null)?.let { FuelFills.decode(it) }.orEmpty()
        // The level before the unit slept: the refuel happens with the engine off.
        gauge = FuelLogRules.Gauge(baseline = p.getInt("baseline", -1).takeIf { it >= 0 })
        scope.launch { follow() }
    }

    /** The car's real consumption from its refuels, or null until there are enough. */
    fun litersPer100(): Double? = FuelFills.litersPer100(_fills.value)

    /** The price paid at the last refuel, where it is known. */
    fun lastPrice(): Double? = FuelFills.lastPrice(_fills.value)

    /** What a litre costs now: the last price paid, else the driver's figure in "My car". */
    fun price(car: CarProfile): Double = lastPrice() ?: car.fuelPrice

    /** The driver says what they paid a litre at the refuel made at [at] (here, or from the phone). */
    fun setPrice(context: Context, at: Long, pricePerL: Double) {
        if (pricePerL <= 0) return
        val fills = FuelFills.priced(_fills.value, at, pricePerL) ?: return
        save(context.applicationContext, fills)
        fills.firstOrNull { it.at == at }?.let { reports.tryEmit(it) }
    }

    /** Runs for as long as the link is up (cancel it when the link ends), handing each message to [send]. */
    suspend fun report(send: (LinkMessage) -> Unit) {
        if (_fills.value.isNotEmpty()) send(FuelSync(_fills.value))
        reports.collect { send(FuelReport(it)) }
    }

    private suspend fun follow() {
        // The fuel level is read by snapshot below: the key stays wanted while the loop runs.
        val hold = Vehicle.bus.hold(K.FUEL_LEVEL)
        try {
            while (true) {
                delay(TICK_MS)
                if (DemoMode.isOn) continue
                val info = fuelInfoNow() ?: continue
                val before = gauge.baseline
                // The settle time is a difference between reads: a clock GPS sets mid-drive would cut it or stretch it.
                val (next, rise) = FuelLogRules.step(gauge, info.percent, SystemClock.elapsedRealtime())
                gauge = next
                if (next.baseline != before) appContext?.let { prefs(it).edit().putInt("baseline", next.baseline ?: -1).apply() }
                if (rise != null) logRise(rise, info)
            }
        } finally {
            hold.release()
        }
    }

    private fun logRise(rise: FuelLogRules.Rise, info: FuelInfo) {
        val app = appContext ?: return
        val car = CarProfileStore.current
        val liters = FuelLogRules.liters(rise, info.tankL)
        if (liters < FuelLogRules.MIN_LITERS) return
        val here = LocationFeed.location.value
        val station = here?.let { FuelPriceRepo.stations.value?.let { s -> FuelLogRules.stationAt(s, it.latitude, it.longitude, FuelPrices.gradesFor(car)) } }
        // Away from a station the feed knows, prices change too often for a fixed
        // figure: the last price paid stands in until the driver gives this one.
        val fill = FuelFill(
            at = System.currentTimeMillis(),
            liters = liters,
            pricePerL = station?.second ?: price(car),
            currency = if (station != null) FuelPrices.CURRENCY else car.currency,
            odometerKm = Maintenance.state.value.odometer?.nowKm,
            station = station?.first?.label,
            estimated = info.percentEstimated,
            priceGuessed = station == null
        )
        record(app, fill)
    }

    /** A refuel the driver typed in (the gauge missed it, or the price was not the station's). */
    fun addManual(context: Context, liters: Double, pricePerL: Double?) {
        val car = CarProfileStore.current
        record(
            context.applicationContext,
            FuelFill(
                at = System.currentTimeMillis(),
                liters = liters,
                pricePerL = pricePerL,
                currency = car.currency,
                odometerKm = Maintenance.state.value.odometer?.nowKm
            )
        )
    }

    private fun record(app: Context, fill: FuelFill) {
        save(app, FuelFills.merge(_fills.value, fill))
        reports.tryEmit(fill)
    }

    private fun save(app: Context, fills: List<FuelFill>) {
        _fills.value = fills
        prefs(app).edit().putString("fills", FuelFills.encode(fills)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
