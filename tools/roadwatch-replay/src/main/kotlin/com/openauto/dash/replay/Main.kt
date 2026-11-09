package com.openauto.dash.replay

import com.openauto.dash.roadwatch.Box
import com.openauto.dash.roadwatch.Detection
import com.openauto.dash.roadwatch.RoadBand
import com.openauto.dash.roadwatch.SignFinder
import com.openauto.dash.roadwatch.VehicleKind
import com.openauto.dash.roadwatch.horizonLookAt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.system.exitProcess

/*
 * roadreplay <video.mp4> [options]: Road watch's code over a recorded drive.
 *
 *   --detections <file.jsonl>   the vehicles per frame, from detect.py (none: the lane only)
 *   --lanes <file.lanes>        what the lane model saw, from lanes.py (none: lines found by brightness)
 *   --clip <clip.json>          the recorder's notes beside the clip: its speeds and indicators
 *   --speed <km/h>              a steady speed instead, when there are no notes
 *   --horizon <0..1>            where the horizon starts in the picture (default 0.5); it is then learned from the lane
 *   --learn-horizon no          keep it where it was set
 *   --bonnet <0..1>             where the bonnet begins (default 0.92)
 *   --hfov <degrees>            the camera's view across the picture (default 66)
 *   --car-x <0..1>              where the car's middle is across the picture (default 0.5)
 *   --labels <labels.csv>       frames labelled by hand: frame,left,right,lead_m (empty cells allowed)
 *   --out <annotated.mp4>       the drive with what Road watch saw drawn over it
 *   --report <report.json>      the counts, and the timeline in <report>.csv beside it
 *   --dump <dir> --every <n>    every nth frame as a PNG with a ruler, for labelling
 *   --max <frames>              stop after this many frames
 *
 * Frames come from ffmpeg as raw RGBA, and the annotated video goes back
 * through it; both must be on the PATH.
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: roadreplay <video.mp4> [--detections f.jsonl] [--lanes f.lanes] [--clip clip.json] [--speed kmh] [--horizon y] [--bonnet y] [--hfov deg] [--car-x x] [--labels f.csv] [--out annotated.mp4] [--report report.json] [--dump dir --every n] [--max n]")
        exitProcess(2)
    }
    val video = File(args[0])
    val o = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val k = args[i]
        if (!k.startsWith("--")) {
            System.err.println("unexpected $k"); exitProcess(2)
        }
        o[k.removePrefix("--")] = args.getOrNull(i + 1) ?: run { System.err.println("$k needs a value"); exitProcess(2) }
        i += 2
    }
    val band = RoadBand(horizonY = o["horizon"]?.toFloat() ?: 0.5f, bonnetY = o["bonnet"]?.toFloat() ?: 0.92f)
    // A clip filmed zoomed in (its notes say how much) sees less across than the camera does.
    val zoom = o["clip"]?.let { Json.parseToJsonElement(File(it).readText()).jsonObject["zoom"]?.jsonPrimitive?.floatOrNull } ?: 1f
    val hfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians((o["hfov"]?.toDouble() ?: 66.0) / 2)) / zoom)).toFloat()
    if (zoom != 1f) System.err.println("zoomed $zoom: ${"%.1f".format(Locale.ROOT, hfov)}° across")
    val session = ReplaySession(band, carX = o["car-x"]?.toFloat() ?: 0.5f, hfovDeg = hfov, learnHorizon = o["learn-horizon"] != "no")
    val detections = o["detections"]?.let { readDetections(File(it)) } ?: emptyMap()
    val lanes = o["lanes"]?.let { readLanes(File(it)) }
    val notes = o["clip"]?.let { readNotes(File(it)) } ?: emptyList()
    val steady = o["speed"]?.toFloat()
    val labels = o["labels"]?.let { readLabels(File(it)) } ?: emptyList()
    val max = o["max"]?.toInt() ?: Int.MAX_VALUE
    val dump = o["dump"]?.let { File(it).also { d -> d.mkdirs() } }
    val every = o["every"]?.toInt() ?: 30
    val signs = o["signs"]?.let { File(it).also { d -> d.mkdirs() } }
    val signRows = StringBuilder("frame,kind,left,top,right,bottom,side_px\n")

    val (width, height, fps) = probe(video)
    System.err.println("$video: ${width}x$height at $fps fps")
    val decoder = ProcessBuilder("ffmpeg", "-v", "error", "-i", video.path, "-f", "rawvideo", "-pix_fmt", "rgba", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val encoder = o["out"]?.let { out ->
        ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${width}x$height", "-r", fps.toString(), "-i", "-",
            "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p", out
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
    val frames = DataInputStream(BufferedInputStream(decoder.inputStream, 1 shl 20))
    val sink = encoder?.let { BufferedOutputStream(it.outputStream, 1 shl 20) }
    val bytes = ByteArray(width * height * 4)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(width * height)
    var index = 0
    try {
        while (index < max) {
            try {
                frames.readFully(bytes)
            } catch (e: EOFException) {
                break
            }
            val timeMs = (index * 1000L / fps)
            val telemetry = telemetryAt(notes, timeMs, steady)
            // detect.py with both looks a frame: the look the phone would take on this frame.
            val found = detections[index]?.let { d -> if (d.tagged && horizonLookAt(index.toLong())) d.horizon else d.square } ?: emptyList()
            val result = session.frame(index, timeMs, ByteBuffer.wrap(bytes), width, height, width * 4, found, telemetry, lanes?.at(index))
            if (signs != null && index % SIGN_EVERY == 0) findSigns(bytes, width, height, index, signs, signRows)
            if (sink != null || (dump != null && index % every == 0)) {
                toImage(bytes, pixels, image)
                Overlay.draw(image, result, session.band, ruler = dump != null && index % every == 0, seen = session.seen)
                if (dump != null && index % every == 0) ImageIO.write(image, "png", File(dump, "f%05d.png".format(index)))
                if (sink != null) {
                    fromImage(image, pixels, bytes)
                    sink.write(bytes)
                }
            }
            index++
            if (index % 300 == 0) System.err.println("frame $index")
        }
    } finally {
        decoder.destroy()
        sink?.close()
        encoder?.waitFor()
    }
    signs?.let { File(it, "signs.csv").writeText(signRows.toString()) }
    val report = session.report(labels)
    print(report.summary())
    o["report"]?.let { path ->
        File(path).writeText(reportJson(report))
        File(path.removeSuffix(".json") + ".csv").writeText(timelineCsv(session.results))
        System.err.println("report: $path")
    }
}

/** The video's size and frame rate, from ffprobe. */
private fun probe(video: File): Triple<Int, Int, Int> {
    val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate", "-of", "csv=p=0", video.path).start()
    val line = p.inputStream.bufferedReader().readText().trim().lines().first()
    val parts = line.split(",")
    val rate = parts[2].split("/").let { if (it.size == 2) it[0].toDouble() / it[1].toDouble() else it[0].toDouble() }
    return Triple(parts[0].toInt(), parts[1].toInt(), Math.round(rate).toInt().coerceAtLeast(1))
}

