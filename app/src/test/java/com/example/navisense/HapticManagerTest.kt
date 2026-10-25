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
    fun gapShrinksWithDistance() {
        // Far obstacle (2-3 m): ~1200 ms gap
        hapticManager.vibrateFarObstacle()
        val farTimings = fakeController.lastTimings!!
        assertEquals("Far obstacle must start with delay 0", 0L, farTimings[0])
        assertEquals("Far obstacle tick must be ~80-100 ms (90 ms)", 90L, farTimings[1])
        assertEquals("Far obstacle gap must be 1200 ms", 1200L, farTimings[2])
        assertEquals("Far obstacle second tick must be 90 ms", 90L, farTimings[3])
        val gapFar = farTimings[2]

        // Near obstacle (1.5-2 m): ~600 ms gap
        hapticManager.cancel()
        hapticManager.vibrateNearObstacle()
        val nearTimings = fakeController.lastTimings!!
        assertEquals("Near obstacle gap must be 600 ms", 600L, nearTimings[2])
        val gapNear = nearTimings[2]

        // Danger obstacle (< 1.5 m): ~250 ms gap with strongest amplitude
        hapticManager.cancel()
        hapticManager.vibrateDangerObstacle()
        val dangerTimings = fakeController.lastTimings!!
        val dangerAmplitudes = fakeController.lastAmplitudes!!
        assertEquals("Danger obstacle gap must be 250 ms", 250L, dangerTimings[2])
        val gapDanger = dangerTimings[2]

        // Assert gap shrinks with distance
        assertTrue("Gap must shrink with distance: Far ($gapFar) > Near ($gapNear)", gapFar > gapNear)
        assertTrue("Gap must shrink with distance: Near ($gapNear) > Danger ($gapDanger)", gapNear > gapDanger)
        assertEquals("Danger obstacle must have strongest amplitude (255)", 255, dangerAmplitudes[1])
    }

    @Test
    fun sideObstaclesProduceNoVibration() {
        // Side obstacles (LEFT and RIGHT) produce no vibration (voice only)
        hapticManager.vibrateForObstacleAlert(1.0f, Zone.LEFT)
        assertEquals("LEFT obstacle must produce 0 vibrations", 0, fakeController.vibrateCount)

        hapticManager.vibrateForObstacleAlert(1.0f, Zone.RIGHT)
        assertEquals("RIGHT obstacle must produce 0 vibrations", 0, fakeController.vibrateCount)

        // Center obstacle beyond 3m also produces no vibration
        hapticManager.vibrateForObstacleAlert(3.5f, Zone.CENTER)
        assertEquals("CENTER obstacle > 3m must produce 0 vibrations", 0, fakeController.vibrateCount)

        // CENTER obstacle within 3m produces vibration
        hapticManager.vibrateForObstacleAlert(2.0f, Zone.CENTER)
        assertEquals("CENTER obstacle <= 3m must produce vibration", 1, fakeController.vibrateCount)
    }

    @Test
    fun turnCuesAre2And3LongPulses_andArrivalIs1LongPulse() {
        // Turn left = 2 long pulses (400 ms each, 400 ms gap)
        hapticManager.vibrateTurnLeft()
        val leftTimings = fakeController.lastTimings!!
        assertEquals("Turn Left must have 4 timing elements [0, 400, 400, 400]", 4, leftTimings.size)
        assertEquals(0L, leftTimings[0])
        assertEquals(400L, leftTimings[1])
        assertEquals(400L, leftTimings[2])
        assertEquals(400L, leftTimings[3])

        // Turn right = 3 long pulses (400 ms each, 400 ms gap)
        hapticManager.cancel()
        hapticManager.vibrateTurnRight()
        val rightTimings = fakeController.lastTimings!!
        assertEquals("Turn Right must have 6 timing elements [0, 400, 400, 400, 400, 400]", 6, rightTimings.size)
        assertEquals(0L, rightTimings[0])
        assertEquals(400L, rightTimings[1])
        assertEquals(400L, rightTimings[2])
        assertEquals(400L, rightTimings[3])
        assertEquals(400L, rightTimings[4])
        assertEquals(400L, rightTimings[5])

        // Arrival = 1 very long pulse (1200 ms)
        hapticManager.cancel()
        hapticManager.vibrateDestinationReached()
        val arrivalTimings = fakeController.lastTimings!!
        assertEquals(2, arrivalTimings.size)
        assertEquals(0L, arrivalTimings[0])
        assertEquals(1200L, arrivalTimings[1])
    }

    @Test
    fun priorityOrder_criticalOverNavigationOverNormal() {
        // 1. Normal obstacle ticks (1.5-3m) active
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals(1, fakeController.vibrateCount)

        // 2. Navigation cue arrives while normal obstacle ticks active:
        // Navigation (3) > Normal obstacle (2) -> navigation cue preempts/pauses normal obstacle
        simulatedTimeMs = 1100L
        hapticManager.vibrateTurnLeft(simulatedTimeMs)
        assertEquals(2, fakeController.vibrateCount)
        assertEquals("Turn left pattern must be playing", 4, fakeController.lastTimings!!.size)

        // 3. Normal obstacle ticks attempted while navigation is active (t = 1500L, nav duration = 1200ms -> ends at 2300L)
        // Navigation (3) > Normal obstacle (2) -> normal obstacle suppressed
        simulatedTimeMs = 1500L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals("Normal obstacle must be suppressed while navigation cue is playing", 2, fakeController.vibrateCount)

        // 4. Critical obstacle (< 1.5m) arrives at t = 1600L:
        // Critical obstacle (< 1.5m) (4) > Navigation cue (3) -> Critical preempts navigation immediately!
        simulatedTimeMs = 1600L
        hapticManager.updateContinuousCenterObstacle(1.2f, simulatedTimeMs)
        assertEquals("Critical obstacle must preempt navigation cue immediately", 3, fakeController.vibrateCount)
        assertEquals("Critical pattern must be playing (gap = 250ms)", 250L, fakeController.lastTimings!![2])

        // 5. While critical is active, navigation cue attempted:
        // Critical (4) > Navigation (3) -> navigation cue cannot preempt critical
        simulatedTimeMs = 1700L
        hapticManager.vibrateTurnRight(simulatedTimeMs)
        assertEquals("Navigation cue must be suppressed while critical obstacle alert is active", 3, fakeController.vibrateCount)
    }

    @Test
    fun navigationCueBrieflyPausesObstacleTicks_andResumesThem() {
        // Start normal obstacle ticks at 1.8m (Near obstacle, gap = 600ms)
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(1.8f, simulatedTimeMs)
        assertEquals(1, fakeController.vibrateCount)
        assertEquals(600L, fakeController.lastTimings!![2])

        // Navigation cue starts at 1100L (duration = 1200ms, ends at 2300L)
        simulatedTimeMs = 1100L
        hapticManager.vibrateTurnLeft(simulatedTimeMs)
        assertEquals(2, fakeController.vibrateCount)
        assertTrue("Obstacle ticks must be paused during navigation cue", hapticManager.isObstacleTicksPaused)

        // Navigation cue finishes at 2300L
        simulatedTimeMs = 2300L
        hapticManager.onPatternCompleted(hapticManager.currentSessionId, simulatedTimeMs)

        // Obstacle ticks resume automatically
        assertEquals("Obstacle ticks must resume after navigation cue finishes", 3, fakeController.vibrateCount)
        assertFalse("isObstacleTicksPaused must be false after resuming", hapticManager.isObstacleTicksPaused)
        assertEquals(600L, fakeController.lastTimings!![2]) // Near gap
    }

    @Test
    fun noOverlap_equalPriorityDoesNotOverlap_andNoIndefiniteLooping_andMaxDuration() {
        // Trigger Turn Left at t = 1000L (duration 1200ms, ends at 2200L)
        simulatedTimeMs = 1000L
        hapticManager.vibrateTurnLeft(simulatedTimeMs)
        assertEquals(1, fakeController.vibrateCount)

        // At t = 1500L, another navigation cue is triggered:
        // Equal priority (NAVIGATION == NAVIGATION) must NOT overlap while first is playing
        simulatedTimeMs = 1500L
        hapticManager.vibrateTurnRight(simulatedTimeMs)
        assertEquals("Equal priority pattern must not overlap ongoing pattern", 1, fakeController.vibrateCount)

        // Indefinite looping must NEVER be used: repeatIndex must always be -1
        assertEquals("repeatIndex must be -1 (finite pattern, no indefinite loop)", -1, fakeController.lastRepeatIndex)

        // Maximum single pattern duration enforcement
        simulatedTimeMs = 3000L // after turn left has finished
        hapticManager.vibrateTurnLeft(simulatedTimeMs)
        assertTrue("Single pattern duration must be capped to MAX_PATTERN_DURATION_MS (2500ms)", fakeController.lastTimings!!.sum() <= HapticManager.MAX_PATTERN_DURATION_MS)
    }

    @Test
    fun minimumGap_allPausesBetweenPulsesAtLeast150ms() {
        // Verify every obstacle pattern pause is >= 150ms
        listOf(2.5f, 1.8f, 1.0f).forEach { dist ->
            val pattern = hapticManager.buildObstaclePattern(dist)
            // Even indices in pattern.timings after 0: odd pulse gaps (timings[2], timings[4], etc.)
            for (i in 2 until pattern.timings.size step 2) {
                val pauseMs = pattern.timings[i]
                assertTrue("Obstacle pause ($pauseMs ms at dist $dist) must be >= 150 ms", pauseMs >= HapticManager.MIN_PAUSE_MS)
            }
        }

        // Verify turn cues pauses are >= 150ms
        hapticManager.cancel()
        hapticManager.vibrateTurnLeft()
        val leftTimings = fakeController.lastTimings!!
        assertTrue("Turn Left pause (${leftTimings[2]}ms) must be >= 150 ms", leftTimings[2] >= HapticManager.MIN_PAUSE_MS)

        hapticManager.cancel()
        hapticManager.vibrateTurnRight()
        val rightTimings = fakeController.lastTimings!!
        assertTrue("Turn Right pause 1 (${rightTimings[2]}ms) must be >= 150 ms", rightTimings[2] >= HapticManager.MIN_PAUSE_MS)
        assertTrue("Turn Right pause 2 (${rightTimings[4]}ms) must be >= 150 ms", rightTimings[4] >= HapticManager.MIN_PAUSE_MS)
    }

    @Test
    fun strengthScaling_scalesAmplitudesProportionally() {
        // High strength (1.0x)
        hapticManager.vibrationStrength = VibrationStrength.HIGH
        hapticManager.vibrateDangerObstacle()
        val highAmps = fakeController.lastAmplitudes!!
        val highTickAmp = highAmps[1]

        // Medium strength (0.75x)
        hapticManager.cancel()
        hapticManager.vibrationStrength = VibrationStrength.MEDIUM
        hapticManager.vibrateDangerObstacle()
        val medAmps = fakeController.lastAmplitudes!!
        val medTickAmp = medAmps[1]

        // Low strength (0.5x)
        hapticManager.cancel()
        hapticManager.vibrationStrength = VibrationStrength.LOW
        hapticManager.vibrateDangerObstacle()
        val lowAmps = fakeController.lastAmplitudes!!
        val lowTickAmp = lowAmps[1]

        // Verify strictly decreasing scaling: High > Medium > Low
        assertTrue("High amplitude ($highTickAmp) must be > Medium amplitude ($medTickAmp)", highTickAmp > medTickAmp)
        assertTrue("Medium amplitude ($medTickAmp) must be > Low amplitude ($lowTickAmp)", medTickAmp > lowTickAmp)

        // Verify all non-zero amplitudes are valid [1, 255]
        assertTrue(highTickAmp in 1..255)
        assertTrue(medTickAmp in 1..255)
        assertTrue(lowTickAmp in 1..255)
    }

    @Test
    fun stopVibrationImmediately_whenNoCenterObstacleWithin3m() {
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals(1, fakeController.vibrateCount)
        val cancelsBefore = fakeController.cancelCount

        // Obstacle leaves CENTER / moves beyond 3m
        hapticManager.clearContinuousCenterObstacle()

        // Vibration must be cancelled immediately
        assertTrue("Hardware vibration must be cancelled immediately when no CENTER obstacle remains", fakeController.cancelCount > cancelsBefore)
        assertEquals(ContinuousHapticMode.NONE, hapticManager.currentContinuousMode)
        assertEquals(HapticPriority.NONE, hapticManager.currentPriority)
    }

    @Test
    fun hysteresis_criticalThreshold0_3mDeadband_preventsModeThrashing() {
        // 1. Initial distance 2.0m -> enters WARNING mode
        simulatedTimeMs = 1000L
        hapticManager.updateContinuousCenterObstacle(2.0f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)

        // 2. Distance drops to 1.6m -> still WARNING mode (>= 1.5m)
        simulatedTimeMs = 3000L
        hapticManager.updateContinuousCenterObstacle(1.6f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)

        // 3. Distance drops to 1.4m (< 1.5m) -> enters CRITICAL mode
        simulatedTimeMs = 5000L
        hapticManager.updateContinuousCenterObstacle(1.4f, simulatedTimeMs)
        assertEquals(ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 4. User takes a step, distance rises to 1.55m -> MUST STAY CRITICAL (deadband threshold is 1.8m)
        simulatedTimeMs = 7000L
        hapticManager.updateContinuousCenterObstacle(1.55f, simulatedTimeMs)
        assertEquals("Must remain in CRITICAL mode within 0.3m deadband (1.55m < 1.8m)", ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 5. User takes another step, distance is 1.70m -> MUST STILL STAY CRITICAL (< 1.8m)
        simulatedTimeMs = 9000L
        hapticManager.updateContinuousCenterObstacle(1.70f, simulatedTimeMs)
        assertEquals("Must remain in CRITICAL mode within 0.3m deadband (1.70m < 1.8m)", ContinuousHapticMode.CENTER_CRITICAL, hapticManager.currentContinuousMode)

        // 6. User moves past obstacle, distance exceeds 1.8m (1.85m >= 1.8m) -> drops back to WARNING mode
        simulatedTimeMs = 11000L
        hapticManager.updateContinuousCenterObstacle(1.85f, simulatedTimeMs)
        assertEquals("Must transition back to WARNING mode once distance >= 1.8m", ContinuousHapticMode.CENTER_WARNING, hapticManager.currentContinuousMode)
    }
}
