package com.openauto.dash

import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.K
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Car problems: the car's own messages first (a Peugeot, Citroën or DS's,
 * read off its CAN box with no OBD adapter, [rememberCarMessages]), then the
 * fault codes (OBD Diagnostic Trouble Codes). Scans run by themselves when the
 * adapter connects (see [AiMechanic]); Scan / Clear are here for doing it by
 * hand. The tile is the overview (verdict, one card per code); a code opens
 * the AI mechanic's full explanation. A short tile keeps to the verdict and
 * the number of codes ([CompactFaults]) and opens the overview in a sheet. Without a Gemini key each code shows
 * the built-in table's description. The microphone by the title asks the
 * mechanic anything about the car, answered from the live readings.
 */
@Composable
internal fun ObdDtcCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onPickDevice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = connection == ObdConnectionState.CONNECTED
    val ai by AiMechanic.state.collectAsState()
    val codes = ai.codes
    val diagnosis = ai.diagnosis
    // The car's own messages, the most serious first; asked for while the tile is on screen.
    // The engine's own warning ("engine fault, have it repaired", the pollution ones) says no more than its
    // fault codes once they are read: then only the codes show.
    val carMessages = rememberCarMessages().let { all ->
        if (codes.isNullOrEmpty()) all else all.filter { it.code !in PsaWarnings.engineLamp }
    }
    // Each problem numbered, the car's messages first, then the engine's codes: the
    // same number on the car's picture and in the list.
    val messageTexts = carMessages.map { carMessageText(it.code) }
    val codeSerious = if (diagnosis?.severity == Severity.STOP) 2 else 1
    // A code's words on the car: what it means (the mechanic's, else the built-in table's) and the code.
    val codeNames = codes.orEmpty().map { c ->
        (diagnosis?.codes?.firstOrNull { it.code == c }?.meaning?.ifBlank { null } ?: ObdCodes.describe(c).localizedTitle()) + " · " + c
    }
    val mapProblems = remember(carMessages, messageTexts, codes, diagnosis, codeNames) {
        carMessages.mapIndexedNotNull { i, m ->
            CarProblemMap.spotOf(m.code)?.let { spot -> MapProblem(spot, m.serious, messageTexts[i], number = i + 1) }
        } + codes.orEmpty().mapIndexedNotNull { i, code ->
            CarProblemMap.spotOfCode(code)?.let { spot -> MapProblem(spot, codeSerious, codeNames[i], number = carMessages.size + i + 1) }
        }
    }
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    val verdict = carVerdict(carMessages.map { it.level }, codes, diagnosis?.severity, lamp?.on == true)
    val problemCount = carMessages.count { it.level != PsaWarnings.Level.INFO } + codes.orEmpty().size
    val problemNames = messageTexts.filterIndexed { i, _ -> carMessages[i].level != PsaWarnings.Level.INFO } +
        codes.orEmpty().map { c -> diagnosis?.codes?.firstOrNull { it.code == c }?.meaning?.ifBlank { null } ?: ObdCodes.describe(c).localizedTitle() }
    // Only whether the engine runs matters here: the card must not redraw on every OBD sample.
    val rpm = rememberGaugeInt(K.RPM)
    val engineOff = rpm == null || rpm == 0
    val pending by ObdBluetoothManager.pending.collectAsState()
    // The AI's advice is written in the mechanic's language; its labels follow it.
    // The setting is re-read as the AI's state moves on (a cheap prefs lookup);
    // the localised resources are rebuilt only when the language itself changes.
    val aiLanguage = remember(ai, context) { AiSettings.load(context).language }
    val aiText = remember(aiLanguage, context) { aiLanguage.resources(context) }
    // Asked from the microphone by the title: about the car as it is now, no fault needed.
    val ask by AskMechanic.state.collectAsState()
    val askAboutCar = rememberAskQuestion { AskMechanic.listenAboutCar(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<DtcMessage?>(null) }
    // The code whose detail sheet is open.
    var opened by remember { mutableStateOf<String?>(null) }
    // The drive lock (DriveLock.kt) holds back the sheet's reading while the car moves; the voice stays.
    val lockWhileMoving = remember(context) { DriveLockStore.load(context) }
    val moving by rememberMoving(lockWhileMoving, DemoMode.isOn)
    val scanFailed = stringResource(R.string.ai_scan_failed)
    val clearFailed = stringResource(R.string.ai_clear_failed)
    val clearedText = stringResource(R.string.ai_cleared)
    val clearCodes = rememberClearCodes {
        busy = true; message = null
        scope.launch {
            val res = ObdBluetoothManager.clearTroubleCodes()
            busy = false
            res.onSuccess { message = DtcMessage(clearedText, failed = false); AiMechanic.cleared() }
                .onFailure { message = DtcMessage(it.message ?: clearFailed, failed = true) }
        }
    }

    val scan: () -> Unit = {
        busy = true; message = null
        scope.launch {
            val r = ObdBluetoothManager.readTroubleCodes()
            busy = false
            r.onSuccess { AiMechanic.report(it, announce = false) }
                .onFailure { message = DtcMessage(it.message ?: scanFailed, failed = true) }
        }
    }
    // A short tile shows the verdict alone; a tap on it opens the whole tile in a sheet.
    var expanded by remember { mutableStateOf(false) }
    // With the car in 3D: the problem it is turned to (a tap on a row or a chip), by number,
    // 0 the whole car; at first the most serious one.
    val in3d = rememberCar3DModel() != null
    var picked by remember { mutableStateOf<Int?>(null) }
    val turnedTo = picked?.takeIf { p -> p == 0 || mapProblems.any { it.number == p } }
        ?: mapProblems.maxByOrNull { it.serious }?.number ?: 0

    // The title row, shared by the tile and the sheet ([onClose]: the sheet's).
    val header: @Composable (onClose: (() -> Unit)?) -> Unit = { onClose ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.vehicle_fault_codes_title),
                color = DashColors.AccentInk,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            AskIconButton(ask, aiText, onClick = askAboutCar)
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = aiText.getString(R.string.ai_close), tint = DashColors.TextSecondary)
                }
            }
        }
    }
    // Everything under the title. It sits whole in one scroll area, so a tile
    // too short for it clips nothing: the rest is a swipe away.
    // [mapHeight]: the car's views with their numbered problems, where there is room (null: none).
    val body: @Composable (mapHeight: Dp?) -> Unit = { mapHeight ->
        // The answer to "can I drive?" first, then where, then what.
        VerdictBanner(verdict, problemCount, lamp?.on == true)
        Spacer(Modifier.height(12.dp))
        // A question about the car, asked here or hands-free; the detail sheet shows its own.
        if (ask != AskMechanic.State.Idle && opened == null) {
            AskPanel(ask, aiText, onReplay = { AskMechanic.replay(context) }, compact = true, onClose = AskMechanic::cancel)
            Spacer(Modifier.height(10.dp))
        }
        if (mapHeight != null && mapProblems.isNotEmpty()) {
            CarProblemsMap(
                mapProblems, Modifier.fillMaxWidth().height(mapHeight),
                selected = turnedTo, onSelect = { picked = it }, interactive = !moving
            )
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            carMessages.forEachIndexed { i, m ->
                val number = i + 1
                ProblemRow(
                    number = number, serious = m.serious, title = messageTexts[i], detail = null,
                    source = stringResource(R.string.car_problem_from_car), pending = false, onOpen = null,
                    selected = in3d && turnedTo == number,
                    onSelect = if (in3d && mapProblems.any { it.number == number }) ({ picked = number }) else null
                )
            }
            codes.orEmpty().forEachIndexed { index, code ->
                val advice = adviceFor(diagnosis, codes.orEmpty(), code, index)
                val builtIn = remember(code) { ObdCodes.describe(code) }
                val number = carMessages.size + index + 1
                ProblemRow(
                    number = number, serious = codeSerious,
                    title = (advice?.meaning?.ifBlank { null } ?: builtIn.localizedTitle()) + " · " + code,
                    detail = advice?.checkFirst?.ifBlank { null }?.let { aiText.getString(R.string.ai_check_first, it) } ?: builtIn.localizedFix(),
                    source = stringResource(R.string.car_problem_from_code) + if (advice != null) "\n" + stringResource(R.string.car_problem_with_ai) else "",
                    pending = code in pending,
                    onOpen = if (advice != null) ({ opened = code }) else null,
                    selected = in3d && turnedTo == number,
                    onSelect = if (in3d && mapProblems.any { it.number == number }) ({ picked = number }) else null
                )
            }
        }
        if (carMessages.isNotEmpty() || !codes.isNullOrEmpty()) Spacer(Modifier.height(12.dp))

        if (!connected) {
            ObdNotConnected(connection, onConnect, onPickDevice)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy,
                    onClick = scan,
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent)
                ) { Text(stringResource(R.string.vehicle_scan)) }
                Button(
                    enabled = !busy && codes?.isNotEmpty() == true,
                    onClick = clearCodes,
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                ) { Text(stringResource(R.string.vehicle_clear)) }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.text, color = if (it.failed) DashColors.Warning else DashColors.Good, style = MaterialTheme.typography.bodyMedium)
            }
            // Ignition on, engine off: every dashboard lamp is lit for its self-test,
            // which the "lamp off" badge would otherwise seem to contradict.
            if (engineOff) {
                Spacer(Modifier.height(8.dp))
                Row {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vehicle_engine_off_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // The alerts the bar showed lately, so one that came and went can still be read.
        if (AlertCenter.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            RecentAlerts()
        }

        // Results stay readable after the adapter drops (engine off, parked).
        if (codes != null) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (codes.isEmpty() && lamp?.on == true) {
                    // Never a green "no fault" while the engine computer says its lamp is on.
                    Text(stringResource(R.string.vehicle_lamp_no_codes), color = DashColors.Warning, style = MaterialTheme.typography.bodyLarge)
                } else if (codes.isEmpty()) {
                    Text(stringResource(R.string.ai_no_codes), color = DashColors.Good, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.vehicle_obd_scope), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    // What the mechanic made of it all, under the numbered list (the banner already gave the verdict).
                    diagnosis?.let { d ->
                        Text(d.summary, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        if (d.raisedByRules) {
                            Text(aiText.getString(R.string.ai_rules_raised), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        AiDisclaimer(d, aiText)
                    }
                    if (ai.thinking) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = DashColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.ai_thinking), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    ai.note?.let { note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                AiMechanic.noteText(context, note),
                                color = DashColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (ai.canRetry) {
                                TextButton(onClick = AiMechanic::refresh) { Text(stringResource(R.string.ai_retry), color = DashColors.AccentInk) }
                            }
                        }
                    }
                }
            }
        }
    }

    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Under about 200 dp (the default 3x2 included) the title, the microphone
            // and the buttons would leave no room for the results.
            if (maxHeight < 200.dp) {
                CompactFaults(
                    verdict = verdict, count = problemCount, names = problemNames, thinking = ai.thinking,
                    connected = connected, busy = busy, message = message,
                    map = if (mapProblems.isNotEmpty() && maxWidth >= 360.dp && maxHeight >= 160.dp) {
                        { CarProblemsMap(mapProblems, Modifier.size(width = 96.dp, height = 96.dp), compact = true) }
                    } else null,
                    oneLine = maxHeight < 130.dp, onScan = scan, onOpen = { expanded = true }
                )
            } else {
                val tileHeight = maxHeight
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    header(null)
                    Spacer(Modifier.height(10.dp))
                    // The car with its numbered problems where the tile is tall enough for it, the banner and the list.
                    val mapHeight = if (tileHeight >= 420.dp) (tileHeight * 0.38f).coerceAtMost(300.dp) else null
                    Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { body(mapHeight) }
                }
            }
        }
    }

    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(
                shape = DashShape.Large,
                color = DashColors.Card.copy(alpha = 1f),
                // Docked app windows are drawn above dialogs on this unit; the ones under it step aside.
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f).fillMaxHeight(0.94f).keepClearOfWindows()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    header { expanded = false }
                    Spacer(Modifier.height(10.dp))
                    Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { body(260.dp) }
                }
            }
        }
    }

    opened?.let { code ->
        val index = codes?.indexOf(code) ?: -1
        val advice = if (codes != null && index >= 0) adviceFor(diagnosis, codes, code, index) else null
        // A new scan can take the code away while the sheet is open: then it just closes.
        if (advice != null) FaultDetailSheet(code, advice, diagnosis, codes.orEmpty(), aiText, moving) { opened = null }
    }
}

