package com.example.navisense

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
    val lastSpokenMessage: String?
        get() = null
}

/**
 * Manages Text-To-Speech synthesis:
 * - Prioritizes the best offline/highest-quality Indian English (en-IN) voices
 * - Uses pitch ~1.0 and default rate 0.95 for a warm, human guide experience
 * - Integrates VoicePersona to turn raw system output into natural, warm phrasing
 * - Exposes available en-IN voices and voice preview capability for settings
 * - Handles high-priority interruption and navigation instruction re-queueing.
 */
class VoiceManager(
    context: Context,
    val persona: VoicePersona = VoicePersona()
) : TextToSpeech.OnInitListener, VoiceNotifier {

    private val tag = "VoiceManager"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    var isEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                stop()
            }
        }

    var speechRate: Float = 0.95f
        private set

    var speechPitch: Float = 1.0f
        private set

    var preferredVoiceName: String? = null
        set(value) {
            field = value
            if (isReady && value != null) {
                selectBestIndianEnglishVoice(value)
            }
        }

    override var lastSpokenMessage: String? = null
        private set

    var onMessageSpoken: ((String) -> Unit)? = null

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(rate)
        Log.d(tag, "Speech rate set to: $rate")
    }

    fun setSpeechPitch(pitch: Float) {
        speechPitch = pitch
        tts?.setPitch(pitch)
        Log.d(tag, "Speech pitch set to: $pitch")
    }

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
            isReady = true
            tts?.setPitch(speechPitch)
            tts?.setSpeechRate(speechRate)

            // Select best available Indian English voice with fallback
            selectBestIndianEnglishVoice(preferredVoiceName)

            Log.d(tag, "TextToSpeech initialized with rate $speechRate and pitch $speechPitch.")
            pendingSpeech?.let { (speechText, callback) ->
                speak(speechText, false, callback)
                pendingSpeech = null
            }
        } else {
            Log.e(tag, "TextToSpeech initialization failed with status: $status")
        }
    }

    /**
     * Filters available voices for Indian English (en-IN), ordered by:
     * 1. Offline (network not required)
     * 2. Quality descending
     * 3. Name
     */
    fun getAvailableIndianEnglishVoices(): List<Voice> {
        val voices = tts?.voices ?: return emptyList()
        return voices.filter { voice ->
            val loc = voice.locale
            (loc.language.equals("en", ignoreCase = true) && loc.country.equals("IN", ignoreCase = true)) ||
                loc.toLanguageTag().startsWith("en-IN", ignoreCase = true)
        }.sortedWith(
            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenBy { it.name }
        )
    }

    /**
     * Sets the voice, preferring the selected name, then best en-IN voice, then graceful fallback.
     */
    fun selectBestIndianEnglishVoice(preferredName: String? = null): Voice? {
        val inVoices = getAvailableIndianEnglishVoices()
        if (preferredName != null) {
            val match = inVoices.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }
                ?: tts?.voices?.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }
            if (match != null) {
                try {
                    tts?.voice = match
                    Log.i(tag, "Applied preferred voice: ${match.name}")
                    return match
                } catch (e: Exception) {
                    Log.w(tag, "Could not apply preferred voice: $preferredName", e)
                }
            }
        }

        val bestVoice = inVoices.firstOrNull()
        if (bestVoice != null) {
            try {
                tts?.voice = bestVoice
                Log.i(tag, "Applied best en-IN voice: ${bestVoice.name} (networkRequired=${bestVoice.isNetworkConnectionRequired}, quality=${bestVoice.quality})")
                return bestVoice
            } catch (e: Exception) {
                Log.w(tag, "Could not set best en-IN voice, falling back", e)
            }
        }

        // Graceful fallback to en-IN locale or US
        val langResult = tts?.setLanguage(Locale("en", "IN"))
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
        return tts?.voice
    }

    fun setVoiceByName(name: String?) {
        preferredVoiceName = name
        selectBestIndianEnglishVoice(name)
    }

    fun previewVoice(voiceName: String?, sampleText: String = "Hello, I am your NaviSense guide.") {
        if (voiceName != null) {
            val match = tts?.voices?.firstOrNull { it.name.equals(voiceName, ignoreCase = true) }
            if (match != null) {
                tts?.voice = match
            }
        }
        speak(sampleText, interrupt = true)
    }

    /**
     * Speaks a navigation instruction.
     * Yields priority to active critical obstacle alerts (<1.5m).
     */
    override fun speakNavigation(text: String) {
        val naturalText = persona.transform(text, isUrgent = false)
        Log.i(tag, "Navigation instruction: \"$naturalText\"")
        activeNavigationInstruction = naturalText

        if (isSpeakingCriticalAlert) {
            Log.i(tag, "Critical alert is active; holding navigation instruction until alert finishes.")
            pendingNavigationInstruction = naturalText
            return
        }

        isSpeakingNavigation = true
        speak(
            text = text,
            interrupt = true,
            onDone = {
                isSpeakingNavigation = false
                if (activeNavigationInstruction == naturalText) {
                    activeNavigationInstruction = null
                }
            }
        )
    }

    override fun speak(text: String, interrupt: Boolean) {
        speak(text, interrupt, onDone = null)
    }

    override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?) {
        if (!isEnabled) {
            onDone?.invoke()
            return
        }

        val phrasedText = persona.transform(text, isUrgent = interrupt)
        lastSpokenMessage = phrasedText
        onMessageSpoken?.invoke(phrasedText)

        if (!isReady) {
            Log.d(tag, "Queueing pending speech: $phrasedText")
            pendingSpeech = Pair(phrasedText, onDone)
            return
        }

        if (interrupt) {
            if (isSpeakingNavigation && activeNavigationInstruction != null) {
                Log.i(
                    tag,
                    "Navigation instruction \"$activeNavigationInstruction\" interrupted by \"$phrasedText\". Saving to re-queue."
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
        Log.i(tag, "Spoken message: \"$phrasedText\" (interrupt=$interrupt, utteranceId=$utteranceId)")
        tts?.speak(phrasedText, queueMode, null, utteranceId)
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
