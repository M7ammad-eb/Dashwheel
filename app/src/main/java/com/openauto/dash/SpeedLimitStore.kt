package com.openauto.dash

import android.content.Context
import android.location.Geocoder
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The speed limits kept on the unit, for the drives when no Overpass server
 * answers ([SpeedLimit]), the way [KeptCameras] keeps the cameras. A whole
 * country is millions of road pieces, too many to ask the servers for, so
 * two kinds are kept:
 *
 * - the roads read on the drives, each once by its OpenStreetMap id, the
 *   [MAX_DRIVEN] seen most recently. A road gone from a new answer while it
 *   lies wholly inside the circle asked is dropped: it left the map, or lost
 *   its limit;
 * - an **area** the driver asked for in Settings, round where the car stood,
 *   read again when older than [SpeedLimitStore.REFRESH_MS].
 *
 * Points are kept as whole 1e-5 degrees (about a metre) in an [IntArray]:
 * an area round a city is a hundred thousand roads. Pure, so it is unit-tested.
 */
internal class KeptLimits {
    class Road(val id: Long, val limitKmh: Int, val coords: IntArray, val seen: Long) {
        fun toLimited() = LimitedRoad(limitKmh, (0 until coords.size / 2).map { coords[2 * it] / E5 to coords[2 * it + 1] / E5 }, id)
    }
    class Area(val lat: Double, val lng: Double, val radiusKm: Int, val at: Long, val name: String, val roads: List<Road>)

    private val driven = LinkedHashMap<Long, Road>()
    private val areas = ArrayList<Area>()

    val areaList: List<Area> get() = areas.toList()
    val drivenCount: Int get() = driven.size

    /** An answer read round ([lat], [lng]) within [radiusM]: kept, in place of what the unit had there. */
    fun remember(lat: Double, lng: Double, radiusM: Int, roads: List<LimitedRoad>, now: Long) {
        val ids = roads.mapTo(HashSet()) { it.id }
        driven.values.removeAll { it.id !in ids && allWithin(it.coords, lat, lng, radiusM.toDouble()) }
        for (r in roads) {
            if (r.id == 0L) continue
            driven.remove(r.id)
            driven[r.id] = road(r, now)
        }
        // Insertion order is the order seen: the oldest go first.
        val over = driven.size - MAX_DRIVEN
        if (over > 0) driven.keys.take(over).forEach { driven.remove(it) }
    }

    fun keepArea(area: Area) {
        areas.removeAll { it.lat == area.lat && it.lng == area.lng }
        areas.add(area)
    }

    fun dropArea(lat: Double, lng: Double) {
        areas.removeAll { it.lat == lat && it.lng == lng }
    }

    /** The areas read more than [maxAgeMs] before [now], oldest first. */
    fun stale(now: Long, maxAgeMs: Long): List<Area> = areas.filter { now - it.at > maxAgeMs }.sortedBy { it.at }

    /** The kept roads with a point within [radiusM] of ([lat], [lng]): the drives' first, they are newer; each road once. */
    fun near(lat: Double, lng: Double, radiusM: Int): List<LimitedRoad> {
        val out = LinkedHashMap<Long, Road>()
        for (r in driven.values) if (anyWithin(r.coords, lat, lng, radiusM.toDouble())) out[r.id] = r
        for (a in areas) {
            if (metresBetween(a.lat, a.lng, lat, lng) > a.radiusKm * 1_000.0 + radiusM + AREA_EDGE_M) continue
            for (r in a.roads) if (r.id !in out && anyWithin(r.coords, lat, lng, radiusM.toDouble())) out[r.id] = r
        }
        return out.values.map { it.toLimited() }
    }

