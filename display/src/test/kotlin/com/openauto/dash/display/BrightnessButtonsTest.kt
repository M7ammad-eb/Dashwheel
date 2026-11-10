package com.openauto.dash.display

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessButtonsTest {

    private class Bench : BrightnessButtons.Pins, BrightnessButtons.Clock {
        var t = 0L
        val taps = mutableListOf<Int>()
        val holds = mutableListOf<Pair<Int, Long>>()
        /** When each tap began, and when the last hold ended. */
        val tapStarts = mutableListOf<Long>()
        var holdEnd = 0L
        private val down = mutableMapOf<Int, Long>()

        override fun press(gpio: Int) { down[gpio] = t }
        override fun release(gpio: Int) {
            val since = down.remove(gpio) ?: return
            if (t - since > 1_000) {
                holds += gpio to t - since
                holdEnd = t
            } else {
                taps += gpio
                tapStarts += since
            }
        }
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    @Test
    fun theFirstLevelGoesToTheBottomThenCountsUp() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        assertEquals(listOf(27 to BrightnessButtons.HOLD_TO_MIN_MS), bench.holds)
        // From 0, with the bar left to close, the first + only opens it: thirty-one presses land on 30.
        assertEquals(List(31) { 17 }, bench.taps)
        assert(bench.tapStarts.first() - bench.holdEnd >= BrightnessButtons.BAR_OPEN_MS + BrightnessButtons.MARGIN_MS)
        assertEquals(30, buttons.level)
    }

    @Test
    fun whileTheBarIsOpenEachPressIsAStep() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        buttons.apply(20)
        assertEquals(List(10) { 27 }, bench.taps)
        bench.taps.clear()
        buttons.apply(25)
        assertEquals(List(5) { 17 }, bench.taps)
        bench.taps.clear()
        buttons.apply(25)
        assertEquals(emptyList<Int>(), bench.taps)
    }

    @Test
    fun onceTheBarClosedTheFirstPressOpensIt() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        bench.t += 60_000
        // Down: the closed bar is opened with +, which only opens it, then each − is a step
        // (− opened it with two presses one day, one the next).
        buttons.apply(20)
        assertEquals(listOf(17) + List(10) { 27 }, bench.taps)
        bench.taps.clear()
        bench.t += 60_000
        // Up, just the one that opens it (55 → 65 for eleven).
        buttons.apply(30)
        assertEquals(List(11) { 17 }, bench.taps)
    }

    @Test
    fun aBarAboutToCloseIsWaitedOut() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.apply(30)
        bench.taps.clear()
        val doneAt = bench.t
        bench.t += 10_000
        buttons.apply(31)
        assertEquals(List(2) { 17 }, bench.taps)
        assert(bench.t - doneAt >= BrightnessButtons.BAR_OPEN_MS + BrightnessButtons.MARGIN_MS)
    }

    @Test
    fun aLevelTheHeadUnitRemembersIsCountedFromNotFromTheBottom() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.set(35, from = 30)
        val until = System.currentTimeMillis() + 5_000
        while (buttons.level != 35 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(emptyList<Pair<Int, Long>>(), bench.holds)
        // One + opens the closed bar, five step up.
        assertEquals(List(6) { 17 }, bench.taps)
        bench.taps.clear()
        // Asked fresh: the long way again, whatever was counted to.
        buttons.set(30, from = 35, fresh = true)
        while (bench.holds.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
        while (buttons.level != 30 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(listOf(27 to BrightnessButtons.HOLD_TO_MIN_MS), bench.holds)
        assertEquals(List(31) { 17 }, bench.taps)
    }

    @Test
    fun thePressesAreTimedByUptimeNotByTheDate() {
        // The head unit sets the Pi's date after every Hello: a step between two presses must not count as time passed.
        val before = System.nanoTime() / 1_000_000
        val now = BrightnessButtons.Clock.System.now()
        val after = System.nanoTime() / 1_000_000
        assert(now in before..after) { "$now is not between $before and $after" }
    }

    @Test
    fun levelsOutsideTheBarAreClamped() {
        val bench = Bench()
        val buttons = BrightnessButtons(up = 17, down = 27, pins = bench, clock = bench)
        buttons.set(500)
        val until = System.currentTimeMillis() + 5_000
        while (buttons.level == null && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(100, buttons.level)
    }
}
