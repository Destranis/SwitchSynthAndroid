package com.example.switchsynth

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class UiState(
    val availableLocales: List<Locale> = emptyList(),
    val selectedLanguages: Set<String> = emptySet(),
    val latinLanguage: String? = null,
    val othersLanguage: String? = null,
    val availableVoices: List<VoiceInfo> = emptyList(),
    val latinVoiceId: String? = null,
    val othersVoiceId: String? = null,
    val useAccessibilityVolume: Boolean = true,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val speechVolume: Float = 1.0f,
    val emojiVoice: String = "latin"
)

data class VoiceInfo(
    val id: String,
    val name: String,
    val locale: Locale,
    val engine: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PreferencesRepository(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val allDiscoveredVoices = mutableListOf<VoiceInfo>()
    private val allDiscoveredLocales = mutableSetOf<Locale>()

    init {
        viewModelScope.launch {
            discoverEngines()
        }

        viewModelScope.launch {
            combine(
                listOf(
                    repository.selectedLanguages,
                    repository.latinLanguage,
                    repository.othersLanguage,
                    repository.latinVoice,
                    repository.othersVoice,
                    repository.useAccessibilityVolume,
                    repository.speechRate,
                    repository.speechPitch,
                    repository.speechVolume,
                    repository.emojiVoice
                )
            ) { args ->
                _uiState.update { 
                    it.copy(
                        selectedLanguages = args[0] as Set<String>,
                        latinLanguage = args[1] as String?,
                        othersLanguage = args[2] as String?,
                        latinVoiceId = args[3] as String?,
                        othersVoiceId = args[4] as String?,
                        useAccessibilityVolume = args[5] as Boolean,
                        speechRate = args[6] as Float,
                        speechPitch = args[7] as Float,
                        speechVolume = args[8] as Float,
                        emojiVoice = args[9] as String
                    )
                }
            }.collect()
        }
    }

    private suspend fun discoverEngines() {
        val application = getApplication<Application>()
        val engineMap = mutableMapOf<String, String>() // Package to Label
        val pm = application.packageManager
        
        Log.d("SwitchSynth", "Starting robust engine discovery...")

        // 1. System-wide TTS Discovery
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        
        resolveInfos.forEach { 
            val packageName = it.serviceInfo.packageName
            val label = it.loadLabel(pm).toString()
            engineMap[packageName] = label
        }

        // 2. Explicit checks for well-known engines
        val commonPackages = listOf(
            "com.google.android.tts",
            "com.googlecode.eyesfree.espeak",
            "com.reecedunn.espeak",
            "com.github.olga_yakovleva.rhvoice",
            "com.nuance.vocalizer",
            "com.nuance.vocalizer.v2",
            "com.code_factory.vocalizer",
            "com.autotts"
        )
        for (pkg in commonPackages) {
            if (!engineMap.containsKey(pkg)) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(ai).toString()
                    engineMap[pkg] = label
                } catch (e: Exception) { }
            }
        }

        // 3. Fallback: Check System API
        try {
            val tempInitLock = CompletableDeferred<Int>()
            val tempTts = TextToSpeech(application) { status -> tempInitLock.complete(status) }
            withTimeoutOrNull(2000) { tempInitLock.await() }
            tempTts.engines.forEach {
                if (!engineMap.containsKey(it.name)) {
                    engineMap[it.name] = it.label
                }
            }
            tempTts.shutdown()
        } catch (e: Exception) { }

        allDiscoveredVoices.clear()
        allDiscoveredLocales.clear()

        // 4. Sequential Querying
        for ((packageName, label) in engineMap) {
            queryEngine(packageName, label)
            updateUiWithDiscoveredData()
            delay(300) 
        }
    }

    private suspend fun queryEngine(packageName: String, label: String) {
        val initLock = CompletableDeferred<Int>()
        var tts: TextToSpeech? = null
        
        try {
            tts = TextToSpeech(getApplication(), { status ->
                initLock.complete(status)
            }, packageName)

            val status = withTimeoutOrNull(10000) { initLock.await() }
            
            if (status == TextToSpeech.SUCCESS && tts != null) {
                // Fetch ALL original locales
                val locales = tts.availableLanguages ?: emptySet()
                allDiscoveredLocales.addAll(locales)

                // Fetch voices
                val voices = try { tts.voices ?: emptySet() } catch (e: Exception) { emptySet() }
                
                val application = getApplication<Application>()
                if (voices.isNotEmpty()) {
                    voices.forEach { voice ->
                        allDiscoveredVoices.add(VoiceInfo(
                            id = "${packageName}:${voice.name}",
                            name = "$label - ${voice.name}",
                            locale = voice.locale,
                            engine = packageName
                        ))
                    }
                } else {
                    locales.forEach { locale ->
                        allDiscoveredVoices.add(VoiceInfo(
                            id = "${packageName}:default_${locale.toLanguageTag()}",
                            name = application.getString(R.string.label_default_voice, label, locale.displayName),
                            locale = locale,
                            engine = packageName
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SwitchSynth", "Error querying $packageName", e)
        } finally {
            try { tts?.shutdown() } catch (e: Exception) {}
        }
    }

    private fun updateUiWithDiscoveredData() {
        _uiState.update { 
            val grouped = allDiscoveredLocales.groupBy { getStableLanguageName(it) }
            val representativeLocales = grouped.map { (_, locales) ->
                val base = locales.first()
                // Special handling to prefer RU for Russian, etc.
                if (base.language == "ru") Locale("ru", "RU")
                else if (base.language == "hu") Locale("hu", "HU")
                else Locale(base.language)
            }.sortedBy { getDisplayName(it) }
            
            it.copy(
                availableLocales = representativeLocales,
                availableVoices = allDiscoveredVoices.toList().sortedBy { it.name }
            )
        }
    }

    fun getDisplayName(locale: Locale): String {
        return locale.getDisplayName(Locale.getDefault())
    }

    // Helper to get a stable language name regardless of variant or system locale
    fun getStableLanguageName(locale: Locale): String {
        return locale.getDisplayName(Locale.US).substringBefore(" (")
    }

    fun setUseAccessibilityVolume(enabled: Boolean) {
        viewModelScope.launch { repository.updateUseAccessibilityVolume(enabled) }
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { repository.updateSpeechRate(rate) }
    }

    fun setSpeechPitch(pitch: Float) {
        viewModelScope.launch { repository.updateSpeechPitch(pitch) }
    }

    fun setSpeechVolume(volume: Float) {
        viewModelScope.launch { repository.updateSpeechVolume(volume) }
    }

    fun toggleLanguage(language: String) {
        viewModelScope.launch {
            val current = _uiState.value.selectedLanguages.toMutableSet()
            if (current.contains(language)) current.remove(language) else current.add(language)
            repository.updateSelectedLanguages(current)
        }
    }

    fun selectAllLanguages() {
        viewModelScope.launch {
            val all = _uiState.value.availableLocales.map { it.toLanguageTag() }.toSet()
            repository.updateSelectedLanguages(all)
        }
    }

    fun deselectAllLanguages() {
        viewModelScope.launch { repository.updateSelectedLanguages(emptySet()) }
    }

    fun setLatinLanguage(language: String) {
        viewModelScope.launch { repository.updateLatinLanguage(language) }
    }

    fun setOthersLanguage(language: String) {
        viewModelScope.launch { repository.updateOthersLanguage(language) }
    }

    fun setLatinVoice(voiceId: String) {
        viewModelScope.launch { repository.updateLatinVoice(voiceId) }
    }

    fun setOthersVoice(voiceId: String) {
        viewModelScope.launch { repository.updateOthersVoice(voiceId) }
    }

    fun setEmojiVoice(voice: String) {
        viewModelScope.launch { repository.updateEmojiVoice(voice) }
    }
}
