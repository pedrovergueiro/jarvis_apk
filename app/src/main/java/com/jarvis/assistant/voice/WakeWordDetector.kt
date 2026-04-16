package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private val WAKE_WORDS = listOf("jarvis", "ei jarvis", "hey jarvis", "ok jarvis")
    }

    // var para permitir reatribuição
    private var isActive: Boolean = false
    private var sttWatcher: ContinuousSTTWatcher? = null

    fun start() {
        if (isActive) return
        isActive = true
        Log.d(TAG, "WakeWordDetector iniciado")
        startContinuousSTT()
    }

    private fun startContinuousSTT() {
        sttWatcher = ContinuousSTTWatcher(context) { text ->
            val lower = text.lowercase().trim()
            val found = WAKE_WORDS.any { lower.contains(it) }
            if (found && isActive) {
                Log.d(TAG, "Wake word detectada: '$text'")
                isActive = false
                onWakeWord()
            }
        }
        sttWatcher?.start()
    }

    fun stop() {
        isActive = false
        sttWatcher?.stop()
        sttWatcher = null
    }

    fun restart() {
        stop()
        CoroutineScope(Dispatchers.Main).launch {
            delay(800L)
            isActive = true
            startContinuousSTT()
        }
    }
}
