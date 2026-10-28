package com.example.navisense

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Reasons why an obstacle detection alert may be suppressed.
 */
enum class AlertSuppressionReason {
    COOLDOWN_ACTIVE,
    ZONE_CHANGE_SUPPRESSED,
    NOT_ALERT_ELIGIBLE,
    NOT_WHITELISTED,
    OUT_OF_RANGE,
    DISABLED
}

/**
 * Listener interface for tracking and auditing alert emissions and suppressions.
 */
interface AlertEventListener {
    fun onAlertEmitted(
        trackId: Int,
        label: String,
        distanceMeters: Float,
        zone: Zone,
        isUrgent: Boolean,
        message: String,
        timestampMs: Long
    ) {}

    fun onAlertSuppressed(
        trackId: Int,
        label: String,
        reason: AlertSuppressionReason,
        timestampMs: Long
    ) {}

    fun onAlertStateExpired(trackId: Int) {}
}

/**
 * State record tracking alert history for an individual object track.
 */
data class TrackAlertRecord(
    val trackId: Int,
    val label: String,
    var lastAnnouncedDistanceMeters: Float,
    var lastAnnouncedZone: Zone,
    var lastVoiceAlertTimeMs: Long,
    var lastVibrationAlertTimeMs: Long = 0L,
    var lastAdvice: AvoidanceAdvice? = null,
    var isUrgent: Boolean = false,
    var lastAlertPriority: HapticPriority = HapticPriority.NONE
)

/**
 * Manages per-object alert isolation and suppression:
 * - Maintains independent alert cooldown and history per track ID.
 * - Suppresses repeated alerts for the same obstacle within cooldown window unless state materially changes.
 * - Permits immediate alert if an obstacle escalates to an urgent hazard (< 1.5 m or close non-vehicle large box).
 * - Permits alert on material distance change (>= 1.0 m) or when entering the center walking path.
 * - Prevents zone flutter from causing spurious duplicate alerts while maintaining identity.
 * - Automatically purges alert records for expired or pruned tracks to avoid unbounded memory growth.
 */
