package com.openauto.dash

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** A fixed speed camera from OpenStreetMap, with its limit when tagged. */
internal data class SpeedCamera(val lat: Double, val lng: Double, val limitKmh: Int?)

/** The nearest camera ahead: how far, and its limit. */
internal data class CameraAhead(val camera: SpeedCamera, val distanceM: Int)

/**
 * Speed cameras ahead, from OpenStreetMap (Overpass): the ones within
 * [RADIUS_M] are read again every [REFETCH_M] driven. The nearest one in the
 * direction of travel is shown, and said out loud at [WARN_M] when the driver
 * turned warnings on ([SpokenEvent.SPEED_CAMERAS], off by default: warning of
 * fixed cameras is not allowed in some countries, France among them).
 *
 * Every answer is kept on the unit ([SpeedCameraStore]), with the whole set
 * of a country when the driver asked for one in Settings: when no server
 * answers, the cameras come from there ([fromStore]) and the drive goes on
 * as before, on roads driven already or anywhere in that country.
 */
internal object SpeedCameras {
    private const val TAG = "SpeedCameras"
    const val RADIUS_M = 15_000
    const val REFETCH_M = 8_000f
    /** After a failed read, the wait before the next: the server is busy, and every GPS fix asked it again. */
    private const val RETRY_AFTER_MS = 60_000L
    private const val AHEAD_DEG = 35.0
    private const val AHEAD_MAX_M = 3_000
    const val WARN_M = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var users = 0
    @Volatile private var cameras: List<SpeedCamera> = emptyList()
    @Volatile private var fetchedAt: Location? = null
    @Volatile private var fetching = false
    @Volatile private var failedAt = 0L
    @Volatile private var refreshedCountries = false
    private val warned = HashSet<SpeedCamera>()

    private val _ahead = MutableStateFlow<CameraAhead?>(null)
    val ahead: StateFlow<CameraAhead?> = _ahead
    private val _fromStore = MutableStateFlow(false)
    /** The cameras matched now are the unit's own, not a server's: the last read failed, or none was made yet. */
    val fromStore: StateFlow<Boolean> = _fromStore
    private var appContext: Context? = null

    /** Follows the drive while a tile shows the cameras, or always once warnings are on ([MainActivity]). */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        users++
        if (job?.isActive == true) return
        job = scope.launch { LocationFeed.location.collect { loc -> if (loc != null) onLocation(app, loc) } }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        val app = appContext
        if (app == null || !SpokenEvents.isOn(app, SpokenEvent.SPEED_CAMERAS)) { job?.cancel(); job = null }
    }

    /** Started at boot, and when the switch is turned on, only when warnings are on. */
    fun startIfSpeaking(context: Context) {
        if (SpokenEvents.isOn(context, SpokenEvent.SPEED_CAMERAS)) start(context)
    }

    private suspend fun onLocation(context: Context, loc: Location) {
        val last = fetchedAt
        val resting = android.os.SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS && failedAt != 0L
        if (!fetching && !resting && (last == null || last.distanceTo(loc) > REFETCH_M)) {
            fetching = true
            // Beside the fixes, not in their way (see SpeedLimit): the cameras already
            // read keep being matched against the car while the servers are tried.
            scope.launch(Dispatchers.IO) {
                // The unit's own cameras at once, for the first kilometres while the servers are tried.
                if (last == null) fromStore(context, loc)
                val found = runCatching { fetch(loc.latitude, loc.longitude) }
                found.onSuccess {
                    cameras = it; fetchedAt = loc; failedAt = 0L
                    _fromStore.value = false
                    // The demo's drive is made up, and promises to save nothing.
                    if (!DemoMode.isOn) SpeedCameraStore.remember(context, loc.latitude, loc.longitude, it)
                }.onFailure {
                    failedAt = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "cameras unread, the unit's own used: ${it.message}")
                    fromStore(context, loc)
                }
                fetching = false
                // A server answers: the countries kept for a week and more are read again, once a drive.
                if (found.isSuccess && !refreshedCountries) {
                    refreshedCountries = true
                    SpeedCameraStore.refreshStale(context)
                }
            }
        }
        val next = nearestAhead(loc, cameras)
        _ahead.value = next
        if (next != null && next.distanceM <= WARN_M && SpokenEvents.isOn(context, SpokenEvent.SPEED_CAMERAS) && warned.add(next.camera)) {
            val app = AppLanguage.wrap(context)
            val text = next.camera.limitKmh?.let { app.getString(R.string.widgets_cam_say_limit, Units.current.value.speed(it)) }
                ?: app.getString(R.string.widgets_cam_say)
            CarVoice.announce(text, InAppNav.locale(context))
        }
        if (warned.size > 200) warned.clear()
    }

    /** The kept cameras round [loc] stand in for the server's; with none kept there, the last answer stays. */
    private fun fromStore(context: Context, loc: Location) {
        val kept = SpeedCameraStore.near(context, loc.latitude, loc.longitude, RADIUS_M)
        if (kept.isEmpty()) return
        cameras = kept
        _fromStore.value = true
    }

    private fun fetch(lat: Double, lng: Double): List<SpeedCamera> {
        val query = "[out:json][timeout:20];node(around:$RADIUS_M,${Overpass.coord(lat)},${Overpass.coord(lng)})[highway=speed_camera];out;"
        return parse(Overpass.query(query))
    }

    /** The cameras in an Overpass answer: its speed_camera nodes, each with its limit when tagged. */
    fun parse(json: JSONObject): List<SpeedCamera> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.optJSONObject(i) ?: return@mapNotNull null
            if (!e.has("lat") || !e.has("lon")) return@mapNotNull null
            // As the road limits are read: "50 mph" is 80 km/h, not 50 (the card shows and says it in the driver's units).
            val limit = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed"))
            SpeedCamera(e.optDouble("lat"), e.optDouble("lon"), limit)
        }
    }

    /**
     * What a tile says with no camera ahead: that the server is [down], unless
     * the unit's own cameras stand in ([kept]), which is worth knowing when the
     * tile is quiet; else that there is none.
     */
    fun noneCaption(down: Boolean, kept: Boolean): Int = when {
        kept && down -> R.string.widgets_cam_none_kept
        down -> R.string.widgets_osm_down
        else -> R.string.widgets_cam_none
    }

    /** The nearest of [cameras] in front of the car within [AHEAD_MAX_M], by its course; null standing still or with none. */
    fun nearestAhead(loc: Location, cameras: List<SpeedCamera>): CameraAhead? {
        if (!loc.hasBearing()) return null
        return cameras.mapNotNull { c ->
            // The flat-earth distance first, with no allocation: a whole country's cameras are looked at each second.
            if (metresBetween(loc.latitude, loc.longitude, c.lat, c.lng) > AHEAD_MAX_M * 1.05) return@mapNotNull null
            val to = Location("cam").apply { latitude = c.lat; longitude = c.lng }
            val d = loc.distanceTo(to)
            if (d > AHEAD_MAX_M) return@mapNotNull null
            val diff = abs(((loc.bearingTo(to) - loc.bearing + 540) % 360) - 180)
            if (diff > AHEAD_DEG) null else CameraAhead(c, d.roundToInt())
        }.minByOrNull { it.distanceM }
    }
}

