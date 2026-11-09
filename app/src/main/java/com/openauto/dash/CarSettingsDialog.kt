package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.CritAir
import com.openauto.dash.link.RegistrationReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * "My car": the car's name, a one-tap spec fetch from Gemini, and every spec
 * to check or correct. Save keeps them, and so does leaving the sheet any
 * other way (its back arrow, Back, another category, the car setting off), as
 * in the AI sheet beside it; only Cancel throws the changes away.
 */
@Composable
internal fun CarSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(CarProfileStore.current) }
    // Bumped when the whole draft is replaced (fetch, preset), so number fields re-read it.
    var version by remember { mutableIntStateOf(0) }
    var edited by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(fetching) {
        waited = 0
        while (fetching) {
            delay(1_000)
            waited++
        }
    }
    // (worked, message) from the last fetch.
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // Without a Gemini key the fetch can only fail: a line says where the key goes instead of the button.
    val hasAiKey = remember { AiSettings.load(context).apiKey.isNotBlank() }

    fun replace(p: CarProfile) {
        draft = p
        version++
    }

    fun edit(p: CarProfile) {
        draft = p
        edited = true
    }

    // A registration scanned on the phone while the sheet is open: taken in, so leaving doesn't save over it.
    val stored by CarProfileStore.profile.collectAsState()
    LaunchedEffect(stored.plate, stored.vin, stored.firstRegistration, stored.registrationEnergy, stored.euro) {
        if (stored.registration() != draft.registration()) {
            draft = draft.copy(
                plate = stored.plate, vin = stored.vin, firstRegistration = stored.firstRegistration,
                registrationEnergy = stored.registrationEnergy, euro = stored.euro
            )
            version++
        }
    }

    // Set once the sheet is on its way out: the drive lock closes Settings and
    // the sheet in the same breath, and the car is saved once.
    var leaving by remember { mutableStateOf(false) }

    fun save() {
        leaving = true
        CarProfileStore.save(if (edited) draft.copy(source = SpecSource.USER, updatedAt = System.currentTimeMillis()) else draft)
        // The mechanic's answers are about the car: redo them for the new one.
        AiMechanic.refresh()
        onDismiss()
    }

    /** Leaving without Save or Cancel: what was changed is kept. */
    fun leave() {
        if (leaving) return
        if (draft != CarProfileStore.current) save() else onDismiss()
    }
    ParkedOnly(::leave)

    fun fetch() {
        fetching = true
        result = null
        scope.launch {
            CarSpecs.fetch(context, draft)
                .onSuccess {
                    replace(it)
                    edited = false
                    result = true to context.getString(R.string.car_fetch_ok)
                }
                .onFailure { result = false to context.getString(R.string.car_fetch_failed, it.message.orEmpty()) }
            fetching = false
        }
    }

    SettingsSheet(
        title = stringResource(R.string.car_my_car_title_dialog),
        onDismiss = ::leave,
        actions = {
            SheetButton(stringResource(R.string.apps_cancel), primary = false, onClick = onDismiss)
            SheetButton(stringResource(R.string.car_save), onClick = ::save)
        }
    ) {
        Text(stringResource(R.string.car_settings_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Label(stringResource(R.string.car_name))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { edit(draft.copy(name = it)); result = null },
            placeholder = { Text(stringResource(R.string.car_name_hint), color = DashColors.Muted) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
        if (!hasAiKey) {
            Text(stringResource(R.string.car_fetch_needs_key), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !fetching && draft.name.isNotBlank(), onClick = ::fetch, colors = buttonColors()) {
                Text(stringResource(R.string.car_fetch))
            }
            Spacer(Modifier.size(12.dp))
            if (fetching) {
                CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.ai_test_waiting, waited), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            result?.let { (ok, message) ->
                Text(message, color = if (ok) DashColors.Good else DashColors.Warning, style = MaterialTheme.typography.bodySmall)
            }
        }

        SwitchRow(stringResource(R.string.car_spec_driver_side), stringResource(R.string.car_spec_driver_side_detail), draft.driverOnRight) {
            edit(draft.copy(driverOnRight = it))
        }

        // The certificate's fields are the driver's own, like the fuel price: typing them doesn't count as editing the specs.
        Label(stringResource(R.string.car_section_registration))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSpec(stringResource(R.string.car_spec_plate), draft.plate, Modifier.weight(1f)) { draft = draft.copy(plate = it.uppercase()) }
            DateSpec(stringResource(R.string.car_spec_first_registration), draft.firstRegistration, version, Modifier.weight(1f)) {
                draft = draft.copy(firstRegistration = it)
            }
        }
        TextSpec(stringResource(R.string.car_spec_vin), draft.vin) { draft = draft.copy(vin = it.uppercase().filter(Char::isLetterOrDigit).take(17)) }
        CritAirLine(draft.critAir)
        InspectionLine()
        Text(stringResource(R.string.car_registration_from_phone), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)

        Label(stringResource(R.string.car_section_engine))
        TextSpec(stringResource(R.string.car_spec_engine), draft.engine) { edit(draft.copy(engine = it)) }
        ChoiceRow(FuelType.entries, draft.fuel, { context.getString(it.labelRes) }) { edit(draft.copy(fuel = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumSpec(stringResource(R.string.car_spec_power), draft.powerHp, version, Modifier.weight(1f)) { edit(draft.copy(powerHp = it?.toInt())) }
            NumSpec(stringResource(R.string.car_spec_torque), draft.torqueNm, version, Modifier.weight(1f)) { edit(draft.copy(torqueNm = it?.toInt())) }
            NumSpec(stringResource(R.string.car_spec_torque_rpm), draft.torqueRpm, version, Modifier.weight(1f)) { edit(draft.copy(torqueRpm = it?.toInt())) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumSpec(stringResource(R.string.car_spec_redline), draft.redlineRpm, version, Modifier.weight(1f)) { edit(draft.copy(redlineRpm = it?.toInt())) }
            NumSpec(stringResource(R.string.car_spec_operating_temp), draft.operatingTempC, version, Modifier.weight(1f)) { edit(draft.copy(operatingTempC = it?.toInt())) }
            NumSpec(stringResource(R.string.car_spec_battery), draft.batteryAh, version, Modifier.weight(1f)) { edit(draft.copy(batteryAh = it?.toInt())) }
        }
        SwitchRow(stringResource(R.string.car_spec_filter), stringResource(R.string.car_spec_filter_detail), draft.particleFilter) {
            edit(draft.copy(particleFilter = it))
        }
        if (draft.particleFilter) {
            SwitchRow(stringResource(R.string.car_spec_additive), stringResource(R.string.car_spec_additive_detail), draft.filterAdditive) {
                edit(draft.copy(filterAdditive = it))
            }
        }

        Label(stringResource(R.string.car_section_gearbox))
        ChoiceRow(GearboxType.entries, draft.gearbox, { context.getString(it.labelRes) }, perRow = 3) { edit(draft.copy(gearbox = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSpec(stringResource(R.string.car_spec_gearbox_name), draft.gearboxName, Modifier.weight(2f)) { edit(draft.copy(gearboxName = it)) }
            NumSpec(stringResource(R.string.car_spec_gears), draft.gears, version, Modifier.weight(1f)) { edit(draft.copy(gears = it?.toInt())) }
        }

        Label(stringResource(R.string.car_section_upkeep))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumSpec(stringResource(R.string.car_spec_tank), draft.tankL, version, Modifier.weight(1f), decimals = true) { edit(draft.copy(tankL = it)) }
            NumSpec(stringResource(R.string.car_spec_consumption), draft.consumptionL100, version, Modifier.weight(1f), decimals = true) { edit(draft.copy(consumptionL100 = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumSpec(stringResource(R.string.car_spec_oil_capacity), draft.oilCapacityL, version, Modifier.weight(1f), decimals = true) { edit(draft.copy(oilCapacityL = it)) }
            TextSpec(stringResource(R.string.car_spec_oil_spec), draft.oilSpec, Modifier.weight(2f)) { edit(draft.copy(oilSpec = it)) }
        }
        // The service interval is edited in Servicing alone (MaintenanceTiles.kt), where
        // every other interval is; here it is only read, so there is one place to change it.
        ServiceIntervalLine()
        TextSpec(stringResource(R.string.car_spec_timing), draft.timing) { edit(draft.copy(timing = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSpec(stringResource(R.string.car_spec_tyre_size), draft.tyreSize, Modifier.weight(2f)) { edit(draft.copy(tyreSize = it)) }
            NumSpec(stringResource(R.string.car_spec_tyre_front), draft.tyreFrontBar, version, Modifier.weight(1f), decimals = true) { edit(draft.copy(tyreFrontBar = it)) }
            NumSpec(stringResource(R.string.car_spec_tyre_rear), draft.tyreRearBar, version, Modifier.weight(1f), decimals = true) { edit(draft.copy(tyreRearBar = it)) }
        }

        Label(stringResource(R.string.car_section_fuel_price))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumSpec(stringResource(R.string.car_spec_fuel_price), draft.fuelPrice, version, Modifier.weight(2f), decimals = true) {
                // Only the driver's own figure, so it doesn't count as editing the specs.
                it?.let { price -> draft = draft.copy(fuelPrice = price) }
            }
            TextSpec(stringResource(R.string.car_spec_currency), draft.currency, Modifier.weight(1f)) { draft = draft.copy(currency = it) }
        }

        if (draft.notes.isNotEmpty()) {
            Label(stringResource(R.string.car_section_notes))
            draft.notes.forEach {
                Text("• $it", color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        TextButton(onClick = {
            replace(CarProfile.PRESET.copy(fuelPrice = draft.fuelPrice, currency = draft.currency))
            edited = false
            result = null
        }) { Text(stringResource(R.string.car_reset_preset), color = DashColors.Muted) }
    }
}

/** The oil service interval as Servicing has it, and where to change it. */
@Composable
private fun ServiceIntervalLine() {
    val upkeep by Maintenance.state.collectAsState()
    val oil = upkeep.plan.firstOrNull { it.kind == UpkeepKind.OIL }
    val km = oil?.everyKm
    val months = oil?.everyMonths
    val line = when {
        km != null && months != null -> stringResource(R.string.car_service_interval, formatKm(km), months)
        km != null -> stringResource(R.string.car_service_interval_km, formatKm(km))
        months != null -> stringResource(R.string.car_service_interval_months, months)
        else -> stringResource(R.string.car_service_interval_unknown)
    }
    Text(line, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.car_service_from_servicing), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
}

/** The French air-quality sticker the car gets, in its colour, once the fuel and the date tell it. */
@Composable
private fun CritAirLine(critAir: Int?) {
    critAir ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(critAirColor(critAir)))
        Spacer(Modifier.size(8.dp))
        Text(
            if (critAir == CritAir.UNCLASSED) stringResource(R.string.car_crit_air_unclassed) else stringResource(R.string.car_crit_air, critAir),
            color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** When the next roadworthiness test is due, as Servicing has it (scanned off X.1, or logged there). */
@Composable
private fun InspectionLine() {
    val upkeep by Maintenance.state.collectAsState()
    val due = remember(upkeep) { upkeep.dueAt(UpkeepKind.INSPECTION, System.currentTimeMillis()) }
    Text(
        due?.let { stringResource(R.string.car_inspection_due, java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(it))) }
            ?: stringResource(R.string.car_inspection_unknown),
        color = if (due == null) DashColors.TextSecondary else DashColors.TextPrimary,
        style = MaterialTheme.typography.bodyMedium
    )
}

/** The sticker colours: green, purple, yellow, orange, burgundy, grey; no sticker in black. */
private fun critAirColor(critAir: Int): Color = when (critAir) {
    0 -> Color(0xFF2E9E4F)
    1 -> Color(0xFF8E44AD)
    2 -> Color(0xFFF1C40F)
    3 -> Color(0xFFE67E22)
    4 -> Color(0xFF8E2C3A)
    5 -> Color(0xFF7F8C8D)
    else -> Color(0xFF202020)
}

/** A date field typed as 2011-03-15 or 15/03/2011; reports an ISO date, or "" when emptied. */
@Composable
private fun DateSpec(label: String, iso: String, version: Int, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var text by remember(version) { mutableStateOf(iso) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank()) onChange("") else parseDate(typed)?.let { onChange(it.toString()) }
        },
        label = { Text(label, maxLines = 1) },
        placeholder = { Text("2011-03-15", color = DashColors.Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = fieldColors()
    )
}

/** yyyy-MM-dd, or day first with / . or - between, as on a European certificate. */
internal fun parseDate(typed: String): LocalDate? {
    val t = typed.trim()
    RegistrationReader.date(t)?.let { return it }
    val m = Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})$").find(t) ?: return null
    return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
}

@Composable
private fun TextSpec(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        colors = fieldColors()
    )
}

/**
 * A number field; blank means "not known". Keeps what's typed ("5," on the way
 * to "5,4") and only reports numbers; [version] re-reads [value] when the whole
 * draft was replaced.
 */
@Composable
private fun NumSpec(
    label: String,
    value: Number?,
    version: Int,
    modifier: Modifier = Modifier,
    decimals: Boolean = false,
    onChange: (Double?) -> Unit
) {
    fun show(v: Number?): String = when {
        v == null -> ""
        decimals -> String.format(Locale.getDefault(), "%.2f", v.toDouble()).trimEnd('0').trimEnd(',', '.')
        else -> v.toLong().toString()
    }
    var text by remember(version) { mutableStateOf(show(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            val cleaned = typed.trim().replace(',', '.')
            // A 0 is "unknown" for every figure here (no car has a 0 red line or 0 gears), not a value.
            if (cleaned.isEmpty()) onChange(null) else cleaned.toDoubleOrNull()?.let { if (it > 0) onChange(it) else if (it == 0.0) onChange(null) }
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
        colors = fieldColors()
    )
}
