package com.example.navisense

import android.graphics.RectF
import java.util.Locale

/**
 * An obstacle tracked across multiple image frames with smoothed distance and temporal metrics.
 */
data class TrackedObstacle(
    val trackId: Int,
    val label: String,
    val zone: Zone,
    val boundingBox: RectF,
    val score: Float,
    val rawDistanceMeters: Float,
    val smoothedDistanceMeters: Float,
    val consecutiveFrames: Int,
    val missedFrames: Int,
    val isUnreliableOrLarge: Boolean,
    val isLargeBox: Boolean = false
) {
    /**
     * Requirement 1: An obstacle may trigger an alert only after it is detected
     * in at least 3 consecutive frames with the same label and similar zone.
     */
    val isAlertEligible: Boolean
        get() = consecutiveFrames >= MIN_CONSECUTIVE_FRAMES

    companion object {
        const val MIN_CONSECUTIVE_FRAMES = 3
    }
}

/**
 * Manages obstacle tracking across consecutive camera frames:
 * - Enforces class-specific score thresholds (0.6 for vehicles, 0.5 for others)
 * - Requires 3 consecutive frames before an obstacle qualifies for an alert
 * - Drops tracks that disappear for a few frames (maxMissedFrames = 3)
 * - Smooths distance estimates using median of the last 5 frames to absorb walking jumps
 * - Performs sanity checks (box height > 80% frame height or extreme vehicle aspect ratio)
 */
