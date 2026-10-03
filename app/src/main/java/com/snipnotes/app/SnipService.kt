package com.snipnotes.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Background capture, two ways:
 *
 * 1. Selection events — many apps report TYPE_VIEW_TEXT_SELECTION_CHANGED with the selected
 *    text. When the selection stops changing we append it to the notes.
 *
 * 2. Selection toolbar — Chrome and many other apps do NOT report selections on read-only text.
 *    For those we watch for the floating "Copy · Select all · Share · ⋮" toolbar. Once it has
 *    been on screen and still for a moment (you finished selecting), we open its ⋮ menu and tap
 *    our own "Save to notes" entry, which hands the selected text to SaveTextActivity.
 */
class SnipService : AccessibilityService() {

    private data class Pending(val pkg: String, val text: String)
    private data class Toolbar(val pkg: String, val bounds: Rect, val visible: Boolean, val editable: Boolean, val window: AccessibilityWindowInfo)

    private val handler = Handler(Looper.getMainLooper())

    // ---- path 1: selection events -----------------------------------------------------
    private var pending: Pending? = null
    private val commit = Runnable { pending?.let { save(it) }; pending = null }
    private var lastText: String? = null
    private var lastPkg: String? = null
    private var lastTime = 0L
    /** Apps where selection events worked; the toolbar path is skipped there. */
    private val eventCapable = HashSet<String>()

    // ---- path 2: selection toolbar ------------------------------------------------------
    private var polling = false
    private var tbKey: String? = null          // pkg + bounds of the visible toolbar
    private var tbStableSince = 0L
    private var episodeHandled = false
    private var suppressUntil = 0L             // ignore the toolbar re-appearing after our own tap
    private var busy = false
    private val poll = object : Runnable {
        override fun run() { pollToolbar() }
    }

    private val sys: Resources = Resources.getSystem()
    private val copyLabels by lazy { labels(android.R.string.copy, "Copy") }
    private val pasteLabels by lazy { labels(android.R.string.paste, "Paste") + labels(android.R.string.cut, "Cut") }
    private val toolbarHints by lazy {
        labels(android.R.string.selectAll, "Select all") + setOf("share", "web search", "search", "translate", "select all")
    }
    private val moreLabels = setOf("more options", "more", "overflow", "more actions")
    private val saveLabel by lazy { getString(R.string.process_text_label).lowercase() }

