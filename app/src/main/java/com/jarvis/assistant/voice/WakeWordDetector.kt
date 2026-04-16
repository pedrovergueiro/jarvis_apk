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
            "natiele", "natiéle", "nati", "natiely",
            "ei natiele", "hey natiele", "ok natiele", "oi natiele",
            "nathiele", "natieli", "natele"
        )
    }

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
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 4
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord não inicializou")
                    return@launch
                }
                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)

                while (running.get()) {
                    // CRÍTICO: não ouvir enquanto TTS está falando
                    if (MicrophoneLock.isLocked()) {
                        delay(200L)
                        continue
                    }

                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read <= 0) continue

                    val rms = calculateRMS(buffer, read)
                    if (rms > VAD_THRESHOLD) {
                        Log.d(TAG, "Voz detectada RMS=$rms")
                        audioRecord.stop()

                        // Esperar TTS terminar antes de gravar
                        while (MicrophoneLock.isLocked()) { delay(100L) }

                        val chunk = recordChunk(CHUNK_DURATION_MS)
                        if (chunk.isNotEmpty() && running.get()) {
                            val text = whisperClient.transcribe(chunk)
                            Log.d(TAG, "Whisper wake: '$text'")
                            val lower = text.lowercase().trim()
                            val found = WAKE_WORDS.any { lower.contains(it) }
                            if (found && running.get() && !MicrophoneLock.isLocked()) {
                                running.set(false)
                                withContext(Dispatchers.Main) { onWakeWord() }
                                return@launch
                            }
                        }

                        if (running.get()) audioRecord.startRecording()
                    }
                }
            } finally {
                try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun recordChunk(durationMs: Long): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        var ar: AudioRecord? = null
        return try {
            ar = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 4)
            val data = ShortArray(totalSamples)
            var offset = 0
            ar.startRecording()
            val buf = ShortArray(bufferSize)
            while (offset < totalSamples) {
                val read = ar.read(buf, 0, minOf(bufferSize, totalSamples - offset))
                if (read <= 0) break
                buf.copyInto(data, offset, 0, read)
                offset += read
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "Erro recordChunk: ${e.message}")
            ShortArray(0)
        } finally {
            try { ar?.stop(); ar?.release() } catch (_: Exception) {}
        }
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) { val s = buffer[i].toDouble(); sum += s * s }
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
