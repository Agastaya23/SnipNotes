package com.snipnotes.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Background capture. Android delivers a TYPE_VIEW_TEXT_SELECTION_CHANGED event whenever
 * text is selected in (most) apps. We wait until the selection stops changing — i.e. you
 * finished dragging the handles — and then append it to the notes document.
 */
class SnipService : AccessibilityService() {

    private data class Pending(val pkg: String, val text: String)

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private val commit = Runnable { pending?.let(::save); pending = null }

    private var lastText: String? = null
    private var lastPkg: String? = null
    private var lastTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        if (!Prefs.autoCapture(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg.contains("inputmethod")) return
        if (event.isPassword) return

        val selected = extractSelection(event) ?: return
        pending = Pending(pkg, selected)
        handler.removeCallbacks(commit)
        handler.postDelayed(commit, SETTLE_MS)
    }

    private fun extractSelection(event: AccessibilityEvent): String? {
        val source: AccessibilityNodeInfo? = try { event.source } catch (_: Exception) { null }
        if (source?.isPassword == true) return null
        if (source?.isEditable == true && Prefs.skipEditable(this)) return null

        var text: CharSequence? = when (event.text.size) {
            0 -> null
            1 -> event.text[0]
            else -> event.text.joinToString("")
        }
        var start = event.fromIndex
        var end = event.toIndex

        // Some apps leave the event empty; fall back to the node itself.
        if (text.isNullOrEmpty() || start < 0 || end < 0 || start == end) {
            val nodeText = source?.text
            val s = source?.textSelectionStart ?: -1
            val e = source?.textSelectionEnd ?: -1
            if (!nodeText.isNullOrEmpty() && s >= 0 && e >= 0 && s != e) {
                text = nodeText; start = s; end = e
            }
        }
        if (text.isNullOrEmpty() || start < 0 || end < 0 || start == end) return null

        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        val out = text.subSequence(a, b).toString().trim()
        return if (out.length < MIN_LENGTH) null else out.take(MAX_LENGTH)
    }

    private fun save(p: Pending) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastText
        val recentSameApp = prev != null && p.pkg == lastPkg && now - lastTime < MERGE_WINDOW_MS

        if (recentSameApp && p.text == prev) return  // duplicate event, already saved

        // Selection was extended/shrunk shortly after saving → replace instead of duplicating.
        val adjusting = recentSameApp && (p.text.contains(prev!!) || prev.contains(p.text))

        if (!NoteStore.addSnippet(this, p.text, p.pkg, replaceLast = adjusting)) return
        lastText = p.text; lastPkg = p.pkg; lastTime = now

        if (Prefs.showToast(this)) {
            val words = p.text.split(Regex("\\s+")).size
            val msg = if (adjusting) "Snippet updated ($words words)" else "Saved to notes ($words words)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(commit)
        super.onDestroy()
    }

    companion object {
        private const val SETTLE_MS = 1200L          // selection must be still this long
        private const val MERGE_WINDOW_MS = 20_000L  // treat re-selection within 20 s as an adjustment
        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 50_000
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
        )
    }
}
