package com.example.navisense

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object NavDestinations {
    const val HOME = "home"
    const val ASSIST = "assist"
    const val SETTINGS = "settings"
    const val HOW_TO_USE = "how_to_use"
    const val ABOUT_SAFETY = "about_safety"
}

/**
 * Requirement 2: Home Screen (dark high-contrast theme).
 * App name "NaviSense", tagline "Your eyes on the move", large buttons (minimum 64dp height):
 * - Start NaviSense
 * - Settings
 * - How to use
 * - About and safety
 * Speaks "Welcome to NaviSense. Double tap anywhere to start." on open.
 * Double tap anywhere on the screen starts assist mode.
 */
@Composable
fun HomeScreen(
    voiceManager: VoiceNotifier,
    hasShownDisclaimer: Boolean,
    onDisclaimerAcknowledged: () -> Unit,
    onStartAssist: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHowToUse: () -> Unit,
    onOpenAboutSafety: () -> Unit
) {
    var showFirstLaunchDisclaimer by remember { mutableStateOf(!hasShownDisclaimer) }

    LaunchedEffect(Unit) {
        if (!hasShownDisclaimer) {
            voiceManager.speak(
                "Important Safety Notice: NaviSense is an assistive aid and does not replace a white cane or a guide. Double tap I Understand to continue.",
                interrupt = true
            )
        } else {
            voiceManager.speak("Welcome to NaviSense. Double tap anywhere to start.", interrupt = false)
        }
    }

    if (showFirstLaunchDisclaimer) {
        AlertDialog(
            onDismissRequest = { /* Require explicit acknowledgment on first launch */ },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color(0xFFFFD600),
            textContentColor = Color.White,
            title = {
                Text(
                    text = "⚠️ Safety Disclaimer",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Text(
                    text = "NaviSense is an assistive aid and does not replace a white cane or a guide. Always pay attention to your surroundings.",
                    fontSize = 18.sp,
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFirstLaunchDisclaimer = false
                        onDisclaimerAcknowledged()
                        voiceManager.speak("Disclaimer acknowledged. Welcome to NaviSense. Double tap anywhere to start.", interrupt = true)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "I Understand button. Double tap to acknowledge safety disclaimer and enter NaviSense."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "I Understand",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // Full screen container with double-tap gesture listener
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .semantics {
                contentDescription = "NaviSense Home screen. Double tap anywhere to start assist mode. Or select from the buttons on screen."
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!showFirstLaunchDisclaimer) {
                            onStartAssist()
                        }
                    }
                )
            }
            .padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
            ) {
                Text(
                    text = "NaviSense",
                    color = Color(0xFF00E5FF),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Your eyes on the move",
                    color = Color(0xFFE0E0E0),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // Main Action Buttons (Minimum 64dp height)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Primary CTA: Start NaviSense
                Button(
                    onClick = onStartAssist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .semantics {
                            contentDescription = "Start NaviSense button. Double tap to activate camera obstacle detection and navigation assistance."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "▶ Start NaviSense",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Settings
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "Settings button. Double tap to adjust voice, vibration, speech rate, and alert distance."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E1E),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "⚙ Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // How to use
                Button(
                    onClick = onOpenHowToUse,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "How to use button. Double tap to learn gestures and voice commands."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E1E),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "📖 How to use",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // About and safety
                Button(
                    onClick = onOpenAboutSafety,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "About and safety button. Double tap to read the assistive aid disclaimer and app info."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E1E),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "ℹ About and safety",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Double tap hint at bottom
            Text(
                text = "Tip: Double tap anywhere on screen to start assist",
                color = Color(0xFFB0B0B0),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * Requirement 3: Assist Screen.
 * The existing camera preview, overlay, detection, voice navigation and haptics.
 * The camera, detector, location, and vibration start ONLY when this screen opens
 * and are fully stopped and released when the user leaves it.
 * Has a clear "Stop" button and BackHandler returning to Home ("Stopped. Back to home").
 */
@Composable
fun AssistScreen(
    cameraManager: CameraManager,
    distanceEstimator: DistanceEstimator,
    decisionEngine: DecisionEngine,
    hapticManager: HapticNotifier,
    voiceManager: VoiceNotifier,
    voiceCommandManager: VoiceCommandManager,
    lifecycleOwner: LifecycleOwner,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    currentRoute: NavigationRoute?,
    isNavigating: Boolean,
    isSearching: Boolean,
    statusMsg: String?,
    showDebugOverlay: Boolean,
    lastObstacle: Pair<String, Float>?,
    onToggleDebugOverlay: () -> Unit,
    onStopAssist: () -> Unit
) {
    // Requirement 3: Handle system back gesture
    BackHandler {
        onStopAssist()
    }

    // Tap counting state for double tap vs triple tap gesture discrimination
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }
    val gestureScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                contentDescription = "NaviSense active assist screen. Double tap anywhere to speak a destination. Triple tap anywhere to toggle camera calibration debug info. Use the Stop button at the bottom to return to Home."
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
                            tapCount = 0
                            onToggleDebugOverlay()
                        } else if (tapCount == 2) {
                            gestureScope.launch {
                                delay(350L)
                                if (tapCount == 2) {
                                    tapCount = 0
                                    val promptText = if (isNavigating) {
                                        "Listening"
                                    } else if (currentRoute != null) {
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
        if (hasCameraPermission) {
            CameraPreview(
                cameraManager = cameraManager,
                lifecycleOwner = lifecycleOwner,
                modifier = Modifier.fillMaxSize()
            )

            DetectionOverlay(
                detections = detections,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                modifier = Modifier.fillMaxSize()
            )

            // Top Status Pill
            val bannerAccessibility = when {
                isSearching -> "Searching walking route, please wait"
                currentRoute != null -> {
                    val dist = Math.round(currentRoute.totalDistanceMeters).toInt()
                    val timeMin = Math.max(1, Math.round(currentRoute.totalDurationSeconds / 60.0).toInt())
                    val status = if (isNavigating) "Navigating to" else "Destination set to"
                    "$status ${currentRoute.destinationName}, distance $dist meters, approximately $timeMin minutes"
                }
                statusMsg != null -> statusMsg
                else -> "NaviSense assist active"
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp)
                    .semantics {
                        contentDescription = bannerAccessibility
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSearching) {
                    Text(
                        text = "🔍 Searching route...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xCC000000), shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else if (currentRoute != null) {
                    val dist = Math.round(currentRoute.totalDistanceMeters).toInt()
                    val timeMin = Math.max(1, Math.round(currentRoute.totalDurationSeconds / 60.0).toInt())
                    val bannerColor = if (isNavigating) Color(0xFF00796B) else Color(0xDD1B5E20)
                    val bannerPrefix = if (isNavigating) "🟢 Navigating: " else "📍 "
                    Text(
                        text = "$bannerPrefix${currentRoute.destinationName} • ${dist}m (${timeMin}m)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(bannerColor, shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else if (statusMsg != null) {
                    Text(
                        text = statusMsg,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .background(Color(0xBB000000), shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            // Triple tap debug calibration info
            if (showDebugOverlay) {
                val currentFocalPx = distanceEstimator.getFocalLengthPx(if (imageHeight > 0) imageHeight else 1080)
                val lastDistStr = if (lastObstacle != null) {
                    "${String.format(Locale.US, "%.1fm", lastObstacle.second)} (${lastObstacle.first})"
                } else {
                    "None"
                }
                val debugText = "Focal: ${currentFocalPx.toInt()}px | Last: $lastDistStr"

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 44.dp, start = 16.dp)
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

            // Bottom Stop button (minimum 64dp height)
            Button(
                onClick = onStopAssist,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 32.dp)
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Stop NaviSense button. Double tap to stop camera, navigation, and haptics, and return to Home screen."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "⏹ Stop NaviSense",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Permission request screen on first entry to Assist
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .semantics {
                        contentDescription = "Permissions required. Camera, microphone, and location permissions must be granted to run NaviSense assist mode."
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Permissions Required",
                    color = Color(0xFFFFD600),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "NaviSense needs camera access for obstacle detection, microphone for destination voice commands, and location for walking navigation.",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "Grant permissions button. Double tap to request camera, microphone, and location permissions."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Grant Permissions",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onStopAssist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "Return to Home button."
                        },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Back to Home",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

/**
 * Requirement 4: Settings Screen.
 * Voice on/off, Vibration on/off, speech rate (slow/normal/fast),
 * maximum alert distance (3 m / 5 m). Persistently saved and applied.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateVoice: (Boolean) -> Unit,
    onUpdateHaptics: (Boolean) -> Unit,
    onUpdateVibrationStrength: (VibrationStrength) -> Unit,
    onUpdateSpeechRate: (SpeechRate) -> Unit,
    onUpdateMaxDistance: (Float) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Settings",
                    color = Color(0xFF00E5FF),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 1. Voice Alerts Toggle
            SettingCard(
                title = "Voice Alerts",
                subtitle = if (settings.isVoiceEnabled) "Spoken obstacle and navigation guidance enabled" else "Voice guidance is muted"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onUpdateVoice(true) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "Enable voice alerts. Currently ${if (settings.isVoiceEnabled) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.isVoiceEnabled) Color(0xFF2E7D32) else Color(0xFF333333),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Voice ON", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onUpdateVoice(false) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "Disable voice alerts. Currently ${if (!settings.isVoiceEnabled) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!settings.isVoiceEnabled) Color(0xFFC62828) else Color(0xFF333333),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Voice OFF", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Vibration Alerts Toggle
            SettingCard(
                title = "Vibration Alerts",
                subtitle = if (settings.isHapticsEnabled) "Parking sensor obstacle ticks and navigation cues enabled" else "Haptics disabled"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onUpdateHaptics(true) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "Enable vibration alerts. Currently ${if (settings.isHapticsEnabled) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.isHapticsEnabled) Color(0xFF1565C0) else Color(0xFF333333),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Haptics ON", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onUpdateHaptics(false) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "Disable vibration alerts. Currently ${if (!settings.isHapticsEnabled) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!settings.isHapticsEnabled) Color(0xFFC62828) else Color(0xFF333333),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Haptics OFF", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Vibration Strength (Low, Medium, High)
            SettingCard(
                title = "Vibration Strength",
                subtitle = "Current strength: ${settings.vibrationStrength.displayName}"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VibrationStrength.values().forEach { strength ->
                        val isSelected = settings.vibrationStrength == strength
                        Button(
                            onClick = { onUpdateVibrationStrength(strength) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp)
                                .semantics {
                                    contentDescription = "${strength.displayName} vibration strength. Currently ${if (isSelected) "selected" else "not selected"}."
                                },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF333333),
                                contentColor = if (isSelected) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(strength.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Speech Rate Selector (Slow / Normal / Fast)
            SettingCard(
                title = "Speech Rate",
                subtitle = "Current rate: ${settings.speechRate.displayName} (${settings.speechRate.rate}x)"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeechRate.values().forEach { rate ->
                        val isSelected = settings.speechRate == rate
                        Button(
                            onClick = { onUpdateSpeechRate(rate) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp)
                                .semantics {
                                    contentDescription = "${rate.displayName} speech rate, ${rate.rate}x speed. Currently ${if (isSelected) "selected" else "not selected"}."
                                },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF333333),
                                contentColor = if (isSelected) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(rate.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Maximum Alert Distance (3 m / 5 m)
            SettingCard(
                title = "Maximum Alert Distance",
                subtitle = "Detect and alert obstacles up to: ${settings.maxAlertDistanceMeters.toInt()} meters"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val is3m = settings.maxAlertDistanceMeters == 3.0f
                    Button(
                        onClick = { onUpdateMaxDistance(3.0f) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "3 meters alert range. Suitable for crowded indoor walking. Currently ${if (is3m) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (is3m) Color(0xFFFFD600) else Color(0xFF333333),
                            contentColor = if (is3m) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("3 Meters", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    val is5m = settings.maxAlertDistanceMeters == 5.0f
                    Button(
                        onClick = { onUpdateMaxDistance(5.0f) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .semantics {
                                contentDescription = "5 meters alert range. Standard outdoor walking range. Currently ${if (is5m) "selected" else "not selected"}."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (is5m) Color(0xFFFFD600) else Color(0xFF333333),
                            contentColor = if (is5m) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("5 Meters", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Back Button
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Back to Home screen button."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E2E2E),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Color(0xFFB0B0B0),
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * Requirement 5: How to Use Screen.
 * Short text and spoken instructions:
 * - double tap to speak a destination
 * - say "start navigation", "stop navigation", "repeat", "how far"
 * - triple tap for debug
 */
@Composable
fun HowToUseScreen(
    voiceManager: VoiceNotifier,
    hapticManager: HapticNotifier? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val instructionsSummary = "How to use NaviSense: First, double tap anywhere during assist to speak your destination. Second, use voice commands such as start navigation, stop navigation, repeat, and how far. Third, feel the parking sensor ticks for center obstacles within 3 meters and distinct long pulses for turns. Fourth, triple tap anywhere to toggle camera calibration debug info. Fifth, tap the Stop button to end assist and return home."

    LaunchedEffect(Unit) {
        voiceManager.speak(instructionsSummary, interrupt = false)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "How to Use",
                color = Color(0xFF00E5FF),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Read Aloud Button
            Button(
                onClick = { voiceManager.speak(instructionsSummary, interrupt = true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Read instructions aloud button. Double tap to hear spoken instructions."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("🔊 Read Aloud", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(20.dp))

            InstructionItem(
                number = "1",
                title = "Double Tap to Speak Destination",
                description = "While Assist is active, double tap anywhere on the screen. When prompted, speak your desired destination (e.g. 'Coffee shop' or 'Central Park')."
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "2",
                title = "Turn-by-Turn Voice Commands",
                description = "After a route is found, double tap and speak:\n• 'start navigation' to begin step guidance\n• 'stop navigation' to cancel the route\n• 'repeat' to hear current step again\n• 'how far' to hear remaining distance"
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "3",
                title = "Obstacle Perception & Haptics",
                description = "Point your phone camera forward. Center-zone obstacles within 3 meters produce proximity vibration ticks that quicken as distance decreases (like a parking sensor). Left and right obstacles are announced by voice only."
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "4",
                title = "Triple Tap for Calibration Debug",
                description = "Triple tap anywhere on the Assist screen to toggle camera focal length and estimated distance debug info."
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "5",
                title = "Returning to Home",
                description = "Tap the large Stop button at the bottom of the screen or use the system back gesture to return to Home at any time."
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Requirement 6: "Feel the patterns" section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Feel the Patterns",
                        color = Color(0xFF00E5FF),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Touch any button below to hear its name and feel its vibration pattern once so you can learn them.",
                        color = Color(0xFFD0D0D0),
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val patternButtons = listOf(
                        "Far obstacle" to {
                            voiceManager.speak("Far obstacle", interrupt = true) {
                                hapticManager?.vibrateFarObstacle()
                            }
                        },
                        "Near obstacle" to {
                            voiceManager.speak("Near obstacle", interrupt = true) {
                                hapticManager?.vibrateNearObstacle()
                            }
                        },
                        "Danger" to {
                            voiceManager.speak("Danger", interrupt = true) {
                                hapticManager?.vibrateDangerObstacle()
                            }
                        },
                        "Turn left" to {
                            voiceManager.speak("Turn left", interrupt = true) {
                                hapticManager?.vibrateTurnLeft()
                            }
                        },
                        "Turn right" to {
                            voiceManager.speak("Turn right", interrupt = true) {
                                hapticManager?.vibrateTurnRight()
                            }
                        },
                        "Arrived" to {
                            voiceManager.speak("Arrived", interrupt = true) {
                                hapticManager?.vibrateDestinationReached()
                            }
                        }
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        patternButtons.forEach { (label, action) ->
                            Button(
                                onClick = action,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .semantics {
                                        contentDescription = "$label pattern. Double tap to hear name and feel vibration."
                                    },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2A2A2A),
                                    contentColor = Color.White
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF555555)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Back to Home screen button."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E2E2E),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InstructionItem(number: String, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF00E5FF), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(number, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.padding(start = 10.dp))
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                description,
                color = Color(0xFFD0D0D0),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

/**
 * Requirement 6: About and Safety Screen.
 * Short description and disclaimer:
 * "NaviSense is an assistive aid and does not replace a white cane or a guide."
 */
@Composable
fun AboutSafetyScreen(
    voiceManager: VoiceNotifier,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val safetyNotice = "About and Safety: NaviSense is an assistive aid and does not replace a white cane or a guide. Always exercise caution and use standard mobility techniques."

    LaunchedEffect(Unit) {
        voiceManager.speak(safetyNotice, interrupt = false)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "About & Safety",
                color = Color(0xFF00E5FF),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Prominent Safety Disclaimer Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFFFFD600), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF261D00)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "⚠️ MANDATORY SAFETY DISCLAIMER",
                        color = Color(0xFFFFD600),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "NaviSense is an assistive aid and does not replace a white cane or a guide.",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 26.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Never rely solely on NaviSense for physical safety. Sensor limitations, environmental lighting, and obstacles outside the camera's view require continuous user alertness and tactile cane verification.",
                        color = Color(0xFFFFE082),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // App Description
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "About NaviSense",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "NaviSense is an AI-powered pedestrian navigation assistant designed specifically for blind and visually impaired individuals. It integrates camera-based machine vision object detection with pinhole distance estimation, turn-by-turn walking directions, directional haptics, and hands-free voice commands.",
                        color = Color(0xFFE0E0E0),
                        fontSize = 16.sp,
                        lineHeight = 23.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Version 1.0 (Step 8 Production Flow)",
                        color = Color(0xFF00E5FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Read Disclaimer Button
            Button(
                onClick = { voiceManager.speak(safetyNotice, interrupt = true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Hear disclaimer again button."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD600),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("🔊 Hear Disclaimer Again", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Back to Home screen button."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E2E2E),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
