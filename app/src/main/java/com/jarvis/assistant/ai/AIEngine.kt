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

/**
 * Motor de IA com seleção dinâmica de modelo (dNaty).
 * Memória de contexto do usuário para respostas personalizadas.
 */
class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIEngine"
        private val GROQ_API_KEY get() = BuildConfig.GROQ_API_KEY
        private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        const val MODEL_FAST = "llama-3.1-8b-instant"
        const val MODEL_SMART = "llama-3.3-70b-versatile"
        const val MODEL_ULTRA = "qwen-qwq-32b"

        private const val SYSTEM_PROMPT = """Você é Jarvis, assistente pessoal de IA rodando em um smartphone Android.
Personalidade: direto, inteligente, levemente sarcástico quando apropriado, como o Jarvis do Tony Stark.
Idioma: sempre português brasileiro, natural e fluido.
Respostas curtas para comandos simples (1-2 frases). Detalhado apenas quando perguntado.
Você tem memória das interações anteriores e conhece o usuário pelo histórico.
Quando executar ações no dispositivo, confirme brevemente o que fez."""
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache simples para respostas repetidas
    private val responseCache = LinkedHashMap<String, String>(50, 0.75f, true)

    // Stats dos modelos para seleção adaptativa (dNaty)
    private val modelStats = mutableMapOf(
        MODEL_FAST to ModelStats(),
        MODEL_SMART to ModelStats(),
        MODEL_ULTRA to ModelStats()
    )

    // Respostas offline instantâneas
    private val offlineMap = mapOf(
        listOf("hora", "horas", "que horas") to {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
            "São ${fmt.format(java.util.Date())}."
        },
        listOf("data", "dia", "hoje") to {
            val fmt = java.text.SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", java.util.Locale("pt", "BR"))
            "Hoje é ${fmt.format(java.util.Date())}."
        },
        listOf("olá", "oi", "e aí", "tudo bem", "como vai") to {
            "Tudo funcionando perfeitamente. O que precisa?"
        },
        listOf("obrigado", "valeu", "brigado") to {
            "Disponha."
        }
    )

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        userContext: String = ""
    ): String {
        // 1. Resposta offline instantânea
        val offlineResp = getOfflineResponse(userMessage)
        if (offlineResp != null) return offlineResp

        // 2. Cache
        val cacheKey = userMessage.lowercase().trim()
        responseCache[cacheKey]?.let { return it }

        // 3. Sem internet
        if (!isOnline()) return getOfflineFallback()

        // 4. Selecionar modelo
        val model = selectModel(userMessage)

        return try {
            val response = callGroq(userMessage, history, model, userContext)
            modelStats[model]?.recordSuccess()
            responseCache[cacheKey] = response
            response
        } catch (e: Exception) {
            Log.e(TAG, "Erro $model: ${e.message}")
            modelStats[model]?.recordFailure()
            // Fallback para modelo mais rápido
            if (model != MODEL_FAST) {
                try {
                    callGroq(userMessage, history, MODEL_FAST, userContext)
                } catch (e2: Exception) {
                    getOfflineFallback()
                }
            } else {
                getOfflineFallback()
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
            "$SYSTEM_PROMPT\n\nContexto do usuário:\n$userContext"
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
            put("max_tokens", 400)
            put("temperature", 0.75)
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
                throw IOException("API error ${response.code}: ${response.body?.string()}")
            }
            val json = JSONObject(response.body?.string() ?: throw IOException("Resposta vazia"))
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    /**
     * dNaty: seleciona modelo baseado em complexidade da query
     * e performance histórica dos modelos.
     */
    private fun selectModel(query: String): String {
        val complexity = estimateComplexity(query)
        val fastRate = modelStats[MODEL_FAST]?.successRate ?: 1.0

        return when {
            complexity < 0.3 && fastRate > 0.7 -> MODEL_FAST
            complexity > 0.7 -> MODEL_SMART
            else -> MODEL_FAST // Preferir rápido para menor latência
        }
    }

    private fun estimateComplexity(query: String): Double {
        val words = query.split(" ").size
        val complexKeywords = listOf(
            "explica", "analisa", "compara", "resume", "como funciona",
            "por que", "diferença", "vantagens", "desvantagens", "detalha"
        )
        val simpleKeywords = listOf(
            "abre", "fecha", "liga", "desliga", "volume", "brilho",
            "hora", "data", "timer", "alarme", "toca", "pausa"
        )
        return when {
            simpleKeywords.any { query.lowercase().contains(it) } -> 0.1
            complexKeywords.any { query.lowercase().contains(it) } -> 0.8
            words > 15 -> 0.6
            else -> 0.3
        }
    }

    private fun getOfflineResponse(query: String): String? {
        val lower = query.lowercase()
        return offlineMap.entries.firstOrNull { (keys, _) ->
            keys.any { lower.contains(it) }
        }?.value?.invoke()
    }

    private fun getOfflineFallback(): String {
        return "Sem conexão no momento. Posso ajudar com comandos básicos como hora, data, abrir apps e controles do dispositivo."
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

data class ModelStats(
    var calls: Int = 0,
    var successes: Int = 0
) {
    val successRate: Double get() = if (calls == 0) 1.0 else successes.toDouble() / calls
    fun recordSuccess() { calls++; successes++ }
    fun recordFailure() { calls++ }
}
