package com.openauto.dash

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Speeds and distances: km/h and km, or mph and miles. */
enum class DistanceChoice { AUTO, METRIC, IMPERIAL }

enum class TempChoice { AUTO, CELSIUS, FAHRENHEIT }

enum class EconomyChoice { AUTO, L_PER_100KM, MPG_UK, MPG_US, KM_PER_L }

/** Auto follows the head unit's own 24-hour switch. */
enum class ClockChoice { AUTO, H24, H12 }

/** Fuel economy as shown; [EconomyChoice] with Auto resolved. */
enum class Economy { L_PER_100KM, MPG_UK, MPG_US, KM_PER_L }

/** What the driver picked in Settings, Auto included. */
data class UnitChoices(
    val distance: DistanceChoice = DistanceChoice.AUTO,
    val temp: TempChoice = TempChoice.AUTO,
    val economy: EconomyChoice = EconomyChoice.AUTO,
    val clock: ClockChoice = ClockChoice.AUTO
)

/**
 * The units every readout uses, Auto resolved. Everything Dashwheel keeps is
 * metric (km/h, km, °C, L/100 km): these convert only on the way to the
 * screen or the voice, so thresholds and saved values never change with them.
 * The metric side returns its input untouched, so a metric driver sees
 * exactly the figures the app showed before units existed.
 */
