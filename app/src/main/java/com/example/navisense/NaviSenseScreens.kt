package com.example.navisense

import android.speech.tts.Voice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import com.example.navisense.ui.theme.DarkInk
import com.example.navisense.ui.theme.DeepBerry
import com.example.navisense.ui.theme.GlassBerry
import com.example.navisense.ui.theme.GlassPillBorder
import com.example.navisense.ui.theme.PressedInk
import com.example.navisense.ui.theme.SignalOrange
import com.example.navisense.ui.theme.SoftCardSurface
import com.example.navisense.ui.theme.VanillaCloud
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
 * Home Screen (Light warm premium theme):
 * - Material 3 light theme (Deep Berry #69346C & Vanilla Cloud #FFF8DF)
 * - Brand header with Sora display typography
 * - Large Deep Berry Start button with soft pulse ring animation (>=68dp touch target)
 * - Secondary action cards (20dp rounded corners, SoftCardSurface #F3E8EE, >=64dp touch targets)
 * - Spoken welcome message and double-tap gesture to start assist
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
            containerColor = SoftCardSurface,
            titleContentColor = DarkInk,
            textContentColor = DarkInk,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(SignalOrange, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⚠️",
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Safety Notice",
                        style = MaterialTheme.typography.titleLarge,
                        color = DarkInk,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            },
            text = {
                Text(
                    text = "NaviSense is an assistive aid and does not replace a white cane or a guide. Always pay attention to your surroundings.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DarkInk,
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFirstLaunchDisclaimer = false
                        onDisclaimerAcknowledged()
                        voiceManager.speak(
                            "Disclaimer acknowledged. Welcome to NaviSense. Double tap anywhere to start.",
                            interrupt = true
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "I Understand button. Double tap to acknowledge safety disclaimer and enter NaviSense."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepBerry,
                        contentColor = VanillaCloud
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "I Understand",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = VanillaCloud
                    )
                }
            }
        )
    }

    // Pulse ring animation for primary Start button
    val infiniteTransition = rememberInfiniteTransition(label = "StartPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Full screen container with double-tap gesture listener
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VanillaCloud)
            .semantics {
                contentDescription = "NaviSense Home screen. Double tap anywhere to start assist mode. Or select from the options below."
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
            // Header Section with refined Sora Display Typography
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
            ) {
                Text(
                    text = "NaviSense",
                    style = MaterialTheme.typography.displayLarge,
                    color = DeepBerry,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Your eyes on the move",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DarkInk.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }

            // Main Actions Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Primary CTA: Start NaviSense with soft pulse ring animation
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = pulseAlpha
                            }
                            .background(
                                color = DeepBerry.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(24.dp)
                            )
                    )
                    Button(
                        onClick = onStartAssist,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .semantics {
                                contentDescription = "Start NaviSense button. Double tap to activate camera obstacle detection and navigation assistance."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepBerry,
                            contentColor = VanillaCloud
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "▶ Start NaviSense",
                            style = MaterialTheme.typography.titleLarge,
                            color = VanillaCloud,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Secondary Actions: 20dp soft cards with icons
                HomeActionCard(
                    icon = "⚙",
                    title = "Settings",
                    subtitle = "Voice, vibration, and detection distance",
                    onClick = onOpenSettings
                )

                HomeActionCard(
                    icon = "📖",
                    title = "How to use",
                    subtitle = "Gestures, spoken commands, and haptics",
                    onClick = onOpenHowToUse
                )

                HomeActionCard(
                    icon = "ℹ",
                    title = "About and safety",
                    subtitle = "Assistive aid disclaimer and user guide",
                    onClick = onOpenAboutSafety
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Double tap hint at bottom
            Text(
                text = "Tip: Double tap anywhere on screen to start assist",
                style = MaterialTheme.typography.bodySmall,
                color = DarkInk.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun HomeActionCard(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics {
                contentDescription = "$title. $subtitle. Double tap to open."
            },
        colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(VanillaCloud, RoundedCornerShape(12.dp))
                    .border(1.dp, DeepBerry.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = DarkInk,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkInk.copy(alpha = 0.72f),
                    lineHeight = 18.sp
                )
            }
            Text(
                text = "›",
                color = DeepBerry,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Assist Screen (over live camera):
 * - Glass-style translucent Deep Berry pills with cream text for status, route, and Stop
 * - Detection boxes with cream outlines, berry label pills, and Signal Orange for critical (< 1.5m)
 * - Bottom caption bar showing the last spoken message
 * - Hardware release on exit
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
    lastSpokenCaption: String? = null,
    onToggleDebugOverlay: () -> Unit,
    onStopAssist: () -> Unit
) {
    BackHandler {
        onStopAssist()
    }

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

            // Top Status & Route Glass Pill
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
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                    .semantics {
                        contentDescription = bannerAccessibility
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .background(GlassBerry, shape = RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, GlassPillBorder), RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "🔍 Searching route...",
                            color = VanillaCloud,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (currentRoute != null) {
                    val dist = Math.round(currentRoute.totalDistanceMeters).toInt()
                    val timeMin = Math.max(1, Math.round(currentRoute.totalDurationSeconds / 60.0).toInt())
                    val bannerPrefix = if (isNavigating) "🟢 Navigating: " else "📍 "
                    Box(
                        modifier = Modifier
                            .background(GlassBerry, shape = RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, GlassPillBorder), RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "$bannerPrefix${currentRoute.destinationName} • ${dist}m (${timeMin}m)",
                            color = VanillaCloud,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (statusMsg != null) {
                    Box(
                        modifier = Modifier
                            .background(GlassBerry, shape = RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, GlassPillBorder), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = statusMsg,
                            color = VanillaCloud,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Triple-tap calibration debug info
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
                        .padding(top = 48.dp, start = 16.dp)
                        .background(Color(0xD93D1A40), shape = RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, GlassPillBorder), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Calibration debug info: Focal length is ${currentFocalPx.toInt()} pixels. Last estimated obstacle distance is $lastDistStr"
                        }
                ) {
                    Text(
                        text = debugText,
                        color = VanillaCloud,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom Section: Caption Bar + Stop Button
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bottom Caption Bar showing last spoken message
                val displayCaption = lastSpokenCaption?.takeIf { it.isNotBlank() } ?: "Ready • Point camera forward"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xD93D1A40), shape = RoundedCornerShape(20.dp))
                        .border(BorderStroke(1.dp, GlassPillBorder), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics {
                            contentDescription = "Last spoken alert: $displayCaption"
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "💬",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            text = displayCaption,
                            color = VanillaCloud,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Glass-style translucent Deep Berry Stop button (>=64dp height)
                Button(
                    onClick = onStopAssist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "Stop NaviSense button. Double tap to stop camera, navigation, and haptics, and return to Home screen."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GlassBerry,
                        contentColor = VanillaCloud
                    ),
                    border = BorderStroke(1.5.dp, GlassPillBorder),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "⏹ Stop NaviSense",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = VanillaCloud
                    )
                }
            }
        } else {
            // Permission request screen styled with warm light theme
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VanillaCloud)
                    .padding(24.dp)
                    .semantics {
                        contentDescription = "Permissions required. Camera, microphone, and location permissions must be granted to run NaviSense assist mode."
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.displayMedium,
                    color = DeepBerry,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "NaviSense needs camera access for obstacle detection, microphone for destination voice commands, and location for walking navigation.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DarkInk,
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
                        containerColor = DeepBerry,
                        contentColor = VanillaCloud
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "Grant Permissions",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = VanillaCloud
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStopAssist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = "Return to Home button."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoftCardSurface,
                        contentColor = DarkInk
                    ),
                    border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "Back to Home",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkInk
                    )
                }
            }
        }
    }
}

