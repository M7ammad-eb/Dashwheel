package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale

/**
 * A weather warning in force somewhere: [level] 1 yellow, 2 orange, 3 red;
 * [hazard] MeteoAlarm's type in lower case ("thunderstorm", "rain-flood"),
 * [event] its English wording, for a type this build doesn't name.
 */
internal data class WeatherWarning(val area: String, val hazard: String, val event: String, val level: Int, val onset: Long, val expires: Long)

internal object WeatherWarnings {
    private val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val titleForm = Regex("^(Yellow|Orange|Red) (.+?) Warning", RegexOption.IGNORE_CASE)

    // One pattern per tag name, compiled once: tag() runs several times for each of hundreds of entries per refresh.
    private val tagPatterns = HashMap<String, Regex>()

    private fun tag(xml: String, name: String): String? =
        synchronized(tagPatterns) { tagPatterns.getOrPut(name) { Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL) } }
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun time(text: String?): Long = text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    /** MeteoAlarm's Atom feed: the warnings above green, past ones left out. */
    fun parse(xml: String, now: Long): List<WeatherWarning> = entry.findAll(xml).mapNotNull { m ->
        val e = m.groupValues[1]
        // "Orange Thunderstorm Warning issued for France - Drôme": the colour is the level.
        val title = titleForm.find(tag(e, "title").orEmpty())
        val level = when (title?.groupValues?.get(1)?.lowercase() ?: tag(e, "cap:severity")?.lowercase()) {
            "yellow", "moderate" -> 1
            "orange", "severe" -> 2
            "red", "extreme" -> 3
            else -> return@mapNotNull null
        }
        val area = tag(e, "cap:areaDesc") ?: return@mapNotNull null
        val expires = time(tag(e, "cap:expires"))
        if (expires in 1 until now) return@mapNotNull null
        val event = tag(e, "cap:event") ?: tag(e, "title") ?: ""
        WeatherWarning(area, title?.groupValues?.get(2)?.lowercase(Locale.ROOT).orEmpty(), event, level, time(tag(e, "cap:onset")), expires)
    }.toList()

    /** "Drôme" and "drome", "Landkreis Kassel" and "Kassel" are the same place. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Words areas carry in one list and not the other ("Landkreis" here, "Kreis" there). */
    private val generic = setOf("landkreis", "kreis", "stadt", "kreisfreie", "provincia", "province", "provincie", "departement", "de", "du", "di", "del")

    private fun key(s: String): String = normalize(s).split(' ').filter { it !in generic }.joinToString(" ")

    fun inArea(warning: WeatherWarning, places: Collection<String>): Boolean {
        val a = key(warning.area)
        return a.isNotEmpty() && places.any { key(it) == a }
    }

    /** MeteoAlarm's feed name for a country (its ISO code), null for one it does not cover. */
    fun feedFor(countryCode: String): String? = mapOf(
        "fr" to "france", "be" to "belgium", "de" to "germany", "es" to "spain", "it" to "italy", "pt" to "portugal",
        "nl" to "netherlands", "lu" to "luxembourg", "ch" to "switzerland", "at" to "austria", "pl" to "poland",
        "ie" to "ireland", "dk" to "denmark", "cz" to "czechia", "si" to "slovenia", "hr" to "croatia", "gr" to "greece"
    )[countryCode.lowercase(Locale.ROOT)]
}

/**
 * Weather warnings (MeteoAlarm, which carries Météo-France's vigilance) where
 * the car is and where the guidance is taking it. Read every [REFRESH_MS];
 * a new orange or red one is said once ([SpokenEvent.WEATHER]), and the ones
 * said are kept until they expire, so a restart doesn't say them again.
 */
internal object WeatherAlerts {
    private const val TAG = "WeatherAlerts"
    private const val PREFS = "weather_alerts"
    private const val KEY_SAID = "said"
    /** A warning with no end given is remembered this long. */
    private const val SAID_DEFAULT_MS = 24 * 3_600_000L
    private const val REFRESH_MS = 30 * 60_000L
    /** After a read that could not be made (no place yet, the geocoder or the feed unreachable), the next try. */
    private const val RETRY_MS = 60_000L
    private const val FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
    private const val REVERSE = "https://nominatim.openstreetmap.org/reverse"

