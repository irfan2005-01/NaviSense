package com.example.navisense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DistanceEstimatorTest {

    private lateinit var estimator: DistanceEstimator

    @Before
    fun setup() {
        // Instantiate with null Context to test default fallback focal length behavior
        estimator = DistanceEstimator(null)
    }

    @Test
    fun getFocalLengthPx_fallbackConstant_returns600() {
        val focalPx = estimator.getFocalLengthPx(1080)
        assertEquals(DistanceEstimator.FOCAL_LENGTH_PX, focalPx, 0.001f)
        assertEquals(600f, focalPx, 0.001f)
    }

    @Test
    fun estimateDistance_knownClassPerson_calculatesCorrectDistance() {
        // Person real height = 1.7m, focal = 600px. Box height = 300px
        // Distance = (1.7 * 600) / 300 = 3.4 meters
        val dist = estimator.estimateDistance("person", 300f, 1080)
        assertEquals(3.4f, dist, 0.01f)
        assertEquals(3.4f, estimator.lastEstimatedDistance ?: 0f, 0.01f)
        assertEquals("person", estimator.lastEstimatedLabel)
    }

    @Test
    fun estimateDistance_knownClassCar_updatesLastEstimatedState() {
        // Car real height = 1.5m, focal = 600px. Box height = 600px
        // Distance = (1.5 * 600) / 600 = 1.5 meters
        val dist = estimator.estimateDistance("car", 600f, 1080)
        assertEquals(1.5f, dist, 0.01f)
        assertEquals(1.5f, estimator.lastEstimatedDistance ?: 0f, 0.01f)
        assertEquals("car", estimator.lastEstimatedLabel)
    }

    @Test
    fun estimateDistance_unknownClass_usesDefaultRealHeight() {
        // Unknown obstacle class defaults to DEFAULT_REAL_HEIGHT_METERS = 0.8m
        // Distance = (0.8 * 600) / 400 = 1.2 meters
        val dist = estimator.estimateDistance("mystery_obstacle", 400f, 1080)
        assertEquals(1.2f, dist, 0.01f)
        assertEquals(1.2f, estimator.lastEstimatedDistance ?: 0f, 0.01f)
        assertEquals("mystery_obstacle", estimator.lastEstimatedLabel)
    }

    @Test
    fun estimateDistance_invalidBoxHeight_returnsZero() {
        val zeroDist = estimator.estimateDistance("person", 0f, 1080)
        assertEquals(0f, zeroDist, 0.001f)

        val negativeDist = estimator.estimateDistance("person", -10f, 1080)
        assertEquals(0f, negativeDist, 0.001f)
    }

    @Test
    fun initialLastEstimatedState_isNull() {
        assertNull(estimator.lastEstimatedDistance)
        assertNull(estimator.lastEstimatedLabel)
    }
}