    fun write(stream: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(stream))
        out.writeInt(MAGIC)
        out.writeInt(areas.size)
        for (a in areas) {
            out.writeDouble(a.lat); out.writeDouble(a.lng); out.writeInt(a.radiusKm); out.writeLong(a.at); out.writeUTF(a.name)
            writeRoads(out, a.roads)
        }
        writeRoads(out, driven.values)
        out.flush()
    }

    companion object {
        /** The drives' roads kept: a few thousand kilometres of driving, a few MB. */
        const val MAX_DRIVEN = 40_000
        /** A road of an area may run this far past its edge: a way is read whole. */
        private const val AREA_EDGE_M = 20_000
        private const val MAGIC = 0x44574C31 // "DWL1"
        private const val E5 = 100_000.0

        fun road(r: LimitedRoad, seen: Long): Road {
            val c = IntArray(r.points.size * 2)
            r.points.forEachIndexed { i, (la, ln) -> c[2 * i] = (la * E5).roundToInt(); c[2 * i + 1] = (ln * E5).roundToInt() }
            return Road(r.id, r.limitKmh, c, seen)
        }

        fun read(stream: InputStream): KeptLimits {
            val input = DataInputStream(BufferedInputStream(stream))
            require(input.readInt() == MAGIC) { "not a speed limit file" }
            val kept = KeptLimits()
            repeat(input.readInt()) {
                val lat = input.readDouble(); val lng = input.readDouble(); val radius = input.readInt(); val at = input.readLong(); val name = input.readUTF()
                kept.areas.add(Area(lat, lng, radius, at, name, readRoads(input)))
            }
            readRoads(input).forEach { kept.driven[it.id] = it }
            return kept
        }

        private fun writeRoads(out: DataOutputStream, roads: Collection<Road>) {
            out.writeInt(roads.size)
            for (r in roads) {
                out.writeLong(r.id); out.writeShort(r.limitKmh); out.writeLong(r.seen); out.writeInt(r.coords.size)
                r.coords.forEach { out.writeInt(it) }
            }
        }

        private fun readRoads(input: DataInputStream): List<Road> = List(input.readInt()) {
            val id = input.readLong(); val limit = input.readShort().toInt(); val seen = input.readLong()
            Road(id, limit, IntArray(input.readInt()) { input.readInt() }, seen)
        }

        private fun anyWithin(c: IntArray, lat: Double, lng: Double, m: Double): Boolean {
            for (i in 0 until c.size / 2) if (metresBetween(c[2 * i] / E5, c[2 * i + 1] / E5, lat, lng) <= m) return true
            return false
        }

        private fun allWithin(c: IntArray, lat: Double, lng: Double, m: Double): Boolean {
            for (i in 0 until c.size / 2) if (metresBetween(c[2 * i] / E5, c[2 * i + 1] / E5, lat, lng) > m) return false
            return true
        }
    }
}

/**
 * The [KeptLimits] on the unit: one file in the app's files, read once, and
 * what Settings shows of it. Reads and writes block: from an IO thread.
 */
internal object SpeedLimitStore {
    private const val TAG = "SpeedLimitStore"
    private const val FILE = "speed_limits.bin"
    /** An area is read again after this long, on a drive with a server answering: limits change slowly. */
    const val REFRESH_MS = 30 * 24 * 3_600_000L
    /** The drives' roads are written at most this often: every kilometre would wear the unit's flash. */
    // shortcut: up to this much driving is lost when the firmware kills the app at ACC off; save on ACC off if it matters.
    private const val SAVE_EVERY_MS = 5 * 60_000L
    /** One Overpass query per square of this many degrees: a city's square is still a few MB. */
    private const val TILE_DEG = 0.1
    val RADII_KM = listOf(10, 25, 50)

    class AreaKept(val lat: Double, val lng: Double, val radiusKm: Int, val name: String, val roads: Int, val at: Long)
    class Summary(val areas: List<AreaKept> = emptyList(), val driven: Int = 0)

