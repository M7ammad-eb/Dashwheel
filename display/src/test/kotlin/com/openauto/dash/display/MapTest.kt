package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class MapTest {

    @get:Rule val tmp = TemporaryFolder()

    init {
        System.setProperty("java.awt.headless", "true")
    }

    @Test
    fun webMercatorPutsParisOnItsTile() {
        // Zoom 0: one tile, the equator and the meridian in its middle.
        assertEquals(128.0, WebMercator.x(0.0, 0), 1e-9)
        assertEquals(128.0, WebMercator.y(0.0, 0), 1e-6)
        // Notre-Dame at zoom 16 falls in tile 33195/22547 (as any slippy-map tool says).
        val x = WebMercator.x(2.3499, 16)
        val y = WebMercator.y(48.8530, 16)
        assertEquals(33195, WebMercator.tileOf(x))
        assertEquals(22547, WebMercator.tileOf(y))
        // About 1.6 m per pixel there.
        assertEquals(1.57, WebMercator.metersPerPixel(48.853, 16), 0.02)
        assertEquals(-1, WebMercator.tileOf(-0.5))
    }

    private fun png(color: Int): ByteArray {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = java.awt.Color(color); fillRect(0, 0, 256, 256); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun refusalsAreForgottenOnceTheyHaveRested() {
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        var now = 1_000_000L
        val cache = TileCache(null, "https://tiles.test/{z}/{x}/{y}.png", 16, "test", onLoaded = {}, fetch = { url, _ -> fetched += url; null }, workers = 1, clock = { now })
        assertNull(cache.tile(16, 1, 1))
        waitFor("the refusal") { cache.resting() == setOf(TileKey(16, 1, 1)) }
        // Asked again within its minute: left alone, not even queued.
        assertNull(cache.tile(16, 1, 1))
        assertEquals(emptyList<TileKey>(), cache.pending())
        assertEquals(1, fetched.size)
        // A minute on, the next ask drops it from the list rather than keeping every tile of the drive.
        now += TileStore.RETRY_MS + 1
        assertNull(cache.tile(16, 2, 2))
        assertTrue(TileKey(16, 1, 1) !in cache.resting())
        waitFor("the second refusal") { cache.resting() == setOf(TileKey(16, 2, 2)) }
        assertEquals(2, fetched.size)
    }

    @Test
    fun tilesAreFetchedOnceKeptOnTheCardAndRefusalsRest() {
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val loaded = java.util.concurrent.atomic.AtomicInteger()
        val cache = TileCache(
            tmp.root, "https://tiles.test/{z}/{x}/{y}.png", 16, "test",
            onLoaded = { loaded.incrementAndGet() },
            fetch = { url, agent ->
                assertEquals("test", agent)
                fetched += url
                if ("/16/5/" in url) null else png(0x336699)
            }
        )
        assertEquals("https://tiles.test/16/33190/22545.png", cache.urlFor(TileKey(16, 33190, 22545)))
        // Not at hand: null now, fetched in the background, then on the card and in memory.
        assertNull(cache.tile(16, 1, 2))
        waitFor("the tile") { cache.tile(16, 1, 2) != null }
        assertEquals(listOf("https://tiles.test/16/1/2.png"), fetched)
        waitFor("the tile on the card") { File(tmp.root, "16/1/2.png").isFile }
        waitFor("the redraw request") { loaded.get() >= 1 }
        // A second cache over the same folder reads it from the card without fetching.
        val againFetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val again = TileCache(tmp.root, "https://tiles.test/{z}/{x}/{y}.png", 16, "test", onLoaded = {}, fetch = { url, _ -> againFetched += url; null })
        waitFor("the tile from the card") { again.tile(16, 1, 2) != null }
        assertEquals(emptyList<String>(), againFetched)
        // A tile the server refuses is not asked for again at once.
        assertNull(cache.tile(16, 5, 5))
        waitFor("the refusal") { fetched.count { "/16/5/5" in it } == 1 }
        assertNull(cache.tile(16, 5, 5))
        assertTrue(TileKey(16, 5, 5) !in cache.pending())
        assertEquals(1, fetched.count { "/16/5/5" in it })
        // Beyond the poles there is no tile; east of the antimeridian the world wraps.
        assertNull(cache.tile(16, 0, -1))
        assertNull(cache.tile(16, 0, 1 shl 16))
        assertEquals("https://tiles.test/1/1/0.png", cache.urlFor(TileKey(1, 1, 0)))
        cache.prefetch(listOf(TileKey(16, 1, 3)))
        waitFor("the prefetched tile") { File(tmp.root, "16/1/3.png").isFile }
    }

    @Test
    fun aTileOnTheCardComesThroughAWorkerNotTheCaller() {
        // Decoding a tile took the drawing thread milliseconds: it gets null now, and the redraw call once a worker has it.
        File(tmp.root, "16/7/7.png").apply { parentFile.mkdirs(); writeBytes(png(0x112233)) }
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val loaded = java.util.concurrent.atomic.AtomicInteger()
        val cache = TileCache(tmp.root, "https://tiles.test/{z}/{x}/{y}.png", 16, "test", onLoaded = { loaded.incrementAndGet() }, fetch = { url, _ -> fetched += url; null })
        assertNull(cache.tile(16, 7, 7))
        waitFor("the redraw request") { loaded.get() == 1 }
        val tile = cache.tileAtHand(16, 7, 7)
        assertNotNull(tile)
        assertEquals(0x112233, tile!!.getRGB(10, 10) and 0xFFFFFF)
        assertEquals(emptyList<String>(), fetched)
        // Asking again is a plain memory hit.
        assertNotNull(cache.tile(16, 7, 7))
        assertEquals(emptyList<TileKey>(), cache.pending())
    }

    @Test
    fun theDrawingThreadsTilesGoToTheFrontOfTheQueue() {
        // The one worker holds the first tile until told: the queue's order can be looked at meanwhile.
        val taken = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cache = TileCache(null, "https://tiles.test/{z}/{x}/{y}.png", 16, "test", onLoaded = {}, fetch = { _, _ -> taken.countDown(); release.await(); null }, workers = 1)
        assertNull(cache.tile(16, 1, 1))
        assertTrue(taken.await(5, java.util.concurrent.TimeUnit.SECONDS))
        cache.prefetch(listOf(TileKey(16, 2, 2), TileKey(16, 3, 3), TileKey(16, 4, 4)))
        // Asked for by the drawing thread: moved to the front, the others keeping their order; asked again, nothing changes.
        assertNull(cache.tile(16, 4, 4))
        assertNull(cache.tile(16, 4, 4))
        assertEquals(listOf(TileKey(16, 4, 4), TileKey(16, 2, 2), TileKey(16, 3, 3), TileKey(16, 1, 1)), cache.pending())
        cache.prefetch(listOf(TileKey(16, 2, 2)))
        assertEquals(listOf(TileKey(16, 4, 4), TileKey(16, 2, 2), TileKey(16, 3, 3), TileKey(16, 1, 1)), cache.pending())
        release.countDown()
    }

    private class FakeTiles(override val zoom: Int = 16) : TileSource {
        val asked = LinkedHashSet<TileKey>()
        val prefetched = LinkedHashSet<TileKey>()
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { color = java.awt.Color(0x224466); fillRect(0, 0, 256, 256); dispose() }
        }
        override fun tile(z: Int, x: Int, y: Int): BufferedImage? {
            asked += TileKey(z, x, y)
            return if ((x + y) % 2 == 0) image else null
        }
        override fun prefetch(keys: Collection<TileKey>) {
            prefetched += keys
        }
    }

    @Test
    fun theMapFaceDrawsTilesTheRouteAndTheCarAndAsksForTheTilesAhead() {
        val painter = Painter(1024, 600, overscanPct = 0)
        val fake = FakeTiles()
        painter.tiles = fake
        // A route north along a street, then east, as the link carries it.
        painter.route = listOf(48.8500, 2.3499, 48.8530, 2.3499, 48.8560, 2.3499, 48.8560, 2.3600, 48.8560, 2.3800)
        val state = ClusterState(
            clock = 0, page = "NAV", night = true,
            position = ClusterState.Position(48.8530, 2.3499, headingDeg = 10),
            nav = ClusterState.Nav("Turn right onto Rue de Rivoli", "300 m", "", "18:42"), maneuver = "RIGHT",
            layout = ClusterState.Layout("ONE", listOf("MAP")),
            // The flat map, north up (the tilted one is ChaseMapTest's).
            mapView = ClusterState.MapView(tilted = false)
        )
        // By day first: the tiles show as they are.
        val image = painter.paintCluster(state.copy(night = false), 0)
        // The tiles under the screen were asked for, the ring around them and the route ahead prefetched.
        assertTrue(fake.asked.any { it.z == 16 && it.x == 33195 && it.y == 22547 })
        assertTrue(fake.prefetched.isNotEmpty())
        assertTrue(fake.prefetched.any { it.x > 33195 })
        // A tile that is at hand shows its colour; somewhere on the screen the accent route is drawn.
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).map { it and 0xFFFFFF }
        assertTrue(pixels.contains(0x224466))
        assertTrue(pixels.contains(0x4FC3F7))

        // Without a fix: the waiting words, nothing asked for; without tiles: the plain ground still draws.
        val before = fake.asked.size
        painter.paintCluster(state.copy(position = null), 0)
        assertEquals(before, fake.asked.size)
        painter.tiles = null
        painter.paintCluster(state, 0)
        painter.paintCluster(state.copy(night = false, position = ClusterState.Position(48.8530, 2.3499, null), nav = null), 0)
        // By night without night tiles the day's are dimmed: the tile's own colour no longer shows as is.
        painter.tiles = fake
        val dimmed = painter.paintCluster(state, 0).getRGB(0, 0, 1024, 600, null, 0, 1024).map { it and 0xFFFFFF }
        assertTrue(!dimmed.contains(0x224466))
    }
}
