package com.hadencain.vox.inject

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class InjectResult { INJECTED, NO_TARGET, SECURE_FIELD, FAILED }
data class SelectionInfo(val text: String, val start: Int, val end: Int)

/** Injection + foreground-app detection. `instance` being non-null IS the enabled check —
 *  callers must re-check before every injection (the user can revoke at any time). */
class VoxAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: VoxAccessibilityService? = null
            private set
    }

    @Volatile var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) foregroundPackage = pkg
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    /** Insert at the cursor (or over the in-field selection), preserving surrounding text.
     *  ACTION_SET_TEXT replaces whole-field content, so we splice ourselves. */
    fun injectText(text: String): InjectResult {
        val node = focusedEditable() ?: return InjectResult.NO_TARGET
        if (node.isPassword) return InjectResult.SECURE_FIELD
        if (!node.isEditable) return InjectResult.NO_TARGET
        // Some OEM keyboards (Samsung Messages observed) report the field's hint text as
        // node.text when the field is empty -- e.g. "RCS message". Treat that as empty so
        // we don't splice the transcript into the middle of the placeholder.
        val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start > end) { val t = start; start = end; end = t }
        if (start !in 0..existing.length) start = existing.length
        if (end !in start..existing.length) end = start
        val combined = existing.substring(0, start) + text + existing.substring(end)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) ok = pasteFallback(node, text)
        if (ok) {
            val cursor = start + text.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
        }
        return if (ok) InjectResult.INJECTED else InjectResult.FAILED
    }

    /** Clipboard + ACTION_PASTE for fields where SET_TEXT misbehaves (e.g. some WebViews). */
    private fun pasteFallback(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val saved = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("vox", text))
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            // `saved` being null doesn't mean the clipboard was empty -- clipboard access can
            // be denied/return null for reasons unrelated to actual content (e.g. the reading
            // app not being default IME/in focus on some OEM builds). Only restore when we
            // actually captured something; otherwise leave the vox text in place rather than
            // risk wiping a real clipboard we never got to read. Android 12+ already toasts
            // both the read and the write, so the user sees this happen either way.
            if (saved != null) cm.setPrimaryClip(saved)
        }
    }

    /** Read the focused field's selection (AI-edit). start==end -> no selection. */
    fun readSelection(): SelectionInfo? {
        val node = focusedEditable() ?: return null
        if (node.isPassword) return null
        if (!node.isEditable) return null
        val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start > end) { val t = start; start = end; end = t }
        if (start !in 0..existing.length) start = existing.length
        if (end !in start..existing.length) end = start
        return SelectionInfo(existing.substring(start, end), start, end)
    }

    /** Replace the exact range captured at trigger-time (AI-edit EDIT route). */
    fun replaceSelection(sel: SelectionInfo, newText: String): InjectResult {
        val node = focusedEditable() ?: return InjectResult.NO_TARGET
        if (node.isPassword) return InjectResult.SECURE_FIELD
        if (!node.isEditable) return InjectResult.NO_TARGET
        val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        if (sel.end > existing.length ||
            existing.substring(sel.start, sel.end) != sel.text) return InjectResult.NO_TARGET
        val combined = existing.substring(0, sel.start) + newText + existing.substring(sel.end)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) InjectResult.INJECTED else InjectResult.FAILED
    }
}
