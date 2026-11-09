package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberDoors
import com.openauto.dash.vehicle.rememberGauges
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberSpeedSourceKind
import com.openauto.dash.vehicle.rememberTyres
import com.openauto.dash.vehicle.rememberVehicle
import com.openauto.dash.vehicle.speedSourceWord
import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.DirectionsCarFilled
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Live readings for the designed tiles. Each built-in widget turns the feeds
 * its standard tile uses into a [WidgetFace]. A design never steps aside for
 * the standard tile: while a reading is missing (adapter off, access not
 * granted, nothing playing...) the face shows "--", says why, and carries the
 * button that fixes it, so the chosen design is always the one on screen.
 */

/** The live face of [kind]; null only for [FRAMED_KINDS], which keep their own content. */
@Composable
internal fun rememberWidgetFace(kind: BuiltinKind, env: SkinTileEnv): WidgetFace? = when (kind) {
    BuiltinKind.TELEMETRY -> telemetryFace(env)
    BuiltinKind.HEAD_UNIT -> headUnitFace()
    BuiltinKind.GEAR -> gearFace()
    BuiltinKind.SPEED_HUD -> speedFace(env)
    BuiltinKind.MEDIA -> mediaFace(env)
    BuiltinKind.NAVIGATION -> directionsFace(env)
    BuiltinKind.CLOCK -> clockFace()
    BuiltinKind.WEATHER -> weatherFace()
    BuiltinKind.RANGE -> rangeFace(env)
    BuiltinKind.OBD_ALL -> obdAllFace(env)
    BuiltinKind.OBD_DTC -> faultCodesFace(env)
    BuiltinKind.DOORS -> doorsFace()
    BuiltinKind.CAN_MON -> canMonitorFace()
    BuiltinKind.COMPASS -> compassFace()
    BuiltinKind.TRIP -> tripFace()
    BuiltinKind.GFORCE -> gForceFace()
    BuiltinKind.PARKING -> parkingFace()
    BuiltinKind.CALENDAR -> agendaFace()
    BuiltinKind.QUICK_DIAL -> quickDialFace()
    BuiltinKind.NOTIFICATIONS -> notificationsFace(env)
    BuiltinKind.AUDIO -> audioFace()
    BuiltinKind.SCREEN_LIGHT -> screenLightFace()
    BuiltinKind.RADIOS -> radioSwitchesFace()
    BuiltinKind.UNIT_LIGHT -> unitLightFace()
    BuiltinKind.SPEED_CAMERAS -> speedCamerasFace()
    BuiltinKind.RADIO_PRESETS -> radioPresetsFace()
    BuiltinKind.HOME_WORK -> homeWorkFace()
    BuiltinKind.SHARE_ETA -> shareEtaFace()
    BuiltinKind.VOICE_NOTES -> voiceNotesFace()
    BuiltinKind.QUICK_SWITCHES -> quickSwitchesFace()
    BuiltinKind.GPS_STATUS -> gpsStatusFace()
    BuiltinKind.PERF_TIMER -> perfTimerFace()
    BuiltinKind.WIFI_NETWORKS -> wifiNetworksFace()
    BuiltinKind.BT_DEVICES -> bluetoothDevicesFace()
    BuiltinKind.SPEED_LIMIT -> speedLimitFace()
    BuiltinKind.PARKING_TIMER -> parkingTimerFace()
    BuiltinKind.FUEL_LOG -> fuelLogFace()
    BuiltinKind.CONSUMPTION -> consumptionFace()
    BuiltinKind.WEATHER_ALERTS -> weatherAlertsFace()
    BuiltinKind.ENGINE_TEMPS -> engineTempsFace()
    BuiltinKind.COMMUTE -> commuteFace()
    BuiltinKind.DRIVE_HISTORY -> driveHistoryFace()
    BuiltinKind.NOW -> nowFace()
    BuiltinKind.LPG_TANK -> lpgTankFace()
    BuiltinKind.MY_READINGS -> myReadingsFace()
    BuiltinKind.FILTER_CARE -> filterFace()
    BuiltinKind.WARMUP -> warmupFace(env)
    BuiltinKind.BATTERY -> batteryFace(env)
    BuiltinKind.ECO_DRIVE -> ecoFace()
    BuiltinKind.FUEL_TO_DEST -> fuelToDestFace()
    BuiltinKind.BREAK_TIMER -> breakFace()
    BuiltinKind.SERVICE -> serviceFace()
    BuiltinKind.FUEL_PRICES -> fuelPricesFace()
    BuiltinKind.CAR_STATUS -> carStatusFace()
    BuiltinKind.TYRES -> tyresFace()
    // Live views and the spec sheet: they keep their content and get the design's frame (DesignFrame).
    BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR -> null
    // The bar has no designs of its own: it is buttons.
    BuiltinKind.DASH_BAR -> null
}

/**
 * Widgets framed rather than redrawn: live views (map, docked window) and
 * the car's spec sheet, whose tap opens the car settings.
 */
internal val FRAMED_KINDS = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR)

private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.getDefault(), pattern, *args)

/** A reading that isn't there yet: "--", why, and the button that gets it. */
private fun idleFace(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    caption: String,
    unit: String = "",
    action: FaceAction? = null,
    fullCircle: Boolean = false,
    /** The road sign the widget reads as, so the Road sign design keeps its own sign while it waits. */
    sign: SignKind? = null
) = WidgetFace(
    icon = icon, title = title, value = NO_READING, unit = unit, caption = caption,
    fraction = 0f, fullCircle = fullCircle, actions = listOfNotNull(action), sign = sign
)

/** The adapter isn't connected: say so, and offer Connect (off while a connection is under way). */
@Composable
private fun obdIdle(kind: BuiltinKind, env: SkinTileEnv, unit: String): WidgetFace = idleFace(
    kindIcon(kind), kind.label,
    stringResource(if (env.obdConnection == ObdConnectionState.CONNECTING) R.string.dash_obd_connecting else R.string.vehicle_obd_not_connected),
    unit,
    FaceAction(Icons.Filled.Bluetooth, stringResource(R.string.info_connect), primary = true, enabled = env.obdConnection.isIdle, onClick = env.onConnectObd)
)

/** Opens the system screen that grants notification access (media, directions, notifications). */
@Composable
private fun grantAccessAction(): FaceAction {
    val context = LocalContext.current
    return FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_grant_access), primary = true,
        onClick = { CarMediaController.openNotificationAccessSettings(context) })
}

// --- Vehicle -----------------------------------------------------------------------

/** The engine readings as small gauges (twin dials, shift lights, gauge bank). */
@Composable
private fun engineGauges(d: ObdData, rpmFirst: Boolean): List<FaceGauge> {
    val u = LocalUnits.current
    val speed = FaceGauge(stringResource(R.string.vehicle_speed), u.speed(d.speedKmh).toString(), u.speedUnit, d.speedKmh / 220f)
    val rpm = FaceGauge(stringResource(R.string.vehicle_rpm), d.rpm.toString(), "rpm", rememberRevScale().fraction(d.rpm))
    return (if (rpmFirst) listOf(rpm, speed) else listOf(speed, rpm)) + listOf(
        FaceGauge(stringResource(R.string.vehicle_coolant), u.temp(d.coolantTempC).toString(), u.tempUnit, d.coolantTempC / 130f),
        FaceGauge(stringResource(R.string.vehicle_battery), fmt("%.1f", d.voltage), "V", batteryFraction(d.voltage)),
        FaceGauge(stringResource(R.string.vehicle_load), d.engineLoadPct.toString(), "%", d.engineLoadPct / 100f),
        FaceGauge(stringResource(R.string.vehicle_throttle), d.throttlePct.toString(), "%", d.throttlePct / 100f)
    )
}

