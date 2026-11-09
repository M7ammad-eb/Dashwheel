package com.openauto.dash

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Where Google Maps is going, read off its screen through the accessibility
 * service: Maps tells other apps its next turn (the notification) but never
 * its destination, so the second screen's shadow route ([ShadowRoute]) had
 * nothing to draw for a trip typed into Maps itself. Its route preview, up for
 * a moment before "Start", names both ends: "Your location", then the
 * destination right under it. That name is kept as a hand-off
 * ([NavHandoff.noticed]) and looked up near the car once Maps guides.
 *
 * What was read is logged, as the Waze reader does, so a layout this guess
 * gets wrong can be taught from the unit's log.
 */
internal object MapsScreen {
    const val PACKAGE = NavHandoff.MAPS
    private const val TAG = "MapsScreen"
    private const val EVERY_MS = 1_000L
    private const val LOG_EVERY_MS = 10_000L

    @Volatile private var lastAt = 0L
    @Volatile private var logged: String? = null
    @Volatile private var loggedAt = 0L
    @Volatile private var lastNoticed: String? = null

    /** "Your location", as Maps writes the route's start in each of Dashwheel's languages. */
    private val YOUR_LOCATION = listOf(
        "your location", "votre position", "ma position", "ihr standort", "mein standort", "dein standort",
        "tu ubicación", "mi ubicación", "la tua posizione", "la mia posizione", "a sua localização", "sua localização", "minha localização",
        "uw locatie", "jouw locatie", "mijn locatie", "twoja lokalizacja", "moja lokalizacja", "ваше местоположение", "моё местоположение", "мое местоположение"
    )

    /** The empty destination field's own words: not a place. */
    private val PLACEHOLDERS = listOf(
        "choose destination", "choisir une destination", "choisissez une destination", "ziel auswählen", "ziel wählen", "elegir destino", "elige un destino",
        "scegli la destinazione", "scegli destinazione", "escolher destino", "escolha o destino", "bestemming kiezen", "kies een bestemming", "wybierz cel", "выберите пункт назначения"
    )

    /** A step of the route ("Head toward Rue Verte", "Turn right onto…"): the steps list, not the preview. */
    private val STEP_WORDS = listOf(
        "head ", "toward", "turn ", "onto", "continue", "take the", "keep ", "exit", "merge", "roundabout", "slight", "sharp", "u-turn", "destination will be",
        "dirigez", "vers ", "tournez", "continuez", "prenez", "restez", "sortie", "rond-point", "serrez", "demi-tour",
        "fahren sie", "richtung", "abbiegen", "weiter", "nehmen sie", "ausfahrt", "kreisverkehr",
        "dirígete", "gira", "continúa", "toma", "salida", "rotonda", "mantente",
        "dirigiti", "svolta", "continua", "prendi", "uscita", "rotatoria", "mantieni",
        "siga", "vire", "pegue", "saída", "rotunda",
        "ga ", "sla ", "rijd", "neem", "afslag", "rotonde", "houd",
        "jedź", "skręć", "kontynuuj", "zjazd", "rondo",
        "двигайтесь", "поверните", "продолжайте", "съезд", "круг"
    )

    /** Words around the destination in a field's description ("Destination: Tour Eiffel"), dropped. */
    private val DESTINATION_WORDS = listOf("destination", "arrivée", "ziel", "destino", "destinazione", "bestemming", "cel", "пункт назначения", "to", "vers", "nach", "a", "naar", "do", "до")

    /** A window change in Maps: its screen is read again when the last read is a second old. Main thread. */
    fun onEvent(context: Context, event: AccessibilityEvent) {
        if (!Edition.full || event.packageName != PACKAGE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < EVERY_MS) return
        lastAt = now
        val root = runCatching { event.source?.let(::rootOf) }.getOrNull() ?: return
        val height = Rect().also { root.getBoundsInScreen(it) }.height()
        val pieces = ArrayList<WazeScreen.Piece>()
        runCatching { collect(root, pieces, 0) }
        val destination = destinationOf(pieces, height)
        log(pieces, destination)
        if (destination != null) noticed(destination)
    }

    /** A destination read, here or off the tile's picture ([TileText]): handed off once per destination. */
    fun noticed(destination: String) {
        if (destination == lastNoticed) return
        lastNoticed = destination
        Log.i(TAG, "Maps is going to \"$destination\"")
        NavHandoff.noticed(destination)
    }

