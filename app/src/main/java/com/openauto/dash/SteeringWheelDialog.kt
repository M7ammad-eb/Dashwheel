package com.openauto.dash

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openauto.dash.unit.fyt.FytKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The steering wheel button map (SteeringWheelStore.kt), from Settings,
 * Driving. While it is open every button pressed is put on the map, or lights
 * up green if it is there already, and nothing runs: the driver presses each
 * button in turn, then taps one to name it, say where it sits and choose what
 * it does. A button the unit can't see is put on the map by hand, with what
 * can be tried about it.
 */

private enum class WheelStep { MAP, BUTTON, PICK_ACTION, PICK_APP }

/** The zones in the map's order, drawn the way they sit around the wheel. */
private val PLACED_ZONES = listOf(WheelZone.LEFT, WheelZone.RIGHT, WheelZone.STALK, WheelZone.PANEL)

@Composable
internal fun SteeringWheelDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val buttons by SteeringWheelStore.buttons.collectAsState()

    var step by remember { mutableStateOf(WheelStep.MAP) }
    var editing by remember { mutableStateOf<String?>(null) }
    // The action being picked is mode 2's ([WheelButton.second]), not the button's own.
    var pickingSecond by remember { mutableStateOf(false) }

    // The map listens for as long as the screen is up, whichever step shows:
    // pressing the button being edited lights it there too.
    DisposableEffect(Unit) {
        SteeringWheelStore.openMap()
        onDispose { SteeringWheelStore.closeMap() }
    }

    fun back() {
        step = when (step) {
            WheelStep.PICK_ACTION, WheelStep.PICK_APP -> WheelStep.BUTTON
            else -> WheelStep.MAP
        }
    }

    // A button removed while open (or never there): back to the map.
    val button = buttons.firstOrNull { it.uid == editing }
    if (step != WheelStep.MAP && button == null) step = WheelStep.MAP

    // A dialog is its own window: while it's up, keys go to it, not to
    // MainActivity.dispatchKeyEvent. So the screen takes focus and hands
    // them to the store itself.
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }

    SettingsSheet(
        title = stringResource(R.string.wheel_title),
        onDismiss = onDismiss,
        onStepBack = if (step != WheelStep.MAP) ::back else null,
        actions = { SheetButton(stringResource(R.string.dash_done), onClick = onDismiss) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { SteeringWheelStore.onKeyEvent(context, it.nativeKeyEvent) }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (step) {
                WheelStep.MAP -> WheelMap(
                    buttons = buttons,
                    onOpen = { uid ->
                        editing = uid
                        step = WheelStep.BUTTON
                    },
                    onAddSilent = {
                        val n = buttons.count { it.key == null } + 1
                        editing = SteeringWheelStore.addSilent(context.getString(R.string.wheel_silent_default_name, n), WheelZone.UNPLACED)
                        step = WheelStep.BUTTON
                    }
                )
                WheelStep.BUTTON -> button?.let {
                    WheelButtonDetail(
                        button = it,
                        onPickAction = { pickingSecond = false; step = WheelStep.PICK_ACTION },
                        onPickSecond = { pickingSecond = true; step = WheelStep.PICK_ACTION },
                        onListen = {
                            SteeringWheelStore.listenFor(it.uid)
                            step = WheelStep.MAP
                        },
                        onRemove = {
                            SteeringWheelStore.remove(it.uid)
                            step = WheelStep.MAP
                        }
                    )
                }
                WheelStep.PICK_ACTION, WheelStep.PICK_APP -> button?.let {
                    WheelActionPicker(
                        keyLabel = buttonName(it),
                        second = pickingSecond,
                        hasExisting = (if (pickingSecond) it.second else it.assignment) != null,
                        onPick = { action ->
                            if (pickingSecond) SteeringWheelStore.assignSecond(it.uid, WheelAssignment.Preset(action))
                            else SteeringWheelStore.assign(it.uid, WheelAssignment.Preset(action))
                            step = WheelStep.BUTTON
                        },
                        onPickApp = { step = WheelStep.PICK_APP },
                        onRemove = {
                            if (pickingSecond) SteeringWheelStore.assignSecond(it.uid, null) else SteeringWheelStore.assign(it.uid, null)
                            step = WheelStep.BUTTON
                        }
                    )
                }
            }
        }
    }

    if (step == WheelStep.PICK_APP) {
        // Loaded off the main thread: every app's label and icon is the slowest list there is.
        var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) } }
        AppPickerDialog(
            apps = apps,
            onPick = { app ->
                editing?.let { uid ->
                    val launch = WheelAssignment.LaunchApp(app.packageName, app.label)
                    if (pickingSecond) SteeringWheelStore.assignSecond(uid, launch) else SteeringWheelStore.assign(uid, launch)
                }
                step = WheelStep.BUTTON
            },
            onDismiss = { step = WheelStep.PICK_ACTION }
        )
    }
}

