package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeVoiceNotifier : VoiceNotifier {
    val spokenMessages = mutableListOf<String>()
    val interruptedList = mutableListOf<Boolean>()
    var stopCalled = false

    override fun speak(text: String, interrupt: Boolean) {
        spokenMessages.add(text)
        interruptedList.add(interrupt)
    }

    override fun stop() {
        stopCalled = true
    }

    fun clear() {
        spokenMessages.clear()
        interruptedList.clear()
        stopCalled = false
    }
}

class FakeHapticNotifier : HapticNotifier {
    override var isEnabled: Boolean = true
    val obstacleAlerts = mutableListOf<Pair<Float, Zone?>>()
    var continuousCenterDistance: Float? = null
    var clearContinuousCalled = false
    var cancelCalled = false
    var continuousCenterCallCount = 0

    override fun vibrateForObstacleAlert(distanceMeters: Float, zone: Zone?) {
        obstacleAlerts.add(distanceMeters to zone)
    }

    override fun updateContinuousCenterObstacle(distanceMeters: Float) {
        continuousCenterCallCount++
        continuousCenterDistance = distanceMeters
    }

    override fun clearContinuousCenterObstacle() {
        clearContinuousCalled = true
        continuousCenterDistance = null
    }

    override fun cancel() {
        cancelCalled = true
    }

    fun clearHistory() {
        obstacleAlerts.clear()
        clearContinuousCalled = false
        cancelCalled = false
        continuousCenterCallCount = 0
    }
}

class DecisionEngineTest {

    private lateinit var fakeVoice: FakeVoiceNotifier
    private lateinit var fakeHaptics: FakeHapticNotifier
    private var currentTimeMs: Long = 1000L
    private lateinit var engine: DecisionEngine

    @Before
    fun setup() {
        fakeVoice = FakeVoiceNotifier()
        fakeHaptics = FakeHapticNotifier()
        currentTimeMs = 1000L
        engine = DecisionEngine(
            voiceManager = fakeVoice,
            hapticManager = fakeHaptics,
            timeProvider = { currentTimeMs }
        )
    }

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        val r = RectF()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    private fun createDetection(
        label: String,
        distanceMeters: Float,
        zone: Zone,
        score: Float = 0.9f,
        box: RectF = rectF(0f, 0f, 100f, 100f),
        frameWidth: Int = 1000,
        frameHeight: Int = 1000,
        boxWidth: Float = 100f,
        boxHeight: Float = 100f
    ): Detection {
        return Detection(
            label = label,
            score = score,
            boundingBox = box,
            zone = zone,
            distanceMeters = distanceMeters,
            frameWidthPx = frameWidth,
            frameHeightPx = frameHeight,
            boxWidthPx = boxWidth,
            boxHeightPx = boxHeight
        )
    }

    private fun feedFrames(detections: List<Detection>, count: Int = 3, frameWidth: Int = 1000, frameHeight: Int = 1000) {
        repeat(count) {
            engine.processDetections(detections, frameWidth, frameHeight)
        }
    }

    @Test
    fun temporalPersistenceRule_triggersOnlyOnThirdConsecutiveFrame() {
        val detection = listOf(createDetection("person", 2.0f, Zone.CENTER))

        // Frame 1: no speech, no haptics
        engine.processDetections(detection)
        assertEquals("Frame 1 must not trigger alert", 0, fakeVoice.spokenMessages.size)
        assertEquals(0, fakeHaptics.obstacleAlerts.size)

        // Frame 2: no speech, no haptics
        engine.processDetections(detection)
        assertEquals("Frame 2 must not trigger alert", 0, fakeVoice.spokenMessages.size)
        assertEquals(0, fakeHaptics.obstacleAlerts.size)

        // Frame 3: triggers alert and haptics!
        engine.processDetections(detection)
        assertEquals("Frame 3 must trigger speech", 1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("Person ahead"))
    }

    @Test
    fun whitelistFiltering_ignoresNonWhitelistedObjects() {
        val nonWhitelisted = listOf(
            createDetection("tv", 2.0f, Zone.CENTER),
            createDetection("clock", 1.5f, Zone.LEFT),
            createDetection("book", 1.0f, Zone.CENTER),
            createDetection("vase", 2.5f, Zone.RIGHT)
        )

        feedFrames(nonWhitelisted, count = 3)

        assertEquals("Non-whitelisted objects should produce 0 spoken alerts", 0, fakeVoice.spokenMessages.size)
        assertEquals("Non-whitelisted objects should produce 0 haptic alerts", 0, fakeHaptics.obstacleAlerts.size)
        assertFalse("Continuous center haptics should not be set for non-whitelisted objects", fakeHaptics.continuousCenterDistance != null)
    }