/** One frame's boxes from detect.py: one look's ([square], untagged), or both looks' when [tagged] (--looks both). */
private class Looks(val square: List<Detection>, val horizon: List<Detection>, val tagged: Boolean)

/** detect.py's lines: {"frame": n, "boxes": [[left, top, right, bottom, class, score(, look)], ...]}, fractions of the frame, COCO classes. */
private fun readDetections(file: File): Map<Int, Looks> {
    val out = HashMap<Int, Looks>()
    file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val obj = Json.parseToJsonElement(line).jsonObject
        val frame = obj["frame"]!!.jsonPrimitive.int
        val square = ArrayList<Detection>()
        val horizon = ArrayList<Detection>()
        var tagged = false
        for (b in obj["boxes"]!!.jsonArray) {
            val a = b.jsonArray.map { it.jsonPrimitive.floatOrNull ?: 0f }
            val kind = VehicleKind.ofCoco(a[4].toInt()) ?: continue
            val d = Detection(Box(a[0], a[1], a[2], a[3]), kind, a[5])
            if (a.size > 6) tagged = true
            if (a.size > 6 && a[6] == 1f) horizon += d else square += d
        }
        out[frame] = Looks(square, horizon, tagged)
    }
    return out
}

/** lanes.py's file (see there): the band the model looked at, and each frame it looked at, still packed. */
private class LaneFile(val top: Float, val height: Float, val packed: Map<Int, ByteArray>) {
    fun at(frame: Int): ModelFrame? {
        val bytes = packed[frame] ?: return null
        val inflater = Inflater()
        inflater.setInput(bytes)
        val raw = ByteArray(2 * W * H)
        var n = 0
        while (n < raw.size && !inflater.finished()) {
            val got = inflater.inflate(raw, n, raw.size - n)
            // A truncated entry (a recording cut short): inflate yields nothing and asks for more, forever.
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            n += got
        }
        inflater.end()
        val chances = FloatBuffer.allocate(raw.size)
        for (b in raw) chances.put((b.toInt() and 0xFF) / 255f)
        chances.rewind()
        return ModelFrame(chances, W, H, top, height)
    }

