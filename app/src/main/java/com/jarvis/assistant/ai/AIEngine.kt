package com.jarvis.assistant.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.io.IOException
import java.util.concurrent.TimeUnit

class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIEngine"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
        private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.3-70b-versatile"
        private const val MODEL_FALLBACK = "llama-3.1-8b-instant"

        private const val SYSTEM_PROMPT = """Você é Natiele, assistente pessoal de IA rodando em um smartphone Android.

PERSONALIDADE:
- Direta, inteligente, confiante — como uma assistente executiva de alto nível
- Tom natural, humano, sem ser robótica
- Levemente sarcástica quando apropriado
- Nunca prolixo — vai direto ao ponto

REGRAS DE RESPOSTA:
- Respostas CURTAS: máximo 2 frases para perguntas simples
- Só detalha quando explicitamente pedido ("explica", "detalha", "como funciona")
- Nunca repete o que o usuário disse
- Nunca começa com "Claro!", "Certamente!", "Com prazer!" — vai direto
- Para ações executadas: confirma em 1 frase ("Feito.", "Abrindo.", "Pronto.")
- Para perguntas de fato: responde o fato, ponto final
- Para conversas: responde naturalmente, sem enrolação

CONTEXTO:
- Você roda no celular Android do usuário
- Você tem acesso a apps, notificações, contatos, calendário
- Você também tem uma versão desktop (PC) que compartilha memória com você
- Idioma: sempre português brasileiro"""
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val responseCache = LinkedHashMap<String, String>(50, 0.75f, true)

    private val offlineMap: Map<List<String>, () -> String> = mapOf(
        listOf("que horas", "hora agora", "horas são") to {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
            fmt.format(java.util.Date())
        },
        listOf("que dia", "data hoje", "dia é hoje") to {
            val fmt = java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", java.util.Locale("pt", "BR"))
            fmt.format(java.util.Date())
        },
        listOf("olá", "oi natiele", "e aí", "tudo bem") to { "Tudo certo. O que precisa?" },
        listOf("obrigado", "valeu", "brigado") to { "Disponha." },
        listOf("status", "como você está", "tudo funcionando") to {
            "Online e funcionando. Memória e IA ativas."
        }
    )

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        userContext: String = ""
    ): String {
        val offlineResp = getOfflineResponse(userMessage)
        if (offlineResp != null) return offlineResp

        val cacheKey = userMessage.lowercase().trim()
        responseCache[cacheKey]?.let { return it }

        if (!isOnline()) return "Sem conexão. Posso ajudar com hora, data e comandos locais."

        return try {
            val response = callGroq(userMessage, history, MODEL, userContext)
            responseCache[cacheKey] = response
            response
        } catch (e: Exception) {
            Log.e(TAG, "Erro modelo principal: ${e.message}")
            try {
                callGroq(userMessage, history, MODEL_FALLBACK, userContext)
            } catch (e2: Exception) {
                Log.e(TAG, "Erro fallback: ${e2.message}")
                "Não consegui processar. Tente novamente."
            }
        }
    }

    private suspend fun callGroq(
        message: String,
        history: List<Pair<String, String>>,
        model: String,
        userContext: String
    ): String = withContext(Dispatchers.IO) {
        val systemContent = if (userContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\nMemória do usuário:\n$userContext"
        } else {
            SYSTEM_PROMPT
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemContent)
            })
            history.takeLast(6).forEach { (u, a) ->
                put(JSONObject().apply { put("role", "user"); put("content", u) })
                put(JSONObject().apply { put("role", "assistant"); put("content", a) })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", message)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 200)   // Forçar respostas curtas
            put("temperature", 0.7)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(GROQ_BASE_URL)
            .header("Authorization", "Bearer $GROQ_API_KEY")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("API error ${response.code}")
            }
            val json = JSONObject(response.body?.string() ?: throw IOException("Resposta vazia"))
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private fun getOfflineResponse(query: String): String? {
        val lower = query.lowercase()
        return offlineMap.entries.firstOrNull { (keys, _) ->
            keys.any { lower.contains(it) }
        }?.value?.invoke()
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
