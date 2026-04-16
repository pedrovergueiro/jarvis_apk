package com.jarvis.assistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.memory.MemoryManager
import com.jarvis.assistant.service.JarvisService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private lateinit var memoryManager: MemoryManager

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startJarvisService() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.jarvis.STATUS_UPDATE" -> updateStatus(intent.getStringExtra("status") ?: return)
                "com.jarvis.LOG_UPDATE" -> addLog(intent.getStringExtra("message") ?: return)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as JarvisApplication
        memoryManager = MemoryManager(app.database)

        setupRecyclerView()
        setupClickListeners()
        loadRecentLogs()
        checkPermissionsAndStart()
    }

    private fun setupClickListeners() {
        // Orb principal — ativa escuta manual
        binding.btnActivate.setOnClickListener {
            if (!JarvisService.isRunning) {
                startJarvisService()
                return@setOnClickListener
            }
            val intent = Intent(this, JarvisService::class.java).apply {
                action = JarvisService.ACTION_MANUAL_LISTEN
            }
            startService(intent)
            // Feedback visual imediato
            updateStatus("LISTENING")
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = logAdapter
        }
    }

    private fun loadRecentLogs() {
        lifecycleScope.launch {
            memoryManager.getRecentContext().forEach { (user, assistant) ->
                logAdapter.addLog("Você: $user")
                if (assistant.isNotBlank()) logAdapter.addLog("Jarvis: $assistant")
            }
            if (logAdapter.itemCount > 0) {
                binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startJarvisService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java).apply {
            action = JarvisService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            val (statusText, orbColor, label) = when (status) {
                "ACTIVE"     -> Triple("Diga 'Jarvis' ou toque", 0xFF00BCD4.toInt(), "Toque")
                "LISTENING"  -> Triple("Ouvindo...", 0xFF4CAF50.toInt(), "Ouvindo")
                "PROCESSING" -> Triple("Processando...", 0xFFFF9800.toInt(), "...")
                "SPEAKING"   -> Triple("Falando...", 0xFF9C27B0.toInt(), "Falando")
                "PAUSED"     -> Triple("Pausado", 0xFF607D8B.toInt(), "Pausado")
                "STOPPED"    -> Triple("Inativo — toque para iniciar", 0xFF455A64.toInt(), "Iniciar")
                else         -> Triple(status, 0xFF00BCD4.toInt(), "Toque")
            }

            binding.tvStatus.text = statusText
            binding.tvOrbLabel.text = label
            binding.btnActivate.setCardBackgroundColor(orbColor)

            // Animação de pulso quando ouvindo
            when (status) {
                "LISTENING" -> {
                    val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
                    binding.orbRing.startAnimation(pulse)
                }
                else -> binding.orbRing.clearAnimation()
            }
        }
    }

    private fun addLog(message: String) {
        runOnUiThread {
            logAdapter.addLog(message)
            binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.jarvis.STATUS_UPDATE")
            addAction("com.jarvis.LOG_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        // Atualizar status ao voltar para o app
        updateStatus(if (JarvisService.isRunning) "ACTIVE" else "STOPPED")
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }
}