    /** One place looked up: its country and the names its warnings may go by (department, region). */
    private data class Area(val country: String, val names: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0
    /** The warnings said, by key, with when each expires (wall clock). */
    private var said: MutableMap<String, Long>? = null
    private var areaCache: Pair<Pair<Int, Int>, Area>? = null
    private var destCache: Pair<Pair<Int, Int>, Area>? = null

    private val _here = MutableStateFlow<List<WeatherWarning>>(emptyList())
    /** In force where the car is, the worst first. */
    val here: StateFlow<List<WeatherWarning>> = _here
    private val _ahead = MutableStateFlow<List<WeatherWarning>>(emptyList())
    /** In force at the destination being guided to (none without one). */
    val ahead: StateFlow<List<WeatherWarning>> = _ahead

    @Synchronized
    fun start(context: Context) {
        users++
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            var failures = 0
            while (isActive) {
                // At a cold start the first fix comes after the tile: waiting for
                // it, rather than finding none and sleeping half an hour, is what
                // gets a red warning said before the car has left the street.
                LocationFeed.location.filterNotNull().first()
                val read = runCatching { refresh(app) }
                    .onFailure { Log.w(TAG, "warnings unread: ${it.message}") }
                    .getOrDefault(false)
                failures = if (read) 0 else failures + 1
                delay(if (read) REFRESH_MS else Backoff.doubling(RETRY_MS, REFRESH_MS, failures))
            }
        }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
    }

    /** Reads the warnings; false when it could not (no fix, or the place could not be named), to be tried again soon. */
    private fun refresh(context: Context): Boolean {
        val loc = LocationFeed.location.value ?: return false
        val hereArea = area(loc.latitude, loc.longitude, cache = true) ?: return false
        val dest = InAppNav.guidance.value?.destination
        val destArea = dest?.let { destinationArea(it.latitude(), it.longitude()) }
        val feeds = HashMap<String, List<WeatherWarning>>()
        fun warnings(a: Area): List<WeatherWarning> = feeds.getOrPut(a.country) {
            val slug = WeatherWarnings.feedFor(a.country) ?: return@getOrPut emptyList()
            val request = Request.Builder().url(FEED + slug).header("User-Agent", OVERPASS_AGENT).build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                WeatherWarnings.parse(r.body?.string().orEmpty(), System.currentTimeMillis())
            }
        }
        fun worst(list: List<WeatherWarning>) = list.sortedByDescending { it.level }.distinctBy { it.hazard to it.area }
        val now = worst(warnings(hereArea).filter { WeatherWarnings.inArea(it, hereArea.names) })
        _here.value = now
        _ahead.value = destArea?.let { a -> worst(warnings(a).filter { WeatherWarnings.inArea(it, a.names) }) }.orEmpty()
        val fresh = (now + _ahead.value).filter { it.level >= 2 && markSaid(context, w = it) }
        if (fresh.isEmpty() || !SpokenEvents.isOn(context, SpokenEvent.WEATHER)) return true
        val app = AppLanguage.wrap(context)
        fresh.forEach { w ->
            val colour = app.getString(if (w.level >= 3) R.string.widgets_wx_red else R.string.widgets_wx_orange)
            CarVoice.announce(app.getString(R.string.widgets_wx_say, colour, hazardName(app, w), w.area), InAppNav.locale(context))
        }
        return true
    }

    /** The destination's area, looked up once per destination cell rather than at every refresh of a long drive. */
    private fun destinationArea(lat: Double, lng: Double): Area? {
        val key = (lat * 10).toInt() to (lng * 10).toInt()
        destCache?.takeIf { it.first == key }?.let { return it.second }
        return area(lat, lng, cache = false)?.also { destCache = key to it }
    }

    /** Notes [w] as said; false when it already was (and still is in force). Saved, so a restart remembers. */
    @Synchronized
    private fun markSaid(context: Context, w: WeatherWarning): Boolean {
        val nowMs = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = said ?: run {
            val loaded = runCatching { JSONObject(prefs.getString(KEY_SAID, null) ?: "{}") }.getOrDefault(JSONObject())
            loaded.keys().asSequence().associateWithTo(HashMap()) { loaded.optLong(it) }
        }.also { said = it }
        map.entries.removeAll { it.value <= nowMs }
        val key = "${w.area}|${w.event}|${w.level}|${w.onset}"
        if (map.containsKey(key)) return false
        map[key] = if (w.expires > nowMs) w.expires else nowMs + SAID_DEFAULT_MS
        prefs.edit().putString(KEY_SAID, JSONObject(map as Map<*, *>).toString()).apply()
        return true
    }

    /** The country and area names at a point; the car's own is kept while it stays within about 10 km. */
    private fun area(lat: Double, lng: Double, cache: Boolean): Area? {
        val key = (lat * 10).toInt() to (lng * 10).toInt()
        if (cache) areaCache?.takeIf { it.first == key }?.let { return it.second }
        val url = String.format(Locale.US, "%s?format=json&zoom=8&lat=%.4f&lon=%.4f", REVERSE, lat, lng)
        val request = Request.Builder().url(url).header("User-Agent", OVERPASS_AGENT).build()
        val a = Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string() ?: "{}").optJSONObject("address") ?: return null
        }
        val names = listOf("county", "state_district", "state", "region").mapNotNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
        val area = Area(a.optString("country_code"), names)
        if (cache) areaCache = key to area
        return area
    }
}

