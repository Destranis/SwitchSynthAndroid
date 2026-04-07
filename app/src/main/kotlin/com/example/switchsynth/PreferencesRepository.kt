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
        val LATIN_LANGUAGE = stringPreferencesKey("latin_language")
        val OTHERS_LANGUAGE = stringPreferencesKey("others_language")
        val LATIN_VOICE = stringPreferencesKey("latin_voice")
        val OTHERS_VOICE = stringPreferencesKey("others_voice")
        val USE_ACCESSIBILITY_VOLUME = booleanPreferencesKey("use_accessibility_volume")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val EMOJI_VOICE = stringPreferencesKey("emoji_voice")
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
        .map { preferences -> preferences[Keys.EMOJI_VOICE] ?: "latin" }

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

    val selectedLanguages: Flow<Set<String>> = context.dataStore.data
        .map { preferences -> preferences[Keys.SELECTED_LANGUAGES] ?: emptySet() }

    val latinLanguage: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.LATIN_LANGUAGE] }

    val othersLanguage: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.OTHERS_LANGUAGE] }

    val latinVoice: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.LATIN_VOICE] }

    val othersVoice: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[Keys.OTHERS_VOICE] }

    suspend fun updateSelectedLanguages(languages: Set<String>) {
        context.dataStore.edit { it[Keys.SELECTED_LANGUAGES] = languages }
    }

    suspend fun updateLatinLanguage(language: String) {
        context.dataStore.edit { it[Keys.LATIN_LANGUAGE] = language }
    }

    suspend fun updateOthersLanguage(language: String) {
        context.dataStore.edit { it[Keys.OTHERS_LANGUAGE] = language }
    }

    suspend fun updateLatinVoice(voiceId: String) {
        context.dataStore.edit { it[Keys.LATIN_VOICE] = voiceId }
    }

    suspend fun updateOthersVoice(voiceId: String) {
        context.dataStore.edit { it[Keys.OTHERS_VOICE] = voiceId }
    }
}
