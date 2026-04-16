package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Executa automações geradas pelo AutomationEngine.
 */
class AutomationExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AutomationExecutor"
        // URL do backend — trocar pelo IP do PC quando rodando localmente
        private val BACKEND_URL = "http://localhost:8000"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun execute(automation: JSONObject): String {
        val action = automation.optString("action", "")
        val params = automation.optJSONObject("params") ?: JSONObject()

        Log.d(TAG, "Executando: $action params=$params")

        return when (action) {
            "OPEN_CAMERA" -> openCamera(params.optString("mode", "photo"))
            "TAKE_PHOTO" -> takePhoto()
            "OPEN_APP" -> openApp(params.optString("package", ""), params.optString("name", ""))
            "SEND_WHATSAPP" -> sendWhatsApp(params.optString("contact"), params.optString("message"))
            "SET_ALARM" -> setAlarm(params.optInt("hour", 8), params.optInt("minute", 0), params.optString("label", "Alarme"))
            "SET_TIMER" -> setTimer(params.optInt("seconds", 60))
            "OPEN_URL" -> openUrl(params.optString("url"))
            "SHARE_TEXT" -> shareText(params.optString("text"))
            "CALL_CONTACT" -> callContact(params.optString("contact"))
            "SEARCH_WEB" -> searchWeb(params.optString("query"))
            "SET_VOLUME" -> setVolume(params.optInt("level", 50))
            "SET_BRIGHTNESS" -> setBrightness(params.optInt("level", 80))
            "PC_SCRIPT" -> executePCScript(params.optString("script"), params.optString("lang", "python"))
            else -> "Ação '$action' não reconhecida."
        }
    }

    private fun openCamera(mode: String): String {
        val intent = if (mode == "video") {
            Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        } else {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Câmera aberta."
        } catch (e: Exception) {
            "Não consegui abrir a câmera."
        }
    }

    private fun takePhoto(): String {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Câmera aberta para foto."
        } catch (e: Exception) {
            "Não consegui abrir a câmera."
        }
    }

    private fun openApp(packageName: String, appName: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "App '$appName' não encontrado."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Abrindo $appName."
        } catch (e: Exception) {
            "Não consegui abrir $appName."
        }
    }

    private fun sendWhatsApp(contact: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/?text=${Uri.encode(message)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Abrindo WhatsApp para $contact."
        } catch (e: Exception) {
            "Não consegui abrir o WhatsApp."
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Alarme criado para ${hour}h${minute.toString().padStart(2, '0')}."
        } catch (e: Exception) {
            "Não consegui criar o alarme."
        }
    }

    private fun setTimer(seconds: Int): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val min = seconds / 60
            val sec = seconds % 60
            if (min > 0) "Timer de ${min}min${if (sec > 0) " e ${sec}s" else ""} criado."
            else "Timer de ${sec}s criado."
        } catch (e: Exception) {
            "Não consegui criar o timer."
        }
    }

    private fun openUrl(url: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Abrindo $url."
        } catch (e: Exception) {
            "Não consegui abrir a URL."
        }
    }

    private fun shareText(text: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Compartilhando texto."
        } catch (e: Exception) {
            "Não consegui compartilhar."
        }
    }

    private fun callContact(contact: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Abrindo discador para $contact."
        } catch (e: Exception) {
            "Não consegui fazer a ligação."
        }
    }

    private fun searchWeb(query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Buscando '$query'."
        } catch (e: Exception) {
            "Não consegui fazer a busca."
        }
    }

    private fun setVolume(level: Int): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val vol = (level * max / 100).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI)
            "Volume em $level%."
        } catch (e: Exception) {
            "Não consegui ajustar o volume."
        }
    }

    private fun setBrightness(level: Int): String {
        return try {
            val brightness = (level * 255 / 100).coerceIn(10, 255)
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            "Brilho em $level%."
        } catch (e: Exception) {
            "Preciso de permissão para alterar o brilho."
        }
    }

    /**
     * Envia script para o PC executar via backend.
     * O PC roda o script e retorna o resultado.
     */
    suspend fun executePCScript(script: String, lang: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("script", script)
                    put("lang", lang)
                    put("source", "android")
                }
                val request = Request.Builder()
                    .url("$BACKEND_URL/pc/execute")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val result = JSONObject(response.body?.string() ?: "{}")
                        val output = result.optString("output", "Script executado.")
                        "PC: $output"
                    } else {
                        "PC offline ou backend não disponível."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar script para PC: ${e.message}")
                "PC não está conectado. Inicie o backend no PC."
            }
        }
    }
}
