package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObstacleTrackerTest {

    private lateinit var tracker: ObstacleTracker

    @Before
    fun setup() {
        tracker = ObstacleTracker()
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
        score: Float = 0.8f,
        distance: Float = 2.0f,
        zone: Zone = Zone.CENTER,
        box: RectF = rectF(100f, 100f, 200f, 200f),
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
            distanceMeters = distance,
            frameWidthPx = frameWidth,
            frameHeightPx = frameHeight,
            boxWidthPx = boxWidth,
            boxHeightPx = boxHeight
        )
    }

    @Test
    fun classSpecificScoreThresholds_vehiclesRequire0_6_othersRequire0_5() {
        // Vehicle with score 0.55 (< 0.6) should be ignored
        val weakCar = listOf(createDetection("car", score = 0.55f))
        val tracks1 = tracker.update(weakCar)
        assertEquals("Vehicle with score < 0.6 must be ignored", 0, tracks1.size)

        // Vehicle with score 0.65 (>= 0.6) should be accepted
        val strongCar = listOf(createDetection("car", score = 0.65f))
        val tracks2 = tracker.update(strongCar)
        assertEquals("Vehicle with score >= 0.6 must be accepted", 1, tracks2.size)

        // Non-vehicle ("person") with score 0.52 (>= 0.5) should be accepted
        tracker.clear()
        val person = listOf(createDetection("person", score = 0.52f))
        val tracks3 = tracker.update(person)
        assertEquals("Non-vehicle with score >= 0.5 must be accepted", 1, tracks3.size)

        // Non-vehicle with score 0.45 (< 0.5) should be ignored
        tracker.clear()
        val weakPerson = listOf(createDetection("person", score = 0.45f))
        val tracks4 = tracker.update(weakPerson)
        assertEquals("Non-vehicle with score < 0.5 must be ignored", 0, tracks4.size)
    }

    @Test
    fun temporalPersistence_requires3ConsecutiveFramesBeforeAlertEligible() {
        val detection = listOf(createDetection("person", distance = 2.5f))

        // Frame 1
        val tracksFrame1 = tracker.update(detection)
        assertEquals(1, tracksFrame1.size)
        assertEquals(1, tracksFrame1[0].consecutiveFrames)
        assertFalse("Frame 1: not eligible for alert", tracksFrame1[0].isAlertEligible)

        // Frame 2
        val tracksFrame2 = tracker.update(detection)
        assertEquals(1, tracksFrame2.size)
        assertEquals(2, tracksFrame2[0].consecutiveFrames)
        assertFalse("Frame 2: not eligible for alert", tracksFrame2[0].isAlertEligible)

        // Frame 3
        val tracksFrame3 = tracker.update(detection)
        assertEquals(1, tracksFrame3.size)
        assertEquals(3, tracksFrame3[0].consecutiveFrames)
        assertTrue("Frame 3: MUST be eligible for alert", tracksFrame3[0].isAlertEligible)
    }

    @Test
    fun temporalPersistence_missingFrameResetsStreak_andDropsAfter3Missed() {
        val detection = listOf(createDetection("person", distance = 2.5f))

        // 2 frames
        tracker.update(detection)
        val frame2 = tracker.update(detection)
        assertEquals(2, frame2[0].consecutiveFrames)

        // Missed in Frame 3
        val frame3 = tracker.update(emptyList())
        assertEquals(1, frame3.size)
        assertEquals("Streak should reset to 0 on missed frame", 0, frame3[0].consecutiveFrames)
        assertEquals(1, frame3[0].missedFrames)
        assertFalse(frame3[0].isAlertEligible)

        // Missed in Frame 4
        val frame4 = tracker.update(emptyList())
        assertEquals(2, frame4[0].missedFrames)

        // Missed in Frame 5 (3rd missed frame) -> track pruned
        val frame5 = tracker.update(emptyList())
        assertEquals("Track should be dropped after 3 consecutive missed frames", 0, frame5.size)
    }

    @Test
    fun distanceSmoothing_medianOfLast5EstimatesAbsorbsSpikes() {
        // Feed 5 frames with distances: 2.5, 2.7, 10.0 (sensor glitch), 2.6, 2.8
        val rawDistances = listOf(2.5f, 2.7f, 10.0f, 2.6f, 2.8f)

        var lastTrack: TrackedObstacle? = null
        for (dist in rawDistances) {
            val tracks = tracker.update(listOf(createDetection("chair", distance = dist)))
            lastTrack = tracks.first()
        }

        // Sorted: [2.5, 2.6, 2.7, 2.8, 10.0] -> Median is 2.7
        assertEquals("Glitch spike to 10m must be rejected by median filter", 2.7f, lastTrack!!.smoothedDistanceMeters, 0.01f)
    }

    @Test
    fun sanityCheck_largeBoxVehicle_markedUnreliable() {
        // Frame height = 1000px, Box height = 850px (> 80%) for a vehicle ("car")
        val largeBox = rectF(100f, 50f, 400f, 900f) // height = 850px
        val detection = listOf(createDetection("car", score = 0.9f, distance = 1.2f, box = largeBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 850f))

        val tracks = tracker.update(detection)
        assertTrue("Vehicle with box height > 80% must be marked as unreliable / large obstacle", tracks[0].isUnreliableOrLarge)
        assertTrue("Tracked obstacle must recognize large box", tracks[0].isLargeBox)
    }

    @Test
    fun sanityCheck_largeBoxNonVehicle_notMarkedUnreliable_andDistanceClampedTo0_5m() {
        // Non-vehicle ("person") taking up 85% of frame height (850px / 1000px) with raw distance 0.3m (< 0.5m)
        val largeBox = rectF(100f, 50f, 400f, 900f) // height = 850px
        val detection = listOf(createDetection("person", score = 0.9f, distance = 0.3f, box = largeBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 850f))

        val tracks = tracker.update(detection)
        assertFalse("Non-vehicle (person) with large box must NOT be marked as unreliable", tracks[0].isUnreliableOrLarge)
        assertTrue("Tracked obstacle must recognize large box", tracks[0].isLargeBox)
        assertEquals("Distance must be clamped to minimum 0.5m", 0.5f, tracks[0].smoothedDistanceMeters, 0.01f)
        assertEquals("Raw distance must also be clamped to minimum 0.5m", 0.5f, tracks[0].rawDistanceMeters, 0.01f)
    }

    @Test
    fun sanityCheck_largeBoxFurnitureAndAnimals_notMarkedUnreliable_andDistanceClamped() {
        // Chair taking up 85% of frame height with distance 0.2m
        val chairBox = rectF(100f, 50f, 400f, 900f)
        val chairDetection = listOf(createDetection("chair", score = 0.8f, distance = 0.2f, box = chairBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 850f))

        val chairTracks = tracker.update(chairDetection)
        assertFalse("Chair with large box must NOT be marked unreliable", chairTracks[0].isUnreliableOrLarge)
        assertEquals("Distance must be clamped to minimum 0.5m", 0.5f, chairTracks[0].smoothedDistanceMeters, 0.01f)

        tracker.clear()
        // Dog taking up 85% of frame height with distance 1.0m (already >= 0.5m)
        val dogDetection = listOf(createDetection("dog", score = 0.8f, distance = 1.0f, box = chairBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 850f))
        val dogTracks = tracker.update(dogDetection)
        assertFalse("Dog with large box must NOT be marked unreliable", dogTracks[0].isUnreliableOrLarge)
        assertEquals("Distance >= 0.5m is preserved", 1.0f, dogTracks[0].smoothedDistanceMeters, 0.01f)
    }

    @Test
    fun sanityCheck_extremeVehicleAspectRatio_markedUnreliable() {
        // Extreme width vs height (e.g. wall/building facade): width = 900px, height = 200px -> ratio = 4.5 (> 2.8)
        val wideBox = rectF(50f, 100f, 950f, 300f)
        val detection = listOf(createDetection("car", score = 0.9f, distance = 1.5f, box = wideBox, frameHeight = 1000, boxWidth = 900f, boxHeight = 200f))

        val tracks = tracker.update(detection)
        assertTrue("Vehicle with extreme aspect ratio must be marked as unreliable / large obstacle", tracks[0].isUnreliableOrLarge)
    }

    @Test
    fun sanityCheck_normalVehicle_notMarkedUnreliable() {
        // Normal vehicle box: width = 300px, height = 250px -> ratio = 1.2, height = 25% of frame
        val normalBox = rectF(100f, 100f, 400f, 350f)
        val detection = listOf(createDetection("car", score = 0.9f, distance = 2.5f, box = normalBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 250f))

        val tracks = tracker.update(detection)
        assertFalse("Normal vehicle bounding box should be reliable", tracks[0].isUnreliableOrLarge)
    }

    // =========================================================================
    // Phase 12 Instance-Aware Tracking Unit Tests
    // =========================================================================

    @Test
    fun tracking_oneObjectDetectedOverManyFrames_retainsTrackId() {
        val detection = listOf(
            createDetection("person", distance = 2.0f, box = rectF(350f, 100f, 450f, 700f), zone = Zone.CENTER)
        )

        var initialTrackId = -1
        for (frame in 1..10) {
            val currentTracks = tracker.update(detection)
            assertEquals(1, currentTracks.size)
            if (frame == 1) {
                initialTrackId = currentTracks[0].trackId
            } else {
                assertEquals("Track ID must remain identical across consecutive frames", initialTrackId, currentTracks[0].trackId)
                assertEquals(frame, currentTracks[0].consecutiveFrames)
            }
        }
    }

    @Test
    fun tracking_twoChairsInSameZone_receiveDifferentTrackIds_andDoNotSwap() {
        // Chair A on left side of center zone: [350, 100, 450, 500]
        // Chair B on right side of center zone: [550, 100, 650, 500]
        val chairA = createDetection("chair", distance = 2.0f, box = rectF(350f, 100f, 450f, 500f), zone = Zone.CENTER)
        val chairB = createDetection("chair", distance = 2.8f, box = rectF(550f, 100f, 650f, 500f), zone = Zone.CENTER)

        // Frame 1: List order [A, B]
        val frame1 = tracker.update(listOf(chairA, chairB))
        assertEquals(2, frame1.size)
        val trackA = frame1.first { it.boundingBox.left < 500f }
        val trackB = frame1.first { it.boundingBox.left >= 500f }
        assertNotEquals("Two chairs in same zone must receive distinct track IDs", trackA.trackId, trackB.trackId)

        // Frame 2: List order REVERSED [B, A] (simulating non-deterministic detector output order)
        val frame2 = tracker.update(listOf(chairB, chairA))
        assertEquals(2, frame2.size)

        val updatedA = frame2.first { it.boundingBox.left < 500f }
        val updatedB = frame2.first { it.boundingBox.left >= 500f }

        assertEquals("Chair A must retain its original track ID regardless of input list order", trackA.trackId, updatedA.trackId)
        assertEquals("Chair B must retain its original track ID regardless of input list order", trackB.trackId, updatedB.trackId)
    }

    @Test
    fun tracking_twoObjectsOfSameClass_remainSeparateWithSlightOverlap() {
        // Chair 1: [100, 100, 220, 400]
        // Chair 2: [200, 100, 320, 400] (20px horizontal overlap between [200, 220])
        val chair1 = createDetection("chair", distance = 2.0f, box = rectF(100f, 100f, 220f, 400f), zone = Zone.LEFT)
        val chair2 = createDetection("chair", distance = 2.5f, box = rectF(200f, 100f, 320f, 400f), zone = Zone.LEFT)

        val frame1 = tracker.update(listOf(chair1, chair2))
        assertEquals(2, frame1.size)
        assertNotEquals("Overlapping objects of same class must receive separate track IDs", frame1[0].trackId, frame1[1].trackId)

        val frame2 = tracker.update(listOf(chair1, chair2))
        assertEquals(2, frame2.size)
        assertEquals(frame1[0].trackId, frame2[0].trackId)
        assertEquals(frame1[1].trackId, frame2[1].trackId)
    }

    @Test
    fun tracking_detectionMovingFromCenterToLeft_retainsIdentity() {
        // Frame 1: obstacle in CENTER [320, 100, 450, 600]
        val centerObstacle = createDetection("person", distance = 2.0f, box = rectF(320f, 100f, 450f, 600f), zone = Zone.CENTER)
        val frame1 = tracker.update(listOf(centerObstacle))
        assertEquals(1, frame1.size)
        val initialId = frame1[0].trackId

        // Frame 2: object moved to LEFT [280, 100, 410, 600] (Zone.LEFT)
        val leftObstacle = createDetection("person", distance = 2.0f, box = rectF(280f, 100f, 410f, 600f), zone = Zone.LEFT)
        val frame2 = tracker.update(listOf(leftObstacle))
        assertEquals(1, frame2.size)
        assertEquals("Object moving from CENTER to LEFT must retain track ID", initialId, frame2[0].trackId)
        assertEquals(Zone.LEFT, frame2[0].zone)
    }

    @Test
    fun tracking_briefMissedDetection_doesNotImmediatelyDestroyTrack() {
        val obstacle = createDetection("dog", distance = 1.5f, box = rectF(400f, 500f, 600f, 700f), zone = Zone.CENTER)

        // Frames 1 & 2: detected
        tracker.update(listOf(obstacle))
        val frame2 = tracker.update(listOf(obstacle))
        val initialId = frame2[0].trackId

        // Frame 3: missed detection (temporary occlusion or camera shake)
        val frame3 = tracker.update(emptyList())
        assertEquals("Track must still be retained during brief missed detection", 1, frame3.size)
        assertEquals(1, frame3[0].missedFrames)
        assertEquals(0, frame3[0].consecutiveFrames)

        // Frame 4: object reappears
        val frame4 = tracker.update(listOf(obstacle))
        assertEquals(1, frame4.size)
        assertEquals("Track must retain identity when reappearing after short interruption", initialId, frame4[0].trackId)
        assertEquals(0, frame4[0].missedFrames)
    }

    @Test
    fun tracking_staleTrack_eventuallyExpires() {
        val obstacle = createDetection("chair", distance = 2.0f)
        tracker.update(listOf(obstacle))
        assertEquals(1, tracker.activeTrackIds.size)

        // 3 consecutive missed frames
        tracker.update(emptyList()) // missed 1
        tracker.update(emptyList()) // missed 2
        val frame4 = tracker.update(emptyList()) // missed 3 -> expired

        assertEquals("Stale track must be pruned after maxMissedFrames", 0, frame4.size)
        assertEquals("Active track IDs must be empty after track expiration", 0, tracker.activeTrackIds.size)
    }

    @Test
    fun tracking_oneDetectionCannotBeAssignedToTwoTracks() {
        // Frame 1: Two separate objects detected (Chair A and Chair B)
        val chairA = createDetection("chair", box = rectF(100f, 100f, 200f, 200f), zone = Zone.LEFT)
        val chairB = createDetection("chair", box = rectF(500f, 100f, 600f, 200f), zone = Zone.RIGHT)
        val frame1 = tracker.update(listOf(chairA, chairB))
        assertEquals(2, frame1.size)

        // Frame 2: Only Chair A is visible
        val frame2 = tracker.update(listOf(chairA))
        assertEquals("Both tracks returned (1 active, 1 missed)", 2, frame2.size)

        val activeTrack = frame2.first { it.missedFrames == 0 }
        val missedTrack = frame2.first { it.missedFrames > 0 }

        assertEquals(frame1.first { it.zone == Zone.LEFT }.trackId, activeTrack.trackId)
        assertEquals(frame1.first { it.zone == Zone.RIGHT }.trackId, missedTrack.trackId)
    }

    @Test
    fun tracking_ambiguousDetections_doNotCauseUncontrolledIdentityMerging() {
        // Two chairs with separate identities
        val chairA = createDetection("chair", box = rectF(350f, 100f, 450f, 400f), zone = Zone.CENTER)
        val chairB = createDetection("chair", box = rectF(550f, 100f, 650f, 400f), zone = Zone.CENTER)
        tracker.update(listOf(chairA, chairB))

        // New third chair enters far away in left zone
        val chairC = createDetection("chair", box = rectF(50f, 100f, 150f, 400f), zone = Zone.LEFT)
        val frame2 = tracker.update(listOf(chairA, chairB, chairC))

        assertEquals("Three separate tracks must be maintained", 3, frame2.size)
        val trackIds = frame2.map { it.trackId }.toSet()
        assertEquals("All three tracks must have unique IDs", 3, trackIds.size)
    }

    @Test
    fun tracking_trackIdsAndState_remainCorrectAfterObjectEntryAndExit() {
        val chairA = createDetection("chair", box = rectF(350f, 100f, 450f, 400f), zone = Zone.CENTER)
        val chairB = createDetection("chair", box = rectF(550f, 100f, 650f, 400f), zone = Zone.CENTER)

        // Chair A and Chair B enter
        val f1 = tracker.update(listOf(chairA, chairB))
        val idA = f1[0].trackId
        val idB = f1[1].trackId

        // Chair A leaves; only Chair B remains
        tracker.update(listOf(chairB)) // A missed 1
        tracker.update(listOf(chairB)) // A missed 2
        val f4 = tracker.update(listOf(chairB)) // A missed 3 -> expired

        assertEquals("Only Chair B active", 1, f4.size)
        assertEquals("Chair B retains its identity", idB, f4[0].trackId)
        assertFalse("Chair A ID must no longer be active", idA in tracker.activeTrackIds)

        // Chair C enters
        val chairC = createDetection("chair", box = rectF(100f, 100f, 200f, 400f), zone = Zone.LEFT)
        val f5 = tracker.update(listOf(chairB, chairC))
        assertEquals(2, f5.size)
        val idC = f5.first { it.trackId != idB }.trackId

        assertNotEquals(idA, idC)
        assertNotEquals(idB, idC)
    }
}
