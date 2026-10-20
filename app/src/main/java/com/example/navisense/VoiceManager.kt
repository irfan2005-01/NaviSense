package com.example.navisense

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceManager(context: Context) : TextToSpeech.OnInitListener {

    private val tag = "VoiceManager"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private var pendingSpeech: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(tag, "Language not supported or missing data.")
            } else {
                isReady = true
                Log.d(tag, "TextToSpeech initialized successfully.")
                pendingSpeech?.let {
                    speak(it)
                    pendingSpeech = null
                }
            }
        } else {
            Log.e(tag, "TextToSpeech initialization failed with status: $status")
        }
    }

    fun speak(text: String) {
        if (isReady) {
            Log.d(tag, "Speaking: $text")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NaviSenseVoice")
        } else {
            Log.d(tag, "Queueing pending speech: $text")
            pendingSpeech = text
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