    @Test
    fun whitelistFiltering_allowsWhitelistedObjects() {
        val whitelisted = listOf(
            createDetection("person", 2.0f, Zone.CENTER),
            createDetection("chair", 3.0f, Zone.LEFT)
        )

        feedFrames(whitelisted, count = 3)

        assertTrue("Whitelisted objects should produce speech", fakeVoice.spokenMessages.isNotEmpty())
    }

    @Test
    fun distanceFiltering_ignoresFartherThan5Meters() {
        val farDetections = listOf(
            createDetection("person", 5.2f, Zone.CENTER),
            createDetection("car", 7.0f, Zone.LEFT)
        )

        feedFrames(farDetections, count = 3)

        assertEquals("Detections > 5m should be ignored", 0, fakeVoice.spokenMessages.size)
        assertEquals("Detections > 5m should produce no haptics", 0, fakeHaptics.obstacleAlerts.size)

        // Obstacle at 4.8m should be accepted after 3 frames
        val validDetection = listOf(createDetection("person", 4.8f, Zone.CENTER))
        feedFrames(validDetection, count = 3)
        assertEquals("Detections <= 5m should be accepted", 1, fakeVoice.spokenMessages.size)
    }

    @Test
    fun largeBoxRule_vehicleSaysLargeObstacleWithoutNumber_andSuppressesUrgentInterrupt() {
        // Vehicle ("car") taking up 85% of frame height (850px / 1000px) with close distance 1.0m (< 1.5m)
        val hugeBox = rectF(50f, 50f, 600f, 900f) // height = 850px
        val hugeDetection = listOf(
            createDetection("car", 1.0f, Zone.CENTER, score = 0.9f, box = hugeBox, frameHeight = 1000, boxWidth = 550f, boxHeight = 850f)
        )

        feedFrames(hugeDetection, count = 3)

        assertEquals(1, fakeVoice.spokenMessages.size)
        val speech = fakeVoice.spokenMessages[0]
        assertEquals("Large obstacle ahead. Stop.", speech)
        assertFalse("Spoken message must NOT contain a specific distance number", speech.contains("meter") || speech.contains("1"))
        assertFalse("Urgent speech interrupt MUST be suppressed for unreliable large vehicle boxes", fakeVoice.interruptedList[0])
    }

    @Test
    fun largeBoxRule_nonVehicleKeepsUrgentAlert_andCriticalHaptic_andClampsDistanceTo0_5m() {
        // Non-vehicle ("person") taking up 85% of frame height (850px / 1000px) with raw distance 0.3m (< 0.5m)
        val hugeBox = rectF(50f, 50f, 600f, 900f) // height = 850px
        val personDetection = listOf(
            createDetection("person", 0.3f, Zone.CENTER, score = 0.9f, box = hugeBox, frameHeight = 1000, boxWidth = 550f, boxHeight = 850f)
        )

        feedFrames(personDetection, count = 3)

        assertEquals(1, fakeVoice.spokenMessages.size)
        val speech = fakeVoice.spokenMessages[0]
        assertEquals("Person ahead, 0.5 meters. Step right.", speech)
        assertTrue("Urgent speech interrupt MUST be true for close non-vehicle", fakeVoice.interruptedList[0])

        // Verify haptic feedback triggered in critical band (< 1.5m)
        assertEquals(1, fakeHaptics.obstacleAlerts.size)
        val (hapticDist, hapticZone) = fakeHaptics.obstacleAlerts[0]
        assertEquals(Zone.CENTER, hapticZone)
        assertEquals(0.5f, hapticDist, 0.01f)
        assertTrue("Haptic distance must be in critical band (< 1.5m)", hapticDist < HapticManager.CRITICAL_DISTANCE_METERS)
    }

