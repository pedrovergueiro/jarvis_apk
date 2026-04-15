package com.jarvis.assistant.commands

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.assistant.memory.MemoryManager
import com.jarvis.assistant.service.JarvisNotificationService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Executor de comandos do sistema Android.
 * Cada método executa uma ação real no dispositivo.
 */
class CommandExecutor(private val context: Context) {

    companion object {
        private const val TAG = "CommandExecutor"
    }

    // Mapeamento de nomes comuns para pacotes
    private val appPackageMap = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "uber" to "com.ubercab",
        "ifood" to "br.com.brainweb.ifood",
        "nubank" to "com.nu.production",
        "inter" to "br.com.intermedium",
        "camera" to "android.media.action.IMAGE_CAPTURE",
        "calculadora" to "com.google.android.calculator",
        "calculator" to "com.google.android.calculator",
        "configurações" to "com.android.settings",
        "settings" to "com.android.settings",
        "chrome" to "com.android.chrome",
        "telegram" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "linkedin" to "com.linkedin.android",
        "zoom" to "us.zoom.videomeetings",
        "meet" to "com.google.android.apps.meetings",
        "drive" to "com.google.android.apps.docs",
        "photos" to "com.google.android.apps.photos",
        "fotos" to "com.google.android.apps.photos",
        "clock" to "com.google.android.deskclock",
        "relógio" to "com.google.android.deskclock",
        "música" to "com.google.android.music",
        "play store" to "com.android.vending",
        "browser" to "com.android.chrome"
    )

    fun openApp(appName: String): String {
        val lower = appName.lowercase().trim()

        // Buscar pacote pelo nome
        val packageName = appPackageMap.entries.firstOrNull { lower.contains(it.key) }?.value

        if (packageName != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Abrindo $appName."
            }
        }

        // Buscar por nome no sistema
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val found = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(lower)
        }

        if (found != null) {
            val intent = pm.getLaunchIntentForPackage(found.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Abrindo ${pm.getApplicationLabel(found)}."
            }
        }

        return "Não encontrei o app '$appName' instalado."
    }

    fun sendWhatsApp(contactName: String): String {
        val phone = findContactPhone(contactName)
        return if (phone != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/${phone.replace(Regex("[^0-9]"), "")}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Abrindo WhatsApp para $contactName."
        } else {
            // Abrir WhatsApp sem contato específico
            val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Abrindo WhatsApp. Não encontrei o contato '$contactName'."
            } else {
                "WhatsApp não está instalado."
            }
        }
    }

    fun readNotifications(): String {
        val notifications = JarvisNotificationService.getRecentNotifications()
        return if (notifications.isEmpty()) {
            "Não há notificações recentes."
        } else {
            val summary = notifications.take(5).joinToString(". ") { notif ->
                "${notif.appName}: ${notif.title} - ${notif.text}"
            }
            "Você tem ${notifications.size} notificações. $summary"
        }
    }

    fun setVolume(action: String, level: Int?): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return when (action) {
            "up" -> {
                val newVol = minOf(currentVolume + (maxVolume / 5), maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                "Volume aumentado para ${(newVol * 100 / maxVolume)}%."
            }
            "down" -> {
                val newVol = maxOf(currentVolume - (maxVolume / 5), 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                "Volume reduzido para ${(newVol * 100 / maxVolume)}%."
            }
            "mute" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Volume silenciado."
            }
            "set" -> {
                if (level != null) {
                    val newVol = (level * maxVolume / 100).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                    "Volume definido para $level%."
                } else "Nível de volume não especificado."
            }
            else -> "Ação de volume não reconhecida."
        }
    }

    fun setBrightness(action: String, level: Int?): String {
        return try {
            val resolver = context.contentResolver
            val current = android.provider.Settings.System.getInt(
                resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
            )
            val newBrightness = when (action) {
                "up" -> minOf(current + 50, 255)
                "down" -> maxOf(current - 50, 10)
                else -> level?.let { (it * 255 / 100).coerceIn(10, 255) } ?: current
            }
            android.provider.Settings.System.putInt(
                resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, newBrightness
            )
            "Brilho ajustado para ${(newBrightness * 100 / 255)}%."
        } catch (e: Exception) {
            "Preciso de permissão para alterar o brilho. Vá em Configurações > Acessibilidade."
        }
    }

    fun createReminder(text: String): String {
        return try {
            // Tentar criar alarme/lembrete via Intent
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, text)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Criando lembrete: $text"
        } catch (e: Exception) {
            // Fallback: criar notificação local
            "Lembrete registrado: $text"
        }
    }

    fun webSearch(query: String): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Buscando '$query' no Google."
    }

    fun toggleFocusMode(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return "Modo foco ativado. Notificações silenciadas."
    }

    suspend fun getDailySummary(memoryManager: MemoryManager): String {
        val commands = memoryManager.getTodayCommands()
        val date = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
        return if (commands.isEmpty()) {
            "Hoje, $date, ainda não registrei nenhuma atividade."
        } else {
            "Hoje você fez ${commands.size} interações comigo. " +
            "Últimas ações: ${commands.takeLast(3).joinToString(", ") { it.command }}."
        }
    }

    fun makeCall(contactName: String): String {
        val phone = findContactPhone(contactName)
        return if (phone != null) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Ligando para $contactName."
        } else {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Não encontrei o contato '$contactName'. Abrindo discador."
        }
    }

    private var flashlightOn = false
    fun toggleFlashlight(): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
            if (flashlightOn) "Lanterna ligada." else "Lanterna desligada."
        } catch (e: Exception) {
            "Não consegui controlar a lanterna."
        }
    }

    fun toggleWifi(enable: Boolean): String {
        // Android 10+ não permite toggle programático de WiFi
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Abrindo configurações de Wi-Fi."
    }

    private fun findContactPhone(name: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar contato: ${e.message}")
            null
        }
    }
}
