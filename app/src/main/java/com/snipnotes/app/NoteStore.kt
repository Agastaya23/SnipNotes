package com.snipnotes.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The notes document. Master copy lives in the app's private storage (notes.md);
 * if the user linked an external file (Documents folder, Drive, etc.) every change
 * is mirrored there too.
 */
object NoteStore {
    private const val TAG = "NoteStore"
    private val io = Executors.newSingleThreadExecutor()

    /** Last text appended by auto-capture (including its leading separator), so it can be replaced. */
    private var lastEntry: String? = null
    private var lastSnippet: String? = null
    private var lastSnippetAt = 0L

    private fun file(c: Context) = File(c.filesDir, "notes.md")

    @Synchronized
    fun read(c: Context): String = file(c).let { if (it.exists()) it.readText() else "" }

    /** Replace the whole document (used by the editor in MainActivity). */
    @Synchronized
    fun save(c: Context, text: String) {
        if (text == read(c)) return
        file(c).writeText(text)
        lastEntry = null
        lastSnippet = null
        mirror(c, text)
    }

    /**
     * Add a snippet at the end of the document.
     * @param replaceLast if true and the previous auto-captured entry is still the tail of
     * the document, it is swapped for this one (the user was just adjusting the selection).
     * @return false if skipped because the same text was saved moments ago (e.g. both the
     * background service and the "Save to notes" menu caught the same selection).
     */
    @Synchronized
    fun addSnippet(c: Context, snippet: String, sourcePkg: String?, replaceLast: Boolean = false): Boolean {
        val clean = snippet.trim()
        val now = android.os.SystemClock.elapsedRealtime()
        if (clean == lastSnippet && now - lastSnippetAt < 60_000) return false
        val entry = format(c, snippet, sourcePkg)
        val current = read(c)
        val prev = lastEntry
        val base = if (replaceLast && prev != null && current.endsWith(prev))
            current.dropLast(prev.length) else current
        val sep = when {
            base.isEmpty() -> ""
            base.endsWith("\n\n") -> ""
            base.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val updated = base + sep + entry
        file(c).writeText(updated)
        lastEntry = sep + entry
        lastSnippet = clean
        lastSnippetAt = now
        mirror(c, updated)
        return true
    }

    /** True if [text] looks like the previous snippet with the selection extended or shrunk. */
    @Synchronized
    fun isAdjustment(text: String): Boolean {
        val last = lastSnippet ?: return false
        val t = text.trim()
        if (t == last) return false
        if (android.os.SystemClock.elapsedRealtime() - lastSnippetAt > 20_000) return false
        return t.contains(last) || last.contains(t)
    }

    private fun format(c: Context, snippet: String, pkg: String?): String {
        val sb = StringBuilder(snippet.trim())
        if (Prefs.addSource(c)) {
            val time = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
            val app = pkg?.let { appLabel(c, it) }
            sb.append("\n— ")
            if (app != null) sb.append(app).append(", ")
            sb.append(time)
        }
        return sb.append("\n").toString()
    }

    @Suppress("DEPRECATION")
    fun appLabel(c: Context, pkg: String): String = try {
        val pm: PackageManager = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** Write the full document to the linked external file, off the main thread. */
    private fun mirror(c: Context, text: String) {
        val uri = Prefs.linkedUri(c) ?: return
        val app = c.applicationContext
        io.execute {
            try {
                val out = try { app.contentResolver.openOutputStream(uri, "wt") }
                          catch (_: Exception) { app.contentResolver.openOutputStream(uri, "w") }
                out?.use { it.write(text.toByteArray()) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not write linked file", e)
            }
        }
    }

    /** Read an external file (used when linking an existing doc). */
    fun readUri(c: Context, uri: android.net.Uri): String = try {
        c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    } catch (_: Exception) { "" }

    fun mirrorNow(c: Context) = mirror(c, read(c))
}
