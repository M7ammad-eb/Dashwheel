package com.openauto.dash.roadwatch

import com.openauto.dash.link.RoadScene
import kotlin.math.abs

/*
 * When Road watch speaks up, from what the phone's camera sees ([RoadScene])
 * and what the car says of itself (its speed, its indicators). Each warning
 * waits until it is sure (a few frames, a few seconds), stays up long enough
 * to be read, and doesn't nag: a wheel on the line is told once until the car
 * is back in its lane, following too close once in a while.
 *
 * The limits follow what cars' own systems do: the collision warning from
 * 30 km/h at under 2.4 s to impact (closer in town), the lane from 60 km/h
 * and never with an indicator on, following too close under 0.8 s behind
 * from 50 km/h.
 */

/** The warnings, the most urgent first. */
enum class RoadWarning {
    /** The car ahead is coming up fast: brake. */
    COLLISION,
    /** A wheel on the lane's left line, without an indicator. */
    LANE_LEFT,
    LANE_RIGHT,
    /** Following too close for the speed. */
    TOO_CLOSE,
    /** Standing in traffic, and the car ahead has driven off. */
    MOVED_OFF
}

/**
 * [lanes]: the lane warnings are wanted; [movedOff]: the word when traffic
 * moves on. The collision warning and following too close are always on
 * while Road watch runs: they are what it is for.
 */
class RoadRules(var lanes: Boolean = true, var movedOff: Boolean = true) {
    private var collisionHits = 0
    private var collisionUntil = 0L

    private var closeSince = -1L
    private var closeUntil = 0L
    private var closeNextAt = 0L

    private class Offset(val at: Long, val value: Float)
    private val offsets = ArrayDeque<Offset>()
    private var laneSide: RoadWarning? = null
    private var laneUntil = 0L
    private var leftArmed = true
    private var rightArmed = true
    private var indicatedAt = Long.MIN_VALUE / 2

    private var driven = false
    private var stoppedSince = -1L
    private var nearestStopped: Float? = null
    private var lastStoppedLead: Float? = null
    private var steadySince = 0L
    private var movedOffTold = false
    private var movedOffUntil = 0L

    /**
     * The warning to show at [now] (ms) with [scene] just in. [speedKmh]: the
     * car's own speed, else the phone's in the scene is used; [indicating]: an
     * indicator (or the hazards) is on.
     */
    fun update(scene: RoadScene, speedKmh: Float?, indicating: Boolean, now: Long): RoadWarning? {
        val kmh = speedKmh ?: scene.speedKmh
        // No speed this frame (the car's reading missed, the GPS blinked): nothing new is judged, but
        // a warning already up stays its time, and what was counted so far (that the car has driven)
        // is kept; only the stream's end clears.
        if (kmh == null) return standing(now)
        if (indicating) indicatedAt = now
        if (kmh >= DRIVEN_KMH) driven = true
        collision(scene, kmh, now)
        tooClose(scene, kmh, now)
        lane(scene, kmh, now)
        movedOff(scene, kmh, now)
        return standing(now)
    }

    /** The most urgent warning still up at [now]. */
    private fun standing(now: Long): RoadWarning? =
        when {
            now < collisionUntil -> RoadWarning.COLLISION
            laneSide != null && now < laneUntil -> laneSide
            now < closeUntil -> RoadWarning.TOO_CLOSE
            now < movedOffUntil -> RoadWarning.MOVED_OFF
            else -> null
        }

    /** Road watch stopped: nothing stands, and nothing half-counted carries over. */
    fun clear() {
        collisionHits = 0
        collisionUntil = 0L
        closeSince = -1L
        closeUntil = 0L
        closeNextAt = 0L
        offsets.clear()
        laneSide = null
        laneUntil = 0L
        leftArmed = true
        rightArmed = true
        indicatedAt = Long.MIN_VALUE / 2
        driven = false
        stoppedSince = -1L
        nearestStopped = null
        lastStoppedLead = null
        steadySince = 0L
        movedOffTold = false
        movedOffUntil = 0L
    }

    private fun collision(scene: RoadScene, kmh: Float, now: Long) {
        val ttc = scene.ttcS
        val closing = scene.closingMps
        val met = ttc != null && closing != null &&
            closing >= MIN_CLOSING_MPS &&
            // Closing faster than the car drives: the boxes jumped, not the car ahead reversing.
            closing <= kmh / 3.6f + CLOSING_SLACK_MPS &&
            (kmh >= COLLISION_KMH && ttc <= COLLISION_TTC_S || kmh >= TOWN_KMH && ttc <= TOWN_TTC_S)
        collisionHits = if (met) collisionHits + 1 else 0
        if (collisionHits >= COLLISION_FRAMES) collisionUntil = now + COLLISION_HOLD_MS
    }

    private fun tooClose(scene: RoadScene, kmh: Float, now: Long) {
        val lead = scene.leadM
        val headway = if (kmh >= CLOSE_KMH && lead != null) lead / (kmh / 3.6f) else null
        if (headway == null || headway >= CLOSE_HEADWAY_S) {
            closeSince = -1L
            return
        }
        if (closeSince < 0) closeSince = now
        if (now - closeSince >= CLOSE_FOR_MS && now >= closeNextAt) {
            closeUntil = now + CLOSE_SHOW_MS
            closeNextAt = now + CLOSE_AGAIN_MS
        }
    }

