package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Wake word detector usando AudioRecord puro — funciona com tela apagada.
 * Fluxo: VAD detecta voz → grava chunk → envia para Whisper → checa "jarvis"
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_THRESHOLD = 600        // energia mínima para considerar voz
        private const val CHUNK_DURATION_MS = 2500L  // grava 2.5s após detectar voz
        private val WAKE_WORDS = listOf(
            "jarvis", "jávis", "járvis", "jarves", "jarwis",
            "ei jarvis", "hey jarvis", "ok jarvis", "oi jarvis",
            "jarvi", "jarbi", "zarvis", "harvis", "jarvis"
        )
    }

    private var isActive: Boolean = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vadJob: Job? = null
    private val whisperClient = WhisperClient(context)

    fun start() {
        if (isActive) return
        isActive = true
        Log.d(TAG, "WakeWordDetector iniciado via AudioRecord")
        startVADLoop()
    }

    private fun startVADLoop() {
        vadJob?.cancel()
        vadJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, FORMAT,
                bufferSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord falhou ao inicializar")
                return@launch
            }

            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize)

            try {
                while (isActive && isActive) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    // VAD: calcular energia RMS
                    val rms = calculateRMS(buffer, read)

                    if (rms > VAD_THRESHOLD) {
                        // Voz detectada — gravar chunk completo
                        Log.d(TAG, "Voz detectada (RMS=$rms), gravando...")
                        audioRecord.stop()

                        val audioData = recordChunk(CHUNK_DURATION_MS)
                        if (audioData.isNotEmpty()) {
                            val text = whisperClient.transcribe(audioData)
                            Log.d(TAG, "Whisper ouviu: '$text'")
                            val lower = text.lowercase().trim()
                            val found = WAKE_WORDS.any { lower.contains(it) }
                            if (found && isActive) {
                                isActive = false
                                Log.d(TAG, "Wake word detectada!")
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    onWakeWord()
                                }
                                return@launch
                            }
                        }

                        // Reiniciar gravação
                        audioRecord.startRecording()
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    private fun recordChunk(durationMs: Long): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, FORMAT,
            bufferSize * 4
        )
        val data = ShortArray(totalSamples)
        var offset = 0
        audioRecord.startRecording()
        val buf = ShortArray(bufferSize)
        while (offset < totalSamples) {
            val read = audioRecord.read(buf, 0, minOf(bufferSize, totalSamples - offset))
            if (read <= 0) break
            buf.copyInto(data, offset, 0, read)
            offset += read
        }
        audioRecord.stop()
        audioRecord.release()
        return data
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return Math.sqrt(sum / length)
    }

    fun stop() {
        isActive = false
        vadJob?.cancel()
        scope.cancel()
    }

    fun restart() {
        isActive = false
        vadJob?.cancel()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        newScope.launch {
            delay(500L)
            isActive = true
            startVADLoop()
        }
    }
}
