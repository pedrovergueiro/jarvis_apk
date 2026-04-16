package com.jarvis.assistant.service

import android.app.Notification
import android.app.NotificationManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class JarvisService : Service() {

    companion object {
        const val ACTION_START = "com.jarvis.START"
        const val ACTION_STOP = "com.jarvis.STOP"
        const val ACTION_TOGGLE = "com.jarvis.TOGGLE"
        const val ACTION_MANUAL_LISTEN = "com.jarvis.MANUAL_LISTEN"
        var isRunning: Boolean = false
        private const val TAG = "NatieleService"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val FOLLOW_UP_WINDOW_MS = 30_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var sttEngine: STTEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var aiEngine: AIEngine
    private lateinit var intentRouter: IntentRouter
    private lateinit var memoryManager: MemoryManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var state: State = State.IDLE
    private var followUpJob: Job? = null

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
            if (state == State.LISTENING) {
                if (transcription.isNotBlank()) {
                    handleVoiceInput(transcription)
                } else {
                    // Nada ouvido — volta a ouvir sem precisar chamar de novo
                    goToListeningMode()
                }
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NatieleService::WakeLock")
        wakeLock.acquire(60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE -> togglePause()
            ACTION_MANUAL_LISTEN -> {
                if (state == State.WAKE_WORD || state == State.IDLE || state == State.PAUSED) {
                    state = State.WAKE_WORD
                    onWakeWordDetected()
                }
            }
            else -> {
                if (!isRunning) {
                    startForeground(JarvisApplication.NOTIFICATION_ID, buildNotification("Aguardando..."))
                    isRunning = true
                    goToWakeWordMode()
                }
            }
        }
        return START_STICKY
    }

    // ─── Estados ──────────────────────────────────────────────────────────────

    private fun goToWakeWordMode() {
        if (state == State.PAUSED) return
        state = State.WAKE_WORD
        followUpJob?.cancel()
        updateNotification("Diga 'Natiele' para ativar")
        broadcastStatus("ACTIVE")
        wakeWordDetector.restart()
    }

    private fun onWakeWordDetected() {
        // CRÍTICO: não ouvir se ainda estiver falando
        if (ttsEngine.isSpeaking()) {
            ttsEngine.stopSpeaking()
        }

        state = State.LISTENING
        wakeWordDetector.stop()
        followUpJob?.cancel()
        updateNotification("Ouvindo...")
        broadcastStatus("LISTENING")

        val greetings = listOf("Sim?", "Diga.", "Pois não?", "Estou aqui.", "Pode falar.")
        ttsEngine.speak(greetings.random(), priority = true) {
            // Só começa a ouvir DEPOIS que terminar de falar
            goToListeningMode()
        }
    }

    private fun goToListeningMode() {
        if (state != State.LISTENING) return
        // Garantir que TTS parou antes de ouvir
        if (ttsEngine.isSpeaking()) {
            serviceScope.launch {
                delay(300L)
                goToListeningMode()
            }
            return
        }
        sttEngine.startListening()
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

                val detectedIntent = intentRouter.route(text)
                val response: String = if (detectedIntent != null) {
                    intentRouter.execute(detectedIntent, text)
                } else {
                    val history = memoryManager.getRecentContext()
                    val userContext = buildUserContext()
                    aiEngine.chat(text, history, userContext)
                }

                memoryManager.saveResponse(text, response)
                broadcastLog("Natiele: $response")

                speak(response) {
                    startFollowUpWindow()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro: ${e.message}")
                speak("Erro ao processar. Pode repetir?") {
                    goToListeningMode()
                }
            }
        }
    }

    /**
     * Após responder, fica ouvindo 30s para follow-up.
     * Não precisa chamar "Natiele" de novo nesse período.
     */
    private fun startFollowUpWindow() {
        state = State.LISTENING
        updateNotification("Ouvindo... (pode continuar)")
        broadcastStatus("LISTENING")
        sttEngine.startListening()

        followUpJob?.cancel()
        followUpJob = serviceScope.launch {
            delay(FOLLOW_UP_WINDOW_MS)
            if (state == State.LISTENING) {
                sttEngine.stopListening()
                goToWakeWordMode()
            }
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        state = State.SPEAKING
        updateNotification("Falando...")
        broadcastStatus("SPEAKING")
        // Para de ouvir ANTES de falar — evita auto-escuta
        sttEngine.stopListening()
        ttsEngine.speak(text, priority = true) {
            onDone?.invoke()
        }
    }

    private suspend fun buildUserContext(): String {
        val memories = memoryManager.getAllMemories()
        if (memories.isEmpty()) return ""
        return memories.take(8).joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun togglePause() {
        if (state == State.PAUSED) {
            // RETOMAR
            state = State.WAKE_WORD
            isRunning = true
            goToWakeWordMode()
        } else {
            // PAUSAR
            state = State.PAUSED
            wakeWordDetector.stop()
            sttEngine.stopListening()
            ttsEngine.stopSpeaking()
            followUpJob?.cancel()
            updateNotification("Pausado — toque Retomar para continuar")
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
        val listenIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisService::class.java).apply { action = ACTION_MANUAL_LISTEN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleLabel = if (state == State.PAUSED) "Retomar" else "Pausar"
        val toggleIcon = if (state == State.PAUSED) R.drawable.ic_mic else R.drawable.ic_pause

        return NotificationCompat.Builder(this, JarvisApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Natiele")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Ouvir", listenIntent)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