    @Test
    fun largeBoxRule_nonVehicleWith1mDistance_keepsUrgentAlert_andCriticalHaptic() {
        // Non-vehicle ("chair") taking up 85% of frame height with distance 1.0m
        val hugeBox = rectF(50f, 50f, 600f, 900f)
        val chairDetection = listOf(
            createDetection("chair", 1.0f, Zone.CENTER, score = 0.9f, box = hugeBox, frameHeight = 1000, boxWidth = 550f, boxHeight = 850f)
        )

        feedFrames(chairDetection, count = 3)

        assertEquals(1, fakeVoice.spokenMessages.size)
        val speech = fakeVoice.spokenMessages[0]
        assertEquals("Chair ahead, 1 meter. Step right.", speech)
        assertTrue("Urgent speech interrupt MUST be true for close chair", fakeVoice.interruptedList[0])
        assertEquals(1, fakeHaptics.obstacleAlerts.size)
        val (hapticDist, _) = fakeHaptics.obstacleAlerts[0]
        assertTrue("Haptic distance must be in critical band (< 1.5m)", hapticDist < HapticManager.CRITICAL_DISTANCE_METERS)
    }

    @Test
    fun singleVibrationSource_oneShotAlertSuppressesSimultaneousCenterCadence() {
        // CENTER obstacle triggers alert on 3rd frame
        val centerObstacle = listOf(createDetection("chair", 2.2f, Zone.CENTER))

        feedFrames(centerObstacle, count = 3)

        // When the one-shot obstacle alert triggers, it takes exclusive priority
        assertEquals("One-shot haptic must be triggered for announcement", 1, fakeHaptics.obstacleAlerts.size)
        assertEquals("Continuous center haptic should NOT be triggered on the same frame as one-shot alert", 0, fakeHaptics.continuousCenterCallCount)
    }

    @Test
    fun continuousCenterHaptics_firesWhenNoOneShotAlertOnThatFrame() {
        val centerObstacle = listOf(createDetection("chair", 2.2f, Zone.CENTER))

        // 3 frames trigger initial one-shot alert
        feedFrames(centerObstacle, count = 3)
        fakeHaptics.clearHistory()

        // 4th frame: voice alert is on cooldown (no one-shot alert fires)
        currentTimeMs = 1200L
        engine.processDetections(centerObstacle, 1000, 1000)

        assertEquals("No new one-shot alert on cooldown", 0, fakeHaptics.obstacleAlerts.size)
        assertEquals("Cadenced center haptic should fire when one-shot alert is not firing", 1, fakeHaptics.continuousCenterCallCount)
        assertEquals(2.2f, fakeHaptics.continuousCenterDistance)
    }

    @Test
    fun cooldown_repeatsOnlyWhenDistanceChangesByAtLeast1mOrAfter8s() {
        // Initial detection at 3.5m, time = 1000ms (3 frames)
        currentTimeMs = 1000L
        feedFrames(listOf(createDetection("person", 3.5f, Zone.CENTER)), count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)

        // 2 seconds later (time = 3000ms), distance changed by only 0.3m (3.2m) -> suppressed
        currentTimeMs = 3000L
        fakeVoice.clear()
        engine.processDetections(listOf(createDetection("person", 3.2f, Zone.CENTER)))
        assertEquals("Alert should be suppressed when <1m change and <8s elapsed", 0, fakeVoice.spokenMessages.size)

        // 3 seconds later (time = 4000ms), distance changed to 2.4m (diff = 1.1m >= 1.0m) -> triggers!
        // (feed 5 frames with 2.4m so median reflects the new distance)
        currentTimeMs = 4000L
        fakeVoice.clear()
        feedFrames(listOf(createDetection("person", 2.4f, Zone.CENTER)), count = 5)
        assertEquals("Alert should trigger when distance changes by >= 1m", 1, fakeVoice.spokenMessages.size)

        // 5 seconds later (time = 9000ms, elapsed 5s < 8s), distance is 2.3m (diff 0.1m < 1m) -> suppressed
        currentTimeMs = 9000L
        fakeVoice.clear()
        engine.processDetections(listOf(createDetection("person", 2.3f, Zone.CENTER)))
        assertEquals("Alert should be suppressed when <1m change and <8s elapsed", 0, fakeVoice.spokenMessages.size)

        // 9 seconds after the 4000ms alert (time = 13000ms, elapsed 9s > 8s), same distance -> triggers!
        currentTimeMs = 13000L
        fakeVoice.clear()
        engine.processDetections(listOf(createDetection("person", 2.3f, Zone.CENTER)))
        assertEquals("Alert should trigger when >= 8s elapsed even without distance change", 1, fakeVoice.spokenMessages.size)
    }

