package com.openauto.dash

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsRoute
import java.util.Date
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The route Maps or Waze is most likely following, worked out here for the
 * second screen's own map, which those apps never give their route to. When
 * guidance was started from Dashwheel (a favourite, the agenda, the phone, a
 * voice request), the destination is known: the same free router as the
 * in-app navigation draws a route from the car to it, the car is followed
 * along it, and a new route is asked for when the car leaves it, so the line
 * follows the road the navigation app actually took. The next turn on that
 * route stands in when the navigation app's own turn can't be read.
 *
 * Only while the navigation app runs, only while a second screen is linked,
 * and never while the in-app navigation guides (it has the real route). A
 * destination typed into Maps is read off its route preview ([MapsScreen]);
 * one typed into Waze is read from Waze's own files with root
 * ([WazeDestination]), else off its preview like Maps.
 */
internal object ShadowRoute {
    private const val TAG = "ShadowRoute"
    /** The car this far from the line, for this long, is on another road: a new route. */
    private const val OFF_ROUTE_M = 60.0
    private const val OFF_ROUTE_MS = 8_000L
    private const val REROUTE_GAP_MS = 20_000L
    /** Offline, the gap grows to this: one request every few minutes instead of three a minute. */
    private const val REROUTE_GAP_MAX_MS = 3 * 60_000L
    /** This close to the destination the trip is over. */
    private const val ARRIVED_M = 60.0
    /** A handed-off destination still counts this long without the navigation app running (it is starting). */
    internal const val HANDOFF_FRESH_MS = 3 * 60_000L

    /** The route as (lat, lon) pairs; empty without one. */
    private val _route = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val route: StateFlow<List<Pair<Double, Double>>> = _route

    /** The next turn on the shadow route, with the arrival line; null without a route. */
    private val _turn = MutableStateFlow<NavState?>(null)
    val turn: StateFlow<NavState?> = _turn

    internal class Plan(val progress: RouteProgress, val steps: List<Step>, val durationS: Double)

    /** One manoeuvre of the route: where it is along the route (metres from its start), and its words. */
    internal data class Step(val atM: Double, val instruction: String, val type: String?, val modifier: String?)

    private var activeTarget: NavHandoff.Target? = null

