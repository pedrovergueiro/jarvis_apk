package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import android.media.MediaPlayer

/**
 * Motor TTS com dois modos:
 * 1. Android TTS nativo (offline, baixa latência)
 * 2. API de voz neural via Groq/OpenAI (alta qualidade)
 * Suporta barge-in (interrupção durante fala)
 */
class TTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "TTSEngine"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
    }

    private var androidTTS: TextToSpeech? = null
    private var isTTSReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()

    // Cache de respostas TTS para frases comuns
    private val ttsCache = mutableMapOf<String, File>()

    init {
        initAndroidTTS()
    }

    private fun initAndroidTTS() {
        androidTTS = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = androidTTS?.setLanguage(Locale("pt", "BR"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    androidTTS?.setLanguage(Locale.getDefault())
                }
                androidTTS?.setSpeechRate(1.1f)  // Ligeiramente mais rápido
                androidTTS?.setPitch(0.95f)       // Tom levemente mais grave (JARVIS)
                isTTSReady = true
                Log.d(TAG, "Android TTS pronto")
            }
        }

        androidTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS concluído: $utteranceId")
            }
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Erro TTS: $utteranceId")
            }
        })
    }

    /**
     * Fala o texto. Se priority=true, interrompe fala atual (barge-in).
     */
    fun speak(text: String, priority: Boolean = false) {
        if (priority) stopSpeaking()

        // Limitar tamanho para TTS nativo (mais rápido para textos curtos)
        if (text.length <= 200 && isTTSReady) {
            speakWithAndroidTTS(text)
        } else {
            // Textos longos: dividir em chunks e falar progressivamente
            speakInChunks(text)
        }
    }

    private fun speakWithAndroidTTS(text: String) {
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_${System.currentTimeMillis()}")
        }
        androidTTS?.speak(text, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
    }

    private fun speakInChunks(text: String) {
        // Dividir por sentenças para menor latência percebida
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        sentences.forEach { sentence ->
            if (sentence.isNotBlank() && isTTSReady) {
                val params = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "chunk_${System.currentTimeMillis()}")
                }
                androidTTS?.speak(
                    sentence,
                    TextToSpeech.QUEUE_ADD,
                    params,
                    params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)
                )
            }
        }
    }

    fun stopSpeaking() {
        androidTTS?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun isSpeaking(): Boolean {
        return androidTTS?.isSpeaking == true || mediaPlayer?.isPlaying == true
    }

    fun shutdown() {
        stopSpeaking()
        androidTTS?.shutdown()
        scope.cancel()
    }
}
