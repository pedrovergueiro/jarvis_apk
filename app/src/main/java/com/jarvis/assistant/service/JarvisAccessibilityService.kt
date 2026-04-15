package com.jarvis.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Serviço de acessibilidade para automação de UI.
 * Permite ao Jarvis interagir com outros apps (clicar, digitar, navegar).
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"
        private var instance: JarvisAccessibilityService? = null

        fun getInstance(): JarvisAccessibilityService? = instance

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Serviço de acessibilidade conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitorar eventos para contexto (opcional)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Serviço de acessibilidade interrompido")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Clica em um elemento pelo texto
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()?.let { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } ?: false
    }

    /**
     * Digita texto no campo focado
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Pressiona botão Voltar
     */
    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Vai para Home
     */
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Abre recentes
     */
    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Scroll para baixo na tela atual
     */
    fun scrollDown() {
        val displayMetrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.7f)
            lineTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.3f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
