package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import com.jarvis.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Cliente Whisper Large V3 Turbo via Groq.
 * Converte ShortArray PCM para WAV e envia para transcrição.
 */
class WhisperClient(private val context: Context) {

    companion object {
        private const val TAG = "WhisperClient"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
        private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val SAMPLE_RATE = 16000
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Transcreve áudio PCM 16-bit mono para texto.
     * Retorna string vazia em caso de erro.
     */
    fun transcribe(audioData: ShortArray): String {
        return try {
            val wavBytes = pcmToWav(audioData)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", "pt")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url(GROQ_WHISPER_URL)
                .header("Authorization", "Bearer $GROQ_API_KEY")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("text", "")
                } else {
                    Log.e(TAG, "Erro Whisper: ${response.code}")
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao transcrever: ${e.message}")
            ""
        }
    }

    private fun pcmToWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)           // PCM
            putShort(1)           // Mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }
        out.write(header.array())
        val dataBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { dataBuffer.putShort(it) }
        out.write(dataBuffer.array())
        return out.toByteArray()
    }
}
