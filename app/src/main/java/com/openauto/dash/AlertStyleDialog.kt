package com.openauto.dash

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/*
 * Settings, Alerts: every event Dashwheel tells the driver about, in one
 * grid. A row per event with Say (spoken) and Show (Dashwheel's own pop-up
 * over the firmware's) and Try; a tap on a pop-up's row opens its design
 * picker, which shows each design as a small screen with the alert where it
 * would be, and "Try it" puts a made-up one on the real screen.
 */

/** One line of the grid: a pop-up Dashwheel can show, or something it only says. */
internal sealed interface AlertLine {
    data class Shown(val kind: AlertKind) : AlertLine
    data class Said(val event: SpokenEvent) : AlertLine
}

/** The grid's groups, by where the event comes from; every alert and every spoken event once. */
internal enum class AlertGroup(@StringRes val title: Int, val lines: List<AlertLine>) {
    CAR(
        R.string.alerts_group_car, listOf(
            AlertLine.Shown(AlertKind.DOORS), AlertLine.Shown(AlertKind.BELT), AlertLine.Shown(AlertKind.TYRES),
            AlertLine.Said(SpokenEvent.CAR_TIPS), AlertLine.Said(SpokenEvent.FAULT_CODES), AlertLine.Said(SpokenEvent.LPG),
            AlertLine.Shown(AlertKind.RADAR), AlertLine.Shown(AlertKind.AC)
        )
    ),
    ROAD(
        R.string.alerts_group_road, listOf(
            AlertLine.Shown(AlertKind.ROAD),
            AlertLine.Said(SpokenEvent.SPEED_CAMERAS), AlertLine.Said(SpokenEvent.SPEED_LIMIT), AlertLine.Said(SpokenEvent.WEATHER)
        )
    ),
    PHONE(R.string.settings_section_phone, listOf(AlertLine.Shown(AlertKind.CALL), AlertLine.Said(SpokenEvent.MESSAGES))),
    START(R.string.alerts_group_start, listOf(AlertLine.Said(SpokenEvent.BRIEFING))),
    UNIT(R.string.unit_card_title, listOf(AlertLine.Shown(AlertKind.VOLUME), AlertLine.Shown(AlertKind.GEMINI)))
}

/**
 * Every alert, one row each: Say, Show and Try side by side, so an alert is
 * found by what it is, not by whether it is heard or seen. Calls on a unit
 * without the head unit's own Bluetooth app have no Show: the companion's
 * calls always show. Only the alerts this unit can have are listed
 * ([RomPopups.available], [RomPopups.canWork]); LPG only with a tank followed.
 */
