package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.jarvis.assistant.memory.JarvisDatabase
import com.jarvis.assistant.ai.AIEngine
import com.jarvis.assistant.voice.TTSEngine

class JarvisApplication : Application() {

    companion object {
        lateinit var instance: JarvisApplication
            private set

        const val NOTIFICATION_CHANNEL_ID = "jarvis_service"
        const val NOTIFICATION_CHANNEL_NAME = "Natiele Assistant"
        const val NOTIFICATION_ID = 1001
    }

    lateinit var database: JarvisDatabase
        private set

    lateinit var aiEngine: AIEngine
        private set

    lateinit var ttsEngine: TTSEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        database = JarvisDatabase.getInstance(this)
        aiEngine = AIEngine(this)
        ttsEngine = TTSEngine(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Natiele rodando em segundo plano"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
