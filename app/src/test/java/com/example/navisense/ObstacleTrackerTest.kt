package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObstacleTrackerTest {

    private lateinit var tracker: ObstacleTracker

    @Before
    fun setup() {
        tracker = ObstacleTracker()
    }

    private fun createDetection(
        label: String,
        score: Float = 0.8f,
        distance: Float = 2.0f,
        zone: Zone = Zone.CENTER,
        box: RectF = RectF(100f, 100f, 200f, 200f),
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
        val largeBox = RectF(100f, 50f, 400f, 900f) // height = 850px
        val detection = listOf(createDetection("car", score = 0.9f, distance = 1.2f, box = largeBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 850f))

        val tracks = tracker.update(detection)
        assertTrue("Vehicle with box height > 80% must be marked as unreliable / large obstacle", tracks[0].isUnreliableOrLarge)
        assertTrue("Tracked obstacle must recognize large box", tracks[0].isLargeBox)
    }

    @Test
    fun sanityCheck_largeBoxNonVehicle_notMarkedUnreliable_andDistanceClampedTo0_5m() {
        // Non-vehicle ("person") taking up 85% of frame height (850px / 1000px) with raw distance 0.3m (< 0.5m)
        val largeBox = RectF(100f, 50f, 400f, 900f) // height = 850px
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
        val chairBox = RectF(100f, 50f, 400f, 900f)
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
        val wideBox = RectF(50f, 100f, 950f, 300f)
        val detection = listOf(createDetection("car", score = 0.9f, distance = 1.5f, box = wideBox, frameHeight = 1000, boxWidth = 900f, boxHeight = 200f))

        val tracks = tracker.update(detection)
        assertTrue("Vehicle with extreme aspect ratio must be marked as unreliable / large obstacle", tracks[0].isUnreliableOrLarge)
    }

    @Test
    fun sanityCheck_normalVehicle_notMarkedUnreliable() {
        // Normal vehicle box: width = 300px, height = 250px -> ratio = 1.2, height = 25% of frame
        val normalBox = RectF(100f, 100f, 400f, 350f)
        val detection = listOf(createDetection("car", score = 0.9f, distance = 2.5f, box = normalBox, frameHeight = 1000, boxWidth = 300f, boxHeight = 250f))

        val tracks = tracker.update(detection)
        assertFalse("Normal vehicle bounding box should be reliable", tracks[0].isUnreliableOrLarge)
    }
}
