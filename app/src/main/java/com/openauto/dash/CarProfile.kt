package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.CarRegistration
import com.openauto.dash.link.CritAir
import com.openauto.dash.link.Energies
import com.openauto.dash.link.Energy
import com.openauto.dash.link.RegistrationReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The car the launcher is fitted to: its specs, typed by the driver (the
 * setup's short form or the "My car" spec sheet) or fetched from Gemini
 * ("My car" → Fetch specs). Tiles, the fuel range, the drive monitor and the
 * AI mechanic all read it, so nothing hardcodes the car.
 */

enum class FuelType { DIESEL, PETROL, HYBRID, LPG, ELECTRIC }

/** ROBOTISED = single-clutch automated manual (Citroën BMP6 / EGS, Peugeot 2-Tronic). */
enum class GearboxType { MANUAL, ROBOTISED, AUTOMATIC, DUAL_CLUTCH, CVT }

/** Where the specs came from. */
enum class SpecSource { PRESET, AI, USER }

/**
 * Everything the launcher knows about the car. Nullable specs are "not known":
 * the derived values below fall back to safe figures for the fuel type.
 * [fuelPrice] and [currency] are the driver's own, never fetched. A blank
 * [name] means no car was entered yet ([known]): screens then say "Your car".
 * [make], [model] and [year] are what the setup's short form asked for; the
 * spec sheet's free [name] is composed from them until the driver edits it.
 */
data class CarProfile(
    val name: String,
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val engine: String = "",
    val fuel: FuelType = FuelType.DIESEL,
    val powerHp: Int? = null,
    val torqueNm: Int? = null,
    val torqueRpm: Int? = null,
    val redlineRpm: Int? = null,
    val gearbox: GearboxType = GearboxType.MANUAL,
    val gearboxName: String = "",
    val gears: Int? = null,
    val tankL: Double? = null,
    val consumptionL100: Double? = null,
    val particleFilter: Boolean = false,
    val filterAdditive: Boolean = false,
    /** Right-hand drive: templates put the main tiles on the right. */
    val driverOnRight: Boolean = false,
    val oilCapacityL: Double? = null,
    val oilSpec: String = "",
    val serviceKm: Int? = null,
    val serviceMonths: Int? = null,
    val timing: String = "",
    val tyreSize: String = "",
    val tyreFrontBar: Double? = null,
    val tyreRearBar: Double? = null,
    val batteryAh: Int? = null,
    val operatingTempC: Int? = null,
    /** Known weak points and upkeep tips for this engine and gearbox. */
    val notes: List<String> = emptyList(),
    /** From the registration certificate (scanned on the phone, or typed): the plate, ... */
    val plate: String = "",
    val vin: String = "",
    /** ... the first registration (ISO yyyy-MM-dd), ... */
    val firstRegistration: String = "",
    /** ... the fuel as printed there ("GO", "EE"...), finer than [fuel] for the Crit'Air sticker, ... */
    val registrationEnergy: String = "",
    /** ... and the Euro emission standard. */
    val euro: Int? = null,
    val fuelPrice: Double = 1.75,
    val currency: String = "€",
    val source: SpecSource = SpecSource.PRESET,
    val updatedAt: Long = 0L
) {
    val diesel: Boolean get() = fuel == FuelType.DIESEL

    /** A car was entered or fetched; false on a fresh install, where nothing should be presented as the driver's car. */
    val known: Boolean get() = name.isNotBlank()

    /** The car's name for a header or a tile, or "Your car" while none was entered. */
    fun displayName(context: Context): String = name.ifBlank { context.getString(R.string.setup_car_title) }

    /** Tank size, or a typical family car's. A zero typed in the car's settings would have divided the level by it. */
    val tank: Double get() = tankL?.takeIf { it > 0.0 } ?: TANK_LITERS

    /** Everyday consumption, or the old rough figure; zero would have made the range infinite. */
    val typicalUse: Double get() = consumptionL100?.takeIf { it > 0.0 } ?: AVG_L_PER_100KM

    /** Coolant temperature the engine runs at once warm. */
    val hotC: Int get() = operatingTempC ?: 90

    /** Below this the engine is still cold: go easy. */
    val coldC: Int get() = hotC - 30

    /** Rev limit while cold: diesels make their torque low and need less. */
    val coldRpmLimit: Int get() = if (diesel) 2500 else 3000

    /** The relaxed band to drive in: from peak torque up about 1,000 rpm. */
    val sweetBand: IntRange get() {
        val low = torqueRpm ?: if (diesel) 1750 else 2500
        return low..(low + 1000)
    }

    /** Revs above this count against the eco score. */
    val ecoRpmMax: Int get() = if (diesel) 3000 else 3500

    /** The certificate's fields alone, to tell whether they changed. */
    fun registration(): List<Any?> = listOf(plate, vin, firstRegistration, registrationEnergy, euro)

    /** The first registration as a date, or null. */
    val registeredOn: LocalDate? get() = RegistrationReader.date(firstRegistration)

    /**
     * The French Crit'Air sticker (0..5, [CritAir.UNCLASSED]) or null: from
     * the certificate's fuel when scanned, else the profile's.
     */
    val critAir: Int? get() = CritAir.of(Energies.of(registrationEnergy) ?: energy(), registeredOn, euro)

    private fun energy(): Energy = when (fuel) {
        FuelType.DIESEL -> Energy.DIESEL
        FuelType.PETROL -> Energy.PETROL
        FuelType.HYBRID -> Energy.HYBRID_PETROL
        FuelType.LPG -> Energy.LPG
        FuelType.ELECTRIC -> Energy.ELECTRIC
    }

    /**
     * [r]'s fields over this car: the plate, VIN and date always (the
     * certificate is the truth), the fuel when it reads as one; the make,
     * model, year and power only where none were known, so specs the driver
     * or the AI set stay.
     */
    fun withRegistration(r: CarRegistration): CarProfile {
        val year = RegistrationReader.date(r.firstRegistration)?.year
        val make = this.make.ifBlank { brandName(r.make) }
        val model = this.model.ifBlank { titleCase(r.model) }
        val withYear = this.year ?: year
        return copy(
            plate = r.plate.ifBlank { plate },
            vin = r.vin.ifBlank { vin },
            firstRegistration = RegistrationReader.date(r.firstRegistration)?.toString() ?: firstRegistration,
            registrationEnergy = r.energy.ifBlank { registrationEnergy },
            euro = r.euro ?: euro,
            make = make,
            model = model,
            year = withYear,
            name = name.ifBlank { composeName(make, model, withYear) },
            fuel = Energies.of(r.energy)?.let(::fuelOf) ?: fuel,
            powerHp = powerHp ?: r.powerKw?.let { (it * 1.35962).roundToInt() },
            updatedAt = System.currentTimeMillis()
        )
    }

    /** One line naming the car for Gemini: model, engine, gearbox, filter. */
    fun promptDescription(): String = buildString {
        append(name.trim().ifBlank { "car not specified" })
        val details = listOfNotNull(
            engine.ifBlank { null },
            fuel.name.lowercase(Locale.ROOT),
            powerHp?.let { "$it hp" },
            gearboxLabel().ifBlank { null },
            when {
                particleFilter && filterAdditive -> "particulate filter with additive (Eolys)"
                particleFilter -> "particulate filter"
                else -> null
            }
        )
        if (details.isNotEmpty()) append(" (" + details.joinToString(", ") + ")")
    }

    private fun gearboxLabel(): String {
        val kind = when (gearbox) {
            GearboxType.MANUAL -> "manual gearbox"
            GearboxType.ROBOTISED -> "robotised single-clutch gearbox"
            GearboxType.AUTOMATIC -> "automatic gearbox"
            GearboxType.DUAL_CLUTCH -> "dual-clutch gearbox"
            GearboxType.CVT -> "CVT gearbox"
        }
        return listOfNotNull(gears?.let { "$it-speed" }, kind, gearboxName.ifBlank { null }?.let { "($it)" }).joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("make", make); put("model", model); putOpt("year", year)
        put("engine", engine); put("fuel", fuel.name)
        putOpt("power_hp", powerHp); putOpt("torque_nm", torqueNm); putOpt("torque_rpm", torqueRpm); putOpt("redline_rpm", redlineRpm)
        put("gearbox", gearbox.name); put("gearbox_name", gearboxName); putOpt("gears", gears)
        putOpt("tank_l", tankL); putOpt("consumption_l100", consumptionL100)
        put("particle_filter", particleFilter); put("filter_additive", filterAdditive)
        put("driver_on_right", driverOnRight)
        putOpt("oil_capacity_l", oilCapacityL); put("oil_spec", oilSpec)
        putOpt("service_km", serviceKm); putOpt("service_months", serviceMonths); put("timing", timing)
        put("tyre_size", tyreSize); putOpt("tyre_front_bar", tyreFrontBar); putOpt("tyre_rear_bar", tyreRearBar)
        putOpt("battery_ah", batteryAh); putOpt("operating_temp_c", operatingTempC)
        put("notes", JSONArray(notes))
        put("plate", plate); put("vin", vin); put("first_registration", firstRegistration)
        put("registration_energy", registrationEnergy); putOpt("euro", euro)
        put("fuel_price", fuelPrice); put("currency", currency)
        put("source", source.name); put("updated_at", updatedAt)
    }

    companion object {
        /** No car entered yet: every spec unknown, so the derived values use their safe figures. */
        val NONE = CarProfile(name = "", source = SpecSource.USER)

        /** The name the short form gives a car: make, model and year, whichever were given. */
        fun composeName(make: String, model: String, year: Int?): String =
            listOfNotNull(make.trim().ifBlank { null }, model.trim().ifBlank { null }, year?.toString()).joinToString(" ")

        /**
         * A sample car with well-known figures (Citroën C4 Picasso 1.6 HDi 110
         * FAP Exclusive 2011, BMP6), offered by the spec sheet's "Back to the
         * preset" only; never presented as the driver's car by itself.
         */
        val PRESET = CarProfile(
            name = "Citroën C4 Picasso 1.6 HDi 110 FAP Exclusive 2011, BMP6",
            engine = "1.6 HDi 110 FAP (PSA DV6TED4)",
            fuel = FuelType.DIESEL,
            powerHp = 110,
            torqueNm = 240,
            torqueRpm = 1750,
            gearbox = GearboxType.ROBOTISED,
            gearboxName = "BMP6",
            gears = 6,
            tankL = 60.0,
            consumptionL100 = AVG_L_PER_100KM,
            particleFilter = true,
            filterAdditive = true,
            oilCapacityL = 3.75,
            oilSpec = "5W-30 PSA B71 2290",
            operatingTempC = 90
        )

        fun fromJson(o: JSONObject): CarProfile {
            fun int(k: String) = if (o.isNull(k)) null else o.optInt(k)
            // A zero typed or saved for a figure that cannot be zero means "unknown": the
            // skins divide by the red line and count the gears (a 0 red line crashed Motorsport).
            fun posInt(k: String) = int(k)?.takeIf { it > 0 }
            fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
            fun <E : Enum<E>> enum(k: String, values: Array<E>, default: E) =
                values.firstOrNull { it.name == o.optString(k) } ?: default
            return CarProfile(
                name = o.optString("name"),
                make = o.optString("make"), model = o.optString("model"), year = int("year"),
                engine = o.optString("engine"),
                fuel = enum("fuel", FuelType.entries.toTypedArray(), FuelType.DIESEL),
                powerHp = int("power_hp"), torqueNm = int("torque_nm"), torqueRpm = int("torque_rpm"), redlineRpm = posInt("redline_rpm"),
                gearbox = enum("gearbox", GearboxType.entries.toTypedArray(), GearboxType.MANUAL),
                gearboxName = o.optString("gearbox_name"), gears = posInt("gears"),
                tankL = dbl("tank_l"), consumptionL100 = dbl("consumption_l100"),
                particleFilter = o.optBoolean("particle_filter"), filterAdditive = o.optBoolean("filter_additive"),
                driverOnRight = o.optBoolean("driver_on_right"),
                oilCapacityL = dbl("oil_capacity_l"), oilSpec = o.optString("oil_spec"),
                serviceKm = int("service_km"), serviceMonths = int("service_months"), timing = o.optString("timing"),
                tyreSize = o.optString("tyre_size"), tyreFrontBar = dbl("tyre_front_bar"), tyreRearBar = dbl("tyre_rear_bar"),
                batteryAh = int("battery_ah"), operatingTempC = int("operating_temp_c"),
                notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                plate = o.optString("plate"), vin = o.optString("vin"), firstRegistration = o.optString("first_registration"),
                registrationEnergy = o.optString("registration_energy"), euro = int("euro"),
                fuelPrice = o.optDouble("fuel_price", PRESET.fuelPrice).takeIf { !it.isNaN() } ?: PRESET.fuelPrice,
                currency = o.optString("currency").ifBlank { PRESET.currency },
                source = enum("source", SpecSource.entries.toTypedArray(), SpecSource.USER),
                updatedAt = o.optLong("updated_at")
            )
        }
    }
}

/** The profile's fuel for a certificate's [energy]. */
private fun fuelOf(energy: Energy): FuelType = when (energy) {
    Energy.DIESEL -> FuelType.DIESEL
    Energy.PETROL, Energy.NATURAL_GAS -> FuelType.PETROL
    Energy.HYBRID_PETROL, Energy.HYBRID_DIESEL, Energy.PLUG_IN_HYBRID -> FuelType.HYBRID
    Energy.LPG -> FuelType.LPG
    Energy.ELECTRIC, Energy.HYDROGEN -> FuelType.ELECTRIC
}

/** "C4 PICASSO" as "C4 Picasso": short words and ones with digits stay as printed. */
internal fun titleCase(printed: String): String =
    printed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { w ->
        if (w.length <= 2 || w.any(Char::isDigit)) w else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/** A make as printed ("CITROEN") with its accents back when it is a well-known one. */
private fun brandName(printed: String): String {
    val name = titleCase(printed)
    return KNOWN_MAKES.firstOrNull { searchKey(it) == searchKey(name) } ?: name
}

/** Makes whose printed name lost an accent or a capital on the certificate. */
private val KNOWN_MAKES = listOf("Citroën", "Škoda", "Mercedes-Benz", "BMW", "DS", "MG", "SEAT", "Dacia", "Peugeot", "Renault", "Opel")

/** The saved car, shared by every screen; [CarProfile.NONE] until the driver enters one. */
object CarProfileStore {
    private const val PREFS = "car_profile"
    private const val KEY = "profile"

    private var appContext: Context? = null
    private val _profile = MutableStateFlow(CarProfile.NONE)
    val profile: StateFlow<CarProfile> = _profile.asStateFlow()

    /** The car right now, for code outside Compose. */
    val current: CarProfile get() = _profile.value

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { CarProfile.fromJson(JSONObject(raw)) }.onSuccess { _profile.value = it }
    }

    fun save(profile: CarProfile) {
        _profile.value = profile
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY, profile.toJson().toString())?.apply()
    }
}
