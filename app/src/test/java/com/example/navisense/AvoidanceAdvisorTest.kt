package com.example.navisense

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AvoidanceAdvisorTest {

    private lateinit var advisor: AvoidanceAdvisor

    @Before
    fun setup() {
        advisor = AvoidanceAdvisor()
    }

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        val r = RectF()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    private fun createTracked(
        trackId: Int,
        label: String,
        distanceMeters: Float,
        zone: Zone,
        box: RectF,
        isAlertEligible: Boolean = true,
        isUnreliable: Boolean = false,
        isLargeBox: Boolean = false
    ): TrackedObstacle {
        return TrackedObstacle(
            trackId = trackId,
            label = label,
            zone = zone,
            boundingBox = box,
            score = 0.9f,
            rawDistanceMeters = distanceMeters,
            smoothedDistanceMeters = distanceMeters,
            consecutiveFrames = if (isAlertEligible) 3 else 1,
            missedFrames = 0,
            isUnreliableOrLarge = isUnreliable,
            isLargeBox = isLargeBox
        )
    }

    @Test
    fun leftClear_rightNotClear_returnsStepLeft() {
        // Frame width 1000. Left third: 0..333, Right third: 666..1000. 25% min width = 250px.
        // Center obstacle: left=300 (width left=300 >= 250), right=850 (width right=150 < 250).
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(300f, 100f, 850f, 800f)
        )

        val advice = advisor.getAdvice(listOf(center), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_LEFT, advice)
    }

    @Test
    fun rightClear_leftNotClear_returnsStepRight() {
        // Center obstacle: left=150 (width left=150 < 250), right=700 (width right=300 >= 250).
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(150f, 100f, 700f, 800f)
        )

        val advice = advisor.getAdvice(listOf(center), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_RIGHT, advice)
    }

    @Test
    fun bothClear_moreWidthWins_leftWins() {
        // Center obstacle: left=380 (width left=380 >= 250), right=720 (width right=280 >= 250).
        // 380 > 280 -> Step left
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(380f, 100f, 720f, 800f)
        )

        val advice = advisor.getAdvice(listOf(center), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_LEFT, advice)
    }

    @Test
    fun bothClear_moreWidthWins_rightWins() {
        // Center obstacle: left=280 (width left=280 >= 250), right=620 (width right=380 >= 250).
        // 380 > 280 -> Step right
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(280f, 100f, 620f, 800f)
        )

        val advice = advisor.getAdvice(listOf(center), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_RIGHT, advice)
    }

    @Test
    fun neitherClear_returnsStop() {
        // Center obstacle: left=200 (< 250), right=800 (< 250).
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(200f, 100f, 800f, 800f)
        )

        val advice = advisor.getAdvice(listOf(center), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STOP, advice)
    }

    @Test
    fun anotherObstacleBlockingLeft_returnsStepRight() {
        // Center obstacle: left=380, right=720 (left normally wins 380 > 280).
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(380f, 100f, 720f, 800f)
        )
        // Another obstacle in LEFT zone within 5m:
        val blocker = createTracked(
            trackId = 2,
            label = "chair",
            distanceMeters = 2.0f,
            zone = Zone.LEFT,
            box = rectF(50f, 100f, 250f, 400f)
        )

        val advice = advisor.getAdvice(listOf(center, blocker), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_RIGHT, advice)
    }

    @Test
    fun anotherObstacleOverlapMoreThan30Percent_blocksTargetSide() {
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(380f, 100f, 720f, 800f)
        )
        // Blocker box centerX=350 (CENTER zone), but overlaps LEFT third [0, 333.3] from 200 to 333.3:
        // overlap = 133.3 / 333.3 = 40% > 30%.
        val blocker = createTracked(
            trackId = 2,
            label = "chair",
            distanceMeters = 2.5f,
            zone = Zone.CENTER,
            box = rectF(200f, 100f, 500f, 400f)
        )

        val advice = advisor.getAdvice(listOf(center, blocker), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_RIGHT, advice)
    }

    @Test
    fun anotherObstacleOverlapLessThan30Percent_doesNotBlock() {
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(380f, 100f, 720f, 800f)
        )
        // Blocker box centerX=400 (CENTER zone), overlaps LEFT third [0, 333.3] from 300 to 333.3:
        // overlap = 33.3 / 333.3 = 10% <= 30%.
        val blocker = createTracked(
            trackId = 2,
            label = "chair",
            distanceMeters = 2.5f,
            zone = Zone.CENTER,
            box = rectF(300f, 100f, 500f, 400f)
        )

        val advice = advisor.getAdvice(listOf(center, blocker), frameWidth = 1000)
        // Left is NOT blocked, and 380 > 280 -> Step left
        assertEquals(AvoidanceAdvice.STEP_LEFT, advice)
    }

    @Test
    fun anotherObstacleFartherThan5Meters_doesNotBlock() {
        val center = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 2.0f,
            zone = Zone.CENTER,
            box = rectF(380f, 100f, 720f, 800f)
        )
        val farObstacle = createTracked(
            trackId = 2,
            label = "chair",
            distanceMeters = 5.2f, // > 5m
            zone = Zone.LEFT,
            box = rectF(50f, 100f, 250f, 400f)
        )

        val advice = advisor.getAdvice(listOf(center, farObstacle), frameWidth = 1000)
        assertEquals(AvoidanceAdvice.STEP_LEFT, advice)
    }

    @Test
    fun vehicleLargeBox_returnsNull() {
        val largeVehicle = createTracked(
            trackId = 1,
            label = "car",
            distanceMeters = 1.0f,
            zone = Zone.CENTER,
            box = rectF(50f, 50f, 600f, 900f),
            isUnreliable = true,
            isLargeBox = true
        )

        val advice = advisor.getAdvice(listOf(largeVehicle), frameWidth = 1000)
        assertNull("Vehicle large box should not trigger AvoidanceAdvisor free-space logic", advice)
    }

    @Test
    fun centerObstacleBeyond3Meters_returnsNull() {
        val farCenter = createTracked(
            trackId = 1,
            label = "person",
            distanceMeters = 3.5f, // > 3m
            zone = Zone.CENTER,
            box = rectF(300f, 100f, 850f, 800f)
        )

        val advice = advisor.getAdvice(listOf(farCenter), frameWidth = 1000)
        assertNull("Obstacle beyond 3m should not trigger avoidance advice", advice)
    }
}
