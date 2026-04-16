package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * SpeechRecognizer contínuo para wake word e comandos.
 * Reinicia automaticamente após cada resultado.
 */
class ContinuousSTTWatcher(
    private val context: Context,
    private val onText: (String) -> Unit
) {
    companion object {
        private const val TAG = "ContinuousSTTWatcher"
        private const val RESTART_DELAY_MS = 400L
        private const val ERROR_RESTART_DELAY_MS = 1000L
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning: Boolean = false

    fun start() {
        isRunning = true
        handler.post { startListening() }
    }

    private fun startListening() {
        if (!isRunning) return

        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer não disponível")
                return
            }

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isRunning) {
                        handler.postDelayed({ startListening() }, ERROR_RESTART_DELAY_MS)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Ouviu: $text")
                        onText(text)
                    }
                    if (isRunning) {
                        handler.postDelayed({ startListening() }, RESTART_DELAY_MS)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    if (partial.isNotBlank()) {
                        onText(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                // Aceitar múltiplos idiomas para cobrir sotaques
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar STT: ${e.message}")
            if (isRunning) {
                handler.postDelayed({ startListening() }, ERROR_RESTART_DELAY_MS * 2)
            }
        }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar: ${e.message}")
        }
        recognizer = null
    }
}