/** The warning's hazard in the driver's language. */
internal fun hazardName(context: Context, w: WeatherWarning): String {
    val res = when (w.hazard) {
        "wind" -> R.string.widgets_wx_h_wind
        "snow-ice", "snow" -> R.string.widgets_wx_h_snow
        "thunderstorm", "thunderstorms" -> R.string.widgets_wx_h_storm
        "fog" -> R.string.widgets_wx_h_fog
        "high-temperature", "extreme high temperature" -> R.string.widgets_wx_h_heat
        "low-temperature", "extreme low temperature" -> R.string.widgets_wx_h_cold
        "coastal event", "coastalevent" -> R.string.widgets_wx_h_coast
        "forest fire", "forest-fire" -> R.string.widgets_wx_h_fire
        "avalanches", "avalanche" -> R.string.widgets_wx_h_avalanche
        "rain" -> R.string.widgets_wx_h_rain
        "flooding", "flood", "rain-flood" -> R.string.widgets_wx_h_flood
        else -> return w.event
    }
    return context.getString(res)
}

private fun levelColor(level: Int): Color = when (level) {
    3 -> Color(0xFFE53935)
    2 -> Color(0xFFFB8C00)
    else -> Color(0xFFFDD835)
}

@Composable
internal fun WeatherAlertsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { WeatherAlerts.start(context); onDispose { WeatherAlerts.stop() } }
    val here by WeatherAlerts.here.collectAsState()
    val ahead by WeatherAlerts.ahead.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            TileHeader(stringResource(R.string.widgets_wx))
            if (here.isEmpty() && ahead.isEmpty()) {
                Text(stringResource(R.string.widgets_wx_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            here.take(3).forEach { WarningRow(it) }
            if (ahead.isNotEmpty()) {
                Text(stringResource(R.string.widgets_wx_ahead), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                ahead.take(2).forEach { WarningRow(it) }
            }
        }
    }
}

@Composable
private fun WarningRow(w: WeatherWarning) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        Box(Modifier.size(12.dp).background(levelColor(w.level), CircleShape))
        Text(
            hazardName(LocalContext.current, w), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text(w.area, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
internal fun weatherAlertsFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { WeatherAlerts.start(context); onDispose { WeatherAlerts.stop() } }
    val here by WeatherAlerts.here.collectAsState()
    val ahead by WeatherAlerts.ahead.collectAsState()
    val top = (here + ahead).maxByOrNull { it.level }
    return WidgetFace(
        icon = Icons.Filled.Warning,
        title = BuiltinKind.WEATHER_ALERTS.label,
        value = top?.let { hazardName(context, it) } ?: stringResource(R.string.widgets_wx_none),
        textValue = true,
        caption = top?.area ?: "",
        alert = top != null && top.level >= 2,
        rows = (here + ahead).take(4).map { FaceRow(hazardName(context, it), it.area, alert = it.level >= 2) }
    )
}
