package com.example.switchsynth

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale

class SwitchSynthService : TextToSpeechService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: PreferencesRepository
    private val engines = mutableMapOf<String, TextToSpeech>()
    private var synthesisJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = PreferencesRepository(this)
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
        synthesisJob?.cancel()
        engines.values.forEach { it.stop() }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        val text = request.charSequenceText.toString()
        
        // Immediate stop of everything
        synthesisJob?.cancel()
        engines.values.forEach { it.stop() }

        synthesisJob = scope.launch {
            val latinVoiceId = repository.latinVoice.first()
            val othersVoiceId = repository.othersVoice.first()
            val useAccVolume = repository.useAccessibilityVolume.first()
            val rate = repository.speechRate.first()
            val pitch = repository.speechPitch.first()
            val volume = repository.speechVolume.first()
            
            val segments = splitText(text)
            
            callback.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

            for (segment in segments) {
                if (!isActive) break
                val voiceId = if (segment.isLatin) latinVoiceId else othersVoiceId
                if (voiceId != null) {
                    synthesizeSegment(segment.text, voiceId, useAccVolume, rate, pitch, volume)
                }
            }
            
            callback.done()
        }
    }

    private enum class Script { LATIN, OTHER, NEUTRAL }

    private fun getScript(char: Char): Script {
        if (!char.isLetter()) return Script.NEUTRAL
        val cp = char.code
        return if (cp in 0x0000..0x024F) Script.LATIN else Script.OTHER
    }

    private fun splitText(text: String): List<TextSegment> {
        if (text.isEmpty()) return emptyList()
        val segments = mutableListOf<TextSegment>()
        var currentText = StringBuilder()
        
        var firstScript = Script.NEUTRAL
        for (char in text) {
            val s = getScript(char)
            if (s != Script.NEUTRAL) {
                firstScript = s
                break
            }
        }
        val startingScript = if (firstScript == Script.NEUTRAL) Script.LATIN else firstScript
        var currentScript = startingScript

        for (char in text) {
            val charScript = getScript(char)
            if (charScript == Script.NEUTRAL || charScript == currentScript) {
                currentText.append(char)
            } else {
                segments.add(TextSegment(currentText.toString(), currentScript == Script.LATIN))
                currentText = StringBuilder().append(char)
                currentScript = charScript
            }
        }
        segments.add(TextSegment(currentText.toString(), currentScript == Script.LATIN))
        return segments
    }

    private suspend fun synthesizeSegment(text: String, voiceId: String, useAccVolume: Boolean, rate: Float, pitch: Float, volume: Float) {
        val parts = voiceId.split(":", limit = 2)
        if (parts.size < 2) return
        
        val engineName = parts[0]
        val voiceName = parts[1]
        
        val internalTts = getEngine(engineName) ?: return
        
        val completionLock = CompletableDeferred<Unit>()
        val utteranceId = "utt_${System.currentTimeMillis()}"

        internalTts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == utteranceId) completionLock.complete(Unit) }
            override fun onError(id: String?) { if (id == utteranceId) completionLock.complete(Unit) }
        })

        // Apply settings every time to be sure
        internalTts.setSpeechRate(rate)
        internalTts.setPitch(pitch)
        
        if (voiceName.startsWith("default_")) {
            val langTag = voiceName.substringAfter("default_")
            internalTts.language = Locale.forLanguageTag(langTag)
        } else {
            internalTts.voices?.find { it.name == voiceName }?.let {
                // Set both language and voice for best compatibility
                internalTts.language = it.locale
                internalTts.voice = it
            }
        }

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        if (useAccVolume) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            internalTts.setAudioAttributes(audioAttributes)
        }

        internalTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        
        try {
            withTimeout(15000) { completionLock.await() }
        } catch (e: Exception) {
            Log.e("SwitchSynth", "Timeout for $utteranceId")
        }
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
            engines[engineName] = tts
            tts
        } else {
            Log.e("SwitchSynth", "Failed to load engine: $engineName (status: $initStatus)")
            tts?.shutdown()
            null
        }
    }

    data class TextSegment(val text: String, val isLatin: Boolean)

    override fun onDestroy() {
        engines.values.forEach { it.shutdown() }
        super.onDestroy()
    }
}
