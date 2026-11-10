package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.runtime.key

/*
 * The Head unit and Gear widgets (HeadUnitMonitor.kt, GearEstimator.kt):
 * their standard tiles, their faces for the designs, the list of running
 * apps with Stop, and the gear gate design.
 */

// --- Head unit ------------------------------------------------------------------------------------

/** Keeps the monitor reading while this tile's page can be seen. */
@Composable
private fun rememberHeadUnitStats(): HeadUnitStats? {
    val context = LocalContext.current
    val active = LocalPageActive.current
    DisposableEffect(active) {
        if (active) HeadUnitMonitor.acquire(context)
        onDispose { if (active) HeadUnitMonitor.release() }
    }
    return HeadUnitMonitor.stats.collectAsState().value
}

/** The second screen's name and its board's last report, while it is linked and the report is one with its load. */
private data class SecondBoard(val name: String, val stats: com.openauto.dash.link.DisplayStats)

@Composable
private fun rememberSecondScreenBoard(): SecondBoard? {
    val link by DisplayLink.state.collectAsState()
    val stats by DisplayLink.stats.collectAsState()
    val display = (link as? DisplayLinkState.Connected)?.display ?: return null
    val s = stats?.takeIf { it.ramTotalMb != null || it.cpuPct != null || it.tempC != null } ?: return null
    return SecondBoard(display.name, s)
}

/**
 * The second screen's board (the Pi): its name and health, then its
 * processor, memory, temperature and clock; on a medium tile, one line.
 */
@Composable
private fun BoardMonitor(b: SecondBoard, full: Boolean) {
    val s = b.stats
    val u = LocalUnits.current
    val health = SecondScreenRules.health(s)
    val healthText = when (health) {
        DisplayHealth.UNDER_POWERED -> stringResource(R.string.monitor_board_under_powered)
        DisplayHealth.HOT -> stringResource(R.string.monitor_board_hot)
        DisplayHealth.THROTTLED -> stringResource(R.string.monitor_board_throttled)
        else -> null
    }
    val ramPct = if (s.ramUsedMb != null && s.ramTotalMb != null && s.ramTotalMb!! > 0) s.ramUsedMb!! * 100 / s.ramTotalMb!! else null
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            (stringResource(R.string.monitor_board_title) + " · " + b.name).uppercase(Locale.getDefault()),
            color = DashColors.Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (healthText != null) {
            Text(healthText, color = DashColors.Warning, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
        if (!full) {
            Text(
                listOfNotNull(
                    s.cpuPct?.let { stringResource(R.string.monitor_cpu) + " $it %" },
                    ramPct?.let { stringResource(R.string.monitor_ram) + " $it %" },
                    s.tempC?.let { u.tempText(it) }
                ).joinToString(" · "),
                color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1
            )
            return
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 4.dp)) {
            BigReading(stringResource(R.string.monitor_cpu), s.cpuPct?.toString() ?: "--", "%", loadColor(s.cpuPct), 30)
            BigReading(stringResource(R.string.monitor_chip), s.tempC?.let { u.temp(it).toString() } ?: "--", u.tempUnit, tempColor(s.tempC), 30)
            if (s.cpuMhz != null) BigReading(stringResource(R.string.monitor_board_clock), s.cpuMhz.toString(), "MHz", DashColors.TextPrimary, 30)
        }
        if (ramPct != null) Meter(stringResource(R.string.monitor_ram), "${s.ramUsedMb} / ${s.ramTotalMb} MB", ramPct)
    }
}

/** Amber from 70 %, red from 90 % (the chip: 70 and 80 °C). */
private fun loadColor(pct: Int?): Color = when {
    pct == null -> DashColors.Muted
    pct >= 90 -> DashColors.Critical
    pct >= 70 -> DashColors.Warning
    else -> DashColors.Accent
}

private fun tempColor(c: Int?): Color = when {
    c == null -> DashColors.Muted
    c >= 80 -> DashColors.Critical
    c >= 70 -> DashColors.Warning
    else -> DashColors.TextPrimary
}

