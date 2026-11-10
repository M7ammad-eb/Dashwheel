package com.openauto.dash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cameras kept on the unit: patches that stand in for older ones nearby, a country's set, what is near the car, and the file's shape. */
class KeptCamerasTest {

    private val paris = SpeedCamera(48.8566, 2.3522, 50)
    private val orly = SpeedCamera(48.7262, 2.3652, 90)
    private val lyon = SpeedCamera(45.7640, 4.8357, null)
    private val lille = SpeedCamera(50.6292, 3.0573, 130)

    @Test
    fun theCamerasNearTheCarComeFromThePatchesAndTheCountry() {
        val kept = KeptCameras()
        kept.remember(48.85, 2.35, listOf(paris, orly), 1_000L)
        kept.keepCountry("FR", listOf(paris, lyon, lille), 2_000L)
        assertEquals(listOf(paris, orly), kept.near(48.85, 2.35, 15_000))
        assertEquals(listOf(lyon), kept.near(45.76, 4.84, 15_000))
        assertEquals(emptyList<SpeedCamera>(), kept.near(43.30, 5.37, 15_000))
    }

    @Test
    fun aNewPatchStandsInForTheOnesAskedFromNearby() {
        val kept = KeptCameras()
        kept.remember(48.85, 2.35, listOf(paris, orly), 1_000L)
        // Read again 2 km on: the camera gone from the map goes from the unit too.
        kept.remember(48.87, 2.35, listOf(paris), 2_000L)
        assertEquals(1, kept.patchCount)
        assertEquals(listOf(paris), kept.near(48.85, 2.35, 15_000))
        // Read 8 km on, as every refetch is: both patches stay.
        kept.remember(48.93, 2.35, listOf(paris), 3_000L)
        assertEquals(2, kept.patchCount)
    }

    @Test
    fun onlyTheLastPatchesAreKept_andDrivenCamerasAreCountedOnce() {
        val kept = KeptCameras()
        repeat(KeptCameras.MAX_PATCHES + 5) { i -> kept.remember(40.0 + i * 0.1, 2.0, listOf(paris), i.toLong()) }
        assertEquals(KeptCameras.MAX_PATCHES, kept.patchCount)
        assertEquals(1, kept.drivenCount)
        // The oldest went: the first patch's spot is empty now.
        assertEquals(emptyList<SpeedCamera>(), kept.near(40.0, 2.0, 1_000))
    }

    @Test
    fun aCountryIsReplacedWhenReadAgain_andStaleOnesAreListedOldestFirst() {
        val kept = KeptCameras()
        kept.keepCountry("fr", listOf(paris), 1_000L)
        kept.keepCountry("de", listOf(lille), 5_000L)
        kept.keepCountry("FR", listOf(paris, lyon), 2_000L)
        assertEquals(listOf("de", "fr"), kept.countryList.map { it.code })
        assertEquals(listOf(paris, lyon), kept.countryList.first { it.code == "fr" }.cameras)
        assertEquals(listOf("fr", "de"), kept.stale(10_000L, 4_000L))
        assertEquals(listOf("fr"), kept.stale(10_000L, 6_000L))
        kept.dropCountry("FR")
        assertEquals(listOf("de"), kept.countryList.map { it.code })
    }

    @Test
    fun theFileHoldsEverything_andAnEmptyOrOddOneReadsAsNothing() {
        val kept = KeptCameras()
        kept.remember(48.85, 2.35, listOf(paris, orly), 1_000L)
        kept.keepCountry("fr", listOf(lyon), 2_000L)
        val back = KeptCameras.fromJson(JSONObject(kept.toJson().toString()))
        assertEquals(1, back.patchCount)
        assertEquals(listOf(paris, orly), back.near(48.85, 2.35, 15_000))
        assertEquals(listOf(lyon), back.countryList.single().cameras)
        assertEquals(2_000L, back.countryList.single().at)
        assertEquals(null, back.near(45.76, 4.84, 15_000).single().limitKmh)

        val empty = KeptCameras.fromJson(JSONObject("{}"))
        assertEquals(0, empty.patchCount)
        assertTrue(empty.countryList.isEmpty())
        val odd = KeptCameras.fromJson(JSONObject("""{"countries":[{"code":"","cams":[]}],"patches":[{"cams":[[1]]}]}"""))
        assertTrue(odd.countryList.isEmpty())
        assertEquals(emptyList<SpeedCamera>(), odd.near(0.0, 0.0, 15_000))
    }

    @Test
    fun anOverpassAnswerParsesToCamerasWithTheirLimits() {
        val json = JSONObject(
            """{"elements":[
                {"type":"node","id":1,"lat":48.8566,"lon":2.3522,"tags":{"highway":"speed_camera","maxspeed":"50"}},
                {"type":"node","id":2,"lat":45.764,"lon":4.8357,"tags":{"highway":"speed_camera","maxspeed":"FR:urban"}},
                {"type":"node","id":3,"lat":50.6292,"lon":3.0573},
                {"type":"way","id":4}
            ]}"""
        )
        // An implied limit ("FR:urban") reads as the road limits do, 50 km/h, not as no limit.
        assertEquals(listOf(paris, SpeedCamera(45.764, 4.8357, 50), SpeedCamera(50.6292, 3.0573, null)), SpeedCameras.parse(json))
        assertEquals(emptyList<SpeedCamera>(), SpeedCameras.parse(JSONObject("{}")))
    }

    @Test
    fun aCountryCodeIsTwoLetters() {
        assertTrue(SpeedCameraStore.usableCode("fr"))
        assertTrue(SpeedCameraStore.usableCode(" DE "))
        assertFalse(SpeedCameraStore.usableCode("F"))
        assertFalse(SpeedCameraStore.usableCode("FRA"))
        assertFalse(SpeedCameraStore.usableCode("F1"))
    }

    @Test
    fun metresBetweenIsNearEnoughOverATown() {
        assertEquals(0.0, metresBetween(48.85, 2.35, 48.85, 2.35), 0.0)
        // Paris to Orly, about 14.5 km.
        assertEquals(14_500.0, metresBetween(paris.lat, paris.lng, orly.lat, orly.lng), 300.0)
    }

    @Test
    fun theTileSaysWhereItsQuietComesFrom() {
        assertEquals(R.string.widgets_cam_none, SpeedCameras.noneCaption(down = false, kept = false))
        assertEquals(R.string.widgets_osm_down, SpeedCameras.noneCaption(down = true, kept = false))
        assertEquals(R.string.widgets_cam_none_kept, SpeedCameras.noneCaption(down = true, kept = true))
        assertEquals(R.string.widgets_cam_none, SpeedCameras.noneCaption(down = false, kept = true))
    }
}
