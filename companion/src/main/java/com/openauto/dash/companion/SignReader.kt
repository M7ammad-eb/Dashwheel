package com.openauto.dash.companion

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openauto.dash.roadwatch.SignConfirm
import com.openauto.dash.roadwatch.SignSpot
import com.openauto.dash.roadwatch.Upright
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the number on a round sign [com.openauto.dash.roadwatch.SignFinder]
 * found: its middle copied out of the camera's frame, made bigger, and read
 * first by [SignClassifier] (a limit, the end of one, another sign, no sign;
 * under a millisecond), then, when that isn't sure, by ML Kit's text
 * recognizer on the phone, no network. One sign at a time: while one is
 * being read, the next waits for a later frame. What it reads goes to
 * [RoadSigns], believed once read twice ([SignConfirm]). An end sign needs
 * no reading: seen twice ([sawEnd], or the classifier's word), it ends the limit.
 */
class SignReader(context: Context, private val dir: File) {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val classifier = runCatching { SignClassifier(context) }.getOrNull()
    private val busy = AtomicBoolean(false)
    private val confirm = SignConfirm()
    private val saver = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    /** Reads [spot] in the RGBA frame [rgba] (see [Upright.index]); the pixels are copied before this returns. */
    fun read(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, spot: SignSpot) {
        if (!busy.compareAndSet(false, true)) return
        val bitmap = middle(rgba, width, height, rowStride, rotation, spot)
        val reading = classifier?.let { runCatching { it.read(bitmap) }.getOrNull() }
        if (reading != null && reading.sure >= SignClassifier.SURE) {
            // Sure: a limit counts, and the rest (the end of a limit, another sign, no sign) needs no reading.
            keep(bitmap, "c" + reading.label)
            reading.kmh?.let { kmh -> synchronized(confirm) { confirm.read(kmh, SystemClock.elapsedRealtime()) }?.let { RoadSigns.seen(it) } }
            if (reading.label == "end") sawEnd()
            busy.set(false)
            return
        }
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                // Read after close (the screen left meanwhile): nobody to tell.
                if (closed) return@addOnSuccessListener
                keep(bitmap, text.text)
                val kmh = SignConfirm.limitIn(text.text) ?: return@addOnSuccessListener
                synchronized(confirm) { confirm.read(kmh, SystemClock.elapsedRealtime()) }?.let { RoadSigns.seen(it) }
            }
            .addOnFailureListener { if (!closed) keep(bitmap, "failed") }
            .addOnCompleteListener { busy.set(false) }
    }

    /** The finder saw the sign that ends a limit on this frame; twice within a few seconds is believed. */
    fun sawEnd() {
        synchronized(confirm) { confirm.read(SignConfirm.END, SystemClock.elapsedRealtime()) }?.let { RoadSigns.ended() }
    }

    /**
     * The face read and what was read on it, kept as a picture ([KEPT] at
     * most, the oldest going): the driver's computer fetches them (GET /signs)
     * to see what was misread.
     */
    private fun keep(bitmap: Bitmap, read: String) {
        // A reading that finishes as close shuts the saver is refused by it: the face goes unkept, the app stays up.
        runCatching {
            saver.execute {
                runCatching {
                    dir.mkdirs()
                    val label = read.filter { it.isLetterOrDigit() }.take(12).ifEmpty { "nothing" }
                    File(dir, "${System.currentTimeMillis()}-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    dir.listFiles()?.sortedByDescending { it.name }?.drop(KEPT)?.forEach { it.delete() }
                }
                bitmap.recycle()
            }
        }.onFailure { bitmap.recycle() }
    }

    fun close() {
        closed = true
        client.close()
        classifier?.close()
        saver.shutdown()
    }

    /** The sign's face inside its ring, [READ_SIDE] pixels across: the recognizer reads small text badly. */
    private fun middle(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, spot: SignSpot): Bitmap {
        val uw = Upright.width(width, height, rotation)
        val uh = Upright.height(width, height, rotation)
        val b = spot.box
        val cx = (b.left + b.right) / 2 * uw
        val cy = (b.top + b.bottom) / 2 * uh
        val half = spot.sidePx * FACE / 2
        val pixels = IntArray(READ_SIDE * READ_SIDE)
        for (y in 0 until READ_SIDE) for (x in 0 until READ_SIDE) {
            val u = (cx - half + x * 2 * half / READ_SIDE).toInt().coerceIn(0, uw - 1)
            val v = (cy - half + y * 2 * half / READ_SIDE).toInt().coerceIn(0, uh - 1)
            val i = Upright.index(u, v, width, height, rowStride, rotation)
            pixels[y * READ_SIDE + x] = (0xFF shl 24) or ((rgba.get(i).toInt() and 0xFF) shl 16) or
                ((rgba.get(i + 1).toInt() and 0xFF) shl 8) or (rgba.get(i + 2).toInt() and 0xFF)
        }
        return Bitmap.createBitmap(pixels, READ_SIDE, READ_SIDE, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Where the faces read are kept. */
        fun dir(context: Context): File = File(context.cacheDir, "road_signs")

        private const val KEPT = 120
        private const val READ_SIDE = 160
        /** The face inside the ring, a share of the sign's width: the number with a little white round it. */
        private const val FACE = 0.74f
    }
}

/**
 * The speed limit last read off a sign, for the car ([com.openauto.dash.link.RoadScene.signKmh]),
 * the recorder's notes and the speed report; and the end sign last passed,
 * which takes the limit away ([com.openauto.dash.link.RoadScene.signEnd]).
 */
object RoadSigns {
    @Volatile var kmh: Int? = null
        private set
    /** When it was read (elapsedRealtime, ms). */
    @Volatile var at = 0L
        private set
    /** When the sign ending the limit was last seen (elapsedRealtime, ms), 0 for never. */
    @Volatile var endAt = 0L
        private set

    fun seen(kmh: Int) {
        this.kmh = kmh
        at = SystemClock.elapsedRealtime()
        // A limit read after the end sign: the end is history, and mustn't take the new limit away.
        endAt = 0L
    }

    /** The limit ends here: none from a sign until the next one. */
    fun ended() {
        kmh = null
        at = 0L
        endAt = SystemClock.elapsedRealtime()
    }

    /** The limit read in the last [withinMs], or null. */
    fun fresh(withinMs: Long, now: Long = SystemClock.elapsedRealtime()): Int? = kmh?.takeIf { now - at < withinMs }

    /** An end sign was seen in the last [withinMs]. */
    fun endFresh(withinMs: Long, now: Long = SystemClock.elapsedRealtime()): Boolean = endAt != 0L && now - endAt < withinMs

    fun clear() {
        kmh = null
        at = 0L
        endAt = 0L
    }
}
