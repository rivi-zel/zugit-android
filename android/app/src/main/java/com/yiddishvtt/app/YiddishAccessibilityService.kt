package com.yiddishvtt.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class YiddishAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YiddishA11yService"
        const val ACTION_KEYBOARD_STATE = "com.yiddishvtt.app.KEYBOARD_STATE"
        const val EXTRA_KEYBOARD_VISIBLE = "keyboard_visible"

        private var instance: YiddishAccessibilityService? = null

        fun injectText(text: String): Boolean {
            val service = instance ?: return false
            return try {
                service.performTextInjection(text)
            } catch (e: Exception) {
                Log.e(TAG, "Text injection failed", e)
                false
            }
        }

        fun isServiceRunning(): Boolean = instance != null

        // Periodic safety check: TYPE_WINDOWS_CHANGED misses dismiss gestures on
        // some OEM keyboards, so the button gets stuck visible without this.
        fun forceKeyboardRecheck() {
            instance?.forceRecheck()
        }
        
        var lastKeyboardVisible = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        // FLAG_REPORT_VIEW_IDS was set but we never query by view ID, so it
        // only makes the OS serialize IDs on every event for nothing —
        // dropping it lightens accessibility event cost & battery use.
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        checkKeyboardState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                checkKeyboardState()
            }
        }
    }

    // Only true when the IME window itself is present. Focused EditText is
    // unreliable — fields stay focused after the user dismisses the keyboard.
    private fun checkKeyboardState() {
        try {
            var keyboardVisible = false
            val allWindows = windows
            if (allWindows != null) {
                for (window in allWindows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        keyboardVisible = true
                        break
                    }
                }
            }
            updateLastKeyboardState(keyboardVisible)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyboard state", e)
        }
    }

    private fun updateLastKeyboardState(keyboardVisible: Boolean) {
        if (keyboardVisible != lastKeyboardVisible) {
            lastKeyboardVisible = keyboardVisible
            Log.d(TAG, "Keyboard visibility changed: $keyboardVisible")
            broadcastKeyboardState(keyboardVisible)
        }
    }

    private fun broadcastKeyboardState(visible: Boolean) {
        val intent = Intent(ACTION_KEYBOARD_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_KEYBOARD_VISIBLE, visible)
        }
        sendBroadcast(intent)
    }

    fun forceRecheck() {
        try {
            var keyboardVisible = false
            val allWindows = windows
            if (allWindows != null) {
                for (window in allWindows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        keyboardVisible = true
                        break
                    }
                }
            }
            lastKeyboardVisible = keyboardVisible
            broadcastKeyboardState(keyboardVisible)
        } catch (e: Exception) {
            Log.e(TAG, "forceRecheck failed", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    // Search APPLICATION windows only so the floating overlay doesn't shadow
    // the real text target.
    private fun performTextInjection(text: String): Boolean {
        val allWindows = windows
        if (allWindows != null) {
            for (window in allWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                val target = findInputTarget(root)
                if (target != null && injectIntoNode(target, text)) {
                    Log.d(TAG, "Injected via application window")
                    return true
                }
            }
        }

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val target = findInputTarget(rootNode)
            if (target != null && injectIntoNode(target, text)) {
                Log.d(TAG, "Injected via active window root")
                return true
            }
        }

        Log.w(TAG, "No editable target found for injection")
        return false
    }

    // Prefer focused editable; fall back to the last editable (chat input pattern).
    private fun findInputTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, editables)
        return editables.lastOrNull()
    }

    private fun injectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Paste preserves IME state; SET_TEXT is a fallback that replaces content.
        if (pasteIntoNode(node, text)) return true
        return setTextOnNode(node, text)
    }

    private fun pasteIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("yiddish-vtt", text))
            // Move cursor to end so paste appends rather than replacing selection.
            val textLen = node.text?.length ?: 0
            if (textLen > 0) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textLen)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLen)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (ok) Log.d(TAG, "ACTION_PASTE OK")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "pasteIntoNode error", e)
            false
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val existing = node.text?.toString() ?: ""
            val combined = if (existing.isEmpty()) text else "$existing $text"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) Log.d(TAG, "ACTION_SET_TEXT OK")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "setTextOnNode error", e)
            false
        }
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        val n = node ?: return
        if (n.isEditable) result.add(AccessibilityNodeInfo.obtain(n))
        for (i in 0 until n.childCount) {
            collectEditableNodes(n.getChild(i), result)
        }
    }
}