/**
 * Settings Screen (Light warm premium theme):
 * - Card sections in SoftCardSurface (#F3E8EE) with 20dp rounded corners
 * - Toggle rows with clear on/off states and consistent icons
 * - Voice picker listing available en-IN voices with Preview button and persistence
 * - Minimum 64dp touch targets throughout
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    voiceManager: VoiceManager,
    onUpdateVoice: (Boolean) -> Unit,
    onUpdateHaptics: (Boolean) -> Unit,
    onUpdateVibrationStrength: (VibrationStrength) -> Unit,
    onUpdateSpeechRate: (SpeechRate) -> Unit,
    onUpdateMaxDistance: (Float) -> Unit,
    onUpdateSelectedVoice: (String?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = VanillaCloud
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
                    style = MaterialTheme.typography.displayMedium,
                    color = DeepBerry,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Spoken Guidance Toggle
            SettingCard(
                title = "🗣️ Spoken Guidance",
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
                            containerColor = if (settings.isVoiceEnabled) DeepBerry else VanillaCloud,
                            contentColor = if (settings.isVoiceEnabled) VanillaCloud else DarkInk
                        ),
                        border = if (!settings.isVoiceEnabled) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Voice ON", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
                            containerColor = if (!settings.isVoiceEnabled) PressedInk else VanillaCloud,
                            contentColor = if (!settings.isVoiceEnabled) VanillaCloud else DarkInk
                        ),
                        border = if (settings.isVoiceEnabled) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Voice OFF", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Guide Voice Picker (Indian English en-IN voices)
            val availableVoices = remember { voiceManager.getAvailableIndianEnglishVoices() }
            SettingCard(
                title = "🎙️ Guide Voice",
                subtitle = "Select your preferred Indian English guide voice"
            ) {
                if (availableVoices.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Standard Guide Voice",
                                style = MaterialTheme.typography.titleMedium,
                                color = DarkInk,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Indian English system voice",
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkInk.copy(alpha = 0.7f)
                            )
                        }
                        Button(
                            onClick = { voiceManager.previewVoice(null, "Hello, I am your NaviSense guide.") },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepBerry, contentColor = VanillaCloud),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) {
                            Text("▶ Preview", fontWeight = FontWeight.Bold, color = VanillaCloud)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Display voices with clean labels and preview buttons
                        availableVoices.take(5).forEachIndexed { index, voice ->
                            val isSelected = (settings.selectedVoiceName == voice.name) ||
                                    (settings.selectedVoiceName == null && index == 0)
                            val offlineStatus = if (!voice.isNetworkConnectionRequired) "Offline" else "Online"
                            val genderHint = when {
                                voice.name.contains("female", ignoreCase = true) -> "Female"
                                voice.name.contains("male", ignoreCase = true) -> "Male"
                                else -> "Voice ${index + 1}"
                            }
                            val displayName = "$genderHint • $offlineStatus"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = if (isSelected) DeepBerry.copy(alpha = 0.08f) else VanillaCloud,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) DeepBerry else DeepBerry.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onUpdateSelectedVoice(voice.name) }
                                        .semantics {
                                            contentDescription = "$displayName voice. Currently ${if (isSelected) "selected" else "not selected"}. Double tap to select."
                                        }
                                ) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = DarkInk,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = voice.name.substringAfterLast("/").take(24),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DarkInk.copy(alpha = 0.65f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        voiceManager.previewVoice(voice.name, "Hello, I am your NaviSense guide.")
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            contentDescription = "Preview $displayName voice."
                                        },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) DeepBerry else SoftCardSurface,
                                        contentColor = if (isSelected) VanillaCloud else DarkInk
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("▶ Preview", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Vibration Alerts (Haptics) Toggle
            SettingCard(
                title = "📳 Haptic Alerts",
                subtitle = if (settings.isHapticsEnabled) "Obstacle proximity ticks and turn cues enabled" else "Haptics disabled"
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
                            containerColor = if (settings.isHapticsEnabled) DeepBerry else VanillaCloud,
                            contentColor = if (settings.isHapticsEnabled) VanillaCloud else DarkInk
                        ),
                        border = if (!settings.isHapticsEnabled) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Haptics ON", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
                            containerColor = if (!settings.isHapticsEnabled) PressedInk else VanillaCloud,
                            contentColor = if (!settings.isHapticsEnabled) VanillaCloud else DarkInk
                        ),
                        border = if (settings.isHapticsEnabled) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Haptics OFF", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Vibration Strength
            SettingCard(
                title = "⚡ Vibration Strength",
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
                                containerColor = if (isSelected) DeepBerry else VanillaCloud,
                                contentColor = if (isSelected) VanillaCloud else DarkInk
                            ),
                            border = if (!isSelected) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(strength.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Speech Rate
            SettingCard(
                title = "⏱️ Speech Rate",
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
                                containerColor = if (isSelected) DeepBerry else VanillaCloud,
                                contentColor = if (isSelected) VanillaCloud else DarkInk
                            ),
                            border = if (!isSelected) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(rate.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Maximum Alert Distance
            SettingCard(
                title = "📏 Maximum Detection Range",
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
                            containerColor = if (is3m) DeepBerry else VanillaCloud,
                            contentColor = if (is3m) VanillaCloud else DarkInk
                        ),
                        border = if (!is3m) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("3 Meters", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
                            containerColor = if (is5m) DeepBerry else VanillaCloud,
                            contentColor = if (is5m) VanillaCloud else DarkInk
                        ),
                        border = if (!is5m) BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("5 Meters", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Back to Home Button
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        contentDescription = "Back to Home screen button."
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepBerry,
                    contentColor = VanillaCloud
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VanillaCloud)
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
        colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = DeepBerry,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = DarkInk.copy(alpha = 0.72f),
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * How to Use Screen (Light warm premium theme):
 * - Refined display typography and 20dp soft cards
 * - Spoken instructions and interactive pattern buttons
 * - Clear contrast and 64dp touch targets
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
        color = VanillaCloud
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "How to Use",
                style = MaterialTheme.typography.displayMedium,
                color = DeepBerry,
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
                    containerColor = DeepBerry,
                    contentColor = VanillaCloud
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("🔊 Read Aloud", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VanillaCloud)
            }

            Spacer(modifier = Modifier.height(20.dp))

            InstructionItem(
                number = "1",
                title = "Double Tap to Speak Destination",
                description = "While Assist is active, double tap anywhere on the screen. When prompted, speak your desired destination (e.g. 'Coffee shop' or 'Metro Station')."
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "2",
                title = "Turn-by-Turn Voice Commands",
                description = "After a route is found, double tap and speak:\n• 'start navigation' to begin guidance\n• 'stop navigation' to cancel the route\n• 'repeat' to hear current step again\n• 'how far' to hear remaining distance"
            )

            Spacer(modifier = Modifier.height(14.dp))

            InstructionItem(
                number = "3",
                title = "Obstacle Perception & Avoidance",
                description = "Point your phone camera forward. Center-zone obstacles within 3 meters trigger natural avoidance advice ('Step left', 'Step right', or 'Stop') and proximity ticks that quicken as distance decreases."
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
                description = "Tap the large Stop button at the bottom of the Assist screen or use the system back gesture to return to Home at any time."
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Feel the Patterns Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Feel the Patterns",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeepBerry,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Touch any button below to hear its name and feel its vibration pattern once so you can learn them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkInk.copy(alpha = 0.75f),
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
                                    containerColor = VanillaCloud,
                                    contentColor = DarkInk
                                ),
                                border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkInk
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
                    containerColor = DeepBerry,
                    contentColor = VanillaCloud
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VanillaCloud)
            }
        }
    }
}

@Composable
private fun InstructionItem(number: String, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(DeepBerry, shape = RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = number,
                        color = VanillaCloud,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = DarkInk,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = DarkInk.copy(alpha = 0.85f),
                lineHeight = 22.sp
            )
        }
    }
}

/**
 * About and Safety Screen:
 * - Mandatory safety disclaimer card with Signal Orange border & badge (never orange text on cream)
 * - App description and version details
 * - 64dp touch targets
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
        color = VanillaCloud
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "About & Safety",
                style = MaterialTheme.typography.displayMedium,
                color = DeepBerry,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Prominent Safety Disclaimer Card with Signal Orange border & badge
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(2.5.dp, SignalOrange), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Box(
                        modifier = Modifier
                            .background(SignalOrange, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "⚠️ MANDATORY SAFETY DISCLAIMER",
                            color = DarkInk,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "NaviSense is an assistive aid and does not replace a white cane or a guide.",
                        style = MaterialTheme.typography.titleLarge,
                        color = DarkInk,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Never rely solely on NaviSense for physical safety. Sensor limitations, environmental lighting, and obstacles outside the camera's view require continuous user alertness and tactile cane verification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DarkInk.copy(alpha = 0.85f),
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // App Description
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SoftCardSurface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, DeepBerry.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "About NaviSense",
                        style = MaterialTheme.typography.titleLarge,
                        color = DeepBerry,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "NaviSense is an AI-powered pedestrian navigation assistant designed specifically for blind and visually impaired individuals. It integrates camera machine vision, pinhole distance estimation, intelligent free-space avoidance guidance, warm guide voice persona, turn-by-turn walking directions, directional haptics, and hands-free voice commands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DarkInk,
                        lineHeight = 24.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Version 1.1 • Premium Warm Edition",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepBerry,
                        fontWeight = FontWeight.Bold
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
                    containerColor = DeepBerry,
                    contentColor = VanillaCloud
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("🔊 Hear Disclaimer Again", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VanillaCloud)
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
                    containerColor = PressedInk,
                    contentColor = VanillaCloud
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("⬅ Back to Home", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VanillaCloud)
            }
        }
    }
}
