package com.example.navisense

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    var frameAnalyzer: ((ImageProxy) -> Unit)? = null
    var isPaused: Boolean = false

    fun pause() {
        isPaused = true
        Log.d(TAG, "Camera frame analysis paused.")
    }

    fun resume() {
        isPaused = false
        Log.d(TAG, "Camera frame analysis resumed.")
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: ((Exception) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                var frameCount = 0
                var lastFpsTimestamp = System.currentTimeMillis()
                var isFirstFrame = true

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (isPaused) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            try {
                                frameCount++
                                if (isFirstFrame) {
                                    isFirstFrame = false
                                    Log.d(
                                        TAG,
                                        "First analysis frame received on thread [${Thread.currentThread().name}]: ${imageProxy.width}x${imageProxy.height}"
                                    )
                                }
                                val now = System.currentTimeMillis()
                                val elapsed = now - lastFpsTimestamp
                                if (elapsed >= 1000L) {
                                    val fps = (frameCount * 1000.0) / elapsed
                                    Log.d(
                                        TAG,
                                        "Frame size: ${imageProxy.width}x${imageProxy.height}, FPS: ${String.format(Locale.US, "%.1f", fps)} on thread [${Thread.currentThread().name}]"
                                    )
                                    frameCount = 0
                                    lastFpsTimestamp = now
                                }
                                frameAnalyzer?.invoke(imageProxy)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error analyzing image frame", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", exc)
                onError?.invoke(exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            if (cameraProviderFuture.isDone) {
                cameraProviderFuture.get().unbindAll()
                Log.d(TAG, "Camera use cases successfully unbound.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera", e)
        }
    }

    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}

@Composable
fun CameraPreview(
    cameraManager: CameraManager,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                cameraManager.startCamera(lifecycleOwner, this)
            }
        }
    )
}