    companion object {
        const val W = 320
        const val H = 176
    }
}

private fun readLanes(file: File): LaneFile {
    val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val magic = ByteArray(8).also { buf.get(it) }
    require(String(magic) == "DWLANES1") { "not a lanes.py file: $file" }
    val top = buf.float
    val height = buf.float
    val packed = HashMap<Int, ByteArray>()
    while (buf.remaining() >= 8) {
        val frame = buf.int
        packed[frame] = ByteArray(buf.int).also { buf.get(it) }
    }
    return LaneFile(top, height, packed)
}

/** One row of the recorder's notes: when (ms into the clip), the speed the car gave, else the GPS's, and the indicators. */
private data class Note(val offsetMs: Long, val speedKmh: Float?, val indicating: Boolean)

private fun readNotes(file: File): List<Note> {
    val obj = Json.parseToJsonElement(file.readText()).jsonObject
    return obj["samples"]?.jsonArray?.map { s ->
        val o = s.jsonObject
        Note(
            offsetMs = o["offsetMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            speedKmh = o["carKmh"]?.jsonPrimitive?.floatOrNull ?: o["gpsKmh"]?.jsonPrimitive?.floatOrNull,
            indicating = listOf("turnLeft", "turnRight", "hazard").any { o[it]?.jsonPrimitive?.booleanOrNull == true }
        )
    }?.sortedBy { it.offsetMs } ?: emptyList()
}

private fun telemetryAt(notes: List<Note>, timeMs: Long, steady: Float?): Telemetry {
    if (notes.isEmpty()) return Telemetry(steady)
    val n = notes.minByOrNull { Math.abs(it.offsetMs - timeMs) }!!
    return Telemetry(n.speedKmh ?: steady, n.indicating)
}

/** frame,left,right,lead_m with a header line; an empty cell is "not labelled". */
private fun readLabels(file: File): List<Label> =
    file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(",").map { it.trim() }
        Label(c[0].toInt(), c.getOrNull(1)?.toFloatOrNull(), c.getOrNull(2)?.toFloatOrNull(), c.getOrNull(3)?.toFloatOrNull())
    }

private fun toImage(rgba: ByteArray, pixels: IntArray, image: BufferedImage) {
    for (p in pixels.indices) {
        val i = p * 4
        pixels[p] = (0xFF shl 24) or ((rgba[i].toInt() and 0xFF) shl 16) or ((rgba[i + 1].toInt() and 0xFF) shl 8) or (rgba[i + 2].toInt() and 0xFF)
    }
    image.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
}

private fun fromImage(image: BufferedImage, pixels: IntArray, rgba: ByteArray) {
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    for (p in pixels.indices) {
        val i = p * 4
        val c = pixels[p]
        rgba[i] = (c shr 16).toByte()
        rgba[i + 1] = (c shr 8).toByte()
        rgba[i + 2] = c.toByte()
        rgba[i + 3] = -1
    }
}

