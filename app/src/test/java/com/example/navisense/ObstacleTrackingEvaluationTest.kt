package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Reproducible quantitative evaluation comparing the Baseline (Phase 11) Tracker
 * with the Improved Instance-Aware Tracker (Phase 12).
 *
 * Simulates recorded video sequences with manually labelled ground truth object identities.
 */
class ObstacleTrackingEvaluationTest {

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
        box: RectF
    ): Detection {
        return Detection(
            label = label,
            score = 0.85f,
            boundingBox = box,
            zone = zone,
            distanceMeters = distanceMeters,
            frameWidthPx = 1000,
            frameHeightPx = 1000,
            boxWidthPx = box.right - box.left,
            boxHeightPx = box.bottom - box.top
        )
    }

    /**
     * Baseline tracker reproducing exact pre-Phase 12 logic:
     * - First match on label and similar zone without bounding box IoU.
     */
    class BaselineTracker {
        private var nextId = 1
        data class BaselineTrack(
            val id: Int,
            var label: String,
            var zone: Zone,
            var missedFrames: Int = 0
        )
        private val tracks = mutableListOf<BaselineTrack>()

        fun update(detections: List<Detection>): List<Int> {
            val matched = mutableSetOf<BaselineTrack>()
            val resultIds = mutableListOf<Int>()

            for (det in detections) {
                val norm = det.label.trim().lowercase(Locale.US)
                val best = tracks.firstOrNull {
                    it !in matched && it.label == norm && isSimilarZone(it.zone, det.zone)
                }
                if (best != null) {
                    matched.add(best)
                    best.zone = det.zone
                    best.missedFrames = 0
                    resultIds.add(best.id)
                } else {
                    val newT = BaselineTrack(nextId++, norm, det.zone)
                    tracks.add(newT)
                    matched.add(newT)
                    resultIds.add(newT.id)
                }
            }

            val it = tracks.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (t !in matched) {
                    t.missedFrames++
                    if (t.missedFrames >= 3) it.remove()
                }
            }
            return resultIds
        }

        private fun isSimilarZone(z1: Zone, z2: Zone): Boolean {
            if (z1 == z2) return true
            return (z1 == Zone.LEFT && z2 == Zone.CENTER) ||
                   (z1 == Zone.CENTER && z2 == Zone.LEFT) ||
                   (z1 == Zone.CENTER && z2 == Zone.RIGHT) ||
                   (z1 == Zone.RIGHT && z2 == Zone.CENTER)
        }
    }

    @Test
    fun benchmark_multiObjectInSameZone_idSwitchComparison() {
        // Ground truth: 2 chairs in CENTER zone across 8 consecutive frames.
        // Detector non-deterministically swaps the order of returned detections between frames.
        val chair1 = createDetection("chair", 2.0f, Zone.CENTER, rectF(350f, 100f, 450f, 500f))
        val chair2 = createDetection("chair", 2.8f, Zone.CENTER, rectF(550f, 100f, 650f, 500f))

        val baseline = BaselineTracker()
        val improved = ObstacleTracker()

        var baselineIdSwitches = 0
        var improvedIdSwitches = 0

        var baselineLastChair1Id: Int? = null
        var baselineLastChair2Id: Int? = null

        var improvedLastChair1Id: Int? = null
        var improvedLastChair2Id: Int? = null

        val startTimeBaseline = System.nanoTime()
        for (frame in 1..8) {
            // Alternate input list order: [C1, C2] on odd frames, [C2, C1] on even frames
            val inputDetections = if (frame % 2 == 1) listOf(chair1, chair2) else listOf(chair2, chair1)

            // Baseline execution
            val baselineResultIds = baseline.update(inputDetections)
            val baseC1Id = if (frame % 2 == 1) baselineResultIds[0] else baselineResultIds[1]
            val baseC2Id = if (frame % 2 == 1) baselineResultIds[1] else baselineResultIds[0]

            if (baselineLastChair1Id != null && baseC1Id != baselineLastChair1Id) {
                baselineIdSwitches++
            }
            if (baselineLastChair2Id != null && baseC2Id != baselineLastChair2Id) {
                baselineIdSwitches++
            }
            baselineLastChair1Id = baseC1Id
            baselineLastChair2Id = baseC2Id
        }
        val elapsedBaselineNs = System.nanoTime() - startTimeBaseline

        val startTimeImproved = System.nanoTime()
        for (frame in 1..8) {
            val inputDetections = if (frame % 2 == 1) listOf(chair1, chair2) else listOf(chair2, chair1)

            val improvedTracks = improved.update(inputDetections)
            val impC1 = improvedTracks.first { it.boundingBox.left < 500f }
            val impC2 = improvedTracks.first { it.boundingBox.left >= 500f }

            if (improvedLastChair1Id != null && impC1.trackId != improvedLastChair1Id) {
                improvedIdSwitches++
            }
            if (improvedLastChair2Id != null && impC2.trackId != improvedLastChair2Id) {
                improvedIdSwitches++
            }
            improvedLastChair1Id = impC1.trackId
            improvedLastChair2Id = impC2.trackId
        }
        val elapsedImprovedNs = System.nanoTime() - startTimeImproved

        println("====================================================")
        println("EVALUATION RESULTS (Multi-Object In Same Zone):")
        println("Frames evaluated: 8 (alternating detector input order)")
        println("Baseline Tracker ID Switches: $baselineIdSwitches (flips identities every frame)")
        println("Improved Tracker ID Switches: $improvedIdSwitches (0 switches, stable IoU)")
        println("Baseline CPU time (8 frames): ${elapsedBaselineNs / 1000} µs")
        println("Improved CPU time (8 frames): ${elapsedImprovedNs / 1000} µs")
        println("====================================================")

        assertTrue("Baseline tracker swaps identities repeatedly on alternating order", baselineIdSwitches > 0)
        assertEquals("Improved tracker must have 0 ID switches for stationary overlapping candidates", 0, improvedIdSwitches)
    }

    @Test
    fun benchmark_alertSuppression_duplicateAlertsComparison() {
        // Ground truth: 2 chairs in CENTER zone over 10 frames.
        // In the baseline engine, because cooldown is keyed by (label, zone),
        // chair 1 and chair 2 shared the key ("chair", CENTER).
        // In the improved engine, each chair has an isolated TrackAlertRecord.

        val fakeVoice = FakeVoiceNotifier()
        val fakeHaptics = FakeHapticNotifier()
        var timeMs = 1000L

        val engine = DecisionEngine(
            voiceManager = fakeVoice,
            hapticManager = fakeHaptics,
            timeProvider = { timeMs }
        )

        val chair1 = createDetection("chair", 2.0f, Zone.CENTER, rectF(350f, 100f, 450f, 500f))
        val chair2 = createDetection("chair", 2.8f, Zone.CENTER, rectF(550f, 100f, 650f, 500f))

        var totalAlerts = 0
        for (f in 1..10) {
            timeMs += 100L
            val prevSize = fakeVoice.spokenMessages.size
            engine.processDetections(listOf(chair1, chair2), 1000, 1000)
            val newAlerts = fakeVoice.spokenMessages.size - prevSize
            totalAlerts += newAlerts
        }

        // Frame 3 confirmed both chairs -> 2 alerts emitted (1 per chair)
        // Frames 4..10 -> 0 duplicate alerts (cooldown active per track)
        assertEquals("Exactly 2 alerts emitted (one per chair) without per-frame duplicate spam", 2, totalAlerts)
    }
}
