package com.example.navisense

import android.graphics.RectF
import java.util.Locale

/**
 * Directional advice for the user when encountering a path obstacle.
 */
enum class AvoidanceAdvice(val spokenText: String) {
    STEP_LEFT("Step left"),
    STEP_RIGHT("Step right"),
    STOP("Stop")
}

/**
 * Evaluates free space around path obstacles to advise the user which way to step:
 * - Triggers only when a CENTER obstacle on the whitelist is within 3 m (and not an unreliable vehicle case)
 * - Checks whether LEFT and RIGHT sides are blocked by other obstacles within 5 m
 * - Computes free width on left (box.left) and right (frameWidth - box.right)
 * - Requires at least 25% of frame width to consider a side clear
 * - Recommends "Step left", "Step right", or "Stop".
 */
class AvoidanceAdvisor {

    /**
     * Evaluates tracked alert-eligible obstacles and returns avoidance advice for the primary path obstacle.
     */
    fun getAdvice(
        trackedObstacles: List<TrackedObstacle>,
        frameWidth: Int
    ): AvoidanceAdvice? {
        if (frameWidth <= 0 || trackedObstacles.isEmpty()) return null

        // Trigger only when a CENTER obstacle on the whitelist is within 3 m (and not a vehicle large-box "unreliable" case)
        val centerObstacle = trackedObstacles
            .filter { obstacle ->
                obstacle.zone == Zone.CENTER &&
                    obstacle.isAlertEligible &&
                    obstacle.smoothedDistanceMeters > 0f &&
                    obstacle.smoothedDistanceMeters <= MAX_AVOIDANCE_TRIGGER_DISTANCE_METERS &&
                    obstacle.label.trim().lowercase(Locale.US) in DecisionEngine.OBSTACLE_WHITELIST &&
                    !obstacle.isUnreliableOrLarge
            }
            .minByOrNull { it.smoothedDistanceMeters } ?: return null

        return evaluateAdvice(centerObstacle, trackedObstacles, frameWidth)
    }

    /**
     * Evaluates free space for a specific center obstacle against all tracked obstacles.
     */
    fun evaluateAdvice(
        centerObstacle: TrackedObstacle,
        allObstacles: List<TrackedObstacle>,
        frameWidth: Int
    ): AvoidanceAdvice? {
        if (frameWidth <= 0) return null
        if (centerObstacle.isUnreliableOrLarge) return null
        if (centerObstacle.smoothedDistanceMeters > MAX_AVOIDANCE_TRIGGER_DISTANCE_METERS) return null

        val frameWidthF = frameWidth.toFloat()
        val thirdWidth = frameWidthF / 3f

        val otherObstacles = allObstacles.filter { it.trackId != centerObstacle.trackId }

        // Free-space logic: for LEFT and RIGHT, compute whether the side is blocked:
        // blocked if any other tracked obstacle within 5 m has its box centre in that zone,
        // or overlaps that side's third of the frame by more than 30%.
        val isLeftBlocked = otherObstacles.any { other ->
            other.smoothedDistanceMeters in 0.001f..MAX_BLOCKING_DISTANCE_METERS &&
                isSideBlocked(other.boundingBox, other.zone, Zone.LEFT, frameWidthF, thirdWidth)
        }

        val isRightBlocked = otherObstacles.any { other ->
            other.smoothedDistanceMeters in 0.001f..MAX_BLOCKING_DISTANCE_METERS &&
                isSideBlocked(other.boundingBox, other.zone, Zone.RIGHT, frameWidthF, thirdWidth)
        }

        // Also use the centre obstacle's box:
        // free width on the left = box.left, on the right = frameWidth - box.right.
        val freeWidthLeft = centerObstacle.boundingBox.left.coerceAtLeast(0f)
        val freeWidthRight = (frameWidthF - centerObstacle.boundingBox.right).coerceAtLeast(0f)

        // A side is "clear" if it is not blocked and its free width is at least 25% of the frame width.
        val minFreeWidth = MIN_FREE_WIDTH_RATIO * frameWidthF
        val isLeftClear = !isLeftBlocked && freeWidthLeft >= minFreeWidth
        val isRightClear = !isRightBlocked && freeWidthRight >= minFreeWidth

        // Decision:
        // if only the left is clear say "Step left";
        // if only the right is clear say "Step right";
        // if both are clear choose the side with more free width;
        // if neither is clear say "Stop".
        return when {
            isLeftClear && !isRightClear -> AvoidanceAdvice.STEP_LEFT
            !isLeftClear && isRightClear -> AvoidanceAdvice.STEP_RIGHT
            isLeftClear && isRightClear -> {
                if (freeWidthLeft >= freeWidthRight) {
                    AvoidanceAdvice.STEP_LEFT
                } else {
                    AvoidanceAdvice.STEP_RIGHT
                }
            }
            else -> AvoidanceAdvice.STOP
        }
    }

    private fun isSideBlocked(
        box: RectF,
        trackedZone: Zone,
        targetSide: Zone,
        frameWidth: Float,
        thirdWidth: Float
    ): Boolean {
        val centerX = (box.left + box.right) / 2f

        // 1. Box centre in target zone
        val centerInZone = when (targetSide) {
            Zone.LEFT -> centerX < thirdWidth || trackedZone == Zone.LEFT
            Zone.RIGHT -> centerX >= 2f * thirdWidth || trackedZone == Zone.RIGHT
            else -> false
        }
        if (centerInZone) return true

        // 2. Overlaps that side's third of the frame by more than 30%
        val (zoneStart, zoneEnd) = when (targetSide) {
            Zone.LEFT -> 0f to thirdWidth
            Zone.RIGHT -> 2f * thirdWidth to frameWidth
            else -> return false
        }

        val overlapStart = maxOf(zoneStart, box.left)
        val overlapEnd = minOf(zoneEnd, box.right)
        val overlapWidth = maxOf(0f, overlapEnd - overlapStart)

        return (overlapWidth / thirdWidth) > OVERLAP_BLOCKING_THRESHOLD
    }

    companion object {
        const val MAX_AVOIDANCE_TRIGGER_DISTANCE_METERS = 3.0f
        const val MAX_BLOCKING_DISTANCE_METERS = 5.0f
        const val MIN_FREE_WIDTH_RATIO = 0.25f
        const val OVERLAP_BLOCKING_THRESHOLD = 0.30f
    }
}
