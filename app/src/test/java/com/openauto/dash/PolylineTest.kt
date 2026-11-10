package com.openauto.dash

import com.openauto.dash.link.ClusterRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    @Test
    fun decodesTheReferenceExample() {
        // Google's own example, precision 5.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 1e-9)
        assertEquals(-120.2, points[0].second, 1e-9)
        assertEquals(40.7, points[1].first, 1e-9)
        assertEquals(-120.95, points[1].second, 1e-9)
        assertEquals(43.252, points[2].first, 1e-9)
        assertEquals(-126.453, points[2].second, 1e-9)
        // Precision 6, as the in-app navigation asks for: the same string reads ten times smaller.
        assertEquals(3.85, Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 6)[0].first, 1e-9)
        // Cut short: what was read stays.
        assertEquals(1, Polyline.decode("_p~iF~ps|U_ulL", 5).size)
        assertTrue(Polyline.decode("", 5).isEmpty())
    }

    @Test
    fun aCorruptStringEndsWithThePointsReadSoFar_neverGarbage() {
        // Twenty continuation chunks never close a number: a shift past 64 bits used to wrap round.
        val good = Polyline.decode("_p~iF~ps|U")
        assertEquals(1, good.size)
        val corrupt = "_p~iF~ps|U" + "~".repeat(20)
        assertEquals(good, Polyline.decode(corrupt))
    }

    @Test
    fun aLongRouteIsThinnedForTheLink() {
        val long = (0 until 5_000).map { 48.0 + it * 1e-4 to 2.0 + it * 1e-4 }
        val message = ClusterRouteFeed.message(long)
        assertTrue(message.points.size <= 2 * (ClusterRoute.MAX_POINTS + 1))
        val pairs = message.pairs()
        assertEquals(48.0, pairs.first().first, 1e-9)
        assertEquals(long.last().first, pairs.last().first, 1e-5)
        // A short one goes whole, rounded to five decimals.
        assertEquals(listOf(48.85301, 2.34992), ClusterRouteFeed.message(listOf(48.853009 to 2.349919)).points)
    }
}