@Composable
private fun telemetryFace(env: SkinTileEnv): WidgetFace {
    val u = LocalUnits.current
    // Whatever the car answers is shown, adapter or not (what it does not answer reads 0, as the face always drew it).
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.BATTERY_VOLTS, K.ENGINE_LOAD, K.THROTTLE)
    if (env.obdConnection != ObdConnectionState.CONNECTED && !g.any) return obdIdle(BuiltinKind.TELEMETRY, env, u.speedUnit)
    val d = g.toObdData()
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.TELEMETRY.label,
        value = u.speed(d.speedKmh).toString(), unit = u.speedUnit,
        caption = "${d.rpm} rpm",
        fraction = d.speedKmh / 220f,
        // An engine alert only: speeding is the speed tile's business, and a red number here read as both.
        alert = d.coolantTempC >= 110,
        alertWord = stringResource(R.string.design_alert_hot),
        number = u.speed(d.speedKmh).toFloat(),
        gauges = engineGauges(d, rpmFirst = false),
        // The revs are already the caption: the designs showing only two stats don't repeat them.
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_coolant), u.tempText(d.coolantTempC)),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            FaceStat(stringResource(R.string.vehicle_load), "${d.engineLoadPct} %"),
            FaceStat(stringResource(R.string.vehicle_rpm), d.rpm.toString())
        )
    )
}

@Composable
private fun obdAllFace(env: SkinTileEnv): WidgetFace {
    val g = rememberPanelGauges(K.SPEED, K.RPM, K.COOLANT_TEMP, K.INTAKE_TEMP, K.THROTTLE, K.ENGINE_LOAD, K.FUEL_LEVEL, K.BATTERY_VOLTS)
    if (env.obdConnection != ObdConnectionState.CONNECTED && !g.any) return obdIdle(BuiltinKind.OBD_ALL, env, "rpm")
    val u = LocalUnits.current
    val d = g.toObdData()
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.OBD_ALL.label,
        value = d.rpm.toString(), unit = "rpm",
        caption = u.speedText(d.speedKmh),
        fraction = rememberRevScale().fraction(d.rpm),
        gauges = engineGauges(d, rpmFirst = true),
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.vehicle_speed), u.speedText(d.speedKmh)),
            FaceStat(stringResource(R.string.vehicle_coolant), u.tempText(d.coolantTempC)),
            FaceStat(stringResource(R.string.vehicle_intake_air), u.tempText(d.intakeTempC)),
            FaceStat(stringResource(R.string.vehicle_throttle), "${d.throttlePct} %"),
            FaceStat(stringResource(R.string.vehicle_engine_load), "${d.engineLoadPct} %"),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            d.fuelLevelPct.takeIf { it > 0 }?.let { FaceStat(stringResource(R.string.vehicle_fuel_level), "$it %") }
        )
    )
}

@Composable
private fun rangeFace(env: SkinTileEnv): WidgetFace {
    var showRangeFinder by remember { mutableStateOf(false) }
    if (showRangeFinder) RangeFinderDialog(onDismiss = { showRangeFinder = false })
    val u = LocalUnits.current
    val fuel = rememberFuel() ?: return idleFace(
        Icons.Filled.LocalGasStation, BuiltinKind.RANGE.label, stringResource(R.string.design_range_unknown), u.distanceUnit,
        FaceAction(Icons.Filled.Tune, stringResource(R.string.vehicle_find_range_signal), primary = true, onClick = { showRangeFinder = true })
    )
    return WidgetFace(
        icon = Icons.Filled.LocalGasStation,
        title = BuiltinKind.RANGE.label,
        value = u.distance(fuel.rangeKm).toString(), unit = u.distanceUnit,
        // Worked back from the car's range when it sends no level: said to be approximate.
        caption = (if (fuel.percentEstimated) "≈ " else "") + fmt("%d %% · %.1f L", fuel.percent, fuel.liters),
        fraction = fuel.percent / 100f,
        alert = fuel.percent <= 10,
        alertWord = stringResource(R.string.design_alert_low),
        severity = if (fuel.percent <= 10) 2 else if (fuel.percent <= 20) 1 else 0,
        sign = SignKind.FUEL,
        scale = "E" to "F",
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_fuel), "${fuel.percent} %"),
            FaceStat(stringResource(R.string.vehicle_in_tank), fmt("%.1f L", fuel.liters)),
            // "L/100" alone fits the stat; the other units are short already.
            FaceStat(
                stringResource(R.string.vehicle_avg_use),
                if (u.economy == Economy.L_PER_100KM) fmt("%.1f L/100", fuel.avgUse) else u.economyText(fuel.avgUse.toDouble())
            )
        )
    )
}

@Composable
private fun faultCodesFace(env: SkinTileEnv): WidgetFace {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai by AiMechanic.state.collectAsState()
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    val pending by ObdBluetoothManager.pending.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val connected = env.obdConnection == ObdConnectionState.CONNECTED
    val clearCodes = rememberClearCodes {
        busy = true
        scope.launch {
            ObdBluetoothManager.clearTroubleCodes().onSuccess { AiMechanic.cleared() }
            busy = false
        }
    }
    val scan = FaceAction(Icons.Filled.Search, stringResource(R.string.vehicle_scan), primary = true, enabled = connected && !busy, onClick = {
        busy = true
        scope.launch {
            ObdBluetoothManager.readTroubleCodes().onSuccess { AiMechanic.report(it, announce = false) }
            busy = false
        }
    })
    // The car's own messages (a Peugeot, Citroën or DS's) come first, and need no OBD adapter.
    val messages = rememberCarMessages()
    val messageRows = messages.map { FaceRow(carMessageText(it.code), "", alert = it.level != PsaWarnings.Level.INFO) }
    val serious = messages.filter { it.level != PsaWarnings.Level.INFO }
    if (ai.codes == null && messages.isEmpty()) {
        return if (connected) {
            idleFace(Icons.Filled.Warning, BuiltinKind.OBD_DTC.label, stringResource(R.string.design_no_scan_yet), action = scan)
        } else obdIdle(BuiltinKind.OBD_DTC, env, "")
    }
    val codes = ai.codes.orEmpty()
    val titles = remember(codes, context) { codes.map { ObdCodes.describe(it).localizedTitle(context) } }
    val lampOn = lamp?.on == true
    val count = serious.size + codes.size
    return WidgetFace(
        icon = Icons.Filled.Warning,
        title = BuiltinKind.OBD_DTC.label,
        value = count.toString(),
        unit = pluralStringResource(R.plurals.design_codes_unit, count),
        caption = when {
            serious.isNotEmpty() -> messageRows[messages.indexOf(serious.first())].title
            codes.isNotEmpty() -> "${codes.first()} · ${titles.first()}"
            lampOn -> stringResource(R.string.vehicle_lamp_on)
            else -> stringResource(R.string.ai_no_codes)
        },
        fraction = if (count == 0) 0f else 1f,
        alert = count > 0 || lampOn,
        severity = when {
            serious.any { it.level == PsaWarnings.Level.STOP } || codes.isNotEmpty() -> 2
            serious.isNotEmpty() || lampOn -> 1
            else -> 0
        },
        rows = messageRows + codes.mapIndexed { i, c ->
            FaceRow("$c ${titles[i]}", if (c in pending) stringResource(R.string.vehicle_pending) else "", alert = true)
        },
        stats = listOfNotNull(lamp?.let { FaceStat(stringResource(R.string.design_engine_lamp), stringResource(if (it.on) R.string.design_on else R.string.design_off)) }),
        actions = listOf(
            scan,
            FaceAction(Icons.Filled.DeleteSweep, stringResource(R.string.vehicle_clear), enabled = connected && !busy && codes.isNotEmpty(), onClick = clearCodes)
        )
    )
}

