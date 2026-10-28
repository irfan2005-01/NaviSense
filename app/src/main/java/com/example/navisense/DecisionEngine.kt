package com.example.navisense

import android.util.Log
import java.util.Locale

/**
 * Tracks the last announced alert timestamp, distance, and avoidance advice for cooldown evaluation.
 * Maintained for backwards compatibility.
 */
data class LastAlertRecord(
    val timestamp: Long,
    val distanceMeters: Float,
    val lastAdvice: AvoidanceAdvice? = null
)

/**
 * Context regarding active navigation used to coordinate avoidance advice with turn instructions.
 */
data class NavigationContext(
    val isNavigating: Boolean = false,
    val upcomingManeuverType: ManeuverType? = null,
    val distanceToManeuverMeters: Double? = null
)

/**
 * Evaluates object detections with temporal tracking:
 * - Requires 3 consecutive frames before triggering obstacle alerts
 * - Filters by class-specific score thresholds (0.6 for vehicles, 0.5 for others)
 * - Enforces median distance smoothing over last 5 frames
 * - Performs sanity checks (large box / extreme vehicle aspect ratio -> "Large obstacle ahead. Stop.", no urgent interrupt)
 * - Computes avoidance guidance ("Step left", "Step right", "Stop") for center obstacles <= 3 m
 * - Prevents advice contradicting upcoming turns within 10 m of a maneuver
 * - Prevents dual vibration collision: only one vibration source active per frame
 * - Enforces track-specific alert isolation and cooldowns with synchronized speech / haptics.
 */
