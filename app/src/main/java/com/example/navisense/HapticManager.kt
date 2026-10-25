package com.example.navisense

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * Haptic priority hierarchy:
 * Critical obstacle (< 1.5 m) > Navigation cue > Normal obstacle ticks (1.5 m - 3.0 m).
 */
enum class HapticPriority(val value: Int) {
    NONE(0),
    INFO(1),              // Obstacles > 3m (no vibration) or settings confirmations
    NORMAL_OBSTACLE(2),   // Obstacles 1.5m - 3m (Far: 2-3m, Near: 1.5-2m)
    NAVIGATION(3),        // Turn left, turn right, destination reached
    CRITICAL(4);          // Obstacles < 1.5m (Danger)

    companion object {
        // Alias for backwards compatibility with earlier components
        val WARNING = NORMAL_OBSTACLE
    }
}

/**
 * Continuous / cadenced haptic mode for CENTER obstacles under 3m.
 */
enum class ContinuousHapticMode {
    NONE,
    CENTER_WARNING,   // 1.5m - 3m (Far: 2-3m, Near: 1.5-2m)
    CENTER_CRITICAL   // < 1.5m (with 0.3m hysteresis deadband)
}

/**
 * Interface defining haptic feedback capabilities for obstacles and navigation.
 */
interface HapticNotifier {
    var isEnabled: Boolean
    fun vibrateForObstacleAlert(distanceMeters: Float, zone: Zone?)
    fun updateContinuousCenterObstacle(distanceMeters: Float)
    fun clearContinuousCenterObstacle()
    fun vibrateTurnLeft() {}
    fun vibrateTurnRight() {}
    fun vibrateDestinationReached() {}
    fun vibrateFarObstacle() {}
    fun vibrateNearObstacle() {}
    fun vibrateDangerObstacle() {}
    fun vibrateArrived() { vibrateDestinationReached() }
    fun cancel()
}

/**
 * Abstraction over Android vibration hardware enabling pure unit testing.
 */
interface VibrationController {
    fun hasVibrator(): Boolean
    fun hasAmplitudeControl(): Boolean
    fun vibrate(timings: LongArray, amplitudes: IntArray?, repeatIndex: Int)
    fun cancel()
}

/**
 * Concrete Android hardware vibration controller using Vibrator and VibrationEffect APIs.
 */
class AndroidVibrationController(
    private val vibrator: Vibrator?,
    private val hasAmpControl: Boolean
) : VibrationController {
    override fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    override fun hasAmplitudeControl(): Boolean = hasAmpControl

    override fun vibrate(timings: LongArray, amplitudes: IntArray?, repeatIndex: Int) {
        val vib = vibrator ?: return
        try {
            if (hasAmpControl && amplitudes != null && amplitudes.size == timings.size) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, repeatIndex)
                vib.vibrate(effect)
            } else {
                val effect = VibrationEffect.createWaveform(timings, repeatIndex)
                vib.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.e("AndroidVibrator", "Error triggering hardware vibration", e)
        }
    }

    override fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("AndroidVibrator", "Error cancelling hardware vibration", e)
        }
    }
}

/**
 * Manages tactile vibration patterns using modern Android VibrationEffect and Vibrator APIs.
 * Redesigned haptic language (Step 9):
 * 1. Obstacle vibration only for CENTER-zone obstacles within 3 m:
 *    - Encodes proximity like a parking sensor: sequence of identical short crisp ticks (80-100 ms each)
 *    - Gap shrinks with distance: 2-3 m (~1200 ms gap), 1.5-2 m (~600 ms gap), <1.5 m (~250 ms gap, strongest amplitude)
 *    - 0.3 m hysteresis around 1.5 m critical threshold
 *    - LEFT and RIGHT obstacles produce NO vibration (voice only)
 *    - Stop vibration immediately when no CENTER obstacle remains within 3 m.
 * 2. Every vibration pause between pulses >= 150 ms so pulses never blur together.
 * 3. Navigation cues:
 *    - Turn Left = 2 long pulses (400 ms each, 400 ms gap)
 *    - Turn Right = 3 long pulses (400 ms each, 400 ms gap)
 *    - Arrival = 1 very long pulse (1200 ms)
 * 4. Priority: critical obstacle (< 1.5 m) > navigation cue > normal obstacle ticks.
 *    Navigation cue briefly pauses obstacle ticks and lets them resume.
 *    Never overlap two patterns. Never use indefinite looping; enforce maximum single pattern duration.
 * 5. Vibration strength option (Low, Medium, High) scales amplitudes.
 */