@Composable
private fun doorsFace(): WidgetFace {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val doors = rememberDoors()
    val d = doors ?: return idleFace(Icons.Filled.SensorDoor, BuiltinKind.DOORS.label, stringResource(McuReader.waitingRes))
    val open = stringResource(R.string.vehicle_door_open_caps)
    val closed = stringResource(R.string.vehicle_door_closed)
    val all = listOf(
        stringResource(R.string.vehicle_door_front_left) to d.frontLeft,
        stringResource(R.string.vehicle_door_front_right) to d.frontRight,
        stringResource(R.string.vehicle_door_rear_left) to d.rearLeft,
        stringResource(R.string.vehicle_door_rear_right) to d.rearRight,
        stringResource(R.string.vehicle_door_tailgate) to d.tailgate,
        stringResource(R.string.vehicle_door_bonnet) to d.bonnet
    )
    val openCount = all.count { it.second }
    return WidgetFace(
        icon = Icons.Filled.SensorDoor,
        title = BuiltinKind.DOORS.label,
        value = openCount.toString(),
        unit = stringResource(R.string.design_doors_open_unit),
        caption = if (d.anyOpen) all.filter { it.second }.joinToString(" · ") { it.first } else stringResource(R.string.vehicle_doors_all_closed),
        fraction = openCount / all.size.toFloat(),
        alert = d.anyOpen,
        alertWord = open,
        severity = if (d.anyOpen) 1 else 0,
        doors = listOf(d.frontLeft, d.frontRight, d.rearLeft, d.rearRight, d.tailgate, d.bonnet),
        // Open doors first, so every design shows them before the closed ones.
        rows = all.sortedByDescending { it.second }.map { (name, isOpen) -> FaceRow(name, if (isOpen) open else closed, alert = isOpen) }
    )
}

@Composable
private fun canMonitorFace(): WidgetFace {
    DisposableEffect(Unit) {
        McuReader.start()
        UnitBus.sniff(true)
        onDispose {
            McuReader.stop()
            UnitBus.sniff(false)
        }
    }
    val entries by McuReader.monitorEntries.collectAsState()
    val now by rememberWallClock(500L)
    if (entries.isEmpty()) return idleFace(Icons.Filled.Sensors, BuiltinKind.CAN_MON.label, stringResource(McuReader.waitingRes), stringResource(R.string.design_can_ids))
    val changed = entries.count { now - it.changedAt < 1_000L }
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.CAN_MON.label,
        value = entries.size.toString(),
        unit = stringResource(R.string.design_can_ids),
        caption = stringResource(R.string.design_can_changed, changed),
        fraction = changed / entries.size.toFloat(),
        stats = listOf(FaceStat(stringResource(R.string.design_can_changed_label), changed.toString())),
        rows = entries.sortedByDescending { it.changedAt }.take(4).map {
            FaceRow("${it.key}  ${it.hex}", fmt("%.1f s", ((now - it.changedAt).coerceAtLeast(0L)) / 1000f))
        }
    )
}

// --- Driving -------------------------------------------------------------------------

@Composable
private fun speedFace(env: SkinTileEnv): WidgetFace {
    val u = LocalUnits.current
    val speed = rememberSpeedKmh()
    // Where the speed comes from, as the bus resolved it.
    val source = speedSourceWord(rememberSpeedSourceKind()) ?: stringResource(R.string.info_speed_no_signal)
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.SPEED_HUD.label,
        value = speed?.let { u.speed(it).toString() } ?: "--", unit = u.speedUnit,
        caption = source,
        fraction = (speed ?: 0) / 200f,
        alert = speedOver(speed),
        number = speed?.let { u.speed(it).toFloat() },
        sign = SignKind.SPEED
    )
}

@Composable
private fun compassFace(): WidgetFace {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val heading by LocationFeed.headingDeg.collectAsState()
    val h = heading
    val u = LocalUnits.current
    return WidgetFace(
        icon = Icons.Filled.Explore,
        title = BuiltinKind.COMPASS.label,
        value = h?.let { stringResource(cardinalRes(it)) } ?: "--",
        unit = h?.let { "${it.roundToInt()}°" } ?: "",
        caption = if (h == null) stringResource(R.string.info_compass_no_heading) else "",
        fraction = h?.let { ((it % 360f) + 360f) % 360f / 360f },
        angle = h,
        fullCircle = true,
        compass = true,
        stats = listOf(
            FaceStat(stringResource(R.string.info_compass_altitude), location?.takeIf { it.hasAltitude() }?.let { u.heightText(it.altitude) } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_gps_speed), location?.let { u.speedText(it.speed * 3.6f) } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_accuracy), location?.let { "±${u.heightText(it.accuracy.toDouble())}" } ?: "--")
        )
    )
}

@Composable
private fun tripFace(): WidgetFace {
    UseLocationFeed()
    val trip by LocationFeed.trip.collectAsState()
    // Re-read each second so the elapsed time keeps moving while parked.
    rememberWallClock(1_000L).longValue
    val u = LocalUnits.current
    val rate = rememberFuelRate()
    val a = TripMath.figures(trip.distanceM / 1000.0, trip.movingMs, rate.use, rate.price)
    val b = rememberSinceFill(trip)
    val since = stringResource(R.string.info_trip_since, remember(trip.startedAt, u) { formatClock(trip.startedAt, u) })
    // Since the fill-up as one row: the designs that list rows show it, the others keep this drive alone.
    val fill = b.fillAt?.let {
        val f = TripMath.figures(b.km, b.movingMs, rate.use, rate.price)
        FaceRow(
            stringResource(R.string.info_trip_since_fill),
            listOfNotNull("${TripMath.figure(u.distance(f.km))} ${u.distanceUnit}", TripMath.litersText(f.liters), f.cost?.let { c -> money(c, rate.currency) })
                .joinToString(", ")
        )
    }
    return WidgetFace(
        icon = Icons.Filled.Timeline,
        title = BuiltinKind.TRIP.label,
        value = TripMath.figure(u.distance(a.km)), unit = u.distanceUnit,
        caption = since,
        reach = since,
        // The first three as before, so a design that shows three still reads the same; the fuel after them.
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.info_trip_time), formatDuration(trip.elapsedMs)),
            FaceStat(stringResource(R.string.info_trip_average), u.speedText(trip.avgSpeedKmh)),
            FaceStat(stringResource(R.string.info_trip_top), u.speedText(trip.maxSpeedKmh)),
            FaceStat(stringResource(R.string.info_trip_fuel), TripMath.litersText(a.liters)),
            a.cost?.let { FaceStat(stringResource(R.string.info_trip_cost), money(it, rate.currency)) }
        ),
        rows = listOfNotNull(fill),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_reset), onClick = { LocationFeed.resetTrip() }))
    )
}

@Composable
private fun gForceFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        GForceFeed.acquire(context)
        onDispose { GForceFeed.release() }
    }
    // Off screen (the page beside the one shown stays composed) the face holds
    // its last reading; the feed, and its peaks, go on. Live again as the page slides in.
    val active = LocalPageActive.current
    val g by remember(active) {
        if (active) GForceFeed.g else kotlinx.coroutines.flow.MutableStateFlow(GForceFeed.g.value)
    }.collectAsState()
    val total = sqrt(g.lateral * g.lateral + g.longitudinal * g.longitudinal)
    // Each axis formatted once and reused (caption and stats), ~15 times a second.
    val lateral = fmt("%+.2f", g.lateral)
    val longitudinal = fmt("%+.2f", g.longitudinal)
    return WidgetFace(
        icon = Icons.Filled.Adjust,
        title = BuiltinKind.GFORCE.label,
        value = fmt("%.2f", total), unit = "g",
        caption = "$lateral / $longitudinal g",
        fraction = total / 1.2f,
        alert = total >= 1f,
        point = g.lateral to g.longitudinal,
        peak = maxOf(g.peakLateral, g.peakLongitudinal),
        stats = listOf(
            FaceStat(stringResource(R.string.info_gforce_lateral), lateral),
            FaceStat(stringResource(R.string.info_gforce_accel_brake), longitudinal),
            FaceStat(stringResource(R.string.info_gforce_peaks), fmt("%.2f / %.2f", g.peakLateral, g.peakLongitudinal))
        ),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_gforce_reset_peaks), onClick = { GForceFeed.resetPeaks() }))
    )
}

