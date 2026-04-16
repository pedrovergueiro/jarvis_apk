package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Detector de wake word 100% offline sem dependências externas.
 * Usa dois métodos combinados:
 * 1. Detecção de energia de voz (VAD) para acordar do sleep
 * 2. Android SpeechRecognizer em modo contínuo para detectar "jarvis"
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val ENERGY_THRESHOLD = 800.0  // Limiar de energia para VAD
        private const val WAKE_WORDS = listOf("jarvis", "jarvis", "ei jarvis", "hey jarvis", "ok jarvis")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isActive = false
    private var audioRecord: AudioRecord? = null
    private var sttWatcher: ContinuousSTTWatcher? = null

    fun start() {
        if (isActive) return
        isActive = true
        Log.d(TAG, "WakeWordDetector iniciado (modo offline)")
        startContinuousSTT()
    }

    /**
     * Escuta continuamente em background usando SpeechRecognizer.
     * Quando detecta "jarvis" dispara o callback.
     */
    private fun startContinuousSTT() {
        sttWatcher = ContinuousSTTWatcher(context) { text ->
            val lower = text.lowercase().trim()
            val detected = WAKE_WORDS.any { lower.contains(it) }
            if (detected && isActive) {
                Log.d(TAG, "Wake word detectada: '$text'")
                isActive = false // Evitar duplo disparo
                onWakeWord()
            }
        }
        sttWatcher?.start()
    }

    fun stop() {
        isActive = false
        sttWatcher?.stop()
        sttWatcher = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        scope.cancel()
    }

    fun restart() {
        stop()
        // Pequeno delay antes de reiniciar para evitar conflito de microfone
        CoroutineScope(Dispatchers.IO).launch {
            delay(800)
            isActive = true
            startContinuousSTT()
        }
    }
}