class HapticManager(
    private val context: Context? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    controllerOverride: VibrationController? = null
) : HapticNotifier {

    private val handler: Handler? = try {
        val looper = Looper.getMainLooper()
        if (looper != null) Handler(looper) else null
    } catch (_: Exception) {
        null
    }

    private val controller: VibrationController = controllerOverride ?: run {
        val vibrator = try {
            if (context != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val hasAmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.hasAmplitudeControl() == true
        } else {
            false
        }
        AndroidVibrationController(vibrator, hasAmp)
    }

    val hasVibrator: Boolean
        get() = controller.hasVibrator()
    val hasAmplitudeControl: Boolean
        get() = controller.hasAmplitudeControl()

    override var isEnabled: Boolean = true
        set(value) {
            field = value
            Log.i(TAG, "HapticManager isEnabled set to: $value")
            if (!value) {
                cancel()
            }
        }

    var vibrationStrength: VibrationStrength = VibrationStrength.HIGH
        set(value) {
            field = value
            Log.i(TAG, "HapticManager vibrationStrength set to: $value (scale=${value.scaleFactor})")
        }

    var currentPriority: HapticPriority = HapticPriority.NONE
        private set
    var currentSessionId: Long = 0L
        private set
    var currentContinuousMode: ContinuousHapticMode = ContinuousHapticMode.NONE
        private set
    var isAlertVibrating: Boolean = false
        private set

    var currentPatternEndTimeMs: Long = 0L
        private set
    var isObstacleTicksPaused: Boolean = false
        private set
    var activeCenterObstacleDistance: Float? = null
        private set
    private var lastCadenceTriggerTimeMs: Long = 0L

    init {
        Log.i(
            TAG,
            "HapticManager initialized. hasVibrator=$hasVibrator, hasAmplitudeControl=$hasAmplitudeControl, api=${Build.VERSION.SDK_INT}"
        )
    }

    /**
     * Obstacle alert vibration: ONLY for CENTER-zone obstacles within 3 m.
     * LEFT and RIGHT obstacles produce NO vibration (voice only).
     */
    override fun vibrateForObstacleAlert(distanceMeters: Float, zone: Zone?) {
        if (!isEnabled || !hasVibrator) return

        // Requirement 1: Vibration is only for CENTER-zone obstacles within 3 m.
        // Side obstacles (LEFT and RIGHT) produce no vibration (voice only).
        if (zone != Zone.CENTER || distanceMeters > WARNING_DISTANCE_METERS) {
            Log.d(TAG, "Obstacle alert vibration suppressed: zone=$zone, dist=$distanceMeters (only CENTER <= 3m vibrates)")
            return
        }

        val isCritical = distanceMeters < CRITICAL_DISTANCE_METERS
        val priority = if (isCritical) HapticPriority.CRITICAL else HapticPriority.NORMAL_OBSTACLE

        val pattern = buildObstaclePattern(distanceMeters)
        val description = "ObstacleAlert (dist=${String.format(java.util.Locale.US, "%.1f", distanceMeters)}m)"

        isAlertVibrating = true
        val now = timeProvider()
        val totalDurationMs = pattern.timings.sum()
        val alertSessionId = currentSessionId + 1

        handler?.postDelayed({
            if (currentSessionId == alertSessionId) {
                isAlertVibrating = false
            }
        }, totalDurationMs)

        playPattern(
            timings = pattern.timings,
            amplitudes = pattern.amplitudes,
            repeatIndex = -1,
            priority = priority,
            description = description,
            currentTimeMs = now
        )
    }

    /**
     * Cadenced vibration while a CENTER obstacle is under 3 m (parking sensor ticks).
     * Enforces 0.3 m hysteresis around 1.5 m critical threshold.
     * Stops immediately when no CENTER obstacle remains within 3 m.
     */
    override fun updateContinuousCenterObstacle(distanceMeters: Float) {
        updateContinuousCenterObstacle(distanceMeters, timeProvider())
    }

    fun updateContinuousCenterObstacle(distanceMeters: Float, currentTimeMs: Long) {
        if (!isEnabled || !hasVibrator) return

        // Exceeding 3m: stop vibration immediately
        if (distanceMeters > WARNING_DISTANCE_METERS) {
            clearContinuousCenterObstacle()
            return
        }

        // Check if an existing pattern has naturally completed by wall-clock time
        if (currentTimeMs >= currentPatternEndTimeMs && currentPriority != HapticPriority.NONE) {
            onPatternCompleted(currentSessionId, currentTimeMs)
        }

        // Hysteresis around 1.5m threshold (0.3m deadband: [1.5m, 1.8m])
        val targetMode = when (currentContinuousMode) {
            ContinuousHapticMode.CENTER_CRITICAL -> {
                // Must exceed 1.8m (1.5m + 0.3m deadband) to drop back to WARNING
                if (distanceMeters >= CRITICAL_EXIT_THRESHOLD_METERS) {
                    ContinuousHapticMode.CENTER_WARNING
                } else {
                    ContinuousHapticMode.CENTER_CRITICAL
                }
            }
            ContinuousHapticMode.CENTER_WARNING, ContinuousHapticMode.NONE -> {
                // Drops below 1.5m to enter CRITICAL
                if (distanceMeters < CRITICAL_DISTANCE_METERS) {
                    ContinuousHapticMode.CENTER_CRITICAL
                } else {
                    ContinuousHapticMode.CENTER_WARNING
                }
            }
        }

        val targetPriority = if (targetMode == ContinuousHapticMode.CENTER_CRITICAL) {
            HapticPriority.CRITICAL
        } else {
            HapticPriority.NORMAL_OBSTACLE
        }

        val isCurrentlyPlaying = currentTimeMs < currentPatternEndTimeMs

        // Priority and single vibration channel enforcement:
        if (isCurrentlyPlaying) {
            // If navigation is currently active:
            if (currentPriority == HapticPriority.NAVIGATION) {
                // Critical obstacle (< 1.5 m) > Navigation cue > Normal obstacle
                if (targetPriority == HapticPriority.CRITICAL) {
                    Log.i(TAG, "Critical obstacle (<1.5m) preempting active navigation cue.")
                } else {
                    // Normal obstacle ticks pause during navigation
                    activeCenterObstacleDistance = distanceMeters
                    isObstacleTicksPaused = true
                    Log.d(TAG, "Obstacle ticks paused during active navigation cue.")
                    return
                }
            } else if (targetPriority.value <= currentPriority.value) {
                // Do not interrupt same or higher priority pattern while currently vibrating (no overlap)
                return
            }
        }

        currentContinuousMode = targetMode
        activeCenterObstacleDistance = distanceMeters

        // Throttle interval between cadence pattern repetitions
        val pattern = buildObstaclePattern(distanceMeters)
        val patternDuration = pattern.timings.sum()
        val cadenceInterval = if (targetMode == ContinuousHapticMode.CENTER_CRITICAL) {
            patternDuration
        } else if (distanceMeters < 2.0f) {
            patternDuration
        } else {
            patternDuration
        }

        val elapsed = currentTimeMs - lastCadenceTriggerTimeMs
        // If mode transitioned to CRITICAL, preempt immediately without waiting for elapsed throttle
        val isCriticalPreemption = targetPriority == HapticPriority.CRITICAL && currentPriority != HapticPriority.CRITICAL
        if (!isCriticalPreemption && elapsed < cadenceInterval && lastCadenceTriggerTimeMs > 0L) {
            return
        }
        lastCadenceTriggerTimeMs = currentTimeMs

        val desc = "CadencedCenterObstacle (mode=$targetMode, dist=${String.format(java.util.Locale.US, "%.1f", distanceMeters)}m)"

        playPattern(
            timings = pattern.timings,
            amplitudes = pattern.amplitudes,
            repeatIndex = -1,
            priority = targetPriority,
            description = desc,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Clears cadenced CENTER haptics and stops vibration immediately when no CENTER obstacle remains within 3 m.
     */
    override fun clearContinuousCenterObstacle() {
        if (currentContinuousMode != ContinuousHapticMode.NONE || activeCenterObstacleDistance != null) {
            Log.d(TAG, "Clearing cadenced CENTER haptic mode: $currentContinuousMode")
            currentContinuousMode = ContinuousHapticMode.NONE
            activeCenterObstacleDistance = null
            isObstacleTicksPaused = false
            lastCadenceTriggerTimeMs = 0L
            if (currentPriority == HapticPriority.NORMAL_OBSTACLE || currentPriority == HapticPriority.CRITICAL) {
                cancel()
            }
        }
    }

    /**
     * Navigation pattern: Turn Left = 2 long pulses (400 ms each, 400 ms gap).
     */
    override fun vibrateTurnLeft() {
        vibrateTurnLeft(timeProvider())
    }

    fun vibrateTurnLeft(currentTimeMs: Long) {
        val timings = longArrayOf(0, 400, 400, 400)
        val amplitudes = intArrayOf(0, AMPLITUDE_NAV, 0, AMPLITUDE_NAV)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Turn Left (2 long pulses)",
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Navigation pattern: Turn Right = 3 long pulses (400 ms each, 400 ms gap).
     */
    override fun vibrateTurnRight() {
        vibrateTurnRight(timeProvider())
    }

    fun vibrateTurnRight(currentTimeMs: Long) {
        val timings = longArrayOf(0, 400, 400, 400, 400, 400)
        val amplitudes = intArrayOf(0, AMPLITUDE_NAV, 0, AMPLITUDE_NAV, 0, AMPLITUDE_NAV)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Turn Right (3 long pulses)",
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Navigation pattern: Destination Reached / Arrival = 1 very long pulse (1200 ms).
     */
    override fun vibrateDestinationReached() {
        vibrateDestinationReached(timeProvider())
    }

    fun vibrateDestinationReached(currentTimeMs: Long) {
        val timings = longArrayOf(0, 1200)
        val amplitudes = intArrayOf(0, AMPLITUDE_STRONG)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Destination Reached (1 very long pulse)",
            currentTimeMs = currentTimeMs
        )
    }

    override fun vibrateFarObstacle() {
        vibrateObstacleBand(2.5f)
    }

    override fun vibrateNearObstacle() {
        vibrateObstacleBand(1.8f)
    }

    override fun vibrateDangerObstacle() {
        vibrateObstacleBand(1.0f)
    }

    fun vibrateObstacleBand(distanceMeters: Float, currentTimeMs: Long = timeProvider()) {
        val isCritical = distanceMeters < CRITICAL_DISTANCE_METERS
        val priority = if (isCritical) HapticPriority.CRITICAL else HapticPriority.NORMAL_OBSTACLE
        val pattern = buildObstaclePattern(distanceMeters)
        playPattern(
            timings = pattern.timings,
            amplitudes = pattern.amplitudes,
            repeatIndex = -1,
            priority = priority,
            description = "ObstacleBand ($distanceMeters m)",
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Short confirmation click / tick when toggling settings.
     */
    fun vibrateConfirmation() {
        val timings = longArrayOf(0, 50)
        val amplitudes = intArrayOf(0, AMPLITUDE_MEDIUM)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.INFO,
            description = "Settings toggle confirmation pulse",
            currentTimeMs = timeProvider()
        )
    }

    /**
     * Cancels any active vibration and resets active state and priority.
     */
    override fun cancel() {
        currentSessionId++
        currentPriority = HapticPriority.NONE
        currentContinuousMode = ContinuousHapticMode.NONE
        isAlertVibrating = false
        currentPatternEndTimeMs = 0L
        isObstacleTicksPaused = false
        lastCadenceTriggerTimeMs = 0L
        try {
            controller.cancel()
            Log.d(TAG, "Vibrator cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibrator", e)
        }
    }

    fun onPatternCompleted(sessionId: Long, currentTimeMs: Long = timeProvider()) {
        if (currentSessionId == sessionId) {
            val wasNavigation = currentPriority == HapticPriority.NAVIGATION
            currentPriority = HapticPriority.NONE
            currentPatternEndTimeMs = 0L
            isAlertVibrating = false
            Log.d(TAG, "Haptic finished (sessionId=$sessionId). Priority reset to NONE.")

            // Requirement 4: A navigation cue briefly pauses obstacle ticks and lets them resume
            if (wasNavigation && isObstacleTicksPaused) {
                isObstacleTicksPaused = false
                val dist = activeCenterObstacleDistance
                if (dist != null && dist <= WARNING_DISTANCE_METERS) {
                    Log.i(TAG, "Resuming paused obstacle ticks after navigation cue: ${dist}m")
                    lastCadenceTriggerTimeMs = 0L
                    updateContinuousCenterObstacle(dist, currentTimeMs)
                }
            }
        }
    }

    private fun playPattern(
        timings: LongArray,
        amplitudes: IntArray,
        repeatIndex: Int,
        priority: HapticPriority,
        description: String,
        currentTimeMs: Long
    ): Boolean {
        if (!isEnabled || !hasVibrator) {
            Log.d(TAG, "Haptics disabled or no vibrator. Skipping $description")
            return false
        }

        // Check if previous pattern duration has expired naturally by time
        if (currentTimeMs >= currentPatternEndTimeMs && currentPriority != HapticPriority.NONE) {
            onPatternCompleted(currentSessionId, currentTimeMs)
        }

        val isCurrentlyPlaying = currentTimeMs < currentPatternEndTimeMs

        if (isCurrentlyPlaying) {
            // Drop lower priority vibrations
            if (priority.value < currentPriority.value) {
                Log.d(TAG, "Suppressed '$description' ($priority) due to higher priority active: $currentPriority")
                return false
            }
            // Never overlap two patterns: equal priority drops rather than interrupting mid-waveform
            if (priority.value == currentPriority.value) {
                Log.d(TAG, "Suppressed '$description' ($priority) due to equal priority currently playing (no overlap)")
                return false
            }
        }

        // Navigation cue pausing normal obstacle ticks:
        if (priority == HapticPriority.NAVIGATION && (currentPriority == HapticPriority.NORMAL_OBSTACLE || currentContinuousMode != ContinuousHapticMode.NONE)) {
            isObstacleTicksPaused = true
            Log.i(TAG, "Navigation cue pausing normal obstacle ticks.")
        }

        // Enforce maximum single pattern duration (never indefinite loop)
        val safePattern = enforceMaxDuration(Pattern(timings, amplitudes))
        val totalDurationMs = safePattern.timings.sum()

        // Cancel previous hardware vibration when preempting
        try {
            controller.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling previous vibration", e)
        }

        currentPriority = priority
        val sessionId = ++currentSessionId
        currentPatternEndTimeMs = currentTimeMs + totalDurationMs

        // Scale amplitudes by vibration strength
        val scaledAmplitudes = IntArray(safePattern.amplitudes.size) { i ->
            val raw = safePattern.amplitudes[i]
            if (raw == 0) 0 else (raw * vibrationStrength.scaleFactor).roundToInt().coerceIn(1, 255)
        }

        try {
            // RepeatIndex is always -1 (no indefinite looping)
            controller.vibrate(
                timings = safePattern.timings,
                amplitudes = if (hasAmplitudeControl) scaledAmplitudes else null,
                repeatIndex = -1
            )
            Log.i(TAG, "Triggered haptic: $description | Priority: $priority | Duration: ${totalDurationMs}ms | Strength: $vibrationStrength")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate for $description", e)
            currentPriority = HapticPriority.NONE
            currentPatternEndTimeMs = 0L
            isAlertVibrating = false
            return false
        }

        // Schedule priority reset and resumption when pattern finishes
        handler?.postDelayed({
            onPatternCompleted(sessionId, timeProvider())
        }, totalDurationMs)

        return true
    }

    private fun enforceMaxDuration(pattern: Pattern): Pattern {
        var accumulated = 0L
        val boundedTimings = mutableListOf<Long>()
        val boundedAmplitudes = mutableListOf<Int>()

        for (i in pattern.timings.indices) {
            val t = pattern.timings[i]
            val a = pattern.amplitudes.getOrElse(i) { 0 }
            if (accumulated + t <= MAX_PATTERN_DURATION_MS) {
                boundedTimings.add(t)
                boundedAmplitudes.add(a)
                accumulated += t
            } else {
                val remainder = MAX_PATTERN_DURATION_MS - accumulated
                if (remainder > 0) {
                    boundedTimings.add(remainder)
                    boundedAmplitudes.add(a)
                }
                break
            }
        }
        return Pattern(boundedTimings.toLongArray(), boundedAmplitudes.toIntArray())
    }

    data class Pattern(val timings: LongArray, val amplitudes: IntArray)

    /**
     * Builds obstacle proximity pattern: sequence of identical short crisp ticks (80-100 ms).
     * Gap shrinks with distance:
     * - 2-3 m: ~1200 ms gap, soft amplitude
     * - 1.5-2 m: ~600 ms gap, medium amplitude
     * - below 1.5 m: ~250 ms gap, strongest amplitude (255)
     */
    fun buildObstaclePattern(distanceMeters: Float): Pattern {
        val isCritical = distanceMeters < CRITICAL_DISTANCE_METERS
        return when {
            // Below 1.5m: Danger, ~250ms gap, strongest amplitude (3 crisp ticks)
            isCritical -> {
                val amp = AMPLITUDE_STRONG
                Pattern(
                    timings = longArrayOf(0, TICK_DURATION_MS, GAP_DANGER_MS, TICK_DURATION_MS, GAP_DANGER_MS, TICK_DURATION_MS),
                    amplitudes = intArrayOf(0, amp, 0, amp, 0, amp)
                )
            }
            // 1.5m - 2.0m: Near, ~600ms gap, medium amplitude
            distanceMeters < 2.0f -> {
                val amp = AMPLITUDE_MEDIUM
                Pattern(
                    timings = longArrayOf(0, TICK_DURATION_MS, GAP_NEAR_MS, TICK_DURATION_MS),
                    amplitudes = intArrayOf(0, amp, 0, amp)
                )
            }
            // 2.0m - 3.0m: Far, ~1200ms gap, soft amplitude
            else -> {
                val amp = AMPLITUDE_SOFT
                Pattern(
                    timings = longArrayOf(0, TICK_DURATION_MS, GAP_FAR_MS, TICK_DURATION_MS),
                    amplitudes = intArrayOf(0, amp, 0, amp)
                )
            }
        }
    }

    companion object {
        private const val TAG = "HapticManager"

        // Tick duration: about 80-100 ms each
        const val TICK_DURATION_MS = 90L

        // Gaps between obstacle ticks:
        const val GAP_FAR_MS = 1200L      // 2-3 m
        const val GAP_NEAR_MS = 600L      // 1.5-2 m
        const val GAP_DANGER_MS = 250L    // < 1.5 m

        // Requirement 2: Every vibration pause between pulses >= 150 ms
        const val MIN_PAUSE_MS = 150L

        const val CRITICAL_DISTANCE_METERS = 1.5f
        const val HYSTERESIS_DEADBAND_METERS = 0.3f
        const val CRITICAL_EXIT_THRESHOLD_METERS = CRITICAL_DISTANCE_METERS + HYSTERESIS_DEADBAND_METERS // 1.8f
        const val WARNING_DISTANCE_METERS = 3.0f

        // Maximum single pattern duration
        const val MAX_PATTERN_DURATION_MS = 2500L

        // Base amplitudes before scaling
        const val AMPLITUDE_SOFT = 100
        const val AMPLITUDE_MEDIUM = 180
        const val AMPLITUDE_STRONG = 255
        const val AMPLITUDE_NAV = 220
    }
}