private fun gb(v: Float) = String.format(Locale.getDefault(), "%.1f", v)

private fun uptime(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} h ${String.format(Locale.getDefault(), "%02d", min % 60)}" else "$min min"
}

@Composable
internal fun HeadUnitCard(modifier: Modifier = Modifier) {
    val stats = rememberHeadUnitStats()
    val lock = LocalDriveLock.current
    var showApps by remember { mutableStateOf(false) }
    val openApps: (() -> Unit)? = if (stats?.apps?.isNotEmpty() == true) ({ lock.whenParked { showApps = true } }) else null
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            val large = maxHeight >= 330.dp && maxWidth >= 380.dp
            val medium = maxHeight >= 200.dp && maxWidth >= 190.dp
            val tight = maxWidth < 260.dp
            Column(modifier = Modifier.fillMaxSize()) {
                TileHeader(stringResource(R.string.monitor_title)) {
                    if (large && stats != null) {
                        Text(
                            HeadUnitMonitor.androidName() + " · " + stringResource(R.string.monitor_up, uptime(stats.uptimeMs)),
                            color = DashColors.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val board = rememberSecondScreenBoard()
                when {
                    stats == null -> Text(stringResource(R.string.monitor_reading), color = DashColors.TextSecondary)
                    large -> LargeMonitor(stats, openApps)
                    medium -> MediumMonitor(stats, openApps, if (tight) 32 else 44)
                    else -> SmallMonitor(stats, openApps)
                }
                // The second screen's own board (a Raspberry Pi), when one is linked and reporting.
                if (board != null && (large || medium)) {
                    Spacer(Modifier.height(12.dp))
                    BoardMonitor(board, full = large)
                }
            }
        }
    }
    if (showApps) HeadUnitAppsSheet { showApps = false }
}

@Composable
private fun LargeMonitor(s: HeadUnitStats, openApps: (() -> Unit)?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Ring(s.cpuPct, s.cpuPct?.let { "$it %" } ?: "--", stringResource(R.string.monitor_cpu), 132.dp, pluralStringResource(R.plurals.monitor_cores, s.cores, s.cores))
            Ring(s.ramPct, "${s.ramPct} %", stringResource(R.string.monitor_ram), 132.dp, gb(s.ramUsedMb / 1024f) + " / " + gb(s.ramTotalMb / 1024f) + " GB")
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.monitor_chip), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                HeroNumber(s.tempC?.let { LocalUnits.current.tempText(it) } ?: "--", 34)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.monitor_storage), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text("${gb(s.storageUsedGb)} / ${gb(s.storageTotalGb)} GB", color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (s.cpuPct == null) {
            Text(stringResource(R.string.monitor_no_shell), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
            return
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(openApps?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
            Text(
                stringResource(R.string.monitor_apps_running).uppercase(Locale.getDefault()) + (s.appCount?.let { " · $it" } ?: ""),
                color = DashColors.Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium
            )
            s.apps.take(5).forEach { key(it.packageName) { AppLoadRow(it, stop = null) } }
        }
    }
}

@Composable
private fun MediumMonitor(s: HeadUnitStats, openApps: (() -> Unit)?, numberSize: Int) {
    Column(modifier = Modifier.fillMaxWidth().then(openApps?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigReading(stringResource(R.string.monitor_cpu), s.cpuPct?.toString() ?: "--", "%", loadColor(s.cpuPct), numberSize)
            val u = LocalUnits.current
            BigReading(stringResource(R.string.monitor_chip), s.tempC?.let { u.temp(it).toString() } ?: "--", u.tempUnit, tempColor(s.tempC), numberSize)
        }
        Meter(stringResource(R.string.monitor_ram), "${gb(s.ramUsedMb / 1024f)} / ${gb(s.ramTotalMb / 1024f)} GB", s.ramPct)
        Meter(stringResource(R.string.monitor_storage), "${gb(s.storageUsedGb)} / ${gb(s.storageTotalGb)} GB", s.storagePct)
        s.apps.firstOrNull { it.packageName != null }?.let { top ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.monitor_busiest, "${top.label} ${top.cpuPct.toInt()} %"),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SmallMonitor(s: HeadUnitStats, openApps: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().then(openApps?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            BigReading(stringResource(R.string.monitor_cpu), s.cpuPct?.toString() ?: "--", "%", loadColor(s.cpuPct), 30)
            BigReading(stringResource(R.string.monitor_ram), s.ramPct.toString(), "%", loadColor(s.ramPct), 30)
        }
        Text(
            listOfNotNull(s.tempC?.let { LocalUnits.current.tempText(it) }, s.appCount?.let { pluralStringResource(R.plurals.monitor_apps_count, it, it) }).joinToString(" · "),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1
        )
    }
}

@Composable
private fun BigReading(label: String, value: String, unit: String, color: Color, size: Int = 44) {
    Column {
        Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold, fontFamily = DashColors.heroFamily(), maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = DashColors.TextSecondary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = (size / 7).dp))
        }
    }
}