@Composable
internal fun SpeedCamerasCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedCameras.start(context); onDispose { SpeedCameras.stop() } }
    val ahead by SpeedCameras.ahead.collectAsState()
    val down by Overpass.down.collectAsState()
    val kept by SpeedCameras.fromStore.collectAsState()
    LaunchedEffect(Unit) { SpokenEvents.load(context) }
    val spoken by SpokenEvents.on.collectAsState()
    val speak = SpokenEvent.SPEED_CAMERAS in spoken
    val units by Units.current.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_cam)) {
                IconButton(onClick = { SpokenEvents.set(context, SpokenEvent.SPEED_CAMERAS, !speak) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(
                        if (speak) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = stringResource(R.string.widgets_cam_speak),
                        tint = if (speak) DashColors.Accent else DashColors.Muted
                    )
                }
            }
            val a = ahead
            if (a == null) {
                Text(stringResource(SpeedCameras.noneCaption(down, kept)), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = if (a.distanceM <= SpeedCameras.WARN_M) DashColors.Warning else DashColors.Accent)
                    Text(
                        "  " + units.shortDistance(a.distanceM.toDouble()),
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    a.camera.limitKmh?.let {
                        Text(units.speed(it).toString(), color = DashColors.Critical, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            Text(
                stringResource(if (speak) R.string.widgets_cam_speaking else R.string.widgets_cam_silent),
                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
internal fun speedCamerasFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedCameras.start(context); onDispose { SpeedCameras.stop() } }
    val ahead by SpeedCameras.ahead.collectAsState()
    val down by Overpass.down.collectAsState()
    val kept by SpeedCameras.fromStore.collectAsState()
    val units by Units.current.collectAsState()
    val a = ahead
    return WidgetFace(
        icon = Icons.Filled.CameraAlt,
        title = BuiltinKind.SPEED_CAMERAS.label,
        value = a?.let { units.shortDistance(it.distanceM.toDouble()) } ?: "",
        caption = a?.camera?.limitKmh?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(SpeedCameras.noneCaption(down, kept)),
        textValue = true,
        alert = a != null && a.distanceM <= SpeedCameras.WARN_M
    )
}
