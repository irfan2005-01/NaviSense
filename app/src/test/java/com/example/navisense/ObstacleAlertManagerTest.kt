package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObstacleAlertManagerTest {

    private lateinit var alertManager: ObstacleAlertManager
    private lateinit var fakeVoice: FakeVoiceNotifier
    private lateinit var fakeHaptics: FakeHapticNotifier
    private var simulatedTimeMs: Long = 1000L
    private lateinit var engine: DecisionEngine

    @Before
    fun setup() {
        alertManager = ObstacleAlertManager()
        fakeVoice = FakeVoiceNotifier()
        fakeHaptics = FakeHapticNotifier()
        simulatedTimeMs = 1000L
        engine = DecisionEngine(
            voiceManager = fakeVoice,
            hapticManager = fakeHaptics,
            timeProvider = { simulatedTimeMs },
            alertManager = alertManager
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
        distance: Float = 2.0f,
        zone: Zone = Zone.CENTER,
        box: RectF = rectF(350f, 100f, 450f, 600f),
        score: Float = 0.9f
    ): Detection {
        return Detection(
            label = label,
            score = score,
            boundingBox = box,
            zone = zone,
            distanceMeters = distance,
            frameWidthPx = 1000,
            frameHeightPx = 1000,
            boxWidthPx = box.right - box.left,
            boxHeightPx = box.bottom - box.top
        )
    }

    private fun feedFrames(detections: List<Detection>, count: Int = 3) {
        repeat(count) {
            engine.processDetections(detections, 1000, 1000)
        }
    }

    @Test
    fun alert_sameObject_doesNotTriggerRepeatedAlertsEveryFrame() {
        val chair = listOf(createDetection("chair", distance = 2.5f))

        // 3 consecutive frames confirm obstacle -> Frame 3 emits alert
        feedFrames(chair, count = 3)
        assertEquals("Initial alert must be emitted on 3rd frame", 1, fakeVoice.spokenMessages.size)

        // Frames 4 through 10 (same object, same distance, elapsed 100ms)
        fakeVoice.clear()
        for (i in 1..7) {
            simulatedTimeMs += 50L
            engine.processDetections(chair, 1000, 1000)
        }

        assertEquals("Same object must NOT trigger repeated alerts on consecutive frames", 0, fakeVoice.spokenMessages.size)
    }

    @Test
    fun alert_separateObjects_haveIndependentCooldowns() {
        val chairA = createDetection("chair", distance = 2.0f, box = rectF(350f, 100f, 450f, 600f))
        val chairB = createDetection("chair", distance = 2.8f, box = rectF(550f, 100f, 650f, 600f))

        // Both chairs confirmed
        feedFrames(listOf(chairA, chairB), count = 3)

        // DecisionEngine processes top 2 candidates; both Chair A and Chair B must be announced!
        assertEquals("Both distinct chairs must receive voice alerts", 2, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages.any { it.contains("2 meters") })
        assertTrue(fakeVoice.spokenMessages.any { it.contains("3 meters") })

        // Check alertManager has independent records for both track IDs
        assertEquals("Alert manager must track both objects independently", 2, alertManager.getActiveRecordCount())
    }

    @Test
    fun alert_validMaterialDistanceChange_triggersUpdateAccordingToRules() {
        val person = listOf(createDetection("person", distance = 3.5f))
        simulatedTimeMs = 1000L
        feedFrames(person, count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("3.5 meters"))

        // Small distance change of 0.3m (3.5 -> 3.2m), within cooldown -> suppressed
        simulatedTimeMs = 2000L
        fakeVoice.clear()
        engine.processDetections(listOf(createDetection("person", distance = 3.2f)), 1000, 1000)
        assertEquals("Minor distance change < 1m must be suppressed on cooldown", 0, fakeVoice.spokenMessages.size)

        // Material distance change of 1.1m (3.5 -> 2.4m, >= 1.0m threshold) -> triggers!
        simulatedTimeMs = 3000L
        fakeVoice.clear()
        // Feed 5 frames so median distance filter reflects the 2.4m update
        feedFrames(listOf(createDetection("person", distance = 2.4f)), count = 5)
        assertEquals("Material distance change >= 1m must trigger new alert", 1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("2.5 meters"))
    }

    @Test
    fun alert_zoneChanges_doNotAutomaticallyResetIdentityOrCauseRepeatedAlerts() {
        val personCenter = createDetection("person", distance = 2.5f, zone = Zone.CENTER, box = rectF(350f, 100f, 450f, 600f))
        simulatedTimeMs = 1000L
        feedFrames(listOf(personCenter), count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)

        // Object moves to LEFT with identical distance (2.5m) shortly after (elapsed 1s < 8s cooldown)
        simulatedTimeMs = 2000L
        fakeVoice.clear()
        val personLeft = createDetection("person", distance = 2.5f, zone = Zone.LEFT, box = rectF(250f, 100f, 350f, 600f))
        engine.processDetections(listOf(personLeft), 1000, 1000)

        // Must NOT generate duplicate alert merely because zone changed
        assertEquals("Zone shift away from center without distance change must not generate repeated alert", 0, fakeVoice.spokenMessages.size)

        // After cooldown expires (8s elapsed), alert naturally repeats with updated zone phrasing
        simulatedTimeMs = 10000L
        fakeVoice.clear()
        engine.processDetections(listOf(personLeft), 1000, 1000)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("left"))
    }

    @Test
    fun alert_trackExpiration_removesAssociatedAlertState() {
        val chair = createDetection("chair", distance = 2.0f)
        feedFrames(listOf(chair), count = 3)
        assertEquals(1, alertManager.getActiveRecordCount())

        // Object disappears for 3 frames
        engine.processDetections(emptyList(), 1000, 1000) // missed 1
        engine.processDetections(emptyList(), 1000, 1000) // missed 2
        engine.processDetections(emptyList(), 1000, 1000) // missed 3 -> track expires

        assertEquals("Alert records must be cleaned up when track expires", 0, alertManager.getActiveRecordCount())
    }

    @Test
    fun alert_urgentAlerts_notSuppressedByRoutineCooldowns() {
        // Obstacle at 2.2m (non-urgent)
        simulatedTimeMs = 1000L
        val detectionFar = listOf(createDetection("person", distance = 2.2f))
        feedFrames(detectionFar, count = 3)
        assertEquals(1, fakeVoice.spokenMessages.size)
        assertFalse("Routine alert at 2.2m must not interrupt", fakeVoice.interruptedList[0])

        // 1 second later: obstacle moves into urgent critical range (1.2m < 1.5m)
        simulatedTimeMs = 2000L
        fakeVoice.clear()
        val detectionClose = listOf(createDetection("person", distance = 1.2f))
        feedFrames(detectionClose, count = 5) // update median filter

        assertEquals("Urgent alert (< 1.5m) must fire immediately without being blocked by ordinary cooldown", 1, fakeVoice.spokenMessages.size)
        assertTrue("Urgent alert must have interrupt = true", fakeVoice.interruptedList[0])
    }

    @Test
    fun alert_navigationSpeech_notIncorrectlyInterruptedByRoutineObjectAlerts() {
        val testVoice = TestVoiceNotifier()
        val navEngine = DecisionEngine(
            voiceManager = testVoice,
            hapticManager = fakeHaptics,
            timeProvider = { simulatedTimeMs }
        )

        // Active navigation instruction playing
        testVoice.speakNavigation("In 30 meters, turn left onto Main Street")
        assertTrue("Navigation instruction is active", testVoice.isSpeakingNavigation)

        // Routine obstacle alert at 2.5m (non-critical >= 1.5m)
        val routineObstacle = listOf(createDetection("chair", distance = 2.5f))
        repeat(3) {
            navEngine.processDetections(routineObstacle, 1000, 1000)
        }

        // Routine obstacle alert must NOT set interrupt = true
        assertFalse("Routine obstacle alert must NOT interrupt navigation speech", testVoice.isSpeakingCriticalAlert)
        assertEquals("Navigation instruction remains undisturbed", "In 30 meters, turn left onto Main Street", testVoice.activeNavigationInstruction)
    }

    @Test
    fun alert_existingVoiceAndVibrationPriorityRules_remainIntact() {
        // Critical obstacle (< 1.5m) in CENTER: triggers voice interrupt and critical haptic
        val criticalCenter = listOf(createDetection("person", distance = 1.0f, zone = Zone.CENTER))
        feedFrames(criticalCenter, count = 3)

        assertEquals(1, fakeVoice.spokenMessages.size)
        assertTrue("Critical obstacle must interrupt voice", fakeVoice.interruptedList[0])
        assertEquals("Critical obstacle must trigger one-shot haptic", 1, fakeHaptics.obstacleAlerts.size)
        val (hapticDist, hapticZone) = fakeHaptics.obstacleAlerts[0]
        assertEquals(Zone.CENTER, hapticZone)
        assertTrue("Haptic distance must be in critical band (< 1.5m)", hapticDist < HapticManager.CRITICAL_DISTANCE_METERS)

        // Side obstacle (LEFT) at 1.0m: speaks with voice, but ZERO haptics
        engine.clear()
        fakeVoice.clear()
        fakeHaptics.clearHistory()

        val criticalLeft = listOf(createDetection("person", distance = 1.0f, zone = Zone.LEFT, box = rectF(50f, 100f, 200f, 600f)))
        feedFrames(criticalLeft, count = 3)

        assertEquals("Side obstacle speaks voice alert", 1, fakeVoice.spokenMessages.size)
        assertTrue(fakeVoice.spokenMessages[0].contains("left"))
        assertEquals("Side obstacle produces NO vibration", 0, fakeHaptics.obstacleAlerts.size)
        assertNull("Side obstacle produces NO continuous center vibration", fakeHaptics.continuousCenterDistance)
    }
}
