package com.jarvis.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import java.text.SimpleDateFormat
import java.util.*

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logs = mutableListOf<LogEntry>()

    data class LogEntry(val message: String, val timestamp: Long = System.currentTimeMillis())

    inner class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tv_log_message)
        val tvTime: TextView = view.findViewById(R.id.tv_log_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = logs[position]
        holder.tvMessage.text = entry.message
        holder.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))

        // Estilo diferente para Jarvis vs Usuário
        val isJarvis = entry.message.startsWith("Jarvis:")
        holder.tvMessage.setTextColor(
            if (isJarvis) 0xFF00BCD4.toInt() else 0xFFFFFFFF.toInt()
        )
    }

    override fun getItemCount() = logs.size

    fun addLog(message: String) {
        logs.add(LogEntry(message))
        if (logs.size > 100) logs.removeAt(0) // Manter máximo 100 logs
        notifyItemInserted(logs.size - 1)
    }

    fun clearLogs() {
        logs.clear()
        notifyDataSetChanged()
    }
}