class ObstacleAlertManager(
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val distanceChangeThresholdMeters: Float = DEFAULT_DISTANCE_CHANGE_THRESHOLD_METERS,
    val criticalDistanceThresholdMeters: Float = DEFAULT_CRITICAL_DISTANCE_THRESHOLD_METERS,
    var eventListener: AlertEventListener? = null
) {
    private val alertRecords = ConcurrentHashMap<Int, TrackAlertRecord>()

    /**
     * Determines whether a tracked obstacle should trigger an announcement.
     *
     * @param obstacle Tracked obstacle instance with current metrics.
     * @param currentTimeMs Current timestamp in milliseconds (typically monotonic or system time).
     * @param isUrgent Whether the obstacle constitutes an urgent hazard (< 1.5 m or very close).
     * @return Pair of (shouldAnnounce: Boolean, suppressionReason: AlertSuppressionReason?).
     */
    fun shouldAlert(
        obstacle: TrackedObstacle,
        currentTimeMs: Long,
        isUrgent: Boolean
    ): Pair<Boolean, AlertSuppressionReason?> {
        val record = alertRecords[obstacle.trackId]

        // 1. First alert for a newly confirmed tracked obstacle
        if (record == null) {
            return true to null
        }

        // 2. Urgent hazard escalation:
        // If an obstacle becomes urgent while previously non-urgent, alert immediately without cooldown block.
        if (isUrgent && !record.isUrgent) {
            Log.d(TAG, "Track ${obstacle.trackId} (${obstacle.label}) escalated to urgent hazard. Cooldown bypassed.")
            return true to null
        }

        // 3. Material distance change: >= threshold (default 1.0 m)
        val distanceDiff = kotlin.math.abs(obstacle.smoothedDistanceMeters - record.lastAnnouncedDistanceMeters)
        if (distanceDiff >= distanceChangeThresholdMeters) {
            Log.d(TAG, "Track ${obstacle.trackId} distance changed by ${String.format(java.util.Locale.US, "%.2f", distanceDiff)}m (>= ${distanceChangeThresholdMeters}m). Alert permitted.")
            return true to null
        }

        // 4. Cooldown expiration: >= cooldownMs (default 8000 ms)
        val elapsed = currentTimeMs - record.lastVoiceAlertTimeMs
        if (elapsed >= cooldownMs) {
            Log.d(TAG, "Track ${obstacle.trackId} cooldown expired (${elapsed}ms >= ${cooldownMs}ms). Alert permitted.")
            return true to null
        }

        // 5. Material zone change: moving into the CENTER walking path directly in user's way
        if (obstacle.zone == Zone.CENTER && record.lastAnnouncedZone != Zone.CENTER) {
            Log.d(TAG, "Track ${obstacle.trackId} moved into CENTER walking path from ${record.lastAnnouncedZone}. Alert permitted.")
            return true to null
        }

        // 6. Otherwise suppressed:
        val reason = if (obstacle.zone != record.lastAnnouncedZone) {
            AlertSuppressionReason.ZONE_CHANGE_SUPPRESSED
        } else {
            AlertSuppressionReason.COOLDOWN_ACTIVE
        }

        eventListener?.onAlertSuppressed(obstacle.trackId, obstacle.label, reason, currentTimeMs)
        return false to reason
    }

    /**
     * Updates alert state for a given track after an announcement or vibration.
     */
    fun recordAlert(
        obstacle: TrackedObstacle,
        currentTimeMs: Long,
        advice: AvoidanceAdvice?,
        isUrgent: Boolean,
        priority: HapticPriority = HapticPriority.NORMAL_OBSTACLE,
        oneShotHapticFired: Boolean = false,
        message: String = ""
    ) {
        val existing = alertRecords[obstacle.trackId]
        if (existing != null) {
            existing.lastAnnouncedDistanceMeters = obstacle.smoothedDistanceMeters
            existing.lastAnnouncedZone = obstacle.zone
            existing.lastVoiceAlertTimeMs = currentTimeMs
            existing.lastAdvice = advice
            existing.isUrgent = isUrgent
            existing.lastAlertPriority = priority
            if (oneShotHapticFired) {
                existing.lastVibrationAlertTimeMs = currentTimeMs
            }
        } else {
            alertRecords[obstacle.trackId] = TrackAlertRecord(
                trackId = obstacle.trackId,
                label = obstacle.label,
                lastAnnouncedDistanceMeters = obstacle.smoothedDistanceMeters,
                lastAnnouncedZone = obstacle.zone,
                lastVoiceAlertTimeMs = currentTimeMs,
                lastVibrationAlertTimeMs = if (oneShotHapticFired) currentTimeMs else 0L,
                lastAdvice = advice,
                isUrgent = isUrgent,
                lastAlertPriority = priority
            )
        }

        eventListener?.onAlertEmitted(
            trackId = obstacle.trackId,
            label = obstacle.label,
            distanceMeters = obstacle.smoothedDistanceMeters,
            zone = obstacle.zone,
            isUrgent = isUrgent,
            message = message,
            timestampMs = currentTimeMs
        )
    }

    /**
     * Purges alert records for tracks that are no longer active in ObstacleTracker.
     * Prevents unbounded memory growth.
     */
    fun pruneInactiveTracks(activeTrackIds: Set<Int>) {
        val iterator = alertRecords.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeTrackIds) {
                Log.d(TAG, "Pruning alert state for expired track ${entry.key} (${entry.value.label}).")
                eventListener?.onAlertStateExpired(entry.key)
                iterator.remove()
            }
        }
    }

    fun getRecord(trackId: Int): TrackAlertRecord? = alertRecords[trackId]

    fun hasRecord(trackId: Int): Boolean = alertRecords.containsKey(trackId)

    fun getActiveRecordCount(): Int = alertRecords.size

    fun clear() {
        alertRecords.clear()
        Log.d(TAG, "ObstacleAlertManager cleared.")
    }

    companion object {
        private const val TAG = "ObstacleAlertManager"

        const val DEFAULT_COOLDOWN_MS = 8000L
        const val DEFAULT_DISTANCE_CHANGE_THRESHOLD_METERS = 1.0f
        const val DEFAULT_CRITICAL_DISTANCE_THRESHOLD_METERS = 1.5f
    }
}
