package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.RecentCall
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * What Dashwheel's Gemini ([DashAssistant], [AssistantTools]) does for the
 * driver beyond a wheel button: call someone, text the arrival time, guide
 * home or to work, add a fuel or parking stop, the parking timer, a refuel
 * in the fuel log. Each goes through what the tiles and buttons already use
 * (PhoneLink, OnMyWay, NavHandoff, FuelPriceRepo, ParkingClock, FuelLog).
 *
 * The answers go back to Gemini, which says them in the driver's language:
 * nothing here speaks, so the car's voice never talks over Gemini's.
 * Calls and texts happen only once Gemini has said the person's name and the
 * driver agreed ("confirmed"), and only to a name that matched one person.
 */

/** Someone the phone can call: from its favourites or its last calls. */
internal data class Callee(val name: String, val number: String)

/** The pure side of [AssistantActs]: matching names, the saved places, the arguments. Unit-tested. */
internal object AssistantRules {

    /** A name asked for, against a list: nobody, one, or several to choose from. */
    sealed interface Match<out T> {
        data object None : Match<Nothing>
        data class One<T>(val item: T) : Match<T>
        data class Several<T>(val items: List<T>) : Match<T>
    }

    /** Lower case, no accents or spaces: "Écran voiture" matches "ecranvoiture". */
    fun simple(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS_AND_SPACES, "").lowercase()

    /** The words of [s], simplified: "Marie-Claire Dupont" is marie, claire, dupont. */
    fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .split(NON_WORD).filter { it.isNotEmpty() }

