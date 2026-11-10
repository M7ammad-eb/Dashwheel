package com.openauto.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Gemini answer read as JSON. Asked for JSON it sometimes still wraps it in
 * a markdown code fence ("```json ... ```"), which goes first.
 */
internal fun aiJson(raw: String): JSONObject =
    JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

/**
 * Asks Gemini for the specs of the car the driver named, and reads the answer
 * into a [CarProfile]. Only the car's name leaves the unit. The question and
 * the reading are pure, so they're unit-tested.
 */
object CarSpecs {

    private const val BUDGET_MS = 60_000L

    private val NUMBERS = listOf(
        "power_hp", "torque_nm", "torque_rpm", "redline_rpm", "gears", "tank_l", "consumption_l100",
        "oil_capacity_l", "service_km", "service_months", "tyre_front_bar", "tyre_rear_bar",
        "battery_ah", "operating_temp_c"
    )
    private val TEXTS = listOf("engine", "gearbox_name", "oil_spec", "timing", "tyre_size")

    /** Gemini's answer shape; a spec it doesn't know comes back null. */
    val SCHEMA: JSONObject
        get() {
            fun str() = JSONObject().put("type", "STRING")
            val props = JSONObject()
            TEXTS.forEach { props.put(it, str()) }
            NUMBERS.forEach { props.put(it, JSONObject().put("type", "NUMBER").put("nullable", true)) }
            props.put("fuel", str().put("enum", JSONArray(FuelType.entries.map { it.name })))
            props.put("gearbox", str().put("enum", JSONArray(GearboxType.entries.map { it.name })))
            props.put("particle_filter", JSONObject().put("type", "BOOLEAN"))
            props.put("filter_additive", JSONObject().put("type", "BOOLEAN"))
            props.put("notes", JSONObject().put("type", "ARRAY").put("items", str()).put("maxItems", 5))
            val required = TEXTS + NUMBERS + listOf("fuel", "gearbox", "particle_filter", "filter_additive", "notes")
            return JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required))
        }

    fun prompt(carName: String, language: AiLanguage): String = buildString {
        appendLine("You are an automotive technical reference. Give the manufacturer's specifications of this exact car, as sold in Europe: \"${carName.trim()}\".")
        appendLine("Match the exact engine, power version and gearbox named. Where a figure varies by market or year, give the most common one for this version. Use null for anything you are not sure of; never guess wildly.")
        appendLine("Fields and units:")
        appendLine("- engine: engine name and manufacturer code, e.g. \"1.6 HDi 110 FAP (PSA DV6TED4)\".")
        appendLine("- fuel, power_hp (DIN hp), torque_nm, torque_rpm (rpm where peak torque starts), redline_rpm.")
        appendLine("- gearbox: MANUAL, ROBOTISED (single-clutch automated manual), AUTOMATIC (torque converter), DUAL_CLUTCH or CVT; gearbox_name: the maker's name for it (e.g. BMP6, EAT6); gears.")
        appendLine("- tank_l (litres), consumption_l100: typical real-world mixed consumption in L/100 km, not the official figure.")
        appendLine("- particle_filter, filter_additive (true when the filter uses a fuel additive such as Eolys).")
        appendLine("- oil_capacity_l (with filter), oil_spec (viscosity and maker norm).")
        appendLine("- service_km and service_months: the maker's service interval; timing: \"belt, replace every N km or N years\" or \"chain\".")
        appendLine("- tyre_size (the size fitted to this trim), tyre_front_bar and tyre_rear_bar (normal load, cold).")
        appendLine("- battery_ah, operating_temp_c (normal coolant temperature).")
        appendLine("- notes: up to 5 known weak points or upkeep tips for this exact engine and gearbox, one short sentence each, in ${language.promptName}.")
    }

    /**
     * Reads Gemini's [raw] answer over [base], keeping the driver's own name,
     * fuel price and currency. Known specs replace the base's; nulls keep it.
     */
    fun read(raw: String, base: CarProfile, now: Long): CarProfile {
        val o = aiJson(raw)
        fun int(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() && it > 0 }?.let { Math.round(it).toInt() }
        fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() && it > 0 }
        fun text(k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotBlank() && it != "null" }
        return base.copy(
            engine = text("engine") ?: base.engine,
            fuel = FuelType.entries.firstOrNull { it.name == o.optString("fuel") } ?: base.fuel,
            powerHp = int("power_hp") ?: base.powerHp,
            torqueNm = int("torque_nm") ?: base.torqueNm,
            torqueRpm = int("torque_rpm") ?: base.torqueRpm,
            redlineRpm = int("redline_rpm")?.takeIf { it > 0 } ?: base.redlineRpm,
            gearbox = GearboxType.entries.firstOrNull { it.name == o.optString("gearbox") } ?: base.gearbox,
            gearboxName = text("gearbox_name") ?: base.gearboxName,
            gears = int("gears")?.takeIf { it > 0 } ?: base.gears,
            tankL = dbl("tank_l") ?: base.tankL,
            consumptionL100 = dbl("consumption_l100") ?: base.consumptionL100,
            particleFilter = if (o.has("particle_filter")) o.optBoolean("particle_filter") else base.particleFilter,
            filterAdditive = if (o.has("filter_additive")) o.optBoolean("filter_additive") else base.filterAdditive,
            oilCapacityL = dbl("oil_capacity_l") ?: base.oilCapacityL,
            oilSpec = text("oil_spec") ?: base.oilSpec,
            serviceKm = int("service_km") ?: base.serviceKm,
            serviceMonths = int("service_months") ?: base.serviceMonths,
            timing = text("timing") ?: base.timing,
            tyreSize = text("tyre_size") ?: base.tyreSize,
            tyreFrontBar = dbl("tyre_front_bar") ?: base.tyreFrontBar,
            tyreRearBar = dbl("tyre_rear_bar") ?: base.tyreRearBar,
            batteryAh = int("battery_ah") ?: base.batteryAh,
            operatingTempC = int("operating_temp_c") ?: base.operatingTempC,
            notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() } ?: base.notes,
            source = SpecSource.AI,
            updatedAt = now
        )
    }

    /** Fetches the specs of [base]'s car; the failure's message is ready to show. */
    suspend fun fetch(context: Context, base: CarProfile): Result<CarProfile> {
        val config = AiSettings.load(context)
        if (config.apiKey.isBlank()) return Result.failure(IllegalStateException(context.getString(R.string.car_fetch_no_key)))
        GeminiClient.reachGoogle().exceptionOrNull()?.let {
            return Result.failure(IllegalStateException(AiMechanic.describe(context, it)))
        }
        return GeminiClient.generate(config.apiKey, prompt(base.name, config.language), SCHEMA, budgetMs = BUDGET_MS)
            .mapCatching {
                runCatching { read(it.text, base, System.currentTimeMillis()) }.getOrElse { throw UnreadableAnswerException() }
            }
            .recoverCatching { throw IllegalStateException(AiMechanic.describe(context, it)) }
    }
}
