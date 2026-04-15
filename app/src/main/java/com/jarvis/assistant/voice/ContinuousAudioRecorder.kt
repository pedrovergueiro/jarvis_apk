package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gravador de áudio contínuo com detecção de silêncio (VAD simples).
 * Grava até detectar silêncio ou atingir tempo máximo.
 */
class ContinuousAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_MS = 8000L   // 8 segundos máximo
        private const val SILENCE_THRESHOLD = 500     // Amplitude mínima
        private const val SILENCE_DURATION_MS = 1500L // Silêncio para parar
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startRecording(onComplete: (File) -> Unit) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord não inicializado")
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        scope.launch {
            val outputFile = File(context.cacheDir, "jarvis_audio_${System.currentTimeMillis()}.wav")
            val audioData = mutableListOf<Short>()
            val buffer = ShortArray(bufferSize)
            var silenceStart = 0L
            val startTime = System.currentTimeMillis()

            try {
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        audioData.addAll(buffer.take(read))

                        // VAD: detectar silêncio
                        val maxAmplitude = buffer.take(read).maxOrNull()?.toInt()?.let {
                            Math.abs(it)
                        } ?: 0

                        if (maxAmplitude < SILENCE_THRESHOLD) {
                            if (silenceStart == 0L) silenceStart = System.currentTimeMillis()
                            val silenceDuration = System.currentTimeMillis() - silenceStart
                            if (silenceDuration >= SILENCE_DURATION_MS && audioData.size > SAMPLE_RATE) {
                                break // Silêncio detectado, parar gravação
                            }
                        } else {
                            silenceStart = 0L
                        }

                        // Timeout máximo
                        if (System.currentTimeMillis() - startTime >= MAX_RECORDING_MS) break
                    }
                }

                // Salvar como WAV
                writeWavFile(outputFile, audioData.toShortArray())
                withContext(Dispatchers.Main) { onComplete(outputFile) }

            } catch (e: Exception) {
                Log.e(TAG, "Erro na gravação: ${e.message}")
            } finally {
                stopRecording()
            }
        }
    }

    private fun writeWavFile(file: File, audioData: ShortArray) {
        val dataSize = audioData.size * 2
        FileOutputStream(file).use { fos ->
            // WAV Header
            fos.write(buildWavHeader(dataSize))
            // Audio data
            val byteBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            audioData.forEach { byteBuffer.putShort(it) }
            fos.write(byteBuffer.array())
        }
    }

    private fun buildWavHeader(dataSize: Int): ByteArray {
        val totalSize = dataSize + 36
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)           // Subchunk1Size
            putShort(1)          // PCM
            putShort(1)          // Mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2) // ByteRate
            putShort(2)          // BlockAlign
            putShort(16)         // BitsPerSample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
