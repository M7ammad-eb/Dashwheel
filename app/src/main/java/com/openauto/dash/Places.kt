package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.gson.JsonParser
import com.openauto.dash.link.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/*
 * The places the driver goes to: home, work and the last destinations, so a
 * destination is one tap or one steering wheel button away instead of typed
 * each time. Guidance itself is the navigation app's ([NavHandoff]).
 */

/** Somewhere to be guided to. [name] is what the driver typed, or the address found for a spot on the map. */
internal data class Place(val name: String, val lat: Double, val lng: Double) {
    /** The same spot, give or take the width of a street. */
    fun sameSpot(other: Place): Boolean = distanceM(other.lat, other.lng) < SAME_SPOT_M

    fun distanceM(toLat: Double, toLng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(toLat - lat)
        val dLng = Math.toRadians(toLng - lng)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(toLat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        const val SAME_SPOT_M = 150.0
    }
}

internal data class Places(val home: Place? = null, val work: Place? = null, val recent: List<Place> = emptyList()) {

    /**
     * With [place] as the latest destination: first of the list, once, and
     * not at all when it is home or work, which have their own buttons.
     */
    fun visited(place: Place): Places {
        if (home?.sameSpot(place) == true || work?.sameSpot(place) == true) return this
        return copy(recent = (listOf(place) + recent.filterNot { it.sameSpot(place) }).take(MAX_RECENT))
    }

    companion object {
        const val MAX_RECENT = 6
    }
}

internal object PlacesStore {
    private const val PREFS = "places"
    private const val KEY = "places"

    private val _places = MutableStateFlow(Places())
    val places: StateFlow<Places> = _places.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { _places.value = decode(raw) }
    }

    fun setHome(context: Context, place: Place?) = update(context) { it.copy(home = place) }

    fun setWork(context: Context, place: Place?) = update(context) { it.copy(work = place) }

    /** Guidance was started to [place]: it heads the last destinations. */
    fun visited(context: Context, place: Place) = update(context) { it.visited(place) }

    fun forgetRecent(context: Context) = update(context) { it.copy(recent = emptyList()) }

    @Synchronized
    private fun update(context: Context, change: (Places) -> Places) {
        load(context)
        val next = change(_places.value)
        if (next == _places.value) return
        _places.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(next)).apply()
    }

    internal fun encode(p: Places): String = JSONObject().apply {
        p.home?.let { put("home", json(it)) }
        p.work?.let { put("work", json(it)) }
        put("recent", JSONArray().apply { p.recent.forEach { put(json(it)) } })
    }.toString()

    internal fun decode(raw: String): Places {
        val o = JSONObject(raw)
        val recent = o.optJSONArray("recent")
        return Places(
            home = o.optJSONObject("home")?.let(::place),
            work = o.optJSONObject("work")?.let(::place),
            recent = if (recent == null) emptyList() else (0 until recent.length()).mapNotNull { i -> recent.optJSONObject(i)?.let(::place) }
        )
    }

    private fun json(p: Place) = JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng)

    private fun place(o: JSONObject): Place? {
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return Place(o.optString("name"), lat, lng)
    }
}

/** Addresses to positions and back, from OpenStreetMap's Nominatim (free, no key). Call off the main thread. */
internal object PlaceSearch {
    private const val SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    private const val REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
    // Nominatim's policy asks for a way to reach the app's author: the same name as for Overpass.
    private const val USER_AGENT = OVERPASS_AGENT