@Composable
private fun parkingFace(): WidgetFace {
    val context = LocalContext.current
    UseLocationFeed()
    LaunchedEffect(Unit) { ParkingStore.load(context) }
    val spot by ParkingStore.spot.collectAsState()
    // Read only once a spot is saved (the distance to it): with none, whether
    // there is a fix is all the face shows, and it doesn't follow each fix.
    val location by LocationFeed.location.collectAsState()
    val hasFix by LocationFeed.hasFix.collectAsState()
    // "Parked 5 min ago" moves by the minute.
    rememberWallClock(60_000L).longValue
    val s = spot ?: return idleFace(
        Icons.Filled.LocalParking, BuiltinKind.PARKING.label,
        stringResource(if (hasFix) R.string.info_parking_prompt else R.string.info_waiting_gps),
        action = FaceAction(Icons.Filled.AddLocation, stringResource(R.string.info_parking_save), primary = true,
            enabled = hasFix, onClick = { LocationFeed.location.value?.let { ParkingStore.save(context, it) } }),
        sign = SignKind.PARKING
    )
    val here = location
    val (dist, bearing) = remember(here, s) {
        if (here == null) null to null else {
            val results = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, s.lat, s.lng, results)
            results[0] to results[1]
        }
    }
    val text = dist?.let { formatDistance(it) } ?: "--"
    val ago = formatAgo(s.savedAt)
    val heading by LocationFeed.headingDeg.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.LocalParking,
        title = BuiltinKind.PARKING.label,
        value = text.substringBefore(' '), unit = text.substringAfter(' ', ""),
        caption = if (bearing != null) stringResource(R.string.info_parking_parked_dir, ago, stringResource(cardinalRes(bearing)))
        else stringResource(R.string.info_parking_parked, ago),
        // Closer is fuller: the last 2 km count down to the car.
        fraction = dist?.let { 1f - (it / 2000f).coerceIn(0f, 1f) },
        // The way to the car relative to where the car points now (straight up = ahead).
        angle = bearing?.let { ((it - (heading ?: 0f)) % 360f + 360f) % 360f },
        sign = SignKind.PARKING,
        actions = listOf(
            FaceAction(Icons.Filled.DirectionsWalk, stringResource(R.string.info_parking_walk), primary = true, onClick = { walkTo(context, s) }),
            FaceAction(Icons.Filled.Close, stringResource(R.string.info_clear), onClick = { ParkingStore.clear(context) })
        )
    )
}

// --- Navigation and media ------------------------------------------------------------------

@Composable
private fun directionsFace(env: SkinTileEnv): WidgetFace {
    val nav by NavDirections.state.collectAsState()
    val running = runningNavApp()
    if (!env.hasMediaAccess) {
        return idleFace(Icons.Filled.TurnRight, BuiltinKind.NAVIGATION.label, stringResource(R.string.info_directions_access_title), action = grantAccessAction(),
            sign = SignKind.DIRECTIONS)
    }
    if (!nav.active) {
        return idleFace(
            Icons.Filled.TurnRight, BuiltinKind.NAVIGATION.label,
            running?.let { stringResource(R.string.info_directions_running_title, navAppName(it)) } ?: stringResource(R.string.info_directions_idle_title),
            action = FaceAction(Icons.Filled.Navigation, stringResource(R.string.info_directions_open_maps, navAppName(running ?: navAppToOpen(env.context))), primary = true,
                onClick = { openNavigationApp(env.context, nav) }),
            sign = SignKind.DIRECTIONS
        )
    }
    val (value, unit) = nav.distanceParts
    val time = stringResource(R.string.design_nav_time)
    val distance = stringResource(R.string.design_nav_distance)
    val arrival = stringResource(R.string.design_nav_arrival)
    val art = remember(nav.icon) { nav.icon?.asImageBitmap() }
    return WidgetFace(
        icon = Icons.Filled.TurnRight,
        title = BuiltinKind.NAVIGATION.label,
        value = value.ifEmpty { "--" }, unit = unit,
        caption = nav.instruction,
        stats = nav.etaParts.map { part ->
            val label = when {
                ARRIVAL_TIME.containsMatchIn(part) -> arrival
                NavState.DISTANCE.containsMatchIn(part) -> distance
                else -> time
            }
            FaceStat(label, part)
        },
        art = art,
        sign = SignKind.DIRECTIONS,
        // Closeness to the turn over its last kilometre (the turn card's bar).
        fraction = value.replace(',', '.').toFloatOrNull()?.let { v ->
            val metres = when (unit) { "km" -> v * 1000f; "mi" -> v * 1609f; "ft" -> v * 0.3048f; "yd" -> v * 0.9144f; else -> v }
            1f - (metres / 1000f).coerceIn(0f, 1f)
        },
        onClick = { openNavigationApp(env.context, nav) }
    )
}

/** An ETA part that is a clock time ("14:05", "14h05"): the arrival. */
private val ARRIVAL_TIME = Regex("""^\d{1,2}[:h.]\d{2}""")

@Composable
private fun mediaFace(env: SkinTileEnv): WidgetFace {
    val state = env.mediaState
    if (!env.hasMediaAccess) {
        return idleFace(Icons.Filled.MusicNote, stringResource(R.string.info_now_playing), stringResource(R.string.info_media_access_needed), action = grantAccessAction())
            .let { WidgetFace(it.icon, it.title, stringResource(R.string.info_nothing_playing), caption = it.caption, textValue = true, fraction = 0f, actions = it.actions) }
    }
    val positionMs = rememberMediaPosition(state, env.mediaController)
    val fraction = if (state.durationMs > 0L) (positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else null
    val art = remember(state.artwork) { state.artwork?.asImageBitmap() }
    val controller = env.mediaController
    // A radio's station on the line above the song, for as long as it plays; not again after the artist.
    val station = state.station
    return WidgetFace(
        icon = Icons.Filled.MusicNote,
        title = station.ifEmpty { stringResource(if (!state.isPlaying && state.title.isNotBlank()) R.string.info_paused else R.string.info_now_playing) },
        value = state.title.ifBlank { stringResource(R.string.info_nothing_playing) },
        textValue = true,
        caption = if (station.isNotEmpty()) artistBesideStation(state) else state.artist,
        fraction = fraction,
        art = art,
        active = state.isPlaying,
        stats = if (state.durationMs > 0L) listOf(
            FaceStat(stringResource(R.string.design_elapsed), formatTrackTime(positionMs)),
            FaceStat(stringResource(R.string.design_length), formatTrackTime(state.durationMs))
        ) else emptyList(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous), onClick = { controller.previous() }),
            FaceAction(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(R.string.info_media_play_pause), primary = true, onClick = { controller.playPause() }
            ),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.info_media_next), onClick = { controller.next() })
        ),
        // A tap anywhere else on the tile opens the player itself.
        onClick = { controller.openPlayer() }
    )
}

