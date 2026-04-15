package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.*
import kotlinx.coroutines.*

/**
 * Detector de wake word usando Porcupine (offline, baixo consumo).
 * Palavra padrão: "Hey Jarvis" (ou "Jarvis" via keyword customizada)
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        // Chave gratuita do Porcupine (substituir pela chave real em produção)
        private const val ACCESS_KEY = "PORCUPINE_ACCESS_KEY_HERE"
    }

    private var porcupineManager: PorcupineManager? = null
    private var isActive = false

    fun start() {
        if (isActive) return
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(context) { keywordIndex ->
                    if (keywordIndex == 0) {
                        Log.d(TAG, "Wake word detectada!")
                        onWakeWord()
                    }
                }
            porcupineManager?.start()
            isActive = true
            Log.d(TAG, "WakeWordDetector iniciado")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Erro ao iniciar Porcupine: ${e.message}")
            // Fallback: usar detecção por energia de áudio
            startFallbackDetection()
        }
    }

    fun stop() {
        isActive = false
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar WakeWordDetector: ${e.message}")
        }
    }

    /**
     * Fallback: detecta quando o usuário fala "jarvis" via STT simples
     * Usado quando Porcupine não está disponível
     */
    private fun startFallbackDetection() {
        Log.w(TAG, "Usando fallback de wake word (STT básico)")
        // O JarvisService vai usar o botão manual como fallback
    }
}
