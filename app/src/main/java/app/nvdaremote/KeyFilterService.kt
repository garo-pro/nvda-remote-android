package app.nvdaremote

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the app consume hardware key events before Android's own shortcuts (Windows key, Alt+Tab...)
 * see them. It does nothing unless remote control is active.
 */
class KeyFilterService : AccessibilityService() {
    companion object {
        /** Set by MainActivity while it is controlling a remote computer; returns true if consumed. */
        @Volatile var sink: ((KeyEvent) -> Boolean)? = null
        @Volatile var running = false
    }

    override fun onServiceConnected() {
        running = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = sink?.invoke(event) ?: false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }
}
