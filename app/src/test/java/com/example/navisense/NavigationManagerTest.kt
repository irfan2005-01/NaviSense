package com.example.navisense

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test fake implementing VoiceNotifier with priority tracking and re-queueing simulation.
 */
class TestVoiceNotifier : VoiceNotifier {
    val spokenList = mutableListOf<String>()
    var activeNavigationInstruction: String? = null
    var pendingNavigationInstruction: String? = null
    var isSpeakingNavigation = false
    var isSpeakingCriticalAlert = false
    var onDoneCallback: (() -> Unit)? = null

    override fun speak(text: String, interrupt: Boolean) {
        speak(text, interrupt, onDone = null)
    }

    override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?) {
        spokenList.add(text)
        onDoneCallback = onDone

        if (interrupt) {
            if (isSpeakingNavigation && activeNavigationInstruction != null) {
                // Interrupted! Preserve for re-queueing
                pendingNavigationInstruction = activeNavigationInstruction
                isSpeakingNavigation = false
            }
            isSpeakingCriticalAlert = true
        }
    }

    override fun speakNavigation(text: String) {
        activeNavigationInstruction = text
        if (isSpeakingCriticalAlert) {
            pendingNavigationInstruction = text
            return
        }

        isSpeakingNavigation = true
        speak(text, interrupt = false) {
            isSpeakingNavigation = false
            if (activeNavigationInstruction == text) {
                activeNavigationInstruction = null
            }
        }
    }

    /**
     * Simulates the active critical alert finishing playback.
     */
    fun simulateCriticalAlertDone() {
        isSpeakingCriticalAlert = false
        onDoneCallback?.invoke()
        onDoneCallback = null

        val toRespeak = pendingNavigationInstruction
        if (toRespeak != null) {
            pendingNavigationInstruction = null
            speakNavigation(toRespeak)
        }
    }

    override fun stop() {
        activeNavigationInstruction = null
        pendingNavigationInstruction = null
        isSpeakingNavigation = false
        isSpeakingCriticalAlert = false
    }

    fun clear() {
        spokenList.clear()
        stop()
    }
}

/**
 * Test fake implementing HapticNotifier with call counts for navigation patterns.
 */
class TestHapticNotifier : HapticNotifier {
    override var isEnabled: Boolean = true
    var turnLeftCount = 0
    var turnRightCount = 0
    var destinationReachedCount = 0
    var cancelCount = 0

    override fun vibrateForObstacleAlert(distanceMeters: Float, zone: Zone?) {}
    override fun updateContinuousCenterObstacle(distanceMeters: Float) {}
    override fun clearContinuousCenterObstacle() {}

    override fun vibrateTurnLeft() {
        turnLeftCount++
    }

    override fun vibrateTurnRight() {
        turnRightCount++
    }

    override fun vibrateDestinationReached() {
        destinationReachedCount++
    }

    override fun cancel() {
        cancelCount++
    }

    fun clear() {
        turnLeftCount = 0
        turnRightCount = 0
        destinationReachedCount = 0
        cancelCount = 0
    }
}

class NavigationManagerTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private lateinit var fakeVoice: TestVoiceNotifier
    private lateinit var fakeHaptics: TestHapticNotifier
    private var simulatedTimeMs: Long = 1000L
    private lateinit var navigationRepository: NavigationRepository
    private lateinit var navigationManager: NavigationManager

    // Sample route geometry coordinates
    // P0: 17.38500, 78.48600 (Start)
    // P1: 17.38550, 78.48600 (~55m north)
    // P2: 17.38600, 78.48600 (~111m north of P0 - Turn Left onto Market Street)
    // P3: 17.38600, 78.48500 (~106m west of P2 - Turn Right onto Church Street)
    // P4: 17.38700, 78.48500 (~111m north of P3 - Destination)
    private val polylinePoints = listOf(
        GeoPoint(17.38500, 78.48600), // idx 0
        GeoPoint(17.38550, 78.48600), // idx 1
        GeoPoint(17.38600, 78.48600), // idx 2: Maneuver 1 (Turn left)
        GeoPoint(17.38600, 78.48500), // idx 3: Maneuver 2 (Turn right)
        GeoPoint(17.38700, 78.48500)  // idx 4: Destination
    )

    private fun buildSampleRoute(): NavigationRoute {
        val encodedGeometry = GeoUtils.encodePolyline(polylinePoints)

        val steps = listOf(
            RouteStep(
                instruction = "Head north on Main Road",
                distanceMeters = 111.0,
                durationSeconds = 88.0,
                wayPoints = listOf(0, 2),
                streetName = "Main Road"
            ),
            RouteStep(
                instruction = "Turn left onto Market Street",
                distanceMeters = 106.0,
                durationSeconds = 85.0,
                wayPoints = listOf(2, 3),
                streetName = "Market Street"
            ),
            RouteStep(
                instruction = "Turn right onto Church Street",
                distanceMeters = 111.0,
                durationSeconds = 88.0,
                wayPoints = listOf(3, 4),
                streetName = "Church Street"
            )
        )

        return NavigationRoute(
            destinationName = "Grand Central",
            destinationPoint = polylinePoints.last(),
            totalDistanceMeters = 328.0,
            totalDurationSeconds = 261.0,
            steps = steps,
            geometryPolyline = encodedGeometry
        )
    }

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

    @Before
    fun setup() {
        fakeVoice = TestVoiceNotifier()
        fakeHaptics = TestHapticNotifier()
        simulatedTimeMs = 1000L

        val mockClient = createMockClient(200, """{"routes":[]}""")
        navigationRepository = NavigationRepository(apiKey = "dummy", client = mockClient)

        navigationManager = NavigationManager(
            navigationRepository = navigationRepository,
            voiceManager = fakeVoice,
            hapticManager = fakeHaptics,
            coroutineScope = testScope,
            timeProvider = { simulatedTimeMs }
        )
    }

    @Test
    fun startNavigation_setsNavigatingAndSpeaksDestination() {
        val route = buildSampleRoute()
        navigationManager.setRoute(route)

        val started = navigationManager.startNavigation()
        assertTrue(started)
        assertTrue(navigationManager.isNavigating)
        assertEquals(1, fakeVoice.spokenList.size)
        assertTrue(fakeVoice.spokenList[0].contains("Starting navigation to Grand Central"))
        assertTrue(fakeVoice.spokenList[0].contains("Head north on Main Road"))
    }

    @Test
    fun stepTriggering_30mAnd10mCuesWithHaptics() {
        val route = buildSampleRoute()
        navigationManager.setRoute(route)
        navigationManager.startNavigation()
        fakeVoice.clear()
        fakeHaptics.clear()

        // Waypoint 2 is at lat: 17.38600, lon: 78.48600.
        // User moves north along Main Road towards waypoint 2:

        // 1. User at 45m before waypoint 2 (lat: 17.38560, lon: 78.48600)
        navigationManager.processLocation(latitude = 17.38560, longitude = 78.48600, accuracy = 5f)
        assertEquals("No maneuver cue should trigger beyond 32m", 0, fakeVoice.spokenList.size)
        assertEquals(0, fakeHaptics.turnLeftCount)

        // 2. User reaches ~28m before waypoint 2 (lat: 17.38575, lon: 78.48600)
        navigationManager.processLocation(latitude = 17.38575, longitude = 78.48600, accuracy = 5f)
        assertEquals("30m cue should trigger", 1, fakeVoice.spokenList.size)
        assertEquals("In 30 meters, turn left onto Market Street", fakeVoice.spokenList[0])
        assertEquals("No haptic vibration at 30m cue", 0, fakeHaptics.turnLeftCount)

        // 3. User moves to ~20m (lat: 17.38582, lon: 78.48600) -> should NOT repeat 30m cue
        navigationManager.processLocation(latitude = 17.38582, longitude = 78.48600, accuracy = 5f)
        assertEquals("Cue must not repeat while approaching", 1, fakeVoice.spokenList.size)

        // 4. User reaches ~9m before waypoint 2 (lat: 17.38592, lon: 78.48600)
        navigationManager.processLocation(latitude = 17.38592, longitude = 78.48600, accuracy = 5f)
        assertEquals("10m cue should trigger", 2, fakeVoice.spokenList.size)
        assertEquals("Turn left now", fakeVoice.spokenList[1])
        assertEquals("Haptic turn left should trigger at 10m", 1, fakeHaptics.turnLeftCount)

        // 5. User reaches waypoint 2 (lat: 17.38600, lon: 78.48600) -> step advances to 1
        navigationManager.processLocation(latitude = 17.38600, longitude = 78.48600, accuracy = 5f)
        assertEquals("Step index should advance to 1", 1, navigationManager.currentStepIndex)

        // 6. User moves west along Market Street towards waypoint 3 (lon: 78.48500)
        // Waypoint 3 is at lat: 17.38600, lon: 78.48500 (Turn right onto Church Street)
        // User reaches ~25m before waypoint 3 (lat: 17.38600, lon: 78.48523)
        fakeVoice.clear()
        fakeHaptics.clear()
        navigationManager.processLocation(latitude = 17.38600, longitude = 78.48523, accuracy = 5f)
        assertEquals("30m cue for next step should trigger", 1, fakeVoice.spokenList.size)
        assertEquals("In 30 meters, turn right onto Church Street", fakeVoice.spokenList[0])

        // User reaches ~8m before waypoint 3 (lat: 17.38600, lon: 78.48507)
        navigationManager.processLocation(latitude = 17.38600, longitude = 78.48507, accuracy = 5f)
        assertEquals(2, fakeVoice.spokenList.size)
        assertEquals("Turn right now", fakeVoice.spokenList[1])
        assertEquals("Haptic turn right should trigger at 10m", 1, fakeHaptics.turnRightCount)
    }

    @Test
    fun turnSensibility_handlesSlightSharpStraightAndUTurn() {
        // Test parsing and formatting of various maneuvers
        val slightLeft = NavigationManager.parseManeuver("Turn slight left onto Ring Road", "Ring Road")
        assertEquals(ManeuverType.TURN_SLIGHT_LEFT, slightLeft.type)
        assertEquals("In 30 meters, turn slight left onto Ring Road", NavigationManager.format30mCue(slightLeft))
        assertEquals("Turn slight left now", NavigationManager.format10mCue(slightLeft))

        val sharpRight = NavigationManager.parseManeuver("Turn sharp right onto Narrow Lane", "Narrow Lane")
        assertEquals(ManeuverType.TURN_SHARP_RIGHT, sharpRight.type)
        assertEquals("In 30 meters, turn sharp right onto Narrow Lane", NavigationManager.format30mCue(sharpRight))
        assertEquals("Turn sharp right now", NavigationManager.format10mCue(sharpRight))

        val uTurn = NavigationManager.parseManeuver("Make a U-turn onto Highway", "Highway")
        assertEquals(ManeuverType.U_TURN, uTurn.type)
        assertEquals("In 30 meters, make a U-turn onto Highway", NavigationManager.format30mCue(uTurn))
        assertEquals("Make a U-turn now", NavigationManager.format10mCue(uTurn))

        val straight = NavigationManager.parseManeuver("Continue straight onto MG Road", "MG Road")
        assertEquals(ManeuverType.STRAIGHT, straight.type)
        assertEquals("In 30 meters, continue straight onto MG Road", NavigationManager.format30mCue(straight))
        assertEquals("Continue straight now", NavigationManager.format10mCue(straight))
    }

    @Test
    fun gpsAccuracyFiltering_ignoresUpdatesWorseThan25m() {
        val route = buildSampleRoute()
        navigationManager.setRoute(route)
        navigationManager.startNavigation()
        fakeVoice.clear()
        fakeHaptics.clear()

        // User is right at the 10m turn point, but GPS accuracy is bad: 30m (> 25m)
        navigationManager.processLocation(latitude = 17.38592, longitude = 78.48600, accuracy = 30f)

        assertEquals("No announcement should trigger for accuracy > 25m", 0, fakeVoice.spokenList.size)
        assertEquals("No haptics should trigger for accuracy > 25m", 0, fakeHaptics.turnLeftCount)

        // Same location, but with good accuracy (10m <= 25m)
        navigationManager.processLocation(latitude = 17.38592, longitude = 78.48600, accuracy = 10f)

        assertEquals("Announcement should trigger for accuracy <= 25m", 1, fakeVoice.spokenList.size)
        assertEquals("Turn left now", fakeVoice.spokenList[0])
        assertEquals(1, fakeHaptics.turnLeftCount)
    }

    @Test
    fun offRouteDetection_triggersRecalculationOnlyAfter5Seconds() = runBlocking {
        val rerouteJson = """
        {
          "routes": [
            {
              "summary": {"distance": 200.0, "duration": 160.0},
              "segments": [
                {
                  "steps": [
                    {
                      "instruction": "Head northeast to reconnect",
                      "distance": 200.0,
                      "duration": 160.0,
                      "way_points": [0, 1],
                      "name": "Side Street"
                    }
                  ]
                }
              ],
              "geometry": "${GeoUtils.encodePolyline(listOf(GeoPoint(17.3855, 78.4880), GeoPoint(17.3870, 78.4850)))}"
            }
          ]
        }
        """.trimIndent()

        val mockClient = createMockClient(200, rerouteJson)
        val repo = NavigationRepository(apiKey = "dummy", client = mockClient)

        navigationManager = NavigationManager(
            navigationRepository = repo,
            voiceManager = fakeVoice,
            hapticManager = fakeHaptics,
            coroutineScope = this,
            timeProvider = { simulatedTimeMs }
        )

        navigationManager.setRoute(buildSampleRoute())
        navigationManager.startNavigation()
        fakeVoice.clear()

        // Main Road runs along lon: 78.48600
        // Point shifted east by ~50m (lon: 78.48650) -> off route!
        val offRouteLat = 17.38550
        val offRouteLon = 78.48650 // ~53m away from polyline (> 30m)

        // 1. Initial off-route detection at t = 1000ms
        simulatedTimeMs = 1000L
        navigationManager.processLocation(offRouteLat, offRouteLon, accuracy = 5f)
        assertEquals("No reroute should trigger immediately", 0, fakeVoice.spokenList.size)

        // 2. 3 seconds later at t = 4000ms (elapsed 3s < 5s)
        simulatedTimeMs = 4000L
        navigationManager.processLocation(offRouteLat, offRouteLon, accuracy = 5f)
        assertEquals("No reroute should trigger before 5s elapsed", 0, fakeVoice.spokenList.size)

        // 3. User moves back on route at t = 5000ms (lat: 17.38550, lon: 78.48600) -> timer resets
        simulatedTimeMs = 5000L
        navigationManager.processLocation(17.38550, 78.48600, accuracy = 5f)
        assertEquals(0, fakeVoice.spokenList.size)

        // 4. User deviates again at t = 6000ms
        simulatedTimeMs = 6000L
        navigationManager.processLocation(offRouteLat, offRouteLon, accuracy = 5f)
        assertEquals(0, fakeVoice.spokenList.size)

        // 5. At t = 11500ms (elapsed 5.5s >= 5s) -> triggers recalculation!
        simulatedTimeMs = 11500L
        navigationManager.processLocation(offRouteLat, offRouteLon, accuracy = 5f)
        navigationManager.recalculationJob?.join()

        assertTrue("Off-route recalculation message must be spoken", fakeVoice.spokenList.any { it.contains("You are off route, recalculating") })
        assertTrue("New route instruction must be spoken after recalculation", fakeVoice.spokenList.any { it.contains("New route found") })
    }

    @Test
    fun arrivalDetection_whenWithin15Meters_announcesAndStops() {
        val route = buildSampleRoute()
        navigationManager.setRoute(route)
        navigationManager.startNavigation()
        fakeVoice.clear()
        fakeHaptics.clear()

        // Destination is at lat: 17.38700, lon: 78.48500
        // User at ~25m away (lat: 17.38677, lon: 78.48500) -> not yet arrived
        navigationManager.processLocation(latitude = 17.38677, longitude = 78.48500, accuracy = 5f)
        assertTrue(navigationManager.isNavigating)
        assertEquals(0, fakeHaptics.destinationReachedCount)

        // User reaches within 12m of destination (lat: 17.38690, lon: 78.48500)
        navigationManager.processLocation(latitude = 17.38690, longitude = 78.48500, accuracy = 5f)

        assertFalse("Navigation should stop upon arrival", navigationManager.isNavigating)
        assertTrue("Arrival speech should trigger", fakeVoice.spokenList.contains("You have reached your destination"))
        assertEquals("Destination reached haptic should trigger", 1, fakeHaptics.destinationReachedCount)
    }

    @Test
    fun voiceCommands_startStopRepeatHowFar() {
        val route = buildSampleRoute()
        navigationManager.setRoute(route)

        // 1. "start navigation"
        val startHandled = navigationManager.handleVoiceCommand("start navigation")
        assertTrue(startHandled)
        assertTrue(navigationManager.isNavigating)

        // 2. "how far"
        fakeVoice.clear()
        val howFarHandled = navigationManager.handleVoiceCommand("how far")
        assertTrue(howFarHandled)
        assertEquals(1, fakeVoice.spokenList.size)
        assertTrue(fakeVoice.spokenList[0].contains("remaining"))

        // 3. "repeat"
        fakeVoice.clear()
        val repeatHandled = navigationManager.handleVoiceCommand("repeat")
        assertTrue(repeatHandled)
        assertEquals(1, fakeVoice.spokenList.size)

        // 4. "stop navigation"
        fakeVoice.clear()
        val stopHandled = navigationManager.handleVoiceCommand("stop navigation")
        assertTrue(stopHandled)
        assertFalse(navigationManager.isNavigating)
        assertTrue(fakeVoice.spokenList.contains("Navigation stopped."))

        // 5. Commands when not navigating
        fakeVoice.clear()
        navigationManager.handleVoiceCommand("repeat")
        assertEquals("Navigation is not active.", fakeVoice.spokenList[0])

        fakeVoice.clear()
        navigationManager.handleVoiceCommand("how far")
        assertEquals("Navigation is not active.", fakeVoice.spokenList[0])
    }

    @Test
    fun priorityRule_closeObstacleAlertInterruptsAndRequeuesNavigationInstruction() {
        // Start navigation instruction
        fakeVoice.speakNavigation("In 30 meters, turn left onto Market Street")

        assertTrue("Navigation should be speaking", fakeVoice.isSpeakingNavigation)
        assertEquals("In 30 meters, turn left onto Market Street", fakeVoice.spokenList.last())

        // Sudden very close obstacle alert (< 1.5m) interrupts speech
        fakeVoice.speak("Person ahead, 1 meter", interrupt = true)

        assertFalse("Navigation speech was interrupted", fakeVoice.isSpeakingNavigation)
        assertTrue("Critical alert is speaking", fakeVoice.isSpeakingCriticalAlert)
        assertEquals("Person ahead, 1 meter", fakeVoice.spokenList.last())
        assertEquals("Navigation instruction preserved in pending queue", "In 30 meters, turn left onto Market Street", fakeVoice.pendingNavigationInstruction)

        // Obstacle alert finishes playback
        fakeVoice.simulateCriticalAlertDone()

        assertFalse("Critical alert finished", fakeVoice.isSpeakingCriticalAlert)
        assertEquals("Navigation instruction re-queued and spoken after alert", "In 30 meters, turn left onto Market Street", fakeVoice.spokenList.last())
    }
}