    private fun lane(scene: RoadScene, kmh: Float, now: Long) {
        val offset = scene.offset
        if (!lanes || kmh < LANE_KMH || offset == null || scene.lines == 0) {
            offsets.clear()
            laneSide = null
            return
        }
        offsets.addLast(Offset(now, offset))
        while (offsets.size > 1 && now - offsets.first().at > DRIFT_WINDOW_MS) offsets.removeFirst()
        if (abs(offset) < REARM_OFFSET) {
            leftArmed = true
            rightArmed = true
        }
        // Back in the lane, or over into the next one (the lines swapped sides): told enough.
        laneSide?.let { side ->
            val back = abs(offset) < BACK_OFFSET ||
                side == RoadWarning.LANE_LEFT && offset > 0 || side == RoadWarning.LANE_RIGHT && offset < 0
            if (back) laneSide = null
        }
        val first = offsets.first()
        val drifting = now - first.at >= DRIFT_MIN_MS && abs(offset) - abs(first.value) >= DRIFT
        val signalled = now - indicatedAt < INDICATOR_GRACE_MS
        // Over the line in the last few scenes running, not in one: a single frame's misreading is not a drift.
        val overRight = offsets.size >= LINE_SCENES && offsets.takeLast(LINE_SCENES).all { it.value >= LINE_OFFSET }
        val overLeft = offsets.size >= LINE_SCENES && offsets.takeLast(LINE_SCENES).all { it.value <= -LINE_OFFSET }
        if (!drifting || signalled) return
        if (overRight && rightArmed) {
            rightArmed = false
            laneSide = RoadWarning.LANE_RIGHT
            laneUntil = now + LANE_SHOW_MS
        } else if (overLeft && leftArmed) {
            leftArmed = false
            laneSide = RoadWarning.LANE_LEFT
            laneUntil = now + LANE_SHOW_MS
        }
    }

    private fun movedOff(scene: RoadScene, kmh: Float, now: Long) {
        if (kmh > STOPPED_KMH) {
            // Driving again: the driver saw it.
            stoppedSince = -1L
            nearestStopped = null
            lastStoppedLead = null
            movedOffUntil = 0L
            return
        }
        if (stoppedSince < 0) {
            stoppedSince = now
            movedOffTold = false
        }
        // A queue is something the car drove into: parked at the kerb at the start of a drive, the van 12 m ahead
        // "moved off" as the phone was placed in its holder (2026-10-27).
        if (!movedOff || !driven) return
        val lead = scene.leadM ?: return
        // A reading that jumps from the frame before, or has only just settled, is a box's flicker to the
        // car beyond, not the car ahead (4.4 m read 9.7 m for one frame at a red light; six frames at 6-7 m
        // before the cars at 12 m in a car park, 2026-10-27): neither the queue nor a move.
        val last = lastStoppedLead
        lastStoppedLead = lead
        if (last == null || abs(lead - last) > STEADY_M) steadySince = now
        if (now - steadySince < STEADY_FOR_MS) return
        val nearest = nearestStopped
        if (nearest == null || lead < nearest) nearestStopped = lead
        val near = nearestStopped ?: return
        if (!movedOffTold && now - stoppedSince >= STOPPED_FOR_MS && near <= QUEUE_M && lead >= near + MOVED_M) {
            movedOffTold = true
            movedOffUntil = now + MOVED_OFF_SHOW_MS
        }
    }

    private companion object {
        const val MIN_CLOSING_MPS = 2f
        const val CLOSING_SLACK_MPS = 3f
        const val COLLISION_KMH = 30f
        const val COLLISION_TTC_S = 2.4f
        const val TOWN_KMH = 8f
        const val TOWN_TTC_S = 1.5f
        /** Frames running (about a fifth of a second): one bad box doesn't sound the alarm. */
        const val COLLISION_FRAMES = 2
        const val COLLISION_HOLD_MS = 2_000L

        const val CLOSE_KMH = 50f
        const val CLOSE_HEADWAY_S = 0.8f
        const val CLOSE_FOR_MS = 3_000L
        const val CLOSE_SHOW_MS = 4_000L
        const val CLOSE_AGAIN_MS = 30_000L

        const val LANE_KMH = 60f
        /** A wheel on the line, for a car of usual width in a usual lane. */
        const val LINE_OFFSET = 0.22f
        /** Scenes running (about a third of a second) the car must be over the line for. */
        const val LINE_SCENES = 3
        const val BACK_OFFSET = 0.15f
        const val REARM_OFFSET = 0.12f
        /** Moving out by this much over the last half second: drifting, not driving along the line. */
        const val DRIFT = 0.03f
        const val DRIFT_MIN_MS = 300L
        const val DRIFT_WINDOW_MS = 600L
        const val LANE_SHOW_MS = 2_500L
        /** An indicator just turned off: the lane change is still under way. */
        const val INDICATOR_GRACE_MS = 2_000L

        const val STOPPED_KMH = 2f
        /** Driven this fast since Road watch started: the next stop can be a queue. */
        const val DRIVEN_KMH = 10f
        const val STOPPED_FOR_MS = 3_000L
        /** Queued behind it: no further than this. */
        const val QUEUE_M = 12f
        const val MOVED_M = 4f
        /** Frame to frame, a car's distance moves less than this; a jump is another box. */
        const val STEADY_M = 1f
        /** Read without a jump for this long: the car, not a box that flickered. */
        const val STEADY_FOR_MS = 300L
        const val MOVED_OFF_SHOW_MS = 3_000L
    }
}
