package com.example.navisense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    @Test
    fun distanceMeters_computesAccurateDistance() {
        // Distance between two points in Hyderabad (~111 meters apart north-south)
        val p1 = GeoPoint(17.38500, 78.48670)
        val p2 = GeoPoint(17.38600, 78.48670)

        val dist = GeoUtils.distanceMeters(p1, p2)
        assertEquals(111.19, dist, 1.0)
    }

    @Test
    fun distanceToSegmentMeters_pointOnSegment_returnsZero() {
        val start = GeoPoint(17.3850, 78.4860)
        val end = GeoPoint(17.3850, 78.4880)
        // Midpoint on the segment
        val mid = GeoPoint(17.3850, 78.4870)

        val dist = GeoUtils.distanceToSegmentMeters(mid, start, end)
        assertEquals(0.0, dist, 0.5)
    }

    @Test
    fun distanceToSegmentMeters_perpendicularOffset_returnsCorrectDistance() {
        val start = GeoPoint(17.3850, 78.4860)
        val end = GeoPoint(17.3850, 78.4880)
        // Point shifted north by 0.00027 degrees (~30 meters)
        val offsetPoint = GeoPoint(17.38527, 78.4870)

        val dist = GeoUtils.distanceToSegmentMeters(offsetPoint, start, end)
        assertEquals(30.0, dist, 1.5)
    }

    @Test
    fun distanceToSegmentMeters_outsideEndpoints_clampsToNearestEndpoint() {
        val start = GeoPoint(17.3850, 78.4860)
        val end = GeoPoint(17.3850, 78.4880)

        // Point before start
        val beforeStart = GeoPoint(17.3850, 78.4850)
        val distToStart = GeoUtils.distanceMeters(beforeStart, start)
        val segDist = GeoUtils.distanceToSegmentMeters(beforeStart, start, end)
        assertEquals(distToStart, segDist, 0.5)
    }

    @Test
    fun distanceToPolylineMeters_findsMinimumDistanceAcrossSegments() {
        val polyline = listOf(
            GeoPoint(17.3850, 78.4860),
            GeoPoint(17.3850, 78.4870),
            GeoPoint(17.3860, 78.4870)
        )

        // Point 10 meters east of segment 2 (vertical segment along lon 78.4870)
        val nearPoint = GeoPoint(17.3855, 78.487094) // ~10m east
        val dist = GeoUtils.distanceToPolylineMeters(nearPoint, polyline)
        assertEquals(10.0, dist, 1.5)
    }

    @Test
    fun polylineEncodingAndDecoding_preservesCoordinates() {
        val original = listOf(
            GeoPoint(17.38504, 78.48671),
            GeoPoint(17.38550, 78.48690),
            GeoPoint(17.38620, 78.48750)
        )

        val encoded = GeoUtils.encodePolyline(original)
        assertTrue(encoded.isNotBlank())

        val decoded = GeoUtils.decodePolyline(encoded)
        assertEquals(original.size, decoded.size)

        for (i in original.indices) {
            assertEquals(original[i].latitude, decoded[i].latitude, 0.00002)
            assertEquals(original[i].longitude, decoded[i].longitude, 0.00002)
        }
    }

    @Test
    fun calculateRemainingDistanceMeters_computesCumulativeDistance() {
        val polyline = listOf(
            GeoPoint(17.3850, 78.4860),
            GeoPoint(17.3860, 78.4860), // ~111m from 0
            GeoPoint(17.3870, 78.4860)  // ~111m from 1, total ~222m
        )

        val current = GeoPoint(17.3850, 78.4860)
        val rem = GeoUtils.calculateRemainingDistanceMeters(current, polyline, 0)
        assertEquals(222.0, rem, 2.0)
    }
}
