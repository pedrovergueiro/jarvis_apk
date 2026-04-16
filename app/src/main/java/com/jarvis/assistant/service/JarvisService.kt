package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
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
        const val ACTION_MANUAL_LISTEN = "com.jarvis.MANUAL_LISTEN"
        var isRunning = false
        private const val TAG = "JarvisService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var sttEngine: STTEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var aiEngine: AIEngine
    private lateinit var intentRouter: IntentRouter
    private lateinit var memoryManager: MemoryManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var state = State.IDLE

    enum class State { IDLE, WAKE_WORD, LISTENING, PROCESSING, SPEAKING, PAUSED }

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
            if (transcription.isNotBlank() && state == State.LISTENING) {
                handleVoiceInput(transcription)
            }
        }

        wakeWordDetector = WakeWordDetector(this) {
            if (state == State.WAKE_WORD) {
                onWakeWordDetected()
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisService::WakeLock")
        wakeLock.acquire(60 * 60 * 1000L) // 1 hora, renovado
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE -> togglePause()
            ACTION_MANUAL_LISTEN -> onWakeWordDetected()
            else -> {
                startForeground(JarvisApplication.NOTIFICATION_ID, buildNotification("Aguardando..."))
                isRunning = true
                goToWakeWordMode()
            }
        }
        return START_STICKY
    }

    // ─── Estados ──────────────────────────────────────────────────────────────

    private fun goToWakeWordMode() {
        if (state == State.PAUSED) return
        state = State.WAKE_WORD
        updateNotification("Diga 'Jarvis' para ativar")
        broadcastStatus("ACTIVE")
        wakeWordDetector.restart()
    }

    private fun onWakeWordDetected() {
        if (state == State.PROCESSING || state == State.SPEAKING) return
        state = State.LISTENING
        wakeWordDetector.stop()
        updateNotification("Ouvindo...")
        broadcastStatus("LISTENING")

        // Falar "Sim?" e depois ouvir
        ttsEngine.speak("Sim?", priority = true)
        // Aguardar TTS terminar antes de ouvir (evita capturar o próprio "Sim?")
        serviceScope.launch {
            delay(600)
            sttEngine.startListening()
        }
    }

    private fun handleVoiceInput(text: String) {
        if (state != State.LISTENING) return
        state = State.PROCESSING
        sttEngine.stopListening()
        updateNotification("Processando...")
        broadcastStatus("PROCESSING")
        broadcastLog("Você: $text")
        Log.d(TAG, "Processando: $text")

        serviceScope.launch {
            try {
                memoryManager.saveCommand(text)
                memoryManager.extractAndSavePreferences(text)

                // Tentar roteamento direto primeiro (offline, rápido)
                val detectedIntent = intentRouter.route(text)
                val response = if (detectedIntent != null) {
                    intentRouter.execute(detectedIntent, text)
                } else {
                    // IA para respostas complexas
                    val context = memoryManager.getRecentContext()
                    aiEngine.chat(text, context)
                }

                memoryManager.saveResponse(text, response)
                broadcastLog("Jarvis: $response")
                speak(response)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar: ${e.message}")
                speak("Desculpe, ocorreu um erro. Pode repetir?")
            }
        }
    }

    private fun speak(text: String) {
        state = State.SPEAKING
        updateNotification("Falando...")
        broadcastStatus("SPEAKING")
        ttsEngine.speak(text)

        // Voltar a ouvir wake word após falar
        // Estima duração da fala: ~100ms por palavra
        val wordCount = text.split(" ").size
        val estimatedMs = (wordCount * 120L).coerceIn(1000L, 8000L)

        serviceScope.launch {
            delay(estimatedMs)
            if (state == State.SPEAKING) {
                goToWakeWordMode()
            }
        }
    }

    private fun togglePause() {
        if (state == State.PAUSED) {
            goToWakeWordMode()
        } else {
            state = State.PAUSED
            wakeWordDetector.stop()
            sttEngine.stopListening()
            ttsEngine.stopSpeaking()
            updateNotification("Pausado — toque para retomar")
            broadcastStatus("PAUSED")
        }
    }

    // ─── Notificação ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val listenIntent = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisService::class.java).apply { action = ACTION_MANUAL_LISTEN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, JarvisApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Ouvir", listenIntent)
            .addAction(R.drawable.ic_pause, if (state == State.PAUSED) "Retomar" else "Pausar", toggleIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(JarvisApplication.NOTIFICATION_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent("com.jarvis.STATUS_UPDATE").putExtra("status", status))
    }

    private fun broadcastLog(message: String) {
        sendBroadcast(Intent("com.jarvis.LOG_UPDATE").putExtra("message", message))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        state = State.IDLE
        serviceScope.cancel()
        wakeWordDetector.stop()
        sttEngine.stopListening()
        ttsEngine.shutdown()
        if (wakeLock.isHeld) wakeLock.release()
        broadcastStatus("STOPPED")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
