package com.example.switchsynth

import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.max
import java.util.Locale

class SwitchSynthService : TextToSpeechService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: PreferencesRepository
    private val engines = mutableMapOf<String, TextToSpeech>()
    @Volatile
    private var synthesisJob: Job? = null

    companion object {
        @Volatile
        var instance: SwitchSynthService? = null

        fun stopSpeech() {
            instance?.onStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = PreferencesRepository(this)
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
        synthesisJob?.cancel()
        engines.values.forEach { 
            try { it.stop() } catch (e: Exception) {}
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        val text = request.charSequenceText.toString()
        Log.d("SwitchSynth", "Synthesize: $text")

        // Immediate stop of everything
        synthesisJob?.cancel()
        engines.values.forEach {
            try { it.stop() } catch (e: Exception) {}
        }

        val job = scope.launch {
            try {
                val latinVoiceId = repository.latinVoice.first()
                val othersVoiceId = repository.othersVoice.first()
                val useAccVolume = repository.useAccessibilityVolume.first()
                val rate = repository.speechRate.first()
                val pitch = repository.speechPitch.first()
                val volume = repository.speechVolume.first()
                val emojiVoicePref = repository.emojiVoice.first()

                val segments = splitText(text, emojiVoicePref == "latin")

                callback.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

                for (segment in segments) {
                    if (!isActive) break
                    val voiceId = if (segment.isLatin) latinVoiceId else othersVoiceId
                    if (voiceId != null) {
                        synthesizeSegment(segment.text, voiceId, useAccVolume, rate, pitch, volume)
                    }
                }

                callback.done()
            } catch (e: CancellationException) {
                Log.d("SwitchSynth", "Synthesis job cancelled")
                throw e
            } catch (e: Exception) {
                Log.e("SwitchSynth", "Synthesis error", e)
                callback.error()
            }
        }
        synthesisJob = job

        // Block the synthesis thread so the TTS framework knows we're still speaking.
        // This lets onStop() work correctly when TalkBack calls stop().
        runBlocking { job.join() }
    }

    private enum class Script { LATIN, OTHER, NEUTRAL }

    private fun getScript(codePoint: Int, emojiAsLatin: Boolean): Script {
        if (isEmoji(codePoint)) {
            return if (emojiAsLatin) Script.LATIN else Script.OTHER
        }
        if (Character.isLetter(codePoint)) {
            // Basic Latin + Latin Supplement + Latin Extended
            return if (codePoint in 0x0000..0x024F) Script.LATIN else Script.OTHER
        }
        return Script.NEUTRAL
    }

    private fun isEmoji(codePoint: Int): Boolean {
        return (codePoint in 0x1F000..0x1FBFF) || // Most modern emojis, flags, and pictographs
                (codePoint in 0x2600..0x27BF) ||  // Misc symbols and Dingbats
                (codePoint in 0x2300..0x23FF) ||  // Misc Technical
                (codePoint in 0x2B00..0x2BFF) ||  // Misc Symbols and Arrows
                (codePoint in 0x2100..0x21FF) ||  // Letterlike Symbols, Arrows, etc.
                (codePoint == 0x203C || codePoint == 0x2049) // !! and !?
    }

    private fun splitText(text: String, emojiAsLatin: Boolean): List<TextSegment> {
        if (text.isEmpty()) return emptyList()
        val segments = mutableListOf<TextSegment>()
        var currentText = StringBuilder()
        
        val codePoints = text.codePoints().toArray()
        
        // Initial script discovery
        var currentScript = Script.LATIN
        for (cp in codePoints) {
            val s = getScript(cp, emojiAsLatin)
            if (s != Script.NEUTRAL) {
                currentScript = s
                break
            }
        }

        for (cp in codePoints) {
            val charScript = getScript(cp, emojiAsLatin)
            
            // NEUTRAL characters (spaces, punctuation) never trigger a split.
            // They are just appended to the current segment.
            if (charScript == Script.NEUTRAL || charScript == currentScript) {
                currentText.appendCodePoint(cp)
            } else {
                // Only split when we transition from one active script to another
                if (currentText.isNotEmpty()) {
                    segments.add(TextSegment(currentText.toString(), currentScript == Script.LATIN))
                }
                currentText = StringBuilder().appendCodePoint(cp)
                currentScript = charScript
            }
        }
        
        if (currentText.isNotEmpty()) {
            segments.add(TextSegment(currentText.toString(), currentScript == Script.LATIN))
        }
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
            override fun onStop(id: String?, interrupted: Boolean) { if (id == utteranceId) completionLock.complete(Unit) }
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
            // Scale timeout by text length: ~200ms per char, minimum 60s
            val timeoutMs = max(60_000L, text.length * 200L)
            withTimeout(timeoutMs) { completionLock.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w("SwitchSynth", "Timeout for segment ($utteranceId), moving on")
            internalTts.stop()
        } catch (e: CancellationException) {
            internalTts.stop()
            throw e
        } catch (e: Exception) {
            Log.e("SwitchSynth", "Error for $utteranceId", e)
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
        instance = null
        engines.values.forEach { it.shutdown() }
        super.onDestroy()
    }
}
