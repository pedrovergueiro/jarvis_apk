package com.jarvis.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.memory.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Serviço de escuta de notificações.
 * Captura notificações de todos os apps e armazena para leitura por voz.
 */
class JarvisNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"

        // Cache em memória das notificações recentes
        private val recentNotifications = mutableListOf<NotificationData>()
        private const val MAX_CACHED = 20

        fun getRecentNotifications(): List<NotificationData> = recentNotifications.toList()
        fun clearNotifications() = recentNotifications.clear()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var memoryManager: MemoryManager

    // Apps para ignorar (sistema, Jarvis)
    private val ignoredPackages = setOf(
        "com.jarvis.assistant",
        "android",
        "com.android.systemui",
        "com.android.phone"
    )

    override fun onCreate() {
        super.onCreate()
        val app = application as JarvisApplication
        memoryManager = MemoryManager(app.database)
        Log.d(TAG, "NotificationListenerService iniciado")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in ignoredPackages) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString("android.title") ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val appName = getAppName(sbn.packageName)

        val data = NotificationData(
            appName = appName,
            packageName = sbn.packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime
        )

        // Adicionar ao cache
        synchronized(recentNotifications) {
            recentNotifications.add(0, data)
            if (recentNotifications.size > MAX_CACHED) {
                recentNotifications.removeAt(recentNotifications.size - 1)
            }
        }

        // Salvar no banco
        scope.launch {
            memoryManager.saveNotification(appName, title, text)
        }

        Log.d(TAG, "Notificação: $appName - $title")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Opcional: marcar como removida
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }
}

data class NotificationData(
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)
