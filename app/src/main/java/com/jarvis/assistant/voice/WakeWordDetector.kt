package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Detector de wake word 100% offline.
 * Reconhece "jarvis" em múltiplos sotaques e variações.
 * Após 5 minutos sem interação, volta ao modo de espera.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos

        // Variações fonéticas de "Jarvis" para cobrir sotaques diferentes
        private val WAKE_WORDS = listOf(
            "jarvis", "jávis", "járvis", "jarves", "jarwis",
            "ei jarvis", "hey jarvis", "ok jarvis", "oi jarvis",
            "e jarvis", "a jarvis", "o jarvis",
            "jarvi", "jarbi", "zarvis", "harvis"
        )
    }

    private var isActive: Boolean = false
    private var sttWatcher: ContinuousSTTWatcher? = null
    private var lastInteractionTime: Long = System.currentTimeMillis()
    private var idleCheckJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (isActive) return
        isActive = true
        lastInteractionTime = System.currentTimeMillis()
        Log.d(TAG, "WakeWordDetector iniciado")
        startContinuousSTT()
        startIdleCheck()
    }

    private fun startContinuousSTT() {
        sttWatcher = ContinuousSTTWatcher(context) { text ->
            val lower = text.lowercase().trim()
            val found = WAKE_WORDS.any { lower.contains(it) }
            if (found && isActive) {
                Log.d(TAG, "Wake word detectada: '$text'")
                isActive = false
                lastInteractionTime = System.currentTimeMillis()
                onWakeWord()
            }
        }
        sttWatcher?.start()
    }

    /**
     * Verifica inatividade — após 5 min sem interação,
     * o usuário precisa chamar "Jarvis" novamente.
     */
    private fun startIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(30_000L) // checar a cada 30s
                val elapsed = System.currentTimeMillis() - lastInteractionTime
                if (elapsed >= IDLE_TIMEOUT_MS) {
                    Log.d(TAG, "Timeout de inatividade — aguardando wake word")
                    // Já está em modo wake word, só loga
                }
            }
        }
    }

    fun recordInteraction() {
        lastInteractionTime = System.currentTimeMillis()
    }

    fun stop() {
        isActive = false
        idleCheckJob?.cancel()
        sttWatcher?.stop()
        sttWatcher = null
    }

    fun restart() {
        stop()
        CoroutineScope(Dispatchers.Main).launch {
            delay(800L)
            isActive = true
            startContinuousSTT()
            startIdleCheck()
        }
    }
}