    private val lock = Any()
    private var kept: KeptLimits? = null
    private var savedAt = 0L

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary
    private val _progress = MutableStateFlow<Float?>(null)
    /** How far the area being read is, 0 to 1; null when none is. */
    val progress: StateFlow<Float?> = _progress

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun load(context: Context): KeptLimits = synchronized(lock) {
        kept ?: run {
            val f = file(context)
            val read = if (f.exists()) runCatching { f.inputStream().use { KeptLimits.read(it) } }
                .onFailure { Log.w(TAG, "kept limits unread: ${it.message}") }.getOrNull() else null
            (read ?: KeptLimits()).also { kept = it; publish(it) }
        }
    }

    fun warm(context: Context) {
        load(context)
    }

    private fun publish(k: KeptLimits) {
        _summary.value = Summary(k.areaList.map { AreaKept(it.lat, it.lng, it.radiusKm, it.name, it.roads.size, it.at) }, k.drivenCount)
    }

    private fun save(context: Context, k: KeptLimits) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        runCatching {
            tmp.outputStream().use { k.write(it) }
            if (!tmp.renameTo(f)) error("rename failed")
            savedAt = SystemClock.elapsedRealtime()
        }.onFailure { Log.w(TAG, "kept limits unsaved: ${it.message}"); tmp.delete() }
        publish(k)
    }

    fun near(context: Context, lat: Double, lng: Double, radiusM: Int): List<LimitedRoad> =
        synchronized(lock) { load(context).near(lat, lng, radiusM) }

    /** An answer read on the drive, kept for the next time the servers are down. */
    fun remember(context: Context, lat: Double, lng: Double, radiusM: Int, roads: List<LimitedRoad>) {
        synchronized(lock) {
            val k = load(context)
            k.remember(lat, lng, radiusM, roads, System.currentTimeMillis())
            if (savedAt == 0L || SystemClock.elapsedRealtime() - savedAt > SAVE_EVERY_MS) save(context, k) else publish(k)
        }
    }

    /** The Overpass squares ([south, west, north, east]) that cover [radiusKm] round ([lat], [lng]). */
    internal fun tiles(lat: Double, lng: Double, radiusKm: Int): List<DoubleArray> {
        val dLat = radiusKm / 111.32
        val dLng = radiusKm / (111.32 * cos(Math.toRadians(lat)))
        val out = ArrayList<DoubleArray>()
        var s = floor((lat - dLat) / TILE_DEG) * TILE_DEG
        while (s < lat + dLat) {
            var w = floor((lng - dLng) / TILE_DEG) * TILE_DEG
            while (w < lng + dLng) {
                // The square's nearest point to the centre, to leave out the corners outside the circle.
                val nLat = lat.coerceIn(s, s + TILE_DEG); val nLng = lng.coerceIn(w, w + TILE_DEG)
                if (metresBetween(lat, lng, nLat, nLng) <= radiusKm * 1_000.0) out.add(doubleArrayOf(s, w, s + TILE_DEG, w + TILE_DEG))
                w += TILE_DEG
            }
            s += TILE_DEG
        }
        return out
    }

    /**
     * Reads every road with a limit within [radiusKm] of ([lat], [lng]),
     * one square after the other, and keeps them as one area; gives back how
     * many roads. Throws when a square found no server: nothing is kept then.
     * One area at a time.
     */
    fun download(context: Context, lat: Double, lng: Double, radiusKm: Int, name: String? = null): Int {
        synchronized(lock) {
            check(_progress.value == null) { "already reading an area" }
            _progress.value = 0f
        }
        try {
            val squares = tiles(lat, lng, radiusKm)
            val roads = LinkedHashMap<Long, LimitedRoad>()
            squares.forEachIndexed { i, b ->
                val box = b.joinToString(",") { Overpass.coord(it) }
                val query = "[out:json][timeout:120];way[highway][maxspeed]($box);out tags geom;"
                SpeedLimit.parse(Overpass.query(query, patient = true)).forEach { roads[it.id] = it }
                _progress.value = (i + 1f) / squares.size
            }
            val now = System.currentTimeMillis()
            val label = name ?: placeName(context, lat, lng)
            synchronized(lock) {
                val k = load(context)
                k.keepArea(KeptLimits.Area(lat, lng, radiusKm, now, label, roads.values.map { KeptLimits.road(it, now) }))
                save(context, k)
            }
            Log.i(TAG, "$radiusKm km round $label: ${roads.size} roads kept")
            return roads.size
        } finally {
            _progress.value = null
        }
    }

    fun drop(context: Context, lat: Double, lng: Double) {
        synchronized(lock) {
            val k = load(context)
            k.dropArea(lat, lng)
            save(context, k)
        }
    }

    /** Reads again the areas older than [REFRESH_MS]; a failure waits for the next drive. */
    fun refreshStale(context: Context) {
        val stale = synchronized(lock) { load(context).stale(System.currentTimeMillis(), REFRESH_MS) }
        for (a in stale) {
            runCatching { download(context, a.lat, a.lng, a.radiusKm, a.name) }.onFailure { Log.w(TAG, "${a.name} not refreshed: ${it.message}") }
        }
    }

    /** The town at ([lat], [lng]), or the position itself when the unit has no geocoder. */
    @Suppress("DEPRECATION")
    private fun placeName(context: Context, lat: Double, lng: Double): String =
        runCatching { Geocoder(context).getFromLocation(lat, lng, 1)?.firstOrNull()?.locality }.getOrNull()
            ?: String.format(java.util.Locale.US, "%.3f, %.3f", lat, lng)
}