class DecisionEngine(
    private val voiceManager: VoiceNotifier,
    private val hapticManager: HapticNotifier,
    val tracker: ObstacleTracker = ObstacleTracker(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    val avoidanceAdvisor: AvoidanceAdvisor = AvoidanceAdvisor(),
    val alertManager: ObstacleAlertManager = ObstacleAlertManager()
) {

    var isVoiceEnabled: Boolean = true
        set(value) {
            field = value
            Log.i(TAG, "DecisionEngine isVoiceEnabled set to: $value")
            if (!value) {
                voiceManager.stop()
            }
        }

    var isHapticsEnabled: Boolean = true
        set(value) {
            field = value
            Log.i(TAG, "DecisionEngine isHapticsEnabled set to: $value")
            hapticManager.isEnabled = value
            if (!value) {
                hapticManager.cancel()
            }
        }

    var maxAlertDistanceMeters: Float = MAX_DETECTION_DISTANCE_METERS

    var isEnabled: Boolean
        get() = isVoiceEnabled || isHapticsEnabled
        set(value) {
            isVoiceEnabled = value
            isHapticsEnabled = value
        }

    var navigationContext: NavigationContext? = null
    var navigationContextProvider: (() -> NavigationContext?)? = null

    fun processDetections(
        detections: List<Detection>,
        frameWidthPx: Int = 0,
        frameHeightPx: Int = 0
    ) {
        if (!isEnabled) {
            alertManager.clear()
            tracker.clear()
            hapticManager.clearContinuousCenterObstacle()
            return
        }

        if (detections.isEmpty()) {
            tracker.update(emptyList(), frameWidthPx, frameHeightPx)
            alertManager.pruneInactiveTracks(tracker.activeTrackIds)
            hapticManager.clearContinuousCenterObstacle()
            return
        }

        // 1. Update ObstacleTracker with instance-aware IoU and spatial matching
        val trackedObstacles = tracker.update(detections, frameWidthPx, frameHeightPx)

        // 2. Clean up alert state for expired tracks (prevents unbounded memory growth)
        alertManager.pruneInactiveTracks(tracker.activeTrackIds)

        // 3. Filter whitelisted & alert-eligible obstacles (3 consecutive frames, distance <= maxAlertDistanceMeters)
        val validObstacles = trackedObstacles.filter { tracked ->
            val normalizedLabel = tracked.label.trim().lowercase(Locale.US)
            val isWhitelisted = normalizedLabel in OBSTACLE_WHITELIST
            val inRange = tracked.smoothedDistanceMeters > 0f && tracked.smoothedDistanceMeters <= maxAlertDistanceMeters
            val isEligible = tracked.isAlertEligible // consecutiveFrames >= 3

            if (!isWhitelisted) {
                Log.d(TAG, "Ignoring non-whitelisted detection: '${tracked.label}'")
            } else if (!inRange) {
                Log.d(TAG, "Ignoring detection beyond ${maxAlertDistanceMeters}m: '${tracked.label}' at ${tracked.smoothedDistanceMeters}m")
            } else if (!isEligible) {
                Log.d(TAG, "Obstacle '${tracked.label}' at frame ${tracked.consecutiveFrames}/3; waiting for 3 consecutive frames.")
            }

            isWhitelisted && inRange && isEligible
        }

        if (validObstacles.isEmpty()) {
            hapticManager.clearContinuousCenterObstacle()
            return
        }

        // 4. Rank detections by danger score: closer = higher danger, CENTER weighted higher
        val ranked = validObstacles.sortedByDescending { calculateDangerScore(it) }
        val topCandidates = ranked.take(2)
        val now = timeProvider()
        var oneShotHapticFired = false

        for (obstacle in topCandidates) {
            val isVehicle = obstacle.label.trim().lowercase(Locale.US) in ObstacleTracker.VEHICLE_CLASSES
            val isVeryClose = !obstacle.isUnreliableOrLarge && (
                obstacle.smoothedDistanceMeters < CRITICAL_DISTANCE_THRESHOLD_METERS || (!isVehicle && obstacle.isLargeBox)
            )

            // Per-object alert management: check cooldown and state change for this specific track
            val (shouldAnnounce, _) = alertManager.shouldAlert(obstacle, now, isVeryClose)

            if (shouldAnnounce) {
                // Requirement 1 & 2: Trigger avoidance advice only when a CENTER obstacle on the whitelist
                // is within 3 m (and not a vehicle large-box "unreliable" case).
                val triggersAvoidance = obstacle.zone == Zone.CENTER &&
                    obstacle.smoothedDistanceMeters <= AvoidanceAdvisor.MAX_AVOIDANCE_TRIGGER_DISTANCE_METERS &&
                    !obstacle.isUnreliableOrLarge

                val rawAdvice = if (triggersAvoidance) {
                    val effectiveWidth = if (frameWidthPx > 0) frameWidthPx else detections.firstOrNull { it.frameWidthPx > 0 }?.frameWidthPx ?: 1000
                    avoidanceAdvisor.evaluateAdvice(obstacle, validObstacles, effectiveWidth)
                } else {
                    null
                }

                // Requirement 5: If navigation is active, do not give avoidance advice that contradicts
                // a turn instruction within 10 m of a manoeuvre: in that case say only "Obstacle ahead, <distance> meters. Stop."
                val nav = navigationContextProvider?.invoke() ?: navigationContext
                val isNavActive = nav?.isNavigating == true
                val isNearManeuver = nav?.distanceToManeuverMeters != null && nav.distanceToManeuverMeters <= 10.0
                val contradictsNav = isNavActive && isNearManeuver && rawAdvice != null &&
                    contradictsTurnInstruction(rawAdvice, nav?.upcomingManeuverType)

                val currentAdvice = when {
                    obstacle.isUnreliableOrLarge && obstacle.zone == Zone.CENTER -> AvoidanceAdvice.STOP
                    contradictsNav -> AvoidanceAdvice.STOP
                    else -> rawAdvice
                }

                // Requirement 4: speak the direction only when the advice changes or on the first alert for that obstacle.
                val lastRecord = alertManager.getRecord(obstacle.trackId)
                val isFirstAlert = (lastRecord == null)
                val adviceChanged = (lastRecord != null && currentAdvice != lastRecord.lastAdvice)
                val includeAdvice = (isFirstAlert || adviceChanged) && currentAdvice != null

                val message = formatMessage(
                    obstacle = obstacle,
                    advice = currentAdvice,
                    includeAdvice = includeAdvice,
                    contradictsNav = contradictsNav
                )

                val alertPriority = if (isVeryClose) HapticPriority.CRITICAL else HapticPriority.NORMAL_OBSTACLE

                // Requirement 6: Obstacle vibration is only for CENTER-zone obstacles within 3 m.
                // LEFT and RIGHT obstacles produce no vibration (voice only). No vibration for direction advice.
                var thisObstacleHapticFired = false
                if (isHapticsEnabled && !oneShotHapticFired && obstacle.zone == Zone.CENTER && obstacle.smoothedDistanceMeters <= CONTINUOUS_HAPTIC_THRESHOLD_METERS) {
                    val hapticDist = if (!isVehicle && obstacle.isLargeBox) {
                        obstacle.smoothedDistanceMeters.coerceAtMost(CRITICAL_DISTANCE_THRESHOLD_METERS - 0.1f)
                    } else {
                        obstacle.smoothedDistanceMeters
                    }
                    hapticManager.vibrateForObstacleAlert(hapticDist, obstacle.zone)
                    oneShotHapticFired = true
                    thisObstacleHapticFired = true
                }

                alertManager.recordAlert(
                    obstacle = obstacle,
                    currentTimeMs = now,
                    advice = currentAdvice,
                    isUrgent = isVeryClose,
                    priority = alertPriority,
                    oneShotHapticFired = thisObstacleHapticFired,
                    message = message
                )

                Log.i(
                    TAG,
                    "DecisionEngine trigger -> \"$message\" | Track: ${obstacle.trackId} | Zone: ${obstacle.zone} | Dist: ${String.format(Locale.US, "%.2f", obstacle.smoothedDistanceMeters)}m | Advice: $currentAdvice | Voice: $isVoiceEnabled | Haptics: $isHapticsEnabled | VeryClose: $isVeryClose | Unreliable: ${obstacle.isUnreliableOrLarge}"
                )

                if (isVoiceEnabled) {
                    voiceManager.speak(message, interrupt = isVeryClose)
                }

                if (isVeryClose) break
            }
        }

        // 5. Center obstacle cadenced haptics
        // Single vibration source - do NOT fire cadence if one-shot alert fired on this frame
        val closestCenterObstacle = validObstacles
            .filter { it.zone == Zone.CENTER && (it.smoothedDistanceMeters < CONTINUOUS_HAPTIC_THRESHOLD_METERS || (!it.isUnreliableOrLarge && it.isLargeBox)) }
            .minByOrNull { it.smoothedDistanceMeters }

        if (closestCenterObstacle != null && isHapticsEnabled) {
            if (!oneShotHapticFired) {
                val isVehicle = closestCenterObstacle.label.trim().lowercase(Locale.US) in ObstacleTracker.VEHICLE_CLASSES
                val hapticDist = if (!isVehicle && closestCenterObstacle.isLargeBox) {
                    closestCenterObstacle.smoothedDistanceMeters.coerceAtMost(CRITICAL_DISTANCE_THRESHOLD_METERS - 0.1f)
                } else {
                    closestCenterObstacle.smoothedDistanceMeters
                }
                hapticManager.updateContinuousCenterObstacle(hapticDist)
            }
        } else {
            hapticManager.clearContinuousCenterObstacle()
        }
    }

    private fun calculateDangerScore(obstacle: TrackedObstacle): Float {
        val zoneWeight = if (obstacle.zone == Zone.CENTER) 1.5f else 1.0f
        return (1f / obstacle.smoothedDistanceMeters.coerceAtLeast(0.3f)) * zoneWeight
    }

    private fun calculateDangerScore(detection: Detection): Float {
        val zoneWeight = if (detection.zone == Zone.CENTER) 1.5f else 1.0f
        return (1f / detection.distanceMeters.coerceAtLeast(0.3f)) * zoneWeight
    }

    fun formatDistance(meters: Float): String {
        val rounded = (Math.round(meters * 2f) / 2.0).coerceAtLeast(0.5)
        val distStr = if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        val unit = if (rounded == 1.0) "meter" else "meters"
        return "$distStr $unit"
    }

    fun formatMessage(
        obstacle: TrackedObstacle,
        advice: AvoidanceAdvice? = null,
        includeAdvice: Boolean = false,
        contradictsNav: Boolean = false
    ): String {
        // Requirement 3: For unreliable or large vehicle boxes say "Large obstacle ahead. Stop."
        if (obstacle.isUnreliableOrLarge) {
            return when (obstacle.zone) {
                Zone.CENTER -> "Large obstacle ahead. Stop."
                Zone.LEFT -> "Large obstacle on your left"
                Zone.RIGHT -> "Large obstacle on your right"
            }
        }

        val distanceText = formatDistance(obstacle.smoothedDistanceMeters)

        // Requirement 5: Contradictory navigation within 10m of maneuver
        if (contradictsNav) {
            return "Obstacle ahead, $distanceText. Stop."
        }

        val capitalizedLabel = obstacle.label.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }

        return when (obstacle.zone) {
            Zone.CENTER -> {
                if (includeAdvice && advice != null) {
                    "$capitalizedLabel ahead, $distanceText. ${advice.spokenText}."
                } else {
                    "$capitalizedLabel ahead, $distanceText"
                }
            }
            Zone.LEFT -> "$capitalizedLabel on your left, $distanceText"
            Zone.RIGHT -> "$capitalizedLabel on your right, $distanceText"
        }
    }

    fun formatMessage(detection: Detection): String {
        if (detection.isUnreliableOrLarge) {
            return when (detection.zone) {
                Zone.CENTER -> "Large obstacle ahead. Stop."
                Zone.LEFT -> "Large obstacle on your left"
                Zone.RIGHT -> "Large obstacle on your right"
            }
        }

        val capitalizedLabel = detection.label.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
        val distanceText = formatDistance(detection.distanceMeters)
        return when (detection.zone) {
            Zone.CENTER -> "$capitalizedLabel ahead, $distanceText"
            Zone.LEFT -> "$capitalizedLabel on your left, $distanceText"
            Zone.RIGHT -> "$capitalizedLabel on your right, $distanceText"
        }
    }

    fun clear() {
        alertManager.clear()
        tracker.clear()
        hapticManager.clearContinuousCenterObstacle()
        hapticManager.cancel()
        navigationContext = null
        Log.i(TAG, "DecisionEngine alert records and haptics cleared.")
    }

    companion object {
        private const val TAG = "DecisionEngine"

        const val MAX_DETECTION_DISTANCE_METERS = 5.0f
        const val CONTINUOUS_HAPTIC_THRESHOLD_METERS = 3.0f
        const val CRITICAL_DISTANCE_THRESHOLD_METERS = 1.5f

        const val COOLDOWN_MS = 8000L
        const val DISTANCE_CHANGE_THRESHOLD_METERS = 1.0f

        /**
         * Checks whether avoidance advice contradicts an upcoming turn maneuver.
         */
        fun contradictsTurnInstruction(
            advice: AvoidanceAdvice,
            maneuverType: ManeuverType?
        ): Boolean {
            if (maneuverType == null) return false
            return when (maneuverType) {
                ManeuverType.TURN_LEFT,
                ManeuverType.TURN_SLIGHT_LEFT,
                ManeuverType.TURN_SHARP_LEFT,
                ManeuverType.U_TURN -> advice == AvoidanceAdvice.STEP_RIGHT

                ManeuverType.TURN_RIGHT,
                ManeuverType.TURN_SLIGHT_RIGHT,
                ManeuverType.TURN_SHARP_RIGHT -> advice == AvoidanceAdvice.STEP_LEFT

                ManeuverType.STRAIGHT,
                ManeuverType.UNKNOWN -> false
            }
        }

        val OBSTACLE_WHITELIST = setOf(
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "bus",
            "truck",
            "dog",
            "cat",
            "cow",
            "horse",
            "chair",
            "couch",
            "sofa",
            "bench",
            "bed",
            "dining table",
            "table",
            "potted plant",
            "pottedplant",
            "plant",
            "door",
            "suitcase",
            "backpack",
            "bottle",
            "fire hydrant",
            "stop sign",
            "traffic light"
        )
    }
}
