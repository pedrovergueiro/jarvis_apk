package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Motor TTS com voz masculina humanizada em pt-BR.
 * Usa Android TTS nativo com ajustes de pitch e velocidade
 * para soar mais natural e menos robótico.
 */
class TTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "TTSEngine"
    }

    private var tts: TextToSpeech? = null
    private var isReady: Boolean = false
    private var onDoneCallback: (() -> Unit)? = null

    init {
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configurarVoz()
                isReady = true
                Log.d(TAG, "TTS pronto")
            } else {
                Log.e(TAG, "Falha ao iniciar TTS: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDoneCallback?.invoke()
                onDoneCallback = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Erro TTS utterance: $utteranceId")
                onDoneCallback?.invoke()
                onDoneCallback = null
            }
        })
    }

    private fun configurarVoz() {
        // Tentar voz masculina brasileira
        val vozes = tts?.voices ?: emptySet()

        val vozMasculina = vozes.firstOrNull { voz ->
            voz.locale.language == "pt" &&
            voz.locale.country == "BR" &&
            voz.name.contains("male", ignoreCase = true) &&
            !voz.name.contains("female", ignoreCase = true)
        } ?: vozes.firstOrNull { voz ->
            voz.locale.language == "pt" && voz.locale.country == "BR"
        }

        if (vozMasculina != null) {
            tts?.voice = vozMasculina
            Log.d(TAG, "Voz selecionada: ${vozMasculina.name}")
        } else {
            tts?.setLanguage(Locale("pt", "BR"))
        }

        // Tom mais grave e velocidade natural — menos robótico
        tts?.setPitch(0.85f)       // Mais grave (masculino)
        tts?.setSpeechRate(0.95f)  // Ligeiramente mais lento = mais natural
    }

    /**
     * Fala o texto. Se priority=true, interrompe fala atual.
     * onDone é chamado quando terminar de falar.
     */
    fun speak(text: String, priority: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!isReady) {
            Log.w(TAG, "TTS não está pronto ainda")
            onDone?.invoke()
            return
        }

        if (priority) {
            tts?.stop()
        }

        onDoneCallback = onDone

        val utteranceId = "jarvis_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        // Textos longos: dividir em sentenças para menor latência percebida
        if (text.length > 150) {
            val sentencas = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            sentencas.forEachIndexed { index, sentenca ->
                val uid = "${utteranceId}_$index"
                val p = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
                }
                val modo = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(sentenca, modo, p, uid)
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        onDoneCallback = null
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
