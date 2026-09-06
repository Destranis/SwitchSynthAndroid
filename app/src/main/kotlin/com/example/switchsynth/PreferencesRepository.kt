package com.example.switchsynth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SELECTED_LANGUAGES = stringSetPreferencesKey("selected_languages")
        val USE_ACCESSIBILITY_VOLUME = booleanPreferencesKey("use_accessibility_volume")
        val EMOJI_VOICE = stringPreferencesKey("emoji_voice")
        val NUMBER_VOICE = stringPreferencesKey("number_voice")

        fun scriptVoiceKey(script: String) = stringPreferencesKey("voice_$script")
        fun scriptLanguageKey(script: String) = stringPreferencesKey("language_$script")
        fun scriptSpeechRateKey(script: String) = floatPreferencesKey("rate_$script")
        fun scriptSpeechPitchKey(script: String) = floatPreferencesKey("pitch_$script")
        fun scriptSpeechVolumeKey(script: String) = floatPreferencesKey("volume_$script")
    }

    val useAccessibilityVolume: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[Keys.USE_ACCESSIBILITY_VOLUME] ?: true }

    fun scriptSpeechRate(script: String): Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.scriptSpeechRateKey(script)] ?: 1.0f }

    fun scriptSpeechPitch(script: String): Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.scriptSpeechPitchKey(script)] ?: 1.0f }

    fun scriptSpeechVolume(script: String): Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.scriptSpeechVolumeKey(script)] ?: 1.0f }

    fun allScriptSpeechRates(scripts: List<String>): Flow<Map<String, Float>> = context.dataStore.data
        .map { preferences ->
            scripts.associateWith { script -> preferences[Keys.scriptSpeechRateKey(script)] ?: 1.0f }
        }

    fun allScriptSpeechPitches(scripts: List<String>): Flow<Map<String, Float>> = context.dataStore.data
        .map { preferences ->
            scripts.associateWith { script -> preferences[Keys.scriptSpeechPitchKey(script)] ?: 1.0f }
        }

    fun allScriptSpeechVolumes(scripts: List<String>): Flow<Map<String, Float>> = context.dataStore.data
        .map { preferences ->
            scripts.associateWith { script -> preferences[Keys.scriptSpeechVolumeKey(script)] ?: 1.0f }
        }

    val emojiVoice: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[Keys.EMOJI_VOICE] ?: "Latin" }

    // "Common" means "follow the surrounding text" (no dedicated voice for numbers).
    val numberVoice: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[Keys.NUMBER_VOICE] ?: "Common" }

    val selectedLanguages: Flow<Set<String>> = context.dataStore.data
        .map { preferences -> preferences[Keys.SELECTED_LANGUAGES] ?: emptySet() }

    fun scriptVoice(script: String): Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.scriptVoiceKey(script)] }

    fun scriptLanguage(script: String): Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.scriptLanguageKey(script)] }

    /** Read all script voices at once from the current preferences snapshot. */
    fun allScriptVoices(scripts: List<String>): Flow<Map<String, String?>> = context.dataStore.data
        .map { preferences ->
            scripts.associateWith { script -> preferences[Keys.scriptVoiceKey(script)] }
        }

    /** Read all script languages at once from the current preferences snapshot. */
    fun allScriptLanguages(scripts: List<String>): Flow<Map<String, String?>> = context.dataStore.data
        .map { preferences ->
            scripts.associateWith { script -> preferences[Keys.scriptLanguageKey(script)] }
        }

    suspend fun updateUseAccessibilityVolume(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_ACCESSIBILITY_VOLUME] = enabled }
    }

    suspend fun updateScriptSpeechRate(script: String, rate: Float) {
        context.dataStore.edit { it[Keys.scriptSpeechRateKey(script)] = rate }
    }

    suspend fun updateScriptSpeechPitch(script: String, pitch: Float) {
        context.dataStore.edit { it[Keys.scriptSpeechPitchKey(script)] = pitch }
    }

    suspend fun updateScriptSpeechVolume(script: String, volume: Float) {
        context.dataStore.edit { it[Keys.scriptSpeechVolumeKey(script)] = volume }
    }

    suspend fun updateEmojiVoice(voice: String) {
        context.dataStore.edit { it[Keys.EMOJI_VOICE] = voice }
    }

    suspend fun updateNumberVoice(voice: String) {
        context.dataStore.edit { it[Keys.NUMBER_VOICE] = voice }
    }

    suspend fun updateSelectedLanguages(languages: Set<String>) {
        context.dataStore.edit { it[Keys.SELECTED_LANGUAGES] = languages }
    }

    suspend fun updateScriptVoice(script: String, voiceId: String) {
        context.dataStore.edit { it[Keys.scriptVoiceKey(script)] = voiceId }
    }

    suspend fun updateScriptLanguage(script: String, langTag: String) {
        context.dataStore.edit { it[Keys.scriptLanguageKey(script)] = langTag }
    }
}