@Composable
internal fun AlertsGrid() {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<AlertKind?>(null) }
    val access = shellAccess()
    val kinds = remember(access) {
        AlertKind.entries.filter { kind -> offered(context, kind, access) }
    }
    // Alerts this unit could have once the car app's settings can be written.
    val held = remember(access) {
        AlertKind.entries.any { kind ->
            val rom = kind.romKind ?: return@any false
            kind != AlertKind.CALL && RomPopups.available(context, rom) && !RomPopups.canWork(rom, access)
        }
    }
    LaunchedEffect(Unit) { SpokenEvents.load(context) }
    val lpg by LpgTank.settings.collectAsState()
    // A made-up alert still up when the page closes goes with it.
    DisposableEffect(Unit) { onDispose { AlertPreview.stop() } }

    Text(stringResource(R.string.alerts_intro), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    AlertGroup.entries.forEach { group ->
        val lines = group.lines.filter { line ->
            when (line) {
                is AlertLine.Shown -> line.kind in kinds
                is AlertLine.Said -> line.event != SpokenEvent.LPG || lpg.enabled
            }
        }
        if (lines.isEmpty()) return@forEach
        Spacer(Modifier.height(16.dp))
        SettingsSection(stringResource(group.title))
        lines.forEach { line ->
            when (line) {
                is AlertLine.Shown -> ShownRow(line.kind) { picking = line.kind }
                is AlertLine.Said -> SaidRow(line.event)
            }
        }
    }
    if (held && access != PrivilegedShell.Access.UNKNOWN) {
        // The Play edition can never write the car app's settings: one line says which edition can.
        if (Edition.play) Text(
            stringResource(R.string.alert_more_with_github),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        else GrantHint()
    }
    picking?.let { kind -> AlertStyleDialog(kind) { picking = null } }
}

/**
 * The alerts held back for want of the car settings permission: a plain line
 * pointing at the wiki, and the adb command itself only once asked for, copied
 * to the clipboard so it never has to be read off the car screen.
 */
@Composable
private fun GrantHint() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.alert_more_with_permission), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("adb", grantCommand(context.packageName)))
            copied = true
        }) {
            Text(stringResource(if (copied) R.string.alert_command_copied else R.string.alert_copy_command))
        }
        if (copied) {
            Text(grantCommand(context.packageName), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Whether [kind] can work on this unit, so its row is offered. */
private fun offered(context: Context, kind: AlertKind, access: PrivilegedShell.Access): Boolean {
    // Road watch comes with the companion's camera, on any unit.
    val rom = kind.romKind ?: return kind != AlertKind.GEMINI || GeminiLive.available(context)
    return kind == AlertKind.CALL || RomPopups.available(context, rom) && RomPopups.canWork(rom, access)
}

/** One command from a PC that lets Dashwheel write the car app's settings without root. */
/** The command that grants this edition (its own package) the permission from a PC. */
private fun grantCommand(packageName: String) = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

/**
 * The switch that turns [this] alert on, in [RomPopups]; none for Gemini Live,
 * opened by the driver, nor for Road watch, started on the phone.
 */
private val AlertKind.romKind: RomPopups.Kind? get() = if (this == AlertKind.GEMINI || this == AlertKind.ROAD) null else RomPopups.Kind.valueOf(name)

/** Whether [kind] has a switch on this unit: all but Gemini Live, and the calls where only the companion brings them. */
@Composable
private fun switchable(kind: AlertKind): Boolean {
    val context = LocalContext.current
    return remember(kind) { kind.romKind != null && (kind != AlertKind.CALL || RomPopups.available(context, RomPopups.Kind.CALL)) }
}

/**
 * A pop-up: its name, what it does now (its design, what it does when off, or
 * why it can't work yet), Say, Show and Try; the row opens its design picker.
 */
@Composable
private fun ShownRow(kind: AlertKind, onOpen: () -> Unit) {
    val context = LocalContext.current
    val styles by AlertStyleStore.styles.collectAsState()
    val spoken by AlertStyleStore.spoken.collectAsState()
    val replaced by RomPopups.replaced.collectAsState()
    val failed by RomPopups.failed.collectAsState()
    val a11y by SplitAccessibilityService.connected.collectAsState()
    val volumeHidden by VolumeRomHide.active.collectAsState()
    val hasSwitch = switchable(kind)
    val on = !hasSwitch || kind.romKind in replaced
    val detail = when {
        on && kind == AlertKind.VOLUME && kind.romKind in failed -> stringResource(R.string.settings_rom_volume_needs_magisk)
        on && kind.romKind in failed -> stringResource(R.string.settings_rom_needs_root)
        on && kind == AlertKind.VOLUME && !volumeHidden -> stringResource(R.string.settings_rom_volume_next_start)
        on && kind == AlertKind.RADAR && !a11y -> stringResource(R.string.settings_rom_radar_needs_access)
        // Road watch has no switch here (it starts on the phone): its row says what it is, and that it is experimental.
        on && kind == AlertKind.ROAD -> stringResource(R.string.alert_road_detail)
        on -> stringResource(styles.of(kind).title)
        else -> stringResource(kind.offDetail)
    }
    GridRow(
        icon = kind.icon, title = stringResource(kind.label), detail = detail,
        // Its voice comes with the pop-up: said only while Dashwheel shows it.
        say = if (kind.speakable) kind in spoken else null, sayEnabled = on,
        onSay = { AlertStyleStore.setSpoken(context, kind, it) },
        show = if (hasSwitch) on else null,
        onShow = { want -> kind.romKind?.let { rom -> RomPopups.setReplaced(context, rom, want) } },
        onTry = { AlertPreview.show(context, kind) },
        onOpen = onOpen
    )
}

/** Something the car only says: its switch is Say, and messages can be tried. */
@Composable
private fun SaidRow(event: SpokenEvent) {
    val context = LocalContext.current
    val on by SpokenEvents.on.collectAsState()
    GridRow(
        icon = event.icon, title = stringResource(event.label), detail = stringResource(event.detail),
        say = event in on, sayEnabled = true,
        onSay = { wanted ->
            SpokenEvents.set(context, event, wanted)
            // Camera warnings follow the drive from now on, tile or no tile.
            if (event == SpokenEvent.SPEED_CAMERAS && wanted) SpeedCameras.startIfSpeaking(context)
        },
        show = null, onShow = {},
        onTry = if (event == SpokenEvent.MESSAGES) ({ MessageAlerts.preview(context) }) else null,
        onOpen = null
    )
}

/** The width of the Say and Show columns, so the pills line up down the grid. */
private val PILL_COLUMN = 112.dp

/** One row of the grid; a [say] or [show] of null leaves its column empty. */
@Composable
private fun GridRow(
    icon: ImageVector, title: String, detail: String,
    say: Boolean?, sayEnabled: Boolean, onSay: (Boolean) -> Unit,
    show: Boolean?, onShow: (Boolean) -> Unit,
    onTry: (() -> Unit)?, onOpen: (() -> Unit)?
) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .searchTarget(title)
            .fillMaxWidth()
            .heightIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .then(if (onOpen != null) Modifier.clickable { tap(); onOpen() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(PILL_COLUMN), contentAlignment = Alignment.Center) {
            if (say != null) AlertPill(Icons.Filled.RecordVoiceOver, stringResource(R.string.alerts_say), say, sayEnabled, DashColors.Secondary) { tap(); onSay(it) }
        }
        Box(Modifier.width(PILL_COLUMN), contentAlignment = Alignment.Center) {
            if (show != null) AlertPill(Icons.Filled.WebAsset, stringResource(R.string.alerts_show), show, true, DashColors.Accent) { tap(); onShow(it) }
        }
        Box(Modifier.size(DashSize.TouchPrimary), contentAlignment = Alignment.Center) {
            if (onTry != null) IconButton(onClick = { tap(); onTry() }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.alert_try), tint = DashColors.TextSecondary)
            }
        }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (onOpen != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** Say or Show: a pill lit in [tint] while on, a switch to a screen reader. */
@Composable
private fun AlertPill(icon: ImageVector, label: String, on: Boolean, enabled: Boolean, tint: Color, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (on) tint.copy(alpha = 0.18f) else DashColors.CardHi)
            .border(1.dp, if (on) tint.copy(alpha = 0.55f) else DashColors.Line, shape)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (on) tint else DashColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label, color = if (on) tint else DashColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}

/** What each alert does, said on its row while it is off. */
private val AlertKind.offDetail: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.settings_rom_call_detail
        AlertKind.DOORS -> R.string.settings_rom_doors_detail
        AlertKind.RADAR -> R.string.settings_rom_radar_detail
        AlertKind.AC -> R.string.settings_rom_ac_detail
        AlertKind.TYRES -> R.string.settings_rom_tyres_detail
        AlertKind.BELT -> R.string.settings_rom_belt_detail
        AlertKind.GEMINI -> R.string.alert_gemini_detail
        AlertKind.VOLUME -> R.string.settings_rom_volume_detail
        AlertKind.ROAD -> R.string.alert_road_detail
    }

