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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    private lateinit var settingsRepository: SettingsRepository

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

    // Flag tracking active Assist mode to conserve camera/sensor resources
    private var isAssistActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Requirement 1: Android SplashScreen API with app icon and dark background (~1.5s)
        val splashScreen = installSplashScreen()
        var keepSplashScreenOn = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreenOn }
        lifecycleScope.launch {
            delay(1500L)
            keepSplashScreenOn = false
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen awake while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize core engines and repositories
        settingsRepository = SettingsRepository(this)
        voiceManager = VoiceManager(this)
        hapticManager = HapticManager(this)
        cameraManager = CameraManager(this)
        distanceEstimator = DistanceEstimator(this)
        decisionEngine = DecisionEngine(voiceManager, hapticManager)
        locationManager = LocationManager(this)
        navigationRepository = NavigationRepository()

        // Apply saved settings
        applySettings(settingsRepository.getSettings())

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
            if (!isAssistActive) return@ObjectDetectorManager

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

        // Feed ImageAnalysis frames from CameraManager to ObjectDetectorManager only while Assist is active
        cameraManager.frameAnalyzer = { imageProxy ->
            if (isAssistActive) {
                objectDetectorManager.detect(imageProxy)
            }
        }

        setContent {
            NaviSenseTheme {
                val navController = rememberNavController()
                var appSettings by remember { mutableStateOf(settingsRepository.getSettings()) }
                var permissionsGranted by remember { mutableStateOf(hasAllRequiredPermissions()) }

                // Requirement 7: Permission request flow on Assist screen
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
                        startAssistServices()
                    } else {
                        Log.w(TAG, "Some runtime permissions were denied: $permissionsMap")
                        val deniedDetails = mutableListOf<String>()
                        if (!cameraGranted) deniedDetails.add("Camera permission is required for obstacle detection.")
                        if (!micGranted) deniedDetails.add("Microphone permission is required for voice commands.")
                        if (!locGranted) deniedDetails.add("Location permission is required for walking navigation.")

                        val spokenError = "Permissions denied. ${deniedDetails.joinToString(" ")} Tap Grant Permissions to try again."
                        voiceManager.speak(spokenError, interrupt = true)
                    }
                }

                // Requirement 8: Navigation Compose NavHost
                NavHost(
                    navController = navController,
                    startDestination = NavDestinations.HOME
                ) {
                    // Screen 1: Home Screen
                    composable(NavDestinations.HOME) {
                        HomeScreen(
                            voiceManager = voiceManager,
                            hasShownDisclaimer = appSettings.hasShownDisclaimer,
                            onDisclaimerAcknowledged = {
                                appSettings = settingsRepository.setDisclaimerShown(true)
                            },
                            onStartAssist = {
                                navController.navigate(NavDestinations.ASSIST)
                            },
                            onOpenSettings = {
                                navController.navigate(NavDestinations.SETTINGS)
                            },
                            onOpenHowToUse = {
                                navController.navigate(NavDestinations.HOW_TO_USE)
                            },
                            onOpenAboutSafety = {
                                navController.navigate(NavDestinations.ABOUT_SAFETY)
                            }
                        )
                    }

                    // Screen 2: Assist Screen
                    composable(NavDestinations.ASSIST) {
                        LaunchedEffect(Unit) {
                            if (!hasAllRequiredPermissions()) {
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
                                startAssistServices()
                            }
                        }

                        val detections by detectionsState
                        val imageWidth by imageWidthState
                        val imageHeight by imageHeightState
                        val currentRoute by currentRouteState
                        val isNavigating by isNavigatingState
                        val isSearching by isSearchingRouteState
                        val statusMsg by statusMessageState
                        val showDebug by showDebugOverlayState
                        val lastObstacle by lastEstimatedObstacleState

                        AssistScreen(
                            cameraManager = cameraManager,
                            distanceEstimator = distanceEstimator,
                            decisionEngine = decisionEngine,
                            hapticManager = hapticManager,
                            voiceManager = voiceManager,
                            voiceCommandManager = voiceCommandManager,
                            lifecycleOwner = this@MainActivity,
                            hasCameraPermission = permissionsGranted,
                            onRequestPermissions = {
                                permissionLauncher.launch(REQUIRED_PERMISSIONS)
                            },
                            detections = detections,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            currentRoute = currentRoute,
                            isNavigating = isNavigating,
                            isSearching = isSearching,
                            statusMsg = statusMsg,
                            showDebugOverlay = showDebug,
                            lastObstacle = lastObstacle,
                            onToggleDebugOverlay = {
                                val newState = !showDebugOverlayState.value
                                showDebugOverlayState.value = newState
                                val msg = if (newState) "Calibration debug info enabled" else "Calibration debug info disabled"
                                voiceManager.speak(msg, interrupt = true)
                            },
                            onStopAssist = {
                                stopAssistServices()
                                voiceManager.speak("Stopped. Back to home", interrupt = true)
                                navController.popBackStack(NavDestinations.HOME, inclusive = false)
                            }
                        )
                    }

                    // Screen 3: Settings Screen
                    composable(NavDestinations.SETTINGS) {
                        SettingsScreen(
                            settings = appSettings,
                            onUpdateVoice = { enabled ->
                                appSettings = settingsRepository.setVoiceEnabled(enabled)
                                applySettings(appSettings)
                                val msg = if (enabled) "Voice alerts enabled" else "Voice alerts disabled"
                                voiceManager.speak(msg, interrupt = true)
                            },
                            onUpdateHaptics = { enabled ->
                                appSettings = settingsRepository.setHapticsEnabled(enabled)
                                applySettings(appSettings)
                                if (enabled) {
                                    hapticManager.vibrateConfirmation()
                                    voiceManager.speak("Haptics enabled", interrupt = true)
                                } else {
                                    hapticManager.cancel()
                                    voiceManager.speak("Haptics disabled", interrupt = true)
                                }
                            },
                            onUpdateVibrationStrength = { strength ->
                                appSettings = settingsRepository.setVibrationStrength(strength)
                                applySettings(appSettings)
                                hapticManager.vibrateConfirmation()
                                voiceManager.speak("Vibration strength set to ${strength.displayName}", interrupt = true)
                            },
                            onUpdateSpeechRate = { rate ->
                                appSettings = settingsRepository.setSpeechRate(rate)
                                applySettings(appSettings)
                                voiceManager.speak("Speech rate set to ${rate.displayName}", interrupt = true)
                            },
                            onUpdateMaxDistance = { dist ->
                                appSettings = settingsRepository.setMaxAlertDistance(dist)
                                applySettings(appSettings)
                                voiceManager.speak("Maximum alert distance set to ${dist.toInt()} meters", interrupt = true)
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    // Screen 4: How to Use Screen
                    composable(NavDestinations.HOW_TO_USE) {
                        HowToUseScreen(
                            voiceManager = voiceManager,
                            hapticManager = hapticManager,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    // Screen 5: About and Safety Screen
                    composable(NavDestinations.ABOUT_SAFETY) {
                        AboutSafetyScreen(
                            voiceManager = voiceManager,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun applySettings(settings: AppSettings) {
        voiceManager.isEnabled = settings.isVoiceEnabled
        voiceManager.setSpeechRate(settings.speechRate.rate)
        hapticManager.isEnabled = settings.isHapticsEnabled
        hapticManager.vibrationStrength = settings.vibrationStrength
        decisionEngine.isVoiceEnabled = settings.isVoiceEnabled
        decisionEngine.isHapticsEnabled = settings.isHapticsEnabled
        decisionEngine.maxAlertDistanceMeters = settings.maxAlertDistanceMeters
    }

    private fun startAssistServices() {
        if (isAssistActive) return
        isAssistActive = true
        Log.i(TAG, "Starting Assist mode services (Camera, Location, Sensors).")
        cameraManager.resume()
        if (hasAllRequiredPermissions()) {
            locationManager.startLocationUpdates { location ->
                navigationManager.onLocationChanged(location)
            }
        }
    }

    private fun stopAssistServices() {
        if (!isAssistActive) return
        Log.i(TAG, "Stopping Assist mode services and releasing hardware resources.")
        isAssistActive = false
        cameraManager.stopCamera()
        cameraManager.pause()
        locationManager.stopLocationUpdates()
        hapticManager.cancel()
        hapticManager.clearContinuousCenterObstacle()
        decisionEngine.clear()
        navigationManager.stopNavigation()
        voiceCommandManager.stopListening()

        // Clear UI states
        detectionsState.value = emptyList()
        currentRouteState.value = null
        statusMessageState.value = null
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

        // Check network connectivity before initiating online route calculation
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

    override fun onResume() {
        super.onResume()
        if (isAssistActive) {
            Log.i(TAG, "MainActivity onResume: resuming Assist mode.")
            cameraManager.resume()
            if (hasAllRequiredPermissions()) {
                locationManager.startLocationUpdates { location ->
                    navigationManager.onLocationChanged(location)
                }
            }
            decisionEngine.clear()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "MainActivity onPause: releasing hardware resources.")
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
        Log.i(TAG, "MainActivity onDestroy: releasing all application resources.")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopAssistServices()
        voiceCommandManager.destroy()
        voiceManager.shutdown()
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