@Composable
private fun audioFace(): WidgetFace {
    val context = LocalContext.current
    val audio = remember { MediaVolume.audio(context) }
    val onUnit by MediaVolume.onUnit.collectAsState()
    val max = remember(onUnit) { MediaVolume.max(audio) }
    // Follows the hardware knob and other apps, like the standard audio tile.
    var volume by rememberMusicVolume(audio)
    val byKeys by MediaVolume.byKeys.collectAsState()
    val unavailable by MediaVolume.unavailable.collectAsState()
    LaunchedEffect(Unit) { MediaVolume.check(audio) }
    // Whether anything plays: the level meter only bounces then.
    var playing by remember { mutableStateOf(audio.isMusicActive) }
    // Held on a page off screen, like the volume poll above: each composed face asked the audio service every 2 s.
    val pageActive = LocalPageActive.current
    LaunchedEffect(pageActive) {
        if (!pageActive) return@LaunchedEffect
        playing = audio.isMusicActive
        while (true) {
            delay(2_000)
            withFrameMillis { playing = audio.isMusicActive }
        }
    }
    // Where the volume keys are pressed instead (MediaVolume), the level isn't known.
    val muted = !byKeys && volume == 0
    fun act(change: () -> Unit) {
        change()
        volume = MediaVolume.level(audio)
    }
    return WidgetFace(
        icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
        title = BuiltinKind.AUDIO.label,
        value = if (byKeys) "\u2013" else (volume * 100f / max).roundToInt().toString(), unit = if (byKeys) "" else "%",
        caption = stringResource(
            when {
                !byKeys -> R.string.design_media_volume
                unavailable -> R.string.info_audio_unavailable
                else -> R.string.info_audio_by_keys
            }
        ),
        fraction = if (byKeys) 0.5f else volume / max.toFloat(),
        alert = muted || (byKeys && unavailable),
        active = playing,
        actions = listOf(
            FaceAction(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                stringResource(if (muted) R.string.info_audio_unmute else R.string.info_audio_mute),
                primary = true,
                onClick = { act { MediaVolume.toggleMute(context) } }
            ),
            FaceAction(Icons.Filled.Remove, stringResource(R.string.design_volume_down), onClick = { act { MediaVolume.lower(context) } }),
            FaceAction(Icons.Filled.Add, stringResource(R.string.design_volume_up), onClick = { act { MediaVolume.raise(context) } })
        )
    )
}

// --- Info ------------------------------------------------------------------------------------

@Composable
private fun clockFace(): WidgetFace {
    val context = LocalContext.current
    val now = rememberNow(1_000L)
    val locale = Locale.getDefault()
    val u = LocalUnits.current
    // The digits alone: a 12-hour clock's AM / PM goes in the unit, which every design sets small.
    val zone = rememberZone()
    val timeFmt = remember(locale, u.clock24, zone) { SimpleDateFormat(u.digitsPattern(), locale).inZone(zone) }
    val dateFmt = remember(locale, zone) { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).inZone(zone) }
    val cal = remember(now) { Calendar.getInstance().apply { time = now } }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    // No seconds: hours and minutes are what a driver reads, and a face equal
    // to the last one costs nothing on screen (the seconds in the triple stay 0).
    return WidgetFace(
        icon = Icons.Filled.Schedule,
        title = BuiltinKind.CLOCK.label,
        value = timeFmt.format(now), unit = u.amPm(now, locale).orEmpty(),
        caption = dateFmt.format(now).replaceFirstChar { it.uppercase() },
        fraction = m / 60f,
        fullCircle = true,
        clock = Triple(h, m, 0),
        onClick = { openClockApp(context) }
    )
}

@Composable
private fun weatherFace(): WidgetFace {
    val hasFix by LocationFeed.hasFix.collectAsState()
    val u = LocalUnits.current
    val w = rememberWeather() ?: return idleFace(
        Icons.Filled.WbSunny, BuiltinKind.WEATHER.label,
        stringResource(if (!hasFix) R.string.info_waiting_gps else R.string.info_weather_loading), u.tempUnit
    )
    return WidgetFace(
        icon = weatherIcon(w.code),
        title = BuiltinKind.WEATHER.label,
        value = u.temp(w.tempC).toString(), unit = u.tempUnit,
        caption = w.condition,
        // -10 °C .. 40 °C across the gauge.
        fraction = ((w.tempC + 10.0) / 50.0).toFloat().coerceIn(0f, 1f),
        scale = "${u.temp(-10)}°" to "${u.temp(40)}°",
        weatherCode = w.code,
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.design_feels_like), "${u.temp(w.feelsC)}°"),
            FaceStat(stringResource(R.string.design_wind), u.speedText(w.windKmh)),
            if (!w.hiC.isNaN()) FaceStat(stringResource(R.string.design_low_high), "${u.temp(w.loC)}° / ${u.temp(w.hiC)}°") else null
        )
    )
}

@Composable
private fun agendaFace(): WidgetFace {
    val context = LocalContext.current
    // The same events as the standard card: the phone's, else this unit's calendar.
    val agenda = rememberAgendaSource()
    val perm = agenda.access
    val list = agenda.events
    val openCalendar = {
        context.launchSafely(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
        Unit
    }
    if (list.isEmpty() && !agenda.phoneSent && !perm.granted) {
        return idleFace(Icons.Filled.Event, BuiltinKind.CALENDAR.label, stringResource(R.string.info_agenda_needs_access),
            action = FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_agenda_allow), primary = true, onClick = perm.request))
    }
    if (list.isEmpty()) {
        return idleFace(Icons.Filled.Event, BuiltinKind.CALENDAR.label, stringResource(agenda.emptyText),
            action = if (agenda.phoneSent) null else FaceAction(Icons.Filled.Event, stringResource(R.string.info_open), onClick = openCalendar))
    }
    val timeFmt = rememberTimeFormat()
    val dayFmt = remember(timeFmt) { SimpleDateFormat("EEE " + timeFmt.toPattern(), Locale.getDefault()).inZone(timeFmt.timeZone) }
    val noTitle = stringResource(R.string.info_agenda_no_title)
    val allDay = stringResource(R.string.info_agenda_all_day)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    fun whenText(e: AgendaEvent): String = when {
        e.allDay -> allDay
        Calendar.getInstance().apply { timeInMillis = e.begin }.get(Calendar.DAY_OF_YEAR) == today -> timeFmt.format(Date(e.begin))
        else -> dayFmt.format(Date(e.begin))
    }
    val next = list.first()
    // Read each minute: taken once, the approach bar and "started" stood still until the events changed.
    val now = rememberWallClock(60_000L).longValue
    val span = (next.begin - now).coerceAtLeast(0L)
    // Guidance starts after the held step, as on the standard card: a wrong row can be taken back.
    val pending = LocalPendingAction.current
    fun guideTo(e: AgendaEvent) = pending.arm(context.getString(R.string.phone_guidance_to, e.title.ifBlank { e.location })) { guideToEvent(context, e) }
    // Guidance to the next event's place, a tap away.
    val guide = next.takeIf { it.location.isNotBlank() }?.let { e ->
        FaceAction(Icons.Filled.Navigation, stringResource(R.string.phone_guidance_to, e.title.ifBlank { e.location }), primary = true,
            onClick = { guideTo(e) })
    }
    return WidgetFace(
        icon = Icons.Filled.Event,
        title = BuiltinKind.CALENDAR.label,
        value = whenText(next), textValue = next.allDay || !whenText(next).first().isDigit(),
        caption = next.title.ifBlank { noTitle } + if (next.location.isNotBlank()) " · ${next.location}" else "",
        // The next event's approach over the coming 3 hours.
        fraction = 1f - (span / (3 * 3_600_000f)).coerceIn(0f, 1f),
        rows = list.map { e ->
            FaceRow(e.title.ifBlank { noTitle }, whenText(e), alert = e.begin <= now,
                onClick = if (e.location.isNotBlank()) ({ guideTo(e) }) else null)
        },
        events = list.filter { !it.allDay }.map { FaceEvent(it.begin, it.end, it.title.ifBlank { noTitle }) },
        actions = listOfNotNull(guide),
        // The unit's calendar app knows nothing of the phone's events.
        onClick = if (agenda.fromPhone) null else openCalendar
    )
}

