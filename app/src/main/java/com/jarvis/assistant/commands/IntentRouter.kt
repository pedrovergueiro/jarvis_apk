package com.jarvis.assistant.commands

import android.content.Context
import android.util.Log
import com.jarvis.assistant.memory.MemoryManager
import org.json.JSONObject

/**
 * Roteador de intenções: interpreta comandos de voz e direciona para o executor correto.
 * Funciona offline (regex + keywords) para comandos básicos.
 * Usa IA apenas para intenções ambíguas.
 */
class IntentRouter(
    private val context: Context,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "IntentRouter"
    }

    private val executor = CommandExecutor(context)

    // Mapeamento de padrões para intenções
    private val intentPatterns = listOf(
        // Abrir aplicativos
        IntentPattern(
            intent = "OPEN_APP",
            patterns = listOf("abr[ei]r?\\s+(.+)", "abre\\s+(.+)", "abrir\\s+(.+)", "lança\\s+(.+)", "inicia\\s+(.+)"),
            extractors = listOf { m -> mapOf("app" to m.groupValues.getOrNull(1)?.trim()) }
        ),
        // WhatsApp
        IntentPattern(
            intent = "SEND_WHATSAPP",
            patterns = listOf(
                "manda?r?\\s+mensagem\\s+(?:no|para|pro)\\s+(.+?)\\s+(?:no\\s+)?whatsapp",
                "whatsapp\\s+(?:para|pro)\\s+(.+?)\\s+(?:diz|fala|manda)",
                "manda?r?\\s+(.+?)\\s+(?:no|pelo)\\s+whatsapp"
            ),
            extractors = listOf { m -> mapOf("contact" to m.groupValues.getOrNull(1)?.trim()) }
        ),
        // Notificações
        IntentPattern(
            intent = "READ_NOTIFICATIONS",
            patterns = listOf("l[eê]r?\\s+notifica[çc][õo]es?", "o que\\s+(?:tem|há)\\s+de\\s+novo", "minhas\\s+notifica[çc][õo]es?"),
            extractors = listOf { _ -> emptyMap() }
        ),
        // Volume
        IntentPattern(
            intent = "SET_VOLUME",
            patterns = listOf(
                "(?:aumenta|sobe|eleva)\\s+(?:o\\s+)?volume",
                "(?:diminui|baixa|reduz)\\s+(?:o\\s+)?volume",
                "volume\\s+(\\d+)",
                "(?:muta|silencia|mudo)"
            ),
            extractors = listOf { m ->
                val action = when {
                    m.value.contains(Regex("aumenta|sobe|eleva")) -> "up"
                    m.value.contains(Regex("diminui|baixa|reduz")) -> "down"
                    m.value.contains(Regex("muta|silencia|mudo")) -> "mute"
                    else -> "set"
                }
                mapOf("action" to action, "level" to (m.groupValues.getOrNull(1) ?: ""))
            }
        ),
        // Brilho
        IntentPattern(
            intent = "SET_BRIGHTNESS",
            patterns = listOf(
                "(?:aumenta|sobe)\\s+(?:o\\s+)?brilho",
                "(?:diminui|baixa)\\s+(?:o\\s+)?brilho",
                "brilho\\s+(\\d+)"
            ),
            extractors = listOf { m ->
                val action = if (m.value.contains(Regex("aumenta|sobe"))) "up" else "down"
                mapOf("action" to action, "level" to (m.groupValues.getOrNull(1) ?: ""))
            }
        ),
        // Lembrete / Alarme
        IntentPattern(
            intent = "CREATE_REMINDER",
            patterns = listOf(
                "cria?r?\\s+lembrete\\s+(?:para|de)\\s+(.+)",
                "me\\s+lembra?\\s+(?:de|que)\\s+(.+)",
                "alarme\\s+(?:para|às?)\\s+(.+)",
                "timer\\s+(?:de|para)\\s+(\\d+)\\s*(?:minutos?|horas?|segundos?)"
            ),
            extractors = listOf { m -> mapOf("text" to m.groupValues.getOrNull(1)?.trim()) }
        ),
        // Busca na internet
        IntentPattern(
            intent = "WEB_SEARCH",
            patterns = listOf(
                "busca?r?\\s+(?:na\\s+internet|no\\s+google)?\\s+(.+)",
                "pesquisa?r?\\s+(.+)",
                "googla?r?\\s+(.+)",
                "procura?r?\\s+(.+)"
            ),
            extractors = listOf { m -> mapOf("query" to m.groupValues.getOrNull(1)?.trim()) }
        ),
        // Modo foco
        IntentPattern(
            intent = "FOCUS_MODE",
            patterns = listOf(
                "modo\\s+foco",
                "não\\s+perturbe",
                "silêncio\\s+total",
                "foco\\s+total"
            ),
            extractors = listOf { _ -> emptyMap() }
        ),
        // Resumo do dia
        IntentPattern(
            intent = "DAILY_SUMMARY",
            patterns = listOf(
                "resumo\\s+do\\s+dia",
                "o\\s+que\\s+aconteceu\\s+hoje",
                "meu\\s+dia",
                "agenda\\s+de\\s+hoje"
            ),
            extractors = listOf { _ -> emptyMap() }
        ),
        // Ligar para contato
        IntentPattern(
            intent = "MAKE_CALL",
            patterns = listOf(
                "liga?r?\\s+(?:para|pro)\\s+(.+)",
                "chama?r?\\s+(.+)",
                "telefona?r?\\s+(?:para|pro)\\s+(.+)"
            ),
            extractors = listOf { m -> mapOf("contact" to m.groupValues.getOrNull(1)?.trim()) }
        ),
        // Flashlight
        IntentPattern(
            intent = "TOGGLE_FLASHLIGHT",
            patterns = listOf("lanterna", "flash", "luz\\s+(?:da\\s+)?câmera"),
            extractors = listOf { _ -> emptyMap() }
        ),
        // WiFi / Bluetooth
        IntentPattern(
            intent = "TOGGLE_WIFI",
            patterns = listOf("(?:liga|desliga)\\s+(?:o\\s+)?wi.?fi", "wi.?fi\\s+(?:on|off|ligar|desligar)"),
            extractors = listOf { m -> mapOf("action" to if (m.value.contains("liga")) "on" else "off") }
        ),
        // Modo estudo
        IntentPattern(
            intent = "STUDY_MODE",
            patterns = listOf("modo\\s+estudo", "me\\s+explica\\s+(.+)", "explica\\s+(.+)\\s+detalhado"),
            extractors = listOf { m -> mapOf("topic" to m.groupValues.getOrNull(1)?.trim()) }
        )
    )

    /**
     * Tenta identificar a intenção do comando.
     * Retorna null se não encontrar (vai para IA).
     */
    fun route(command: String): DetectedIntent? {
        val lower = command.lowercase().trim()

        for (pattern in intentPatterns) {
            for ((index, regex) in pattern.patterns.withIndex()) {
                val match = Regex(regex, RegexOption.IGNORE_CASE).find(lower)
                if (match != null) {
                    val params = pattern.extractors.getOrNull(index)?.invoke(match) ?: emptyMap()
                    Log.d(TAG, "Intenção detectada: ${pattern.intent} params=$params")
                    return DetectedIntent(pattern.intent, params, command)
                }
            }
        }

        Log.d(TAG, "Nenhuma intenção detectada para: $command")
        return null
    }

    /**
     * Executa a intenção detectada.
     */
    suspend fun execute(intent: DetectedIntent, originalCommand: String): String {
        return try {
            when (intent.type) {
                "OPEN_APP" -> executor.openApp(intent.params["app"] ?: "")
                "SEND_WHATSAPP" -> executor.sendWhatsApp(intent.params["contact"] ?: "")
                "READ_NOTIFICATIONS" -> executor.readNotifications()
                "SET_VOLUME" -> executor.setVolume(intent.params["action"] ?: "up", intent.params["level"]?.toIntOrNull())
                "SET_BRIGHTNESS" -> executor.setBrightness(intent.params["action"] ?: "up", intent.params["level"]?.toIntOrNull())
                "CREATE_REMINDER" -> executor.createReminder(intent.params["text"] ?: originalCommand)
                "WEB_SEARCH" -> executor.webSearch(intent.params["query"] ?: originalCommand)
                "FOCUS_MODE" -> executor.toggleFocusMode()
                "DAILY_SUMMARY" -> executor.getDailySummary(memoryManager)
                "MAKE_CALL" -> executor.makeCall(intent.params["contact"] ?: "")
                "TOGGLE_FLASHLIGHT" -> executor.toggleFlashlight()
                "TOGGLE_WIFI" -> executor.toggleWifi(intent.params["action"] == "on")
                "STUDY_MODE" -> null ?: "Entrando em modo estudo. Pode perguntar o que quiser."
                else -> "Comando não reconhecido."
            } ?: "Ação executada."
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar ${intent.type}: ${e.message}")
            "Não consegui executar essa ação. ${e.message}"
        }
    }
}

data class DetectedIntent(
    val type: String,
    val params: Map<String, String?>,
    val originalCommand: String
)

data class IntentPattern(
    val intent: String,
    val patterns: List<String>,
    val extractors: List<(MatchResult) -> Map<String, String?>>
)
