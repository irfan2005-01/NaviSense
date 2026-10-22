package com.example.navisense

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface VoiceNotifier {
    fun speak(text: String, interrupt: Boolean = false)
    fun speak(text: String, interrupt: Boolean = false, onDone: (() -> Unit)?) {
        speak(text, interrupt)
        onDone?.invoke()
    }
    fun speakNavigation(text: String) {
        speak(text, interrupt = false)
    }
    fun stop()
}

class VoiceManager(context: Context) : TextToSpeech.OnInitListener, VoiceNotifier {

    private val tag = "VoiceManager"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // Tracking for priority coordination (close obstacle alerts vs navigation instructions)
    private var activeNavigationInstruction: String? = null
    private var pendingNavigationInstruction: String? = null
    private var isSpeakingNavigation = false
    private var isSpeakingCriticalAlert = false

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(tag, "Utterance started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(tag, "Utterance done: $utteranceId")
                if (utteranceId != null) {
                    val callback = utteranceCallbacks.remove(utteranceId)
                    callback?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(tag, "Utterance error: $utteranceId")
                if (utteranceId != null) {
                    val callback = utteranceCallbacks.remove(utteranceId)
                    callback?.invoke()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(tag, "Utterance error: $utteranceId, code: $errorCode")
                if (utteranceId != null) {
                    val callback = utteranceCallbacks.remove(utteranceId)
                    callback?.invoke()
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(tag, "Language not supported or missing data.")
            } else {
                isReady = true
                Log.d(tag, "TextToSpeech initialized successfully.")
                pendingSpeech?.let { (speechText, callback) ->
                    speak(speechText, false, callback)
                    pendingSpeech = null
                }
            }
        } else {
            Log.e(tag, "TextToSpeech initialization failed with status: $status")
        }
    }

    /**
     * Speaks a navigation instruction.
     * Yields priority to active critical obstacle alerts (<1.5m).
     * If interrupted by a critical obstacle alert, will automatically re-queue after alert completes.
     */
    override fun speakNavigation(text: String) {
        Log.i(tag, "Navigation instruction: \"$text\"")
        activeNavigationInstruction = text

        if (isSpeakingCriticalAlert) {
            Log.i(tag, "Critical alert is active; holding navigation instruction until alert finishes.")
            pendingNavigationInstruction = text
            return
        }

        isSpeakingNavigation = true
        speak(
            text = text,
            interrupt = true,
            onDone = {
                isSpeakingNavigation = false
                if (activeNavigationInstruction == text) {
                    activeNavigationInstruction = null
                }
            }
        )
    }

    /**
     * Speaks the given text.
     * If interrupt is true (e.g. very close obstacle < 1.5m), halts any currently playing utterance and speaks immediately.
     * If a navigation instruction was speaking, preserves it and re-queues it once the alert finishes.
     */
    override fun speak(text: String, interrupt: Boolean) {
        speak(text, interrupt, onDone = null)
    }

    override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?) {
        if (!isReady) {
            Log.d(tag, "Queueing pending speech: $text")
            pendingSpeech = Pair(text, onDone)
            return
        }

        if (interrupt) {
            // Check if navigation was in progress and got interrupted
            if (isSpeakingNavigation && activeNavigationInstruction != null) {
                Log.i(
                    tag,
                    "Navigation instruction \"$activeNavigationInstruction\" was interrupted by high-priority alert \"$text\". Saving to re-queue."
                )
                pendingNavigationInstruction = activeNavigationInstruction
                isSpeakingNavigation = false
            }

            isSpeakingCriticalAlert = true
            tts?.stop()
            utteranceCallbacks.clear()
        }

        val utteranceId = "NaviSenseVoice_${System.currentTimeMillis()}_${(0..9999).random()}"
        utteranceCallbacks[utteranceId] = {
            if (interrupt) {
                isSpeakingCriticalAlert = false
                // Re-queue interrupted navigation instruction if one is waiting
                val savedInstruction = pendingNavigationInstruction
                if (savedInstruction != null) {
                    pendingNavigationInstruction = null
                    Log.i(tag, "Re-queueing saved navigation instruction after alert: \"$savedInstruction\"")
                    speakNavigation(savedInstruction)
                }
            }
            onDone?.invoke()
        }

        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        Log.i(tag, "Spoken message: \"$text\" (interrupt=$interrupt, utteranceId=$utteranceId)")
        tts?.speak(text, queueMode, null, utteranceId)
    }

    override fun stop() {
        activeNavigationInstruction = null
        pendingNavigationInstruction = null
        isSpeakingNavigation = false
        isSpeakingCriticalAlert = false
        utteranceCallbacks.clear()
        tts?.stop()
    }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    fun shutdown() {
        utteranceCallbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}