    /**
     * The first place found for [query], under the name typed; null when nothing
     * matches. Throws when offline. With [nearLat]/[nearLng], places around there
     * come first (a shop's name alone would otherwise find the one in another town).
     */
    fun find(query: String, nearLat: Double? = null, nearLng: Double? = null): Place? {
        val around = if (nearLat != null && nearLng != null) {
            String.format(java.util.Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=0", nearLng - 1.0, nearLat + 0.7, nearLng + 1.0, nearLat - 0.7)
        } else ""
        val url = "$SEARCH_URL?format=json&limit=1&q=" + URLEncoder.encode(query, "UTF-8") + around
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JsonParser.parseString(resp.body?.string() ?: return null).asJsonArray
            if (arr.size() == 0) return null
            val o = arr[0].asJsonObject
            // An answer without a position is "not found", not "offline" (a throw here reads as the latter).
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return null
            return Place(query, lat, lon)
        }
    }

    /** "12 Rue de Rivoli, Paris" for a spot picked on the map; null when it has no address or offline. */
    fun nameOf(lat: Double, lng: Double): String? = runCatching {
        val url = String.format(Locale.US, "%s?format=json&zoom=18&lat=%.6f&lon=%.6f", REVERSE_URL, lat, lng)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            shortAddress(resp.body?.string() ?: return null)
        }
    }.getOrNull()

    /** House number and street, then the town, out of Nominatim's answer: short enough for a button. */
    internal fun shortAddress(json: String): String? {
        val a = JSONObject(json).optJSONObject("address") ?: return null
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).trim().takeIf { it.isNotEmpty() } }
        val street = first("road", "pedestrian", "footway", "square", "hamlet")
        val number = first("house_number")
        val town = first("city", "town", "village", "municipality", "county")
        val line = listOfNotNull(number, street).joinToString(" ")
        return listOf(line, town.orEmpty()).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** What to call a spot with no address: its position, as a map app writes it. */
    fun coordinates(lat: Double, lng: Double): String = String.format(Locale.US, "%.4f, %.4f", lat, lng)
}

/**
 * Hands a destination to the navigation app, which does the guidance: the app
 * the driver uses (the one the unit's NAVI key opens, on the QF firmware), else
 * Google Maps, else Waze, else whatever map app answers. Free intents, no key.
 */
internal object NavHandoff {
    const val MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    /** Which of the two is asked first: [preferred] when it is one of them and installed, else Maps, else Waze. */
    internal fun order(preferred: String?, mapsInstalled: Boolean, wazeInstalled: Boolean): List<String> {
        val installed = listOfNotNull(MAPS.takeIf { mapsInstalled }, WAZE.takeIf { wazeInstalled })
        return installed.sortedByDescending { it == preferred }
    }

    /**
     * Where guidance was last started from here, and when, for "On my way"
     * ([OnMyWay]): the navigation app's notification says the next turn and
     * the arrival time, never the destination.
     */
    @Volatile var destination: Pair<String, Long>? = null
        private set

    /** Where guidance was last started to, for the second screen's shadow route ([ShadowRoute]). */
    data class Target(val name: String, val lat: Double?, val lng: Double?, val query: String?, val at: Long, val fromApp: Boolean = false)

    private val _handedOff = kotlinx.coroutines.flow.MutableStateFlow<Target?>(null)
    val handedOff: kotlinx.coroutines.flow.StateFlow<Target?> = _handedOff

    /**
     * A destination read off the navigation app's own screen ([MapsScreen]):
     * not started from here, but known all the same, so the second screen's
     * shadow route can follow it. Looked up by its words near the car.
     */
    fun noticed(name: String) {
        if (name.isBlank()) return
        val current = _handedOff.value
        if (current?.query == name && System.currentTimeMillis() - current.at < 10 * 60_000L) return
        // The app's own records ([WazeDestination]) know better than words off its screen.
        if (current?.fromApp == true && NavDirections.running.value != null) return
        _handedOff.value = Target(name, null, null, name, System.currentTimeMillis())
    }

    /** A destination read from the navigation app's own records ([WazeDestination]), with its exact position. */
    fun fromApp(name: String, lat: Double, lng: Double) {
        _handedOff.value = Target(name, lat, lng, null, System.currentTimeMillis(), fromApp = true)
    }

