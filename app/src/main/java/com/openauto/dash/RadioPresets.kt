package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A stored station: [freq] in hundredths of a MHz (9850 = 98.5 MHz). */
/** An FM frequency in the radio's 10 kHz units (64 to 108 MHz, Eastern Europe's band included); AM ones are in kHz. */
internal fun isFm(freq: Int): Boolean = freq in 6_000..11_000

internal data class RadioStation(val freq: Int, val name: String) {
    /** "98.5", "101.25", "104.0": also the tune command, where a tenth rounded off tuned 50 kHz beside a x.x5 station. */
    val mhz: String get() = (freq / 100.0).toBigDecimal().stripTrailingZeros().toPlainString().let { if ('.' in it) it else "$it.0" }
}

/**
 * The K706's FM radio (com.android.fmradio.ext) from a tile: its stored
 * stations (its open station list), the one playing (its broadcast), and a
 * tap to tune. The radio only takes orders while its screen is alive, so
 * when it isn't, it is started first and the dashboard brought back.
 */
internal object RadioPresets {
    private const val TAG = "RadioPresets"
    const val PACKAGE = "com.android.fmradio.ext"
    private val ACTIVITY = ComponentName(PACKAGE, "com.android.fmradio.FmMainActivity")
    private val STATIONS = Uri.parse("content://$PACKAGE/station")
    private const val UPDATE = "com.qf.radio.update_action"
    // Tunes to the MHz string in TUNE_EXTRA on the band the radio is on, with no region
    // check. The voice command ("ailit.set.radio.frequency") checks the frequency against
    // band FM1 of the unit's radio region and says "Invalid frequency, please reset" for
    // good stations (Eastern Europe FM1 is 65-74 MHz, the US region only takes odd tenths).
    private const val TUNE = "/customize/radio/station"
    /** Steps the radio to its next band (FM1, FM2, FM3, AM1, AM2, then FM1 again). */
    private const val BAND = "/customize/radio/band"
    /** At most this many steps from AM back to FM, each awaited this long. */
    private const val BAND_STEPS = 4
    private const val BAND_WAIT_MS = 1_500L
    private const val TUNE_EXTRA = "com.qf.radio.update_action_key"
    private const val AM_BAND = 3
    private const val NEXT = "/customize/radio/next"
    private const val PREVIOUS = "/customize/radio/pre"
    private const val START_WAIT_MS = 1_800L
    const val SHOWN = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var users = 0

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations
    private val _playing = MutableStateFlow<RadioStation?>(null)
    val playing: StateFlow<RadioStation?> = _playing
    /** The radio's band from its last broadcast: 0..2 FM1..FM3, 3 AM, -1 not heard yet. */
    @Volatile private var band = -1

    fun installed(context: Context): Boolean = isPackageInstalled(context, PACKAGE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val freq = intent.getIntExtra("${UPDATE}_freq_key", 0).takeIf { it > 0 } ?: return
            band = intent.getIntExtra("${UPDATE}_band_key", -1)
            val name = intent.getStringExtra("${UPDATE}_name_key").orEmpty().trim()
            _playing.value = RadioStation(freq, name.ifEmpty { names(context).getString(freq.toString(), null).orEmpty() })
            // The radio keeps its station names to itself: remember the one it announces.
            if (name.isNotEmpty() && band in 0 until AM_BAND && isFm(freq) && !intent.getBooleanExtra("${UPDATE}_searching_key", false)) learn(context, freq, name)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (users++ == 0) ContextCompat.registerReceiver(app, receiver, IntentFilter(UPDATE), ContextCompat.RECEIVER_EXPORTED)
        scope.launch { _stations.value = withContext(Dispatchers.IO) { read(app) } }
        if (_playing.value == null) {
            systemProperty("sys.qf.station_freq")?.toIntOrNull()?.takeIf { it > 0 }?.let { _playing.value = RadioStation(it, "") }
        }
    }