data class UnitSystem(
    val imperial: Boolean = false,
    val fahrenheit: Boolean = false,
    val economy: Economy = Economy.L_PER_100KM,
    val clock24: Boolean = true
) {
    // Russian writes its units in Cyrillic (км/ч); the Latin "KM/H" reads as the wrong letters there.
    val speedUnit: String get() = if (imperial) (if (cyrillic) "миль/ч" else "mph") else if (cyrillic) "км/ч" else "km/h"
    val distanceUnit: String get() = if (imperial) (if (cyrillic) "миль" else "mi") else if (cyrillic) "км" else "km"
    val tempUnit: String get() = if (fahrenheit) "°F" else "°C"
    val economyUnit: String
        get() = when (economy) {
            Economy.L_PER_100KM -> if (cyrillic) "л/100 км" else "L/100 km"
            Economy.MPG_UK, Economy.MPG_US -> "mpg"
            Economy.KM_PER_L -> if (cyrillic) "км/л" else "km/L"
        }

    /** The app is in Russian (AppLanguage keeps the default locale on the app's language). */
    private val cyrillic: Boolean get() = Locale.getDefault().language == "ru"

    fun speed(kmh: Int): Int = if (imperial) (kmh / KM_PER_MILE).roundToInt() else kmh
    fun speed(kmh: Float): Int = (if (imperial) kmh / KM_PER_MILE.toFloat() else kmh).roundToInt()
    fun speed(kmh: Double): Int = (if (imperial) kmh / KM_PER_MILE else kmh).roundToInt()

    /** A speed in the shown unit back to km/h, for dial scales drawn in km/h. */
    fun kmh(shown: Float): Float = if (imperial) shown * KM_PER_MILE.toFloat() else shown

    /**
     * The top of a speed dial made for [maxKmh], in the shown unit: the same
     * figure in km/h, rounded up to the next ten in mph (240 km/h reads 150).
     */
    fun speedScale(maxKmh: Int): Int =
        if (imperial) (ceil(maxKmh / KM_PER_MILE / 10) * 10).toInt() else maxKmh

    fun distance(km: Int): Int = if (imperial) (km / KM_PER_MILE).roundToInt() else km
    fun distance(km: Double): Double = if (imperial) km / KM_PER_MILE else km
    fun distance(km: Float): Float = if (imperial) km / KM_PER_MILE.toFloat() else km

    /** A distance typed in the shown unit, in km for storage. */
    fun km(shown: Double): Double = if (imperial) shown * KM_PER_MILE else shown

    /** A volume of [l] litres in the driver's units: gallons when the economy is in mpg (US or UK), else litres. */
    fun volume(l: Double): Double = when (economy) {
        Economy.MPG_US -> l / LITRES_PER_US_GALLON
        Economy.MPG_UK -> l / LITRES_PER_UK_GALLON
        else -> l
    }
    val volumeUnit: String get() = when (economy) {
        Economy.MPG_US, Economy.MPG_UK -> "gal"
        else -> if (cyrillic) "л" else "L"
    }
    /** "42 L", "11 gal": a tank's contents in the driver's units (the mpg driver saw litres beside their miles). */
    fun volumeText(l: Double): String = String.format(Locale.getDefault(), "%.0f %s", volume(l), volumeUnit)

    fun temp(c: Int): Int = if (fahrenheit) (c * 1.8 + 32).roundToInt() else c
    fun temp(c: Double): Int = (if (fahrenheit) c * 1.8 + 32 else c).roundToInt()
    fun temp(c: Float): Int = temp(c.toDouble())

    /** [c] in the shown scale, unrounded, for readouts that format it themselves. */
    fun tempExact(c: Double): Double = if (fahrenheit) c * 1.8 + 32 else c

    /**
     * [l100] in the shown unit; null where the inverse units have no answer
     * (coasting burns nothing: infinite mpg).
     */
    fun economy(l100: Double): Double? = when (economy) {
        Economy.L_PER_100KM -> l100
        else -> if (l100 < 0.1) null else when (economy) {
            Economy.MPG_UK -> MPG_UK_FACTOR / l100
            Economy.MPG_US -> MPG_US_FACTOR / l100
            else -> 100.0 / l100
        }
    }

    fun speedText(kmh: Int): String = "${speed(kmh)} $speedUnit"
    fun speedText(kmh: Float): String = "${speed(kmh)} $speedUnit"
    fun speedText(kmh: Double): String = "${speed(kmh)} $speedUnit"
    fun distanceText(km: Int): String = "${distance(km)} $distanceUnit"
    fun tempText(c: Int): String = "${temp(c)} $tempUnit"
    fun tempText(c: Double): String = "${temp(c)} $tempUnit"

    /** "54°C" with no space, as the LCD-style readouts write it. */
    fun tempTight(c: Int): String = "${temp(c)}$tempUnit"
    fun tempTight(c: Double): String = "${temp(c)}$tempUnit"

    /** "6.4 L/100 km", "44.1 mpg"; "--" with the unit when there is no figure. */
    fun economyText(l100: Double, locale: Locale = Locale.getDefault()): String =
        "${economy(l100)?.let { String.format(locale, "%.1f", it) } ?: "--"} $economyUnit"

    /**
     * A short distance given in metres: "350 m" / "1.2 km", or "500 ft" under
     * a tenth of a mile then "0.4 mi".
     */
    fun shortDistance(m: Double, locale: Locale = Locale.getDefault()): String = when {
        !imperial -> if (m < 1000) "${m.roundToInt()} m" else String.format(locale, "%.1f km", m / 1000)
        m < FEET_LIMIT_M -> "${(m / METRES_PER_FOOT / 10).roundToInt() * 10} ft"
        else -> String.format(locale, "%.1f mi", m / 1000 / KM_PER_MILE)
    }

    /** A height or a margin given in metres: "312 m", or "1024 ft" where distances are in miles. */
    fun heightText(m: Double): String = if (imperial) "${(m / METRES_PER_FOOT).roundToInt()} ft" else "${m.roundToInt()} m"

    /** "14:05", or "2:05 PM" on a 12-hour clock. */
    fun timePattern(): String = if (clock24) "HH:mm" else "h:mm a"

    /** The hours and minutes alone, for clocks that draw AM / PM apart ([amPm]). */
    fun digitsPattern(): String = if (clock24) "HH:mm" else "h:mm"

    fun timeFormat(locale: Locale = Locale.getDefault()): SimpleDateFormat = SimpleDateFormat(timePattern(), locale)

    fun time(date: Date, locale: Locale = Locale.getDefault()): String = timeFormat(locale).format(date)

    /** "AM" / "PM" for [date] on a 12-hour clock, null on a 24-hour one. */
    fun amPm(date: Date, locale: Locale = Locale.getDefault()): String? =
        if (clock24) null else SimpleDateFormat("a", locale).format(date)

    companion object {
        const val KM_PER_MILE = 1.609344
        const val METRES_PER_FOOT = 0.3048
        private const val MPG_UK_FACTOR = 282.481
        private const val LITRES_PER_US_GALLON = 3.785411784
        private const val LITRES_PER_UK_GALLON = 4.54609
        private const val MPG_US_FACTOR = 235.215

        /** Under a tenth of a mile a distance reads in feet, as road signs and maps do. */
        private const val FEET_LIMIT_M = 160.9344
    }
}

