package com.example.navisense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.Locale

enum class Zone {
    LEFT,
    CENTER,
    RIGHT
}

data class Detection(
    val label: String,
    val score: Float,
    val boundingBox: RectF,
    val zone: Zone,
    val distanceMeters: Float = 0f,
    val frameWidthPx: Int = 0,
    val frameHeightPx: Int = 0,
    val boxWidthPx: Float = 0f,
    val boxHeightPx: Float = 0f,
    val isUnreliableOrLarge: Boolean = false
)

class ObjectDetectorManager(
    private val context: Context,
    private val distanceEstimator: DistanceEstimator = DistanceEstimator(context),
    private val onDetections: (List<Detection>, Int, Int) -> Unit
) {

    private var objectDetector: ObjectDetector? = null
    private var lastFrameTimeMs: Long = 0L

    init {
        setupDetector()
    }

    private fun setupDetector() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(0.5f)
            .setMaxResults(5)
            .setResultListener { result: ObjectDetectorResult, inputImage: MPImage ->
                processResult(result, inputImage)
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe ObjectDetector error: ${error.message}", error)
            }
            .build()

        try {
            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "ObjectDetector successfully initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector", e)
        }
    }

    fun detect(imageProxy: ImageProxy) {
        val detector = objectDetector ?: return
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()

        // Handle rotation so that the bitmap fed to MediaPipe is upright
        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        // Generate strictly increasing timestamp in milliseconds required by LIVE_STREAM mode
        val now = SystemClock.uptimeMillis()
        val frameTime = if (now <= lastFrameTimeMs) lastFrameTimeMs + 1 else now
        lastFrameTimeMs = frameTime

        detector.detectAsync(mpImage, frameTime)
    }

    private fun processResult(result: ObjectDetectorResult, inputImage: MPImage) {
        val frameWidth = inputImage.width.toFloat()
        val frameHeight = inputImage.height

        val detections = result.detections().map { mpDetection ->
            val category = mpDetection.categories().firstOrNull()
            val label = category?.categoryName() ?: "Unknown"
            val score = category?.score() ?: 0f
            val box = mpDetection.boundingBox()

            val boxCenterX = box.centerX()
            val zone = when {
                boxCenterX < frameWidth / 3f -> Zone.LEFT
                boxCenterX < (2f * frameWidth) / 3f -> Zone.CENTER
                else -> Zone.RIGHT
            }

            val bHeight = kotlin.math.abs(box.bottom - box.top)
            val bWidth = kotlin.math.abs(box.right - box.left)

            // Estimate distance from bounding box height
            var distance = distanceEstimator.estimateDistance(
                label = label,
                boxHeightPx = bHeight,
                frameHeightPx = frameHeight
            )

            val isLargeBox = frameHeight > 0 && bHeight > ObstacleTracker.LARGE_BOX_FRAME_HEIGHT_RATIO * frameHeight
            val normLabel = label.trim().lowercase(Locale.US)
            val isVehicle = normLabel in ObstacleTracker.VEHICLE_CLASSES
            val isExtremeVehicleRatio = isVehicle && bHeight > 0f && (bWidth / bHeight > ObstacleTracker.MAX_VEHICLE_ASPECT_RATIO || bWidth / bHeight < ObstacleTracker.MIN_VEHICLE_ASPECT_RATIO)

            // Apply "unreliable or large obstacle" suppression only to vehicle classes
            val isUnreliable = isVehicle && (isLargeBox || isExtremeVehicleRatio)

            // For non-vehicle classes, large box means object is very close; clamp distance to minimum 0.5m
            if (!isVehicle && isLargeBox) {
                distance = distance.coerceAtLeast(ObstacleTracker.MIN_CLAMPED_DISTANCE_METERS)
            }

            Detection(
                label = label,
                score = score,
                boundingBox = box,
                zone = zone,
                distanceMeters = distance,
                frameWidthPx = inputImage.width,
                frameHeightPx = inputImage.height,
                boxWidthPx = bWidth,
                boxHeightPx = bHeight,
                isUnreliableOrLarge = isUnreliable
            )
        }

        // Log detections to Logcat, e.g. "person | CENTER | 0.82 | 2.1m"
        for (detection in detections) {
            Log.d(
                TAG,
                "${detection.label} | ${detection.zone} | ${String.format(Locale.US, "%.2f", detection.score)} | ${String.format(Locale.US, "%.1fm", detection.distanceMeters)}"
            )
        }

        onDetections(detections, inputImage.width, inputImage.height)
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
    }

    companion object {
        private const val TAG = "ObjectDetectorManager"
    }
}

@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0 || detections.isEmpty()) return@Canvas

        val viewWidth = size.width
        val viewHeight = size.height

        // Scale and center matching PreviewView.ScaleType.FILL_CENTER
        val scale = maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        for (detection in detections) {
            val box = detection.boundingBox
            val left = box.left * scale + offsetX
            val top = box.top * scale + offsetY
            val right = box.right * scale + offsetX
            val bottom = box.bottom * scale + offsetY

            val boxColor = when (detection.zone) {
                Zone.LEFT -> Color(0xFF29B6F6) // Light Blue
                Zone.CENTER -> Color(0xFF66BB6A) // Green
                Zone.RIGHT -> Color(0xFFFFA726) // Orange
            }

            // Draw bounding box
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                style = Stroke(width = 3.dp.toPx())
            )

            // Label text format: "person 2.1m (CENTER)"
            val distFormatted = String.format(Locale.US, "%.1fm", detection.distanceMeters)
            val labelText = "${detection.label} $distFormatted (${detection.zone})"
            val textLayoutResult = textMeasurer.measure(
                text = AnnotatedString(labelText),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            val paddingPx = 4.dp.toPx()
            val textWidth = textLayoutResult.size.width + paddingPx * 2
            val textHeight = textLayoutResult.size.height + paddingPx * 2
            val labelTop = (top - textHeight).coerceAtLeast(0f)
            val labelLeft = left.coerceIn(0f, (viewWidth - textWidth).coerceAtLeast(0f))

            // Background pill for label
            drawRect(
                color = boxColor.copy(alpha = 0.85f),
                topLeft = Offset(labelLeft, labelTop),
                size = Size(textWidth, textHeight)
            )

            // Draw text inside pill
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(labelLeft + paddingPx, labelTop + paddingPx)
            )
        }
    }
}