@Composable
private fun quickDialFace(): WidgetFace {
    val context = LocalContext.current
    // The same contacts as the standard card: the phone's favourites, else this unit's starred ones.
    val source = rememberQuickDialSource()
    val perm = source.access
    val favourites = source.favourites
    val dialer = FaceAction(Icons.Filled.Dialpad, stringResource(R.string.info_quickdial_dialer), onClick = { PhoneScreen.open(context) })
    // Every call waits in the tile's strip first, as on the standard card: "Calling X", Cancel, three seconds.
    val pending = LocalPendingAction.current
    fun call(name: String, number: String) = pending.arm(context.getString(R.string.phone_calling, name)) { dialNumber(context, number) }
    // A missed call on the phone: calling back comes first.
    val callBack = source.callBack?.let { missed ->
        FaceAction(Icons.Filled.PhoneMissed, stringResource(R.string.phone_call_back, missed.name ?: missed.number), primary = true,
            onClick = { call(missed.name ?: missed.number, missed.number) })
    }
    if (favourites.isEmpty() && !source.phoneSent && !perm.granted) {
        return idleFace(Icons.Filled.Call, BuiltinKind.QUICK_DIAL.label, stringResource(R.string.info_quickdial_needs_access),
            action = FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_quickdial_allow), primary = true, onClick = perm.request))
            .let { WidgetFace(it.icon, it.title, "--", caption = it.caption, actions = it.actions + dialer) }
    }
    if (favourites.isEmpty()) {
        return idleFace(Icons.Filled.Call, BuiltinKind.QUICK_DIAL.label, stringResource(source.emptyText), action = callBack ?: dialer)
    }
    fun dial(f: Favourite) {
        f.number?.let { call(f.name, it) }
    }
    fun initials(name: String) = name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    val first = favourites.first()
    return WidgetFace(
        icon = Icons.Filled.Call,
        title = BuiltinKind.QUICK_DIAL.label,
        value = first.name.substringBefore(' '), textValue = true,
        caption = first.number.orEmpty(),
        rows = favourites.map { f -> FaceRow(f.name, f.number.orEmpty(), badge = initials(f.name), onClick = { dial(f) }) },
        stats = favourites.drop(1).take(3).map { FaceStat(initials(it.name), it.name.substringBefore(' ')) },
        actions = listOfNotNull(
            callBack,
            FaceAction(Icons.Filled.Call, stringResource(R.string.design_call_first, first.name.substringBefore(' ')), primary = callBack == null,
                enabled = first.number != null, onClick = { dial(first) }),
            dialer
        )
    )
}

@Composable
private fun notificationsFace(env: SkinTileEnv): WidgetFace {
    val items by NotificationFeed.items.collectAsState()
    val phoneConnected = PhoneLink.state.collectAsState().value is PhoneLinkState.Connected
    // As the plain tile: the unit's access only while its own notifications are wanted, and never over the phone's (#123).
    if (!env.hasMediaAccess && NotificationFeed.showsUnit(env.context) && !phoneConnected && items.none { it.fromPhone }) {
        return idleFace(Icons.Filled.Notifications, BuiltinKind.NOTIFICATIONS.label, stringResource(R.string.info_notif_needs_access), action = grantAccessAction())
    }
    val timeFmt = rememberTimeFormat()
    val latest = items.firstOrNull()
    return WidgetFace(
        icon = Icons.Filled.Notifications,
        title = BuiltinKind.NOTIFICATIONS.label,
        value = items.size.toString(),
        unit = stringResource(R.string.design_notif_new_unit),
        caption = latest?.let { n -> listOf(n.title.ifEmpty { n.appLabel }, n.text).filter { it.isNotEmpty() }.joinToString(": ") }
            ?: stringResource(R.string.info_notif_empty),
        fraction = (items.size / 10f).coerceIn(0f, 1f),
        rows = items.map { n ->
            FaceRow(
                n.title.ifEmpty { n.appLabel }, timeFmt.format(Date(n.postedAt)), onClick = { runCatching { n.contentIntent?.send() } },
                onDismiss = { NotificationFeed.remove(n.key) }, key = n.key
            )
        },
        stats = latest?.let { listOf(FaceStat(stringResource(R.string.design_latest), timeFmt.format(Date(it.postedAt)))) }.orEmpty(),
        events = items.take(6).map { FaceEvent(it.postedAt, null, it.title.ifEmpty { it.appLabel }) },
        actions = if (items.isEmpty()) emptyList() else listOf(
            FaceAction(Icons.Filled.ClearAll, stringResource(R.string.info_clear), onClick = { NotificationFeed.dismissAll() })
        )
    )
}

/** Stand-in reading for the design picker, for a kind with no face of its own. */
@Composable
internal fun sampleFace(kind: BuiltinKind): WidgetFace = WidgetFace(
    icon = kindIcon(kind),
    title = kind.label,
    value = when (kind) {
        BuiltinKind.CLOCK -> "12:30"
        BuiltinKind.MEDIA, BuiltinKind.QUICK_DIAL, BuiltinKind.CALENDAR -> kind.label
        else -> "88"
    },
    textValue = kind == BuiltinKind.MEDIA || kind == BuiltinKind.QUICK_DIAL || kind == BuiltinKind.CALENDAR,
    caption = kind.blurb,
    fraction = 0.6f,
    fullCircle = kind == BuiltinKind.COMPASS || kind == BuiltinKind.CLOCK,
    clock = if (kind == BuiltinKind.CLOCK) Triple(12, 30, 0) else null,
    compass = kind == BuiltinKind.COMPASS
)

// --- Car care (CarCareTiles.kt) -------------------------------------------------------

