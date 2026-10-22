package com.example.navisense

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin geodesic and geometric utilities for pedestrian walking navigation.
 * Designed to execute cleanly in unit tests without Android system/framework stub dependencies.
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val METERS_PER_DEGREE_LAT = 111132.954

    /**
     * Calculates distance between two coordinates in meters using the Haversine formula.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun distanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        return distanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }

    /**
     * Calculates minimum perpendicular distance from a point to a line segment [start -> end].
     * Uses equirectangular planar projection, which is sub-millimeter accurate over pedestrian scale distances.
     */
    fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val meanLatRad = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(meanLatRad)

        val x = (point.longitude - start.longitude) * metersPerDegreeLon
        val y = (point.latitude - start.latitude) * METERS_PER_DEGREE_LAT

        val dx = (end.longitude - start.longitude) * metersPerDegreeLon
        val dy = (end.latitude - start.latitude) * METERS_PER_DEGREE_LAT

        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6) {
            return sqrt(x * x + y * y)
        }

        val t = ((x * dx + y * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = t * dx
        val projY = t * dy

        val distX = x - projX
        val distY = y - projY
        return sqrt(distX * distX + distY * distY)
    }

    /**
     * Calculates minimum distance from a point to any segment in a polyline.
     */
    fun distanceToPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return distanceMeters(point, polyline[0])

        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val dist = distanceToSegmentMeters(point, polyline[i], polyline[i + 1])
            if (dist < minDistance) {
                minDistance = dist
            }
        }
        return minDistance
    }

    /**
     * Decodes a Google encoded polyline string (5 decimal places precision, used by OpenRouteService).
     */
    fun decodePolyline(encoded: String?): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        if (encoded.isNullOrBlank()) return poly

        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        try {
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dlng

                poly.add(GeoPoint(lat.toDouble() / 1e5, lng.toDouble() / 1e5))
            }
        } catch (_: Exception) {
            // Return whatever points were decoded successfully
        }
        return poly
    }

    /**
     * Encodes a list of GeoPoints into a Google encoded polyline string (useful for testing & mocking).
     */
    fun encodePolyline(points: List<GeoPoint>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0

        for (point in points) {
            val lat = (point.latitude * 1e5).toInt()
            val lng = (point.longitude * 1e5).toInt()

            encodeValue(lat - lastLat, result)
            encodeValue(lng - lastLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            result.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }

    /**
     * Calculates remaining distance along polyline starting from current position projected onto polyline.
     */
    fun calculateRemainingDistanceMeters(
        currentPosition: GeoPoint,
        polyline: List<GeoPoint>,
        fromWaypointIndex: Int
    ): Double {
        if (polyline.isEmpty()) return 0.0
        val clampedIndex = fromWaypointIndex.coerceIn(0, polyline.size - 1)

        // Distance from current position to upcoming waypoint
        var total = distanceMeters(currentPosition, polyline[clampedIndex])

        // Sum remaining segments to end
        for (i in clampedIndex until polyline.size - 1) {
            total += distanceMeters(polyline[i], polyline[i + 1])
        }
        return total
    }
}
