package com.example.navisense

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRepositoryTest {

    private fun createMockClient(statusCode: Int, responseBody: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun geocodeDestination_parsesPeliasGeoJsonSuccessfully() = runBlocking {
        val samplePeliasJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "Point",
                "coordinates": [78.4867, 17.3850]
              },
              "properties": {
                "name": "Charminar",
                "label": "Charminar, Hyderabad, Telangana, India",
                "confidence": 0.95
              }
            }
          ]
        }
        """.trimIndent()

        val mockClient = createMockClient(200, samplePeliasJson)
        val repository = NavigationRepository(apiKey = "dummy_key", client = mockClient)

        val result = repository.geocodeDestination("Charminar", null)

        assertTrue(result.isSuccess)
        val geocoded = result.getOrThrow()
        assertEquals("Charminar", geocoded.name)
        assertEquals(17.3850, geocoded.point.latitude, 0.0001)
        assertEquals(78.4867, geocoded.point.longitude, 0.0001)
        assertEquals("Charminar, Hyderabad, Telangana, India", geocoded.label)
    }

    @Test
    fun geocodeDestination_handlesEmptyFeatures() = runBlocking {
        val emptyPeliasJson = """
        {
          "type": "FeatureCollection",
          "features": []
        }
        """.trimIndent()

        val mockClient = createMockClient(200, emptyPeliasJson)
        val repository = NavigationRepository(apiKey = "dummy_key", client = mockClient)

        val result = repository.geocodeDestination("NonExistentPlaceXYZ", null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun fetchWalkingRoute_parsesRouteAndStepsCorrectly() = runBlocking {
        val sampleDirectionsJson = """
        {
          "routes": [
            {
              "summary": {
                "distance": 850.5,
                "duration": 680.0
              },
              "segments": [
                {
                  "distance": 850.5,
                  "duration": 680.0,
                  "steps": [
                    {
                      "distance": 120.0,
                      "duration": 96.0,
                      "type": 11,
                      "instruction": "Head north on Main Road",
                      "name": "Main Road",
                      "way_points": [0, 4]
                    },
                    {
                      "distance": 730.5,
                      "duration": 584.0,
                      "type": 0,
                      "instruction": "Turn left onto Market Street",
                      "name": "Market Street",
                      "way_points": [4, 18]
                    }
                  ]
                }
              ],
              "geometry": "sample_encoded_polyline_geometry"
            }
          ]
        }
        """.trimIndent()

        val mockClient = createMockClient(200, sampleDirectionsJson)
        val repository = NavigationRepository(apiKey = "dummy_key", client = mockClient)

        val start = GeoPoint(17.3800, 78.4800)
        val destination = GeocodedLocation(
            name = "Charminar",
            point = GeoPoint(17.3850, 78.4867),
            label = "Charminar, Hyderabad"
        )

        val result = repository.fetchWalkingRoute(start, destination)

        assertTrue(result.isSuccess)
        val route = result.getOrThrow()

        assertEquals("Charminar", route.destinationName)
        assertEquals(850.5, route.totalDistanceMeters, 0.01)
        assertEquals(680.0, route.totalDurationSeconds, 0.01)
        assertEquals(2, route.steps.size)

        val step1 = route.steps[0]
        assertEquals("Head north on Main Road", step1.instruction)
        assertEquals(120.0, step1.distanceMeters, 0.01)
        assertEquals(listOf(0, 4), step1.wayPoints)
        assertEquals("Main Road", step1.streetName)

        val step2 = route.steps[1]
        assertEquals("Turn left onto Market Street", step2.instruction)
        assertEquals(730.5, step2.distanceMeters, 0.01)
        assertEquals(listOf(4, 18), step2.wayPoints)
        assertEquals("Market Street", step2.streetName)

        assertEquals("sample_encoded_polyline_geometry", route.geometryPolyline)
    }

    @Test
    fun fetchWalkingRoute_handlesHttpError() = runBlocking {
        val mockClient = createMockClient(404, """{"error": "Route not found"}""")
        val repository = NavigationRepository(apiKey = "dummy_key", client = mockClient)

        val start = GeoPoint(17.3800, 78.4800)
        val destination = GeocodedLocation(
            name = "Unknown Place",
            point = GeoPoint(17.3850, 78.4867),
            label = "Unknown"
        )

        val result = repository.fetchWalkingRoute(start, destination)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }
}