/**
 * The driver's units and clock (Settings, Display). Auto takes them from the
 * head unit's region: the United States drive in mph, °F and US gallons, the
 * United Kingdom in mph and miles with °C and imperial gallons, everyone else
 * metric (see [country] for when the region counts). The clock's Auto follows
 * the unit's own 24-hour switch.
 */
object Units {
    private const val PREFS = "units"
    private const val KEY_DISTANCE = "distance"
    private const val KEY_TEMP = "temp"
    private const val KEY_ECONOMY = "economy"
    private const val KEY_CLOCK = "clock"

    private val _choices = MutableStateFlow(UnitChoices())
    val choices: StateFlow<UnitChoices> = _choices.asStateFlow()

    // Metric and 24-hour until [load] has read the driver's choice (the
    // launcher does so first thing), which keeps JVM tests off the machine's locale.
    private val _current = MutableStateFlow(UnitSystem())
    val current: StateFlow<UnitSystem> = _current.asStateFlow()

    /** The head unit's 24-hour switch as last read ([refresh]). */
    private var system24: Boolean? = null
    private var loaded = false

    fun load(context: Context) {
        if (!loaded) {
            loaded = true
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains(KEY_DISTANCE) && upgrading(context)) {
                // Dashwheel was set up before units existed: it keeps what it showed
                // (km/h, °C, L/100 km, 24 h). A head unit left in US English would
                // otherwise switch a French driver to mph and a 12-hour clock overnight.
                save(context, UPGRADE_CHOICES)
            } else {
                _choices.value = UnitChoices(
                    distance = enumOr(p.getString(KEY_DISTANCE, null), DistanceChoice.AUTO),
                    temp = enumOr(p.getString(KEY_TEMP, null), TempChoice.AUTO),
                    economy = enumOr(p.getString(KEY_ECONOMY, null), EconomyChoice.AUTO),
                    clock = enumOr(p.getString(KEY_CLOCK, null), ClockChoice.AUTO)
                )
            }
        }
        refresh(context)
    }

    /** What an install from before the units setting keeps: exactly what it showed. */
    internal val UPGRADE_CHOICES = UnitChoices(DistanceChoice.METRIC, TempChoice.CELSIUS, EconomyChoice.L_PER_100KM, ClockChoice.H24)

    /** Whether this is an update of a Dashwheel already in use (its dashboards or its setup were saved), not a fresh install. */
    private fun upgrading(context: Context): Boolean =
        listOf("dashboard_layout_prefs", "setup").any { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()
        }

    /** Reads the unit's 24-hour switch again: it can change in its own settings while Dashwheel sits behind. */
    fun refresh(context: Context) {
        system24 = runCatching { DateFormat.is24HourFormat(context) }.getOrNull()
        _current.value = resolve(_choices.value, country(), system24)
    }

    fun save(context: Context, choices: UnitChoices) {
        _choices.value = choices
        _current.value = resolve(choices, country(), system24)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISTANCE, choices.distance.name)
            .putString(KEY_TEMP, choices.temp.name)
            .putString(KEY_ECONOMY, choices.economy.name)
            .putString(KEY_CLOCK, choices.clock.name)
            .apply()
    }

    /** What Auto gives here, for the hint under the switches. */
    fun auto(): UnitSystem = resolve(UnitChoices(), country(), system24)

    /**
     * The system's region (a language picked in Dashwheel keeps the unit's
     * country, AppLanguage), counted only for an English-speaking driver: head
     * units often leave the factory set to en_US, and a French driver who never
     * changed that must not wake up to mph. Anyone else reads as metric.
     */
    private fun country(): String {
        val region = runCatching { AppLanguage.systemLocale().country }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country
        return autoCountry(region, Locale.getDefault().language)
    }

    /** [region] when the driver reads English, else none: see [country]. */
    internal fun autoCountry(region: String, language: String): String = if (language == "en") region else ""

    /** What Auto means in [country] (ISO 3166 code); [system24] is the unit's 24-hour switch, null when unknown. */
    internal fun defaultsFor(country: String, system24: Boolean?): UnitSystem {
        val c = country.uppercase(Locale.ROOT)
        return when (c) {
            "US" -> UnitSystem(imperial = true, fahrenheit = true, economy = Economy.MPG_US, clock24 = system24 ?: false)
            "GB" -> UnitSystem(imperial = true, fahrenheit = false, economy = Economy.MPG_UK, clock24 = system24 ?: true)
            else -> UnitSystem(imperial = false, fahrenheit = false, economy = Economy.L_PER_100KM, clock24 = system24 ?: true)
        }
    }

    internal fun resolve(choices: UnitChoices, country: String, system24: Boolean?): UnitSystem {
        val auto = defaultsFor(country, system24)
        return UnitSystem(
            imperial = when (choices.distance) {
                DistanceChoice.AUTO -> auto.imperial
                DistanceChoice.METRIC -> false
                DistanceChoice.IMPERIAL -> true
            },
            fahrenheit = when (choices.temp) {
                TempChoice.AUTO -> auto.fahrenheit
                TempChoice.CELSIUS -> false
                TempChoice.FAHRENHEIT -> true
            },
            economy = when (choices.economy) {
                EconomyChoice.AUTO -> auto.economy
                EconomyChoice.L_PER_100KM -> Economy.L_PER_100KM
                EconomyChoice.MPG_UK -> Economy.MPG_UK
                EconomyChoice.MPG_US -> Economy.MPG_US
                EconomyChoice.KM_PER_L -> Economy.KM_PER_L
            },
            clock24 = when (choices.clock) {
                ClockChoice.AUTO -> auto.clock24
                ClockChoice.H24 -> true
                ClockChoice.H12 -> false
            }
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}

/**
 * The speed correction's value ([SpeedCorrection]): "+3 km/h", "−2 km/h", or
 * "0 km/h" when there's none. In mph to a tenth: its km/h step is 0.6 mph.
 */
internal fun speedOffsetText(offsetKmh: Int, units: UnitSystem = UnitSystem()): String {
    val size = if (units.imperial) String.format(Locale.getDefault(), "%.1f", abs(offsetKmh) / UnitSystem.KM_PER_MILE) else abs(offsetKmh).toString()
    return when {
        offsetKmh > 0 -> "+$size ${units.speedUnit}"
        offsetKmh < 0 -> "−$size ${units.speedUnit}"
        else -> "0 ${units.speedUnit}"
    }
}

/**
 * A time as [UnitSystem.time] wrote it, split into its digits and its AM / PM
 * ("2:05 PM" -> "2:05", "PM"; "14:05" has none), for the big clocks that set
 * the marker small beside the digits.
 */
internal fun splitClock(text: String): Pair<String, String?> {
    val i = text.indexOf(' ')
    return if (i < 0) text to null else text.substring(0, i) to text.substring(i + 1).ifBlank { null }
}

/**
 * The units for everything under the dashboard's theme. Static: they change
 * only from Settings, and reading one costs a tile nothing on its frames.
 */
val LocalUnits = staticCompositionLocalOf { Units.current.value }

/** Hands the current units down; one collector for the whole screen. */
@Composable
fun ProvideUnits(content: @Composable () -> Unit) {
    val units by Units.current.collectAsState()
    CompositionLocalProvider(LocalUnits provides units, content = content)
}

/** A time format that follows the clock choice and the language, remembered. */
@Composable
fun rememberTimeFormat(): SimpleDateFormat {
    val units = LocalUnits.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = rememberZone()
    return remember(units.clock24, locale, zone) { units.timeFormat(locale).inZone(zone) }
}