private val AlertKind.icon: ImageVector
    get() = when (this) {
        AlertKind.CALL -> Icons.Filled.Call
        AlertKind.DOORS -> Icons.Filled.SensorDoor
        AlertKind.RADAR -> Icons.Filled.Sensors
        AlertKind.AC -> Icons.Filled.AcUnit
        AlertKind.TYRES -> Icons.Filled.TireRepair
        AlertKind.BELT -> Icons.Filled.AirlineSeatReclineNormal
        AlertKind.GEMINI -> Icons.Filled.AutoAwesome
        AlertKind.VOLUME -> Icons.Filled.VolumeUp
        AlertKind.ROAD -> Icons.Filled.Traffic
    }

private val AlertKind.label: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call
        AlertKind.DOORS -> R.string.alert_kind_doors
        AlertKind.RADAR -> R.string.alert_kind_radar
        AlertKind.AC -> R.string.alert_kind_ac
        AlertKind.TYRES -> R.string.alert_kind_tyres
        AlertKind.BELT -> R.string.alert_kind_belt
        AlertKind.GEMINI -> R.string.ai_gemini_live
        AlertKind.VOLUME -> R.string.alert_kind_volume
        AlertKind.ROAD -> R.string.alert_kind_road
    }

private val AlertKind.dialogTitle: Int
    get() = when (this) {
        AlertKind.CALL -> R.string.alert_kind_call_title
        AlertKind.DOORS -> R.string.alert_kind_doors_title
        AlertKind.RADAR -> R.string.alert_kind_radar_title
        AlertKind.AC -> R.string.alert_kind_ac_title
        AlertKind.TYRES -> R.string.alert_kind_tyres_title
        AlertKind.BELT -> R.string.alert_kind_belt_title
        AlertKind.GEMINI -> R.string.alert_kind_gemini_title
        AlertKind.VOLUME -> R.string.alert_kind_volume_title
        AlertKind.ROAD -> R.string.alert_kind_road_title
    }

