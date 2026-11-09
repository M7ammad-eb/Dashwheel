package com.openauto.dash

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** One road near the car: its limit and its shape. */
internal class LimitedRoad(val limitKmh: Int, val points: List<Pair<Double, Double>>)

/** OpenStreetMap's speed limit text in km/h: "80", "50 mph", or a country's implied ones ("FR:urban"); null for none. */
internal fun parseMaxspeed(tag: String?): Int? {
    val t = tag?.trim()?.lowercase() ?: return null
    t.takeWhile { it.isDigit() }.toIntOrNull()?.let { n -> return if ("mph" in t) (n * 1.609).toInt() else n }
    return when (t) {
        "fr:urban", "de:urban", "be:urban", "it:urban", "es:urban", "nl:urban" -> 50
        "fr:rural" -> 80
        "de:rural", "be-wal:rural", "it:rural", "es:rural", "nl:rural" -> 90
        "fr:motorway", "it:motorway", "be:motorway", "nl:motorway" -> 130
        "es:motorway" -> 120
        "walk", "fr:walk" -> 6
        "fr:zone30", "de:zone30" -> 30
        else -> null
    }
}

/**
 * The road the car is on, by its position and its course, among [roads]:
 * the nearest within [MAX_M] that runs the way the car goes; null off any.
 */
internal fun roadAt(lat: Double, lng: Double, bearing: Float?, roads: List<LimitedRoad>): LimitedRoad? {
    val mPerLat = 111_320.0
    val mPerLng = 111_320.0 * cos(Math.toRadians(lat))
    var best: LimitedRoad? = null
    var bestD = SpeedLimit.MAX_M
    for (r in roads) {
        for (i in 0 until r.points.size - 1) {
            val (aLat, aLng) = r.points[i]
            val (bLat, bLng) = r.points[i + 1]
            val ax = (aLng - lng) * mPerLng; val ay = (aLat - lat) * mPerLat
            val bx = (bLng - lng) * mPerLng; val by = (bLat - lat) * mPerLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            if (d >= bestD) continue
            if (bearing != null && len2 > 1.0) {
                val seg = Math.toDegrees(Math.atan2(dx, dy)).toFloat()
                val diff = abs(((seg - bearing + 540) % 360) - 180)
                // Either way along the road: most ways carry traffic both ways.
                if (diff > 45 && diff < 135) continue
            }
            bestD = d
            best = r
        }
    }
    return best
}

/**
 * When staying over the limit is said ([SpeedLimit]), keyed on the limit's
 * value, not on the road: OpenStreetMap splits one road into many ways, and
 * they are read again every kilometre. Over by more than [tolerance] for
 * [overMs]: said once, then not again for that value for [againMs], unless
 * the car dropped back under the limit for [underMs] and went over again. A
 * new value is said after its own [overMs]. Pure, so it's unit-tested.
 */
internal class LimitNag(
    val tolerance: Int = 5,
    val overMs: Long = 4_000L,
    val againMs: Long = 3 * 60_000L,
    val underMs: Long = 30_000L
) {
    private var lastLimit: Int? = null
    // Since when (ms) the car has been over, or under, the limit; null when it isn't.
    private var overSince: Long? = null
    private var underSince: Long? = null
    private var warnedLimit: Int? = null
    private var warnedAt = 0L

    /** Whether to say it now, at [now] (ms), doing [kmh] on a road limited to [limit] (null: unknown). */
    fun step(limit: Int?, kmh: Int, now: Long): Boolean {
        if (limit != lastLimit) {
            lastLimit = limit
            overSince = null
            underSince = null
        }
        if (limit == null) return false
        if (kmh <= limit + tolerance) {
            overSince = null
            if (kmh <= limit) {
                val since = underSince ?: now.also { underSince = it }
                if (now - since >= underMs) warnedLimit = null
            } else {
                underSince = null
            }
            return false
        }
        underSince = null
        val since = overSince ?: now.also { overSince = it }
        if (now - since < overMs) return false
        if (warnedLimit == limit && now - warnedAt < againMs) return false
        warnedLimit = limit
        warnedAt = now
        return true
    }
}

/**
 * The speed limit of the road the car is on, from OpenStreetMap (Overpass):
 * the roads around with a limit are read again every [REFETCH_M], the one the
 * car is on is found by position and course. Staying over the limit is said
 * out loud when the driver asked for it ([SpokenEvent.SPEED_LIMIT], off until
 * then; the tile's button is the same switch), once per limit ([LimitNag]).
 * Legal everywhere, unlike camera warnings. A limit Road watch read off a
 * sign ([sign]) stands over the map's for the next [SIGN_HOLD_M]: signs are
 * newer than the map, and roadworks put up their own.
 */
internal object SpeedLimit {
    private const val TAG = "SpeedLimit"
    private const val RADIUS_M = 1_500
    private const val REFETCH_M = 1_000f
    /** After a failed read, the wait before the next: the servers are down, and every GPS fix asked again. */
    private const val RETRY_AFTER_MS = 60_000L
    const val MAX_M = 30.0
    /** Over by more than this counts as over, on the tile and out loud. */
    const val TOLERANCE_KMH = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var users = 0
    @Volatile private var roads: List<LimitedRoad> = emptyList()
    @Volatile private var fetchedAt: Location? = null
    @Volatile private var fetching = false
    @Volatile private var failedAt = 0L
    private val nag = LimitNag(tolerance = TOLERANCE_KMH)
    /** The camera's sign, where it was read: it holds for [SIGN_HOLD_M] from there, or until the sign that ends it. */
    @Volatile private var signKmh: Int? = null
    @Volatile private var signAt: Location? = null
    private const val SIGN_HOLD_M = 2_000f
    /** The map's limit where the car last was, for when a sign's limit ends. */
    @Volatile private var roadKmh: Int? = null

