package com.example.navisense

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Types of navigational maneuvers supported by turn-by-turn guidance.
 */
enum class ManeuverType {
    TURN_LEFT,
    TURN_SLIGHT_LEFT,
    TURN_SHARP_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_RIGHT,
    TURN_SHARP_RIGHT,
    U_TURN,
    STRAIGHT,
    UNKNOWN
}

/**
 * Parsed maneuver metadata extracted from OpenRouteService instruction and street name.
 */
data class ParsedManeuver(
    val type: ManeuverType,
    val actionText: String,
    val fullInstruction: String,
    val streetName: String
)

/**
 * State tracking for an individual step in the active route.
 */
data class StepNavigationState(
    val stepIndex: Int,
    val step: RouteStep,
    val maneuverPoint: GeoPoint,
    val parsedManeuver: ParsedManeuver,
    var announced30m: Boolean = false,
    var announced10m: Boolean = false
)

/**
 * Manages turn-by-turn walking guidance:
 * - Tracks live GPS updates against route steps and polyline
 * - Filters GPS accuracy (> 25m ignored)
 * - Triggers timed announcements at ~30m ("In 30 meters, turn left onto <street>")
 *   and ~10m ("Turn left now") + synchronized directional haptics
 * - Off-route deviation detection (> 30m away for 5+s) -> rerouting
 * - Arrival detection (<= 15m to destination) -> stop navigation
 * - Voice commands ("start navigation", "stop navigation", "repeat", "how far")
 * - High priority interruption and re-queueing via VoiceNotifier
 */
