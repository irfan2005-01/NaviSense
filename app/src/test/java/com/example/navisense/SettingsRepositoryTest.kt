package com.example.navisense

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure in-memory FakeSharedPreferences for fast JVM unit testing.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = data

    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private var clearCalled = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) staging[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) staging[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearCalled = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearCalled) {
                parent.data.clear()
            }
            for ((k, v) in staging) {
                if (v == null) {
                    parent.data.remove(k)
                } else {
                    parent.data[k] = v
                }
            }
        }
    }
}

class SettingsRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepository(fakePrefs)
    }

    @Test
    fun defaultSettings_matchExpectedDefaults() {
        val settings = repository.getSettings()

        assertTrue("Voice should be enabled by default", settings.isVoiceEnabled)
        assertTrue("Haptics should be enabled by default", settings.isHapticsEnabled)
        assertEquals("Speech rate should default to NORMAL (1.0x)", SpeechRate.NORMAL, settings.speechRate)
        assertEquals(1.0f, settings.speechRate.rate, 0.01f)
        assertEquals("Max alert distance should default to 5.0m", 5.0f, settings.maxAlertDistanceMeters, 0.01f)
        assertEquals("Vibration strength should default to HIGH", VibrationStrength.HIGH, settings.vibrationStrength)
        assertFalse("Disclaimer should not be marked shown by default", settings.hasShownDisclaimer)
    }

    @Test
    fun setVoiceEnabled_updatesAndPersistsState() {
        repository.setVoiceEnabled(false)
        assertFalse(repository.getSettings().isVoiceEnabled)

        repository.setVoiceEnabled(true)
        assertTrue(repository.getSettings().isVoiceEnabled)
    }

    @Test
    fun setHapticsEnabled_updatesAndPersistsState() {
        repository.setHapticsEnabled(false)
        assertFalse(repository.getSettings().isHapticsEnabled)

        repository.setHapticsEnabled(true)
        assertTrue(repository.getSettings().isHapticsEnabled)
    }

    @Test
    fun setSpeechRate_updatesAndMapsProperly() {
        // Slow
        repository.setSpeechRate(SpeechRate.SLOW)
        assertEquals(SpeechRate.SLOW, repository.getSettings().speechRate)
        assertEquals(0.8f, repository.getSettings().speechRate.rate, 0.01f)

        // Fast
        repository.setSpeechRate(SpeechRate.FAST)
        assertEquals(SpeechRate.FAST, repository.getSettings().speechRate)
        assertEquals(1.3f, repository.getSettings().speechRate.rate, 0.01f)

        // Normal
        repository.setSpeechRate(SpeechRate.NORMAL)
        assertEquals(SpeechRate.NORMAL, repository.getSettings().speechRate)
        assertEquals(1.0f, repository.getSettings().speechRate.rate, 0.01f)
    }

    @Test
    fun speechRate_fromRateHelper_handlesBoundaryFloats() {
        assertEquals(SpeechRate.SLOW, SpeechRate.fromRate(0.7f))
        assertEquals(SpeechRate.SLOW, SpeechRate.fromRate(0.85f))
        assertEquals(SpeechRate.NORMAL, SpeechRate.fromRate(1.0f))
        assertEquals(SpeechRate.NORMAL, SpeechRate.fromRate(1.1f))
        assertEquals(SpeechRate.FAST, SpeechRate.fromRate(1.2f))
        assertEquals(SpeechRate.FAST, SpeechRate.fromRate(1.5f))
    }

    @Test
    fun setMaxAlertDistance_updatesAndPersistsState() {
        repository.setMaxAlertDistance(3.0f)
        assertEquals(3.0f, repository.getSettings().maxAlertDistanceMeters, 0.01f)

        repository.setMaxAlertDistance(5.0f)
        assertEquals(5.0f, repository.getSettings().maxAlertDistanceMeters, 0.01f)
    }

    @Test
    fun setDisclaimerShown_updatesAndPersistsFlag() {
        assertFalse(repository.getSettings().hasShownDisclaimer)

        repository.setDisclaimerShown(true)
        assertTrue(repository.getSettings().hasShownDisclaimer)

        repository.setDisclaimerShown(false)
        assertFalse(repository.getSettings().hasShownDisclaimer)
    }

    @Test
    fun setVibrationStrength_updatesAndPersistsState() {
        repository.setVibrationStrength(VibrationStrength.LOW)
        assertEquals(VibrationStrength.LOW, repository.getSettings().vibrationStrength)
        assertEquals(0.5f, repository.getSettings().vibrationStrength.scaleFactor, 0.01f)

        repository.setVibrationStrength(VibrationStrength.MEDIUM)
        assertEquals(VibrationStrength.MEDIUM, repository.getSettings().vibrationStrength)
        assertEquals(0.75f, repository.getSettings().vibrationStrength.scaleFactor, 0.01f)

        repository.setVibrationStrength(VibrationStrength.HIGH)
        assertEquals(VibrationStrength.HIGH, repository.getSettings().vibrationStrength)
        assertEquals(1.0f, repository.getSettings().vibrationStrength.scaleFactor, 0.01f)
    }

    @Test
    fun vibrationStrength_fromStringHelper() {
        assertEquals(VibrationStrength.LOW, VibrationStrength.fromString("LOW"))
        assertEquals(VibrationStrength.LOW, VibrationStrength.fromString("low"))
        assertEquals(VibrationStrength.MEDIUM, VibrationStrength.fromString("MEDIUM"))
        assertEquals(VibrationStrength.HIGH, VibrationStrength.fromString("HIGH"))
        assertEquals(VibrationStrength.HIGH, VibrationStrength.fromString("invalid"))
        assertEquals(VibrationStrength.HIGH, VibrationStrength.fromString(null))
    }
}
