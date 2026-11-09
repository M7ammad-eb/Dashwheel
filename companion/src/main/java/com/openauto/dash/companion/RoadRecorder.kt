package com.openauto.dash.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.openauto.dash.link.CarTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/*
 * The road recorder, EXPERIMENTAL: while Road watch runs, the camera also
 * records the road, in short segments of which only the last two minutes are
 * kept ([ClipRing]). A moment worth keeping (a knock the phone feels, a hard
 * brake, Road watch's collision warning, the car's hazard lights, a tap)
 * locks the minute around it as a clip: one MP4 with the time, the place,
 * the car's own speed and its indicators written into the picture, and a
 * notes file beside it with the same data second by second. Clips stay on
 * the phone until shared or deleted; nothing is uploaded anywhere.
 *
 * No sound is recorded: it isn't needed for what happened on the road, and
 * it would record the people in the car.
 */

/** The car's latest word about itself, from the head unit ([CarTelemetry]), for the picture and the notes. */
object RoadTelemetry {
    @Volatile var latest: CarTelemetry? = null
        private set
    @Volatile var at = 0L
        private set

    fun update(message: CarTelemetry) {
        latest = message
        at = SystemClock.elapsedRealtime()
    }

    /** The car's word, unless it is stale (the link dropped, Road watch stopped on the car). */
    fun fresh(now: Long = SystemClock.elapsedRealtime()): CarTelemetry? = latest?.takeIf { now - at < FRESH_MS }

    private const val FRESH_MS = 3_000L
}

/** A clip kept on the phone, as the Companion lists it. */
data class ClipInfo(
    val dir: File,
    /** When the moment was, wall clock. */
    val at: Long,
    val trigger: String,
    val durationMs: Long,
    val bytes: Long
) {
    val video: File get() = File(dir, "clip.mp4")
    val notes: File get() = File(dir, "clip.json")
}

/** The clips on the phone, and the recorder's switch. */
object RoadClips {
    private const val PREFS = "road_recorder"
    private const val KEY_ON = "on"
    /** More than this and the oldest goes: a clip is about 40 MB. */
    const val MAX_CLIPS = 25

    private val _clips = MutableStateFlow<List<ClipInfo>>(emptyList())
    val clips: StateFlow<List<ClipInfo>> = _clips

    private val _enabled = MutableStateFlow(false)
    /** Record the road while Road watch runs; off until the driver turns it on. */
    val enabled: StateFlow<Boolean> = _enabled

