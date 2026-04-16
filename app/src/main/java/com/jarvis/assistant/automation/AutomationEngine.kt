package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Motor de automação da Natiele.
 * Interpreta comandos em linguagem natural e gera + executa automações.
 * Suporta: câmera, apps, intents Android, scripts via backend PC.
 */
class AutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutomationEngine"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

        private const val AUTOMATION_PROMPT = """Você é um gerador de automações Android.
Dado um comando em português, gere um JSON com a automação a executar.

Formato de resposta (APENAS JSON, sem texto extra):
{
  "action": "TIPO_DA_ACAO",
  "params": { ... }
}

Ações disponíveis:
- OPEN_CAMERA: abre câmera. params: {"mode": "photo"|"video"}
- TAKE_PHOTO: tira foto silenciosa. params: {}
- OPEN_APP: abre app. params: {"package": "nome.do.pacote", "name": "nome"}
- SEND_WHATSAPP: envia mensagem. params: {"contact": "nome", "message": "texto"}
- SET_ALARM: cria alarme. params: {"hour": 8, "minute": 30, "label": "texto"}
- SET_TIMER: cria timer. params: {"seconds": 300}
- OPEN_URL: abre URL. params: {"url": "https://..."}
- SHARE_TEXT: compartilha texto. params: {"text": "conteúdo"}
- CALL_CONTACT: liga para contato. params: {"contact": "nome"}
- SEND_SMS: envia SMS. params: {"contact": "nome", "message": "texto"}
- SEARCH_WEB: busca na web. params: {"query": "termo"}
- TOGGLE_WIFI: liga/desliga wifi. params: {"enable": true|false}
- TOGGLE_BLUETOOTH: liga/desliga bluetooth. params: {"enable": true|false}
- SET_VOLUME: ajusta volume. params: {"level": 50}
- SET_BRIGHTNESS: ajusta brilho. params: {"level": 80}
- CREATE_NOTE: cria nota. params: {"title": "título", "content": "conteúdo"}
- PC_SCRIPT: executa script no PC. params: {"script": "código python/powershell", "lang": "python"|"powershell"}

Exemplos:
"tira uma foto" → {"action": "TAKE_PHOTO", "params": {}}
"abre a câmera" → {"action": "OPEN_CAMERA", "params": {"mode": "photo"}}
"manda oi para João no whatsapp" → {"action": "SEND_WHATSAPP", "params": {"contact": "João", "message": "oi"}}
"cria um script para abrir o chrome no pc" → {"action": "PC_SCRIPT", "params": {"script": "import subprocess; subprocess.Popen(['chrome'])", "lang": "python"}}"""
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val executor = AutomationExecutor(context)

    /**
     * Interpreta comando e executa automação.
     * Retorna descrição do que foi feito.
     */
    suspend fun execute(command: String): String {
        return try {
            val json = generateAutomation(command)
            if (json != null) {
                executor.execute(json)
            } else {
                "Não consegui criar uma automação para esse comando."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na automação: ${e.message}")
            "Erro ao executar automação: ${e.message}"
        }
    }

    private suspend fun generateAutomation(command: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", AUTOMATION_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", command)
                    })
                }

                val body = JSONObject().apply {
                    put("model", "llama-3.1-8b-instant") // Modelo rápido para geração de JSON
                    put("messages", messages)
                    put("max_tokens", 200)
                    put("temperature", 0.1) // Baixa temperatura para JSON consistente
                }

                val request = Request.Builder()
                    .url(GROQ_URL)
                    .header("Authorization", "Bearer $GROQ_API_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val text = JSONObject(response.body?.string() ?: return@withContext null)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    // Extrair JSON da resposta
                    val jsonStart = text.indexOf("{")
                    val jsonEnd = text.lastIndexOf("}") + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        JSONObject(text.substring(jsonStart, jsonEnd))
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao gerar automação: ${e.message}")
                null
            }
        }
    }

    /**
     * Verifica se um comando parece ser uma automação
     */
    fun isAutomationCommand(command: String): Boolean {
        val lower = command.lowercase()
        val automationKeywords = listOf(
            "tira foto", "tira uma foto", "abre a câmera", "abre câmera",
            "cria um script", "cria script", "executa", "automatiza",
            "faz um código", "escreve um script", "programa para",
            "manda mensagem", "envia mensagem", "liga para",
            "cria alarme", "cria timer", "abre o site", "busca no google",
            "liga o wifi", "desliga o wifi", "liga bluetooth"
        )
        return automationKeywords.any { lower.contains(it) }
    }
}