/** The Driving settings row: the areas kept, or what keeping is for. */
@Composable
internal fun KeptLimitsRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val summary by SpeedLimitStore.summary.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SpeedLimitStore.warm(context) } }
    SettingsRow(
        Icons.Filled.Speed, stringResource(R.string.limits_kept_title),
        if (summary.areas.isEmpty()) stringResource(R.string.limits_kept_row_detail)
        else summary.areas.joinToString(", ") { it.name }
    ) { onOpen() }
}

/** Settings → Driving → Speed limits on the unit: an area round the car to keep, and what is kept. */
@Composable
internal fun KeptLimitsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val summary by SpeedLimitStore.summary.collectAsState()
    val progress by SpeedLimitStore.progress.collectAsState()
    val units by Units.current.collectAsState()
    var radius by remember { mutableIntStateOf(SpeedLimitStore.RADII_KM[1]) }
    var problem by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.limits_kept_failed)
    val noFix = stringResource(R.string.limits_kept_no_position)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SpeedLimitStore.warm(context) } }

    fun download() {
        if (progress != null) return
        val at = LocationFeed.location.value
        if (at == null) { problem = noFix; return }
        problem = null
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { SpeedLimitStore.download(context, at.latitude, at.longitude, radius) } }
            if (got.isFailure) problem = failed
        }
    }

    SettingsSheet(
        title = stringResource(R.string.limits_kept_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.limits_kept_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Label(stringResource(R.string.limits_kept_radius))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedLimitStore.RADII_KM.forEach { km ->
                CategoryChip(units.shortDistance(km * 1_000.0), radius == km) { if (progress == null) radius = km }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val p = progress
            SheetButton(
                if (p != null) stringResource(R.string.limits_kept_downloading, (p * 100).roundToInt())
                else stringResource(R.string.limits_kept_download)
            ) { download() }
        }
        problem?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.limits_kept_section))
        if (summary.areas.isEmpty()) {
            Text(stringResource(R.string.limits_kept_no_area), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        for (a in summary.areas) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        units.shortDistance(a.radiusKm * 1_000.0) + ", " +
                            pluralStringResource(R.plurals.limits_kept_roads, a.roads, a.roads) + ", " +
                            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(a.at)),
                        color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { scope.launch(Dispatchers.IO) { SpeedLimitStore.drop(context, a.lat, a.lng) } }) {
                    Text(stringResource(R.string.cams_kept_remove), color = DashColors.Accent)
                }
            }
        }
        Text(
            stringResource(R.string.cams_kept_driven, pluralStringResource(R.plurals.limits_kept_roads, summary.driven, summary.driven)),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
        )
    }
}