class ObstacleTracker(
    val vehicleMinScore: Float = VEHICLE_MIN_SCORE_DEFAULT,
    val defaultMinScore: Float = DEFAULT_MIN_SCORE_DEFAULT,
    val minConsecutiveFrames: Int = TrackedObstacle.MIN_CONSECUTIVE_FRAMES,
    val maxMissedFrames: Int = MAX_MISSED_FRAMES_DEFAULT,
    val maxDistanceHistory: Int = MAX_DISTANCE_HISTORY_DEFAULT
) {

    private var nextTrackId = 1

    private class TrackState(
        val trackId: Int,
        var label: String,
        var zone: Zone,
        var boundingBox: RectF,
        var score: Float,
        var rawDistance: Float,
        var consecutiveFrames: Int = 1,
        var missedFrames: Int = 0,
        var isUnreliableOrLarge: Boolean = false,
        var isLargeBox: Boolean = false,
        val distanceHistory: ArrayDeque<Float> = ArrayDeque()
    ) {
        fun addDistance(dist: Float, maxSize: Int) {
            if (distanceHistory.size >= maxSize) {
                distanceHistory.removeFirst()
            }
            distanceHistory.addLast(dist)
        }

        fun medianDistance(): Float {
            if (distanceHistory.isEmpty()) return rawDistance
            val sorted = distanceHistory.sorted()
            val size = sorted.size
            return if (size % 2 == 1) {
                sorted[size / 2]
            } else {
                (sorted[size / 2 - 1] + sorted[size / 2]) / 2f
            }
        }
    }

    private val tracks = mutableListOf<TrackState>()

    /**
     * Updates active tracks with the latest frame detections.
     */
    fun update(
        detections: List<Detection>,
        frameWidthPx: Int = 0,
        frameHeightPx: Int = 0
    ): List<TrackedObstacle> {
        // 1. Filter detections by class-specific score thresholds (Requirement 3)
        val filtered = detections.filter { detection ->
            val normLabel = detection.label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in VEHICLE_CLASSES
            val threshold = if (isVehicle) vehicleMinScore else defaultMinScore
            detection.score >= threshold
        }

        val matchedTracks = mutableSetOf<TrackState>()
        val matchedDetections = mutableSetOf<Detection>()

        // 2. Associate detections with existing tracks
        for (detection in filtered) {
            val normLabel = detection.label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in VEHICLE_CLASSES

            val bestTrack = tracks.firstOrNull { track ->
                track !in matchedTracks &&
                    track.label.equals(normLabel, ignoreCase = true) &&
                    isSimilarZone(track.zone, detection.zone)
            }

            val box = detection.boundingBox
            val boxHeight = if (detection.boxHeightPx > 0f) detection.boxHeightPx else kotlin.math.abs(box.bottom - box.top)
            val boxWidth = if (detection.boxWidthPx > 0f) detection.boxWidthPx else kotlin.math.abs(box.right - box.left)
            val effectiveHeight = if (detection.frameHeightPx > 0) detection.frameHeightPx else frameHeightPx

            val isLargeBox = effectiveHeight > 0 && boxHeight > LARGE_BOX_FRAME_HEIGHT_RATIO * effectiveHeight
            val isExtremeRatio = isVehicle && boxHeight > 0f && (
                (boxWidth / boxHeight) > MAX_VEHICLE_ASPECT_RATIO || (boxWidth / boxHeight) < MIN_VEHICLE_ASPECT_RATIO
            )

            // Requirement: Apply "unreliable or large obstacle" suppression ONLY to vehicle classes
            val isUnreliable = isVehicle && (isLargeBox || isExtremeRatio || detection.isUnreliableOrLarge)

            // Requirement: For people, animals, furniture and other whitelisted classes,
            // a box taller than 80% of the frame height means the object is very close,
            // so use the distance clamped to a minimum of 0.5m.
            val effectiveDist = if (!isVehicle && isLargeBox) {
                detection.distanceMeters.coerceAtLeast(MIN_CLAMPED_DISTANCE_METERS)
            } else {
                detection.distanceMeters
            }

            if (bestTrack != null) {
                matchedTracks.add(bestTrack)
                matchedDetections.add(detection)
                bestTrack.label = normLabel
                bestTrack.zone = detection.zone
                bestTrack.boundingBox = detection.boundingBox
                bestTrack.score = detection.score
                bestTrack.rawDistance = effectiveDist
                bestTrack.consecutiveFrames++
                bestTrack.missedFrames = 0
                bestTrack.isUnreliableOrLarge = isUnreliable
                bestTrack.isLargeBox = isLargeBox
                bestTrack.addDistance(effectiveDist, maxDistanceHistory)
            } else {
                val newTrack = TrackState(
                    trackId = nextTrackId++,
                    label = normLabel,
                    zone = detection.zone,
                    boundingBox = detection.boundingBox,
                    score = detection.score,
                    rawDistance = effectiveDist,
                    consecutiveFrames = 1,
                    missedFrames = 0,
                    isUnreliableOrLarge = isUnreliable,
                    isLargeBox = isLargeBox
                ).apply {
                    addDistance(effectiveDist, maxDistanceHistory)
                }
                tracks.add(newTrack)
                matchedTracks.add(newTrack)
            }
        }

        // 3. Update missed frames and prune lost tracks (Requirement 1)
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track !in matchedTracks) {
                track.missedFrames++
                track.consecutiveFrames = 0 // Streak broken
                if (track.missedFrames >= maxMissedFrames) {
                    iterator.remove() // Dropped after disappearing for a few frames
                }
            }
        }

        // 4. Return current tracked obstacle states
        return tracks.map { track ->
            val normLabel = track.label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in VEHICLE_CLASSES
            val smoothedDist = if (!isVehicle && track.isLargeBox) {
                track.medianDistance().coerceAtLeast(MIN_CLAMPED_DISTANCE_METERS)
            } else {
                track.medianDistance()
            }
            TrackedObstacle(
                trackId = track.trackId,
                label = track.label,
                zone = track.zone,
                boundingBox = track.boundingBox,
                score = track.score,
                rawDistanceMeters = track.rawDistance,
                smoothedDistanceMeters = smoothedDist,
                consecutiveFrames = track.consecutiveFrames,
                missedFrames = track.missedFrames,
                isUnreliableOrLarge = track.isUnreliableOrLarge,
                isLargeBox = track.isLargeBox
            )
        }
    }

    private fun isSimilarZone(z1: Zone, z2: Zone): Boolean {
        if (z1 == z2) return true
        return (z1 == Zone.LEFT && z2 == Zone.CENTER) ||
               (z1 == Zone.CENTER && z2 == Zone.LEFT) ||
               (z1 == Zone.CENTER && z2 == Zone.RIGHT) ||
               (z1 == Zone.RIGHT && z2 == Zone.CENTER)
    }

    fun clear() {
        tracks.clear()
        nextTrackId = 1
    }

    companion object {
        const val VEHICLE_MIN_SCORE_DEFAULT = 0.60f
        const val DEFAULT_MIN_SCORE_DEFAULT = 0.50f
        const val MAX_MISSED_FRAMES_DEFAULT = 3
        const val MAX_DISTANCE_HISTORY_DEFAULT = 5

        const val LARGE_BOX_FRAME_HEIGHT_RATIO = 0.80f
        const val MIN_CLAMPED_DISTANCE_METERS = 0.50f
        const val MAX_VEHICLE_ASPECT_RATIO = 2.80f
        const val MIN_VEHICLE_ASPECT_RATIO = 0.35f

        val VEHICLE_CLASSES = setOf("car", "truck", "bus", "motorcycle")
    }
}
