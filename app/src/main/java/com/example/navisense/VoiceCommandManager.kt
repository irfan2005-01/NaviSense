package com.example.navisense

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manages voice commands using Android's SpeechRecognizer with en-IN language support.
 * Coordinates speech prompts and listening states, handling recognition errors with spoken feedback.
 */
class VoiceCommandManager(
    private val context: Context,
    private val voiceManager: VoiceManager,
    private val onCommandReceived: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    init {
        mainHandler.post {
            setupSpeechRecognizer()
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition service is not available on this device.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized with en-IN locale.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
        }
    }

    /**
     * Prompts the user with the given spoken question, then starts listening once the speech ends.
     */
    fun prompt(promptText: String = "Where do you want to go?") {
        if (!hasAudioPermission()) {
            Log.w(TAG, "Audio permission missing when attempting to prompt.")
            voiceManager.speak(
                "Microphone permission is needed for voice navigation. Please grant it in settings.",
                interrupt = true
            )
            return
        }

        mainHandler.post {
            if (isListening) {
                stopListening()
            }

            Log.i(TAG, "Prompting user: '$promptText'")
            voiceManager.speak(
                text = promptText,
                interrupt = true,
                onDone = {
                    mainHandler.post {
                        startListening()
                    }
                }
            )
        }
    }

    /**
     * Prompts the user with "Where do you want to go?", then starts listening once the speech ends.
     */
    fun promptForDestination() {
        prompt("Where do you want to go?")
    }

    fun startListening() {
        if (!hasAudioPermission()) {
            voiceManager.speak("Microphone permission required.", interrupt = true)
            return
        }

        mainHandler.post {
            if (speechRecognizer == null) {
                setupSpeechRecognizer()
            }

            val recognizer = speechRecognizer
            if (recognizer == null) {
                Log.e(TAG, "Cannot start listening: SpeechRecognizer is null.")
                voiceManager.speak("Speech recognizer unavailable.", interrupt = true)
                return@post
            }

            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                isListening = true
                recognizer.startListening(intent)
                Log.i(TAG, "SpeechRecognizer listening for destination...")
            } catch (e: Exception) {
                isListening = false
                Log.e(TAG, "Error starting speech recognition", e)
                voiceManager.speak("Could not start microphone, please try again.", interrupt = true)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
                Log.d(TAG, "Stopped speech listening.")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping speech recognizer", e)
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer ready for speech.")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech began.")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                Log.d(TAG, "Speech ended.")
            }

            override fun onError(error: Int) {
                isListening = false
                Log.w(TAG, "Speech recognition error code: $error")

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        "No internet connection for voice recognition. Obstacle detection is still active offline."
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        "I did not catch that, please try again."
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        "Microphone permission is required for voice commands."
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        "Audio recording error, please try again."
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        "Speech recognizer is busy, please wait a moment and try again."
                    }
                    SpeechRecognizer.ERROR_SERVER -> {
                        "Speech server error. Obstacle detection is still active offline."
                    }
                    else -> {
                        "I did not catch that, please try again."
                    }
                }

                voiceManager.speak(errorMessage, interrupt = true)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull()?.trim()

                if (!recognizedText.isNullOrBlank()) {
                    Log.i(TAG, "Voice command received: \"$recognizedText\"")
                    onCommandReceived(recognizedText)
                } else {
                    Log.w(TAG, "Empty speech recognition results.")
                    voiceManager.speak("I did not catch that, please try again.", interrupt = true)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                Log.d(TAG, "VoiceCommandManager destroyed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer", e)
            }
        }
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
    }
}
