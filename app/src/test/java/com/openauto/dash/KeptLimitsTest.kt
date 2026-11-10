package com.openauto.dash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The speed limits kept on the unit: the drives' roads, an area's, what is near the car, and the file's shape. */
class KeptLimitsTest {

    // Two short roads in Paris, one in Lyon.
    private val rivoli = LimitedRoad(50, listOf(48.8600 to 2.3400, 48.8610 to 2.3450), 1L)
    private val peri = LimitedRoad(70, listOf(48.8300 to 2.3000, 48.8310 to 2.3100), 2L)
    private val lyon = LimitedRoad(30, listOf(45.7640 to 4.8357, 45.7650 to 4.8360), 3L)

    private fun ids(roads: List<LimitedRoad>) = roads.map { it.id }.sorted()

    @Test
    fun theRoadsNearTheCarComeFromTheDrivesAndTheAreas() {
        val kept = KeptLimits()
        kept.remember(48.86, 2.34, 1_500, listOf(rivoli), 1_000L)
        kept.keepArea(KeptLimits.Area(45.76, 4.84, 25, 2_000L, "Lyon", listOf(KeptLimits.road(lyon, 2_000L))))
        assertEquals(listOf(1L), ids(kept.near(48.86, 2.34, 5_000)))
        assertEquals(listOf(3L), ids(kept.near(45.76, 4.84, 5_000)))
        assertTrue(kept.near(43.30, 5.37, 5_000).isEmpty())
        val back = kept.near(48.86, 2.34, 5_000).single()
        assertEquals(50, back.limitKmh)
        assertEquals(48.8610, back.points[1].first, 1e-5)
    }

    @Test
    fun aRoadGoneFromANewAnswerLeavesOnlyWhenItLayInsideTheCircleAsked() {
        val kept = KeptLimits()
        kept.remember(48.86, 2.34, 1_500, listOf(rivoli, peri), 1_000L)
        // Read again round Rivoli: the périphérique, 5 km off, was not in the circle and stays.
        kept.remember(48.86, 2.34, 1_500, emptyList(), 2_000L)
        assertEquals(listOf(2L), ids(kept.near(48.85, 2.32, 10_000)))
        assertEquals(1, kept.drivenCount)
    }

    @Test
    fun onlyTheRoadsSeenMostRecentlyAreKept() {
        val kept = KeptLimits()
        val many = (1..KeptLimits.MAX_DRIVEN + 10).map { LimitedRoad(50, listOf(10.0 to 10.0, 10.0 to 10.001), it.toLong()) }
        // The oldest ten go at once; road 1, seen again, comes back as the newest and survives the next cut.
        kept.remember(10.0, 10.0, 0, many, 1_000L)
        kept.remember(10.0, 10.0, 0, listOf(many[0]), 2_000L)
        assertEquals(KeptLimits.MAX_DRIVEN, kept.drivenCount)
        assertTrue(kept.near(10.0, 10.0, 100).any { it.id == 1L })
    }

    @Test
    fun theFileGivesBackWhatWasKept() {
        val kept = KeptLimits()
        kept.remember(48.86, 2.34, 1_500, listOf(rivoli), 1_000L)
        kept.keepArea(KeptLimits.Area(45.76, 4.84, 25, 2_000L, "Lyon", listOf(KeptLimits.road(lyon, 2_000L))))
        val bytes = ByteArrayOutputStream().also { kept.write(it) }.toByteArray()
        val back = KeptLimits.read(ByteArrayInputStream(bytes))
        assertEquals(1, back.drivenCount)
        assertEquals("Lyon", back.areaList.single().name)
        assertEquals(listOf(3L), ids(back.near(45.76, 4.84, 5_000)))
        assertEquals(listOf("Lyon"), back.stale(2_000L + 31L * 24 * 3_600_000, SpeedLimitStore.REFRESH_MS).map { it.name })
    }

    @Test
    fun theSquaresCoverTheCircleAndLeaveOutTheFarCorners() {
        val squares = SpeedLimitStore.tiles(48.86, 2.34, 10)
        // 10 km round Paris: about 0.18° of latitude by 0.27° of longitude, in 0.1° squares.
        assertTrue(squares.size in 6..16)
        assertTrue(squares.any { 48.86 in it[0]..it[2] && 2.34 in it[1]..it[3] })
    }

    @Test
    fun anOverpassAnswerGivesTheRoadsWithTheirIds() {
        val json = JSONObject(
            """{"elements":[
              {"type":"way","id":42,"tags":{"maxspeed":"FR:urban"},"geometry":[{"lat":48.0,"lon":2.0},{"lat":48.001,"lon":2.0}]},
              {"type":"way","id":43,"tags":{"maxspeed":"none?"},"geometry":[{"lat":48.0,"lon":2.0},{"lat":48.001,"lon":2.0}]}
            ]}"""
        )
        val roads = SpeedLimit.parse(json)
        assertEquals(1, roads.size)
        assertEquals(42L, roads[0].id)
        assertEquals(50, roads[0].limitKmh)
    }
}
