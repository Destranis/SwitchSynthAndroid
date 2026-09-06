package com.example.switchsynth

import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.math.max
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwitchSynthService : TextToSpeechService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: PreferencesRepository
    // Accessed from both the settings-observer coroutine (warm-up) and the TTS
    // synthesis thread, so it must be concurrent-safe.
    private val engines = java.util.concurrent.ConcurrentHashMap<String, TextToSpeech>()

    @Volatile private var stopped = false

    // --- Cached settings (updated in background, read instantly during synthesis) ---
    @Volatile private var cachedUseAccVolume = true
    @Volatile private var cachedEmojiScript = "Latin"
    // "Common" = follow the surrounding text (no dedicated number voice).
    @Volatile private var cachedNumberScript = "Common"
    @Volatile private var cachedScriptVoices = emptyMap<String, String?>()
    @Volatile private var cachedScriptRates = emptyMap<String, Float>()
    @Volatile private var cachedScriptPitches = emptyMap<String, Float>()
    @Volatile private var cachedScriptVolumes = emptyMap<String, Float>()
    @Volatile private var settingsReady = false

    // Track what voice/rate/pitch is currently set on each engine to avoid
    // redundant IPC calls (each set* is a binder round-trip to the child engine).
    private val engineCurrentVoice = mutableMapOf<String, String>()
    private val engineCurrentRate = mutableMapOf<String, Float>()
    private val engineCurrentPitch = mutableMapOf<String, Float>()

    // Whether we are currently producing speech. Read by the accessibility
    // helper so an idle screen tap costs nothing (no engine IPC storm).
    @Volatile var isSpeaking = false
        private set

    // Name of the engine that last played audio, so a new request only needs to
    // stop that one instead of every loaded engine.
    @Volatile private var lastActiveEngineName: String? = null

    // eSpeak package names to check for fallback
    private val espeakPackages = listOf(
        "com.reecedunn.espeak",
        "com.googlecode.eyesfree.espeak"
    )

    // Cached eSpeak availability (null = not yet checked)
    @Volatile private var espeakPackage: String? = null
    @Volatile private var espeakChecked = false

    // Track pending utterance completions
    private val pendingUtterances = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()

    // Reusable audio attributes
    private val accessibilityAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Shared listener for all engines
    private val sharedListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { id?.let { pendingUtterances.remove(it)?.countDown() } }
        override fun onError(id: String?) { id?.let { pendingUtterances.remove(it)?.countDown() } }
        override fun onStop(id: String?, interrupted: Boolean) { id?.let { pendingUtterances.remove(it)?.countDown() } }
    }

    companion object {
        @Volatile
        var instance: SwitchSynthService? = null

        fun stopSpeech() {
            val inst = instance ?: return
            // Fast path: nothing is playing, so a screen tap does no work.
            if (!inst.isSpeaking) return
            // Never run the engine stop IPCs on the caller's thread (the
            // accessibility service main thread) — that is what froze the helper.
            inst.scope.launch { inst.onStop() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = PreferencesRepository(this)
        startSettingsObserver()
    }

    private fun startSettingsObserver() {
        scope.launch {
            combine(
                repository.useAccessibilityVolume,
                repository.emojiVoice,
                repository.numberVoice
            ) { useAcc, emoji, number ->
                cachedUseAccVolume = useAcc
                cachedEmojiScript = emoji
                cachedNumberScript = number
            }.collect()
        }

        scope.launch {
            repository.selectedLanguages.collect { selectedLangs ->
                val activeScripts = UnicodeScripts.getActiveScripts(selectedLangs)
                if (activeScripts.isEmpty()) {
                    cachedScriptVoices = emptyMap()
                    cachedScriptRates = emptyMap()
                    cachedScriptPitches = emptyMap()
                    cachedScriptVolumes = emptyMap()
                    settingsReady = true
                    return@collect
                }
                combine(
                    repository.allScriptVoices(activeScripts),
                    repository.allScriptSpeechRates(activeScripts),
                    repository.allScriptSpeechPitches(activeScripts),
                    repository.allScriptSpeechVolumes(activeScripts)
                ) { voices, rates, pitches, volumes ->
                    cachedScriptVoices = voices
                    cachedScriptRates = rates
                    cachedScriptPitches = pitches
                    cachedScriptVolumes = volumes
                    settingsReady = true

                    for (voiceId in voices.values) {
                        if (voiceId != null) {
                            val engineName = voiceId.substringBefore(":", "")
                            if (engineName.isNotEmpty() && !engines.containsKey(engineName)) {
                                launch { getEngine(engineName) }
                            }
                        }
                    }

                    // Warm up the eSpeak fallback engine too if any active script
                    // has no configured voice — its cold init is the slowest and
                    // would otherwise land on the first real utterance.
                    if (voices.any { it.value == null }) {
                        getInstalledEspeakPackage()?.let { pkg ->
                            if (!engines.containsKey(pkg)) launch { getEngine(pkg) }
                        }
                    }
                }.collect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.switchsynth.ACTION_STOP") {
            Log.d("SwitchSynth", "ACTION_STOP received")
            onStop()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onStop() {
        Log.d("SwitchSynth", "onStop() called")
        stopped = true
        isSpeaking = false
        // Release all pending waits
        for ((_, latch) in pendingUtterances) {
            latch.countDown()
        }
        pendingUtterances.clear()
        engines.values.forEach {
            try { it.stop() } catch (e: Exception) {}
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        val text = request.charSequenceText.toString()
        Log.d("SwitchSynth", "Synthesize: $text")

        stopped = false
        // Only the engine that last played can still be talking — stop just that
        // one instead of an IPC to every loaded engine on every utterance.
        lastActiveEngineName?.let { name ->
            try { engines[name]?.stop() } catch (e: Exception) {}
        }

        // Ensure settings are loaded (only blocks on very first call ever)
        if (!settingsReady) {
            runBlocking {
                val selectedLangs = repository.selectedLanguages.first()
                val activeScripts = UnicodeScripts.getActiveScripts(selectedLangs)
                val voices = mutableMapOf<String, String?>()
                for (script in activeScripts) {
                    voices[script] = repository.scriptVoice(script).first()
                }
                cachedScriptVoices = voices
                val rates = mutableMapOf<String, Float>()
                val pitches = mutableMapOf<String, Float>()
                val vols = mutableMapOf<String, Float>()
                for (script in activeScripts) {
                    rates[script] = repository.scriptSpeechRate(script).first()
                    pitches[script] = repository.scriptSpeechPitch(script).first()
                    vols[script] = repository.scriptSpeechVolume(script).first()
                }
                cachedScriptRates = rates
                cachedScriptPitches = pitches
                cachedScriptVolumes = vols
                cachedUseAccVolume = repository.useAccessibilityVolume.first()
                cachedEmojiScript = repository.emojiVoice.first()
                cachedNumberScript = repository.numberVoice.first()
                settingsReady = true
            }
        }

        // Read cached settings — no disk I/O
        val useAccVolume = cachedUseAccVolume
        val emojiScript = cachedEmojiScript
        val numberScript = cachedNumberScript
        val scriptVoices = cachedScriptVoices
        val scriptRates = cachedScriptRates
        val scriptPitches = cachedScriptPitches
        val scriptVolumes = cachedScriptVolumes

        val segments = splitText(text, emojiScript, numberScript)

        // Run directly on the TTS synthesis thread — no coroutine overhead
        isSpeaking = true
        try {
            callback.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

            for (segment in segments) {
                if (stopped) break
                val voiceId = scriptVoices[segment.script]
                    ?: getEspeakFallbackVoiceId(segment.script)
                    ?: scriptVoices["Latin"]
                    ?: scriptVoices.values.firstOrNull { it != null }
                val rate = scriptRates[segment.script] ?: 1.0f
                val pitch = scriptPitches[segment.script] ?: 1.0f
                val volume = scriptVolumes[segment.script] ?: 1.0f
                if (voiceId != null) {
                    synthesizeSegment(segment.text, voiceId, useAccVolume, rate, pitch, volume)
                }
            }

            callback.done()
        } catch (e: Exception) {
            Log.e("SwitchSynth", "Synthesis error", e)
            callback.error()
        } finally {
            isSpeaking = false
        }
    }

    private fun getScriptForCodePoint(codePoint: Int, emojiScript: String, numberScript: String): String {
        if (isEmoji(codePoint)) {
            return emojiScript
        }
        val base = UnicodeScripts.getScriptName(codePoint)
        // Route script-neutral digits (0-9, fullwidth digits, …) to the chosen
        // number voice. "Common" means "follow surrounding text" — leave as-is.
        // Script-specific digits (Arabic-Indic, Devanagari, …) keep their own script.
        if (numberScript != "Common" && base == "Common" && Character.isDigit(codePoint)) {
            return numberScript
        }
        return base
    }

    private fun isEmoji(codePoint: Int): Boolean {
        return (codePoint in 0x1F000..0x1FBFF) ||
                (codePoint in 0x2600..0x27BF) ||
                (codePoint in 0x2300..0x23FF) ||
                (codePoint in 0x2B00..0x2BFF) ||
                (codePoint in 0x2100..0x21FF) ||
                (codePoint == 0x203C || codePoint == 0x2049)
    }

    private fun splitText(text: String, emojiScript: String, numberScript: String): List<TextSegment> {
        if (text.isEmpty()) return emptyList()
        val segments = mutableListOf<TextSegment>()
        var currentText = StringBuilder()

        val codePoints = text.codePoints().toArray()

        var currentScript = "Latin"
        for (cp in codePoints) {
            val s = getScriptForCodePoint(cp, emojiScript, numberScript)
            if (s != "Common") {
                currentScript = s
                break
            }
        }

        for (cp in codePoints) {
            val charScript = getScriptForCodePoint(cp, emojiScript, numberScript)

            if (charScript == "Common" || charScript == currentScript) {
                currentText.appendCodePoint(cp)
            } else {
                if (currentText.isNotEmpty()) {
                    segments.add(TextSegment(currentText.toString(), currentScript))
                }
                currentText = StringBuilder().appendCodePoint(cp)
                currentScript = charScript
            }
        }

        if (currentText.isNotEmpty()) {
            segments.add(TextSegment(currentText.toString(), currentScript))
        }
        return segments
    }

    private fun synthesizeSegment(text: String, voiceId: String, useAccVolume: Boolean, rate: Float, pitch: Float, volume: Float) {
        val parts = voiceId.split(":", limit = 2)
        if (parts.size < 2) return

        val engineName = parts[0]
        val voiceName = parts[1]

        val internalTts = getEngine(engineName) ?: return

        val utteranceId = "utt_${System.nanoTime()}"
        val latch = CountDownLatch(1)
        pendingUtterances[utteranceId] = latch

        // Only push rate/pitch if they changed since last time on this engine
        if (engineCurrentRate[engineName] != rate) {
            internalTts.setSpeechRate(rate)
            engineCurrentRate[engineName] = rate
        }
        if (engineCurrentPitch[engineName] != pitch) {
            internalTts.setPitch(pitch)
            engineCurrentPitch[engineName] = pitch
        }

        // Only set voice/language if it changed since last time on this engine
        val lastVoice = engineCurrentVoice[engineName]
        if (lastVoice != voiceName) {
            if (voiceName.startsWith("default_")) {
                val langTag = voiceName.substringAfter("default_")
                internalTts.language = Locale.forLanguageTag(langTag)
            } else {
                internalTts.voices?.find { it.name == voiceName }?.let {
                    internalTts.language = it.locale
                    internalTts.voice = it
                }
            }
            engineCurrentVoice[engineName] = voiceName
        }

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        if (useAccVolume) {
            internalTts.setAudioAttributes(accessibilityAudioAttributes)
        }

        lastActiveEngineName = engineName
        internalTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

        // Block until utterance completes — no coroutine overhead
        val timeoutMs = max(60_000L, text.length * 200L)
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w("SwitchSynth", "Timeout for segment ($utteranceId), moving on")
                internalTts.stop()
            }
        } catch (e: InterruptedException) {
            internalTts.stop()
        }
        pendingUtterances.remove(utteranceId)
    }

    private fun getEngine(engineName: String): TextToSpeech? {
        if (engines.containsKey(engineName)) return engines[engineName]

        Log.d("SwitchSynth", "Loading engine: $engineName")
        var tts: TextToSpeech? = null
        val syncObj = Object()
        var initialized = false
        var initStatus = TextToSpeech.ERROR

        tts = TextToSpeech(this, { status ->
            synchronized(syncObj) {
                initialized = true
                initStatus = status
                syncObj.notifyAll()
            }
        }, engineName)

        synchronized(syncObj) {
            val startTime = System.currentTimeMillis()
            while (!initialized && (System.currentTimeMillis() - startTime) < 5000) {
                try {
                    syncObj.wait(1000)
                } catch (e: Exception) { break }
            }
        }

        return if (initialized && initStatus == TextToSpeech.SUCCESS) {
            Log.d("SwitchSynth", "Successfully loaded engine: $engineName")
            tts.setOnUtteranceProgressListener(sharedListener)
            engines[engineName] = tts
            tts
        } else {
            Log.e("SwitchSynth", "Failed to load engine: $engineName (status: $initStatus)")
            tts?.shutdown()
            null
        }
    }

    private fun getInstalledEspeakPackage(): String? {
        if (espeakChecked) return espeakPackage
        val pm = packageManager
        for (pkg in espeakPackages) {
            try {
                pm.getApplicationInfo(pkg, 0)
                espeakPackage = pkg
                espeakChecked = true
                return pkg
            } catch (e: Exception) { }
        }
        espeakChecked = true
        espeakPackage = null
        return null
    }

    /**
     * Build an eSpeak fallback voice ID for a given script.
     * Uses the script's default language to pick a locale for eSpeak.
     */
    private fun getEspeakFallbackVoiceId(script: String): String? {
        val pkg = getInstalledEspeakPackage() ?: return null
        val langTag = UnicodeScripts.SCRIPT_DEFAULT_LANGUAGE[script] ?: return null
        return "$pkg:default_$langTag"
    }

    data class TextSegment(val text: String, val script: String)

    override fun onDestroy() {
        instance = null
        scope.cancel()
        engines.values.forEach { it.shutdown() }
        super.onDestroy()
    }
}