    private fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n = node
        while (true) n = n.parent ?: return n
    }

    private fun collect(node: AccessibilityNodeInfo, into: MutableList<WazeScreen.Piece>, depth: Int) {
        if (depth > 40 || !node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { into += WazeScreen.Piece(it, r.top, r.bottom, r.left) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { into += WazeScreen.Piece(it, r.top, r.bottom, r.left, described = true) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, into, depth + 1) }
    }

    private fun log(pieces: List<WazeScreen.Piece>, destination: String?) {
        val what = pieces.joinToString(" | ") { (if (it.described) "~" else "") + it.text + "@" + it.top }
        val now = SystemClock.elapsedRealtime()
        if (what == logged || now - loggedAt < LOG_EVERY_MS) return
        logged = what
        loggedAt = now
        Log.i(TAG, "read ${destination?.let { "destination \"$it\"" } ?: "no destination"} from [$what]")
    }

    /**
     * The destination on Maps' route preview: the first text under "Your
     * location" in the top half of the screen that is a place's name, not a
     * figure, a time or a button's word. Null on any other screen. Pure, so
     * the format can be unit tested.
     */
    fun destinationOf(pieces: List<WazeScreen.Piece>, height: Int): String? {
        if (height <= 0) return null
        val ordered = pieces.filter { it.top < height / 2 }.sortedWith(compareBy({ it.top }, { it.left }))
        val start = ordered.indexOfFirst { isYourLocation(it.text) }
        if (start < 0) return null
        return ordered.drop(start + 1)
            .map { cleaned(it.text) }
            .firstOrNull { isPlaceName(it) }
    }

    /** Whether [text] can be the destination: long enough, not the start, a figure, the empty field's words or a step of the route. */
    internal fun isPlaceName(text: String): Boolean {
        if (text.length !in 4..120 || isYourLocation(text) || looksLikeFigure(text)) return false
        val lower = text.lowercase()
        if (PLACEHOLDERS.any { lower == it || lower.startsWith(it) }) return false
        if (!lower.first().isLetterOrDigit()) return false
        if (STEP_WORDS.any { it in lower }) return false
        // A name needs a word of letters in it ("ois", "Wa" and other scraps of the map don't count).
        return LETTERS.containsMatchIn(text) || NUMBER_THEN_WORD.containsMatchIn(text)
    }

    private val LETTERS = Regex("""\p{L}{4,}""")
    private val NUMBER_THEN_WORD = Regex("""^\d+\s+\p{L}""")
    /** One stray character and a space before the name: the pin drawn there, as the recognizer reads it (a 9, an O, a Q…). */
    private val PIN = Regex("""^[^\p{L}\d]?[\p{L}\d¢]\s+(?=\S)""")

    private fun isYourLocation(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('…', '.', ':')
        return YOUR_LOCATION.any { t == it || t.startsWith("$it,") || t.endsWith(": $it") || t.endsWith(", $it") }
    }

    /** "Destination: Tour Eiffel", "Tour Eiffel, destination" and "9 Tour Eiffel" (the pin) give "Tour Eiffel". */
    // Compiled once: cleaned() runs on every text piece of every accessibility event while Maps is up.
    private val DESTINATION_HEADS = DESTINATION_WORDS.map { Regex("^$it\\s*[:：]\\s*", RegexOption.IGNORE_CASE) }
    private val DESTINATION_TAILS = DESTINATION_WORDS.map { Regex("\\s*[,:：]\\s*$it\\s*$", RegexOption.IGNORE_CASE) }
    private val CLOCK = Regex("""^\d{1,2}[:h]\d{2}""")

    internal fun cleaned(text: String): String {
        var t = text.trim()
        for (i in DESTINATION_WORDS.indices) {
            t = t.replace(DESTINATION_HEADS[i], "").replace(DESTINATION_TAILS[i], "")
        }
        t = t.replace(PIN, "")
        return t.trim()
    }

    private val FIGURE = Regex("""^[\d\s.,:hmkin%°•·/-]+(min|km|mi|m|h)?\s*$""", RegexOption.IGNORE_CASE)

    private fun looksLikeFigure(text: String): Boolean =
        FIGURE.matches(text) || NavState.DISTANCE.matchEntire(text) != null || CLOCK.containsMatchIn(text)
}