/**
 * The map: what to do, the wheel's two sides next to each other, the stalk
 * and the screen's bezel under them, then the buttons not placed yet. Below,
 * a button that gives no signal can be added, and what the unit sees.
 */
@Composable
private fun WheelMap(buttons: List<WheelButton>, onOpen: (String) -> Unit, onAddSilent: () -> Unit) {
    val listeningFor by SteeringWheelStore.listeningFor.collectAsState()
    val candidate by SteeringWheelStore.candidate.collectAsState()
    val waiting = buttons.firstOrNull { it.uid == listeningFor }
    MapStatus(waiting, candidate)
    UnitKeysStatus()
    // The CAN stream is read through root: where there is none, there is
    // nothing to report about it (see PrivilegedShell).
    if (shellAccess().root) CanStatus()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ZoneCard(WheelZone.LEFT, buttons, onOpen, Modifier.weight(1f))
        ZoneCard(WheelZone.RIGHT, buttons, onOpen, Modifier.weight(1f))
    }
    ZoneCard(WheelZone.STALK, buttons, onOpen, Modifier.fillMaxWidth())
    ZoneCard(WheelZone.PANEL, buttons, onOpen, Modifier.fillMaxWidth())
    if (buttons.any { it.zone == WheelZone.UNPLACED }) ZoneCard(WheelZone.UNPLACED, buttons, onOpen, Modifier.fillMaxWidth())
    OutlinedButton(onClick = onAddSilent, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.wheel_add_silent))
    }
    if (buttons.any { it.isBound }) AccessibilityReminder()
    HorizontalDivider(color = DashColors.Line)
    MonitorPanel()
    if (buttons.isNotEmpty()) ResetButton()
}

/** Empties the map, after asking: the wheel goes back to what the unit does by itself. */
@Composable
private fun ResetButton() {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) {
        Text(stringResource(R.string.wheel_reset), color = DashColors.Critical)
    }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.wheel_reset_title),
            body = stringResource(R.string.wheel_reset_body),
            action = stringResource(R.string.wheel_reset_confirm),
            onConfirm = {
                SteeringWheelStore.resetAll()
                asking = false
            },
            onDismiss = { asking = false }
        )
    }
}

/** What the map waits for: any button, the one being learned again, or a CAN press once more. */
@Composable
private fun MapStatus(waiting: WheelButton?, candidate: WheelKey?) {
    val text = when {
        candidate != null -> stringResource(R.string.wheel_confirm_detail, wheelKeyLabel(candidate))
        waiting != null -> stringResource(R.string.wheel_map_waiting_for, buttonName(waiting))
        else -> stringResource(R.string.wheel_explanation)
    }
    val highlight = candidate != null || waiting != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .background(if (highlight) DashColors.Accent.copy(alpha = 0.12f) else DashColors.CardHi)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (waiting != null) Icons.Filled.Hearing else Icons.Filled.TouchApp, contentDescription = null,
            tint = if (highlight) DashColors.Accent else DashColors.TextSecondary, modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            // Idle: the second step on its own line, so neither sentence runs long.
            if (!highlight) {
                Text(stringResource(R.string.wheel_explanation_tap), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (waiting != null) {
            TextButton(onClick = { SteeringWheelStore.listenFor(null) }) { Text(stringResource(R.string.dash_cancel)) }
        }
    }
}

/**
 * On an FYT unit (Joying, Atoto...) the firmware keeps the wheel keys to
 * itself until Dashwheel takes them (FytKeys): pressing them puts nothing on
 * the map, so the map says so and takes them from here. Once asked for, what
 * still stands in the way until the firmware hands them over: the taking
 * needs a shell. Given a few seconds before saying so.
 */
@Composable
private fun UnitKeysStatus() {
    val keys = UnitPlatform.adapter.keys as? FytKeys ?: return
    val taking by keys.taking.collectAsState()
    val listening by keys.listening.collectAsState()
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(taking) {
        waited = false
        delay(4_000)
        waited = true
    }
    when {
        !taking -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DashShape.Small)
                .background(DashColors.Warning.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.wheel_unit_keys_off),
                color = DashColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { keys.setTaking(true) }) {
                Text(stringResource(R.string.wheel_unit_keys_take), color = DashColors.Warning, fontWeight = FontWeight.Bold)
            }
        }
        listening -> Text(stringResource(R.string.wheel_unit_keys_live), color = DashColors.Good, style = MaterialTheme.typography.bodySmall)
        waited -> Text(stringResource(R.string.wheel_unit_keys_waiting), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
    }
}