    // Compiled once: match() calls these for every contact and app at each tier.
    private val MARKS_AND_SPACES = Regex("\\p{M}+|\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Who [wanted] names in [items]: the whole name first, then every word
     * said being one of the name's ("Marie" for "Marie Dupont"), then every
     * word of the name said ("Marie Dupont" for "Marie"), then the start of
     * the name's words ("Mari"), then a near miss of the speech recognition
     * ("Mary" for "Marie"). The first of those that finds anyone decides; the
     * same name twice counts once (the first kept).
     */
    fun <T> match(wanted: String, items: List<T>, nameOf: (T) -> String): Match<T> {
        val w = simple(wanted)
        val ws = words(wanted)
        if (w.isEmpty() || ws.isEmpty()) return Match.None
        val named = items.filter { simple(nameOf(it)).isNotEmpty() }
        val tiers: List<(T) -> Boolean> = listOf(
            { simple(nameOf(it)) == w },
            { val n = words(nameOf(it)); ws.all { x -> x in n } },
            { val n = words(nameOf(it)); n.isNotEmpty() && n.all { x -> x in ws } },
            { val n = words(nameOf(it)); ws.all { x -> x.length >= 3 && n.any { y -> y.startsWith(x) } } },
            { item -> (words(nameOf(item)) + simple(nameOf(item))).any { near(it, w) } }
        )
        for (tier in tiers) {
            val found = named.filter(tier).distinctBy { simple(nameOf(it)) }
            when (found.size) {
                0 -> continue
                1 -> return Match.One(found[0])
                else -> return Match.Several(found)
            }
        }
        return Match.None
    }

    /** One or two letters off, for names long enough that this is still the same name. */
    private fun near(a: String, b: String): Boolean {
        val shortest = minOf(a.length, b.length)
        val allowed = when {
            shortest >= 7 -> 2
            shortest >= 4 -> 1
            else -> return false
        }
        return abs(a.length - b.length) <= allowed && edits(a, b) <= allowed
    }

    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** The phone's favourites with a number, then the named people of its last calls, each number once. */
    fun callees(favourites: List<Favourite>?, calls: List<RecentCall>?): List<Callee> =
        (favourites.orEmpty().mapNotNull { f -> f.number?.takeIf { it.isNotBlank() }?.let { Callee(f.name, it) } } +
            calls.orEmpty().mapNotNull { c -> c.name?.takeIf { it.isNotBlank() }?.let { Callee(it, c.number) } })
            .distinctBy { it.number.filter(Char::isDigit).takeLast(9).ifEmpty { it.number } }

    enum class Saved { HOME, WORK }

    // Said as a destination in the launcher's languages, simplified ([simple]).
    private val HOME = listOf(
        "home", "myhome", "house", "gohome", "maison", "alamaison", "chezmoi", "domicile", "zuhause", "nachhause",
        "heim", "daheim", "casa", "micasa", "acasa", "minhacasa", "thuis", "naarhuis", "huis", "dom", "domu",
        "dodomu", "дом", "домой"
    ).map(::simple).toSet()
    private val WORK = listOf(
        "work", "towork", "office", "myoffice", "job", "travail", "autravail", "boulot", "bureau", "arbeit",
        "zurarbeit", "büro", "trabajo", "altrabajo", "oficina", "lavoro", "allavoro", "ufficio", "trabalho",
        "escritório", "werk", "naarwerk", "kantoor", "praca", "pracy", "dopracy", "biuro", "работа", "работу",
        "наработу", "офис"
    ).map(::simple).toSet()

    /** Whether [destination] means the driver's home or work, in any of the launcher's languages. */
    fun savedKind(destination: String): Saved? = when (simple(destination)) {
        in HOME -> Saved.HOME
        in WORK -> Saved.WORK
        else -> null
    }

    /** One of the last destinations by its exact name ("Gym" when it was saved as "gym"); never a near one. */
    fun recentNamed(destination: String, places: Places): Place? {
        val w = simple(destination)
        if (w.isEmpty()) return null
        return places.recent.firstOrNull { simple(it.name) == w }
    }

    /** The longest parking timer the voice may set: a day. */
    const val PARKING_MAX_MIN = 24 * 60

    /** Minutes for the parking timer, or null when they aren't a sensible number. */
    fun parkingMinutes(value: Double): Int? =
        value.takeIf { !it.isNaN() && it >= 1 && it <= PARKING_MAX_MIN }?.let { Math.round(it).toInt() }

    /** A refuel said out loud: the litres, and the price per litre when told, or why it can't be logged. */
    sealed interface Fill {
        data class Ok(val liters: Double, val pricePerL: Double?) : Fill
        data class Bad(val reason: String) : Fill
    }

    /** [total] is the amount paid, used for the price per litre when only it was said. NaN: not said. */
    fun fill(liters: Double, pricePerL: Double, total: Double): Fill {
        if (liters.isNaN() || liters < 1 || liters > 200) return Fill.Bad("the litres must be between 1 and 200")
        val price = when {
            !pricePerL.isNaN() -> pricePerL
            !total.isNaN() -> total / liters
            else -> null
        }
        if (price != null && (price < 0.1 || price > 10)) return Fill.Bad("a price of %.3f per litre can't be right".format(Locale.ROOT, price))
        return Fill.Ok(liters, price)
    }

    /** How long the driver has to say yes once Gemini named who it would call or text. */
    const val CONFIRM_MS = 2 * 60_000L

    /**
     * A call or text goes only to the person Gemini named to the driver: a
     * "confirmed" with no question asked first about that same number (lately)
     * is a question, not a yes.
     */
    fun mayGo(confirmed: Boolean, asked: Pair<String, Long>?, number: String, now: Long): Boolean =
        confirmed && asked != null && asked.first == number && now - asked.second in 0..CONFIRM_MS

    /** How a stop is added: through Maps' own link, by guiding there instead, or asked first. */
    enum class StopWay { VIA_MAPS, GO_THERE, ASK_INSTEAD }

    /**
     * Only Google Maps can be handed a stop on the way, and only with the
     * destination known. Without guidance there is nothing to replace; with
     * it, the route is replaced only once the driver said so ([instead]).
     */
    fun stopWay(fuel: Boolean, ownGuidance: Boolean, runningApp: String?, destinationKnown: Boolean, instead: Boolean): StopWay = when {
        instead -> StopWay.GO_THERE
        !ownGuidance && runningApp == null -> StopWay.GO_THERE
        fuel && !ownGuidance && runningApp == NavHandoff.MAPS && destinationKnown -> StopWay.VIA_MAPS
        else -> StopWay.ASK_INSTEAD
    }

    /**
     * The cheapest of [ranked] (cheapest first) ahead of the car, heading
     * [headingDeg]: a station behind means turning round. Any, when none is
     * ahead or the heading isn't known.
     */
    fun ahead(ranked: List<RankedStation>, lat: Double, lng: Double, headingDeg: Float?): RankedStation? {
        if (headingDeg == null) return ranked.firstOrNull()
        return ranked.firstOrNull { abs(angle(bearing(lat, lng, it.station.lat, it.station.lng) - headingDeg)) <= 90 } ?: ranked.firstOrNull()
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        return Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
    }

    /** [deg] in -180..180. */
    private fun angle(deg: Double): Double = ((deg % 360) + 540) % 360 - 180
}

/** Running the tools of [AssistantTools] that act for the driver. Main thread; each returns what Gemini is told. */
internal object AssistantActs {
    private const val STATIONS_WAIT_MS = 10_000L

    /** The number Gemini last named to the driver for a call, and for a text, and when ([AssistantRules.mayGo]). */
    private var callAsked: Pair<String, Long>? = null
    private var textAsked: Pair<String, Long>? = null

