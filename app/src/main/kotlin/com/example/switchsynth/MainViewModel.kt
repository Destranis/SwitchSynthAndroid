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
    val activeScripts: List<String> = emptyList(),
    val scriptLanguages: Map<String, String?> = emptyMap(),
    val scriptVoices: Map<String, String?> = emptyMap(),
    val availableVoices: List<VoiceInfo> = emptyList(),
    val useAccessibilityVolume: Boolean = true,
    val scriptSpeechRates: Map<String, Float> = emptyMap(),
    val scriptSpeechPitches: Map<String, Float> = emptyMap(),
    val scriptSpeechVolumes: Map<String, Float> = emptyMap(),
    val emojiVoice: String = "Latin",
    val numberVoice: String = "Common"
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

        // Observe selected languages + basic settings
        viewModelScope.launch {
            combine(
                listOf(
                    repository.selectedLanguages,
                    repository.useAccessibilityVolume,
                    repository.emojiVoice,
                    repository.numberVoice
                )
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val selectedLangs = args[0] as Set<String>
                val useAccVol = args[1] as Boolean
                val emojiVoice = args[2] as String
                val numberVoice = args[3] as String
                val activeScripts = UnicodeScripts.getActiveScripts(selectedLangs)
                _uiState.update {
                    it.copy(
                        selectedLanguages = selectedLangs,
                        activeScripts = activeScripts,
                        useAccessibilityVolume = useAccVol,
                        emojiVoice = emojiVoice,
                        numberVoice = numberVoice
                    )
                }
                activeScripts
            }.collectLatest { activeScripts ->
                // Now observe per-script voices, languages, and speech params
                if (activeScripts.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            scriptLanguages = emptyMap(),
                            scriptVoices = emptyMap(),
                            scriptSpeechRates = emptyMap(),
                            scriptSpeechPitches = emptyMap(),
                            scriptSpeechVolumes = emptyMap()
                        )
                    }
                    return@collectLatest
                }
                val selectedLangs = _uiState.value.selectedLanguages
                combine(
                    repository.allScriptLanguages(activeScripts),
                    repository.allScriptVoices(activeScripts),
                    repository.allScriptSpeechRates(activeScripts),
                    repository.allScriptSpeechPitches(activeScripts),
                    repository.allScriptSpeechVolumes(activeScripts)
                ) { scriptLangs, scriptVoices, scriptRates, scriptPitches, scriptVolumes ->
                    // Auto-assign script language when unset or invalid
                    for (script in activeScripts) {
                        val currentLang = scriptLangs[script]
                        val langsForScript = UnicodeScripts.getLanguagesForScript(script, selectedLangs)
                        if (currentLang == null || currentLang !in langsForScript) {
                            val autoLang = langsForScript.firstOrNull()
                            if (autoLang != null) {
                                repository.updateScriptLanguage(script, autoLang)
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            scriptLanguages = scriptLangs,
                            scriptVoices = scriptVoices,
                            scriptSpeechRates = scriptRates,
                            scriptSpeechPitches = scriptPitches,
                            scriptSpeechVolumes = scriptVolumes
                        )
                    }
                }.collect()
            }
        }
    }

    private suspend fun discoverEngines() {
        val application = getApplication<Application>()
        val engineMap = mutableMapOf<String, String>()
        val pm = application.packageManager

        Log.d("SwitchSynth", "Starting robust engine discovery...")

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

            if (status == TextToSpeech.SUCCESS) {
                val locales = tts.availableLanguages ?: emptySet()
                allDiscoveredLocales.addAll(locales)

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

    fun getStableLanguageName(locale: Locale): String {
        return locale.getDisplayName(Locale.US).substringBefore(" (")
    }

    fun setUseAccessibilityVolume(enabled: Boolean) {
        viewModelScope.launch { repository.updateUseAccessibilityVolume(enabled) }
    }

    fun setScriptSpeechRate(script: String, rate: Float) {
        viewModelScope.launch { repository.updateScriptSpeechRate(script, rate) }
    }

    fun setScriptSpeechPitch(script: String, pitch: Float) {
        viewModelScope.launch { repository.updateScriptSpeechPitch(script, pitch) }
    }

    fun setScriptSpeechVolume(script: String, volume: Float) {
        viewModelScope.launch { repository.updateScriptSpeechVolume(script, volume) }
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

    fun setScriptLanguage(script: String, langTag: String) {
        viewModelScope.launch { repository.updateScriptLanguage(script, langTag) }
    }

    fun setScriptVoice(script: String, voiceId: String) {
        viewModelScope.launch { repository.updateScriptVoice(script, voiceId) }
    }

    fun setEmojiVoice(voice: String) {
        viewModelScope.launch { repository.updateEmojiVoice(voice) }
    }

    fun setNumberVoice(voice: String) {
        viewModelScope.launch { repository.updateNumberVoice(voice) }
    }
}
