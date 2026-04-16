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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

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
        private const val SILENCE_CONFIRM_MS = 1800L
        private const val MAX_DURATION_MS = 12000L
        private const val MIN_SPEECH_MS = 400L
    }

    private val recording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val whisperClient = WhisperClient(context)

    fun startListening() {
        // Não ouvir se TTS está falando
        if (MicrophoneLock.isLocked()) {
            Log.w(TAG, "Mic bloqueado pelo TTS — aguardando")
            CoroutineScope(Dispatchers.IO).launch {
                while (MicrophoneLock.isLocked()) { delay(100L) }
                delay(200L) // pequeno buffer após TTS parar
                withContext(Dispatchers.Main) { startListening() }
            }
            return
        }

        if (recording.get()) return
        recording.set(true)
        Log.d(TAG, "STTEngine: iniciando captura")

        recordJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 4
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
                    // Parar imediatamente se TTS começar a falar
                    if (MicrophoneLock.isLocked()) {
                        Log.w(TAG, "TTS iniciou durante gravação — abortando")
                        break
                    }

                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    allData.addAll(buffer.take(read))
                    val rms = calculateRMS(buffer, read)
                    val now = System.currentTimeMillis()

                    if (rms > SILENCE_THRESHOLD) {
                        if (!hasSpeech) { hasSpeech = true; speechStartTime = now }
                        silenceStartTime = 0L
                    } else if (hasSpeech) {
                        if (silenceStartTime == 0L) silenceStartTime = now
                        if (now - silenceStartTime >= SILENCE_CONFIRM_MS) {
                            Log.d(TAG, "Silêncio confirmado — fim da fala")
                            break
                        }
                    }

                    if (now - startTime >= MAX_DURATION_MS) {
                        Log.d(TAG, "Timeout máximo")
                        break
                    }
                }

                val speechDuration = if (speechStartTime > 0L) System.currentTimeMillis() - speechStartTime else 0L

                if (speechDuration < MIN_SPEECH_MS || allData.isEmpty() || MicrophoneLock.isLocked()) {
                    withContext(Dispatchers.Main) { onResult("") }
                    return@launch
                }

                val text = whisperClient.transcribe(allData.toShortArray())
                Log.d(TAG, "Transcrição: '$text'")
                withContext(Dispatchers.Main) { onResult(text) }

            } catch (e: Exception) {
                Log.e(TAG, "Erro: ${e.message}")
                withContext(Dispatchers.Main) { onResult("") }
            } finally {
                recording.set(false)
                try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            }
        }
    }

    fun stopListening() {
        recording.set(false)
        recordJob?.cancel()
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) { val s = buffer[i].toDouble(); sum += s * s }
        return sqrt(sum / length)
    }

    fun destroy() { stopListening(); scope.cancel() }
}