    fun callContact(name: String, confirmed: Boolean): String {
        val lists = PhoneLink.lists.value
        if (lists.favourites == null && lists.calls == null) {
            return "No contacts: the phone isn't linked through the Dashwheel companion app, which is what places calls."
        }
        val people = AssistantRules.callees(lists.favourites, lists.calls)
        return when (val m = AssistantRules.match(name, people) { it.name }) {
            AssistantRules.Match.None -> "Nobody called $name among the phone's favourites and last calls" +
                (if (people.isEmpty()) "." else "; there are: ${people.joinToString { it.name }}.") + " Nothing was dialled."
            is AssistantRules.Match.Several -> "Several match: ${m.items.joinToString { it.name }}. Ask the driver which one. Nothing was dialled."
            is AssistantRules.Match.One -> {
                val who = m.item
                val now = System.currentTimeMillis()
                if (!AssistantRules.mayGo(confirmed, callAsked, who.number, now)) {
                    callAsked = who.number to now
                    return "Found ${who.name}. Ask the driver to confirm calling ${who.name}, then call again with confirmed true. Nothing was dialled yet."
                }
                callAsked = null
                when {
                    DemoMode.isOn -> "Demo mode: ${who.name} is made up, nothing was dialled."
                    PhoneLink.dial(who.number) -> "Calling ${who.name} on the phone."
                    else -> "The phone can't place the call now (the companion link is down or too old). Nothing was dialled."
                }
            }
        }
    }

    suspend fun shareArrival(context: Context, contact: String, confirmed: Boolean): String {
        if (!PhoneLink.textsOn.value) return "The phone can't send texts for the car: the companion link is off, or texts aren't allowed in the companion app."
        val contacts = OnMyWay.contacts(context)
        if (contacts.isEmpty()) return "The phone shared no favourite with a number to text."
        val m = if (contact.isBlank()) AssistantRules.Match.One(contacts.first()) else AssistantRules.match(contact, contacts) { it.name }
        return when (m) {
            AssistantRules.Match.None -> "Nobody called $contact among the phone's favourites; there are: ${contacts.joinToString { it.name }}. Nothing was sent."
            is AssistantRules.Match.Several -> "Several match: ${m.items.joinToString { it.name }}. Ask the driver which one. Nothing was sent."
            is AssistantRules.Match.One -> {
                val who = m.item
                val now = System.currentTimeMillis()
                val number = who.number.orEmpty()
                if (!AssistantRules.mayGo(confirmed, textAsked, number, now)) {
                    textAsked = number to now
                    return "Would text ${who.name}: \"${OnMyWay.message(context)}\". Ask the driver to confirm ${who.name}, then call again with confirmed true. Nothing was sent yet."
                }
                textAsked = null
                when {
                    DemoMode.isOn -> "Demo mode: ${who.name} is made up, nothing was sent."
                    else -> when (OnMyWay.deliver(context, who)) {
                        true -> "Sent to ${who.name}."
                        false -> "The phone could not send the text to ${who.name}."
                        null -> "The phone did not answer; the text may not have gone."
                    }
                }
            }
        }
    }

    /** Guidance to a saved place when [destination] names one (home, work, a last destination), else to the words. */
    fun navigateTo(context: Context, destination: String): String {
        PlacesStore.load(context)
        val places = PlacesStore.places.value
        val place = when (AssistantRules.savedKind(destination)) {
            AssistantRules.Saved.HOME -> places.home ?: return "Home isn't saved in Dashwheel (Settings, Driving, My places). Ask for the address instead."
            AssistantRules.Saved.WORK -> places.work ?: return "Work isn't saved in Dashwheel (Settings, Driving, My places). Ask for the address instead."
            null -> AssistantRules.recentNamed(destination, places)
        }
        return if (place != null) {
            if (NavHandoff.start(context, place)) "Guidance started to ${place.name} (a saved place)." else "No navigation app could take it."
        } else {
            if (NavHandoff.startQuery(context, destination)) "Guidance started to $destination." else "No navigation app could take it."
        }
    }

