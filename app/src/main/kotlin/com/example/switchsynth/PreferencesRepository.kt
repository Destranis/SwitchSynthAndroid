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
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val EMOJI_VOICE = stringPreferencesKey("emoji_voice")

        fun scriptVoiceKey(script: String) = stringPreferencesKey("voice_$script")
        fun scriptLanguageKey(script: String) = stringPreferencesKey("language_$script")
    }

    val useAccessibilityVolume: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[Keys.USE_ACCESSIBILITY_VOLUME] ?: true }

    val speechRate: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.SPEECH_RATE] ?: 1.0f }

    val speechPitch: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.SPEECH_PITCH] ?: 1.0f }

    val speechVolume: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[Keys.SPEECH_VOLUME] ?: 1.0f }

    val emojiVoice: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[Keys.EMOJI_VOICE] ?: "Latin" }

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

    suspend fun updateSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.SPEECH_RATE] = rate }
    }

    suspend fun updateSpeechPitch(pitch: Float) {
        context.dataStore.edit { it[Keys.SPEECH_PITCH] = pitch }
    }

    suspend fun updateSpeechVolume(volume: Float) {
        context.dataStore.edit { it[Keys.SPEECH_VOLUME] = volume }
    }

    suspend fun updateEmojiVoice(voice: String) {
        context.dataStore.edit { it[Keys.EMOJI_VOICE] = voice }
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
