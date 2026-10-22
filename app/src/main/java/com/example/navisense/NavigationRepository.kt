package com.example.navisense

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Geographical coordinates represented by latitude and longitude.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * Destination result from OpenRouteService Pelias geocoding.
 */
data class GeocodedLocation(
    val name: String,
    val point: GeoPoint,
    val label: String
)

/**
 * A discrete navigation step along the route.
 */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val wayPoints: List<Int>,
    val streetName: String
)

/**
 * Full walking navigation route with summary metrics and step-by-step instructions.
 */
data class NavigationRoute(
    val destinationName: String,
    val destinationPoint: GeoPoint,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val steps: List<RouteStep>,
    val geometryPolyline: String? = null
)

/**
 * Handles geocoding and walking route calculations using the OpenRouteService API.
 */
class NavigationRepository(
    private val apiKey: String = BuildConfig.ORS_API_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Geocodes the spoken destination query using OpenRouteService Pelias search,
     * biasing results around the user's current location.
     */
    suspend fun geocodeDestination(
        query: String,
        currentLocation: Location?
    ): Result<GeocodedLocation> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val err = "OpenRouteService API key is missing. Ensure ORS_API_KEY is configured in local.properties."
            Log.e(TAG, err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val urlBuilder = StringBuilder("https://api.openrouteservice.org/geocode/search?")
                .append("api_key=").append(apiKey)
                .append("&text=").append(encodedQuery)
                .append("&size=3")

            // Focus point on user's current location to prioritize nearby destinations
            if (currentLocation != null) {
                urlBuilder.append("&focus.point.lat=").append(currentLocation.latitude)
                    .append("&focus.point.lon=").append(currentLocation.longitude)
                // Restrict search circle within 40km radius of user
                urlBuilder.append("&boundary.circle.lat=").append(currentLocation.latitude)
                    .append("&boundary.circle.lon=").append(currentLocation.longitude)
                    .append("&boundary.circle.radius=40")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            Log.d(TAG, "Requesting Pelias geocoding for query: \"$query\" (focus: ${currentLocation?.latitude}, ${currentLocation?.longitude})")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                val errorMsg = "Geocoding request failed with HTTP ${response.code}: $responseBody"
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IOException("Geocoding failed (${response.code})"))
            }

            val json = JSONObject(responseBody)
            val features = json.optJSONArray("features")
            if (features == null || features.length() == 0) {
                val notFoundMsg = "No destination found matching: \"$query\""
                Log.w(TAG, notFoundMsg)
                return@withContext Result.failure(NoSuchElementException(notFoundMsg))
            }

            // Pick the highest confidence / first feature
            val bestFeature = features.getJSONObject(0)
            val geometry = bestFeature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            // GeoJSON coordinates format: [longitude, latitude]
            val lon = coordinates.getDouble(0)
            val lat = coordinates.getDouble(1)

            val properties = bestFeature.optJSONObject("properties")
            val name = properties?.optString("name", query) ?: query
            val label = properties?.optString("label", name) ?: name

            val geocoded = GeocodedLocation(
                name = name,
                point = GeoPoint(latitude = lat, longitude = lon),
                label = label
            )

            Log.i(TAG, "Geocoded destination: \"${geocoded.name}\" at lat=$lat, lon=$lon ($label)")
            Result.success(geocoded)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during geocoding: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches a pedestrian walking route from startPoint to destinationPoint using foot-walking profile.
     */
    suspend fun fetchWalkingRoute(
        startPoint: GeoPoint,
        destination: GeocodedLocation
    ): Result<NavigationRoute> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val err = "OpenRouteService API key is missing."
            Log.e(TAG, err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val url = "https://api.openrouteservice.org/v2/directions/foot-walking"

            // Construct POST body: coordinates [[startLon, startLat], [destLon, destLat]]
            val requestJson = JSONObject().apply {
                val coordsArray = JSONArray().apply {
                    put(JSONArray().put(startPoint.longitude).put(startPoint.latitude))
                    put(JSONArray().put(destination.point.longitude).put(destination.point.latitude))
                }
                put("coordinates", coordsArray)
                put("instructions", true)
                put("language", "en")
                put("units", "m")
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            Log.d(
                TAG,
                "Requesting walking route from [${startPoint.latitude}, ${startPoint.longitude}] to [${destination.point.latitude}, ${destination.point.longitude}]"
            )

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                val errorMsg = "Routing request failed with HTTP ${response.code}: $responseBody"
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IOException("Routing failed (${response.code})"))
            }

            val json = JSONObject(responseBody)
            val routesArray = json.optJSONArray("routes")
            if (routesArray == null || routesArray.length() == 0) {
                Log.w(TAG, "No walking routes returned by OpenRouteService.")
                return@withContext Result.failure(NoSuchElementException("No pedestrian route found."))
            }

            val routeObj = routesArray.getJSONObject(0)
            val summary = routeObj.getJSONObject("summary")
            val totalDistance = summary.optDouble("distance", 0.0)
            val totalDuration = summary.optDouble("duration", 0.0)
            val geometry = if (routeObj.has("geometry")) routeObj.optString("geometry") else null

            val stepsList = mutableListOf<RouteStep>()
            val segments = routeObj.optJSONArray("segments")
            if (segments != null) {
                for (s in 0 until segments.length()) {
                    val segment = segments.getJSONObject(s)
                    val stepsArray = segment.optJSONArray("steps") ?: continue
                    for (i in 0 until stepsArray.length()) {
                        val stepObj = stepsArray.getJSONObject(i)
                        val instruction = stepObj.optString("instruction", "")
                        val distance = stepObj.optDouble("distance", 0.0)
                        val duration = stepObj.optDouble("duration", 0.0)
                        val streetName = stepObj.optString("name", "")
                        val wayPointsArray = stepObj.optJSONArray("way_points")
                        val wayPoints = mutableListOf<Int>()
                        if (wayPointsArray != null) {
                            for (w in 0 until wayPointsArray.length()) {
                                wayPoints.add(wayPointsArray.getInt(w))
                            }
                        }

                        stepsList.add(
                            RouteStep(
                                instruction = instruction,
                                distanceMeters = distance,
                                durationSeconds = duration,
                                wayPoints = wayPoints,
                                streetName = streetName
                            )
                        )
                    }
                }
            }

            val route = NavigationRoute(
                destinationName = destination.name,
                destinationPoint = destination.point,
                totalDistanceMeters = totalDistance,
                totalDurationSeconds = totalDuration,
                steps = stepsList,
                geometryPolyline = geometry
            )

            Log.i(
                TAG,
                "Walking route calculated: destination=\"${route.destinationName}\", distance=${String.format(Locale.US, "%.0f", route.totalDistanceMeters)}m, duration=${String.format(Locale.US, "%.1f", route.totalDurationSeconds / 60.0)}min, steps=${route.steps.size}"
            )

            Result.success(route)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during walking route calculation: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Combined convenience method: Geocodes destination and calculates walking route.
     */
    suspend fun calculateRouteTo(
        destinationQuery: String,
        currentLocation: Location
    ): Result<NavigationRoute> {
        val geocodeResult = geocodeDestination(destinationQuery, currentLocation)
        if (geocodeResult.isFailure) {
            return Result.failure(geocodeResult.exceptionOrNull() ?: Exception("Geocoding failed"))
        }

        val geocoded = geocodeResult.getOrThrow()
        val startPoint = GeoPoint(
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude
        )

        return fetchWalkingRoute(startPoint, geocoded)
    }

    companion object {
        private const val TAG = "NavigationRepository"
    }
}
