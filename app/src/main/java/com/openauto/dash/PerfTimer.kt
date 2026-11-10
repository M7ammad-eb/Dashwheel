package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One run's times, in milliseconds from moving off; null until reached. */
internal data class PerfResult(val to50: Long? = null, val to100: Long? = null, val quarter: Long? = null, val quarterKmh: Int? = null)

/**
 * A standing start timed from the speed readings: ready once the car has
 * stood still a moment, running from the first reading above zero, 0–50 and
 * 0–100 km/h worked out between the two readings either side, 400 m from the
 * distance the speeds add up to. A stop before 400 m ends the run.
 */
internal class PerfRun {
    enum class Phase { WAITING, READY, RUNNING, DONE }

    var phase = Phase.WAITING
        private set
    var result = PerfResult()
        private set
    private var stillSince = -1L
    private var startAt = 0L
    private var lastAt = 0L
    private var lastKmh = 0.0
    private var metres = 0.0

    /** A speed reading [kmh] at [atMs]; true when the run changed. */
    fun reading(atMs: Long, kmh: Double): Boolean {
        val before = phase to result
        when (phase) {
            Phase.WAITING, Phase.DONE -> if (kmh < STILL_KMH) {
                if (stillSince < 0) stillSince = atMs
                if (atMs - stillSince >= READY_MS) phase = Phase.READY
            } else stillSince = -1
            Phase.READY -> if (kmh >= STILL_KMH) {
                phase = Phase.RUNNING
                result = PerfResult()
                // The car left a moment before the reading that shows it moving: half a reading back.
                startAt = atMs - ((atMs - lastAt).coerceAtMost(500) / 2)
                metres = 0.0
                lastAt = startAt
                lastKmh = 0.0
            }
            Phase.RUNNING -> {
                val dt = (atMs - lastAt).coerceAtLeast(1)
                metres += (lastKmh + kmh) / 2 / 3.6 * dt / 1000.0
                fun cross(target: Double): Long = lastAt + ((target - lastKmh) / (kmh - lastKmh) * dt).toLong() - startAt
                if (result.to50 == null && kmh >= 50 && lastKmh < 50) result = result.copy(to50 = cross(50.0))
                if (result.to100 == null && kmh >= 100 && lastKmh < 100) result = result.copy(to100 = cross(100.0))
                if (result.quarter == null && metres >= QUARTER_M) {
                    result = result.copy(quarter = atMs - startAt, quarterKmh = kmh.toInt())
                }
                if (kmh < STILL_KMH || (result.quarter != null && result.to100 != null) || atMs - startAt > MAX_MS) {
                    phase = Phase.DONE
                    stillSince = if (kmh < STILL_KMH) atMs else -1
                }
            }
        }
        if (phase != Phase.RUNNING) lastAt = atMs
        if (phase == Phase.RUNNING) { lastAt = atMs; lastKmh = kmh }
        return before != (phase to result)
    }

    companion object {
        const val STILL_KMH = 1.0
        const val READY_MS = 1_500L
        const val QUARTER_M = 402.3
        /** A run longer than this is not a standing start any more. */
        const val MAX_MS = 60_000L
    }
}

/** The tile's timer: the speed readings while it shows, the best times kept. */
internal object PerfTimer {
    private const val PREFS = "perf_timer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var users = 0
    private val run = PerfRun()
    private const val TICK_MS = 100L

    private val _phase = MutableStateFlow(PerfRun.Phase.WAITING)
    val phase: StateFlow<PerfRun.Phase> = _phase
    private val _last = MutableStateFlow(PerfResult())
    val last: StateFlow<PerfResult> = _last
    private val _best = MutableStateFlow(PerfResult())
    val best: StateFlow<PerfResult> = _best

    private fun readBest(context: Context): PerfResult {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PerfResult(
            p.getLong("to50", 0).takeIf { it > 0 }, p.getLong("to100", 0).takeIf { it > 0 },
            p.getLong("quarter", 0).takeIf { it > 0 }, p.getInt("quarter_kmh", 0).takeIf { it > 0 }
        )
    }