/**
 * The tile at its short sizes: how many codes and the verdict, large, and one
 * Scan button ([oneLine]: side by side, on a tile one row high). A tap
 * anywhere else opens the whole tile in a sheet ([onOpen]).
 */
@Composable
private fun CompactFaults(
    verdict: CarVerdict,
    count: Int,
    /** What each problem is, in a few words: the car's messages, then the codes. */
    names: List<String>,
    thinking: Boolean,
    connected: Boolean,
    busy: Boolean,
    message: DtcMessage?,
    /** The car with its problems on it, small, before the summary; null where there's no room. */
    map: (@Composable () -> Unit)?,
    oneLine: Boolean,
    onScan: () -> Unit,
    onOpen: () -> Unit
) {
    val failed = message?.takeIf { it.failed }
    val colour = verdict.colour()
    val summary: @Composable (Modifier) -> Unit = { m ->
        Row(modifier = m, verticalAlignment = Alignment.CenterVertically) {
            if (map != null) {
                map()
                Spacer(Modifier.width(12.dp))
            }
            if (count > 0) {
                Text(count.toString(), color = colour, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    failed?.text ?: stringResource(if (thinking && verdict == CarVerdict.UNREAD) R.string.ai_thinking else verdict.titleRes),
                    color = if (failed != null) DashColors.Warning else DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    names.joinToString(", ").ifEmpty { stringResource(verdict.hintRes) },
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (oneLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    // Scanning needs the adapter; without it the sheet has the way to connect.
    val scanButton: @Composable (Modifier) -> Unit = { m ->
        if (connected) {
            Button(
                enabled = !busy,
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                modifier = m.height(DashSize.TouchPrimary)
            ) { Text(stringResource(R.string.vehicle_scan), maxLines = 1) }
        }
    }
    // Tinted like the banner of the full tile: the verdict reads from across the car.
    val tint = Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colour.copy(alpha = 0.16f), Color.Transparent)))
    if (oneLine) {
        Row(
            modifier = Modifier.fillMaxSize().then(tint).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            summary(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            scanButton(Modifier)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().then(tint).clickable(onClick = onOpen).padding(16.dp)) {
            Text(
                stringResource(R.string.vehicle_fault_codes_title),
                color = DashColors.AccentInk,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            summary(Modifier.weight(1f).fillMaxWidth())
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
                Spacer(Modifier.height(6.dp))
            }
            scanButton(Modifier.fillMaxWidth())
        }
    }
}

/** What the driver needs to know first: whether to stop, drive on, or that nothing is wrong. */
internal enum class CarVerdict(val titleRes: Int, val hintRes: Int) {
    STOP(R.string.car_verdict_stop, R.string.car_verdict_stop_hint),
    DRIVE(R.string.car_verdict_drive, R.string.car_verdict_soon_hint),
    FINE(R.string.car_verdict_fine, R.string.car_verdict_fine_hint),
    /** No code read and nothing from the car: not known yet. */
    UNREAD(R.string.car_verdict_unread, R.string.car_verdict_unread_hint)
}

/**
 * The verdict from the car's own messages ([levels]), the engine's codes and
 * the mechanic's call on them, and the engine lamp: the worst of them. Never
 * "nothing to report" while the lamp is on or before anything was read.
 */
internal fun carVerdict(levels: List<PsaWarnings.Level?>, codes: List<String>?, severity: Severity?, lampOn: Boolean): CarVerdict = when {
    PsaWarnings.Level.STOP in levels || (!codes.isNullOrEmpty() && severity == Severity.STOP) -> CarVerdict.STOP
    PsaWarnings.Level.SERVICE in levels || !codes.isNullOrEmpty() || lampOn -> CarVerdict.DRIVE
    codes != null || levels.isNotEmpty() -> CarVerdict.FINE
    else -> CarVerdict.UNREAD
}

@Composable
private fun CarVerdict.colour(): Color = when (this) {
    CarVerdict.STOP -> DashColors.Critical
    CarVerdict.DRIVE -> DashColors.Warning
    CarVerdict.FINE -> DashColors.Good
    CarVerdict.UNREAD -> DashColors.TextSecondary
}

/** How serious one of the car's messages is, as the map and the list count it (2 stop, 1 garage, 0 good to know). */
internal val CarMessage.serious: Int
    get() = when (level) {
        PsaWarnings.Level.STOP -> 2
        PsaWarnings.Level.SERVICE -> 1
        else -> 0
    }

/**
 * The verdict as a tinted banner: how many problems, large, in the verdict's
 * colour, what that means for driving, and the engine lamp when it is lit.
 */
@Composable
private fun VerdictBanner(verdict: CarVerdict, count: Int, lampOn: Boolean) {
    val colour = verdict.colour()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Medium)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colour.copy(alpha = 0.22f), colour.copy(alpha = 0.06f))))
            .border(1.dp, colour.copy(alpha = 0.5f), DashShape.Medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (count > 0) {
            Text(count.toString(), color = colour, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(14.dp))
        } else {
            Icon(
                if (verdict == CarVerdict.FINE) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null, tint = colour, modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(verdict.titleRes), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            val hint = stringResource(verdict.hintRes)
            Text(
                if (lampOn) hint + " · " + stringResource(R.string.vehicle_lamp_on) else hint,
                color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * One problem in the list: its number (the same as on the car's picture) in
 * its colour, what it is and what to check, and where it comes from. A code
 * the mechanic explained opens its detail sheet ([onOpen]).
 */
@Composable
private fun ProblemRow(
    number: Int, serious: Int, title: String, detail: String?, source: String, pending: Boolean, onOpen: (() -> Unit)?,
    /** The car in 3D is turned to it. */
    selected: Boolean = false,
    /** Turns the car in 3D to it; the details then open from the arrow alone. */
    onSelect: (() -> Unit)? = null
) {
    val colour = when (serious) { 2 -> DashColors.Critical; 1 -> DashColors.Warning; else -> DashColors.TextSecondary }
    val content: @Composable () -> Unit = { BoxWithConstraints {
        // A narrow tile gives the problem's words the whole row: where it comes from can go.
        val wide = maxWidth >= 380.dp
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = if (serious == 2) Color.White else Color(0xFF111111), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (pending) {
                    Text(stringResource(R.string.vehicle_pending), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (wide) {
                Spacer(Modifier.width(10.dp))
                Text(
                    source, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.06.em,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            if (onOpen != null && onSelect != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.car3d_details), tint = DashColors.Accent)
                }
            } else if (onOpen != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Accent)
            }
        }
    } }
    val color = DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f)
    val ring = if (selected) Modifier.border(1.5.dp, colour, DashShape.Medium) else Modifier
    val tap = onSelect ?: onOpen
    if (tap != null) {
        Surface(onClick = tap, shape = DashShape.Medium, color = color, modifier = Modifier.fillMaxWidth().then(ring)) { content() }
    } else {
        Surface(shape = DashShape.Medium, color = color, modifier = Modifier.fillMaxWidth().then(ring)) { content() }
    }
}

/**
 * What a Clear button runs. Clearing erases the codes stored in the car's
 * computer for good, and a garage reads them to find a fault: it is asked
 * first, and only parked, so a finger aiming at Scan can't do it.
 */
@Composable
internal fun rememberClearCodes(onClear: () -> Unit): () -> Unit {
    val lock = LocalDriveLock.current
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        ParkedOnly { asking = false }
        ConfirmDialog(
            title = stringResource(R.string.vehicle_clear_confirm_title),
            body = stringResource(R.string.vehicle_clear_confirm_body),
            action = stringResource(R.string.vehicle_clear),
            onConfirm = { asking = false; onClear() },
            onDismiss = { asking = false }
        )
    }
    return { lock.whenParked { asking = true } }
}

/**
 * The AI's advice for [code]. Answers come back in the order asked, and the
 * model sometimes mistypes a code (P1351 for P1352), so position is the
 * fallback; the car's own code is always the one shown.
 */
internal fun adviceFor(diagnosis: Diagnosis?, codes: List<String>, code: String, index: Int): CodeAdvice? {
    diagnosis ?: return null
    return diagnosis.codes.firstOrNull { it.code == code }
        ?: diagnosis.codes.getOrNull(index)?.takeIf { diagnosis.codes.size == codes.size }
}

/** A Scan / Clear outcome; [failed] picks the colour, so it never depends on the wording. */
private class DtcMessage(val text: String, val failed: Boolean)

/** The last three alerts, newest first: time, a dot in the alert's colour, what it said. */
@Composable
private fun RecentAlerts() {
    val timeFmt = rememberTimeFormat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.vehicle_recent_alerts),
            color = DashColors.TextSecondary,
            letterSpacing = 0.08.em,
            style = MaterialTheme.typography.labelSmall
        )
        AlertCenter.history.take(3).forEach { event ->
            val colour = if (event.level == AlertLevel.CRITICAL) DashColors.Critical else DashColors.Warning
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFmt.format(java.util.Date(event.at)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
                Spacer(Modifier.width(8.dp))
                Text(event.text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun severityColor(severity: Severity?): Color = when (severity) {
    Severity.OK -> DashColors.Good
    Severity.STOP -> DashColors.Critical
    Severity.SOON, null -> DashColors.Warning
}

/** Says the advice is the AI's, which model gave it, and that a garage has the last word. */
@Composable
private fun AiDisclaimer(d: Diagnosis, aiText: Resources) {
    val source = d.model.ifBlank { aiText.getString(R.string.ai_disclaimer_gemini) }
    Text(aiText.getString(R.string.ai_disclaimer, source), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
}

/** A code on a coloured badge, the way a garage printout sets it apart. */
@Composable
private fun CodeBadge(code: String, severity: Severity?, large: Boolean = false) {
    val color = severityColor(severity)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 6.dp else 4.dp)
    ) {
        Text(
            code,
            color = if (severity == Severity.STOP) Color.White else Color(0xFF111111),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall
        )
    }
}

/**
 * Everything the AI mechanic said about one code, set out to be read parked:
 * large type, one heading per question, lists as lists, and two columns on the
 * wide head-unit screen so lines stay short.
 */
@Composable
private fun FaultDetailSheet(
    code: String,
    advice: CodeAdvice,
    diagnosis: Diagnosis?,
    codes: List<String>,
    aiText: Resources,
    moving: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val ask by AskMechanic.state.collectAsState()
    // Only a question asked from this sheet: one asked from the tile's header goes on when the sheet closes.
    var askedHere by remember { mutableStateOf(false) }
    val askQuestion = rememberAskQuestion { askedHere = true; AskMechanic.listen(context, code, codes, diagnosis) }
    // Leaving the sheet stops listening, and the answer shown goes with it.
    DisposableEffect(Unit) { onDispose { if (askedHere) AskMechanic.cancel() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = DashShape.Large,
            color = DashColors.Card.copy(alpha = 1f),
            // Docked app windows are drawn above dialogs on this unit; the ones under it step aside.
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).keepClearOfWindows()
        ) {
            Column {
                Row(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodeBadge(code, diagnosis?.severity, large = true)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        advice.meaning.ifBlank { code },
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    AskButton(ask, aiText, onClick = askQuestion)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = aiText.getString(R.string.ai_close), tint = DashColors.TextSecondary)
                    }
                }
                HorizontalDivider(color = DashColors.Line)
                if (ask != AskMechanic.State.Idle) {
                    AskPanel(ask, aiText, onReplay = { AskMechanic.replay(context) })
                    HorizontalDivider(color = DashColors.Line)
                }

                // What it is and what to look for on the left; what to do about it on the right.
                val understand: @Composable () -> Unit = {
                    diagnosis?.takeIf { it.codes.size > 1 && it.overview.isNotBlank() }?.let {
                        DetailSection(Icons.Filled.Info, aiText.getString(R.string.ai_detail_overview)) { DetailText(it.overview) }
                    }
                    if (advice.explanation.isNotBlank()) {
                        DetailSection(Icons.Filled.Info, aiText.getString(R.string.ai_detail_meaning)) { DetailText(advice.explanation) }
                    }
                    if (advice.symptoms.isNotEmpty()) {
                        DetailSection(Icons.Filled.Visibility, aiText.getString(R.string.ai_detail_symptoms)) { DetailList(advice.symptoms, numbered = false) }
                    }
                    if (advice.causes.isNotEmpty()) {
                        DetailSection(Icons.Filled.Search, aiText.getString(R.string.ai_detail_causes)) { DetailList(advice.causes, numbered = true) }
                    }
                }
                val act: @Composable () -> Unit = {
                    if (advice.checkFirst.isNotBlank()) CheckFirstBox(advice.checkFirst, aiText)
                    if (advice.checks.isNotEmpty()) {
                        DetailSection(Icons.Filled.Checklist, aiText.getString(R.string.ai_detail_checks)) { DetailList(advice.checks, numbered = true) }
                    }
                    if (advice.repair.isNotBlank()) {
                        DetailSection(Icons.Filled.Build, aiText.getString(R.string.ai_detail_repair)) { DetailText(advice.repair) }
                    }
                    if (advice.cost.isNotBlank()) {
                        DetailSection(Icons.Filled.Euro, aiText.getString(R.string.ai_detail_cost)) { DetailText(advice.cost) }
                    }
                    if (advice.diy.isNotBlank()) {
                        DetailSection(Icons.Filled.Handyman, aiText.getString(R.string.ai_detail_diy)) { DetailText(advice.diy) }
                    }
                    if (advice.driving.isNotBlank()) {
                        DetailSection(Icons.Filled.DirectionsCar, aiText.getString(R.string.ai_detail_driving)) { DetailText(advice.driving) }
                    }
                }
                if (moving) {
                    // Too much to read at the wheel: the spoken summary and the Ask button stay, the rest waits for a stop.
                    Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        diagnosis?.let { Text(it.summary, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium) }
                        Text(aiText.getString(R.string.ai_details_parked), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val twoColumns = maxWidth > 760.dp
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            if (twoColumns) {
                                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { understand() }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { act() }
                                }
                            } else {
                                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    understand()
                                    act()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What an Ask button runs: [listen] once the microphone may be used, asking for it the first time. */
@Composable
private fun rememberAskQuestion(listen: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(listen)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) latest() else AskMechanic.micDenied(context)
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            latest()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** The tile's microphone, beside its title: a question about the car as it is now. While listening it sends. */
@Composable
private fun AskIconButton(state: AskMechanic.State, aiText: Resources, onClick: () -> Unit) {
    val listening = state == AskMechanic.State.Listening
    val thinking = state == AskMechanic.State.Thinking
    IconButton(onClick = onClick, enabled = !thinking, modifier = Modifier.size(40.dp)) {
        Icon(
            if (listening) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
            contentDescription = aiText.getString(if (listening) R.string.ai_ask_send else R.string.ai_ask),
            tint = when {
                listening -> DashColors.Warning
                thinking -> DashColors.Muted
                else -> DashColors.Accent
            }
        )
    }
}

/** Ask the mechanic out loud; while listening it becomes Send. */
@Composable
private fun AskButton(state: AskMechanic.State, aiText: Resources, onClick: () -> Unit) {
    val listening = state == AskMechanic.State.Listening
    Button(
        onClick = onClick,
        enabled = state != AskMechanic.State.Thinking,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (listening) DashColors.Warning else DashColors.Accent,
            contentColor = if (listening) Color.White else DashColors.OnAccent,
            disabledContainerColor = DashColors.CardHi,
            disabledContentColor = DashColors.Muted
        )
    ) {
        Icon(if (listening) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(aiText.getString(if (listening) R.string.ai_ask_send else R.string.ai_ask))
    }
}

/**
 * Under the title: listening, thinking, or the question as heard and the
 * answer (which is also spoken). [compact]: on the tile, a rounded strip with
 * a shorter answer and [onClose] to put it away.
 */
@Composable
private fun AskPanel(
    state: AskMechanic.State,
    aiText: Resources,
    onReplay: () -> Unit,
    compact: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.clip(DashShape.Medium) else Modifier)
            .background(DashColors.Accent.copy(alpha = 0.08f))
            .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            AskMechanic.State.Listening -> {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(aiText.getString(R.string.ai_ask_listening), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            AskMechanic.State.Thinking -> {
                CircularProgressIndicator(color = DashColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(aiText.getString(R.string.ai_ask_thinking), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            is AskMechanic.State.Answered -> {
                Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (state.exchange.heard.isNotBlank()) {
                        Text(
                            aiText.getString(R.string.ai_ask_you_asked, state.exchange.heard),
                            color = DashColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        state.exchange.answer,
                        color = DashColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        // The whole answer is spoken; the tile keeps a few lines of it.
                        maxLines = if (compact) 4 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onReplay) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = aiText.getString(R.string.ai_ask_replay), tint = DashColors.Accent)
                }
            }
            AskMechanic.State.NothingHeard ->
                Text(
                    aiText.getString(R.string.ai_ask_nothing_heard),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            is AskMechanic.State.Failed ->
                Text(state.reason, color = DashColors.Warning, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            AskMechanic.State.Idle -> Unit
        }
        // Listening and thinking end by themselves (the microphone button sends); the rest can be put away.
        val settled = state != AskMechanic.State.Listening && state != AskMechanic.State.Thinking
        if (onClose != null && settled) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = aiText.getString(R.string.ai_close), tint = DashColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DetailSection(icon: ImageVector, title: String, body: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        body()
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
}

/** One line per item: numbered when the order matters (likelihood, steps), bulleted otherwise. */
@Composable
private fun DetailList(items: List<String>, numbered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, item ->
            Row {
                Text(
                    if (numbered) "${i + 1}." else "•",
                    color = DashColors.AccentInk,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(26.dp)
                )
                Text(item, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The one thing to look at first, set apart so it's found at a glance. */
@Composable
private fun CheckFirstBox(text: String, aiText: Resources) {
    Surface(
        shape = DashShape.Medium,
        color = DashColors.Good.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, DashColors.Good.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = DashColors.Good, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(aiText.getString(R.string.ai_detail_check_first), color = DashColors.Good, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * The bar's OBD mark while the link is up (issue #121): the Car problems tile
 * and the Car data tile side by side, as big as the screen allows.
 */
@Composable
internal fun ObdDialog(connection: ObdConnectionState, onConnect: () -> Unit, onPickDevice: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = DashShape.Large,
            color = DashColors.Card.copy(alpha = 1f),
            // Docked app windows are drawn above dialogs on this unit; the ones under it step aside.
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(0.94f).fillMaxHeight(0.94f).keepClearOfWindows()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.weight(1f)) {
                    ObdDtcCard(connection, onConnect, Modifier.weight(1f).fillMaxHeight(), onPickDevice)
                    Spacer(Modifier.width(12.dp))
                    ObdAllCard(connection, onConnect, Modifier.weight(1f).fillMaxHeight(), onPickDevice)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.dash_close), color = DashColors.AccentInk) }
            }
        }
    }
}