@Composable
private fun Meter(label: String, value: String, pct: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Box(modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(DashColors.CardHi)) {
            Box(modifier = Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(loadColor(pct)))
        }
    }
}

/** A three-quarter ring filled to [pct], the reading and its label inside. */
@Composable
private fun Ring(pct: Int?, value: String, label: String, size: Dp, detail: String = "") {
    val track = DashColors.CardHi
    val fill = loadColor(pct)
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val box = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (pct != null && pct > 0) drawArc(fill, 135f, 270f * pct / 100f, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(value, color = DashColors.TextPrimary, fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.SemiBold, fontFamily = DashColors.heroFamily(), maxLines = 1)
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Each app's icon as the Head unit tile draws it, for the life of the process. */
private val monitorIcons = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

/** One app's CPU and memory; with [stop], its Stop button. */
@Composable
private fun AppLoadRow(app: AppLoad, stop: (() -> Unit)?, stopColumn: Boolean = false) {
    val context = LocalContext.current
    // Kept per app: the rows reorder every few seconds, and an icon drawn once is drawn for good.
    val icon = remember(app.packageName) {
        app.packageName?.let { pkg ->
            synchronized(monitorIcons) {
                monitorIcons.getOrPut(pkg) { loadAppIcon(context, pkg)?.let { runCatching { it.toBitmap(64, 64).asImageBitmap() }.getOrNull() } }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
        else Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(DashColors.CardHi))
        Spacer(Modifier.width(10.dp))
        Text(app.label, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            "${app.cpuPct.toInt()} %", color = if (app.cpuPct >= 30f) DashColors.Warning else DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 52.dp), maxLines = 1
        )
        Text("${app.ramMb} MB", color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 72.dp), maxLines = 1)
        // The same room with or without the button, so the columns line up.
        Box(modifier = Modifier.width(if (stopColumn) 76.dp else 0.dp), contentAlignment = Alignment.CenterEnd) {
            if (stop != null) {
                val tap = rememberTapFeedback()
                Text(
                    stringResource(R.string.monitor_stop), color = DashColors.Critical, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(DashColors.Critical.copy(alpha = 0.14f))
                        .clickable { tap(); stop() }.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Every app running, with Stop where it is safe to stop it. Parked only. */
@Composable
internal fun HeadUnitAppsSheet(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by HeadUnitMonitor.stats.collectAsState()
    // The order is kept while the sheet is open: rows re-sorting every few
    // seconds would move a Stop button from under the finger. New apps go last.
    val order = remember { mutableListOf<String>() }
    val apps = stats?.apps.orEmpty()
    apps.forEach { a -> if (a.label !in order) order += a.label }
    val shown = order.mapNotNull { key -> apps.firstOrNull { it.label == key } }
    SettingsSheet(
        title = stringResource(R.string.monitor_apps_running),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.dash_close), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.monitor_stop_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        shown.forEach { app ->
            val pkg = app.packageName
            AppLoadRow(app, stop = if (pkg != null && HeadUnitMonitor.canStop(context, pkg)) ({ scope.launch { HeadUnitMonitor.stop(context, pkg) } }) else null, stopColumn = true)
        }
    }
}

/** The Head unit tile for the designs: CPU as the headline, then memory, the chip and storage. */
@Composable
internal fun headUnitFace(): WidgetFace {
    val s = rememberHeadUnitStats()
    val cpu = s?.cpuPct
    val worst = listOfNotNull(cpu, s?.ramPct).maxOrNull() ?: 0
    val hot = s?.tempC ?: 0
    val lock = LocalDriveLock.current
    var showApps by remember { mutableStateOf(false) }
    if (showApps) HeadUnitAppsSheet { showApps = false }
    val cpuLabel = stringResource(R.string.monitor_cpu)
    val ramLabel = stringResource(R.string.monitor_ram)
    val chipLabel = stringResource(R.string.monitor_chip)
    val storageLabel = stringResource(R.string.monitor_storage)
    val u = LocalUnits.current
    return WidgetFace(
        icon = Icons.Filled.Memory,
        title = BuiltinKind.HEAD_UNIT.label,
        value = cpu?.toString() ?: s?.ramPct?.toString() ?: "--",
        unit = "%",
        caption = if (cpu != null) cpuLabel else if (s != null) ramLabel else stringResource(R.string.monitor_reading),
        fraction = ((cpu ?: s?.ramPct ?: 0) / 100f),
        severity = when {
            worst >= 90 || hot >= 80 -> 2
            worst >= 70 || hot >= 70 -> 1
            else -> 0
        },
        stats = listOfNotNull(
            FaceStat(ramLabel, s?.let { "${it.ramPct} %" } ?: "--"),
            FaceStat(chipLabel, s?.tempC?.let { u.tempText(it) } ?: "--"),
            FaceStat(storageLabel, s?.let { "${it.storagePct} %" } ?: "--")
        ),
        gauges = listOfNotNull(
            cpu?.let { FaceGauge(cpuLabel, it.toString(), "%", it / 100f) },
            s?.let { FaceGauge(ramLabel, it.ramPct.toString(), "%", it.ramPct / 100f) },
            s?.tempC?.let { FaceGauge(chipLabel, u.temp(it).toString(), u.tempUnit, it / 100f) },
            s?.let { FaceGauge(storageLabel, it.storagePct.toString(), "%", it.storagePct / 100f) }
        ),
        rows = s?.apps.orEmpty().take(4).map { FaceRow(it.label, "${it.cpuPct.toInt()} % · ${it.ramMb} MB") },
        onClick = if (s?.apps?.isNotEmpty() == true) ({ lock.whenParked { showApps = true } }) else null
    )
}

// --- Gear -----------------------------------------------------------------------------------------

/** What the gear reads as: R, N, the gear's number, or a dash. */
internal fun gearGlyph(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    Gear.Free -> "N"
    is Gear.Forward -> gear.n.toString()
    Gear.Stopped, Gear.Unknown -> "–"
}

@Composable
private fun gearCaption(r: GearReading): String = when (val g = r.gear) {
    Gear.Reverse -> stringResource(R.string.gear_reverse)
    Gear.Free -> stringResource(R.string.gear_free)
    Gear.Stopped -> stringResource(R.string.gear_stopped)
    is Gear.Forward -> stringResource(R.string.gear_nth, g.n) + (r.rpm?.let { " · " + String.format(Locale.getDefault(), "%,d rpm", it) } ?: "")
    Gear.Unknown -> if (r.rpm == null) stringResource(R.string.gear_needs_data) else ""
}

@Composable
internal fun GearCard(modifier: Modifier = Modifier) {
    val reading by rememberGear()
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), contentAlignment = Alignment.Center) {
            val roomy = maxHeight >= 150.dp
            val gate = maxWidth >= 260.dp && maxHeight >= 210.dp
            val glyphSize = (min(maxWidth, maxHeight).value * if (gate) 0.42f else 0.55f).coerceIn(28f, 150f)
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (roomy) TileHeader(stringResource(R.string.gear_title))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        gearGlyph(reading.gear),
                        color = when (reading.gear) {
                            Gear.Reverse -> DashColors.Warning
                            is Gear.Forward -> DashColors.Accent
                            else -> DashColors.TextSecondary
                        },
                        fontSize = glyphSize.sp, lineHeight = glyphSize.sp,
                        fontWeight = FontWeight.Bold, fontFamily = DashColors.heroFamily(), maxLines = 1
                    )
                }
                val caption = gearCaption(reading)
                if (roomy && caption.isNotEmpty()) {
                    Text(caption, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (gate) {
                    Spacer(Modifier.height(10.dp))
                    GateRow(reading, DashColors.Accent, DashColors.Warning, DashColors.CardHi, DashColors.TextSecondary, DashColors.OnAccent)
                }
            }
        }
    }
}

/** R, N and the forward gears in a row, the one engaged lit. */
@Composable
private fun GateRow(r: GearReading, accent: Color, warn: Color, fill: Color, ink: Color, onAccent: Color, cell: Dp = 30.dp) {
    val lit = gearGlyph(r.gear)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        (listOf("R", "N") + (1..r.gears).map { it.toString() }).forEach { g ->
            val on = g == lit
            val colour = if (g == "R") warn else accent
            Box(
                modifier = Modifier.size(cell).clip(RoundedCornerShape(8.dp))
                    .background(if (on) colour else fill)
                    .then(if (!on && g == "R") Modifier.border(1.dp, warn.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(g, color = if (on) onAccent else ink, fontWeight = FontWeight.Bold, fontSize = (cell.value * 0.5f).sp, maxLines = 1)
            }
        }
    }
}

/** The Gear tile for the designs: the gear as the headline, the revs lighting the shift lights. */
@Composable
internal fun gearFace(): WidgetFace {
    val r by rememberGear()
    // As state: a red line typed in while the tile shows moves the shift lights at once.
    val profile by CarProfileStore.profile.collectAsState()
    val redline = profile.redlineRpm ?: DEFAULT_REDLINE_RPM
    val u = LocalUnits.current
    return WidgetFace(
        icon = Icons.Filled.SettingsInputComponent,
        title = BuiltinKind.GEAR.label,
        value = gearGlyph(r.gear),
        caption = gearCaption(r),
        fraction = r.rpm?.let { it.toFloat() / redline },
        severity = if (r.gear == Gear.Reverse) 1 else 0,
        gears = r.gears,
        stats = listOfNotNull(
            r.rpm?.let { FaceStat("rpm", String.format(Locale.getDefault(), "%,d", it)) },
            r.speedKmh?.let { FaceStat(u.speedUnit, u.speed(it).toString()) }
        )
    )
}

/** The gear gate design: the gear large, and under it the gate with the one engaged lit. */
@Composable
internal fun GearGate(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FaceHeader(f, look, m)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            FaceText(
                f.value, look, m.sp(kotlin.math.min(m.h * 0.42f, m.w * 0.4f)),
                color = if (f.value == "R") look.warn else look.accent, weight = FontWeight.ExtraBold
            )
        }
        FaceCaption(f, look, m)
        val gears = f.gears ?: 6
        val cell = ((m.w - m.pad * 2 - (gears + 1) * 5f) / (gears + 2)).coerceIn(14f, 40f).dp
        Spacer(Modifier.height(m.dp(3f)))
        GateRow(
            GearReading(glyphGear(f.value), null, null, gears),
            look.accent, look.warn, look.fill, look.dim, look.onAccent, cell
        )
    }
}

private fun glyphGear(glyph: String): Gear = when (glyph) {
    "R" -> Gear.Reverse
    "N" -> Gear.Free
    else -> glyph.toIntOrNull()?.let { Gear.Forward(it) } ?: Gear.Unknown
}
