package com.example.navisense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeVibrationController : VibrationController {
    var hasVibratorVal = true
    var hasAmplitudeVal = true
    var vibrateCount = 0
    var cancelCount = 0
    var lastRepeatIndex: Int = -999
    var lastTimings: LongArray? = null
    var lastAmplitudes: IntArray? = null

    override fun hasVibrator(): Boolean = hasVibratorVal
    override fun hasAmplitudeControl(): Boolean = hasAmplitudeVal

    override fun vibrate(timings: LongArray, amplitudes: IntArray?, repeatIndex: Int) {
        vibrateCount++
        lastTimings = timings
        lastAmplitudes = amplitudes
        lastRepeatIndex = repeatIndex
    }

    override fun cancel() {
        cancelCount++
    }
}

class HapticManagerTest {

    private lateinit var fakeController: FakeVibrationController
    private var simulatedTimeMs: Long = 1000L
    private lateinit var hapticManager: HapticManager

    @Before
    fun setup() {
        fakeController = FakeVibrationController()
        simulatedTimeMs = 1000L

        hapticManager = HapticManager(
            context = null,
            timeProvider = { simulatedTimeMs },
            controllerOverride = fakeController
        )
    }

    @Test
    fun finiteCadencePulse_replacesIndefiniteLooping() {
        // Trigger center obstacle cadence
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)

        assertEquals("Controller must be called for center cadence pulse", 1, fakeController.vibrateCount)
        // Requirement 5: repeatIndex must be -1 (finite pulse), NOT 0 (infinite loop)
        assertEquals("Vibration repeatIndex must be -1 for finite cadenced pulse", -1, fakeController.lastRepeatIndex)
    }

    @Test
    fun cadenceMinimumInterval_throttlesPulsesUnder800ms() {
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals(1, fakeController.vibrateCount)

        // 400ms later: condition still holds, but interval < 800ms -> should NOT fire
        simulatedTimeMs = 1400L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals("Pulse must not re-trigger within 800ms interval", 1, fakeController.vibrateCount)

        // 900ms after first pulse (t = 1900ms): interval >= 800ms -> should fire next pulse
        simulatedTimeMs = 1900L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals("Pulse should re-trigger once 800ms interval has elapsed", 2, fakeController.vibrateCount)
    }

    @Test
    fun hysteresis_criticalThreshold0_3mDeadband_preventsModeThrashing() {
        // 1. Initial distance 2.0m -> enters WARNING mode
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)

        // 2. Distance drops to 1.6m -> still WARNING mode (>= 1.5m)
        simulatedTimeMs = 2000L
        hapticManager.updateContinuousCenterObstacle(1.6f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)

        // 3. Distance drops to 1.4m (< 1.5m) -> enters CRITICAL mode
        simulatedTimeMs = 3000L
        hapticManager.updateContinuousCenterObstacle(1.4f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 4. User takes a step, distance rises to 1.55m -> MUST STAY CRITICAL (deadband threshold is 1.8m)
        simulatedTimeMs = 4000L
        hapticManager.updateContinuousCenterObstacle(1.55f, simulatedTimeMs)
        assertEquals("Must remain in CRITICAL mode within 0.3m deadband (1.55m < 1.8m)", ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 5. User takes another step, distance is 1.70m -> MUST STILL STAY CRITICAL (< 1.8m)
        simulatedTimeMs = 5000L
        hapticManager.updateContinuousCenterObstacle(1.70f, simulatedTimeMs)
        assertEquals("Must remain in CRITICAL mode within 0.3m deadband (1.70m < 1.8m)", ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 6. User moves past obstacle, distance exceeds 1.8m (1.85m >= 1.8m) -> drops back to WARNING mode
        simulatedTimeMs = 6000L
        hapticManager.updateContinuousCenterObstacle(1.85f, simulatedTimeMs)
        assertEquals("Must transition back to WARNING mode once distance >= 1.8m", ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)
    }

    @Test
    fun singleVibrationSource_oneShotAlertSuppressesContinuousCadence() {
        // Trigger one-shot alert
        hapticManager.vibrateForObstacleAlert(2.0f, Zone.CENTER)

        assertTrue("isAlertVibrating must be true while one-shot alert is active", hapticManager.isAlertVibrating)
        val countAfterAlert = fakeController.vibrateCount

        // Attempting to trigger continuous center cadence while alert is active must be suppressed
        simulatedTimeMs = 2000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)

        assertEquals("Continuous cadence must be suppressed while one-shot alert is active", countAfterAlert, fakeController.vibrateCount)
    }
}
