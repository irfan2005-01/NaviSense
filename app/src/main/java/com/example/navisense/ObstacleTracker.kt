package com.example.navisense

import android.graphics.RectF
import android.util.Log
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
 * Event listener for tracking lifecycle and association diagnostics.
 */
interface TrackingEventListener {
    fun onTrackCreated(trackId: Int, label: String, boundingBox: RectF) {}
    fun onDetectionAssociated(trackId: Int, detection: Detection, iou: Float, displacement: Float) {}
    fun onTrackMissed(trackId: Int, missedFrames: Int) {}
    fun onTrackExpired(trackId: Int, label: String) {}
}

/**
 * Manages obstacle tracking across consecutive camera frames:
 * - Assigns stable, unique track IDs to distinct objects.
 * - Associates detections across frames using IoU (Intersection over Union) matching and spatial continuity fallback.
 * - Enforces deterministic, one-to-one assignment (no shared or duplicate track assignments).
 * - Enforces class-specific score thresholds (0.6 for vehicles, 0.5 for others).
 * - Requires 3 consecutive frames before an obstacle qualifies for an alert.
 * - Smooths distance estimates using median of the last 5 frames to absorb walking jumps.
 * - Preserves track identity during short detection interruptions (up to maxMissedFrames).
 * - Prunes stale tracks when missed frames threshold is exceeded to avoid unbounded memory growth.
 * - Performs sanity checks (box height > 80% frame height or extreme vehicle aspect ratio).
 */
