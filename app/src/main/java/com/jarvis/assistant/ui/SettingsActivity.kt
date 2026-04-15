package com.jarvis.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AIEngine

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Modelo de IA
        findPreference<ListPreference>("ai_model")?.apply {
            entries = arrayOf("Rápido (Llama 3.1 8B)", "Inteligente (Llama 70B)", "Ultra (Qwen 32B)", "Auto (dNaty)")
            entryValues = arrayOf(AIEngine.MODEL_FAST, AIEngine.MODEL_SMART, AIEngine.MODEL_ULTRA, "auto")
            value = "auto"
        }

        // Permissão de Notificações
        findPreference<Preference>("notification_access")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            true
        }

        // Permissão de Acessibilidade
        findPreference<Preference>("accessibility_access")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }

        // Permissão de Brilho
        findPreference<Preference>("write_settings")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
            true
        }
    }
}