    fun stop(context: Context) {
        if (users == 0 || --users > 0) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun names(context: Context) = context.applicationContext.getSharedPreferences("radio_names", Context.MODE_PRIVATE)

    private fun learn(context: Context, freq: Int, name: String) {
        val prefs = names(context)
        if (prefs.getString(freq.toString(), null) == name) return
        prefs.edit().putString(freq.toString(), name).apply()
        _stations.value = _stations.value.map { if (it.freq == freq) it.copy(name = name) else it }
    }

    /** The stored stations, favourites and presets first, at most [SHOWN]. */
    private fun read(context: Context): List<RadioStation> = runCatching {
        val learned = names(context)
        val out = ArrayList<Triple<RadioStation, Int, Boolean>>()
        context.contentResolver.query(STATIONS, null, null, null, null)?.use { c ->
            val f = c.getColumnIndex("frequency")
            val n = c.getColumnIndex("station_name")
            val ps = c.getColumnIndex("program_service")
            val p = c.getColumnIndex("preset")
            val fav = c.getColumnIndex("is_favorite")
            while (c.moveToNext()) {
                if (f < 0) break
                // FM only, told by its frequency: the table's band numbers are not the broadcast's,
                // and the AM presets (522 to 1620 kHz) showed as "5.2" to "16.2" and tuned AM.
                val freq = c.getInt(f).takeIf { isFm(it) } ?: continue
                val name = listOf(n, ps).firstNotNullOfOrNull { i -> if (i >= 0) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() } else null }
                    ?: learned.getString(freq.toString(), null).orEmpty()
                out += Triple(RadioStation(freq, name), if (p >= 0) c.getInt(p) else 0, fav >= 0 && c.getInt(fav) != 0)
            }
        }
        out.sortedWith(compareByDescending<Triple<RadioStation, Int, Boolean>> { it.third }.thenBy { if (it.second > 0) it.second else Int.MAX_VALUE })
            .map { it.first }.distinctBy { it.freq }.take(SHOWN)
    }.onFailure { Log.w(TAG, "stations unread: ${it.message}") }.getOrDefault(emptyList())

    fun tune(context: Context, station: RadioStation) = command(context) { app ->
        // The radio tunes the string on the band it is on ("10.0" became 999 kHz on AM):
        // stepped to FM first, each step awaited in its own broadcast.
        var steps = 0
        while (band >= AM_BAND && steps++ < BAND_STEPS) {
            val before = band
            app.sendBroadcast(Intent(BAND))
            withTimeoutOrNull(BAND_WAIT_MS) { while (band == before) delay(100) }
        }
        app.sendBroadcast(Intent(TUNE).putExtra(TUNE_EXTRA, station.mhz))
        _playing.value = station
    }

    fun next(context: Context) = command(context) { it.sendBroadcast(Intent(NEXT)) }
    fun previous(context: Context) = command(context) { it.sendBroadcast(Intent(PREVIOUS)) }

    /** [send] once the radio's screen is alive: started first when it isn't, and the dashboard brought back over it. */
    private fun command(context: Context, send: suspend (Context) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            if (systemProperty("sys.qf.radio.status") != "true") {
                runCatching { app.startActivity(Intent().setComponent(ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w(TAG, "can't start the radio", it); return@launch }
                delay(START_WAIT_MS)
                send(app)
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            } else {
                send(app)
            }
        }
    }

    private fun systemProperty(name: String): String? = SysProps.get(name)
}

@Composable
internal fun RadioPresetsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioPresets.start(context); onDispose { RadioPresets.stop(context) } }
    val stations by RadioPresets.stations.collectAsState()
    val playing by RadioPresets.playing.collectAsState()
    val tap = rememberTapFeedback()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = DashColors.Accent)
                Text(
                    "  " + (playing?.let { p -> listOf(p.name, p.mhz).filter { it.isNotEmpty() }.joinToString("  ") } ?: stringResource(R.string.widgets_radio)),
                    color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { tap(); RadioPresets.previous(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.widgets_radio_previous), tint = DashColors.TextPrimary)
                }
                IconButton(onClick = { tap(); RadioPresets.next(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.widgets_radio_next), tint = DashColors.TextPrimary)
                }
            }
            if (!RadioPresets.installed(context)) {
                Text(stringResource(R.string.widgets_radio_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (stations.isEmpty()) {
                Text(stringResource(R.string.widgets_radio_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // Two rows of three: big enough for a finger at the wheel.
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                stations.chunked(3).forEach { row ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                        row.forEach { s ->
                            val on = playing?.freq == s.freq
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(DashShape.Medium)
                                    .background(if (on) DashColors.Accent.copy(alpha = 0.25f) else DashColors.CardHi)
                                    .clickable(role = Role.Button) { tap(); RadioPresets.tune(context, s) },
                                contentAlignment = Alignment.Center
                            ) {
                                // The name when the radio has told us one, the frequency under it.
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = DashSpace.Xs)) {
                                    Text(
                                        s.name.ifEmpty { s.mhz }, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                                    )
                                    if (s.name.isNotEmpty()) Text(s.mhz, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun radioPresetsFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioPresets.start(context); onDispose { RadioPresets.stop(context) } }
    val playing by RadioPresets.playing.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Radio,
        title = BuiltinKind.RADIO_PRESETS.label,
        value = playing?.mhz ?: "",
        unit = if (playing != null) "MHz" else "",
        caption = playing?.name.orEmpty(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.widgets_radio_previous), onClick = { RadioPresets.previous(context) }),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.widgets_radio_next), onClick = { RadioPresets.next(context) })
        )
    )
}
