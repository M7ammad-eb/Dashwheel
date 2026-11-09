package com.openauto.dash.companion

import com.openauto.dash.companion.ClipServer.Route
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var clip: File

    @Before
    fun clips() {
        dir = tmp.newFolder("road_clips")
        clip = File(dir, "20261025-101500-impact").apply { mkdirs() }
        File(clip, "clip.mp4").writeBytes(ByteArray(100))
        File(clip, "clip.json").writeText("{}")
        File(dir, "half-written").mkdirs()
        // A video without its notes did not come out whole: not served.
        File(dir, "20261025-101600-brake").apply { mkdirs() }.let { File(it, "clip.mp4").writeBytes(ByteArray(50)) }
        tmp.newFile("secret.txt")
    }

    private fun get(path: String, range: String? = null, key: String = "abc") = ClipServer.route("GET $path HTTP/1.1", range, key, dir)

    @Test
    fun listsOnlyFinishedClipsWithTheKey() {
        assertEquals(Route.Listing(listOf(clip)), get("/clips?k=abc"))
    }

    @Test
    fun refusesWithoutTheRightKey() {
        assertEquals(Route.Refused("403 Forbidden"), get("/clips"))
        assertEquals(Route.Refused("403 Forbidden"), get("/clips?k=abd"))
        assertEquals(Route.Refused("403 Forbidden"), get("/clips?k=", key = ""))
    }

    @Test
    fun sendsAClipFile() {
        assertEquals(Route.Send(File(clip, "clip.mp4"), 0), get("/clips/${clip.name}/clip.mp4?k=abc"))
        assertEquals(Route.Send(File(clip, "clip.json"), 0), get("/clips/${clip.name}/clip.json?k=abc"))
    }

    @Test
    fun nothingOutsideAClipsTwoFiles() {
        val notFound = Route.Refused("404 Not Found")
        assertEquals(notFound, get("/clips/../secret.txt?k=abc"))
        assertEquals(notFound, get("/clips/${clip.name}/../../secret.txt?k=abc"))
        assertEquals(notFound, get("/clips/..%2F..%2Fsecret.txt/clip.mp4?k=abc"))
        assertEquals(notFound, get("/clips/half-written/clip.json?k=abc"))
        assertEquals(notFound, get("/clips/20261025-101600-brake/clip.mp4?k=abc"))
        assertEquals(notFound, get("/other/${clip.name}/clip.mp4?k=abc"))
    }

    @Test
    fun resumesFromARange() {
        assertEquals(Route.Send(File(clip, "clip.mp4"), 40), get("/clips/${clip.name}/clip.mp4?k=abc", "bytes=40-"))
        assertEquals(Route.Refused("416 Range Not Satisfiable"), get("/clips/${clip.name}/clip.mp4?k=abc", "bytes=100-"))
        // A closed range is not resumed: the whole file, which HTTP allows.
        assertEquals(Route.Send(File(clip, "clip.mp4"), 0), get("/clips/${clip.name}/clip.mp4?k=abc", "bytes=10-20"))
    }

    @Test
    fun theCameraReportNeedsTheKeyToo() {
        assertEquals(Route.Cameras, get("/cameras?k=abc"))
        assertEquals(Route.RoadWatch, get("/roadwatch?k=abc"))
        assertEquals(Route.Refused("403 Forbidden"), get("/cameras"))
    }

    @Test
    fun theSignFacesAreListedAndSentByName() {
        val signs = tmp.newFolder("road_signs")
        val face = File(signs, "1-30.png").apply { writeBytes(ByteArray(10)) }
        fun signs(path: String) = ClipServer.route("GET $path HTTP/1.1", null, "abc", dir, signs)
        assertEquals(Route.Signs(listOf(face)), signs("/signs?k=abc"))
        assertEquals(Route.Send(face, 0), signs("/signs/1-30.png?k=abc"))
        assertEquals(Route.Refused("404 Not Found"), signs("/signs/../secret.txt?k=abc"))
        assertEquals(Route.Refused("403 Forbidden"), signs("/signs"))
    }

    @Test
    fun onlyGet() {
        assertEquals(Route.Refused("405 Method Not Allowed"), ClipServer.route("POST /clips?k=abc HTTP/1.1", null, "abc", dir))
    }
}