    suspend fun addStop(context: Context, kind: String, instead: Boolean): String {
        val fuel = when (kind.lowercase(Locale.ROOT)) {
            "fuel" -> true
            "parking" -> false
            else -> return "Unknown stop: only fuel or parking."
        }
        val now = System.currentTimeMillis()
        val own = InAppNav.guidance.value != null
        val running = NavDirections.running.value
        val target = NavHandoff.handedOff.value?.takeIf { now - it.at < OnMyWayText.DESTINATION_MS && (running != null || own) }
        val way = AssistantRules.stopWay(fuel, own, running, target != null, instead)
        val app = when {
            own -> "Dashwheel's own guidance"
            running == NavHandoff.WAZE -> "Waze"
            running == NavHandoff.MAPS -> "Google Maps"
            else -> "the navigation app"
        }
        if (!fuel) {
            val near = target?.name?.takeIf { it.isNotBlank() }
            val query = if (near != null) "parking near $near" else "parking"
            return when (way) {
                AssistantRules.StopWay.ASK_INSTEAD -> "A stop can't be added to $app's route from here. The navigation app can guide to a car park" +
                    (if (near != null) " near $near" else " nearby") + " instead of the current route: ask the driver, then call again with instead true."
                else -> if (NavHandoff.startQuery(context, query)) "Guidance started to a car park" + (if (near != null) " near $near." else " nearby.")
                else "No navigation app could take it."
            }
        }
        val here = HandsFree.position(context) ?: return "The car's position isn't known yet, so no station can be picked."
        val best = cheapestAhead(here.latitude, here.longitude)
            ?: return "Fuel prices aren't known around here (they come from the French government's list and need internet)."
        val s = best.station
        val about = "${s.label}, ${FuelPrices.formatPriceWithCurrency(best.price)} per litre, %.1f km away".format(Locale.ROOT, best.distanceKm)
        return when (way) {
            AssistantRules.StopWay.VIA_MAPS -> {
                val to = target ?: return "The destination isn't known."
                if (NavHandoff.startVia(context, s.lat, s.lng, to)) "Asked Google Maps for a stop at $about, then on to ${to.name.ifBlank { "the destination" }}. " +
                    "Maps may start the route without the stop: say the stop was asked for, and that the map shows whether it is on the route."
                else "Google Maps would not take the stop. The cheapest station is $about: offer to guide there instead (call again with instead true)."
            }
            AssistantRules.StopWay.GO_THERE ->
                if (NavHandoff.start(context, s.lat, s.lng, s.label)) "Guidance started to $about." else "No navigation app could take it."
            AssistantRules.StopWay.ASK_INSTEAD -> "A stop can't be added to $app's route from here. The cheapest station on the way is $about. " +
                "Ask the driver whether to be guided there instead of the current route, then call again with instead true."
        }
    }

    /** The cheapest station ahead for the car's fuel, as the fuel button picks it ([HandsFree.navigateToFuel]). */
    private suspend fun cheapestAhead(lat: Double, lng: Double): RankedStation? {
        if (!DemoMode.isOn) withTimeoutOrNull(STATIONS_WAIT_MS) { runCatching { FuelPriceRepo.cheapest(lat, lng) } }
        val list = FuelPriceRepo.stations.value ?: return null
        val grade = FuelPrices.gradesFor(CarProfileStore.current).firstOrNull { g -> list.any { it.prices.containsKey(g) } } ?: return null
        return AssistantRules.ahead(FuelPrices.rank(list, grade, lat, lng), lat, lng, LocationFeed.headingDeg.value)
    }

    fun startParkingTimer(context: Context, minutes: Double, addToRunning: Boolean): String {
        val m = AssistantRules.parkingMinutes(minutes) ?: return "The minutes must be between 1 and ${AssistantRules.PARKING_MAX_MIN}."
        ParkingClock.load(context)
        if (addToRunning) ParkingClock.add(context, m) else ParkingClock.startFor(context, m)
        val ends = Units.current.value.time(Date(ParkingClock.endsAt.value))
        return "The parking timer ends at $ends. " +
            if (ParkingClock.onPhone.value) "The phone reminds the driver 10 minutes before." else "The car says so 10 minutes before, while it is on."
    }

    fun stopParkingTimer(context: Context): String {
        if (ParkingClock.load(context).value <= System.currentTimeMillis()) return "No parking timer is running."
        ParkingClock.stop(context)
        return "Parking timer stopped."
    }

    fun logFuelFill(context: Context, liters: Double, pricePerL: Double, total: Double): String =
        when (val f = AssistantRules.fill(liters, pricePerL, total)) {
            is AssistantRules.Fill.Bad -> "Not logged: ${f.reason}."
            is AssistantRules.Fill.Ok -> {
                FuelLog.start(context)
                FuelLog.addManual(context, f.liters, f.pricePerL)
                "Logged %.1f litres".format(Locale.ROOT, f.liters) +
                    (f.pricePerL?.let { " at %.3f %s per litre.".format(Locale.ROOT, it, CarProfileStore.current.currency) } ?: ".")
            }
        }
}
