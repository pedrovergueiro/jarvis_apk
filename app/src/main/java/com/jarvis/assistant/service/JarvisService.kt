package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AIEngine
import com.jarvis.assistant.commands.IntentRouter
import com.jarvis.assistant.memory.MemoryManager
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.voice.STTEngine
import com.jarvis.assistant.voice.TTSEngine
import com.jarvis.assistant.voice.WakeWordDetector
import kotlinx.coroutines.*

class JarvisService : Service() {

    companion object {
        const val ACTION_START = "com.jarvis.START"
        const val ACTION_STOP = "com.jarvis.STOP"
        const val ACTION_TOGGLE = "com.jarvis.TOGGLE"
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var sttEngine: STTEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var aiEngine: AIEngine
    private lateinit var intentRouter: IntentRouter
    private lateinit var memoryManager: MemoryManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isListening = false
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        initComponents()
        acquireWakeLock()
    }

    private fun initComponents() {
        val app = application as JarvisApplication
        ttsEngine = app.ttsEngine
        aiEngine = app.aiEngine
        memoryManager = MemoryManager(app.database)
        intentRouter = IntentRouter(this, memoryManager)

        sttEngine = STTEngine(this) { transcription ->
            if (transcription.isNotBlank()) {
                handleVoiceInput(transcription)
            }
        }

        wakeWordDetector = WakeWordDetector(this) {
            onWakeWordDetected()
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JarvisService::WakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10 min max, renovado automaticamente
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE -> if (isListening) pauseListening() else resumeListening()
            else -> startForegroundService()
        }
        return START_STICKY // Reinicia automaticamente se morrer
    }

    private fun startForegroundService() {
        isRunning = true
        startForeground(JarvisApplication.NOTIFICATION_ID, buildNotification("Aguardando wake word..."))
        wakeWordDetector.start()
        broadcastStatus("ACTIVE")
    }

    private fun onWakeWordDetected() {
        if (isProcessing) return
        isListening = true
        updateNotification("Ouvindo...")
        broadcastStatus("LISTENING")
        ttsEngine.speak("Sim?", priority = true)
        sttEngine.startListening()
    }

    private fun handleVoiceInput(text: String) {
        if (isProcessing) return
        isProcessing = true
        isListening = false
        sttEngine.stopListening()
        updateNotification("Processando: $text")
        broadcastStatus("PROCESSING")
        broadcastLog("Você: $text")

        serviceScope.launch {
            try {
                // Salvar no histórico
                memoryManager.saveCommand(text)

                // Roteamento de intenção
                val intent = intentRouter.route(text)

                if (intent != null) {
                    // Comando direto (abrir app, volume, etc.)
                    val result = intentRouter.execute(intent, text)
                    speak(result)
                } else {
                    // Resposta via IA
                    val context = memoryManager.getRecentContext()
                    val response = aiEngine.chat(text, context)
                    memoryManager.saveResponse(text, response)
                    speak(response)
                }
            } catch (e: Exception) {
                speak("Desculpe, ocorreu um erro. Tente novamente.")
            } finally {
                isProcessing = false
                updateNotification("Aguardando wake word...")
                broadcastStatus("ACTIVE")
                // Volta a escutar wake word
                wakeWordDetector.start()
            }
        }
    }

    private suspend fun speak(text: String) {
        withContext(Dispatchers.Main) {
            broadcastLog("Jarvis: $text")
            ttsEngine.speak(text)
        }
    }

    private fun pauseListening() {
        isListening = false
        wakeWordDetector.stop()
        sttEngine.stopListening()
        updateNotification("Pausado")
        broadcastStatus("PAUSED")
    }

    private fun resumeListening() {
        wakeWordDetector.start()
        updateNotification("Aguardando wake word...")
        broadcastStatus("ACTIVE")
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Jarvis Assistant")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_pause, "Pausar", toggleIntent)
            .addAction(R.drawable.ic_stop, "Parar", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(JarvisApplication.NOTIFICATION_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent("com.jarvis.STATUS_UPDATE").apply {
            putExtra("status", status)
        })
    }

    private fun broadcastLog(message: String) {
        sendBroadcast(Intent("com.jarvis.LOG_UPDATE").apply {
            putExtra("message", message)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        wakeWordDetector.stop()
        sttEngine.stopListening()
        ttsEngine.shutdown()
        if (wakeLock.isHeld) wakeLock.release()
        broadcastStatus("STOPPED")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