    private fun heading(to: String, lat: Double? = null, lng: Double? = null, query: String? = null) {
        if (to.isNotBlank()) destination = to to System.currentTimeMillis()
        _handedOff.value = Target(to.ifBlank { query.orEmpty() }, lat, lng, query, System.currentTimeMillis())
    }

    /**
     * The navigation app the driver uses: the one the unit's NAVI key opens when
     * it is Maps or Waze, else Maps, else Waze; null with neither installed.
     */
    fun preferredApp(context: Context): String? =
        order(KeyTargets.targets.value[KeyTargets.Key.NAVI], isPackageInstalled(context, MAPS), isPackageInstalled(context, WAZE)).firstOrNull()

    /** Starts guidance to ([lat], [lng]); false when no app on the unit can. */
    fun start(context: Context, lat: Double, lng: Double, label: String = ""): Boolean {
        heading(label, lat, lng)
        InAppNav.handOver()
        for (app in apps(context)) {
            val uri = if (app == WAZE) String.format(Locale.US, "waze://?ll=%.6f,%.6f&navigate=yes", lat, lng)
            else String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lng)
            if (open(context, app, uri)) return true
        }
        val geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lng, lat, lng, Uri.encode(label)))
        return context.launchSafely(Intent(Intent.ACTION_VIEW, geo))
    }

    /** Guidance to [place], which then heads the last destinations. */
    fun start(context: Context, place: Place): Boolean {
        val started = start(context, place.lat, place.lng, place.name)
        if (started) PlacesStore.visited(context, place)
        return started
    }

    /**
     * Guidance to what [query] names (an address, a place's name), which the
     * navigation app looks up itself; false when no app on the unit can.
     */
    fun startQuery(context: Context, query: String): Boolean {
        heading(query, query = query)
        InAppNav.handOver()
        for (app in apps(context)) {
            if (open(context, app, queryUri(app, query))) return true
        }
        return context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(queryUri(null, query))))
    }

    /**
     * Guidance in Google Maps through a stop at ([lat], [lng]) and then on to
     * [to], the destination under way; false when Maps isn't installed, the
     * destination has neither position nor words, or Maps refused the link.
     * Maps' own directions link ([viaUri]): Waze has no way to be given a stop.
     */
    fun startVia(context: Context, lat: Double, lng: Double, to: Target): Boolean {
        val uri = viaUri(lat, lng, to) ?: return false
        if (MAPS !in apps(context)) return false
        return open(context, MAPS, uri)
    }

    /** Maps' directions link to [to] with one stop at ([lat], [lng]), guidance started at once; null when [to] can't be named. */
    internal fun viaUri(lat: Double, lng: Double, to: Target): String? {
        val toLat = to.lat
        val toLng = to.lng
        val dest = if (toLat != null && toLng != null) String.format(Locale.US, "%.6f,%.6f", toLat, toLng)
        else to.query?.takeIf { it.isNotBlank() } ?: to.name.takeIf { it.isNotBlank() } ?: return null
        fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
        return "https://www.google.com/maps/dir/?api=1&destination=${enc(dest)}" +
            "&waypoints=${enc(String.format(Locale.US, "%.6f,%.6f", lat, lng))}&travelmode=driving&dir_action=navigate"
    }

    /** How [app] (null: any map app) is asked to guide to [query]. */
    internal fun queryUri(app: String?, query: String): String {
        // Uri.encode's form (%20, not +): each app reads it back the same way.
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return when (app) {
            WAZE -> "waze://?q=$q&navigate=yes"
            MAPS -> "google.navigation:q=$q&mode=d"
            else -> "geo:0,0?q=$q"
        }
    }

    /**
     * Guidance to [name] and says so, as a steering wheel button would: to its
     * position when known (it then heads the last destinations), else to
     * [query] looked up by the navigation app.
     */
    fun go(context: Context, name: String, lat: Double?, lng: Double?, query: String?) {
        val started = when {
            lat != null && lng != null -> start(context, Place(name.ifBlank { PlaceSearch.coordinates(lat, lng) }, lat, lng))
            !query.isNullOrBlank() -> startQuery(context, query)
            else -> return
        }
        val spoken = name.ifBlank { query.orEmpty() }.ifBlank { if (lat != null && lng != null) PlaceSearch.coordinates(lat, lng) else "" }
        if (started) HandsFree.say(context, R.string.phone_guidance_to, spoken) else HandsFree.say(context, R.string.places_no_nav_app)
    }

    /**
     * A place shared on the phone ([Destination]): guidance starts at once, even
     * on the move (the passenger sent it), and only a spoken line confirms it.
     */
    fun fromPhone(context: Context, destination: Destination) =
        go(context, destination.label, destination.lat, destination.lng, destination.query)

    /**
     * Guidance [uri] in [app]: inside its dashboard tile when it runs in one,
     * so the map stays where the driver put it, else opened as usual.
     */
    private fun open(context: Context, app: String, uri: String): Boolean =
        EmbeddedApp.openInside(app, Intent(Intent.ACTION_VIEW, Uri.parse(uri))) ||
            openInWindow(context, app, uri) ||
            context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app))

    /**
     * [app] docked in a window tile ([PipAnchor]): guidance goes to that
     * window, same place and size. This ROM may still open it full screen:
     * it is then put back into its window by the window manager ([backInWindow]).
     * Never by pressing Back, which Maps reads as "quit the navigation?".
     */
    private fun openInWindow(context: Context, app: String, uri: String): Boolean {
        val status = PipAnchor.statusOf(app).value
        val at = status.windowBounds?.takeIf { status.docked } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchBounds(Rect(at.left, at.top, at.right, at.bottom))
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(options, WINDOWING_MODE_FREEFORM)
        }
        if (runCatching { context.startActivity(intent, options.toBundle()) }.isFailure) return false
        windowScope.launch { backInWindow(context.applicationContext, app, at) }
        return true
    }

    /** For a few seconds after [openInWindow]: [app] found full screen in front goes back into its window at [at]. */
    private suspend fun backInWindow(context: Context, app: String, at: ScreenRect) {
        repeat(WINDOW_CHECKS) {
            delay(WINDOW_CHECK_MS)
            DockShell.forgetListing()
            val listing = runCatching { DockShell.listStacks(context) }.getOrNull() ?: return
            WindowListing.fullscreenInFront(listing, app, context.packageName) ?: return@repeat
            // Android 10 can't switch a task's mode by command; started again as a
            // window, the running task moves into one, guidance and all (the way the
            // window tile brings it back).
            Log.i("NavHandoff", "$app came up full screen: back into its window")
            SplitLauncher.launchFreeform(context, app, Rect(at.left, at.top, at.right, at.bottom))
            PipAnchor.pollAgainSoon()
            return
        }
    }

    private val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val WINDOW_CHECKS = 10
    private const val WINDOW_CHECK_MS = 300L

    /** WindowConfiguration.WINDOWING_MODE_FREEFORM (@hide). */
    private const val WINDOWING_MODE_FREEFORM = 5

    /**
     * The navigation apps to ask, in order, noting that the driver asked. One
     * running inside a dashboard tile goes first: that's the map on screen.
     */
    private fun apps(context: Context): List<String> {
        KeyTargets.refresh()
        val preferred = KeyTargets.targets.value[KeyTargets.Key.NAVI]
        // The driver asked for it: the app opening full screen is not the unit's doing (EmbeddedApp, PipAnchor).
        EmbeddedApp.userActed()
        PipAnchor.noteUserTouch()
        val apps = order(preferred, isPackageInstalled(context, MAPS), isPackageInstalled(context, WAZE))
        return apps.sortedByDescending { EmbeddedApp.holds(it) }
    }
}