@Composable
private fun filterFace(): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    if (!car.known || !car.particleFilter) return idleFace(
        kindIcon(BuiltinKind.FILTER_CARE), BuiltinKind.FILTER_CARE.label,
        stringResource(if (car.known) R.string.car_filter_none else R.string.car_filter_unknown)
    )
    val streak = care.filter.shortStreak
    val call = filterCall(streak, LocalUnits.current.imperial)
    val drive = care.drive
    val last = care.filter.lastLongAt
    // Once a minute: "last long drive 3 h ago" otherwise stands still until the care state changes.
    val now = rememberWallClock(60_000L).longValue
    // The real soot load, when the experimental reading finder got the car to give it up.
    val extra by PidExplorer.readings.collectAsState()
    val soot = extra[ExtraReading.SOOT_LOAD]?.value
    return WidgetFace(
        icon = kindIcon(BuiltinKind.FILTER_CARE),
        title = BuiltinKind.FILTER_CARE.label,
        value = streak.toString(),
        unit = pluralStringResource(R.plurals.car_filter_short_unit, streak),
        stats = listOfNotNull(
            soot?.let { FaceStat(stringResource(R.string.explore_soot_load), extraValueText(ExtraReading.SOOT_LOAD, it)) },
            extra[ExtraReading.DPF_TEMP]?.let { FaceStat(stringResource(R.string.explore_dpf_temp), extraValueText(ExtraReading.DPF_TEMP, it.value)) },
            extra[ExtraReading.REGEN_ACTIVE]?.let { FaceStat(stringResource(R.string.explore_regen), extraValueText(ExtraReading.REGEN_ACTIVE, it.value)) }
        ),
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        fraction = drive?.let { it.hotFastMs.toFloat() / CareRules.LONG_DRIVE_MS },
        rows = listOfNotNull(
            drive?.let {
                FaceRow(stringResource(R.string.car_filter_this_drive, (it.hotFastMs / 60_000).toInt(), (CareRules.LONG_DRIVE_MS / 60_000).toInt()), "")
            },
            FaceRow(
                if (last > 0) stringResource(R.string.car_filter_last_long, android.text.format.DateUtils.getRelativeTimeSpanString(last, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                else stringResource(R.string.car_filter_last_long_never),
                ""
            )
        )
    )
}

@Composable
private fun warmupFace(env: SkinTileEnv): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    // The coolant moves a degree at a time: follow it, not every OBD sample.
    val t = rememberGaugeInt(K.COOLANT_TEMP) ?: 0
    val u = LocalUnits.current
    // A coolant reading from any source shows; with none, the adapter's state says what to do.
    if (t == 0) {
        if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.WARMUP, env, u.tempUnit)
        return idleFace(kindIcon(BuiltinKind.WARMUP), BuiltinKind.WARMUP.label, stringResource(R.string.car_waiting_obd), u.tempUnit)
    }
    val call = warmupCall(t, car)
    // Ticks each second only while a drive runs, for its "running for" line.
    val runningFor = care.drive?.let { d ->
        val now by rememberWallClock(1_000L)
        stringResource(R.string.car_running_for, formatDuration(now - d.startedAt))
    }
    return WidgetFace(
        icon = kindIcon(BuiltinKind.WARMUP),
        title = BuiltinKind.WARMUP.label,
        value = u.temp(t).toString(), unit = u.tempUnit,
        caption = stringResource(call.text, car.coldRpmLimit),
        alert = call.level > 0,
        severity = call.level,
        fraction = t.toFloat() / car.hotC,
        scale = "${u.temp(0)}°" to "${u.temp(car.hotC)}°",
        rows = listOfNotNull(runningFor?.let { FaceRow(it, "") })
    )
}

@Composable
private fun batteryFace(env: SkinTileEnv): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    // The voltage and whether the engine runs.
    val g = rememberGauges(K.BATTERY_VOLTS, K.RPM)
    val v = g.voltage ?: 0.0
    val engineRuns = (g.rpm ?: 0) > LiveWatch.RUNNING_RPM
    // Volts from any source show (the unit's own, without an adapter); with none, the adapter's state says what to do.
    if (v < LiveWatch.MIN_PLAUSIBLE_V || v > LiveWatch.MAX_PLAUSIBLE_V) {
        if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.BATTERY, env, "V")
        return idleFace(kindIcon(BuiltinKind.BATTERY), BuiltinKind.BATTERY.label, stringResource(R.string.car_waiting_obd), "V")
    }
    val running = engineRuns
    val call = batteryCall(v, running)
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BATTERY),
        title = BuiltinKind.BATTERY.label,
        value = fmt("%.1f", v), unit = "V",
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        fraction = batteryCareFraction(v).coerceIn(0f, 1f),
        rows = listOf(
            FaceRow(
                car.batteryAh?.let { stringResource(R.string.car_battery_capacity, it) }
                    ?: stringResource(if (running) R.string.car_battery_hint_running else R.string.car_battery_hint_off),
                ""
            )
        )
    )
}

@Composable
private fun ecoFace(): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val drive = care.drive ?: care.lastDrive
        ?: return idleFace(kindIcon(BuiltinKind.ECO_DRIVE), BuiltinKind.ECO_DRIVE.label, stringResource(R.string.car_eco_empty), "/ 100")
    val score = drive.ecoScore
    val liters = drive.distanceKm * car.typicalUse / 100
    val call = ecoCall(score)
    val u = LocalUnits.current
    // Whole currency units while driving; the cents are for a look while parked.
    val moving = LocalDriveLock.current.moving
    return WidgetFace(
        icon = kindIcon(BuiltinKind.ECO_DRIVE),
        title = stringResource(if (care.drive != null) R.string.car_eco_title else R.string.car_eco_title_last),
        value = score?.toString() ?: "--", unit = "/ 100",
        caption = stringResource(call.text),
        alert = call.level >= 2,
        severity = call.level,
        fraction = score?.let { it / 100f },
        stats = listOfNotNull(
            drive.sweetPercent?.let { FaceStat(stringResource(R.string.car_eco_band, car.sweetBand.first, car.sweetBand.last), "$it %") },
            FaceStat(stringResource(R.string.car_eco_hard), stringResource(R.string.car_eco_hard_value, drive.hardAccel, drive.hardBrake)),
            if (car.gearbox == GearboxType.ROBOTISED) FaceStat(stringResource(R.string.car_eco_clutch), drive.clutchHolds.toString()) else null,
            FaceStat(
                stringResource(if (u.imperial) R.string.units_eco_fuel_mi else R.string.car_eco_fuel, fmt("%.1f", u.distance(drive.distanceKm))),
                stringResource(R.string.car_eco_fuel_value, fmt("%.1f", liters), fmt(if (moving) "%.0f" else "%.2f", liters * FuelLog.price(car)), car.currency)
            )
        )
    )
}

@Composable
private fun fuelToDestFace(): WidgetFace {
    val u = LocalUnits.current
    val trip = rememberFuelToDest()
        ?: return idleFace(kindIcon(BuiltinKind.FUEL_TO_DEST), BuiltinKind.FUEL_TO_DEST.label, stringResource(R.string.car_fuel_dest_no_range), u.distanceUnit)
    val range = u.distance(trip.rangeKm)
    val verdict = trip.verdict
    val icon = kindIcon(BuiltinKind.FUEL_TO_DEST)
    val title = BuiltinKind.FUEL_TO_DEST.label
    if (verdict == null) {
        return WidgetFace(icon = icon, title = title, value = range.toString(), unit = u.distanceUnit, caption = stringResource(R.string.car_fuel_dest_no_nav), sign = SignKind.FUEL)
    }
    val call = fuelCall(verdict)
    val km = u.distance(trip.km)
    return WidgetFace(
        icon = icon, title = title,
        value = u.distance(trip.spareKm).toString(),
        unit = stringResource(if (u.imperial) R.string.units_fuel_dest_spare_unit_mi else R.string.car_fuel_dest_spare_unit),
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        sign = SignKind.FUEL,
        scale = "E" to "F",
        reach = "$range ${u.distanceUnit}",
        marker = "${km.toInt()} ${u.distanceUnit}",
        fraction = trip.usedFraction,
        rows = listOf(FaceRow(stringResource(if (u.imperial) R.string.units_fuel_dest_detail_mi else R.string.car_fuel_dest_detail, range, km.toInt()), ""))
    )
}

@Composable
private fun breakFace(): WidgetFace {
    val care by CarCare.state.collectAsState()
    val due = CareRules.FIRST_BREAK_MIN * 60_000L
    val driving = care.rest.drivingMs
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BREAK_TIMER),
        title = BuiltinKind.BREAK_TIMER.label,
        value = formatDuration(driving),
        caption = stringResource(if (driving >= due) R.string.car_break_due else R.string.car_break_ok),
        alert = driving >= due,
        severity = if (driving >= due) 2 else if (driving >= due - 20 * 60_000L) 1 else 0,
        sign = SignKind.REST,
        fraction = driving.toFloat() / due,
        rows = listOf(FaceRow(stringResource(R.string.car_break_hint), ""))
    )
}

// --- Servicing and fuel prices ------------------------------------------------------------------

