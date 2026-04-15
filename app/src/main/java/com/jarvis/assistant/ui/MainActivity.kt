package com.jarvis.assistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startJarvisService()
        } else {
            Toast.makeText(this, "Algumas permissões foram negadas. Funcionalidades limitadas.", Toast.LENGTH_LONG).show()
            startJarvisService() // Inicia mesmo assim com funcionalidades reduzidas
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.jarvis.STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: return
                    updateStatus(status)
                }
                "com.jarvis.LOG_UPDATE" -> {
                    val message = intent.getStringExtra("message") ?: return
                    addLog(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as JarvisApplication
        memoryManager = MemoryManager(app.database)

        setupUI()
        setupRecyclerView()
        loadRecentLogs()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        // Botão de ativação manual
        binding.btnActivate.setOnClickListener {
            if (JarvisService.isRunning) {
                val intent = Intent(this, JarvisService::class.java).apply {
                    action = JarvisService.ACTION_TOGGLE
                }
                startService(intent)
            } else {
                startJarvisService()
            }
        }

        // Botão de configurações
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Status inicial
        updateStatus(if (JarvisService.isRunning) "ACTIVE" else "STOPPED")
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
            val commands = memoryManager.getRecentContext()
            commands.forEach { (user, assistant) ->
                logAdapter.addLog("Você: $user")
                logAdapter.addLog("Jarvis: $assistant")
            }
            binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startJarvisService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
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
            val (text, color) = when (status) {
                "ACTIVE" -> Pair("Aguardando wake word...", R.color.status_active)
                "LISTENING" -> Pair("Ouvindo...", R.color.status_listening)
                "PROCESSING" -> Pair("Processando...", R.color.status_processing)
                "PAUSED" -> Pair("Pausado", R.color.status_paused)
                "STOPPED" -> Pair("Inativo", R.color.status_stopped)
                else -> Pair(status, R.color.status_active)
            }
            binding.tvStatus.text = text
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, color))
            binding.btnActivate.text = if (status == "PAUSED" || status == "STOPPED") "Ativar" else "Pausar"

            // Animação de pulso quando ouvindo
            if (status == "LISTENING") {
                binding.statusIndicator.animate().scaleX(1.3f).scaleY(1.3f).setDuration(300)
                    .withEndAction {
                        binding.statusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                    }.start()
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
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }
}
