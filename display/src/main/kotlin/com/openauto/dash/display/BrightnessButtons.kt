package com.openauto.dash.display

import com.openauto.dash.link.DisplayBrightness

/**
 * The monitor's backlight, set by pressing its own `+` and `−` buttons: the
 * Pi's GPIOs wired to them (through a divider, as the buttons sit at ~4.6 V),
 * pulled low to press, let float to release. Measured on a JRP7002 board
 * (CUQI 7"), whose firmware answers neither DDC/CI nor CEC:
 *
 * - with its bar closed, the first press of `+` only opens the bar; `−` took two presses one
 *   day and one another, so a closed bar is always opened with `+` ([openBar]);
 * - while the bar is open, every press is a step, the other way too;
 * - the bar closes [BAR_OPEN_MS] after the last press;
 * - held, a button repeats ~20 steps a second, so [HOLD_TO_MIN_MS] reaches 1 from anywhere;
 * - right after that hold, the first `+` is sometimes swallowed and sometimes counted, so the
 *   bar is left to close first;
 * - presses of [PRESS_MS] down and [GAP_MS] up all count (60 ms lost one in twenty).
 *
 * The level isn't readable, so the first change after a start goes the long
 * way (to 0, then up), unless the head unit remembers the level it last had
 * set (the monitor keeps it across power cuts); later ones are counted from
 * the level last set. The latest wish wins: one asked while the buttons are
 * busy follows once they're done.
 */
class BrightnessButtons(
    private val up: Int,
    private val down: Int,
    private val pins: Pins = Pins.Pinctrl,
    private val clock: Clock = Clock.System
) {
    /** Driving a GPIO: low (pressed) or let go (input, no pull). */
    interface Pins {
        fun press(gpio: Int)
        fun release(gpio: Int)

        object Pinctrl : Pins {
            override fun press(gpio: Int) = run("op", "dl", gpio = gpio)
            override fun release(gpio: Int) = run("ip", "pn", gpio = gpio)

            private fun run(vararg how: String, gpio: Int) {
                runCatching { ProcessBuilder(listOf("pinctrl", "set", gpio.toString()) + how).redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() }
                    .onFailure { log("brightness: pinctrl failed: ${it.message}") }
            }
        }
    }

    interface Clock {
        /** Milliseconds that only go forward: the presses are timed against each other, never against the date. */
        fun now(): Long
        fun sleep(ms: Long)

        object System : Clock {
            // Not the wall clock: the head unit sets the Pi's date after every Hello, and a step
            // between two presses left the bar taken for closed (or open) and every level a step off.
            override fun now() = java.lang.System.nanoTime() / 1_000_000
            override fun sleep(ms: Long) = Thread.sleep(ms)
        }
    }

    private val lock = Object()
    private var wanted: Int? = null
    /** The next wish starts the long way, whatever level was counted to. */
    private var startOver = false
    private var worker: Thread? = null

    /** The level last set, or null until the first one since the start. */
    @Volatile var level: Int? = null
        private set
    private var lastPress = Long.MIN_VALUE / 2

    /** Both buttons let go: a pin left low from a previous run would hold a button down. */
    fun start() {
        pins.release(up)
        pins.release(down)
    }

    /**
     * Asks for [target]; returns at once, the buttons take a few seconds. [from]
     * is the level the monitor is at as the head unit remembers it, taken when
     * nothing was set since the start; [fresh] goes the long way regardless.
     */
    fun set(target: Int, from: Int? = null, fresh: Boolean = false) {
        synchronized(lock) {
            wanted = target.coerceIn(DisplayBrightness.MIN, DisplayBrightness.MAX)
            if (fresh) startOver = true
            else if (level == null && from != null && worker?.isAlive != true) level = from.coerceIn(DisplayBrightness.MIN, DisplayBrightness.MAX)
            if (worker?.isAlive == true) return
            worker = Thread({ work() }, "display-brightness").apply { isDaemon = true; start() }
        }
    }

    private fun work() {
        while (true) {
            val target = synchronized(lock) {
                if (startOver) { level = null; startOver = false }
                wanted.also { wanted = null }
            } ?: return
            apply(target)
        }
    }

    /** Runs on the calling thread: the buttons to [target]. */
    internal fun apply(target: Int) {
        val from = level
        if (from == target) return
        if (from == null) {
            hold(down, HOLD_TO_MIN_MS)
            // Held down, the level stops at 0 (the backlight off). Right after the hold the first +
            // was swallowed once (2026-10-13: 61 asked, 60 shown) and counted another time
            // (2026-10-14: 75 asked, 76 shown, every later level one too high). Once the bar has
            // closed, the first + only opens it, as for any change: [target] + 1 presses, exact.
            clock.sleep(BAR_OPEN_MS + MARGIN_MS)
            openBar()
            repeat(target) { tap(up) }
        } else {
            val since = clock.now() - lastPress
            // Near its closing, the bar may or may not still be open: wait until it surely isn't.
            if (since in BAR_SURELY_OPEN_MS until BAR_OPEN_MS + MARGIN_MS) clock.sleep(BAR_OPEN_MS + MARGIN_MS - since)
            // While the bar is open every press is a step, either way.
            if (since >= BAR_SURELY_OPEN_MS) openBar()
            val button = if (target > from) up else down
            repeat(kotlin.math.abs(target - from)) { tap(button) }
        }
        level = target
        log("brightness $target")
    }

    /**
     * Opens the closed bar with one `+`, which only opens it, every time it was tried.
     * `−` opened it with two presses on the bench (2026-10-13: 45 → 36 for eleven) but
     * with one in the car (2026-10-14: three presses, 32 → 30), and a set made down from
     * a closed bar ended a step off. Once the bar is open, every press is a step.
     */
    private fun openBar() {
        tap(up)
        clock.sleep(OPEN_SETTLE_MS)
    }

    private fun tap(gpio: Int) {
        pins.press(gpio)
        clock.sleep(PRESS_MS)
        pins.release(gpio)
        clock.sleep(GAP_MS)
        lastPress = clock.now()
    }

    private fun hold(gpio: Int, ms: Long) {
        pins.press(gpio)
        clock.sleep(ms)
        pins.release(gpio)
        lastPress = clock.now()
    }

    companion object {
        const val PRESS_MS = 100L
        const val GAP_MS = 100L
        const val HOLD_TO_MIN_MS = 7_000L
        const val BAR_OPEN_MS = 11_000L
        const val MARGIN_MS = 1_500L
        /** After the press that opens the bar, before the presses that step: the bar is surely up. */
        const val OPEN_SETTLE_MS = 500L
        /** Since the last press: the bar is open for sure, with room for the presses themselves. */
        const val BAR_SURELY_OPEN_MS = 8_000L
    }
}
