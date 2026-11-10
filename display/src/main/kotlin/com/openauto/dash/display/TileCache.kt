package com.openauto.dash.display

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

/** Where the map's tiles come from, as the Painter sees it. */
interface TileSource {
    val zoom: Int

    /** The tile, if it is at hand; null while it is being fetched (the source redraws the screen once it is). */
    fun tile(z: Int, x: Int, y: Int): BufferedImage?

    /** The tile only if it is in memory now: nothing fetched for it. */
    fun tileAtHand(z: Int, x: Int, y: Int): BufferedImage? = null

    /** Tiles wanted soon, in order of need. */
    fun prefetch(keys: Collection<TileKey>)
}

data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Tiles of any kind for the display's own map: fetched from [urlTemplate]
 * ({z}, {x}, {y}) over whatever network the Pi has (the phone's hotspot),
 * kept decoded in memory for the tiles in use, and on the card under [dir]
 * for the next drive (on a read-only card that folder is in memory too, and
 * starts empty at each power-up). A tile that can't be fetched is left alone
 * for a minute, so a Pi without internet doesn't hammer the link.
 */
open class TileStore<T : Any>(
    private val dir: File?,
    private val urlTemplate: String,
    private val userAgent: String,
    /** The file's bytes made into a tile; null for bytes that aren't one. */
    private val decode: (ByteArray) -> T?,
    /** The files' extension on the card. */
    private val extension: String,
    private val memoryTiles: Int,
    /** A tile arrived: the picture is worth drawing again. */
    private val onLoaded: () -> Unit,
    private val fetch: (String, String) -> ByteArray?,
    workers: Int,
    /** Milliseconds that only go forward (the Pi's wall clock jumps when the head unit sets it). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lock = Object()
    private val memory = object : LinkedHashMap<TileKey, T>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, T>?): Boolean = size > memoryTiles
    }
    /** The tiles to fetch, first first; the drawing thread's own needs go to the front. */
    private val queue = ArrayDeque<TileKey>()
    private val fetching = HashSet<TileKey>()
    private val failedUntil = HashMap<TileKey, Long>()
    private var writes = 0

    init {
        dir?.let { runCatching { it.mkdirs() } }
        repeat(workers) { i -> Thread({ work() }, "tile-fetch-$extension-$i").apply { isDaemon = true; start() } }
    }

    /**
     * The tile, if it is in memory; else it is read from the card or fetched
     * by a worker, and [onLoaded] says when it came. A tile on the card is
     * not read here: decoding it took the drawing thread a few milliseconds a
     * tile, a whole picture's worth when the map turned to a new region.
     */
    fun get(z: Int, x: Int, y: Int): T? {
        val key = wrap(z, x, y) ?: return null
        synchronized(lock) {
            memory[key]?.let { return it }
        }
        enqueue(listOf(key), first = true)
        return null
    }

    /** The tile only if it is in memory now: no fetching, no reading the card. */
    fun peek(z: Int, x: Int, y: Int): T? {
        val key = wrap(z, x, y) ?: return null
        synchronized(lock) { return memory[key] }
    }

    fun prefetch(keys: Collection<TileKey>) {
        enqueue(keys.mapNotNull { wrap(it.z, it.x, it.y) }, first = false)
    }

    /** Queued for the workers, unless in memory, being fetched, or recently refused; [first]: ahead of the rest, queued or not. */
    private fun enqueue(keys: List<TileKey>, first: Boolean) {
        val now = clock()
        synchronized(lock) {
            // Refusals that have rested their minute are forgotten: over a drive without internet, every tile passed would stay.
            failedUntil.values.removeIf { it <= now }
            val wanted = keys.filter { it !in memory && it !in fetching && it !in failedUntil }
            if (wanted.isEmpty()) return
            if (first) {
                // Moved to the front one by one, in their order: the queue is short, and the drawing thread asks every picture.
                for (key in wanted.asReversed()) {
                    queue.remove(key)
                    queue.addFirst(key)
                }
            } else {
                for (key in wanted) if (key !in queue) queue.addLast(key)
            }
            while (queue.size > MAX_QUEUE) queue.removeLast()
            lock.notifyAll()
        }
    }

    private fun work() {
        while (true) {
            val key = synchronized(lock) {
                while (queue.isEmpty()) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                queue.removeFirst().also { fetching += it }
            }
            // On the card from an earlier drive, else fetched; a file that isn't a tile (a write cut short) goes.
            val file = fileOf(key)?.takeIf { it.isFile }
            var tile = file?.let { f -> runCatching { decode(f.readBytes()) }.getOrNull() }
            val fromCard = tile != null
            var bytes: ByteArray? = null
            if (!fromCard) {
                file?.let { runCatching { it.delete() } }
                bytes = runCatching { fetch(urlFor(key), userAgent) }.getOrNull()
                tile = bytes?.let { runCatching { decode(it) }.getOrNull() }
            }
            synchronized(lock) {
                fetching -= key
                if (tile == null) {
                    failedUntil[key] = clock() + RETRY_MS
                } else {
                    memory[key] = tile
                }
            }
            if (tile != null) {
                if (!fromCard) save(key, bytes!!)
                onLoaded()
            }
        }
    }

    private fun save(key: TileKey, bytes: ByteArray) {
        val file = fileOf(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        synchronized(lock) {
            if (++writes % PRUNE_EVERY != 0) return
        }
        prune()
    }

    /** The oldest tiles go when the card holds more than [MAX_FILES]. */
    private fun prune() {
        val root = dir ?: return
        val files = runCatching { root.walkTopDown().filter { it.isFile && it.name.endsWith(".$extension") }.toList() }.getOrDefault(emptyList())
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /** The tiles refused lately and still resting, for the tests. */
    internal fun resting(): Set<TileKey> = synchronized(lock) { failedUntil.keys.toSet() }

    /** The tiles queued or with a worker, in the queue's order, for the tests. */
    internal fun pending(): List<TileKey> = synchronized(lock) { queue.toList() + fetching }

    private fun fileOf(key: TileKey): File? = dir?.let { File(it, "${key.z}/${key.x}/${key.y}.$extension") }

    internal fun urlFor(key: TileKey): String =
        urlTemplate.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())

    /** [x] wrapped round the world; null for a row outside it. */
    private fun wrap(z: Int, x: Int, y: Int): TileKey? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        return TileKey(z, Math.floorMod(x, n), y)
    }

    companion object {
        const val MAX_QUEUE = 120
        /** Tiles on the card at most, about 10 KB each: a region of a few thousand square kilometres. */
        const val MAX_FILES = 20_000
        const val PRUNE_EVERY = 200
        const val RETRY_MS = 60_000L
        private const val TIMEOUT_MS = 6_000

        /** The tile's bytes (unzipped), or null when the server (or the network) says no or sends more than [maxBytes]. */
        fun download(url: String, userAgent: String, maxBytes: Int): ByteArray? {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept-Encoding", "gzip")
                if (connection.responseCode != 200) return null
                val zipped = connection.contentEncoding.equals("gzip", ignoreCase = true)
                val bytes = connection.inputStream.use { raw ->
                    (if (zipped) GZIPInputStream(raw) else raw).use { it.readNBytes(maxBytes + 1) }
                }
                return bytes.takeIf { it.size <= maxBytes }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Raster map tiles: kept decoded as plain RGB pixels, which Java2D draws
 * fastest and the tilted map ([ChaseMap]) reads straight from memory.
 */
class TileCache(
    dir: File?,
    urlTemplate: String,
    override val zoom: Int,
    userAgent: String,
    onLoaded: () -> Unit,
    fetch: (String, String) -> ByteArray? = { url, agent -> download(url, agent, MAX_BYTES) },
    workers: Int = 2,
    clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : TileStore<BufferedImage>(dir, urlTemplate, userAgent, ::decodeImage, "png", MEMORY_TILES, onLoaded, fetch, workers, clock), TileSource {

    override fun tile(z: Int, x: Int, y: Int): BufferedImage? = get(z, x, y)

    override fun tileAtHand(z: Int, x: Int, y: Int): BufferedImage? = peek(z, x, y)

    companion object {
        /** Decoded tiles kept in memory: the tilted map uses about 60 of three zooms, with a ring around them. */
        const val MEMORY_TILES = 128
        private const val MAX_BYTES = 1_000_000

        /** A PNG or JPEG as TYPE_INT_RGB; null for bytes that aren't a picture. */
        fun decodeImage(bytes: ByteArray): BufferedImage? {
            val read = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            if (read.type == BufferedImage.TYPE_INT_RGB) return read
            return BufferedImage(read.width, read.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
                val g = rgb.createGraphics()
                g.drawImage(read, 0, 0, null)
                g.dispose()
            }
        }
    }
}

/** The buildings' shapes and heights for the tilted map, by vector tile at [zoom]. */
interface BuildingSource {
    val zoom: Int

    /** The tile's buildings if at hand; else null while it is fetched. */
    fun buildings(x: Int, y: Int): BuildingTile?
}

/**
 * Vector tiles (OpenMapTiles, as MapTiler serves them) for the buildings
 * only: at zoom 14, where they all are, about 1.5 km a side in France, so a
 * drive needs few of them; a town centre's tile can weigh a megabyte.
 */
class BuildingCache(
    dir: File?,
    urlTemplate: String,
    userAgent: String,
    onLoaded: () -> Unit,
    fetch: (String, String) -> ByteArray? = { url, agent -> download(url, agent, MAX_BYTES) }
) : TileStore<BuildingTile>(dir, urlTemplate, userAgent, VectorTile::buildings, "pbf", MEMORY_TILES, onLoaded, fetch, workers = 1), BuildingSource {
    override val zoom = ZOOM

    override fun buildings(x: Int, y: Int): BuildingTile? = get(ZOOM, x, y)

    companion object {
        const val ZOOM = 14
        const val MEMORY_TILES = 9
        private const val MAX_BYTES = 6_000_000
    }
}
