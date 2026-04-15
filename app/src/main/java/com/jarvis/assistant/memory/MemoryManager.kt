package com.jarvis.assistant.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Gerenciador de memória do Jarvis.
 * Mantém histórico de comandos, preferências e contexto recente.
 */
class MemoryManager(private val db: JarvisDatabase) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val CONTEXT_WINDOW = 10 // Últimas N interações para contexto
    }

    // ─── Comandos ─────────────────────────────────────────────────────────────

    suspend fun saveCommand(command: String, intent: String = "") {
        withContext(Dispatchers.IO) {
            db.commandDao().insert(CommandEntity(command = command, intent = intent))
            cleanOldData()
        }
    }

    suspend fun saveResponse(command: String, response: String, intent: String = "") {
        withContext(Dispatchers.IO) {
            db.commandDao().insert(CommandEntity(command = command, response = response, intent = intent))
        }
    }

    suspend fun getTodayCommands(): List<CommandEntity> {
        return withContext(Dispatchers.IO) {
            val startOfDay = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            db.commandDao().getSince(startOfDay)
        }
    }

    /**
     * Retorna histórico recente como lista de pares (user, assistant)
     * para usar como contexto na IA.
     */
    suspend fun getRecentContext(): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            db.commandDao().getRecent()
                .take(CONTEXT_WINDOW)
                .filter { it.response.isNotBlank() }
                .map { Pair(it.command, it.response) }
                .reversed()
        }
    }

    // ─── Memória de Longo Prazo ────────────────────────────────────────────────

    suspend fun remember(key: String, value: String, category: String = "general") {
        withContext(Dispatchers.IO) {
            db.memoryDao().upsert(MemoryEntity(key = key, value = value, category = category))
            Log.d(TAG, "Memória salva: $key = $value")
        }
    }

    suspend fun recall(key: String): String? {
        return withContext(Dispatchers.IO) {
            db.memoryDao().getByKey(key)?.value
        }
    }

    suspend fun getPreferences(): List<MemoryEntity> {
        return withContext(Dispatchers.IO) {
            db.memoryDao().getByCategory("preference")
        }
    }

    suspend fun getAllMemories(): List<MemoryEntity> {
        return withContext(Dispatchers.IO) {
            db.memoryDao().getAll()
        }
    }

    // ─── Notificações ─────────────────────────────────────────────────────────

    suspend fun saveNotification(appName: String, title: String, text: String) {
        withContext(Dispatchers.IO) {
            db.notificationDao().insert(
                NotificationEntity(appName = appName, title = title, text = text)
            )
        }
    }

    suspend fun getRecentNotifications(): List<NotificationEntity> {
        return withContext(Dispatchers.IO) {
            db.notificationDao().getRecent()
        }
    }

    // ─── Limpeza ──────────────────────────────────────────────────────────────

    private suspend fun cleanOldData() {
        val oneWeekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        db.commandDao().deleteOlderThan(oneWeekAgo)
        db.notificationDao().deleteOlderThan(oneWeekAgo)
    }

    /**
     * Extrai e salva preferências automaticamente das conversas.
     * Ex: "prefiro respostas curtas" → salva preferência
     */
    suspend fun extractAndSavePreferences(command: String) {
        val lower = command.lowercase()
        when {
            lower.contains("prefiro") || lower.contains("gosto de") -> {
                remember("user_preference_${System.currentTimeMillis()}", command, "preference")
            }
            lower.contains("meu nome é") -> {
                val name = command.substringAfter("meu nome é").trim()
                remember("user_name", name, "fact")
            }
            lower.contains("trabalho") || lower.contains("minha profissão") -> {
                remember("user_job", command, "fact")
            }
        }
    }
}
