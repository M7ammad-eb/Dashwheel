package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProbeTest {

    private val door = "can:65.0C"
    private fun frame(bits: Int, counter: Int = 0) = "41 FD 0C 38 ${SignalProbe.hex2(bits)} ${SignalProbe.hex2(counter)}"

    @Test
    fun bytesAreSlicedIntoBytesAndBits() {
        val flat = SignalProbe.flatten(mapOf(door to frame(0x20), "prop:sys.qf.x" to "true"))
        assertEquals("20", flat["$door@4.-1"])
        assertEquals("1", flat["$door@4.5"])
        assertEquals("0", flat["$door@4.7"])
        assertEquals("true", flat["prop:sys.qf.x@-1.-1"])
        // a flag is never sliced, even when it reads like hex
        assertNull(SignalProbe.flatten(mapOf("prop:a" to "10"))["prop:a@0.-1"])
    }

    @Test
    fun aProbeReadsItsPiece() {
        val p = SignalProbe(door, 4, 5, "1")
        assertEquals(true, p.isOn(frame(0x20)))
        assertEquals(false, p.isOn(frame(0x80)))
        assertNull(p.isOn(null))
        assertNull(p.isOn("41 FD"))
        assertEquals(p, SignalProbe.decode(p.encode()))
        assertNull(SignalProbe.decode("nonsense"))
    }

    @Test
    fun theBitThatFollowsTheDoorComesFirst() {
        fun at(bits: Int, counter: Int, light: String) = SignalProbe.flatten(
            mapOf(door to frame(bits, counter), "prop:sys.light" to light, "prop:sys.clock" to counter.toString())
        )
        // still: the counter and clock move, the doors don't
        val baseline = (0 until 6).map { at(0x00, it, "off") }
        val noisy = SignalMatch.noisy(baseline)
        assertTrue("$door@5.-1" in noisy)
        assertTrue("prop:sys.clock@-1.-1" in noisy)
        assertFalse("$door@4.5" in noisy)
        val rest = baseline.last()
        // the rear left door (bit 5) three times; the interior light only in the first two rounds
        val rounds = listOf(
            SignalRound(on = at(0x20, 10, "on"), off = at(0x00, 11, "off")),
            SignalRound(on = at(0x20, 12, "on"), off = at(0x00, 13, "off")),
            SignalRound(on = at(0x20, 14, "off"), off = at(0x00, 15, "off"))
        )
        val ranked = SignalMatch.rank(rest, rounds, noisy)
        val best = ranked.first()
        assertEquals(SignalProbe(door, 4, 5, "1"), best.probe)
        assertEquals(3, best.matched)
        // its byte and whole frame match no better: left out
        assertTrue(ranked.none { it.probe.key == door && it.probe.bit < 0 })
        // the light followed two of three rounds and comes after
        val light = ranked.first { it.probe.key == "prop:sys.light" }
        assertEquals(2, light.matched)
        assertEquals("on", light.probe.onValue)
        assertTrue(ranked.indexOf(light) > 0)
        assertTrue(ranked.none { it.probe.key == "prop:sys.clock" })
    }

    @Test
    fun aRoundThatDoesNotGoBackDoesNotCount() {
        val rest = SignalProbe.flatten(mapOf("prop:x" to "0"))
        val rounds = listOf(
            SignalRound(on = SignalProbe.flatten(mapOf("prop:x" to "1")), off = SignalProbe.flatten(mapOf("prop:x" to "1"))),
            SignalRound(on = SignalProbe.flatten(mapOf("prop:x" to "2")), off = SignalProbe.flatten(mapOf("prop:x" to "0")))
        )
        val ranked = SignalMatch.rank(rest, rounds, emptySet())
        assertEquals(1, ranked.single().matched)
        assertEquals("2", ranked.single().probe.onValue)
    }

    @Test
    fun aLearnedDoorWinsOverTheCarsOwn() {
        val own = McuReader.DoorState(frontLeft = true)
        assertEquals(own, mergeDoors(own, mapOf(CarSignal.REVERSE to true)))
        val merged = mergeDoors(own, mapOf(CarSignal.REAR_LEFT to true, CarSignal.FRONT_LEFT to false))!!
        assertFalse(merged.frontLeft)
        assertTrue(merged.rearLeft)
        // no doors from the car at all: the learned one alone
        assertTrue(mergeDoors(null, mapOf(CarSignal.BONNET to true))!!.bonnet)
        assertNull(mergeDoors(null, emptyMap()))
    }

    @Test
    fun flippedNamesWhatLeftItsRestingValue() {
        val rest = mapOf("a" to "0", "b" to "0", "c" to "0")
        assertEquals(setOf("b"), SignalMatch.flipped(rest, mapOf("a" to "0", "b" to "1", "c" to "1"), listOf("a", "b")))
    }

    @Test
    fun aLearnedDoorChangesOnlyOnceItHolds_otherSignalsAtOnce() {
        val fl = CarSignal.FRONT_LEFT
        val lights = CarSignal.HEADLIGHTS
        val settle = DoorSettle(3)
        assertEquals(mapOf(fl to false), settle.settle(mapOf(fl to false)))
        // A two-read flicker never shows.
        assertEquals(false, settle.settle(mapOf(fl to true))[fl])
        assertEquals(false, settle.settle(mapOf(fl to true))[fl])
        assertEquals(false, settle.settle(mapOf(fl to false))[fl])
        // Three reads in a row do; the lights follow at once.
        assertEquals(false, settle.settle(mapOf(fl to true, lights to true))[fl])
        assertEquals(false, settle.settle(mapOf(fl to true, lights to false))[fl])
        assertEquals(mapOf(fl to true, lights to true), settle.settle(mapOf(fl to true, lights to true)))
    }
}
