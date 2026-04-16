package com.jarvis.assistant.commands

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.memory.MemoryManager

/**
 * Roteador de intenções — detecta comandos por palavras-chave simples.
 * Sem regex complexo. Rápido e confiável.
 */
class IntentRouter(
    private val context: Context,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "IntentRouter"
    }

    private val executor = CommandExecutor(context)
    private val automationEngine = AutomationEngine(context)

    /**
     * Detecta intenção por palavras-chave.
     * Retorna null se não reconhecer → vai para IA.
     */
    fun route(command: String): DetectedIntent? {
        val lower = command.lowercase().trim()

        return when {
            // ── Abrir apps ──────────────────────────────────────────────────
            lower.contains("abre") || lower.contains("abrir") || lower.contains("abre o") ||
            lower.contains("abrir o") || lower.contains("lança") || lower.contains("inicia") -> {
                val app = extractAfterKeyword(lower, listOf("abre o", "abre a", "abre", "abrir o", "abrir a", "abrir", "lança", "inicia"))
                if (app.isNotBlank()) DetectedIntent("OPEN_APP", mapOf("app" to app), command) else null
            }

            // ── WhatsApp ─────────────────────────────────────────────────────
            lower.contains("whatsapp") && (lower.contains("manda") || lower.contains("envia") ||
            lower.contains("mensagem") || lower.contains("fala")) -> {
                val contact = extractContact(lower)
                DetectedIntent("SEND_WHATSAPP", mapOf("contact" to contact), command)
            }

            // ── Notificações ─────────────────────────────────────────────────
            lower.contains("notificaç") || lower.contains("o que tem de novo") ||
            lower.contains("minhas mensagens") -> {
                DetectedIntent("READ_NOTIFICATIONS", emptyMap(), command)
            }

            // ── Volume ───────────────────────────────────────────────────────
            (lower.contains("volume") || lower.contains("som")) &&
            (lower.contains("aumenta") || lower.contains("sobe") || lower.contains("mais alto")) -> {
                DetectedIntent("SET_VOLUME", mapOf("action" to "up"), command)
            }
            (lower.contains("volume") || lower.contains("som")) &&
            (lower.contains("diminui") || lower.contains("baixa") || lower.contains("menos")) -> {
                DetectedIntent("SET_VOLUME", mapOf("action" to "down"), command)
            }
            lower.contains("muta") || lower.contains("silencia") || lower.contains("sem som") -> {
                DetectedIntent("SET_VOLUME", mapOf("action" to "mute"), command)
            }

            // ── Brilho ───────────────────────────────────────────────────────
            lower.contains("brilho") && (lower.contains("aumenta") || lower.contains("sobe") || lower.contains("mais")) -> {
                DetectedIntent("SET_BRIGHTNESS", mapOf("action" to "up"), command)
            }
            lower.contains("brilho") && (lower.contains("diminui") || lower.contains("baixa") || lower.contains("menos")) -> {
                DetectedIntent("SET_BRIGHTNESS", mapOf("action" to "down"), command)
            }

            // ── Lembrete / Alarme / Timer ─────────────────────────────────────
            lower.contains("lembrete") || lower.contains("me lembra") -> {
                val text = extractAfterKeyword(lower, listOf("lembrete de", "lembrete para", "lembrete", "me lembra de", "me lembra"))
                DetectedIntent("CREATE_REMINDER", mapOf("text" to text), command)
            }
            lower.contains("alarme") -> {
                DetectedIntent("CREATE_REMINDER", mapOf("text" to command), command)
            }
            lower.contains("timer") || lower.contains("cronômetro") -> {
                DetectedIntent("CREATE_REMINDER", mapOf("text" to command), command)
            }

            // ── Busca ─────────────────────────────────────────────────────────
            lower.contains("busca") || lower.contains("pesquisa") ||
            lower.contains("googla") || lower.contains("procura") -> {
                val query = extractAfterKeyword(lower, listOf("busca no google", "busca na internet", "busca", "pesquisa", "googla", "procura"))
                DetectedIntent("WEB_SEARCH", mapOf("query" to query), command)
            }

            // ── Modo foco ─────────────────────────────────────────────────────
            lower.contains("modo foco") || lower.contains("não perturbe") ||
            lower.contains("silêncio total") -> {
                DetectedIntent("FOCUS_MODE", emptyMap(), command)
            }

            // ── Resumo do dia ─────────────────────────────────────────────────
            lower.contains("resumo do dia") || lower.contains("o que aconteceu hoje") ||
            lower.contains("minha agenda") -> {
                DetectedIntent("DAILY_SUMMARY", emptyMap(), command)
            }

            // ── Ligar ─────────────────────────────────────────────────────────
            (lower.contains("liga") || lower.contains("ligar") || lower.contains("chama")) &&
            (lower.contains("para") || lower.contains("pro") || lower.contains("pra")) -> {
                val contact = extractContact(lower)
                DetectedIntent("MAKE_CALL", mapOf("contact" to contact), command)
            }

            // ── Lanterna ─────────────────────────────────────────────────────
            lower.contains("lanterna") || lower.contains("flash") -> {
                DetectedIntent("TOGGLE_FLASHLIGHT", emptyMap(), command)
            }

            // ── Câmera ────────────────────────────────────────────────────────
            lower.contains("câmera") || lower.contains("camera") || lower.contains("tira foto") ||
            lower.contains("tira uma foto") -> {
                DetectedIntent("OPEN_CAMERA", emptyMap(), command)
            }

            // ── Status / hora / data ──────────────────────────────────────────
            lower.contains("que horas") || lower.contains("hora agora") -> {
                DetectedIntent("GET_TIME", emptyMap(), command)
            }
            lower.contains("que dia") || lower.contains("data hoje") -> {
                DetectedIntent("GET_DATE", emptyMap(), command)
            }

            else -> null
        }
    }

    suspend fun execute(intent: DetectedIntent, originalCommand: String): String {
        Log.d(TAG, "Executando: ${intent.type} params=${intent.params}")
        return try {
            when (intent.type) {
                "OPEN_APP" -> executor.openApp(intent.params["app"] ?: "")
                "SEND_WHATSAPP" -> executor.sendWhatsApp(intent.params["contact"] ?: "")
                "READ_NOTIFICATIONS" -> executor.readNotifications()
                "SET_VOLUME" -> executor.setVolume(intent.params["action"] ?: "up", null)
                "SET_BRIGHTNESS" -> executor.setBrightness(intent.params["action"] ?: "up", null)
                "CREATE_REMINDER" -> executor.createReminder(intent.params["text"] ?: originalCommand)
                "WEB_SEARCH" -> executor.webSearch(intent.params["query"] ?: originalCommand)
                "FOCUS_MODE" -> executor.toggleFocusMode()
                "DAILY_SUMMARY" -> executor.getDailySummary(memoryManager)
                "MAKE_CALL" -> executor.makeCall(intent.params["contact"] ?: "")
                "TOGGLE_FLASHLIGHT" -> executor.toggleFlashlight()
                "OPEN_CAMERA" -> {
                    automationEngine.execute("abre a câmera")
                }
                "GET_TIME" -> {
                    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
                    "São ${fmt.format(java.util.Date())}."
                }
                "GET_DATE" -> {
                    val fmt = java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", java.util.Locale("pt", "BR"))
                    fmt.format(java.util.Date())
                }
                else -> "Comando não reconhecido."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar ${intent.type}: ${e.message}")
            "Não consegui executar isso. ${e.message}"
        }
    }

    private fun extractAfterKeyword(text: String, keywords: List<String>): String {
        for (kw in keywords.sortedByDescending { it.length }) {
            val idx = text.indexOf(kw)
            if (idx >= 0) {
                val after = text.substring(idx + kw.length).trim()
                if (after.isNotBlank()) return after
            }
        }
        return text
    }

    private fun extractContact(text: String): String {
        val contactKeywords = listOf("para o", "para a", "para", "pro", "pra", "ao", "à")
        for (kw in contactKeywords) {
            val idx = text.indexOf(kw)
            if (idx >= 0) {
                val after = text.substring(idx + kw.length).trim()
                    .replace(Regex("\\s+(no|pelo|via|no|whatsapp|zap).*"), "")
                    .trim()
                if (after.isNotBlank()) return after
            }
        }
        return ""
    }
}

data class DetectedIntent(
    val type: String,
    val params: Map<String, String>,
    val originalCommand: String
)
