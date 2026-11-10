package com.openauto.dash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cameras in an Overpass answer: their limit read as the road limits are, in km/h whatever the tag's unit. */
class SpeedCamerasParseTest {

    private fun answer(vararg tags: String?): JSONObject {
        val nodes = tags.mapIndexed { i, maxspeed ->
            val t = maxspeed?.let { ""","tags":{"highway":"speed_camera","maxspeed":"$it"}""" } ?: ""
            """{"type":"node","id":$i,"lat":${48.8 + i * 0.001},"lon":2.35$t}"""
        }
        return JSONObject("""{"elements":[${nodes.joinToString(",")}]}""")
    }

    @Test
    fun aLimitInMphIsKeptInKmh() {
        val cams = SpeedCameras.parse(answer("50 mph", "50", "fr:urban", null))
        assertEquals(listOf(80, 50, 50, null), cams.map { it.limitKmh })
    }

    @Test
    fun aNodeWithoutAPositionIsLeftOut() {
        val cams = SpeedCameras.parse(JSONObject("""{"elements":[{"type":"node","id":1,"tags":{"maxspeed":"30"}}]}"""))
        assertEquals(0, cams.size)
        assertNull(SpeedCameras.parse(JSONObject("{}")).firstOrNull())
    }
}
