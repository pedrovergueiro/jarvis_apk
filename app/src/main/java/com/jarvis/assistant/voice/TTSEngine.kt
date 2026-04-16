package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "TTSEngine"
    }

    private var tts: TextToSpeech? = null
    private var isReady: Boolean = false
    private var onDoneCallback: (() -> Unit)? = null

    init { initTTS() }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configurarVoz()
                isReady = true
                Log.d(TAG, "TTS pronto")
            } else {
                Log.e(TAG, "Falha TTS: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Bloquear microfone quando TTS começa a falar
                MicrophoneLock.lock()
                Log.d(TAG, "Mic bloqueado — TTS falando")
            }

            override fun onDone(utteranceId: String?) {
                // Só desbloquear quando TODA a fila terminar
                if (tts?.isSpeaking == false) {
                    MicrophoneLock.unlock()
                    Log.d(TAG, "Mic liberado — TTS terminou")
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                MicrophoneLock.unlock()
                Log.e(TAG, "Erro TTS")
                onDoneCallback?.invoke()
                onDoneCallback = null
            }
        })
    }

    private fun configurarVoz() {
        val vozes = tts?.voices ?: emptySet()
        val vozFeminina = vozes.firstOrNull { v ->
            v.locale.language == "pt" && v.locale.country == "BR" &&
            (v.name.contains("female", ignoreCase = true) ||
             v.name.contains("feminina", ignoreCase = true))
        } ?: vozes.firstOrNull { v ->
            v.locale.language == "pt" && v.locale.country == "BR"
        }

        if (vozFeminina != null) {
            tts?.voice = vozFeminina
            Log.d(TAG, "Voz: ${vozFeminina.name}")
        } else {
            tts?.setLanguage(Locale("pt", "BR"))
        }

        tts?.setPitch(1.1f)
        tts?.setSpeechRate(1.0f)
    }

    fun speak(text: String, priority: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!isReady) {
            Log.w(TAG, "TTS não pronto")
            onDone?.invoke()
            return
        }

        if (priority) {
            tts?.stop()
            MicrophoneLock.unlock() // Resetar antes de nova fala
        }

        onDoneCallback = onDone

        val uid = "natiele_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }

        if (text.length > 150) {
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            sentences.forEachIndexed { i, s ->
                val id = "${uid}_$i"
                val p = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                }
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(s, mode, p, id)
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        MicrophoneLock.unlock()
        onDoneCallback = null
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true || MicrophoneLock.isLocked()

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        MicrophoneLock.unlock()
        tts = null
    }
}