    private fun labels(id: Int, fallback: String): Set<String> =
        setOf(fallback.lowercase(), runCatching { sys.getString(id).lowercase() }.getOrDefault(fallback.lowercase()))

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!Prefs.autoCapture(this)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                onSelectionEvent(event)
                startPolling()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> startPolling()
        }
    }

    // =====================================================================================
    // Path 1
    // =====================================================================================

    private fun onSelectionEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (ignored(pkg) || event.isPassword) return
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
        if (recentSameApp && p.text == prev) return

        val adjusting = recentSameApp && (p.text.contains(prev!!) || prev.contains(p.text))
        eventCapable += p.pkg
        if (!NoteStore.addSnippet(this, p.text, p.pkg, replaceLast = adjusting)) return
        lastText = p.text; lastPkg = p.pkg; lastTime = now

        if (Prefs.showToast(this)) {
            val words = p.text.split(Regex("\\s+")).size
            val msg = if (adjusting) "Snippet updated ($words words)" else "Saved to notes ($words words)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================================
    // Path 2
    // =====================================================================================

    private fun startPolling() {
        if (!Prefs.toolbarCapture(this) || polling) return
        polling = true
        handler.post(poll)
    }

    /** Runs every POLL_MS while a selection toolbar is on screen; stops when it's gone. */
    private fun pollToolbar() {
        if (busy) { handler.postDelayed(poll, POLL_MS); return }
        val tb = findToolbar()
        val now = SystemClock.elapsedRealtime()

        if (tb == null) {
            polling = false
            tbKey = null
            if (now >= suppressUntil) episodeHandled = false
            return
        }

        val key = if (tb.visible) "${tb.pkg}|${tb.bounds.flattenToString()}" else null
        if (key != tbKey) {
            // Toolbar appeared, moved (selection changed) or was hidden while dragging handles.
            tbKey = key
            tbStableSince = now
            if (now >= suppressUntil) episodeHandled = false
        }

        val ready = key != null && !episodeHandled && now - tbStableSince >= TOOLBAR_SETTLE_MS &&
            !(tb.editable && Prefs.skipEditable(this)) && tb.pkg !in eventCapable &&
            Prefs.autoCapture(this) && Prefs.toolbarCapture(this)

        if (ready) {
            episodeHandled = true
            autoSave(tb)
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun findToolbar(): Toolbar? {
        val ws = try { windows } catch (_: Exception) { return null } ?: return null
        val screenH = resources.displayMetrics.heightPixels
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val wb = Rect().also { w.getBoundsInScreen(it) }
            if (wb.height() > screenH / 2) continue           // toolbar is a small popup window
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (ignored(pkg)) continue

            val copy = copyLabels.asSequence()
                .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                .firstOrNull { it.text?.toString()?.trim()?.lowercase() in copyLabels } ?: continue

            val texts = collectTexts(root)
            if (texts.none { it in toolbarHints }) continue
            val editable = texts.any { it in pasteLabels }

            val b = Rect().also { copy.getBoundsInScreen(it) }
            return Toolbar(pkg, b, copy.isVisibleToUser, editable, w)
        }
        return null
    }

    private fun autoSave(tb: Toolbar) {
        busy = true
        // Our entry may already be on the main row on wide screens.
        findSaveItem()?.let { tap(it); done(tb.pkg, true); return }

        val more = findOverflow(tb.window.root) ?: run { done(tb.pkg, false); return }
        tap(more)
        var tries = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                val item = findSaveItem()
                when {
                    item != null -> { tap(item); done(tb.pkg, true) }
                    ++tries < 8 -> handler.postDelayed(this, 150)
                    else -> {
                        // This app's menu doesn't offer "Save to notes": close the menu again.
                        findOverflow(tb.window.root)?.let { tap(it) }
                        done(tb.pkg, false)
                    }
                }
            }
        }, 250)
    }

    private fun done(pkg: String, ok: Boolean) {
        suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
        busy = false
        if (!ok) eventCapable += pkg // stop trying in this app for this session
    }

    private fun findSaveItem(): AccessibilityNodeInfo? {
        val ws = try { windows } catch (_: Exception) { return null } ?: return null
        for (w in ws) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == packageName) continue
            root.findAccessibilityNodeInfosByText(saveLabel)
                .firstOrNull { it.text?.toString()?.trim()?.lowercase() == saveLabel }
                ?.let { return it }
        }
        return null
    }

    private fun findOverflow(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        var fallback: AccessibilityNodeInfo? = null
        walk(root) { n ->
            val desc = n.contentDescription?.toString()?.trim()?.lowercase()
            if (desc != null && desc in moreLabels) return@walk n
            if (fallback == null && n.text.isNullOrEmpty() && n.isClickable &&
                n.className?.toString()?.endsWith("ImageButton") == true) fallback = n
            null
        }?.let { return it }
        return fallback
    }

    private fun collectTexts(root: AccessibilityNodeInfo): Set<String> {
        val out = HashSet<String>()
        walk(root) { n -> n.text?.toString()?.trim()?.lowercase()?.let { out.add(it) }; null }
        return out
    }

    /** Depth-first walk, bounded so a large window can never stall the service. */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val stack = ArrayList<AccessibilityNodeInfo>().apply { add(root) }
        var count = 0
        while (stack.isNotEmpty() && count++ < 300) {
            val n = stack.removeAt(stack.size - 1)
            visit(n)?.let { return it }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun tap(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        // Fallback: a real tap on the centre of the item.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null, null
        )
    }

    private fun ignored(pkg: String) =
        pkg == packageName || pkg in IGNORED_PACKAGES || pkg.contains("inputmethod")

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val SETTLE_MS = 1200L
        private const val TOOLBAR_SETTLE_MS = 1300L   // toolbar must be still this long
        private const val POLL_MS = 300L
        private const val SUPPRESS_MS = 3000L
        private const val MERGE_WINDOW_MS = 20_000L
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