@Composable
private fun serviceFace(): WidgetFace {
    val state by Maintenance.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (editing) UpkeepDialog(onDismiss = { editing = false })
    val reveal = rememberEditReveal()
    val lock = LocalDriveLock.current
    // Once an hour: a unit left on overnight otherwise keeps yesterday's "due in N days".
    val now = rememberWallClock(3_600_000L).longValue
    val dues = remember(state, now) { state.statuses(now) }
    val first = dues.firstOrNull { it.stage != UpkeepStage.UNKNOWN }
    val odo = state.odometer
    val u = LocalUnits.current
    val caption = when {
        odo == null -> stringResource(R.string.upkeep_no_odometer)
        first == null -> stringResource(R.string.upkeep_needs_dates)
        first.stage == UpkeepStage.OK -> stringResource(R.string.upkeep_all_good)
        else -> upkeepLine(first)
    }
    // How far into its interval the next item is.
    val fraction = first?.let { d ->
        val interval = state.plan.firstOrNull { it.kind == d.kind }
        when {
            d.kmLeft != null && interval?.everyKm != null -> 1f - (d.kmLeft.toFloat() / interval.everyKm)
            d.daysLeft != null && interval?.everyMonths != null -> 1f - (d.daysLeft.toFloat() / (interval.everyMonths * 30f))
            else -> null
        }?.coerceIn(0f, 1f)
    }
    return WidgetFace(
        icon = kindIcon(BuiltinKind.SERVICE),
        title = BuiltinKind.SERVICE.label,
        value = odo?.let { formatKm(u.distance(it.nowKm)) } ?: "--", unit = u.distanceUnit,
        caption = caption,
        fraction = fraction,
        alert = first?.stage == UpkeepStage.DUE || first?.stage == UpkeepStage.SOON,
        rows = dues.map { FaceRow(stringResource(it.kind.labelRes), upkeepLeft(it), alert = it.stage == UpkeepStage.DUE) },
        onClick = { reveal.shown = !reveal.shown },
        edit = FaceEdit(reveal) { lock.whenParked { editing = true } }
    )
}

@Composable
private fun fuelPricesFace(): WidgetFace {
    val context = LocalContext.current
    val icon = kindIcon(BuiltinKind.FUEL_PRICES)
    val title = BuiltinKind.FUEL_PRICES.label
    val perm = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    if (!perm.granted) {
        return idleFace(icon, title, stringResource(R.string.fuel_allow_location), "€/L",
            FaceAction(Icons.Filled.LockOpen, stringResource(R.string.fuel_allow_location), primary = true, onClick = perm.request))
    }
    val error by FuelPriceRepo.error.collectAsState()
    val hasFix by LocationFeed.hasFix.collectAsState()
    val nearby = rememberFuelNearby()
        ?: return idleFace(icon, title, stringResource(
            when {
                error != null -> R.string.fuel_error
                !hasFix -> R.string.info_waiting_gps
                else -> R.string.fuel_loading
            }
        ), "€/L")
    if (nearby.ranked.isEmpty()) return idleFace(icon, title, fuelNoneText(), "€/L")
    val best = nearby.ranked.first()
    // A new route only after the held step, as on the standard card: "Guidance to X", Cancel, three seconds.
    val pending = LocalPendingAction.current
    fun goTo(station: FuelStation) = pending.arm(context.getString(R.string.phone_guidance_to, station.name.ifBlank { station.town })) {
        navigateTo(context, station.lat, station.lng, station.label)
    }
    return WidgetFace(
        icon = icon, title = title,
        value = FuelPrices.formatPrice(best.price), unit = "€/L",
        caption = stringResource(R.string.fuel_cheapest, nearby.grade.label) + " · " + best.station.name.ifBlank { best.station.town } +
            " · " + FuelPrices.formatDistance(best.distanceKm),
        stats = listOf(
            FaceStat(nearby.grade.label, FuelPrices.formatPriceWithCurrency(best.price)),
            FaceStat(stringResource(R.string.fuel_navigate), FuelPrices.formatDistance(best.distanceKm))
        ),
        rows = nearby.ranked.take(6).map { r ->
            FaceRow(
                FuelPrices.formatPriceWithCurrency(r.price) + " · " + listOf(r.station.name, r.station.town).filter { it.isNotBlank() }.joinToString(", "),
                FuelPrices.formatDistance(r.distanceKm),
                onClick = { goTo(r.station) }
            )
        },
        onClick = { goTo(best.station) }
    )
}

@Composable
private fun carStatusFace(): WidgetFace {
    val trip = rememberPsaTrip()
    val rows = carStatusRows(trip)
    if (rows.isEmpty()) return idleFace(Icons.Filled.DirectionsCarFilled, BuiltinKind.CAR_STATUS.label, stringResource(R.string.car_status_waiting))
    val lights = rememberVehicle(K.LIGHTS).orEmpty()
    val u = LocalUnits.current
    // The PSA trip computer sends no mileage: its range leads instead.
    val odometer = rememberVehicle(K.ODOMETER)?.takeIf { it > 0.0 }
    return WidgetFace(
        icon = Icons.Filled.DirectionsCarFilled,
        title = BuiltinKind.CAR_STATUS.label,
        value = odometer?.let { NumberFormat.getIntegerInstance().format(u.distance(it).toLong()) }
            ?: trip?.rangeKm?.let { u.distance(it.toDouble()).toInt().toString() } ?: "--",
        unit = if (u.imperial) u.distanceUnit else stringResource(R.string.car_status_km),
        caption = rows.firstOrNull()?.second.orEmpty(),
        alert = CarLight.HAZARD in lights,
        severity = if (CarLight.HAZARD in lights) 1 else 0,
        rows = rows.map { (label, value) -> FaceRow(label, value) }
    )
}

@Composable
private fun tyresFace(): WidgetFace {
    val tyres = rememberTyres()
    val judged by Tyres.problems.collectAsState()
    val unit by Tyres.unit.collectAsState()
    if (tyres.isEmpty()) return idleFace(Icons.Filled.TireRepair, BuiltinKind.TYRES.label, stringResource(R.string.car_tyres_waiting))
    val order = listOf(TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT)
    val problems = judged.toList()
    val lowest = tyres.filterKeys { it in order }.values.minByOrNull { it.kPa }
    val u = LocalUnits.current
    val wheels = order.map { pos ->
        tyres[pos]?.let { t ->
            val problem = judged[pos]
            // "Low pressure · 2.3 bar, 29 °C": what's wrong, then the reading (the Tyre map splits it the same way).
            val flags = listOfNotNull(problem?.let { stringResource(it.labelRes) }, stringResource(R.string.car_tyres_sensor_battery).takeIf { t.sensorBatteryLow })
            val reading = "${formatPressure(t.kPa, unit)}, ${u.tempText(t.celsius)}"
            // The unit's TPMS app says low or not for every tyre; the USB receiver's frame has no known battery bit, so a receiver Dashwheel reads itself gets no mark.
            FaceRow(stringResource(pos.labelRes), (flags + reading).joinToString(" · "), alert = problem != null, battery = (!t.sensorBatteryLow).takeIf { Tyres.sampleFromApp() })
        }
    }
    val rows = wheels.filterNotNull()
    val shown = lowest?.let { formatPressure(it.kPa, unit) }
    return WidgetFace(
        icon = Icons.Filled.TireRepair,
        title = BuiltinKind.TYRES.label,
        value = shown?.substringBefore(' ') ?: "--",
        unit = shown?.substringAfter(' ').orEmpty(),
        caption = problems.firstOrNull()?.let { (pos, p) -> "${stringResource(pos.labelRes)}: ${stringResource(p.labelRes)}" }
            ?: stringResource(R.string.car_tyres_ok),
        alert = problems.isNotEmpty(),
        severity = if (problems.isNotEmpty()) 2 else 0,
        rows = rows,
        wheels = wheels
    )
}
