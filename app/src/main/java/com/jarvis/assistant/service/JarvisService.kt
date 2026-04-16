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
        private const val TAG = "JarvisService"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos
        private const val LISTEN_TIMEOUT_MS = 8000L         // 8s para ouvir comando
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
    private var lastInteractionTime: Long = 0L
    private var listenTimeoutJob: Job? = null
    private var idleTimeoutJob: Job? = null

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
            listenTimeoutJob?.cancel()
            if (state == State.LISTENING && transcription.isNotBlank()) {
                handleVoiceInput(transcription)
            } else if (state == State.LISTENING) {
                // Nada ouvido — voltar a aguardar
                speak("Não ouvi nada. Pode repetir?") {
                    goToListeningMode() // Continua ouvindo sem precisar chamar Jarvis de novo
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisService::WakeLock")
        wakeLock.acquire(60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE -> togglePause()
            ACTION_MANUAL_LISTEN -> {
                if (state == State.WAKE_WORD || state == State.IDLE) {
                    onWakeWordDetected()
                }
            }
            else -> {
                if (!isRunning) {
                    startForeground(JarvisApplication.NOTIFICATION_ID, buildNotification("Iniciando..."))
                    isRunning = true
                    goToWakeWordMode()
                }
            }
        }
        return START_STICKY
    }

    // ─── Máquina de estados ───────────────────────────────────────────────────

    private fun goToWakeWordMode() {
        if (state == State.PAUSED) return
        state = State.WAKE_WORD
        listenTimeoutJob?.cancel()
        idleTimeoutJob?.cancel()
        updateNotification("Diga 'Jarvis' para ativar")
        broadcastStatus("ACTIVE")
        wakeWordDetector.restart()
        startIdleTimeout()
    }

    private fun onWakeWordDetected() {
        state = State.LISTENING
        wakeWordDetector.stop()
        idleTimeoutJob?.cancel()
        lastInteractionTime = System.currentTimeMillis()
        updateNotification("Ouvindo...")
        broadcastStatus("LISTENING")

        // Resposta de ativação curta e natural
        val greetings = listOf("Sim?", "Pois não?", "Diga.", "Pode falar.", "Estou ouvindo.")
        val greeting = greetings.random()

        ttsEngine.speak(greeting, priority = true) {
            // Só começa a ouvir DEPOIS que terminar de falar
            goToListeningMode()
        }
    }

    private fun goToListeningMode() {
        if (state != State.LISTENING) return
        sttEngine.startListening()

        // Timeout de escuta — se não ouvir nada em 8s, volta ao wake word
        listenTimeoutJob?.cancel()
        listenTimeoutJob = serviceScope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (state == State.LISTENING) {
                sttEngine.stopListening()
                goToWakeWordMode()
            }
        }
    }

    private fun handleVoiceInput(text: String) {
        if (state != State.LISTENING) return
        state = State.PROCESSING
        sttEngine.stopListening()
        lastInteractionTime = System.currentTimeMillis()
        updateNotification("Processando...")
        broadcastStatus("PROCESSING")
        broadcastLog("Você: $text")
        Log.d(TAG, "Processando: $text")

        serviceScope.launch {
            try {
                memoryManager.saveCommand(text)
                memoryManager.extractAndSavePreferences(text)

                // Roteamento direto (offline, instantâneo)
                val detectedIntent = intentRouter.route(text)
                val response: String = if (detectedIntent != null) {
                    intentRouter.execute(detectedIntent, text)
                } else {
                    // IA com contexto do usuário
                    val history = memoryManager.getRecentContext()
                    val userContext = buildUserContext()
                    aiEngine.chat(text, history, userContext)
                }

                memoryManager.saveResponse(text, response)
                broadcastLog("Jarvis: $response")

                // Após responder, continua ouvindo por mais 30s sem precisar chamar "Jarvis"
                speak(response) {
                    startPostResponseListening()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar: ${e.message}")
                speak("Desculpe, ocorreu um erro. Pode repetir?") {
                    goToListeningMode()
                }
            }
        }
    }

    /**
     * Após responder, fica ouvindo por 30s para perguntas de follow-up.
     * Não precisa chamar "Jarvis" de novo nesse período.
     */
    private fun startPostResponseListening() {
        state = State.LISTENING
        updateNotification("Ouvindo... (pode continuar)")
        broadcastStatus("LISTENING")
        sttEngine.startListening()

        listenTimeoutJob?.cancel()
        listenTimeoutJob = serviceScope.launch {
            delay(30_000L) // 30s de janela para follow-up
            if (state == State.LISTENING) {
                sttEngine.stopListening()
                goToWakeWordMode()
            }
        }
    }

    /**
     * Timeout de inatividade — após 5 min sem interação,
     * exige chamar "Jarvis" novamente.
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (state == State.WAKE_WORD) {
                Log.d(TAG, "Timeout de inatividade")
                updateNotification("Diga 'Jarvis' para ativar")
            }
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        state = State.SPEAKING
        updateNotification("Falando...")
        broadcastStatus("SPEAKING")
        ttsEngine.speak(text, priority = true) {
            onDone?.invoke()
        }
    }

    private suspend fun buildUserContext(): String {
        val memories = memoryManager.getAllMemories()
        if (memories.isEmpty()) return ""
        return memories.take(10).joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun togglePause() {
        if (state == State.PAUSED) {
            goToWakeWordMode()
        } else {
            state = State.PAUSED
            wakeWordDetector.stop()
            sttEngine.stopListening()
            ttsEngine.stopSpeaking()
            listenTimeoutJob?.cancel()
            idleTimeoutJob?.cancel()
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

        val pauseLabel = if (state == State.PAUSED) "Retomar" else "Pausar"

        return NotificationCompat.Builder(this, JarvisApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Ouvir", listenIntent)
            .addAction(R.drawable.ic_pause, pauseLabel, toggleIntent)
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
