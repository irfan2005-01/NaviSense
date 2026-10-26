package com.example.navisense

import android.content.Context
import android.content.SharedPreferences

enum class SpeechRate(val rate: Float, val displayName: String) {
    SLOW(0.8f, "Slow"),
    NORMAL(1.0f, "Normal"),
    FAST(1.3f, "Fast");

    companion object {
        fun fromRate(rate: Float): SpeechRate = when {
            rate <= 0.85f -> SLOW
            rate >= 1.2f -> FAST
            else -> NORMAL
        }
    }
}

enum class VibrationStrength(val scaleFactor: Float, val displayName: String) {
    LOW(0.5f, "Low"),
    MEDIUM(0.75f, "Medium"),
    HIGH(1.0f, "High");

    companion object {
        fun fromString(name: String?): VibrationStrength {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: HIGH
        }
    }
}

data class AppSettings(
    val isVoiceEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val speechRate: SpeechRate = SpeechRate.NORMAL,
    val maxAlertDistanceMeters: Float = 5.0f,
    val hasShownDisclaimer: Boolean = false,
    val vibrationStrength: VibrationStrength = VibrationStrength.HIGH,
    val selectedVoiceName: String? = null
)

/**
 * Manages persistent storage and retrieval of NaviSense user preferences.
 */
class SettingsRepository(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun getSettings(): AppSettings {
        val voice = prefs.getBoolean(KEY_VOICE, true)
        val haptics = prefs.getBoolean(KEY_HAPTICS, true)
        val rateVal = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        val maxDist = prefs.getFloat(KEY_MAX_DISTANCE, 5.0f)
        val disclaimer = prefs.getBoolean(KEY_DISCLAIMER_SHOWN, false)
        val strengthStr = prefs.getString(KEY_VIBRATION_STRENGTH, VibrationStrength.HIGH.name)
        val voiceName = prefs.getString(KEY_SELECTED_VOICE, null)
        return AppSettings(
            isVoiceEnabled = voice,
            isHapticsEnabled = haptics,
            speechRate = SpeechRate.fromRate(rateVal),
            maxAlertDistanceMeters = maxDist,
            hasShownDisclaimer = disclaimer,
            vibrationStrength = VibrationStrength.fromString(strengthStr),
            selectedVoiceName = voiceName
        )
    }

    fun setVoiceEnabled(enabled: Boolean): AppSettings {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
        return getSettings()
    }

    fun setHapticsEnabled(enabled: Boolean): AppSettings {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
        return getSettings()
    }

    fun setVibrationStrength(strength: VibrationStrength): AppSettings {
        prefs.edit().putString(KEY_VIBRATION_STRENGTH, strength.name).apply()
        return getSettings()
    }

    fun setSpeechRate(rate: SpeechRate): AppSettings {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate.rate).apply()
        return getSettings()
    }

    fun setMaxAlertDistance(distanceMeters: Float): AppSettings {
        prefs.edit().putFloat(KEY_MAX_DISTANCE, distanceMeters).apply()
        return getSettings()
    }

    fun setDisclaimerShown(shown: Boolean): AppSettings {
        prefs.edit().putBoolean(KEY_DISCLAIMER_SHOWN, shown).apply()
        return getSettings()
    }

    fun setSelectedVoice(voiceName: String?): AppSettings {
        prefs.edit().putString(KEY_SELECTED_VOICE, voiceName).apply()
        return getSettings()
    }

    companion object {
        const val PREFS_NAME = "navisense_settings"
        const val KEY_VOICE = "pref_voice_enabled"
        const val KEY_HAPTICS = "pref_haptics_enabled"
        const val KEY_VIBRATION_STRENGTH = "pref_vibration_strength"
        const val KEY_SPEECH_RATE = "pref_speech_rate"
        const val KEY_MAX_DISTANCE = "pref_max_alert_distance"
        const val KEY_DISCLAIMER_SHOWN = "pref_disclaimer_shown"
        const val KEY_SELECTED_VOICE = "pref_selected_voice"
    }
}
