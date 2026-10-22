package com.example.navisense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.navisense.ui.theme.NaviSenseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var voiceManager: VoiceManager
    private lateinit var hapticManager: HapticManager
    private lateinit var cameraManager: CameraManager
    private lateinit var distanceEstimator: DistanceEstimator
    private lateinit var decisionEngine: DecisionEngine
    private lateinit var objectDetectorManager: ObjectDetectorManager
    private lateinit var locationManager: LocationManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var navigationRepository: NavigationRepository
    private lateinit var navigationManager: NavigationManager

    private val detectionsState = mutableStateOf<List<Detection>>(emptyList())
    private val imageWidthState = mutableIntStateOf(0)
    private val imageHeightState = mutableIntStateOf(0)

    private val currentRouteState = mutableStateOf<NavigationRoute?>(null)
    private val isNavigatingState = mutableStateOf(false)
    private val isSearchingRouteState = mutableStateOf(false)
    private val statusMessageState = mutableStateOf<String?>(null)

    // Calibration and debug overlay state
    private val showDebugOverlayState = mutableStateOf(false)
    private val lastEstimatedObstacleState = mutableStateOf<Pair<String, Float>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Requirement 2: Keep the screen awake and camera active while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        voiceManager = VoiceManager(this)
        hapticManager = HapticManager(this)
        cameraManager = CameraManager(this)
        distanceEstimator = DistanceEstimator(this)
        decisionEngine = DecisionEngine(voiceManager, hapticManager)
        locationManager = LocationManager(this)
        navigationRepository = NavigationRepository()

        navigationManager = NavigationManager(
            navigationRepository = navigationRepository,
            voiceManager = voiceManager,
            hapticManager = hapticManager,
            coroutineScope = lifecycleScope,
            onNavigationStateChanged = { isNav ->
                runOnUiThread {
                    isNavigatingState.value = isNav
                }
            },
            onRouteUpdated = { newRoute ->
                runOnUiThread {
                    currentRouteState.value = newRoute
                }
            },
            onStatusMessage = { status ->
                runOnUiThread {
                    statusMessageState.value = status
                }
            }
        )

        voiceCommandManager = VoiceCommandManager(
            context = this,
            voiceManager = voiceManager
        ) { command ->
            handleVoiceCommand(command)
        }

        // Initialize ObjectDetectorManager with DistanceEstimator
        objectDetectorManager = ObjectDetectorManager(
            context = this,
            distanceEstimator = distanceEstimator
        ) { detectedList, width, height ->
            runOnUiThread {
                detectionsState.value = detectedList
                imageWidthState.intValue = width
                imageHeightState.intValue = height
                if (detectedList.isNotEmpty()) {
                    val closest = detectedList.minByOrNull { it.distanceMeters } ?: detectedList.first()
                    lastEstimatedObstacleState.value = Pair(closest.label, closest.distanceMeters)
                }
            }

            // Feed detections to DecisionEngine with image dimensions for sanity checks
            decisionEngine.processDetections(detectedList, width, height)
        }

        // Feed ImageAnalysis frames from CameraManager to ObjectDetectorManager
        cameraManager.frameAnalyzer = { imageProxy ->
            objectDetectorManager.detect(imageProxy)
        }

        // Initial welcome speech
        voiceManager.speak("NaviSense is ready. Double tap anywhere to set destination.")

        setContent {
            NaviSenseTheme {
                var permissionsGranted by remember {
                    mutableStateOf(hasAllRequiredPermissions())
                }

                // Requirement 1: Handle denied permissions gracefully with spoken guidance
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissionsMap ->
                    val cameraGranted = permissionsMap[Manifest.permission.CAMERA] ?: false
                    val micGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] ?: false
                    val fineGranted = permissionsMap[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                    val coarseGranted = permissionsMap[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
                    val locGranted = fineGranted || coarseGranted

                    val allGranted = cameraGranted && micGranted && locGranted
                    permissionsGranted = allGranted

                    if (allGranted) {
                        Log.i(TAG, "All runtime permissions granted.")
                        voiceManager.speak("All permissions granted. NaviSense is ready.", interrupt = true)
                        locationManager.startLocationUpdates { location ->
                            navigationManager.onLocationChanged(location)
                        }
                    } else {
                        Log.w(TAG, "Some runtime permissions were denied: $permissionsMap")
                        val deniedDetails = mutableListOf<String>()
                        if (!cameraGranted) deniedDetails.add("Camera permission is required for obstacle detection.")
                        if (!micGranted) deniedDetails.add("Microphone permission is required for voice commands.")
                        if (!locGranted) deniedDetails.add("Location permission is required for walking navigation.")

                        val spokenError = "Permissions denied. ${deniedDetails.joinToString(" ")} Tap Grant Permissions on screen to try again."
                        voiceManager.speak(spokenError, interrupt = true)
                    }
                }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) {
                        voiceManager.speak(
                            "NaviSense needs camera for obstacle detection, microphone for voice destinations, and location for walking navigation. Please grant permissions.",
                            interrupt = true,
                            onDone = {
                                runOnUiThread {
                                    permissionLauncher.launch(REQUIRED_PERMISSIONS)
                                }
                            }
                        )
                    } else {
                        locationManager.startLocationUpdates { location ->
                            navigationManager.onLocationChanged(location)
                        }
                    }
                }

                val hasCamera = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                // Tap counting state for double tap vs triple tap gesture discrimination
                var tapCount by remember { mutableIntStateOf(0) }
                var lastTapTimestamp by remember { mutableLongStateOf(0L) }
                val gestureScope = rememberCoroutineScope()

                // Requirement 4 & 5: Root full-screen container with TalkBack description and gesture listener
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .semantics {
                            contentDescription = "NaviSense main screen. Double tap anywhere to speak a destination. Triple tap anywhere to toggle camera calibration debug info."
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTimestamp > 450L) {
                                        tapCount = 1
                                    } else {
                                        tapCount++
                                    }
                                    lastTapTimestamp = now

                                    if (tapCount == 3) {
                                        // Requirement 5: Triple tap toggles calibration debug overlay
                                        tapCount = 0
                                        val newState = !showDebugOverlayState.value
                                        showDebugOverlayState.value = newState
                                        val msg = if (newState) "Calibration debug info enabled" else "Calibration debug info disabled"
                                        voiceManager.speak(msg, interrupt = true)
                                    } else if (tapCount == 2) {
                                        // Wait briefly for potential 3rd tap before executing double-tap action
                                        gestureScope.launch {
                                            delay(350L)
                                            if (tapCount == 2) {
                                                tapCount = 0
                                                Log.i(TAG, "Full-screen double tap confirmed -> Prompting voice command")
                                                val isNav = isNavigatingState.value
                                                val hasRoute = currentRouteState.value != null
                                                val promptText = if (isNav) {
                                                    "Listening"
                                                } else if (hasRoute) {
                                                    "Say start navigation or speak a destination."
                                                } else {
                                                    "Where do you want to go?"
                                                }
                                                voiceCommandManager.prompt(promptText)
                                            }
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCamera) {
                        CameraPreview(
                            cameraManager = cameraManager,
                            lifecycleOwner = this@MainActivity,
                            modifier = Modifier.fillMaxSize()
                        )
                        val detections by detectionsState
                        val imageWidth by imageWidthState
                        val imageHeight by imageHeightState

                        DetectionOverlay(
                            detections = detections,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Top Destination / Navigation Status Pill with TalkBack accessibility
                        val currentRoute by currentRouteState
                        val isNavigating by isNavigatingState
                        val isSearching by isSearchingRouteState
                        val statusMsg by statusMessageState

                        val bannerAccessibility = when {
                            isSearching -> "Searching walking route, please wait"
                            currentRoute != null -> {
                                val dist = Math.round(currentRoute!!.totalDistanceMeters).toInt()
                                val timeMin = Math.max(1, Math.round(currentRoute!!.totalDurationSeconds / 60.0).toInt())
                                val status = if (isNavigating) "Navigating to" else "Destination set to"
                                "$status ${currentRoute!!.destinationName}, distance $dist meters, approximately $timeMin minutes"
                            }
                            statusMsg != null -> statusMsg!!
                            else -> "NaviSense idle"
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                                .semantics {
                                    contentDescription = bannerAccessibility
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isSearching) {
                                Text(
                                    text = "🔍 Searching route...",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xCC000000), shape = RoundedCornerShape(16.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            } else if (currentRoute != null) {
                                val dist = Math.round(currentRoute!!.totalDistanceMeters).toInt()
                                val timeMin = Math.max(1, Math.round(currentRoute!!.totalDurationSeconds / 60.0).toInt())
                                val bannerColor = if (isNavigating) Color(0xFF00796B) else Color(0xDD1B5E20)
                                val bannerPrefix = if (isNavigating) "🟢 Navigating: " else "📍 "
                                Text(
                                    text = "$bannerPrefix${currentRoute!!.destinationName} • ${dist}m (${timeMin}m)",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(bannerColor, shape = RoundedCornerShape(16.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            } else if (statusMsg != null) {
                                Text(
                                    text = statusMsg!!,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .background(Color(0xBB000000), shape = RoundedCornerShape(16.dp))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Requirement 5: Small calibration debug overlay (toggled with triple tap)
                        val showDebug by showDebugOverlayState
                        val lastObstacle by lastEstimatedObstacleState
                        val frameHeight = imageHeightState.intValue
                        val currentFocalPx = distanceEstimator.getFocalLengthPx(if (frameHeight > 0) frameHeight else 1080)
                        val lastDistStr = if (lastObstacle != null) {
                            "${String.format(Locale.US, "%.1fm", lastObstacle!!.second)} (${lastObstacle!!.first})"
                        } else {
                            "None"
                        }
                        val debugText = "Focal: ${currentFocalPx.toInt()}px | Last: $lastDistStr"

                        if (showDebug) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(top = 48.dp, start = 16.dp)
                                    .background(Color(0xDD000000), shape = RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .semantics {
                                        contentDescription = "Calibration debug info: Focal length is ${currentFocalPx.toInt()} pixels. Last estimated obstacle distance is $lastDistStr"
                                    }
                            ) {
                                Text(
                                    text = debugText,
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Settings toggles for Voice and Vibration separately with TalkBack descriptions
                        var isVoiceEnabled by remember { mutableStateOf(decisionEngine.isVoiceEnabled) }
                        var isHapticsEnabled by remember { mutableStateOf(decisionEngine.isHapticsEnabled) }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp)
                                .background(Color(0xDD1E1E1E), shape = RoundedCornerShape(24.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Requirement 4: Voice Toggle Button with TalkBack accessibility
                            Button(
                                onClick = {
                                    val newState = !isVoiceEnabled
                                    isVoiceEnabled = newState
                                    decisionEngine.isVoiceEnabled = newState
                                    Log.i(TAG, "User toggled Voice: $newState")
                                    if (newState) {
                                        decisionEngine.clear()
                                        voiceManager.speak("Voice alerts enabled", interrupt = true)
                                    } else {
                                        voiceManager.stop()
                                        voiceManager.speak("Voice alerts disabled", interrupt = true)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isVoiceEnabled) Color(0xFF2E7D32) else Color(0xFF424242)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.semantics {
                                    contentDescription = if (isVoiceEnabled) {
                                        "Voice alerts button, currently enabled. Tap to disable voice alerts."
                                    } else {
                                        "Voice alerts button, currently disabled. Tap to enable voice alerts."
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isVoiceEnabled) "Voice: ON" else "Voice: OFF",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }

                            // Requirement 4: Vibration Toggle Button with TalkBack accessibility
                            Button(
                                onClick = {
                                    val newState = !isHapticsEnabled
                                    isHapticsEnabled = newState
                                    decisionEngine.isHapticsEnabled = newState
                                    hapticManager.isEnabled = newState
                                    Log.i(TAG, "User toggled Haptics: $newState")
                                    if (newState) {
                                        hapticManager.vibrateConfirmation()
                                        if (isVoiceEnabled) {
                                            voiceManager.speak("Haptics enabled", interrupt = true)
                                        }
                                    } else {
                                        hapticManager.cancel()
                                        if (isVoiceEnabled) {
                                            voiceManager.speak("Haptics disabled", interrupt = true)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isHapticsEnabled) Color(0xFF1976D2) else Color(0xFF424242)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.semantics {
                                    contentDescription = if (isHapticsEnabled) {
                                        "Vibration alerts button, currently enabled. Tap to disable vibration alerts."
                                    } else {
                                        "Vibration alerts button, currently disabled. Tap to enable vibration alerts."
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isHapticsEnabled) "Haptics: ON" else "Haptics: OFF",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    } else {
                        // Permissions Fallback Screen with TalkBack descriptions
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .semantics {
                                    contentDescription = "Permissions required. Camera, microphone, and location permissions must be granted to run NaviSense."
                                },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Camera, Audio, and Location permissions are required for NaviSense.",
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(REQUIRED_PERMISSIONS)
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Grant permissions button. Double tap to request camera, microphone, and location permissions."
                                }
                            ) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleVoiceCommand(spokenText: String) {
        val trimmed = spokenText.trim()
        Log.i(TAG, "Received voice command: \"$trimmed\"")

        // Handle turn-by-turn navigation commands ("start navigation", "stop navigation", "repeat", "how far")
        if (navigationManager.handleVoiceCommand(trimmed)) {
            Log.i(TAG, "Voice command handled by NavigationManager: \"$trimmed\"")
            return
        }

        // Requirement 3: Check network connectivity before initiating online route calculation
        if (!NetworkUtils.isNetworkAvailable(this@MainActivity)) {
            isSearchingRouteState.value = false
            val offlineMsg = "No internet connection. Walking navigation requires internet, but camera obstacle detection is active offline."
            statusMessageState.value = "Offline: Obstacle detection active"
            voiceManager.speak(offlineMsg, interrupt = true)
            return
        }

        // Process as destination query
        isSearchingRouteState.value = true
        statusMessageState.value = "Searching route to $trimmed..."
        voiceManager.speak("Searching route to $trimmed...", interrupt = true)

        locationManager.getLastLocation { loc ->
            if (loc == null) {
                isSearchingRouteState.value = false
                statusMessageState.value = "Location unavailable. Please wait."
                voiceManager.speak(
                    "Current location is not available yet. Please wait a moment and double tap to try again.",
                    interrupt = true
                )
                return@getLastLocation
            }

            lifecycleScope.launch {
                try {
                    val result = navigationRepository.calculateRouteTo(trimmed, loc)
                    isSearchingRouteState.value = false

                    result.onSuccess { route ->
                        currentRouteState.value = route
                        navigationManager.setRoute(route)
                        val distance = Math.round(route.totalDistanceMeters).toInt()
                        val durationMinutes = Math.max(1, Math.round(route.totalDurationSeconds / 60.0).toInt())
                        val announcement = "Destination found: ${route.destinationName}, about $distance meters, $durationMinutes minutes. Say start navigation to begin."

                        statusMessageState.value = "Found: ${route.destinationName} (${distance}m, ${durationMinutes}m)"
                        Log.i(TAG, announcement)

                        Log.i(TAG, "=== Parsed Walking Route Steps (${route.steps.size} steps) ===")
                        for ((index, step) in route.steps.withIndex()) {
                            Log.i(
                                TAG,
                                "Step ${index + 1}: ${step.instruction} | ${String.format(Locale.US, "%.0f", step.distanceMeters)}m (${String.format(Locale.US, "%.0f", step.durationSeconds)}s) | way_points: ${step.wayPoints} | street: ${step.streetName}"
                            )
                        }

                        voiceManager.speak(announcement, interrupt = true)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to get walking route for: \"$trimmed\"", error)
                        val errorSpeech = when (error) {
                            is NoSuchElementException -> "Destination \"$trimmed\" could not be found nearby. Please try again."
                            is IOException -> "No internet connection. Walking navigation requires internet, but camera obstacle detection is active offline."
                            else -> "Could not calculate walking route to $trimmed. Please try again."
                        }
                        statusMessageState.value = errorSpeech
                        voiceManager.speak(errorSpeech, interrupt = true)
                    }
                } catch (e: Exception) {
                    isSearchingRouteState.value = false
                    Log.e(TAG, "Unexpected error in route fetching", e)
                    val errorSpeech = "Error calculating route, please try again."
                    statusMessageState.value = errorSpeech
                    voiceManager.speak(errorSpeech, interrupt = true)
                }
            }
        }
    }

    // Requirement 6: Make sure resources are released on pause/destroy and restored on resume
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "MainActivity onResume: resuming camera analysis and sensors.")
        cameraManager.resume()
        if (hasAllRequiredPermissions()) {
            locationManager.startLocationUpdates { location ->
                navigationManager.onLocationChanged(location)
            }
        }
        decisionEngine.clear()
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "MainActivity onPause: releasing camera analysis, haptics, recognizer, and location.")
        cameraManager.pause()
        hapticManager.cancel()
        hapticManager.clearContinuousCenterObstacle()
        decisionEngine.clear()
        voiceCommandManager.stopListening()
        voiceManager.stop()
        locationManager.stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MainActivity onDestroy: releasing all resources.")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navigationManager.stopNavigation()
        voiceCommandManager.destroy()
        locationManager.stopLocationUpdates()
        voiceManager.shutdown()
        hapticManager.cancel()
        cameraManager.shutdown()
        objectDetectorManager.close()
    }

    companion object {
        private const val TAG = "MainActivity"

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