    /** The saved bests into [best] without running the timer (a skin shows the 0-100 without the tile), read off the main thread. */
    suspend fun loadBest(context: Context) {
        if (_best.value != PerfResult()) return
        val app = context.applicationContext
        val saved = withContext(Dispatchers.IO) { readBest(app) }
        if (_best.value == PerfResult()) _best.value = saved
    }

    @Synchronized
    fun start(context: Context) {
        _best.value = readBest(context)
        if (users++ > 0) return
        val app = context.applicationContext
        job = scope.launch {
            // The speed only comes when it changes: read at a steady pace, so standing still counts too.
            var kmh = 0
            launch { carSpeedKmh().collect { kmh = it } }
            while (true) {
                // A clock GPS cannot set mid-run: the times are differences.
                if (run.reading(android.os.SystemClock.elapsedRealtime(), kmh.toDouble())) {
                    _phase.value = run.phase
                    _last.value = run.result
                    if (run.phase == PerfRun.Phase.DONE) keepBest(app, run.result)
                }
                kotlinx.coroutines.delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
    }

    private fun keepBest(context: Context, r: PerfResult) {
        val b = _best.value
        fun better(new: Long?, old: Long?) = if (new != null && (old == null || new < old)) new else old
        val quarterBetter = r.quarter != null && (b.quarter == null || r.quarter < b.quarter)
        val next = PerfResult(
            better(r.to50, b.to50), better(r.to100, b.to100),
            if (quarterBetter) r.quarter else b.quarter, if (quarterBetter) r.quarterKmh else b.quarterKmh
        )
        if (next == b) return
        _best.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("to50", next.to50 ?: 0).putLong("to100", next.to100 ?: 0)
            .putLong("quarter", next.quarter ?: 0).putInt("quarter_kmh", next.quarterKmh ?: 0).apply()
    }

    fun forgetBest(context: Context) {
        _best.value = PerfResult()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

private fun seconds(ms: Long?): String = ms?.let { String.format(Locale.getDefault(), "%.1f s", it / 1000.0) } ?: "--"

@Composable
internal fun PerfTimerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { PerfTimer.start(context); onDispose { PerfTimer.stop() } }
    val phase by PerfTimer.phase.collectAsState()
    val last by PerfTimer.last.collectAsState()
    val best by PerfTimer.best.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_perf)) {
                IconButton(onClick = { PerfTimer.forgetBest(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(R.string.widgets_perf_reset), tint = DashColors.Muted)
                }
            }
            Text(
                stringResource(
                    when (phase) {
                        PerfRun.Phase.WAITING -> R.string.widgets_perf_stop
                        PerfRun.Phase.READY -> R.string.widgets_perf_ready
                        PerfRun.Phase.RUNNING -> R.string.widgets_perf_running
                        PerfRun.Phase.DONE -> R.string.widgets_perf_done
                    }
                ),
                color = if (phase == PerfRun.Phase.READY) DashColors.Good else DashColors.TextSecondary,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Times("0–50", last.to50, best.to50)
                Times("0–100", last.to100, best.to100)
                Times("400 m", last.quarter, best.quarter)
            }
        }
    }
}

@Composable
private fun Times(label: String, last: Long?, best: Long?) {
    Column {
        Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(seconds(last), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.widgets_perf_best, seconds(best)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun perfTimerFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { PerfTimer.start(context); onDispose { PerfTimer.stop() } }
    val last by PerfTimer.last.collectAsState()
    val best by PerfTimer.best.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Timer,
        title = BuiltinKind.PERF_TIMER.label,
        value = seconds(last.to100),
        textValue = true,
        caption = stringResource(R.string.widgets_perf_best, seconds(best.to100)),
        stats = listOf(FaceStat("0–50", seconds(last.to50)), FaceStat("400 m", seconds(last.quarter)))
    )
}