private fun reportJson(r: Report): String = buildString {
    appendLine("{")
    appendLine("  \"frames\": ${r.frames},")
    appendLine("  \"bothLines\": ${r.bothLines}, \"oneLine\": ${r.oneLine}, \"noLine\": ${r.noLine},")
    appendLine("  \"leadSeen\": ${r.leadSeen},")
    appendLine("  \"lineRecall\": ${r.lineRecall}, \"lineErrorX\": ${r.lineErrorX},")
    appendLine("  \"leadRecall\": ${r.leadRecall}, \"leadErrorM\": ${r.leadErrorM},")
    appendLine("  \"warnings\": [")
    appendLine(r.warnings.joinToString(",\n") { "    {\"warning\": \"${it.warning}\", \"frame\": ${it.frame}, \"timeMs\": ${it.timeMs}}" })
    appendLine("  ]")
    append("}")
}

private fun timelineCsv(results: List<FrameResult>): String = buildString {
    appendLine("frame,time_s,speed_kmh,lines,offset,left_x,right_x,lead_m,closing_mps,ttc_s,warning,lanes,lane,horizon,car_x,lead_kind,lead_x,lead_w,lateral_m")
    for (r in results) {
        appendLine(
            listOf(
                r.index, "%.3f".format(Locale.ROOT, r.timeMs / 1000.0), r.speedKmh ?: "", r.lane.lines, r.lane.offset ?: "",
                r.lane.left?.bottomX ?: "", r.lane.right?.bottomX ?: "",
                r.lead?.distanceM ?: "", r.lead?.closingMps ?: "", r.lead?.ttcS ?: "", r.warning ?: "", r.place?.lanes ?: "", r.place?.lane ?: "", r.horizonY ?: "", r.carX ?: "",
                r.lead?.kind ?: "", r.lead?.box?.centerX ?: "", r.lead?.box?.width ?: "", r.lead?.lateralM ?: ""
            ).joinToString(",")
        )
    }
}

/** The phone looks for signs every few frames. */
private const val SIGN_EVERY = 3
/** The phone's picture: 1280 across the camera's width (Road watch's 1280 x 960). */
private const val PHONE_WIDTH = 1280

/**
 * The round signs in a frame (red rings, and the struck-through discs that end a limit), found as the
 * phone finds them: the frame shrunk to the phone's width, [SignFinder] on it, and each sign's square
 * written out four times bigger, to look at.
 */
private fun findSigns(rgba: ByteArray, width: Int, height: Int, index: Int, dir: File, rows: StringBuilder) {
    val pw = min(PHONE_WIDTH, width)
    val ph = height * pw / width
    val small = ByteArray(pw * ph * 4)
    for (y in 0 until ph) for (x in 0 until pw) {
        val from = ((y * height / ph) * width + x * width / pw) * 4
        System.arraycopy(rgba, from, small, (y * pw + x) * 4, 4)
    }
    for (spot in SignFinder.find(ByteBuffer.wrap(small), pw, ph, pw * 4, 0, bottomY = 0.85f)) {
        val b = spot.box
        rows.append("$index,${spot.kind.name.lowercase()},${b.left},${b.top},${b.right},${b.bottom},${spot.sidePx}\n")
        val x0 = (b.left * pw).toInt().coerceIn(0, pw - 1)
        val y0 = (b.top * ph).toInt().coerceIn(0, ph - 1)
        val side = spot.sidePx.coerceAtMost(min(pw - x0, ph - y0))
        val img = BufferedImage(side * 4, side * 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side * 4) for (x in 0 until side * 4) {
            val i = ((y0 + y / 4) * pw + x0 + x / 4) * 4
            img.setRGB(x, y, (small[i].toInt() and 0xFF shl 16) or (small[i + 1].toInt() and 0xFF shl 8) or (small[i + 2].toInt() and 0xFF))
        }
        ImageIO.write(img, "png", File(dir, "f%05d-%d-%s.png".format(index, (b.left * 1000).toInt(), spot.kind.name.lowercase())))
    }
}
