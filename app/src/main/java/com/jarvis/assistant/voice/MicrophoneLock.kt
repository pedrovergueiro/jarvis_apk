package com.jarvis.assistant.voice

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutex global do microfone.
 * Garante que apenas um componente usa o mic por vez.
 * Quando TTS está falando → mic bloqueado → nada ouve.
 */
object MicrophoneLock {
    private val locked = AtomicBoolean(false)

    fun lock() = locked.set(true)
    fun unlock() = locked.set(false)
    fun isLocked() = locked.get()
}
