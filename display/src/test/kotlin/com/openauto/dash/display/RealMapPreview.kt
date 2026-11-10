package com.openauto.dash.display

import com.openauto.dash.link.ClusterState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The tilted map from real tiles kept in a folder (REAL_TILES: z/x/y.png and
 * buildings/14/x/y.pbf), for judging it on a computer; preview only. Tiles
 * the pictures needed but the folder lacks are listed in missing.txt, to be
 * fetched (with the provider's key) and the test run again.
 */
class RealMapPreview {
    @Test
    fun render() {
        val dir = System.getenv("REAL_TILES")?.let(::File) ?: run { assumeTrue("REAL_TILES not set", false); return }
        System.setProperty("java.awt.headless", "true")
        val missing = sortedSetOf<String>()
        val raster = object : TileSource {
            override val zoom = 16
            private val cache = HashMap<TileKey, BufferedImage?>()
            override fun tile(z: Int, x: Int, y: Int): BufferedImage? = cache.getOrPut(TileKey(z, x, y)) {
                File(dir, "$z/$x/$y.png").takeIf { it.isFile }?.let { TileCache.decodeImage(it.readBytes()) }
                    ?: null.also { missing += "$z/$x/$y.png" }
            }
            override fun tileAtHand(z: Int, x: Int, y: Int): BufferedImage? = cache[TileKey(z, x, y)]
            override fun prefetch(keys: Collection<TileKey>) = Unit
        }
        val buildings = object : BuildingSource {
            override val zoom = 14
            private val cache = HashMap<Pair<Int, Int>, BuildingTile?>()
            override fun buildings(x: Int, y: Int): BuildingTile? = cache.getOrPut(x to y) {
                File(dir, "buildings/14/$x/$y.pbf").takeIf { it.isFile }?.let { VectorTile.buildings(it.readBytes()) }
                    ?: null.also { missing += "buildings/14/$x/$y.pbf" }
            }
        }
        val out = File(dir, "out").apply { mkdirs() }
        val painter = Painter(1920, 1080, overscanPct = 0)
        painter.tiles = raster
        painter.buildings = buildings
        // REAL_ROUTE "lat,lon;lat,lon..." and REAL_POS "lat,lon,heading" where the folder's tiles are;
        // else up the Boulevard Saint-Michel to the Seine, then right along the quay.
        painter.route = System.getenv("REAL_ROUTE")?.split(";")?.flatMap { it.split(",").map(String::toDouble) }
            ?: listOf(48.8462, 2.3417, 48.8490, 2.3431, 48.8517, 2.3443, 48.8533, 2.3447, 48.8538, 2.3468, 48.8545, 2.3502)
        val at = System.getenv("REAL_POS")?.split(",")?.map(String::toDouble)?.let { ClusterState.Position(it[0], it[1], it[2].toInt()) }
            ?: ClusterState.Position(48.8490, 2.3431, 15)
        val base = ClusterState(
            clock = 1_700_000_000_000, speedKmh = 38, night = true, page = "NAV", maneuver = "RIGHT",
            nav = ClusterState.Nav("Tournez à droite sur Quai Saint-Michel", "250 m", "Quai Saint-Michel", "18:42"),
            position = at,
            layout = ClusterState.Layout("BIG_STACK", listOf("MAP", "SPEED", "CLOCK"))
        )
        val views = mapOf(
            "night_medium_close" to base,
            "day_medium_close" to base.copy(night = false),
            "night_low_normal" to base.copy(mapView = ClusterState.MapView(tilt = "LOW", distance = "NORMAL")),
            "night_high_far" to base.copy(mapView = ClusterState.MapView(tilt = "HIGH", distance = "FAR")),
            "night_nobuildings" to base.copy(mapView = ClusterState.MapView(buildings = false)),
            "night_flat" to base.copy(mapView = ClusterState.MapView(tilted = false)),
            "one_night" to base.copy(layout = ClusterState.Layout("ONE", listOf("MAP")))
        )
        for ((name, s) in views) {
            ImageIO.write(Painter(1920, 1080, 0).also { it.tiles = raster; it.buildings = buildings; it.route = painter.route }.paintCluster(s, s.clock), "png", File(out, "$name.png"))
        }
        // How long a picture takes here: the Pi 3B is many times slower.
        repeat(5) { painter.paintCluster(base, base.clock) }
        val t0 = System.nanoTime()
        repeat(20) { painter.paintCluster(base, base.clock) }
        println("tilted map: ${(System.nanoTime() - t0) / 20 / 1_000_000} ms a picture")
        File(dir, "missing.txt").writeText(missing.joinToString("\n"))
        println("missing: ${missing.size}")
    }
}