    private val _limit = MutableStateFlow<Int?>(null)
    val limit: StateFlow<Int?> = _limit

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        users++
        if (job?.isActive == true) return
        job = scope.launch {
            var kmh = 0
            launch { carSpeedKmh().collect { kmh = it } }
            LocationFeed.location.collect { loc -> if (loc != null) onLocation(app, loc, kmh) }
        }
    }

    /** Road watch read [kmh] off a sign here: it is the limit until [SIGN_HOLD_M] further on, the next sign, or the sign that ends it. */
    fun sign(kmh: Int) {
        signKmh = kmh
        signAt = LocationFeed.location.value
        if (users > 0) _limit.value = kmh
    }

    /** Road watch passed the sign that ends the limit: the map's limit again, from here. */
    fun endSign() {
        signKmh = null
        signAt = null
        if (users > 0) _limit.value = roadKmh
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
        // Nobody reads the road any more: a kept limit would go stale under the speed colours.
        _limit.value = null
    }

    private suspend fun onLocation(context: Context, loc: Location, kmh: Int) {
        val last = fetchedAt
        val resting = failedAt != 0L && android.os.SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS
        if (!fetching && !resting && (last == null || last.distanceTo(loc) > REFETCH_M)) {
            fetching = true
            // Fetched beside the fixes, not in their way: every Overpass server tried
            // in turn on a dead link is a minute and more, during which the limit of
            // the roads already read must still follow the car.
            scope.launch(Dispatchers.IO) {
                val got = runCatching { fetch(loc.latitude, loc.longitude) }
                got.onSuccess { roads = it; fetchedAt = loc; failedAt = 0L }.onFailure {
                    failedAt = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "limits unread: ${it.message}")
                }
                fetching = false
            }
        }
        val road = roadAt(loc.latitude, loc.longitude, if (loc.hasBearing() && loc.speed > 2f) loc.bearing else null, roads)
        roadKmh = road?.limitKmh
        val sign = signKmh?.takeIf { signAt?.let { at -> at.distanceTo(loc) < SIGN_HOLD_M } ?: true }
        if (sign == null) signKmh = null
        val limit = sign ?: road?.limitKmh
        _limit.value = limit
        // A clock that only goes forward: GPS sets the unit's time mid-drive, which would say the limit at once or never again.
        if (nag.step(limit, kmh, SystemClock.elapsedRealtime()) && limit != null && SpokenEvents.isOn(context, SpokenEvent.SPEED_LIMIT)) {
            val app = AppLanguage.wrap(context)
            CarVoice.announce(app.getString(R.string.widgets_limit_say, Units.current.value.speed(limit)), InAppNav.locale(context))
        }
    }

    private fun fetch(lat: Double, lng: Double): List<LimitedRoad> {
        val query = "[out:json][timeout:25];way(around:$RADIUS_M,${Overpass.coord(lat)},${Overpass.coord(lng)})[highway][maxspeed];out tags geom;"
        val elements = Overpass.query(query).optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.optJSONObject(i) ?: return@mapNotNull null
            val limit = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed")) ?: return@mapNotNull null
            val geom = e.optJSONArray("geometry") ?: return@mapNotNull null
            val pts = (0 until geom.length()).mapNotNull { j -> geom.optJSONObject(j)?.let { it.optDouble("lat") to it.optDouble("lon") } }
            if (pts.size < 2) null else LimitedRoad(limit, pts)
        }
    }
}

@Composable
internal fun SpeedLimitCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedLimit.start(context); onDispose { SpeedLimit.stop() } }
    val limit by SpeedLimit.limit.collectAsState()
    val down by Overpass.down.collectAsState()
    LaunchedEffect(Unit) { SpokenEvents.load(context) }
    val spoken by SpokenEvents.on.collectAsState()
    val speak = SpokenEvent.SPEED_LIMIT in spoken
    val units by Units.current.collectAsState()
    val kmh by remember { carSpeedKmh() }.collectAsState(0)
    val over = limit != null && kmh > limit!! + SpeedLimit.TOLERANCE_KMH
    Card(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Md)) {
            // A round road sign: red ring, the number in black on white.
            Box(
                modifier = Modifier.fillMaxHeight().aspectRatio(1f).padding(DashSpace.Xs)
                    .background(Color.White, CircleShape)
                    .border(8.dp, Color(0xFFD32F2F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    limit?.let { units.speed(it).toString() } ?: "--",
                    color = Color.Black, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            limit == null && down -> R.string.widgets_osm_down
                            limit == null -> R.string.widgets_limit_unknown
                            over -> R.string.widgets_limit_over
                            else -> R.string.widgets_limit_ok
                        }
                    ),
                    color = if (over) DashColors.Critical else DashColors.TextSecondary, fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = { SpokenEvents.set(context, SpokenEvent.SPEED_LIMIT, !speak) }, modifier = Modifier.size(DashSize.Touch)) {
                Icon(if (speak) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = stringResource(R.string.widgets_limit_speak), tint = if (speak) DashColors.Accent else DashColors.Muted)
            }
        }
    }
}

@Composable
internal fun speedLimitFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { SpeedLimit.start(context); onDispose { SpeedLimit.stop() } }
    val limit by SpeedLimit.limit.collectAsState()
    val down by Overpass.down.collectAsState()
    val units by Units.current.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.SPEED_LIMIT.label,
        // The Road sign design draws the red ring for it (its else branch is a fuel pump).
        sign = SignKind.SPEED,
        value = limit?.let { units.speed(it).toString() } ?: "--",
        unit = units.speedUnit,
        caption = stringResource(if (limit != null) R.string.widgets_limit_ok else if (down) R.string.widgets_osm_down else R.string.widgets_limit_unknown)
    )
}