class NavigationManager(
    private val navigationRepository: NavigationRepository,
    private val voiceManager: VoiceNotifier,
    private val hapticManager: HapticNotifier,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    var onNavigationStateChanged: ((Boolean) -> Unit)? = null,
    var onRouteUpdated: ((NavigationRoute) -> Unit)? = null,
    var onStatusMessage: ((String) -> Unit)? = null
) {

    var isNavigating: Boolean = false
        private set

    var currentRoute: NavigationRoute? = null
        private set

    var currentStepIndex: Int = 0
        private set

    var lastSpokenInstruction: String? = null
        private set

    var isRecalculating: Boolean = false
        private set

    private var decodedPolyline: List<GeoPoint> = emptyList()
    private var stepStates: MutableList<StepNavigationState> = mutableListOf()
    private var lastKnownPosition: GeoPoint? = null
    private var offRouteStartTime: Long? = null
    var recalculationJob: Job? = null
        private set

    /**
     * Sets or updates the active navigation route and prepares step maneuver points.
     */
    fun setRoute(route: NavigationRoute) {
        currentRoute = route
        decodedPolyline = GeoUtils.decodePolyline(route.geometryPolyline)
        stepStates.clear()
        currentStepIndex = 0
        offRouteStartTime = null
        isRecalculating = false

        // Build StepNavigationState for each step
        val steps = route.steps
        for (i in steps.indices) {
            val step = steps[i]
            val parsed = parseManeuver(step.instruction, step.streetName)

            // Determine maneuver location
            // In ORS, step[i].wayPoints[0] is where step i begins / where the maneuver takes place.
            val maneuverPoint = if (decodedPolyline.isNotEmpty() && step.wayPoints.isNotEmpty()) {
                val pointIndex = step.wayPoints[0].coerceIn(0, decodedPolyline.size - 1)
                decodedPolyline[pointIndex]
            } else if (i == steps.size - 1) {
                route.destinationPoint
            } else {
                route.destinationPoint
            }

            stepStates.add(
                StepNavigationState(
                    stepIndex = i,
                    step = step,
                    maneuverPoint = maneuverPoint,
                    parsedManeuver = parsed
                )
            )
        }

        Log.i(
            TAG,
            "Route set: ${route.destinationName}, ${steps.size} steps, polyline points=${decodedPolyline.size}"
        )
    }

    /**
     * Starts turn-by-turn guidance.
     */
    fun startNavigation(): Boolean {
        val route = currentRoute
        if (route == null) {
            voiceManager.speakNavigation("No destination selected. Double tap anywhere to speak a destination.")
            return false
        }

        if (isNavigating) {
            voiceManager.speakNavigation("Navigation is already active.")
            return true
        }

        isNavigating = true
        currentStepIndex = 0
        offRouteStartTime = null
        isRecalculating = false

        val firstStep = route.steps.firstOrNull()
        val startMsg = if (firstStep != null && firstStep.instruction.isNotBlank()) {
            "Starting navigation to ${route.destinationName}. ${firstStep.instruction}."
        } else {
            "Starting navigation to ${route.destinationName}."
        }

        lastSpokenInstruction = startMsg
        voiceManager.speakNavigation(startMsg)
        onNavigationStateChanged?.invoke(true)
        onStatusMessage?.invoke("Navigating to ${route.destinationName}")
        Log.i(TAG, "Navigation started to: ${route.destinationName}")
        return true
    }

    /**
     * Stops active turn-by-turn guidance.
     */
    fun stopNavigation() {
        if (!isNavigating) return
        isNavigating = false
        recalculationJob?.cancel()
        isRecalculating = false
        offRouteStartTime = null
        hapticManager.cancel()
        onNavigationStateChanged?.invoke(false)
        onStatusMessage?.invoke("Navigation stopped")
        Log.i(TAG, "Navigation stopped.")
    }

    /**
     * Repeats the most recent navigation instruction.
     */
    fun repeatInstruction() {
        if (!isNavigating) {
            voiceManager.speakNavigation("Navigation is not active.")
            return
        }

        val speech = lastSpokenInstruction
            ?: stepStates.getOrNull(currentStepIndex)?.step?.instruction
            ?: "Continue along the route"

        Log.i(TAG, "Repeating instruction: \"$speech\"")
        voiceManager.speakNavigation(speech)
    }

    /**
     * Announces remaining distance and estimated time.
     */
    fun howFar() {
        if (!isNavigating || currentRoute == null) {
            voiceManager.speakNavigation("Navigation is not active.")
            return
        }

        val pos = lastKnownPosition
        val remMeters = if (pos != null && decodedPolyline.isNotEmpty()) {
            val nextWaypointIndex = stepStates.getOrNull(currentStepIndex)?.step?.wayPoints?.lastOrNull() ?: 0
            GeoUtils.calculateRemainingDistanceMeters(pos, decodedPolyline, nextWaypointIndex)
        } else if (pos != null) {
            GeoUtils.distanceMeters(pos, currentRoute!!.destinationPoint)
        } else {
            currentRoute!!.totalDistanceMeters
        }

        val distInt = Math.round(remMeters).toInt()
        val minutes = Math.max(1, Math.round(remMeters / WALKING_SPEED_METERS_PER_MIN).toInt())
        val timeStr = if (minutes <= 1) "1 minute" else "$minutes minutes"
        val announcement = "About $distInt meters, $timeStr remaining."

        Log.i(TAG, "howFar response: \"$announcement\"")
        lastSpokenInstruction = announcement
        voiceManager.speakNavigation(announcement)
    }

    /**
     * Routes incoming spoken voice commands.
     * Returns true if command was handled by navigation.
     */
    fun handleVoiceCommand(command: String): Boolean {
        val trimmed = command.trim()
        val lower = trimmed.lowercase(Locale.US)

        return when {
            lower == "start navigation" || lower == "start" || lower == "begin navigation" -> {
                startNavigation()
                true
            }

            lower == "stop navigation" || lower == "stop" || lower == "cancel navigation" -> {
                if (isNavigating) {
                    stopNavigation()
                    val stopMsg = "Navigation stopped."
                    lastSpokenInstruction = stopMsg
                    voiceManager.speakNavigation(stopMsg)
                } else {
                    voiceManager.speakNavigation("Navigation is not active.")
                }
                true
            }

            lower == "repeat" || lower == "repeat instruction" || lower == "what was that" || lower == "say again" -> {
                repeatInstruction()
                true
            }

            lower == "how far" || lower == "how far is it" || lower == "how long" ||
                    lower == "remaining distance" || lower == "distance" -> {
                howFar()
                true
            }

            else -> false
        }
    }

    /**
     * Handles live Android Location updates with GPS accuracy filtering (Requirement 7).
     */
    fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_ALLOWED_ACCURACY_METERS) {
            Log.d(TAG, "Ignoring location update: poor accuracy (${location.accuracy}m > ${MAX_ALLOWED_ACCURACY_METERS}m)")
            return
        }

        processLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        )
    }

    /**
     * Processes coordinates directly for clean testability using simulated coordinates.
     */
    fun processLocation(latitude: Double, longitude: Double, accuracy: Float = 0f) {
        if (accuracy > MAX_ALLOWED_ACCURACY_METERS && accuracy > 0f) {
            Log.d(TAG, "Ignoring coordinate update: poor accuracy (${accuracy}m > ${MAX_ALLOWED_ACCURACY_METERS}m)")
            return
        }

        if (!isNavigating || currentRoute == null) return

        val currentPos = GeoPoint(latitude, longitude)
        lastKnownPosition = currentPos
        val route = currentRoute!!

        // 1. Check Arrival (Requirement 4: within 15 meters of destination)
        val distToDestination = GeoUtils.distanceMeters(currentPos, route.destinationPoint)
        if (distToDestination <= ARRIVAL_THRESHOLD_METERS) {
            handleArrival()
            return
        }

        // 2. Check Off-route deviation (Requirement 3: > 30m away for 5+s)
        val distToPolyline = if (decodedPolyline.isNotEmpty()) {
            GeoUtils.distanceToPolylineMeters(currentPos, decodedPolyline)
        } else {
            distToDestination
        }

        checkOffRoute(currentPos, distToPolyline)

        // 3. Step turn-by-turn guidance and timing (Requirement 2)
        processStepGuidance(currentPos)
    }

    private fun handleArrival() {
        val arrivalSpeech = "You have reached your destination"
        Log.i(TAG, "Arrival detected! Triggering voice cue and destination haptic.")
        lastSpokenInstruction = arrivalSpeech
        voiceManager.speakNavigation(arrivalSpeech)
        hapticManager.vibrateDestinationReached()
        stopNavigation()
        onStatusMessage?.invoke("Arrived at destination")
    }

    private fun checkOffRoute(currentPos: GeoPoint, distToPolyline: Double) {
        if (isRecalculating) return

        val now = timeProvider()
        if (distToPolyline > OFF_ROUTE_DISTANCE_METERS) {
            if (offRouteStartTime == null) {
                offRouteStartTime = now
                Log.d(TAG, "User deviated off route (${distToPolyline.toInt()}m > 30m). Deviation timer started.")
            } else {
                val elapsed = now - offRouteStartTime!!
                if (elapsed >= OFF_ROUTE_TIMEOUT_MS) {
                    Log.w(TAG, "Off route for ${elapsed}ms (>= 5s). Triggering recalculation.")
                    offRouteStartTime = null
                    triggerRecalculation(currentPos)
                }
            }
        } else {
            if (offRouteStartTime != null) {
                Log.d(TAG, "User back on route (${distToPolyline.toInt()}m <= 30m). Timer reset.")
                offRouteStartTime = null
            }
        }
    }

    private fun triggerRecalculation(currentPos: GeoPoint) {
        if (isRecalculating) return
        isRecalculating = true

        val alertMsg = "You are off route, recalculating"
        lastSpokenInstruction = alertMsg
        voiceManager.speakNavigation(alertMsg)
        onStatusMessage?.invoke(alertMsg)

        val destination = currentRoute?.destinationName ?: return
        val destPoint = currentRoute?.destinationPoint ?: return

        recalculationJob?.cancel()
        recalculationJob = coroutineScope.launch {
            try {
                val target = GeocodedLocation(
                    name = destination,
                    point = destPoint,
                    label = destination
                )
                val result = navigationRepository.fetchWalkingRoute(currentPos, target)
                isRecalculating = false

                result.onSuccess { newRoute ->
                    Log.i(TAG, "Successfully recalculated walking route: ${newRoute.steps.size} steps")
                    setRoute(newRoute)
                    onRouteUpdated?.invoke(newRoute)
                    isNavigating = true

                    val firstStep = newRoute.steps.firstOrNull()?.instruction ?: "Continue along route"
                    val rerouteSpeech = "New route found. $firstStep"
                    lastSpokenInstruction = rerouteSpeech
                    voiceManager.speakNavigation(rerouteSpeech)
                    onStatusMessage?.invoke("Rerouted to $destination")
                }.onFailure { err ->
                    Log.e(TAG, "Route recalculation failed: ${err.message}", err)
                    voiceManager.speakNavigation("Could not recalculate route. Please continue toward your destination.")
                }
            } catch (e: Exception) {
                isRecalculating = false
                Log.e(TAG, "Exception during rerouting", e)
            }
        }
    }

    /**
     * Evaluates live user position against the current and upcoming steps.
     * Triggers 30m cue, 10m cue + haptic, and advances to the next step.
     */
    private fun processStepGuidance(currentPos: GeoPoint) {
        if (stepStates.isEmpty()) return

        // Check if there is an upcoming maneuver step
        // In a route of steps [0..N-1]:
        // While user is in step k, the next turn maneuver is at step k+1 (or step k if at beginning)
        val upcomingStepIndex = if (currentStepIndex + 1 < stepStates.size) {
            currentStepIndex + 1
        } else {
            currentStepIndex
        }

        val targetState = stepStates[upcomingStepIndex]
        val distToManeuver = GeoUtils.distanceMeters(currentPos, targetState.maneuverPoint)

        Log.d(
            TAG,
            "Step guidance: activeStep=$currentStepIndex, targetManeuverStep=$upcomingStepIndex, distToManeuver=${String.format(Locale.US, "%.1f", distToManeuver)}m"
        )

        // 1. Announce 30m cue (approx 30m before maneuver)
        if (distToManeuver <= MANEUVER_30M_THRESHOLD && distToManeuver > MANEUVER_10M_THRESHOLD) {
            if (!targetState.announced30m) {
                targetState.announced30m = true
                val cue30m = format30mCue(targetState.parsedManeuver)
                Log.i(TAG, "Triggering 30m cue: \"$cue30m\" (dist=${distToManeuver.toInt()}m)")
                lastSpokenInstruction = cue30m
                voiceManager.speakNavigation(cue30m)
            }
        }

        // 2. Announce 10m cue (approx 10m before maneuver) + directional haptic
        if (distToManeuver <= MANEUVER_10M_THRESHOLD) {
            if (!targetState.announced10m) {
                targetState.announced10m = true
                val cue10m = format10mCue(targetState.parsedManeuver)
                Log.i(TAG, "Triggering 10m cue: \"$cue10m\" (dist=${distToManeuver.toInt()}m) + haptic")
                lastSpokenInstruction = cue10m
                voiceManager.speakNavigation(cue10m)
                triggerManeuverHaptic(targetState.parsedManeuver.type)
            }

            // Advance to next step once user is very close to or past the maneuver point
            if (distToManeuver <= STEP_ADVANCE_DISTANCE_METERS && currentStepIndex < upcomingStepIndex) {
                Log.i(TAG, "Advancing active step from $currentStepIndex to $upcomingStepIndex")
                currentStepIndex = upcomingStepIndex
            }
        }
    }

    private fun triggerManeuverHaptic(type: ManeuverType) {
        when (type) {
            ManeuverType.TURN_LEFT,
            ManeuverType.TURN_SLIGHT_LEFT,
            ManeuverType.TURN_SHARP_LEFT,
            ManeuverType.U_TURN -> {
                hapticManager.vibrateTurnLeft()
            }

            ManeuverType.TURN_RIGHT,
            ManeuverType.TURN_SLIGHT_RIGHT,
            ManeuverType.TURN_SHARP_RIGHT -> {
                hapticManager.vibrateTurnRight()
            }

            ManeuverType.STRAIGHT,
            ManeuverType.UNKNOWN -> {
                // Straight maneuvers do not trigger directional turn vibrations
            }
        }
    }

    companion object {
        private const val TAG = "NavigationManager"

        // Accuracy threshold: updates worse than 25m are discarded (Requirement 7)
        const val MAX_ALLOWED_ACCURACY_METERS = 25.0f

        // Off-route thresholds: > 30m for 5+ seconds (Requirement 3)
        const val OFF_ROUTE_DISTANCE_METERS = 30.0
        const val OFF_ROUTE_TIMEOUT_MS = 5000L

        // Arrival threshold: within 15m of destination (Requirement 4)
        const val ARRIVAL_THRESHOLD_METERS = 15.0

        // Maneuver announcement distances (Requirement 2)
        const val MANEUVER_30M_THRESHOLD = 32.0 // ~30 meters
        const val MANEUVER_10M_THRESHOLD = 10.0 // ~10 meters
        const val STEP_ADVANCE_DISTANCE_METERS = 8.0

        // Average walking speed (75 meters per minute = 1.25 m/s)
        const val WALKING_SPEED_METERS_PER_MIN = 75.0

        /**
         * Parses human and OpenRouteService directions into structured maneuver details.
         */
        fun parseManeuver(instruction: String, streetName: String): ParsedManeuver {
            val lower = instruction.lowercase(Locale.US)

            val type = when {
                lower.contains("u-turn") || lower.contains("turn around") -> ManeuverType.U_TURN
                lower.contains("slight left") -> ManeuverType.TURN_SLIGHT_LEFT
                lower.contains("sharp left") -> ManeuverType.TURN_SHARP_LEFT
                lower.contains("slight right") -> ManeuverType.TURN_SLIGHT_RIGHT
                lower.contains("sharp right") -> ManeuverType.TURN_SHARP_RIGHT
                lower.contains("turn left") || lower.contains("left onto") -> ManeuverType.TURN_LEFT
                lower.contains("turn right") || lower.contains("right onto") -> ManeuverType.TURN_RIGHT
                lower.contains("keep left") -> ManeuverType.TURN_SLIGHT_LEFT
                lower.contains("keep right") -> ManeuverType.TURN_SLIGHT_RIGHT
                lower.contains("straight") || lower.contains("head ") || lower.contains("continue") -> ManeuverType.STRAIGHT
                else -> ManeuverType.STRAIGHT
            }

            val actionText = when (type) {
                ManeuverType.TURN_LEFT -> "turn left"
                ManeuverType.TURN_SLIGHT_LEFT -> "turn slight left"
                ManeuverType.TURN_SHARP_LEFT -> "turn sharp left"
                ManeuverType.TURN_RIGHT -> "turn right"
                ManeuverType.TURN_SLIGHT_RIGHT -> "turn slight right"
                ManeuverType.TURN_SHARP_RIGHT -> "turn sharp right"
                ManeuverType.U_TURN -> "make a U-turn"
                ManeuverType.STRAIGHT -> "continue straight"
                ManeuverType.UNKNOWN -> "continue"
            }

            return ParsedManeuver(
                type = type,
                actionText = actionText,
                fullInstruction = instruction,
                streetName = streetName
            )
        }

        fun format30mCue(maneuver: ParsedManeuver): String {
            val street = maneuver.streetName.trim()
            val hasStreet = street.isNotBlank() && street != "-" && !street.equals("unnamed", ignoreCase = true)
            val streetSuffix = if (hasStreet) " onto $street" else ""

            return when (maneuver.type) {
                ManeuverType.TURN_LEFT -> "In 30 meters, turn left$streetSuffix"
                ManeuverType.TURN_SLIGHT_LEFT -> "In 30 meters, turn slight left$streetSuffix"
                ManeuverType.TURN_SHARP_LEFT -> "In 30 meters, turn sharp left$streetSuffix"
                ManeuverType.TURN_RIGHT -> "In 30 meters, turn right$streetSuffix"
                ManeuverType.TURN_SLIGHT_RIGHT -> "In 30 meters, turn slight right$streetSuffix"
                ManeuverType.TURN_SHARP_RIGHT -> "In 30 meters, turn sharp right$streetSuffix"
                ManeuverType.U_TURN -> "In 30 meters, make a U-turn$streetSuffix"
                ManeuverType.STRAIGHT -> {
                    if (hasStreet) "In 30 meters, continue straight onto $street"
                    else "In 30 meters, continue straight"
                }
                ManeuverType.UNKNOWN -> "In 30 meters, ${maneuver.actionText}$streetSuffix"
            }
        }

        fun format10mCue(maneuver: ParsedManeuver): String {
            return when (maneuver.type) {
                ManeuverType.TURN_LEFT -> "Turn left now"
                ManeuverType.TURN_SLIGHT_LEFT -> "Turn slight left now"
                ManeuverType.TURN_SHARP_LEFT -> "Turn sharp left now"
                ManeuverType.TURN_RIGHT -> "Turn right now"
                ManeuverType.TURN_SLIGHT_RIGHT -> "Turn slight right now"
                ManeuverType.TURN_SHARP_RIGHT -> "Turn sharp right now"
                ManeuverType.U_TURN -> "Make a U-turn now"
                ManeuverType.STRAIGHT -> "Continue straight now"
                ManeuverType.UNKNOWN -> "${maneuver.actionText.replaceFirstChar { it.titlecase(Locale.US) }} now"
            }
        }
    }
}
