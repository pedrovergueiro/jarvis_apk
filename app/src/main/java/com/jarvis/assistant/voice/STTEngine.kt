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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Captura comando de voz via AudioRecord + Whisper.
 * Funciona em background, com tela apagada.
 * VAD detecta fim da fala automaticamente.
 */
class STTEngine(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "STTEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 400.0
        private const val SILENCE_DURATION_MS = 1500L  // 1.5s de silêncio = fim da fala
        private const val MAX_DURATION_MS = 8000L      // máximo 8s de gravação
        private const val MIN_DURATION_MS = 500L       // mínimo 0.5s para processar
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var isRecording: Boolean = false
    private val whisperClient = WhisperClient(context)

    fun startListening() {
        if (isRecording) return
        isRecording = true
        Log.d(TAG, "Iniciando captura de comando")

        recordJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, FORMAT,
                bufferSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord não inicializou")
                withContext(Dispatchers.Main) { onResult("") }
                return@launch
            }

            audioRecord.startRecording()
            val allData = mutableListOf<Short>()
            val buffer = ShortArray(bufferSize)
            val startTime = System.currentTimeMillis()
            var silenceStart = 0L
            var hasSpeech = false

            try {
                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    allData.addAll(buffer.take(read))

                    val rms = calculateRMS(buffer, read)
                    val elapsed = System.currentTimeMillis() - startTime

                    if (rms > SILENCE_THRESHOLD) {
                        hasSpeech = true
                        silenceStart = 0L
                    } else if (hasSpeech) {
                        if (silenceStart == 0L) silenceStart = System.currentTimeMillis()
                        val silenceDuration = System.currentTimeMillis() - silenceStart
                        if (silenceDuration >= SILENCE_DURATION_MS) {
                            Log.d(TAG, "Silêncio detectado — fim da fala")
                            break
                        }
                    }

                    if (elapsed >= MAX_DURATION_MS) {
                        Log.d(TAG, "Timeout máximo de gravação")
                        break
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
                isRecording = false
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < MIN_DURATION_MS || allData.isEmpty()) {
                withContext(Dispatchers.Main) { onResult("") }
                return@launch
            }

            // Transcrever com Whisper
            Log.d(TAG, "Enviando ${allData.size} samples para Whisper")
            val text = whisperClient.transcribe(allData.toShortArray())
            Log.d(TAG, "Transcrição: '$text'")
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    fun stopListening() {
        isRecording = false
        recordJob?.cancel()
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / length)
    }

    fun destroy() {
        stopListening()
        scope.cancel()
    }
}
