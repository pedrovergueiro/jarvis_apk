package com.jarvis.assistant.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Motor de IA com seleção dinâmica de modelo (dNaty-inspired):
 * - Online: Groq API (llama-3.1-8b, mixtral-8x7b, qwen-32b)
 * - Offline: Respostas baseadas em regras + cache
 *
 * Implementa o algoritmo dNaty para seleção adaptativa de modelo
 * baseado em complexidade da query e disponibilidade de rede.
 */
class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIEngine"
        private const val GROQ_API_KEY = BuildConfig.GROQ_API_KEY
        private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        // Modelos disponíveis via Groq (ordenados por custo/velocidade)
        const val MODEL_FAST = "llama-3.1-8b-instant"       // Rápido, custo baixo
        const val MODEL_SMART = "llama-3.3-70b-versatile"   // Inteligente
        const val MODEL_ULTRA = "qwen-qwq-32b"              // Máxima qualidade

        private const val SYSTEM_PROMPT = """Você é Jarvis, um assistente pessoal de IA avançado rodando em um smartphone Android.
Você é direto, eficiente e inteligente. Responda sempre em português brasileiro.
Seja conciso em respostas simples (máximo 2-3 frases) e detalhado apenas quando necessário.
Você tem acesso ao dispositivo do usuário e pode executar ações como abrir apps, enviar mensagens, criar lembretes, etc.
Quando o usuário pedir para executar uma ação no dispositivo, responda confirmando a ação de forma breve."""
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache de respostas para reduzir latência e uso de API
    private val responseCache = mutableMapOf<String, Pair<String, Long>>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutos

    // dNaty: histórico de performance por modelo
    private val modelPerformance = mutableMapOf(
        MODEL_FAST to ModelStats(),
        MODEL_SMART to ModelStats(),
        MODEL_ULTRA to ModelStats()
    )

    // Respostas offline para comandos básicos
    private val offlineResponses = mapOf(
        "hora" to { "São ${java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt","BR")).format(java.util.Date())}." },
        "data" to { "Hoje é ${java.text.SimpleDateFormat("dd 'de' MMMM 'de' yyyy", java.util.Locale("pt","BR")).format(java.util.Date())}." },
        "olá" to { "Olá! Como posso ajudar?" },
        "oi" to { "Oi! Estou aqui." },
        "tudo bem" to { "Tudo funcionando perfeitamente. O que precisa?" }
    )

    /**
     * Processa uma mensagem e retorna resposta.
     * Seleciona modelo dinamicamente baseado em complexidade (dNaty).
     */
    suspend fun chat(userMessage: String, conversationHistory: List<Pair<String, String>> = emptyList()): String {
        // 1. Verificar cache
        val cacheKey = userMessage.lowercase().trim()
        responseCache[cacheKey]?.let { (response, timestamp) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                Log.d(TAG, "Cache hit para: $cacheKey")
                return response
            }
        }

        // 2. Tentar resposta offline
        val offlineResponse = getOfflineResponse(userMessage)
        if (offlineResponse != null) return offlineResponse

        // 3. Verificar conectividade
        if (!isOnline()) {
            return getOfflineFallback(userMessage)
        }

        // 4. Selecionar modelo via dNaty
        val model = selectModel(userMessage)
        Log.d(TAG, "Modelo selecionado: $model para: $userMessage")

        // 5. Chamar API
        return try {
            val startTime = System.currentTimeMillis()
            val response = callGroqAPI(userMessage, conversationHistory, model)
            val latency = System.currentTimeMillis() - startTime

            // Atualizar stats do modelo (dNaty learning)
            modelPerformance[model]?.recordSuccess(latency)

            // Salvar no cache
            responseCache[cacheKey] = Pair(response, System.currentTimeMillis())

            response
        } catch (e: Exception) {
            Log.e(TAG, "Erro na API ($model): ${e.message}")
            modelPerformance[model]?.recordFailure()

            // Fallback para modelo mais simples
            if (model != MODEL_FAST) {
                try {
                    callGroqAPI(userMessage, conversationHistory, MODEL_FAST)
                } catch (e2: Exception) {
                    getOfflineFallback(userMessage)
                }
            } else {
                getOfflineFallback(userMessage)
            }
        }
    }

    /**
     * dNaty: Seleção dinâmica de modelo baseada em:
     * - Complexidade da query (tokens estimados, palavras-chave)
     * - Performance histórica dos modelos
     * - Latência desejada
     */
    private fun selectModel(query: String): String {
        val complexity = estimateComplexity(query)
        val fastStats = modelPerformance[MODEL_FAST]!!
        val smartStats = modelPerformance[MODEL_SMART]!!

        return when {
            // Query simples + modelo rápido com boa performance
            complexity < 0.3 && fastStats.successRate > 0.8 -> MODEL_FAST

            // Query complexa ou modelo rápido com falhas
            complexity > 0.7 -> MODEL_ULTRA

            // Padrão: modelo inteligente
            else -> MODEL_SMART
        }
    }

    /**
     * Estima complexidade da query (0.0 a 1.0)
     */
    private fun estimateComplexity(query: String): Double {
        val words = query.split(" ").size
        val hasComplexKeywords = listOf(
            "explica", "analisa", "compara", "resume", "detalha",
            "como funciona", "por que", "diferença", "vantagens"
        ).any { query.lowercase().contains(it) }

        val hasSimpleKeywords = listOf(
            "abre", "fecha", "liga", "desliga", "volume", "brilho",
            "hora", "data", "timer", "alarme"
        ).any { query.lowercase().contains(it) }

        return when {
            hasSimpleKeywords -> 0.1
            hasComplexKeywords -> 0.8
            words > 20 -> 0.7
            words > 10 -> 0.5
            else -> 0.3
        }
    }

    private suspend fun callGroqAPI(
        userMessage: String,
        history: List<Pair<String, String>>,
        model: String
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            // Histórico de conversa (últimas 5 trocas)
            history.takeLast(5).forEach { (user, assistant) ->
                put(JSONObject().apply { put("role", "user"); put("content", user) })
                put(JSONObject().apply { put("role", "assistant"); put("content", assistant) })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 512)
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
                throw IOException("API error: ${response.code} - ${response.body?.string()}")
            }
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private fun getOfflineResponse(query: String): String? {
        val lower = query.lowercase()
        return offlineResponses.entries.firstOrNull { lower.contains(it.key) }?.value?.invoke()
    }

    private fun getOfflineFallback(query: String): String {
        return "Estou sem conexão no momento. Posso ajudar com comandos básicos como hora, data, abrir apps e controles do dispositivo."
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Streaming de resposta para menor latência percebida
     */
    suspend fun chatStream(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        onChunk: (String) -> Unit
    ) {
        if (!isOnline()) {
            onChunk(getOfflineFallback(userMessage))
            return
        }

        val model = selectModel(userMessage)
        withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    history.takeLast(5).forEach { (u, a) ->
                        put(JSONObject().apply { put("role", "user"); put("content", u) })
                        put(JSONObject().apply { put("role", "assistant"); put("content", a) })
                    }
                    put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 512)
                    put("temperature", 0.7)
                    put("stream", true)
                }

                val request = Request.Builder()
                    .url(GROQ_BASE_URL)
                    .header("Authorization", "Bearer $GROQ_API_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    response.body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ") && line != "data: [DONE]") {
                                try {
                                    val chunk = JSONObject(line.removePrefix("data: "))
                                    val content = chunk.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("delta")
                                        .optString("content", "")
                                    if (content.isNotEmpty()) {
                                        withContext(Dispatchers.Main) { onChunk(content) }
                                    }
                                } catch (e: Exception) { /* skip malformed chunks */ }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no streaming: ${e.message}")
                withContext(Dispatchers.Main) { onChunk(getOfflineFallback(userMessage)) }
            }
        }
    }
}

/**
 * Estatísticas de performance por modelo (dNaty tracking)
 */
data class ModelStats(
    var totalCalls: Int = 0,
    var successCalls: Int = 0,
    var totalLatencyMs: Long = 0L,
    var failureCount: Int = 0
) {
    val successRate: Double get() = if (totalCalls == 0) 1.0 else successCalls.toDouble() / totalCalls
    val avgLatencyMs: Long get() = if (successCalls == 0) 0L else totalLatencyMs / successCalls

    fun recordSuccess(latencyMs: Long) {
        totalCalls++
        successCalls++
        totalLatencyMs += latencyMs
    }

    fun recordFailure() {
        totalCalls++
        failureCount++
    }
}
