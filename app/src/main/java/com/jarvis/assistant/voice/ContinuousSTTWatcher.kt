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
 * SpeechRecognizer em modo contínuo para detecção de wake word.
 * Reinicia automaticamente após cada resultado ou timeout.
 */
class ContinuousSTTWatcher(
    private val context: Context,
    private val onText: (String) -> Unit
) {
    companion object {
        private const val TAG = "ContinuousSTTWatcher"
        private const val RESTART_DELAY_MS = 500L
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

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
                    // Reiniciar em qualquer erro (timeout, sem match, etc.)
                    if (isRunning) {
                        handler.postDelayed({ startListening() }, RESTART_DELAY_MS)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Ouviu: $text")
                        onText(text)
                    }
                    // Reiniciar para continuar ouvindo
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar STT: ${e.message}")
            if (isRunning) {
                handler.postDelayed({ startListening() }, 2000L)
            }
        }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}
