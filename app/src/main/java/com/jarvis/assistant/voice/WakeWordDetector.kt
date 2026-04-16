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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Wake word detector via AudioRecord + Whisper.
 * Funciona com tela apagada e em background.
 * VAD detecta energia de voz → grava → Whisper transcreve → checa "jarvis"
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_THRESHOLD = 600.0
        private const val CHUNK_DURATION_MS = 2500L
        private val WAKE_WORDS = listOf(
            "natiele", "natiéle", "natiele", "nati", "natiely",
            "ei natiele", "hey natiele", "ok natiele", "oi natiele",
            "nathiele", "natieli", "natele", "natiéli"
        )
    }

    // AtomicBoolean para ser seguro entre threads/coroutines
    private val running = AtomicBoolean(false)
    private var vadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val whisperClient = WhisperClient(context)

    fun start() {
        if (running.get()) return
        running.set(true)
        Log.d(TAG, "WakeWordDetector iniciado")
        startVADLoop()
    }

    private fun startVADLoop() {
        vadJob?.cancel()
        vadJob = scope.launch {
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
                    return@launch
                }

                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)

                while (running.get()) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    val rms = calculateRMS(buffer, read)

                    if (rms > VAD_THRESHOLD) {
                        Log.d(TAG, "Voz detectada RMS=$rms, gravando chunk...")
                        audioRecord.stop()

                        val chunk = recordChunk(CHUNK_DURATION_MS)
                        if (chunk.isNotEmpty()) {
                            val text = whisperClient.transcribe(chunk)
                            Log.d(TAG, "Whisper: '$text'")
                            val lower = text.lowercase().trim()
                            val found = WAKE_WORDS.any { lower.contains(it) }
                            if (found && running.get()) {
                                running.set(false)
                                Log.d(TAG, "Wake word detectada!")
                                withContext(Dispatchers.Main) { onWakeWord() }
                                return@launch
                            }
                        }

                        if (running.get()) {
                            audioRecord.startRecording()
                        }
                    }
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao liberar AudioRecord: ${e.message}")
                }
            }
        }
    }

    private fun recordChunk(durationMs: Long): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        var audioRecord: AudioRecord? = null
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING,
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
            data
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gravar chunk: ${e.message}")
            ShortArray(0)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) { /* ignorar */ }
        }
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    fun stop() {
        running.set(false)
        vadJob?.cancel()
    }

    fun restart() {
        stop()
        CoroutineScope(Dispatchers.IO).launch {
            delay(600L)
            running.set(true)
            startVADLoop()
        }
    }
}
