package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openauto.dash.vehicle.Gear as VehicleGear
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.VehicleBus
import com.openauto.dash.vehicle.estimateWhileWanted
import com.openauto.dash.vehicle.measured
import com.openauto.dash.vehicle.speedKmhFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/*
 * The gear the car is in, as the bus has it (K.GEAR). A car source that says it wins; none does yet, so it is
 * the estimate of `computed.gear` ([startGearEstimate]): reverse comes from the
 * head unit's reversing signal (the camera the ROM brings up), and the forward
 * gears from the engine's revs against the road speed, each gear turning the
 * engine a set number of rpm per km/h. The table starts from the car's gearbox
 * (the C4 Picasso's BMP6 figures, else a typical six-speed spread) and settles
 * on the car's own ratios as it drives. Rolling with the engine at idle and no
 * gear matching reads as free-wheeling (neutral or the clutch open). A robotised
 * box's A/M mode is not in any feed Dashwheel reads.
 */

internal sealed interface Gear {
    data object Reverse : Gear
    /** Forward gear [n], from 1. */
    data class Forward(val n: Int) : Gear
    /** Moving with no gear engaged: neutral, or the clutch open. */
    data object Free : Gear
    data object Stopped : Gear
    /** No revs or no speed to tell by. */
    data object Unknown : Gear
}

internal data class GearReading(val gear: Gear, val rpm: Int?, val speedKmh: Int?, val gears: Int)

internal object GearEstimator {

    private const val PREFS = "gear_ratios"
    private const val KEY = "rpm_per_kmh"

    /** Below this, the car is standing (the gear can't be told). */
    const val STOPPED_KMH = 4

    /** Rpm per km/h within this share of a gear's figure reads as that gear. */
    private const val MATCH = 0.12

    /** Tighter still for a reading that teaches the table. */
    private const val LEARN_MATCH = 0.07

    /** How far one reading moves the table. */
    private const val LEARN_RATE = 0.02

    /** Revs at or under this with no gear matching: free-wheeling. */
    private const val IDLE_RPM = 1_100

    /** The C4 Picasso 1.6 HDi's BMP6, rpm per km/h in gears 1 to 6. */
    private val BMP6 = listOf(118.0, 64.0, 41.0, 30.0, 23.0, 19.0)

    /** The first table for a box of [gears]: the BMP6's for six gears, else a spread between the same ends. */
    fun seeds(gears: Int): List<Double> {
        val n = gears.coerceIn(4, 9)
        if (n == BMP6.size) return BMP6
        val first = BMP6.first()
        val last = BMP6.last()
        return List(n) { i -> first * (last / first).pow(i / (n - 1.0)) }
    }

    /** The gear [rpm] at [speedKmh] points to in [table]; null when none is close. */
    fun classify(rpm: Int, speedKmh: Int, table: List<Double>): Int? {
        if (speedKmh < STOPPED_KMH || rpm <= 0) return null
        val r = rpm.toDouble() / speedKmh
        val (i, off) = table.withIndex().map { (i, c) -> i to abs(ln(r / c)) }.minByOrNull { it.second } ?: return null
        return if (off <= MATCH) i + 1 else null
    }

    /** [table] nudged towards a steady reading in gear [gear], kept in order (each gear shorter than the one before). */
    fun learn(table: List<Double>, gear: Int, rpm: Int, speedKmh: Int): List<Double> {
        if (speedKmh < 15 || gear !in 1..table.size) return table
        val r = rpm.toDouble() / speedKmh
        val c = table[gear - 1]
        if (abs(ln(r / c)) > LEARN_MATCH) return table
        val next = table.toMutableList().also { it[gear - 1] = c + (r - c) * LEARN_RATE }
        val ordered = next.zipWithNext().all { (a, b) -> a > b }
        return if (ordered) next else table
    }

    /** What to show for one moment of the feeds. */
    fun read(reversing: Boolean, rpm: Int?, speedKmh: Int?, table: List<Double>): Gear = when {
        reversing -> Gear.Reverse
        speedKmh == null -> Gear.Unknown
        speedKmh < STOPPED_KMH -> Gear.Stopped
        rpm == null || rpm <= 0 -> Gear.Unknown
        else -> classify(rpm, speedKmh, table)?.let { Gear.Forward(it) } ?: if (rpm <= IDLE_RPM) Gear.Free else Gear.Unknown
    }

    private var table: List<Double>? = null

    fun table(context: Context, gears: Int): List<Double> {
        table?.takeIf { it.size == gears.coerceIn(4, 9) }?.let { return it }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == gears.coerceIn(4, 9) }
        return (saved ?: seeds(gears)).also { table = it }
    }

    fun save(context: Context, next: List<Double>) {
        table = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(",")).apply()
    }
}

/**
 * The gear estimate over time: [GearEstimator.read] on each moment of the feeds, the table nudged towards the
 * car's own ratios while driving ([onSave] is told of the table now and then), and a moment between gears (the
 * clutch slipping mid-shift) keeping the last gear for [holdMs] rather than flickering to nothing. Pure but for
 * [onSave]; the clock is the caller's.
 */
