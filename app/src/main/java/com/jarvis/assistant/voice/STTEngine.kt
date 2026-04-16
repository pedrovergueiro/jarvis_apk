package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Motor STT para captura de comandos (não wake word).
 * Usa Android SpeechRecognizer como primário (baixa latência).
 * Fallback para Whisper via Groq se Android STT falhar.
 */
class STTEngine(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    companion object {
        private const val TAG = "STTEngine"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
        private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecorder: ContinuousAudioRecorder? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            startAndroidSTT()
        } else {
            startWhisperSTT()
        }
    }

    private fun startAndroidSTT() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.e(TAG, "Erro STT Android: $error")
                // Fallback para Whisper em erros de reconhecimento
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    startWhisperSTT()
                } else {
                    onResult("")
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "STT resultado: $text")
                if (text.isNotBlank()) {
                    onResult(text)
                } else {
                    onResult("")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun startWhisperSTT() {
        audioRecorder = ContinuousAudioRecorder(context)
        audioRecorder?.startRecording { audioFile ->
            transcribeWithWhisper(audioFile)
        }
    }

    private fun transcribeWithWhisper(audioFile: File) {
        scope.launch {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", audioFile.name,
                        audioFile.readBytes().toRequestBody("audio/wav".toMediaType())
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
                        val text = json.optString("text", "")
                        withContext(Dispatchers.Main) {
                            if (text.isNotBlank()) {
                                onResult(text)
                            } else {
                                onResult("")
                            }
                        }
                    } else {
                        Log.e(TAG, "Erro Whisper API: ${response.code}")
                        withContext(Dispatchers.Main) { onResult("") }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Erro de rede Whisper: ${e.message}")
                withContext(Dispatchers.Main) { onResult("") }
            } finally {
                audioFile.delete()
            }
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        audioRecorder?.stopRecording()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        audioRecorder?.stopRecording()
        scope.cancel()
    }
}
