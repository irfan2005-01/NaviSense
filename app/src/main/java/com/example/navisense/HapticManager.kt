package com.example.navisense

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Haptic priority hierarchy to prevent low-priority vibration queueing
 * and prioritize urgent navigation and obstacle warnings.
 */
enum class HapticPriority(val value: Int) {
    NONE(0),
    INFO(1),       // Obstacles > 3m
    NAVIGATION(2), // Turn left, turn right, destination reached
    WARNING(3),    // Obstacles 1.5m - 3m
    CRITICAL(4)    // Obstacles < 1.5m
}

/**
 * Continuous / cadenced haptic mode for CENTER obstacles under 3m.
 */
enum class ContinuousHapticMode {
    NONE,
    CENTER_WARNING,   // 1.5m - 3m
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
 * Supports directional pulse cadences (LEFT, CENTER, RIGHT), distance bands,
 * finite cadenced center danger pulses with hysteresis, and navigation cues.
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

    private var currentPriority: HapticPriority = HapticPriority.NONE
    private var currentSessionId: Long = 0L
    var currentContinuousMode: ContinuousHapticMode = ContinuousHapticMode.NONE
        private set
    private var lastCadenceTriggerTimeMs: Long = 0L

    // Requirement 7: Track active one-shot alert to enforce single vibration source
    var isAlertVibrating: Boolean = false
        private set

    init {
        Log.i(
            TAG,
            "HapticManager initialized. hasVibrator=$hasVibrator, hasAmplitudeControl=$hasAmplitudeControl, api=${Build.VERSION.SDK_INT}"
        )
    }