class ObstacleTracker(
    val vehicleMinScore: Float = VEHICLE_MIN_SCORE_DEFAULT,
    val defaultMinScore: Float = DEFAULT_MIN_SCORE_DEFAULT,
    val minConsecutiveFrames: Int = TrackedObstacle.MIN_CONSECUTIVE_FRAMES,
    val maxMissedFrames: Int = MAX_MISSED_FRAMES_DEFAULT,
    val maxDistanceHistory: Int = MAX_DISTANCE_HISTORY_DEFAULT,
    val iouMatchThreshold: Float = DEFAULT_IOU_MATCH_THRESHOLD,
    val maxCenterDisplacementRatio: Float = DEFAULT_MAX_CENTER_DISPLACEMENT_RATIO,
    val maxDistanceContinuityMeters: Float = DEFAULT_MAX_DISTANCE_CONTINUITY_METERS,
    var eventListener: TrackingEventListener? = null
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
     * Active track IDs currently retained in memory.
     */
    val activeTrackIds: Set<Int>
        get() = tracks.map { it.trackId }.toSet()

    /**
     * Updates active tracks with the latest frame detections.
     */
    fun update(
        detections: List<Detection>,
        frameWidthPx: Int = 0,
        frameHeightPx: Int = 0
    ): List<TrackedObstacle> {
        // 1. Filter detections by class-specific score thresholds
        val filtered = detections.filter { detection ->
            val normLabel = detection.label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in VEHICLE_CLASSES
            val threshold = if (isVehicle) vehicleMinScore else defaultMinScore
            detection.score >= threshold
        }

        val matchedTrackIds = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        data class MatchData(
            val effectiveDist: Float,
            val isUnreliable: Boolean,
            val isLargeBox: Boolean
        )

        fun computeMatchData(detection: Detection): MatchData {
            val box = detection.boundingBox
            val boxHeight = if (detection.boxHeightPx > 0f) detection.boxHeightPx else kotlin.math.abs(box.bottom - box.top)
            val boxWidth = if (detection.boxWidthPx > 0f) detection.boxWidthPx else kotlin.math.abs(box.right - box.left)
            val effectiveHeight = if (detection.frameHeightPx > 0) detection.frameHeightPx else frameHeightPx

            val normLabel = detection.label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in VEHICLE_CLASSES
            val isLarge = effectiveHeight > 0 && boxHeight > LARGE_BOX_FRAME_HEIGHT_RATIO * effectiveHeight
            val isExtremeRatio = isVehicle && boxHeight > 0f && (
                (boxWidth / boxHeight) > MAX_VEHICLE_ASPECT_RATIO || (boxWidth / boxHeight) < MIN_VEHICLE_ASPECT_RATIO
            )

            val unreliable = isVehicle && (isLarge || isExtremeRatio || detection.isUnreliableOrLarge)
            val dist = if (!isVehicle && isLarge) {
                detection.distanceMeters.coerceAtLeast(MIN_CLAMPED_DISTANCE_METERS)
            } else {
                detection.distanceMeters
            }
            return MatchData(dist, unreliable, isLarge)
        }

        // 2. Phase 1 Association: Greedy IoU matching on compatible classes
        data class IoUCandidate(
            val track: TrackState,
            val detectionIndex: Int,
            val iou: Float
        )

        val iouCandidates = mutableListOf<IoUCandidate>()
        for (track in tracks) {
            for ((detIndex, detection) in filtered.withIndex()) {
                val normLabel = detection.label.trim().lowercase(Locale.US)
                if (track.label.equals(normLabel, ignoreCase = true)) {
                    val iou = calculateIoU(track.boundingBox, detection.boundingBox)
                    if (iou >= iouMatchThreshold) {
                        iouCandidates.add(IoUCandidate(track, detIndex, iou))
                    }
                }
            }
        }

        // Sort descending by IoU for greedy highest-overlap assignment
        iouCandidates.sortByDescending { it.iou }
        for (candidate in iouCandidates) {
            val track = candidate.track
            val detIndex = candidate.detectionIndex
            if (track.trackId !in matchedTrackIds && detIndex !in matchedDetectionIndices) {
                matchedTrackIds.add(track.trackId)
                matchedDetectionIndices.add(detIndex)

                val detection = filtered[detIndex]
                val (effectiveDist, isUnreliable, isLargeBox) = computeMatchData(detection)

                track.label = detection.label.trim().lowercase(Locale.US)
                track.zone = detection.zone
                track.boundingBox = detection.boundingBox
                track.score = detection.score
                track.rawDistance = effectiveDist
                track.consecutiveFrames++
                track.missedFrames = 0
                track.isUnreliableOrLarge = isUnreliable
                track.isLargeBox = isLargeBox
                track.addDistance(effectiveDist, maxDistanceHistory)

                Log.d(TAG, "IoU Match: Track ${track.trackId} ('${track.label}') with Det #$detIndex (IoU=${String.format(Locale.US, "%.2f", candidate.iou)})")
                eventListener?.onDetectionAssociated(track.trackId, detection, candidate.iou, 0f)
            }
        }

        // 3. Phase 2 Association: Spatial center displacement fallback
        data class SpatialCandidate(
            val track: TrackState,
            val detectionIndex: Int,
            val score: Float,
            val displacement: Float
        )

        val spatialCandidates = mutableListOf<SpatialCandidate>()
        for (track in tracks) {
            if (track.trackId in matchedTrackIds) continue
            for ((detIndex, detection) in filtered.withIndex()) {
                if (detIndex in matchedDetectionIndices) continue
                val normLabel = detection.label.trim().lowercase(Locale.US)
                if (!track.label.equals(normLabel, ignoreCase = true)) continue

                val box1 = track.boundingBox
                val box2 = detection.boundingBox
                val hasArea = (box1.right > box1.left && box1.bottom > box1.top) &&
                    (box2.right > box2.left && box2.bottom > box2.top)

                if (hasArea) {
                    val disp = calculateNormalizedCenterDisplacement(box1, box2, frameWidthPx, frameHeightPx)
                    val isZoneCompatible = isSimilarZone(track.zone, detection.zone)

                    if (disp <= maxCenterDisplacementRatio && isZoneCompatible) {
                        val distDiff = kotlin.math.abs(track.rawDistance - detection.distanceMeters)
                        val distBonus = if (track.rawDistance > 0f && detection.distanceMeters > 0f && distDiff <= maxDistanceContinuityMeters) 0.2f else 0.0f
                        val score = (1.0f - disp) + distBonus
                        spatialCandidates.add(SpatialCandidate(track, detIndex, score, disp))
                    }
                } else {
                    // Fallback for synthetic/degenerate boxes
                    if (isSimilarZone(track.zone, detection.zone)) {
                        val distDiff = kotlin.math.abs(track.rawDistance - detection.distanceMeters)
                        val distBonus = if (track.rawDistance > 0f && detection.distanceMeters > 0f && distDiff <= maxDistanceContinuityMeters) 0.2f else 0.0f
                        spatialCandidates.add(SpatialCandidate(track, detIndex, 0.5f + distBonus, 0f))
                    }
                }
            }
        }

        spatialCandidates.sortByDescending { it.score }
        for (candidate in spatialCandidates) {
            val track = candidate.track
            val detIndex = candidate.detectionIndex
            if (track.trackId !in matchedTrackIds && detIndex !in matchedDetectionIndices) {
                matchedTrackIds.add(track.trackId)
                matchedDetectionIndices.add(detIndex)

                val detection = filtered[detIndex]
                val (effectiveDist, isUnreliable, isLargeBox) = computeMatchData(detection)

                track.label = detection.label.trim().lowercase(Locale.US)
                track.zone = detection.zone
                track.boundingBox = detection.boundingBox
                track.score = detection.score
                track.rawDistance = effectiveDist
                track.consecutiveFrames++
                track.missedFrames = 0
                track.isUnreliableOrLarge = isUnreliable
                track.isLargeBox = isLargeBox
                track.addDistance(effectiveDist, maxDistanceHistory)

                Log.d(TAG, "Spatial Match: Track ${track.trackId} ('${track.label}') with Det #$detIndex (disp=${String.format(Locale.US, "%.2f", candidate.displacement)})")
                eventListener?.onDetectionAssociated(track.trackId, detection, 0f, candidate.displacement)
            }
        }

        // 4. Phase 3: Create new tracks for unmatched detections
        for ((detIndex, detection) in filtered.withIndex()) {
            if (detIndex !in matchedDetectionIndices) {
                val normLabel = detection.label.trim().lowercase(Locale.US)
                val (effectiveDist, isUnreliable, isLargeBox) = computeMatchData(detection)

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
                matchedTrackIds.add(newTrack.trackId)
                Log.d(TAG, "Created new Track ${newTrack.trackId} ('${newTrack.label}', zone=${newTrack.zone}, dist=${effectiveDist}m)")
                eventListener?.onTrackCreated(newTrack.trackId, newTrack.label, newTrack.boundingBox)
            }
        }

        // 5. Phase 4: Update missed frames and prune lost tracks
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track.trackId !in matchedTrackIds) {
                track.missedFrames++
                track.consecutiveFrames = 0 // Streak broken
                eventListener?.onTrackMissed(track.trackId, track.missedFrames)
                Log.d(TAG, "Track ${track.trackId} ('${track.label}') missed frame (${track.missedFrames}/$maxMissedFrames)")
                if (track.missedFrames >= maxMissedFrames) {
                    Log.i(TAG, "Track ${track.trackId} ('${track.label}') expired after $maxMissedFrames missed frames. Dropped.")
                    eventListener?.onTrackExpired(track.trackId, track.label)
                    iterator.remove()
                }
            }
        }

        // 6. Return current tracked obstacle states
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
        Log.d(TAG, "ObstacleTracker cleared.")
    }

    companion object {
        private const val TAG = "ObstacleTracker"

        const val VEHICLE_MIN_SCORE_DEFAULT = 0.60f
        const val DEFAULT_MIN_SCORE_DEFAULT = 0.50f
        const val MAX_MISSED_FRAMES_DEFAULT = 3
        const val MAX_DISTANCE_HISTORY_DEFAULT = 5

        const val DEFAULT_IOU_MATCH_THRESHOLD = 0.20f
        const val DEFAULT_MAX_CENTER_DISPLACEMENT_RATIO = 0.25f
        const val DEFAULT_MAX_DISTANCE_CONTINUITY_METERS = 2.0f

        const val LARGE_BOX_FRAME_HEIGHT_RATIO = 0.80f
        const val MIN_CLAMPED_DISTANCE_METERS = 0.50f
        const val MAX_VEHICLE_ASPECT_RATIO = 2.80f
        const val MIN_VEHICLE_ASPECT_RATIO = 0.35f

        val VEHICLE_CLASSES = setOf("car", "truck", "bus", "motorcycle")

        /**
         * Calculates Intersection over Union (IoU) between two bounding boxes.
         */
        fun calculateIoU(box1: RectF, box2: RectF): Float {
            val intersectLeft = maxOf(box1.left, box2.left)
            val intersectTop = maxOf(box1.top, box2.top)
            val intersectRight = minOf(box1.right, box2.right)
            val intersectBottom = minOf(box1.bottom, box2.bottom)

            val intersectWidth = maxOf(0f, intersectRight - intersectLeft)
            val intersectHeight = maxOf(0f, intersectBottom - intersectTop)
            val intersectionArea = intersectWidth * intersectHeight

            if (intersectionArea <= 0f) return 0f

            val area1 = maxOf(0f, box1.right - box1.left) * maxOf(0f, box1.bottom - box1.top)
            val area2 = maxOf(0f, box2.right - box2.left) * maxOf(0f, box2.bottom - box2.top)
            val unionArea = area1 + area2 - intersectionArea

            if (unionArea <= 0f) return 0f
            return intersectionArea / unionArea
        }

        /**
         * Calculates the normalized Euclidean center displacement between two bounding boxes.
         */
        fun calculateNormalizedCenterDisplacement(
            box1: RectF,
            box2: RectF,
            frameWidth: Int,
            frameHeight: Int
        ): Float {
            val cx1 = (box1.left + box1.right) / 2f
            val cy1 = (box1.top + box1.bottom) / 2f
            val cx2 = (box2.left + box2.right) / 2f
            val cy2 = (box2.top + box2.bottom) / 2f

            val fw = if (frameWidth > 0) frameWidth.toFloat() else 1000f
            val fh = if (frameHeight > 0) frameHeight.toFloat() else 1000f

            val dx = (cx1 - cx2) / fw
            val dy = (cy1 - cy2) / fh
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }
}