    @Test
    fun sideObstaclesProduceNoVibration_voiceOnly() {
        // LEFT obstacle: speaks message, but produces NO vibration
        engine.clear()
        fakeVoice.clear()
        fakeHaptics.clearHistory()
        feedFrames(listOf(createDetection("person", 2.0f, Zone.LEFT)), count = 3)
        assertEquals("LEFT obstacle must trigger spoken voice cue", 1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("left"))
        assertEquals("LEFT obstacle must NOT trigger any haptic alert", 0, fakeHaptics.obstacleAlerts.size)
        assertEquals("Continuous center haptic must be null for side obstacle", null, fakeHaptics.continuousCenterDistance)

        // RIGHT obstacle: speaks message, but produces NO vibration
        engine.clear()
        fakeVoice.clear()
        fakeHaptics.clearHistory()
        feedFrames(listOf(createDetection("person", 2.0f, Zone.RIGHT)), count = 3)
        assertEquals("RIGHT obstacle must trigger spoken voice cue", 1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("right"))
        assertEquals("RIGHT obstacle must NOT trigger any haptic alert", 0, fakeHaptics.obstacleAlerts.size)
        assertEquals("Continuous center haptic must be null for side obstacle", null, fakeHaptics.continuousCenterDistance)

        // CENTER obstacle: triggers both voice and vibration
        engine.clear()
        fakeVoice.clear()
        fakeHaptics.clearHistory()
        feedFrames(listOf(createDetection("person", 2.0f, Zone.CENTER)), count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("CENTER obstacle <= 3m MUST trigger haptic alert", 1, fakeHaptics.obstacleAlerts.size)
    }

    @Test
    fun settingsToggles_voiceAndHapticsSeparately() {
        // Voice OFF, Haptics ON
        engine.isVoiceEnabled = false
        engine.isHapticsEnabled = true
        fakeVoice.clear()
        fakeHaptics.clearHistory()

        feedFrames(listOf(createDetection("bicycle", 2.0f, Zone.CENTER)), count = 3)
        assertEquals(0, fakeVoice.spokenMessages.size)
        assertEquals(1, fakeHaptics.obstacleAlerts.size)

        // Voice ON, Haptics OFF
        engine.clear()
        engine.isVoiceEnabled = true
        engine.isHapticsEnabled = false
        fakeVoice.clear()
        fakeHaptics.clearHistory()

        feedFrames(listOf(createDetection("bicycle", 2.0f, Zone.CENTER)), count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals(0, fakeHaptics.obstacleAlerts.size)

        // Both OFF
        engine.clear()
        engine.isVoiceEnabled = false
        engine.isHapticsEnabled = false
        fakeVoice.clear()
        fakeHaptics.clearHistory()

        feedFrames(listOf(createDetection("bicycle", 2.0f, Zone.CENTER)), count = 3)
        assertEquals(0, fakeVoice.spokenMessages.size)
        assertEquals(0, fakeHaptics.obstacleAlerts.size)
    }

    // ==========================================
    // Step 10: Avoidance Guidance Unit Tests
    // ==========================================

    @Test
    fun avoidance_leftClear_saysStepLeft() {
        // Frame width 1000px. Left third: 0..333, Center: 333..666, Right: 666..1000.
        // Center obstacle box: left=300, right=850 (center = 575 in CENTER)
        // freeWidthLeft = 300 >= 250 (clear)
        // freeWidthRight = 1000 - 850 = 150 < 250 (not clear)
        val centerObstacle = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(300f, 100f, 850f, 800f),
                frameWidth = 1000,
                frameHeight = 1000,
                boxWidth = 550f,
                boxHeight = 700f
            )
        )

        feedFrames(centerObstacle, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step left.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_rightClear_saysStepRight() {
        // Center obstacle box: left=150, right=700 (center = 425 in CENTER)
        // freeWidthLeft = 150 < 250 (not clear)
        // freeWidthRight = 1000 - 700 = 300 >= 250 (clear)
        val centerObstacle = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(150f, 100f, 700f, 800f),
                frameWidth = 1000,
                frameHeight = 1000,
                boxWidth = 550f,
                boxHeight = 700f
            )
        )

        feedFrames(centerObstacle, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_bothClear_moreWidthWins() {
        // 1. Left has more free width (380px vs 280px)
        val leftWider = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(380f, 100f, 720f, 800f),
                frameWidth = 1000,
                frameHeight = 1000,
                boxWidth = 340f,
                boxHeight = 700f
            )
        )

        feedFrames(leftWider, count = 3, frameWidth = 1000, frameHeight = 1000)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step left.", fakeVoice.spokenMessages[0])

        // 2. Right has more free width (380px vs 280px)
        engine.clear()
        fakeVoice.clear()
        fakeHaptics.clearHistory()

        val rightWider = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(280f, 100f, 620f, 800f),
                frameWidth = 1000,
                frameHeight = 1000,
                boxWidth = 340f,
                boxHeight = 700f
            )
        )

        feedFrames(rightWider, count = 3, frameWidth = 1000, frameHeight = 1000)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_neitherClear_saysStop() {
        // Center obstacle taking wide central space:
        // left = 200, right = 800 (freeWidthLeft = 200 < 250, freeWidthRight = 200 < 250)
        val wideObstacle = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(200f, 100f, 800f, 800f),
                frameWidth = 1000,
                frameHeight = 1000,
                boxWidth = 600f,
                boxHeight = 700f
            )
        )

        feedFrames(wideObstacle, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Stop.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_anotherObstacleBlockingOneSide_choosesClearSide() {
        // Center obstacle has left=380 (width=380 >= 250) and right=720 (width=280 >= 250).
        // Left would normally win (380 > 280).
        // Another obstacle (chair at 2.0m <= 5m) is in LEFT zone: left=50, right=250 (center=150 in LEFT).
        val obstacles = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(380f, 100f, 720f, 800f),
                frameWidth = 1000,
                frameHeight = 1000
            ),
            createDetection(
                label = "chair",
                distanceMeters = 2.0f,
                zone = Zone.LEFT,
                box = rectF(50f, 100f, 250f, 400f),
                frameWidth = 1000,
                frameHeight = 1000
            )
        )

        feedFrames(obstacles, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(2, fakeVoice.spokenMessages.size)
        // Because left is blocked by the chair, user is advised to step right
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_anotherObstacleOverlapMoreThan30Percent_blocksSide() {
        // Center obstacle has left=380 (width 380) vs right=720 (width 280).
        // Another obstacle in CENTER zone (centerX = 350 > 333.3), but overlaps LEFT third [0, 333.3] from 200 to 333.3:
        // overlap = 133.3px / 333.3px = 40% > 30%.
        val obstacles = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(380f, 100f, 720f, 800f),
                frameWidth = 1000,
                frameHeight = 1000
            ),
            createDetection(
                label = "chair",
                distanceMeters = 2.5f,
                zone = Zone.CENTER,
                box = rectF(200f, 100f, 500f, 400f),
                frameWidth = 1000,
                frameHeight = 1000
            )
        )

        feedFrames(obstacles, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(2, fakeVoice.spokenMessages.size)
        // Left blocked by 40% overlap -> Step right
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_anotherObstacleFartherThan5Meters_doesNotBlockSide() {
        // Center obstacle has left=380 (width 380) vs right=720 (width 280).
        // Another obstacle in LEFT zone at 5.5m (> 5m). It should NOT block left.
        val obstacles = listOf(
            createDetection(
                label = "person",
                distanceMeters = 2.0f,
                zone = Zone.CENTER,
                box = rectF(380f, 100f, 720f, 800f),
                frameWidth = 1000,
                frameHeight = 1000
            ),
            createDetection(
                label = "chair",
                distanceMeters = 5.5f,
                zone = Zone.LEFT,
                box = rectF(50f, 100f, 250f, 400f),
                frameWidth = 1000,
                frameHeight = 1000
            )
        )

        feedFrames(obstacles, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        // Left is clear because 5.5m > 5m, left free width 380 > 280 -> Step left
        assertEquals("Person ahead, 2 meters. Step left.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_vehicleLargeBox_saysLargeObstacleAheadStop() {
        val hugeBox = rectF(50f, 50f, 600f, 900f) // height = 850px in 1000px frame
        val vehicleDetection = listOf(
            createDetection("truck", 2.0f, Zone.CENTER, score = 0.9f, box = hugeBox, frameHeight = 1000, boxWidth = 550f, boxHeight = 850f)
        )

        feedFrames(vehicleDetection, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Large obstacle ahead. Stop.", fakeVoice.spokenMessages[0])
        assertFalse("Urgent speech interrupt must be suppressed for large vehicle", fakeVoice.interruptedList[0])
    }

    @Test
    fun avoidance_repeatRule_repeatsDirectionOnlyWhenAdviceChangesOrFirstAlert() {
        // Frame 3: person at 2.4m, right clear (freeWidthLeft = 150 < 250, freeWidthRight = 300 >= 250)
        currentTimeMs = 1000L
        val detection1 = listOf(
            createDetection("person", 2.4f, Zone.CENTER, box = rectF(150f, 100f, 700f, 800f))
        )
        feedFrames(detection1, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2.5 meters. Step right.", fakeVoice.spokenMessages[0])

        // 1 second later: distance changed by only 0.1m, elapsed 1s < 8s -> suppressed
        currentTimeMs = 2000L
        val detection2 = listOf(
            createDetection("person", 2.3f, Zone.CENTER, box = rectF(150f, 100f, 700f, 800f))
        )
        engine.processDetections(detection2, 1000, 1000)
        assertEquals("Alert suppressed on cooldown", 1, fakeVoice.spokenMessages.size)

        // 9 seconds later (t = 11000ms, elapsed 10s >= 8s): same advice (Step right)
        // Should repeat alert, but NOT speak direction because advice has not changed!
        currentTimeMs = 11000L
        engine.processDetections(detection2, 1000, 1000)
        assertEquals(2, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2.5 meters", fakeVoice.spokenMessages[1])

        // Later (t = 13000ms): distance changes to 1.3m (diff = 1.0m >= 1.0m) AND obstacle moves so left is clear (Step left)
        currentTimeMs = 13000L
        val detection3 = listOf(
            createDetection("person", 1.3f, Zone.CENTER, box = rectF(300f, 100f, 850f, 800f))
        )
        feedFrames(detection3, count = 5, frameWidth = 1000, frameHeight = 1000)
        assertEquals(3, fakeVoice.spokenMessages.size)
        // Advice changed to Step left -> direction IS spoken!
        assertEquals("Person ahead, 1.5 meters. Step left.", fakeVoice.spokenMessages[2])
    }

    @Test
    fun avoidance_navigationNearManeuver_stopsWhenAdviceContradictsTurn() {
        // Navigation active, 5m to a TURN_LEFT maneuver
        engine.navigationContext = NavigationContext(
            isNavigating = true,
            upcomingManeuverType = ManeuverType.TURN_LEFT,
            distanceToManeuverMeters = 5.0
        )

        // Center obstacle has right clear (free space advice would be "Step right")
        val obstacle = listOf(
            createDetection("person", 2.0f, Zone.CENTER, box = rectF(150f, 100f, 700f, 800f))
        )

        feedFrames(obstacle, count = 3, frameWidth = 1000, frameHeight = 1000)

        assertEquals(1, fakeVoice.spokenMessages.size)
        // "Step right" contradicts upcoming TURN_LEFT within 10m -> must say "Obstacle ahead, 2 meters. Stop."
        assertEquals("Obstacle ahead, 2 meters. Stop.", fakeVoice.spokenMessages[0])

        // When maneuver does not contradict (TURN_RIGHT with "Step right"):
        engine.clear()
        fakeVoice.clear()
        engine.navigationContext = NavigationContext(
            isNavigating = true,
            upcomingManeuverType = ManeuverType.TURN_RIGHT,
            distanceToManeuverMeters = 5.0
        )

        feedFrames(obstacle, count = 3, frameWidth = 1000, frameHeight = 1000)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])

        // When maneuver is farther than 10m (15m):
        engine.clear()
        fakeVoice.clear()
        engine.navigationContext = NavigationContext(
            isNavigating = true,
            upcomingManeuverType = ManeuverType.TURN_LEFT,
            distanceToManeuverMeters = 15.0
        )

        feedFrames(obstacle, count = 3, frameWidth = 1000, frameHeight = 1000)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertEquals("Person ahead, 2 meters. Step right.", fakeVoice.spokenMessages[0])
    }

    @Test
    fun avoidance_noVibrationForDirectionAdvice_respectsVoiceSetting() {
        // Voice OFF, Haptics ON
        engine.isVoiceEnabled = false
        engine.isHapticsEnabled = true

        val obstacle = listOf(
            createDetection("person", 2.0f, Zone.CENTER, box = rectF(300f, 100f, 850f, 800f))
        )

        feedFrames(obstacle, count = 3, frameWidth = 1000, frameHeight = 1000)

        // No voice messages
        assertEquals(0, fakeVoice.spokenMessages.size)
        // Only obstacle proximity vibration, no directional turn vibrations
        assertEquals(1, fakeHaptics.obstacleAlerts.size)
        assertEquals(Zone.CENTER, fakeHaptics.obstacleAlerts[0].second)
    }
}