internal class GearTracker(
    private var table: List<Double>,
    private val learning: () -> Boolean = { true },
    private val holdMs: Long = HOLD_MS,
    private val saveEvery: Int = SAVE_EVERY,
    private val onSave: (List<Double>) -> Unit = {}
) {
    private var lastGear: Gear = Gear.Unknown
    private var lastGearAt = 0L
    private var learned = 0

    /** The gear for this moment as the bus's [VehicleGear]; null when it can't be told (standing, no revs or no speed). */
    fun step(reversing: Boolean, rpm: Int?, speedKmh: Int?, now: Long): VehicleGear? {
        var gear = GearEstimator.read(reversing, rpm, speedKmh, table)
        if (gear is Gear.Forward && rpm != null && speedKmh != null && learning()) {
            table = GearEstimator.learn(table, gear.n, rpm, speedKmh)
            // Kept now and then, not at every reading.
            if (++learned % saveEvery == 0) onSave(table)
        }
        if (gear is Gear.Forward || gear is Gear.Reverse) {
            lastGear = gear
            lastGearAt = now
        } else if (gear is Gear.Unknown && now - lastGearAt < holdMs) {
            gear = lastGear
        }
        return vehicleGearOf(gear)
    }

    companion object {
        const val HOLD_MS = 1_500L
        const val SAVE_EVERY = 200
    }
}

/** An estimated gear as the bus has it: no "stopped" and no "unknown" there, those are a missing value. */
internal fun vehicleGearOf(gear: Gear): VehicleGear? = when (gear) {
    Gear.Reverse -> VehicleGear.Reverse
    is Gear.Forward -> VehicleGear.Forward(gear.n)
    Gear.Free -> VehicleGear.Neutral
    Gear.Stopped, Gear.Unknown -> null
}

/** The gear for a tile: what the bus has, and where it has none, standing or not told. */
internal fun gearOf(gear: VehicleGear?, speedKmh: Int?): Gear = when (gear) {
    VehicleGear.Reverse -> Gear.Reverse
    is VehicleGear.Forward -> Gear.Forward(gear.n)
    VehicleGear.Neutral -> Gear.Free
    VehicleGear.Park -> Gear.Stopped
    // A car that says "drive" without a number has no gear to put in a gate.
    VehicleGear.Drive -> Gear.Unknown
    null -> if (speedKmh != null && speedKmh < GearEstimator.STOPPED_KMH) Gear.Stopped else Gear.Unknown
}

/** The gear estimate over [rpm], the car's [speed] and reverse, for [tracker]; collecting it asks for all three. */
internal fun gearEstimateFlow(bus: VehicleBus, tracker: GearTracker, clock: () -> Long = System::currentTimeMillis): Flow<VehicleGear?> {
    // The revs and the speed are the car's: never an estimate's.
    val rpm = bus.measured(K.RPM).map { it?.value?.roundToInt() }.distinctUntilChanged()
    val speed = bus.measured(K.VEHICLE_SPEED).map { it?.value?.roundToInt() }.distinctUntilChanged()
    val reverse = bus.value(K.REVERSE).map { it == true }.distinctUntilChanged()
    return combine(rpm, reverse, speed) { r, reversing, s -> tracker.step(reversing, r, s, clock()) }
}

/**
 * `computed.gear`: GEAR from the revs against the road speed, while somebody wants the gear and no car source says
 * it. The table starts from the car's gearbox and settles on its own ratios as it drives ([GearEstimator.table]),
 * as it did when the tiles estimated by themselves; it follows the car profile's number of gears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun startGearEstimate(context: Context, bus: VehicleBus, scope: CoroutineScope): Job {
    val out = bus.register(Source("computed.gear", SourceKind.COMPUTED, "Gear from revs and speed"))
    return estimateWhileWanted(scope, bus, out, K.GEAR) {
        CarProfileStore.profile.map { it.gears ?: 6 }.distinctUntilChanged().flatMapLatest { gears ->
            val tracker = GearTracker(
                GearEstimator.table(context, gears),
                learning = { !DemoMode.isOn },
                onSave = { GearEstimator.save(context, it) }
            )
            gearEstimateFlow(bus, tracker)
        }
    }
}

/**
 * The gear, followed while the tile shows: the bus's GEAR (a car source when one has it, else the estimate of
 * `computed.gear`, which only runs because this asks), with the revs and the speed beside it.
 */
@Composable
internal fun rememberGear(): State<GearReading> {
    val context = LocalContext.current
    // As state: a gear count fetched or typed while the tile shows is taken up at once.
    val gears = CarProfileStore.profile.collectAsState().value.gears ?: 6
    val state = remember { mutableStateOf(GearReading(Gear.Unknown, null, null, gears)) }
    LaunchedEffect(Unit) { CarBox.start(context) }
    LaunchedEffect(gears) {
        // The revs are whatever the bus resolves: the car app's or a unit bus's, else the adapter's.
        val rpmFlow = Vehicle.bus.value(K.RPM).map { it?.roundToInt() }.distinctUntilChanged()
        combine(Vehicle.bus.value(K.GEAR), rpmFlow, speedKmhFlow(K.VEHICLE_SPEED)) { gear, rpm, speed ->
            GearReading(gearOf(gear, speed), rpm, speed, gears)
        }.collect { state.value = it }
    }
    return state
}
