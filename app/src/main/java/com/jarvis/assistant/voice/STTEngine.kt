package com.jarvis.assistant.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Captura comando de voz via AudioRecord + Whisper.
 * VAD com múltipla confirmação de silêncio — só para quando tem certeza
 * que o usuário terminou de falar.
 */
class STTEngine(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "STTEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 350.0
        // Precisa de 2s contínuos de silêncio para considerar fim da fala
        private const val SILENCE_CONFIRM_MS = 2000L
        private const val MAX_DURATION_MS = 12000L  // máximo 12s
        private const val MIN_SPEECH_MS = 400L      // mínimo para processar
    }

    private val recording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val whisperClient = WhisperClient(context)

    fun startListening() {
        if (recording.get()) return
        recording.set(true)
        Log.d(TAG, "STTEngine: iniciando captura de comando")

        recordJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            var audioRecord: AudioRecord? = null

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL, ENCODING,
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
                var silenceStartTime = 0L
                var hasSpeech = false
                var speechStartTime = 0L

                while (recording.get()) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    allData.addAll(buffer.take(read))

                    val rms = calculateRMS(buffer, read)
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime

                    if (rms > SILENCE_THRESHOLD) {
                        // Voz detectada
                        if (!hasSpeech) {
                            hasSpeech = true
                            speechStartTime = now
                            Log.d(TAG, "Início da fala detectado")
                        }
                        silenceStartTime = 0L  // resetar contador de silêncio
                    } else if (hasSpeech) {
                        // Silêncio após fala
                        if (silenceStartTime == 0L) {
                            silenceStartTime = now
                        }
                        val silenceDuration = now - silenceStartTime

                        // Só para se tiver silêncio confirmado por SILENCE_CONFIRM_MS
                        if (silenceDuration >= SILENCE_CONFIRM_MS) {
                            Log.d(TAG, "Silêncio confirmado (${silenceDuration}ms) — fim da fala")
                            break
                        }
                    }

                    // Timeout máximo
                    if (elapsed >= MAX_DURATION_MS) {
                        Log.d(TAG, "Timeout máximo atingido")
                        break
                    }
                }

                val speechDuration = if (speechStartTime > 0L) {
                    System.currentTimeMillis() - speechStartTime
                } else {
                    0L
                }

                if (speechDuration < MIN_SPEECH_MS || allData.isEmpty()) {
                    Log.d(TAG, "Fala muito curta ou vazia")
                    withContext(Dispatchers.Main) { onResult("") }
                    return@launch
                }

                Log.d(TAG, "Enviando ${allData.size} samples para Whisper")
                val text = whisperClient.transcribe(allData.toShortArray())
                Log.d(TAG, "Transcrição final: '$text'")
                withContext(Dispatchers.Main) { onResult(text) }

            } catch (e: Exception) {
                Log.e(TAG, "Erro na gravação: ${e.message}")
                withContext(Dispatchers.Main) { onResult("") }
            } finally {
                recording.set(false)
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) { /* ignorar */ }
            }
        }
    }

    fun stopListening() {
        recording.set(false)
        recordJob?.cancel()
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    fun destroy() {
        stopListening()
        scope.cancel()
    }
}