    /**
     * Triggers tactile feedback for an obstacle based on its distance and zone.
     * Respects directional pulse timing (LEFT: short-short, CENTER: long-short, RIGHT: short-short-short)
     * and distance bands (>3m: soft short, 1.5-3m: repeated medium, <1.5m: fast strong).
     */
    override fun vibrateForObstacleAlert(distanceMeters: Float, zone: Zone?) {
        if (!isEnabled || !hasVibrator) return

        val priority = when {
            distanceMeters < CRITICAL_DISTANCE_METERS -> HapticPriority.CRITICAL
            distanceMeters <= WARNING_DISTANCE_METERS -> HapticPriority.WARNING
            else -> HapticPriority.INFO
        }

        val pattern = buildObstaclePattern(distanceMeters, zone, isContinuous = false)
        val description = "ObstacleAlert (zone=$zone, dist=${String.format(java.util.Locale.US, "%.1f", distanceMeters)}m)"

        // Requirement 7: Mark one-shot alert active to prevent simultaneous cadence pulse
        isAlertVibrating = true
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
            description = description
        )
    }

    /**
     * Cadenced vibration while a CENTER obstacle is under 3m.
     * Requirement 5: Replaces indefinite looping with finite cadenced pulses re-triggered at a minimum interval.
     * Requirement 6: Hysteresis around the 1.5m critical threshold (0.3m deadband).
     * Requirement 7: Single vibration source - suppressed if a one-shot alert is currently active.
     */
    override fun updateContinuousCenterObstacle(distanceMeters: Float) {
        updateContinuousCenterObstacle(distanceMeters, timeProvider())
    }

    fun updateContinuousCenterObstacle(distanceMeters: Float, currentTimeMs: Long) {
        if (!isEnabled || !hasVibrator) return

        // Requirement 7: Do not trigger continuous cadence while one-shot alert is vibrating
        if (isAlertVibrating) {
            Log.d(TAG, "Continuous center cadence suppressed: one-shot alert is currently active.")
            return
        }

        if (distanceMeters > WARNING_DISTANCE_METERS) {
            clearContinuousCenterObstacle()
            return
        }

        // Requirement 6: Hysteresis around 1.5m threshold (0.3m deadband: [1.5m, 1.8m])
        val targetMode = when (currentContinuousMode) {
            ContinuousHapticMode.CENTER_CRITICAL -> {
                // To exit CRITICAL mode, distance must exceed 1.8m (1.5m + 0.3m deadband)
                if (distanceMeters >= CRITICAL_EXIT_THRESHOLD_METERS) {
                    ContinuousHapticMode.CENTER_WARNING
                } else {
                    ContinuousHapticMode.CENTER_CRITICAL
                }
            }
            ContinuousHapticMode.CENTER_WARNING -> {
                // To enter CRITICAL mode, distance must drop below 1.5m
                if (distanceMeters < CRITICAL_DISTANCE_METERS) {
                    ContinuousHapticMode.CENTER_CRITICAL
                } else {
                    ContinuousHapticMode.CENTER_WARNING
                }
            }
            ContinuousHapticMode.NONE -> {
                if (distanceMeters < CRITICAL_DISTANCE_METERS) {
                    ContinuousHapticMode.CENTER_CRITICAL
                } else {
                    ContinuousHapticMode.CENTER_WARNING
                }
            }
        }

        currentContinuousMode = targetMode

        // Requirement 5: Re-trigger at minimum interval (800ms) only while condition holds
        val elapsed = currentTimeMs - lastCadenceTriggerTimeMs
        if (elapsed < CADENCE_MIN_INTERVAL_MS && lastCadenceTriggerTimeMs > 0L) {
            return
        }
        lastCadenceTriggerTimeMs = currentTimeMs

        val targetPriority = if (targetMode == ContinuousHapticMode.CENTER_CRITICAL) {
            HapticPriority.CRITICAL
        } else {
            HapticPriority.WARNING
        }

        val pattern = buildObstaclePattern(distanceMeters, Zone.CENTER, isContinuous = true)
        val desc = "CadencedCenterObstacle (mode=$targetMode, dist=${String.format(java.util.Locale.US, "%.1f", distanceMeters)}m)"

        // Requirement 5: Finite pulse (repeatIndex = -1), never an infinite loop
        playPattern(
            timings = pattern.timings,
            amplitudes = pattern.amplitudes,
            repeatIndex = -1,
            priority = targetPriority,
            description = desc
        )
    }

    /**
     * Clears cadenced CENTER haptics if currently active.
     */
    override fun clearContinuousCenterObstacle() {
        if (currentContinuousMode != ContinuousHapticMode.NONE) {
            Log.d(TAG, "Clearing cadenced CENTER haptic mode: $currentContinuousMode")
            currentContinuousMode = ContinuousHapticMode.NONE
            lastCadenceTriggerTimeMs = 0L
            if (!isAlertVibrating) {
                cancel()
            }
        }
    }

    /**
     * Navigation pattern: Turn Left = 2 short pulses.
     */
    override fun vibrateTurnLeft() {
        val timings = longArrayOf(0, 110, 90, 110)
        val amplitudes = intArrayOf(0, 220, 0, 220)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Turn Left (2 short pulses)"
        )
    }

    /**
     * Navigation pattern: Turn Right = 3 short pulses.
     */
    override fun vibrateTurnRight() {
        val timings = longArrayOf(0, 110, 80, 110, 80, 110)
        val amplitudes = intArrayOf(0, 220, 0, 220, 0, 220)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Turn Right (3 short pulses)"
        )
    }

    /**
     * Navigation pattern: Destination Reached = 1 long pulse.
     */
    override fun vibrateDestinationReached() {
        val timings = longArrayOf(0, 650)
        val amplitudes = intArrayOf(0, 255)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.NAVIGATION,
            description = "Navigation: Destination Reached (1 long pulse)"
        )
    }

    /**
     * Short confirmation click / tick when toggling settings.
     */
    fun vibrateConfirmation() {
        val timings = longArrayOf(0, 50)
        val amplitudes = intArrayOf(0, 150)
        playPattern(
            timings = timings,
            amplitudes = amplitudes,
            repeatIndex = -1,
            priority = HapticPriority.INFO,
            description = "Settings toggle confirmation pulse"
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
        lastCadenceTriggerTimeMs = 0L
        try {
            controller.cancel()
            Log.d(TAG, "Vibrator cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibrator", e)
        }
    }

    private fun playPattern(
        timings: LongArray,
        amplitudes: IntArray,
        repeatIndex: Int,
        priority: HapticPriority,
        description: String
    ): Boolean {
        if (!isEnabled || !hasVibrator) {
            Log.d(TAG, "Haptics disabled or no vibrator. Skipping $description")
            return false
        }

        // Never queue up old vibrations; drop lower priority vibrations
        if (priority.value < currentPriority.value) {
            Log.d(TAG, "Suppressed '$description' ($priority) due to higher priority active: $currentPriority")
            return false
        }

        // Cancel previous vibration when equal or higher priority starts
        try {
            controller.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling previous vibration", e)
        }

        currentPriority = priority
        val sessionId = ++currentSessionId

        try {
            controller.vibrate(
                timings = timings,
                amplitudes = if (hasAmplitudeControl) amplitudes else null,
                repeatIndex = repeatIndex
            )
            Log.i(TAG, "Triggered haptic: $description | Priority: $priority | Repeat: $repeatIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate for $description", e)
            currentPriority = HapticPriority.NONE
            isAlertVibrating = false
            return false
        }

        // If one-shot, reset priority after duration completes
        if (repeatIndex == -1) {
            val totalDurationMs = timings.sum()
            handler?.postDelayed({
                if (currentSessionId == sessionId) {
                    currentPriority = HapticPriority.NONE
                    Log.d(TAG, "Haptic finished: $description. Priority reset to NONE.")
                }
            }, totalDurationMs)
        }

        return true
    }

    private data class Pattern(val timings: LongArray, val amplitudes: IntArray)

    private fun buildObstaclePattern(
        distanceMeters: Float,
        zone: Zone?,
        isContinuous: Boolean
    ): Pattern {
        val isCritical = distanceMeters < CRITICAL_DISTANCE_METERS
        val isWarning = !isCritical && distanceMeters <= WARNING_DISTANCE_METERS

        return when {
            // Under 1.5m: Fast strong pulses (finite 3-cycle cadence for center)
            isCritical -> {
                val amp = AMPLITUDE_STRONG
                when (zone) {
                    Zone.LEFT -> {
                        Pattern(
                            timings = longArrayOf(0, 80, 50, 80, 100, 80, 50, 80),
                            amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
                        )
                    }
                    Zone.RIGHT -> {
                        Pattern(
                            timings = longArrayOf(0, 70, 50, 70, 50, 70, 100, 70, 50, 70, 50, 70),
                            amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp, 0, amp, 0, amp)
                        )
                    }
                    else -> { // CENTER or null: 3-cycle finite pulse
                        if (isContinuous) {
                            Pattern(
                                timings = longArrayOf(0, 120, 50, 70, 70, 120, 50, 70, 70, 120, 50, 70),
                                amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp, 0, amp, 0, amp)
                            )
                        } else {
                            Pattern(
                                timings = longArrayOf(0, 140, 50, 70, 100, 140, 50, 70),
                                amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
                            )
                        }
                    }
                }
            }

            // 1.5m - 3m: Repeated medium pulses (finite 3-cycle cadence for center)
            isWarning -> {
                val amp = AMPLITUDE_MEDIUM
                when (zone) {
                    Zone.LEFT -> {
                        Pattern(
                            timings = longArrayOf(0, 100, 80, 100, 250, 100, 80, 100),
                            amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
                        )
                    }
                    Zone.RIGHT -> {
                        Pattern(
                            timings = longArrayOf(0, 80, 60, 80, 60, 80, 250, 80, 60, 80, 60, 80),
                            amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
                        )
                    }
                    else -> { // CENTER or null: 3-cycle finite medium pulse
                        if (isContinuous) {
                            Pattern(
                                timings = longArrayOf(0, 140, 70, 70, 80, 140, 70, 70, 80, 140, 70, 70),
                                amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp, 0, amp, 0, amp)
                            )
                        } else {
                            Pattern(
                                timings = longArrayOf(0, 180, 80, 80, 250, 180, 80, 80),
                                amplitudes = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
                            )
                        }
                    }
                }
            }

            // Over 3m: Single soft short pulse
            else -> {
                val amp = AMPLITUDE_SOFT
                when (zone) {
                    Zone.LEFT -> Pattern(
                        timings = longArrayOf(0, 70, 60, 70),
                        amplitudes = intArrayOf(0, amp, 0, amp)
                    )
                    Zone.RIGHT -> Pattern(
                        timings = longArrayOf(0, 60, 50, 60, 50, 60),
                        amplitudes = intArrayOf(0, amp, 0, amp, 0, amp)
                    )
                    Zone.CENTER -> Pattern(
                        timings = longArrayOf(0, 130, 60, 60),
                        amplitudes = intArrayOf(0, amp, 0, amp)
                    )
                    else -> Pattern(
                        timings = longArrayOf(0, 90),
                        amplitudes = intArrayOf(0, amp)
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "HapticManager"

        const val CRITICAL_DISTANCE_METERS = 1.5f
        const val HYSTERESIS_DEADBAND_METERS = 0.3f
        const val CRITICAL_EXIT_THRESHOLD_METERS = CRITICAL_DISTANCE_METERS + HYSTERESIS_DEADBAND_METERS // 1.8f
        const val WARNING_DISTANCE_METERS = 3.0f

        const val CADENCE_MIN_INTERVAL_MS = 800L

        const val AMPLITUDE_SOFT = 95
        const val AMPLITUDE_MEDIUM = 180
        const val AMPLITUDE_STRONG = 255
    }
}