/** The alert's colour in the picker's small screens. */
@Composable
private fun AlertKind.tint(): Color = when (this) {
    AlertKind.DOORS, AlertKind.RADAR -> DashColors.Warning
    AlertKind.TYRES, AlertKind.BELT, AlertKind.ROAD -> DashColors.Critical
    else -> DashColors.Accent
}

@Composable
private fun AlertStyleDialog(kind: AlertKind, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val styles by AlertStyleStore.styles.collectAsState()
    val spoken by AlertStyleStore.spoken.collectAsState()
    val chosen = styles.of(kind)
    // A preview still up when the picker closes goes with it.
    DisposableEffect(Unit) { onDispose { AlertPreview.stop() } }

    AlertDialog(
        modifier = Modifier.widthIn(max = 760.dp).keepClearOfWindows(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = {
            Text(stringResource(kind.dialogTitle), color = DashColors.TextPrimary)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Its switch here too: a design is chosen for an alert that shows.
                if (switchable(kind)) {
                    val replaced by RomPopups.replaced.collectAsState()
                    SettingsToggle(
                        kind.icon, stringResource(R.string.alert_show), stringResource(kind.offDetail), kind.romKind in replaced
                    ) { on -> kind.romKind?.let { RomPopups.setReplaced(context, it, on) } }
                    Spacer(Modifier.height(12.dp))
                }
                kind.styles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        row.forEach { style ->
                            StyleOption(kind, style, style == chosen, Modifier.weight(1f)) {
                                AlertStyleStore.set(context, kind, style)
                                AlertPreview.show(context, kind)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                // The card is the one design the radar's place moves: on a portrait screen its right edge crosses the picture (#124).
                if (kind == AlertKind.RADAR && chosen == AlertStyle.CARD) {
                    Label(stringResource(R.string.alert_at))
                    SegmentedSwitch(
                        options = listOf(CardAt.TOP, CardAt.START, CardAt.END, CardAt.BOTTOM),
                        chosen = placementOf(kind),
                        icon = {
                            when (it) {
                                CardAt.TOP -> Icons.Filled.KeyboardArrowUp
                                CardAt.START -> Icons.Filled.KeyboardArrowLeft
                                CardAt.BOTTOM -> Icons.Filled.KeyboardArrowDown
                                else -> Icons.Filled.KeyboardArrowRight
                            }
                        },
                        title = {
                            stringResource(
                                when (it) {
                                    CardAt.TOP -> R.string.alert_at_top
                                    CardAt.START -> R.string.alert_at_start
                                    CardAt.BOTTOM -> R.string.alert_at_bottom
                                    else -> R.string.alert_at_end
                                }
                            )
                        }
                    ) {
                        AlertStyleStore.setRadarAt(context, it)
                        AlertPreview.show(context, kind)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (kind == AlertKind.CALL) {
                    Text(stringResource(R.string.alert_call_note), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                if (kind == AlertKind.GEMINI) {
                    // Which Gemini answers: Dashwheel's own, that knows the car, or Google's app.
                    var ai by remember { mutableStateOf(AiSettings.load(context)) }
                    SettingsToggle(
                        Icons.Filled.AutoAwesome, stringResource(R.string.ai_live_own), stringResource(R.string.ai_live_own_detail), ai.ownLive
                    ) {
                        // From the settings as saved now, not the copy loaded when the picker opened:
                        // a key typed meanwhile in the AI sheet would be written over.
                        ai = AiSettings.load(context).copy(ownLive = it)
                        AiSettings.save(context, ai)
                    }
                }
                if (kind == AlertKind.ROAD) {
                    // What it watches besides the car ahead: the lane, the traffic moving off.
                    Text(stringResource(R.string.alert_road_how), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    val lanes by RoadWatch.lanes.collectAsState()
                    val movedOff by RoadWatch.movedOff.collectAsState()
                    SettingsToggle(
                        Icons.Filled.SwapHoriz, stringResource(R.string.alert_road_lanes), stringResource(R.string.alert_road_lanes_detail), lanes
                    ) { RoadWatch.setLanes(context, it) }
                    SettingsToggle(
                        Icons.Filled.Traffic, stringResource(R.string.alert_road_moved_off), stringResource(R.string.alert_road_moved_off_detail), movedOff
                    ) { RoadWatch.setMovedOff(context, it) }
                }
                if (kind.speakable) {
                    // Its voice is here with its design, not in the "What the car says" list: said so.
                    Label(stringResource(R.string.alert_also_spoken))
                    SettingsToggle(
                        Icons.Filled.RecordVoiceOver, stringResource(R.string.alert_speak),
                        stringResource(
                            when (kind) {
                                AlertKind.CALL -> R.string.alert_speak_call_detail
                                AlertKind.TYRES -> R.string.alert_speak_tyres_detail
                                AlertKind.BELT -> R.string.alert_speak_belt_detail
                                AlertKind.ROAD -> R.string.alert_speak_road_detail
                                else -> R.string.alert_speak_doors_detail
                            }
                        ),
                        kind in spoken
                    ) { AlertStyleStore.setSpoken(context, kind, it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AlertPreview.show(context, kind) }) {
                Text(stringResource(R.string.alert_try), color = DashColors.AccentInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
        }
    )
}

@Composable
private fun StyleOption(kind: AlertKind, style: AlertStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = DashShape.Medium
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .clickable(role = Role.RadioButton) { tap(); onClick() }
            .padding(10.dp)
    ) {
        StyleThumbnail(kind, style, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(style.title), color = if (selected) DashColors.Accent else DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge
        )
        Text(
            stringResource(style.detail), color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** A small screen: a few tiles, and the alert drawn where the design puts it. */
@Composable
private fun StyleThumbnail(kind: AlertKind, style: AlertStyle, modifier: Modifier) {
    val screen = DashColors.Background.copy(alpha = 1f)
    val tile = DashColors.CardHi.compositeOver(DashColors.Card.copy(alpha = 1f))
    val alert = kind.tint()
    val frame = DashColors.Line
    val square = alertSquare
    val at = placementOf(kind)
    Canvas(modifier.clip(DashShape.Small)) {
        val w = size.width
        val h = size.height
        // The look's corners: square plates in the hard-edged looks (AlertChrome.kt).
        val r = CornerRadius(h * if (square) 0.015f else 0.04f)
        drawRect(screen)
        // The dashboard behind: three tiles.
        val gap = w * 0.03f
        val tw = (w - gap * 4) / 3f
        repeat(3) { i -> drawRoundRect(tile, Offset(gap + i * (tw + gap), h * 0.12f), Size(tw, h * 0.76f), r) }
        when (style) {
            AlertStyle.PILL -> drawRoundRect(alert, Offset(w * 0.36f, h * 0.05f), Size(w * 0.28f, h * 0.12f), if (square) r else CornerRadius(h))
            AlertStyle.CARD -> when (at) {
                CardAt.TOP -> drawRoundRect(alert, Offset(w * 0.3f, h * 0.07f), Size(w * 0.4f, h * 0.22f), r)
                CardAt.TOP_END -> drawRoundRect(alert, Offset(w * 0.56f, h * 0.07f), Size(w * 0.4f, h * 0.22f), r)
                CardAt.START -> drawRoundRect(alert, Offset(w * 0.04f, h * 0.2f), Size(w * 0.16f, h * 0.6f), r)
                CardAt.END -> drawRoundRect(alert, Offset(w * 0.8f, h * 0.2f), Size(w * 0.16f, h * 0.6f), r)
                CardAt.BOTTOM -> drawRoundRect(alert, Offset(w * 0.3f, h * 0.71f), Size(w * 0.4f, h * 0.22f), r)
            }
            AlertStyle.BANNER -> drawRoundRect(alert, Offset(w * 0.02f, h * 0.03f), Size(w * 0.96f, h * 0.16f), r)
            AlertStyle.PANEL -> {
                drawRect(alert.copy(alpha = 0.9f), Offset(w * 0.64f, 0f), Size(w * 0.36f, h))
                drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(w * 0.77f, h * 0.22f), Size(w * 0.1f, h * 0.5f), r)
            }
            AlertStyle.FULL -> {
                drawRect(screen.copy(alpha = 0.85f))
                drawRoundRect(alert, Offset(w * 0.3f, h * 0.25f), Size(w * 0.4f, h * 0.5f), r)
            }
            AlertStyle.BUBBLE -> {
                drawCircle(alert, radius = h * 0.14f, center = Offset(w * 0.86f, h * 0.2f))
                drawCircle(Color.White.copy(alpha = 0.7f), radius = h * 0.05f, center = Offset(w * 0.86f, h * 0.2f))
            }
            AlertStyle.ICON -> {
                drawCircle(alert.copy(alpha = 0.3f), radius = h * 0.1f, center = Offset(w * 0.88f, h * 0.16f))
                drawCircle(alert, radius = h * 0.05f, center = Offset(w * 0.88f, h * 0.16f))
            }
        }
        drawRoundRect(frame, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f), cornerRadius = r)
    }
}