/** One zone of the map with its buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneCard(zone: WheelZone, buttons: List<WheelButton>, onOpen: (String) -> Unit, modifier: Modifier) {
    val inZone = buttons.filter { it.zone == zone }
    Column(
        modifier = modifier
            .clip(DashShape.Medium)
            .border(1.dp, DashColors.Line, DashShape.Medium)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(zone.labelRes).uppercase(),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        if (inZone.isEmpty()) {
            Text(stringResource(R.string.wheel_zone_empty), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inZone.forEach { ButtonChip(it, onClick = { onOpen(it.uid) }) }
            }
        }
    }
}

/**
 * One button on the map: its name and what it does. Green while pressed;
 * dimmed with a crossed-out signal when the unit can't see it.
 */
@Composable
private fun ButtonChip(button: WheelButton, onClick: () -> Unit, wide: Boolean = false) {
    val lit by SteeringWheelStore.lit.collectAsState()
    val listeningFor by SteeringWheelStore.listeningFor.collectAsState()
    val on = button.uid in lit
    val silent = button.key == null
    val fill by animateColorAsState(if (on) DashColors.Good.copy(alpha = 0.6f) else DashColors.CardHi, label = "wheel-lit")
    val edge = when {
        on -> DashColors.Good
        button.uid == listeningFor -> DashColors.Accent
        silent -> DashColors.Muted.copy(alpha = 0.5f)
        else -> DashColors.Line
    }
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .then(if (wide) Modifier.fillMaxWidth() else Modifier.width(132.dp))
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .background(fill)
            .border(if (on) 2.dp else 1.dp, edge, DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (silent) {
            Icon(Icons.Filled.PortableWifiOff, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                buttonName(button),
                color = if (silent) DashColors.TextSecondary else DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    silent -> stringResource(R.string.wheel_no_signal)
                    button.assignment != null -> assignmentSummary(button.assignment)
                    else -> stringResource(R.string.wheel_no_action)
                },
                color = if (button.assignment != null) DashColors.Accent else DashColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One button: its name, where it sits, what it does, its signal (learned
 * again on demand) and, for one the unit can't see, what can be tried.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WheelButtonDetail(button: WheelButton, onPickAction: () -> Unit, onPickSecond: () -> Unit, onListen: () -> Unit, onRemove: () -> Unit) {
    ButtonChip(button, onClick = {}, wide = true)
    var name by remember(button.uid) { mutableStateOf(button.name) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            SteeringWheelStore.rename(button.uid, it)
        },
        label = { Text(stringResource(R.string.wheel_name_label)) },
        placeholder = { button.key?.let { Text(wheelKeyLabel(it), color = DashColors.Muted) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors()
    )
    SettingsSection(stringResource(R.string.wheel_where))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PLACED_ZONES.forEach { zone ->
            CategoryChip(stringResource(zone.labelRes), selected = button.zone == zone) { SteeringWheelStore.place(button.uid, zone) }
        }
    }
    SettingsSection(stringResource(R.string.wheel_does))
    SettingsRow(
        if (button.assignment is WheelAssignment.Preset) button.assignment.action.icon else Icons.Filled.Apps,
        button.assignment?.let { assignmentSummary(it) } ?: stringResource(R.string.wheel_no_action),
        null,
        onPickAction
    )
    // What it does in mode 2 instead (ScreenFocus); the button that switches the mode keeps to it.
    if (!button.switchesMode) {
        SettingsSection(stringResource(R.string.wheel_does_second))
        val second = button.second
        SettingsRow(
            if (second is WheelAssignment.Preset) second.action.icon else if (second != null) Icons.Filled.Apps else SteeringWheelAction.SCREEN_FOCUS.icon,
            second?.let { assignmentSummary(it) } ?: stringResource(R.string.wheel_second_same),
            null,
            onPickSecond
        )
    }
    val key = button.key
    if (key != null && key.keyCode >= HeadUnitKeys.FIRST_VENDOR_KEY) {
        Text(stringResource(R.string.wheel_unit_also_acts), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    if (SteeringWheelStore.canSilence(key)) {
        SettingsToggle(
            Icons.Filled.Block, stringResource(R.string.wheel_unit_ignores),
            stringResource(R.string.wheel_unit_ignores_detail), button.unitIgnores
        ) { SteeringWheelStore.setUnitIgnores(button.uid, it) }
    }
    SettingsSection(stringResource(R.string.wheel_signal_section))
    if (key?.log != null) {
        // Learned from a log line: the driver trims the text down to what names the button.
        var match by remember(button.uid) { mutableStateOf(key.log) }
        OutlinedTextField(
            value = match,
            onValueChange = {
                match = it
                SteeringWheelStore.setLogMatch(button.uid, it)
            },
            label = { Text(stringResource(R.string.wheel_log_match_label)) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
        Text(stringResource(R.string.wheel_log_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            if (key != null) stringResource(R.string.wheel_signal, wheelKeyLabel(key)) else stringResource(R.string.wheel_silent_title),
            color = if (key != null) DashColors.TextPrimary else DashColors.Warning,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    if (key == null) SilentTips()
    OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Hearing, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (key != null) R.string.wheel_listen_again else R.string.wheel_listen_for_it))
    }
    TextButton(onClick = onRemove) {
        Text(stringResource(R.string.wheel_remove_button), color = DashColors.Critical)
    }
}

/** What can be tried about a button the unit doesn't see, most useful first. */
@Composable
private fun SilentTips() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            R.string.wheel_silent_tip_monitor,
            R.string.wheel_silent_tip_canbox,
            R.string.wheel_silent_tip_car,
            R.string.wheel_silent_tip_other
        ).forEachIndexed { i, tip ->
            Row {
                Text("${i + 1}.", color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(20.dp))
                Text(stringResource(tip), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val WheelZone.labelRes: Int
    get() = when (this) {
        WheelZone.LEFT -> R.string.wheel_zone_left
        WheelZone.RIGHT -> R.string.wheel_zone_right
        WheelZone.STALK -> R.string.wheel_zone_stalk
        WheelZone.PANEL -> R.string.wheel_zone_panel
        WheelZone.UNPLACED -> R.string.wheel_zone_unplaced
    }

/** The name the driver gave [button], else its signal's. */
@Composable
private fun buttonName(button: WheelButton): String =
    button.name.ifBlank { button.key?.let { wheelKeyLabel(it) } ?: stringResource(R.string.wheel_no_signal) }

/** [WheelKey.label], with the name of a raw, unnamed button in the chosen language. */
@Composable
private fun wheelKeyLabel(key: WheelKey): String = when {
    key.canKey != null -> stringResource(R.string.wheel_can_button, key.canHex.orEmpty())
    key.vendor == CarBoxKeys.VENDOR -> stringResource(R.string.wheel_can_button, wheelBoxLabel(key.vendorCode))
    key.input != null -> key.input
    key.log != null -> key.log
    key.keyCode == KeyEvent.KEYCODE_UNKNOWN -> stringResource(R.string.wheel_raw_button, key.scanCode)
    // The head unit's own keys (HeadUnitKeys): by the name on the button.
    key.keyCode >= HeadUnitKeys.FIRST_VENDOR_KEY -> HeadUnitKeys.nameRes[key.keyCode]?.let { stringResource(it) }
        ?: HeadUnitKeys.customNumber(key.keyCode)?.let { stringResource(R.string.wheel_unit_key_n, it) }
        ?: stringResource(R.string.wheel_unit_key_code, key.keyCode)
    else -> key.label
}

@Composable
private fun assignmentSummary(assignment: WheelAssignment): String = when (assignment) {
    is WheelAssignment.Preset -> stringResource(assignment.action.labelRes)
    is WheelAssignment.LaunchApp -> assignment.appLabel
}

/**
 * Without the accessibility service a key only reaches Dashwheel while it is
 * in front. Not observable, so polled: the user may just have turned it on.
 */
@Composable
private fun AccessibilityReminder() {
    // The service says itself when it is bound: no poll of the system settings every second.
    val accessibilityOn by SplitAccessibilityService.connected.collectAsState()
    if (!accessibilityOn) AccessibilityHint(stringResource(R.string.wheel_accessibility_anywhere))
}

@Composable
private fun WheelActionPicker(
    keyLabel: String,
    second: Boolean,
    hasExisting: Boolean,
    onPick: (SteeringWheelAction) -> Unit,
    onPickApp: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (second) R.string.wheel_assign_prompt_second else R.string.wheel_assign_prompt, keyLabel),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        AddChoiceRow(icon = Icons.Filled.Apps, label = stringResource(R.string.wheel_choose_app), onClick = onPickApp)
        // The service says itself when it is bound, so the hint goes away on coming back from system settings.
        val accessibilityOn by SplitAccessibilityService.connected.collectAsState()
        WheelActionGroup.entries.forEach { group ->
            Spacer(Modifier.height(8.dp))
            SettingsSection(stringResource(group.labelRes))
            if (group == WheelActionGroup.SYSTEM && !accessibilityOn) AccessibilityHint(stringResource(R.string.wheel_accessibility_hint))
            SteeringWheelAction.entries.filter { it.group == group }.forEach { action ->
                WheelActionRow(action, unavailable = action.needsAccessibility && !accessibilityOn, onClick = { onPick(action) })
            }
        }
        if (hasExisting) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove) {
                Text(stringResource(if (second) R.string.wheel_second_same else R.string.wheel_no_action), color = DashColors.Critical)
            }
        }
    }
}

/** One action to pick. [unavailable]: still pickable (it works once Accessibility is on), but says so. */
@Composable
private fun WheelActionRow(action: SteeringWheelAction, unavailable: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(action.icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(action.labelRes), color = DashColors.TextPrimary)
            if (unavailable) {
                Text(stringResource(R.string.wheel_needs_accessibility), color = DashColors.Warning, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Why to turn Dashwheel on under Accessibility, with a way there: back, recents,
 * notifications, split screen go through the service, and it is what puts a
 * button's action in front of whatever app is showing.
 */
@Composable
private fun AccessibilityHint(text: String) {
    val context = LocalContext.current
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .background(DashColors.Warning.copy(alpha = 0.12f))
            .clickable { tap(); SplitLauncher.openAccessibilitySettings(context) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Accessibility, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.wheel_accessibility_enable), color = DashColors.Warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Whether the car's CAN data can be read (root): on MCU units that's where
 * some wheel buttons are, so saying it can't is better than recording
 * nothing. Given a few seconds to start before saying so.
 */
@Composable
private fun CanStatus() {
    // Another family's bus (FYT) counts: its values are read without the MCU log.
    val entries by McuReader.monitorEntries.collectAsState()
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(4_000)
        waited = true
    }
    val live = entries.isNotEmpty()
    if (!live && !waited) return
    Text(
        stringResource(if (live) R.string.wheel_listening_can_live else R.string.wheel_listening_can_off),
        color = if (live) DashColors.Good else DashColors.Warning,
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * Everything the unit shows while the map is open (WheelMonitor): where this
 * firmware's wheel buttons surface. Folded away until asked for; a key or CAN
 * line can be tapped to use it when the press wasn't recognised by itself.
 */
@Composable
private fun MonitorPanel() {
    val lines by WheelMonitor.lines.collectAsState()
    var open by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashSize.Touch)
                .clip(DashShape.Small)
                .clickable { tap(); open = !open }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.wheel_monitor_title),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = DashColors.TextSecondary)
        }
        if (!open) return@Column
        Text(
            stringResource(if (lines.any { it.learn != null }) R.string.wheel_monitor_hint else R.string.wheel_monitor_empty),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        // The raw input and the log are read through root: without it, two of the four sources stay dark.
        if (Edition.full && !shellAccess().root) {
            Text(stringResource(R.string.wheel_monitor_no_root), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        lines.forEach { line ->
            val learn = line.learn
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DashShape.Small)
                    .then(
                        if (learn != null) Modifier
                            .background(DashColors.Accent.copy(alpha = 0.12f))
                            .clickable { tap(); SteeringWheelStore.learnFromMonitor(learn) }
                        else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    line.source.name,
                    color = if (learn != null) DashColors.Accent else DashColors.Muted,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(48.dp)
                )
                Text(
                    line.text,
                    color = DashColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