    fun load(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
        refresh(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    fun dir(context: Context): File = File(context.filesDir, "road_clips")

    fun refresh(context: Context) {
        val dirs = dir(context).listFiles { f -> f.isDirectory } ?: emptyArray()
        _clips.value = dirs.mapNotNull { read(it) }.sortedByDescending { it.at }
    }

    fun delete(context: Context, clip: ClipInfo) {
        clip.dir.deleteRecursively()
        refresh(context)
    }

    private fun read(dir: File): ClipInfo? {
        val video = File(dir, "clip.mp4")
        val json = File(dir, "clip.json")
        // Both, or the clip is still being built (RoadRecorder.build) or broken.
        if (!video.exists() || !json.exists()) return null
        val notes = runCatching { JSONObject(json.readText()) }.getOrNull()
        return ClipInfo(
            dir,
            at = notes?.optLong("at", video.lastModified()) ?: video.lastModified(),
            trigger = notes?.optString("trigger", "button") ?: "button",
            durationMs = notes?.optLong("durationMs") ?: 0L,
            bytes = video.length()
        )
    }
}

class RoadRecorder(context: Context) {
    private val app = context.applicationContext
    private val ring = ClipRing(RING_MS, BEFORE_MS, AFTER_MS)
    private val impacts = ImpactDetector(app.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GRAVITY) != null)
    private val worker = Executors.newSingleThreadExecutor()
    private val thread = HandlerThread("road-recorder").apply { start() }
    private val handler = Handler(thread.looper)
    private val ringDir = File(app.cacheDir, "road_ring")

    /** The video use case to bind with the camera, and the effect that writes the data into its picture. */
    val videoCapture: VideoCapture<Recorder>
    val overlay: OverlayEffect

    /** A segment being recorded: its file, CameraX's recording of it, and when the encoder reported starting. */
    private class Run(val file: File) {
        var recording: Recording? = null
        var startedAt = 0L
    }

    private var run: Run? = null
    /**
     * The run [stop] or [pause] ended, until its Finalize comes: only then is
     * its segment in the ring, and a clip still waiting for its after-segments
     * closes with it rather than without it (see [onEvent]).
     */
    private var ending: Run? = null
    private var closing = false
    @Volatile private var running = false
    private var startedOnce = false
    @Volatile private var tooHot = false

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state

    /** How many clips are being written right now (the camera's "keeping" light). */
    private val _keeping = MutableStateFlow(0)
    val keeping: StateFlow<Int> = _keeping

    enum class State { OFF, RECORDING, PAUSED_HOT, PAUSED_FULL, PAUSED_LOW, UNSUPPORTED }

    @Volatile private var lowPower = false

    /** The battery is low with no charger ([on]): the recorder stops, so Road watch itself lasts longer. */
    fun lowPower(on: Boolean) {
        if (on == lowPower) return
        lowPower = on
        handler.post { if (on) pause(State.PAUSED_LOW) else resume() }
    }

    /** What the phone and the car knew at one moment: a row of the notes, and what the picture shows. */
    private class Sample(
        val elapsedMs: Long, val wallMs: Long,
        val lat: Double?, val lng: Double?, val gpsKmh: Float?,
        val car: CarTelemetry?,
        val headingRad: Float? = null,
        val signKmh: Int? = null,
        val pitchDeg: Float? = null,
        val tiltDeg: Float? = null
    )

    private val samples = ArrayDeque<Sample>()
    @Volatile private var location: Location? = null
    @Volatile private var locationAt = 0L
    @Volatile private var rotation = 0

    private val sensors = app.getSystemService(SensorManager::class.java)
    private val shock = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (e.sensor.type == Sensor.TYPE_GRAVITY) {
                impacts.gravity(e.values[0], e.values[1], e.values[2], now)?.let { keep("impact", it) }
            } else if (impacts.acceleration(e.values[0], e.values[1], e.values[2], now)) {
                keep("impact")
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val power = app.getSystemService(PowerManager::class.java)
    private val heat = PowerManager.OnThermalStatusChangedListener { status ->
        val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
        if (hot != tooHot) {
            tooHot = hot
            handler.post { if (hot) pause(State.PAUSED_HOT) else if (running) resume() }
        }
    }

    init {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(BIT_RATE)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        overlay = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, handler) { e -> Log.w(TAG, "overlay failed", e) }
        overlay.setOnDrawListener { frame -> draw(frame); true }
    }

    /** The camera is bound with the video use case: the ring starts rolling. Again after [stop], when the screen comes back. */
    fun start() {
        if (running || _state.value == State.UNSUPPORTED) return
        generation++
        active = this
        running = true
        ringDir.mkdirs()
        // The first time only: after a stop the ring still lists the segments on disk,
        // and a clip kept soon after the restart wants the minute before it.
        if (!startedOnce) ringDir.listFiles()?.forEach { it.delete() }
        startedOnce = true
        sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(shock, it, SensorManager.SENSOR_DELAY_GAME) }
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(shock, it, SensorManager.SENSOR_DELAY_UI) }
        runCatching { power?.addThermalStatusListener(heat) }
        handler.post {
            // After a stop whose work left the sampling running (see stop): one loop, not two.
            handler.removeCallbacks(sampleTick)
            sample()
            if (!tooHot) resume()
        }
    }

    /** Which start or stop was the last: a stop's work, run later on the recorder's thread, must not undo a start that came after it. */
    private var generation = 0

    /** The camera couldn't take the video use case beside Road watch's: no recording on this phone. */
    fun unsupported() {
        _state.value = State.UNSUPPORTED
    }

    fun stop() {
        running = false
        val gen = ++generation
        if (active === this) active = null
        sensors?.unregisterListener(shock)
        runCatching { power?.removeThermalStatusListener(heat) }
        handler.post {
            // Started again meanwhile (the screen off and on at once): the start's own
            // work is queued behind this and must stay; only the old segment ends here.
            val last = gen == generation
            // Only the sampling stops: a segment's Finalize or a clip's release posted here must still run.
            if (last) handler.removeCallbacks(sampleTick)
            end()
            if (last) _state.value = State.OFF
        }
    }

    fun close() {
        stop()
        handler.post {
            closing = true
            if (ending == null) release()
        }
    }

    /** The recorder's thread and worker go, once the last segment's Finalize has been seen ([close]). */
    private fun release() {
        overlay.close()
        thread.quitSafely()
        worker.shutdown()
    }

    /** The phone's position, from Road watch's listener: for the notes, the picture, and the hard-brake trigger. */
    fun location(l: Location) {
        location = l
        locationAt = SystemClock.elapsedRealtime()
        if (l.hasSpeed() && impacts.speed(l.speed * 3.6f, locationAt)) keep("brake")
    }

    /** The frames' rotation, so the written data stands upright in the video whichever way the phone is held. */
    fun rotation(degrees: Int) {
        rotation = degrees
    }

    /** Keep the minute around [now]; [trigger] says why ("impact", "brake", "collision", "hazard", "button"). */
    fun keep(trigger: String, now: Long = SystemClock.elapsedRealtime()) {
        handler.post {
            if (_state.value != State.RECORDING) return@post
            if (ring.keep(now, trigger)) {
                _keeping.value = ring.pending.size
                felt()
            }
        }
    }

    /** A short buzz and a note: the driver knows the moment is kept without looking at the screen. */
    private fun felt() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            handler.postDelayed({ tone.release() }, 500)
        }
    }

    // ---- The segments ----

    private fun resume() {
        if (!running || run != null || tooHot || lowPower) return
        if (freeBytes() < MIN_FREE_BYTES) {
            _state.value = State.PAUSED_FULL
            // Room is made by the phone's own clean-up or the driver: looked at again in a while,
            // as docs/road-watch.md promises. Nothing else would have called back here.
            handler.postDelayed({ if (running) resume() }, RETRY_FULL_MS)
            return
        }
        val r = Run(File(ringDir, "seg-${SystemClock.elapsedRealtime()}.mp4"))
        val options = FileOutputOptions.Builder(r.file).setDurationLimitMillis(SEGMENT_MS).build()
        try {
            // The events name their run: a Finalize that comes after the next segment started
            // (a stop and a start at once, a pause and a resume) is told from the new segment's.
            r.recording = videoCapture.output.prepareRecording(app, options).start(ContextCompat.getMainExecutor(app)) { event -> handler.post { onEvent(r, event) } }
            run = r
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start a segment", e)
            _state.value = State.UNSUPPORTED
        }
    }

    private fun pause(why: State) {
        end()
        if (_state.value != State.OFF) _state.value = why
    }

    /**
     * The segment being recorded ends. The clips still waiting for their
     * after-segments close once its Finalize has put it in the ring, not
     * before: closed now, they would lose the segment holding the moment.
     * With nothing recording and nothing on its way, they close now.
     */
    private fun end() {
        val r = run
        run = null
        if (r != null) {
            r.recording?.stop()
            ending = r
        } else if (ending == null) {
            ring.stopped().forEach { build(it) }
        }
    }

    private fun onEvent(r: Run, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                r.startedAt = SystemClock.elapsedRealtime()
                // A segment paused (heat, battery) before its start was reported: not recording.
                if (r === run) _state.value = State.RECORDING
            }
            is VideoRecordEvent.Finalize -> {
                val ok = (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) && r.file.length() > 0
                if (ok) {
                    val done = ring.segmentDone(Segment(r.file.name, r.startedAt, SystemClock.elapsedRealtime()))
                    done.completed.forEach { build(it) }
                    done.delete.forEach { File(ringDir, it.name).delete() }
                } else {
                    r.file.delete()
                    if (event.hasError()) Log.w(TAG, "segment failed: ${event.error}")
                }
                if (r === ending) {
                    ending = null
                    // Its segment is in the ring: a clip still waiting for its after-segments keeps what it has,
                    // this segment included, unless the ring rolls on already (started again meanwhile).
                    if (run == null) ring.stopped().forEach { build(it) }
                    if (closing) release()
                } else if (r === run) {
                    run = null
                    // The next segment, straight away: the gap between two is what the encoder takes to start.
                    if (running && !tooHot) resume()
                }
            }
            else -> Unit
        }
    }

    // ---- The data: sampled twice a second, written into the picture, kept beside a clip ----

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val l = location?.takeIf { now - locationAt < 5_000L }
        samples.addLast(Sample(now, System.currentTimeMillis(), l?.latitude, l?.longitude, l?.speed?.takeIf { l.hasSpeed() }?.times(3.6f), RoadTelemetry.fresh(now), PhoneHeading.radians, RoadSigns.kmh, PhoneHeading.pitchDeg, PhoneHeading.tiltDeg))
        while (samples.isNotEmpty() && now - samples.first().elapsedMs > RING_MS + AFTER_MS) samples.removeFirst()
        handler.postDelayed(sampleTick, SAMPLE_MS)
    }

    private val sampleTick = Runnable { sample() }

    private val strip = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 185, 66) }
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** The data written into one frame of the video, upright whichever way the phone is held. */
    private fun draw(frame: Frame) {
        val canvas = frame.overlayCanvas
        val size = frame.size
        val r = frame.rotationDegrees
        canvas.save()
        val w: Int
        val h: Int
        when (r) {
            90 -> { canvas.translate(0f, size.height.toFloat()); canvas.rotate(-90f); w = size.height; h = size.width }
            270 -> { canvas.translate(size.width.toFloat(), 0f); canvas.rotate(90f); w = size.height; h = size.width }
            180 -> { canvas.translate(size.width.toFloat(), size.height.toFloat()); canvas.rotate(180f); w = size.width; h = size.height }
            else -> { w = size.width; h = size.height }
        }
        val unit = h / 26f
        text.textSize = unit
        val pad = unit * 0.5f
        val now = SystemClock.elapsedRealtime()
        val l = location?.takeIf { now - locationAt < 5_000L }
        val car = RoadTelemetry.fresh(now)
        val speed = car?.speedKmh?.let { "$it km/h" } ?: l?.takeIf { it.hasSpeed() }?.let { "${(it.speed * 3.6f).toInt()} km/h GPS" } ?: ""
        val place = l?.let { String.format(Locale.US, "%.5f %.5f", it.latitude, it.longitude) } ?: ""
        // A strip along the bottom: time and place on the left, the car on the right.
        canvas.drawRect(0f, h - unit * 1.9f, w.toFloat(), h.toFloat(), strip)
        canvas.drawText(clock.format(Date()), pad, h - unit * 0.6f, text)
        if (place.isNotEmpty()) canvas.drawText(place, pad + text.measureText("0000-00-00 00:00:00 "), h - unit * 0.6f, text)
        if (speed.isNotEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(speed, w - pad, h - unit * 0.6f, text)
            text.textAlign = Paint.Align.LEFT
        }
        // The indicators and the hazards, as arrows beside the speed.
        if (car != null && (car.turnLeft || car.turnRight || car.hazard)) {
            val x = w - pad - text.measureText(speed) - unit * 2.2f
            val y = h - unit * 1.0f
            val left = car.turnLeft || car.hazard
            val right = car.turnRight || car.hazard
            if (left) canvas.drawPath(Path().apply { moveTo(x - unit * 0.9f, y); lineTo(x, y - unit * 0.5f); lineTo(x, y + unit * 0.5f); close() }, amber)
            if (right) canvas.drawPath(Path().apply { moveTo(x + unit * 1.5f, y); lineTo(x + unit * 0.6f, y - unit * 0.5f); lineTo(x + unit * 0.6f, y + unit * 0.5f); close() }, amber)
        }
        // Who recorded it, top left, small.
        text.textSize = unit * 0.7f
        canvas.drawText("Dashwheel Road watch", pad, unit * 0.9f, text)
        canvas.restore()
    }

    // ---- The clips ----

    /** One MP4 from the clip's segments, with its notes; off the recorder's thread, the ring holding the segments until done. */
    private fun build(clip: Clip) {
        _keeping.value = ring.pending.size
        val parts = clip.segments.map { File(ringDir, it.name) }.filter { it.exists() }
        if (parts.isEmpty()) {
            ring.release(clip.atMs)
            return
        }
        val wallAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - clip.atMs)
        val rows = samples.filter { it.elapsedMs in (clip.atMs - BEFORE_MS)..(clip.atMs + AFTER_MS) }.toList()
        worker.execute {
            try {
                val dir = File(RoadClips.dir(app), SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallAt)) + "-" + clip.trigger)
                dir.mkdirs()
                val video = File(dir, "clip.mp4")
                val part = File(dir, "clip.mp4.part")
                val durationMs = concat(parts, part)
                File(dir, "clip.json").writeText(notes(clip, wallAt, durationMs, rows, parts).toString(2))
                // Listed, shared and fetched by its name: it gets the name only once it is whole, notes beside it.
                check(part.renameTo(video)) { "could not rename ${part.name}" }
                trim()
                RoadClips.refresh(app)
            } catch (e: Exception) {
                Log.w(TAG, "could not build the clip", e)
            } finally {
                handler.post {
                    ring.release(clip.atMs)
                    _keeping.value = ring.pending.size
                }
            }
        }
    }

    /** The notes beside the video: the moment, why, and what the phone and the car knew, twice a second. */
    private fun notes(clip: Clip, wallAt: Long, durationMs: Long, rows: List<Sample>, parts: List<File>): JSONObject {
        val first = clip.segments.first().startMs
        return JSONObject()
            .put("app", "Dashwheel Companion")
            .put("trigger", clip.trigger)
            .put("at", wallAt)
            .put("atIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(wallAt)))
            .put("momentOffsetMs", clip.atMs - first)
            .put("durationMs", durationMs)
            .put("noSound", true)
            .put("zoom", RoadStats.zoom.toDouble())
            .put("segments", JSONArray(parts.map { JSONObject().put("name", it.name).put("bytes", it.length()).put("sha256", sha256(it)) }))
            .put("samples", JSONArray(rows.map { s ->
                JSONObject()
                    .put("offsetMs", s.elapsedMs - first)
                    .put("time", s.wallMs)
                    .put("lat", s.lat ?: JSONObject.NULL)
                    .put("lng", s.lng ?: JSONObject.NULL)
                    .put("gpsKmh", s.gpsKmh ?: JSONObject.NULL)
                    .put("carKmh", s.car?.speedKmh ?: JSONObject.NULL)
                    .put("rpm", s.car?.rpm ?: JSONObject.NULL)
                    .put("turnLeft", s.car?.turnLeft ?: JSONObject.NULL)
                    .put("turnRight", s.car?.turnRight ?: JSONObject.NULL)
                    .put("hazard", s.car?.hazard ?: JSONObject.NULL)
                    .put("lights", s.car?.lights ?: JSONObject.NULL)
                    .put("headingDeg", s.headingRad?.let { Math.toDegrees(it.toDouble()) } ?: JSONObject.NULL)
                    .put("signKmh", s.signKmh ?: JSONObject.NULL)
                    .put("pitchDeg", s.pitchDeg ?: JSONObject.NULL)
                    .put("tiltDeg", s.tiltDeg ?: JSONObject.NULL)
            }))
    }

    /** Only the newest [RoadClips.MAX_CLIPS] stay. */
    private fun trim() {
        val dirs = RoadClips.dir(app).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        dirs.dropLast(RoadClips.MAX_CLIPS).forEach { it.deleteRecursively() }
    }

    private fun freeBytes(): Long = runCatching { StatFs(app.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        private const val TAG = "RoadRecorder"
        /** The recorder running now, for the car's "keep a clip" and Road watch's warnings. */
        @Volatile var active: RoadRecorder? = null
            private set

        const val SEGMENT_MS = 20_000L
        const val RING_MS = 120_000L
        const val BEFORE_MS = 30_000L
        const val AFTER_MS = 30_000L
        const val SAMPLE_MS = 500L
        /** 1080p at this rate: about 45 MB a minute, plates readable. */
        const val BIT_RATE = 6_000_000
        const val MIN_FREE_BYTES = 400L * 1024 * 1024
        /** Paused for want of room: how long until the room is looked at again. */
        const val RETRY_FULL_MS = 30_000L

        /**
         * [parts], recorded one after another by the same encoder, joined into
         * [out] without decoding: the video track's samples copied with their
         * times moved on. Returns the length in ms.
         */
        fun concat(parts: List<File>, out: File): Long {
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var offsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(4 shl 20)
            val info = MediaCodec.BufferInfo()
            try {
                for (part in parts) {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(part.path)
                        val index = (0 until extractor.trackCount).firstOrNull {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                        } ?: continue
                        val format = extractor.getTrackFormat(index)
                        extractor.selectTrack(index)
                        if (track < 0) {
                            track = muxer.addTrack(format)
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                            muxer.start()
                        }
                        var last = offsetUs
                        while (true) {
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0) break
                            info.offset = 0
                            info.size = n
                            info.presentationTimeUs = extractor.sampleTime + offsetUs
                            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            muxer.writeSampleData(track, buffer, info)
                            last = max(last, info.presentationTimeUs)
                            extractor.advance()
                        }
                        // The next part starts a frame after the last one ended.
                        offsetUs = last + 33_333L
                    } finally {
                        extractor.release()
                    }
                }
            } finally {
                runCatching { if (track >= 0) muxer.stop() }
                muxer.release()
            }
            return offsetUs / 1000
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