    fun start(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        // Off the main thread: the car is matched to the whole route at every fix.
        scope.launch(Dispatchers.Default) {
            combine(DisplayLink.state, NavHandoff.handedOff, NavDirections.running, InAppNav.guidance) { link, target, running, guidance ->
                activeTarget = armed(activeTarget, target, running != null, System.currentTimeMillis())
                val wanted = link is DisplayLinkState.Connected && guidance == null && running != null
                if (wanted) activeTarget else null
            }
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target == null) clear() else follow(app, target)
                }
        }
    }

    /**
     * Which destination the shadow follows: a destination just handed off takes
     * over; it is dropped once the navigation app has been gone for a while
     * (the trip ended, and the next one may be typed into the app itself).
     */
    internal fun armed(current: NavHandoff.Target?, target: NavHandoff.Target?, running: Boolean, nowMs: Long): NavHandoff.Target? {
        if (target != null && target != current && nowMs - target.at < HANDOFF_FRESH_MS) return target
        if (current != null && !running && nowMs - current.at > HANDOFF_FRESH_MS) return null
        return current
    }

    private suspend fun follow(context: Context, target: NavHandoff.Target) = coroutineScope {
        var plan: Plan? = null
        var lastRequest = 0L
        var failures = 0
        var offSince = 0L
        // The route request runs beside the fixes, not inside their collector: a
        // fix a second cancelled a request inside collectLatest before any router
        // answered, so a slow answer never landed. Its answer is taken at the next fix.
        var request: Deferred<Plan?>? = null
        val dest = resolve(target) ?: run {
            Log.i(TAG, "no position for ${target.name}: no shadow route")
            return@coroutineScope
        }
        LocationFeed.location.filterNotNull().collect { here ->
            val now = System.currentTimeMillis()
            request?.takeIf { it.isCompleted }?.let { done ->
                request = null
                val fresh = runCatching { done.getCompleted() }.getOrNull()
                failures = if (fresh == null) failures + 1 else 0
                if (fresh != null) {
                    Log.i(TAG, (if (plan == null) "route" else "new route") + " to ${target.name}: ${fresh.progress.lengthM.toInt()} m, ${fresh.steps.size} turns")
                    plan = fresh
                    offSince = 0L
                    _route.value = fresh.progress.points
                }
            }
            val current = plan
            val fix = current?.progress?.locate(here.latitude, here.longitude)
            val off = fix != null && fix.offM > OFF_ROUTE_M
            if (off) { if (offSince == 0L) offSince = now } else offSince = 0L
            val stale = off && now - offSince > OFF_ROUTE_MS
            if ((current == null || stale) && request == null && now - lastRequest > Backoff.doubling(REROUTE_GAP_MS, REROUTE_GAP_MAX_MS, failures)) {
                lastRequest = now
                request = async(Dispatchers.IO) { runCatching { plan(context, here, dest) }.onFailure { Log.w(TAG, "no route: ${it.message}") }.getOrNull() }
            }
            val p = plan ?: return@collect
            val at = p.progress.locate(here.latitude, here.longitude)
            if (p.progress.lengthM - at.alongM < ARRIVED_M && at.offM < OFF_ROUTE_M) {
                Log.i(TAG, "arrived at ${target.name}")
                request?.cancel()
                activeTarget = null
                clear()
                return@collect
            }
            _turn.value = turnAt(context, p, at)
        }
    }

    /** The destination's position: given, or looked up from its words. */
    private suspend fun resolve(target: NavHandoff.Target): Point? = withContext(Dispatchers.IO) {
        val lat = target.lat
        val lng = target.lng
        if (lat != null && lng != null) return@withContext Point.fromLngLat(lng, lat)
        val query = target.query ?: return@withContext null
        val here = LocationFeed.location.value
        runCatching { PlaceSearch.find(query, here?.latitude, here?.longitude) }.getOrNull()?.let { Point.fromLngLat(it.lng, it.lat) }
    }

    private fun plan(context: Context, here: Location, dest: Point): Plan? {
        val route = valhallaRoute(Point.fromLngLat(here.longitude, here.latitude), dest, InAppNav.locale(context).language).routes.firstOrNull() ?: return null
        return planOf(route)
    }

    internal fun planOf(route: DirectionsRoute): Plan? {
        val points = route.geometry?.let { Polyline.decode(it, 6) } ?: return null
        if (points.size < 2) return null
        val progress = RouteProgress(points)
        // Each step's manoeuvre sits where the step before it ends.
        val steps = ArrayList<Step>()
        var along = 0.0
        route.legs.flatMap { it.steps }.forEach { step ->
            steps += Step(along, step.maneuver.instruction.orEmpty(), step.maneuver.type?.text, step.maneuver.modifier?.text)
            along += step.distance
        }
        return Plan(progress, steps, route.duration)
    }

    /** The next manoeuvre ahead of [at], as the Directions widget would show it. */
    private fun turnAt(context: Context, plan: Plan, at: RouteProgress.Fix): NavState? {
        // The first manoeuvre still ahead, skipping the departure one.
        val next = plan.steps.firstOrNull { it.atM > at.alongM + 1.0 && it.type != "depart" } ?: return null
        val units = Units.current.value
        val leftM = (plan.progress.lengthM - at.alongM).coerceAtLeast(0.0)
        val leftS = if (plan.progress.lengthM > 0) plan.durationS * leftM / plan.progress.lengthM else 0.0
        val mins = (leftS / 60.0).toInt()
        val res = AppLanguage.wrap(context).resources
        val time = if (mins >= 60) res.getString(R.string.info_map_duration_hm, mins / 60, mins % 60) else res.getString(R.string.info_map_duration_min, mins)
        val arrival = units.time(Date(System.currentTimeMillis() + (leftS * 1000).toLong()))
        return NavState(
            active = true,
            instruction = next.instruction,
            distance = units.shortDistance(next.atM - at.alongM),
            eta = "$time · ${units.shortDistance(leftM)} · $arrival",
            packageName = NavDirections.running.value.orEmpty(),
            maneuver = Maneuvers.fromOsrm(next.type, next.modifier)
        )
    }

    private fun clear() {
        _route.value = emptyList()
        _turn.value = null
    }
}

/**
 * Where along a route (lat, lon points) a position is: the distance from the
 * route's start to the nearest point of the line, and how far off the line
 * the position is. Flat-earth metres, fine over a road's width.
 */
internal class RouteProgress(val points: List<Pair<Double, Double>>) {
    /** Metres along the route to the nearest point of it, and metres from the line. */
    data class Fix(val alongM: Double, val offM: Double)

    private val kLat = 111_320.0
    private val kLon = 111_320.0 * cos(Math.toRadians(points.firstOrNull()?.first ?: 0.0))
    private val xs = DoubleArray(points.size) { points[it].second * kLon }
    private val ys = DoubleArray(points.size) { points[it].first * kLat }
    private val cum = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val lengthM: Double get() = cum.lastOrNull() ?: 0.0

    fun locate(lat: Double, lon: Double): Fix {
        val px = lon * kLon
        val py = lat * kLat
        var bestOff = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 1 until points.size) {
            val ax = xs[i - 1]
            val ay = ys[i - 1]
            val dx = xs[i] - ax
            val dy = ys[i] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val off = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            if (off < bestOff) {
                bestOff = off
                bestAlong = cum[i - 1] + t * sqrt(len2)
            }
        }
        return Fix(bestAlong, if (bestOff == Double.MAX_VALUE) 0.0 else bestOff)
    }
}
